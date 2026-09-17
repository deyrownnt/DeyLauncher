package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reading rules that decide where a pack's files end up -- including the {@code .minecraft/}
 * wrapper that used to put every mod one folder too deep, and the CurseForge id pairs that make the
 * automatic download possible at all.
 */
class ModpackReaderTest {

    @Test
    void aPackWrappedInDotMinecraftInstallsIntoTheInstanceItself(@TempDir Path pack) throws Exception {
        write(pack.resolve(".minecraft/mods/example-mod.jar"), "jar");
        write(pack.resolve(".minecraft/config/pack.cfg"), "cfg");
        write(pack.resolve(".minecraft/saves/world/level.dat"), "world");

        ModpackInfo info = ModpackReader.read(pack);
        List<String> paths = info.bundled().stream().map(ModpackFile::path).toList();

        assertTrue(paths.contains("mods/example-mod.jar"),
                "a .minecraft/ wrapper must be stripped, or the loader never sees the mod: " + paths);
        assertTrue(paths.contains("config/pack.cfg"));
        assertTrue(paths.contains("saves/world/level.dat"));
        assertFalse(paths.stream().anyMatch(p -> p.startsWith(".minecraft/")), "nothing may stay under .minecraft/");
    }

    @Test
    void curseForgeEntriesPairNamesWithIdsWhenTheListsLineUp(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20","primary":true}]},
                 "name":"Test Pack","version":"1.0","overrides":"overrides",
                 "files":[{"projectID":238222,"fileID":8807823,"required":true},
                          {"projectID":306612,"fileID":8882089,"required":false}]}""");
        Files.writeString(pack.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/jei\">Just Enough Items (JEI)</a></li>"
                        + "<li><a href=\"https://www.curseforge.com/minecraft/mc-mods/fabric-api\">Fabric API</a></li></ul>");

        List<CurseForgeEntry> entries = ModpackReader.curseForgeEntries(pack);

        assertEquals(2, entries.size());
        assertEquals(238222, entries.get(0).projectId());
        assertEquals(8807823, entries.get(0).fileId());
        assertEquals("Just Enough Items (JEI)", entries.get(0).name());
        assertTrue(entries.get(0).required());
        assertEquals("Fabric API", entries.get(1).name());
        assertFalse(entries.get(1).required());
    }

    @Test
    void namesAreDroppedWhenTheListsDoNotLineUp(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1"},
                 "files":[{"projectID":238222,"fileID":8807823,"required":true}]}""");
        // Two modlist entries for one manifest entry: the order can't be trusted, so no names are used.
        Files.writeString(pack.resolve("modlist.html"),
                "<ul><li><a href=\"https://example.invalid/a\">Something</a></li>"
                        + "<li><a href=\"https://example.invalid/b\">Something Else</a></li></ul>");

        List<CurseForgeEntry> entries = ModpackReader.curseForgeEntries(pack);

        assertEquals(1, entries.size());
        assertEquals("", entries.get(0).name(), "a mismatched modlist must never be used to guess a mod");
    }

    @Test
    void aCurseForgePackCountsNothingAsUnresolvableAtReadTime(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20","primary":true}]},
                 "name":"CF Pack","version":"1.0",
                 "files":[{"projectID":238222,"fileID":8807823,"required":true}]}""");

        ModpackInfo info = ModpackReader.read(pack);

        assertTrue(info.resolvesRemotely(), "a CurseForge pack's files are resolved while installing");
        assertEquals(0, info.unresolvedCount());
        assertNotNull(info.note(), "the preview must explain that its files come from CurseForge");
    }

    @Test
    void modrinthEnvOptionalBecomesAOptionalNotARequiredFile(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("modrinth.index.json"), """
                {"name":"Env Pack","versionId":"1.0",
                 "dependencies":{"minecraft":"1.20.1","fabric-loader":"0.15.11"},
                 "files":[
                   {"path":"mods/required.jar","downloads":["https://example.invalid/required.jar"],
                    "fileSize":10,"hashes":{"sha1":""},"env":{"client":"required","server":"unsupported"}},
                   {"path":"mods/extra.jar","downloads":["https://example.invalid/extra.jar"],
                    "fileSize":10,"hashes":{"sha1":""},"env":{"client":"optional","server":"optional"}}]}""");

        ModpackInfo info = ModpackReader.read(pack);

        assertEquals(2, info.downloads().size());
        ModpackFile required = info.downloads().get(0);
        ModpackFile optional = info.downloads().get(1);
        assertTrue(required.clientRequired());
        assertTrue(required.serverUnsupported());
        assertFalse(optional.clientRequired(), "an env:optional file failing must not fail the install");
        assertFalse(optional.clientUnsupported(), "optional is not the same as unsupported");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
