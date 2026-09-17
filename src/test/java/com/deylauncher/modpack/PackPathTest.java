package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a modpack file actually lands, and when it can't land at all.
 *
 * <p>Two real cross-platform bugs are pinned down here:
 * <ul>
 *   <li>a pack that spells a game folder {@code Mods/} used to install into a folder Minecraft never
 *       reads on Linux/macOS (case-sensitive filesystems) while working perfectly on the pack author's
 *       Windows machine -- "works for them, not for me";</li>
 *   <li>a pack carrying a path Windows cannot create (a reserved device name like {@code CON}, an
 *       invalid character, a trailing dot) failed with a bare "Invalid argument", or resolved to a
 *       DEVICE, with no indication of which file was at fault.</li>
 * </ul>
 */
class PackPathTest {

    // ---- which folder a pack file lands in (case normalisation) ----

    @Test
    void knownGameFoldersAreRewrittenToTheSpellingTheGameUses() {
        assertEquals("mods/foo.jar", ModpackReader.canonicalGameFolder("Mods/foo.jar"));
        assertEquals("config/my.toml", ModpackReader.canonicalGameFolder("Config/my.toml"));
        assertEquals("resourcepacks/x.zip", ModpackReader.canonicalGameFolder("ResourcePacks/x.zip"));
        assertEquals("saves/World/level.dat", ModpackReader.canonicalGameFolder("Saves/World/level.dat"),
                "only the top segment is normalised -- nothing below it is touched");
        assertEquals("options.txt", ModpackReader.canonicalGameFolder("Options.txt"));
        assertEquals("servers.dat", ModpackReader.canonicalGameFolder("Servers.dat"));
    }

    @Test
    void alreadyCanonicalPathsAndUnknownFoldersAreLeftExactlyAsTheyAre() {
        assertEquals("mods/foo.jar", ModpackReader.canonicalGameFolder("mods/foo.jar"));
        // A pack-specific folder keeps its own spelling: only the pack's author knows whether something
        // reads it case-sensitively, and guessing could break it.
        assertEquals("kubejs_scripts/MyScript.js", ModpackReader.canonicalGameFolder("kubejs_scripts/MyScript.js"));
        assertEquals("MyOwnDir/File.txt", ModpackReader.canonicalGameFolder("MyOwnDir/File.txt"));
        // Only the TOP segment is normalised: nothing inside a known folder is touched.
        assertEquals("mods/Sub/Dir/File.jar", ModpackReader.canonicalGameFolder("Mods/Sub/Dir/File.jar"));
        assertNull(ModpackReader.canonicalGameFolder(null));
    }

    @Test
    void resolveLandsACapitalisedPackPathInTheFolderTheGameReads() {
        Path base = Path.of("/tmp/dey-instance");
        assertEquals(base.resolve("mods/foo.jar"), PackFileOps.resolve(base, "Mods/foo.jar"));
        assertEquals(base.resolve("config/a/b.toml"), PackFileOps.resolve(base, "Config/a/b.toml"));
        // The zip-slip guard must still win over normalisation.
        assertNull(PackFileOps.resolve(base, "../outside.jar"));
        assertNull(PackFileOps.resolve(base, "mods/../../outside.jar"));
    }

    // ---- when a pack path cannot be created on this OS ----

    @Test
    void linuxAcceptsPathsWindowsCannotCreate() {
        // Linux takes all of these, so a Linux user installing such a pack still gets the file.
        assertNull(PackFileOps.pathProblem("mods/CON", Path.of("/tmp/x"), false));
        assertNull(PackFileOps.pathProblem("config/evil?.toml", Path.of("/tmp/x"), false));
        assertNull(PackFileOps.pathProblem("mods/trailing.", Path.of("/tmp/x"), false));
    }

    @Test
    void windowsRejectsReservedDeviceNamesWithAReadableReason() {
        String con = PackFileOps.pathProblem("mods/CON", Path.of("/tmp/x"), true);
        assertNotNull(con);
        assertTrue(con.contains("reserved Windows device name"), con);
        assertTrue(con.contains("CON"), "the message must name the file so the user can find it");
        assertNotNull(PackFileOps.pathProblem("mods/aux.txt", Path.of("/tmp/x"), true));
        assertNotNull(PackFileOps.pathProblem("config/com1.json", Path.of("/tmp/x"), true));
        assertNull(PackFileOps.pathProblem("mods/console.jar", Path.of("/tmp/x"), true),
                "only the exact device name is reserved, not any name starting with it");
    }

    @Test
    void windowsRejectsInvalidCharactersTrailingDotsAndLongNames() {
        String bad = PackFileOps.pathProblem("config/evil?.toml", Path.of("/tmp/x"), true);
        assertNotNull(bad);
        assertTrue(bad.contains("?"), bad);

        assertNotNull(PackFileOps.pathProblem("mods/pack.", Path.of("/tmp/x"), true));
        assertNotNull(PackFileOps.pathProblem("mods/pack ", Path.of("/tmp/x"), true));

        String tooLong = PackFileOps.pathProblem("mods/" + "a".repeat(300) + ".jar", Path.of("/tmp/x"), true);
        assertNotNull(tooLong);
        assertTrue(tooLong.contains("longer than Windows allows"), tooLong);
    }

    @Test
    void windowsRejectsTooLongFullPathsAndAcceptsCleanOnes() {
        String longPath = PackFileOps.pathProblem("mods/" + "b".repeat(250) + "/x.jar", Path.of("/tmp/x"), true);
        assertNotNull(longPath);
        assertTrue(longPath.contains("260"), longPath);

        assertNull(PackFileOps.pathProblem("mods/ok.jar", Path.of("/tmp/x"), true));
        assertNull(PackFileOps.pathProblem("config/ok.toml", Path.of("/tmp/x"), true));
    }
}
