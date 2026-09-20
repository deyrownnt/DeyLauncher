package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instance's pack record is what a launch-time repair reads, so its file manifest has to survive
 * being written as JSON and read back (including records and null hash fields).
 */
class ModpackMetaTest {

    @Test
    void theFileManifestSurvivesARoundTripThroughTheInstanceRecord(@TempDir Path instance) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = "Test Pack";
        meta.version = "1.0";
        meta.mcVersion = "1.20.1";
        meta.loader = "Forge";
        meta.loaderVersion = "47.4.20";
        meta.format = ModpackFormat.CURSEFORGE_ZIP.name();
        meta.source = "/tmp/test-pack.zip";
        meta.files = List.of(
                ModpackFile.download("mods/a.jar", "https://example.invalid/a.jar", "abc", "def", 123,
                        true, false, true, false),
                ModpackFile.bundled("config/pack.cfg", "overrides/config/pack.cfg", 5));
        meta.unresolved = List.of("999/111 -- Gone");

        meta.write(instance);
        ModpackMeta read = ModpackMeta.read(instance);

        assertNotNull(read, "the pack must still be listed after a restart");
        assertEquals("Test Pack", read.name);
        assertEquals("47.4.20", read.loaderVersion, "the pinned loader build is what makes the pack launchable");
        assertTrue(read.managesFiles());
        assertEquals(2, read.packFiles().size());

        ModpackFile mod = read.packFiles().get(0);
        assertEquals("mods/a.jar", mod.path());
        assertEquals("https://example.invalid/a.jar", mod.url());
        assertEquals("abc", mod.sha1());
        assertEquals(123, mod.sizeBytes());
        assertTrue(mod.clientRequired());
        assertFalse(mod.clientUnsupported());
        assertTrue(mod.downloadable());

        ModpackFile bundled = read.packFiles().get(1);
        assertEquals("config/pack.cfg", bundled.path());
        assertEquals("overrides/config/pack.cfg", bundled.archiveEntry());
        assertFalse(bundled.downloadable(), "a bundled file is unpacked from the pack, not downloaded");

        assertEquals(List.of("999/111 -- Gone"), read.unresolvedFiles());
    }

    @Test
    void aRecordFromAnOlderBuildStillReads(@TempDir Path instance) throws Exception {
        Files.writeString(instance.resolve("modpack.json"),
                "{\"name\":\"Old Pack\",\"mcVersion\":\"1.20.1\",\"loader\":\"Forge\"}");

        ModpackMeta read = ModpackMeta.read(instance);

        assertNotNull(read);
        assertEquals("Old Pack", read.name);
        assertTrue(read.packFiles().isEmpty(), "an older record simply has no manifest to verify");
        assertFalse(read.managesFiles());
    }

    // ---- instance identity = the folder; version/loader are compatibility info only ----

    private static ModpackMeta pack(String name, String mc, String loader) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = name;
        meta.version = "1.0";
        meta.mcVersion = mc;
        meta.loader = loader;
        return meta;
    }

    @Test
    void theInstanceIdIsTheFolderTheRecordLivesInAndIsNeverStored(@TempDir Path root) throws Exception {
        Path dir = root.resolve("instances").resolve("rising-legends");
        pack("Rising Legends", "1.20.1", "Fabric").write(dir);

        assertFalse(Files.readString(dir.resolve("modpack.json")).contains("instanceId"),
                "identity is derived from the folder, so it can never disagree with where the files are");
        ModpackMeta read = ModpackMeta.read(dir);
        assertEquals("rising-legends", read.instanceId);
        assertEquals(dir, read.instanceDir(root));
    }

    @Test
    void aRecordFromThePreviousProfileBuildStillReadsAndResolvesToItsFolder(@TempDir Path root) throws Exception {
        Path dir = root.resolve("instances").resolve("my-profile");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("modpack.json"), "{\"name\":\"P\",\"mcVersion\":\"1.21.1\","
                + "\"loader\":\"Fabric\",\"profileId\":\"my-profile\"}");

        ModpackMeta read = ModpackMeta.read(dir);
        assertNotNull(read);
        assertEquals(dir, read.instanceDir(root), "no migration needed: the folder name is the identity");
    }

    @Test
    void aRecordFromAnOlderBuildInTheSharedFolderResolvesToThatSamePlainPath(@TempDir Path root) {
        Path shared = ModpackMeta.instanceDirFor(root, "1.20.1", "Forge");
        pack("Old Pack", "1.20.1", "Forge").write(shared);

        assertEquals(shared, ModpackMeta.read(shared).instanceDir(root));
        assertEquals(shared, pack("Unplaced", "1.20.1", "Forge").instanceDir(root),
                "a record never written to a folder falls back to the plain <mc>-<loader> path");
    }

    @Test
    void targetsIsCompatibilityInformationCaseInsensitiveWithVanillaAsTheEmptyLoader() {
        ModpackMeta fabric = pack("P", "1.20.1", "Fabric");
        assertTrue(fabric.targets("1.20.1", "fabric"));
        assertFalse(fabric.targets("1.20.4", "Fabric"));
        assertFalse(fabric.targets("1.20.1", "Forge"));
        assertFalse(fabric.targets(null, "Fabric"));
        assertTrue(pack("V", "1.20.1", null).targets("1.20.1", "Vanilla"));
    }

    @Test
    void thePinnedLoaderOnlyAppliesToTheVersionAndLoaderItWasMadeFor() {
        ModpackMeta meta = pack("Pinned", "1.21.1", "Fabric");
        meta.loaderVersion = "0.15.11";

        assertEquals("0.15.11", meta.pinnedLoaderVersion("1.21.1", "Fabric"));
        assertNull(meta.pinnedLoaderVersion("1.21.1", "Forge"), "another loader must not inherit the pin");
        assertNull(meta.pinnedLoaderVersion("1.20.1", "Fabric"), "another Minecraft version must not inherit it");
        assertNull(pack("NoPin", "1.21.1", "Fabric").pinnedLoaderVersion("1.21.1", "Fabric"));
    }

    @Test
    void twoPacksForTheSameVersionAndLoaderGetDifferentFolders(@TempDir Path root) {
        Path a = ModpackMeta.installDirFor(root, "Pack A", "1.20.1", "Fabric");
        pack("Pack A", "1.20.1", "Fabric").write(a);
        Path b = ModpackMeta.installDirFor(root, "Pack B", "1.20.1", "Fabric");
        pack("Pack B", "1.20.1", "Fabric").write(b);

        assertFalse(a.equals(b), "packs must never share a mods/ directory");
        assertEquals(root.resolve("instances"), a.getParent(), "launch code expects instances/<folder>");
        assertEquals(root.resolve("instances"), b.getParent());
    }

    @Test
    void reinstallingTheSamePackForTheSameTargetReusesItsFolderButANewTargetGetsANewOne(@TempDir Path root) {
        Path first = ModpackMeta.installDirFor(root, "Rising Legends", "1.20.1", "Fabric");
        pack("Rising Legends", "1.20.1", "Fabric").write(first);

        assertEquals(first, ModpackMeta.installDirFor(root, "Rising Legends", "1.20.1", "Fabric"),
                "an update replaces the pack's own files in place instead of duplicating it");
        assertEquals(first, ModpackMeta.installDirFor(root, "  rising legends ", "1.20.1", "fabric"),
                "same pack regardless of case/whitespace");
        assertFalse(first.equals(ModpackMeta.installDirFor(root, "Rising Legends", "1.21.1", "Fabric")),
                "the same pack for another Minecraft version is a different mod set, so a different folder");
    }

    @Test
    void aFolderNameTakenByAnotherThingIsNeverReused(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("instances").resolve("My-Pack"));

        Path dir = ModpackMeta.installDirFor(root, "My Pack", "1.20.1", "Fabric");
        assertEquals("My-Pack-2", dir.getFileName().toString());
        assertFalse(Files.exists(dir), "installDirFor is a pure lookup: it creates nothing");
    }

    @Test
    void folderNamesAreSanitisedAndCanNeverEscapeTheInstancesFolder(@TempDir Path root) {
        for (String hostile : new String[]{"..", ".", "../../evil", "a/b\\c", "", "   ", "CON", "nul.txt", "\u30e2\u30c3\u30c9"}) {
            Path dir = ModpackMeta.installDirFor(root, hostile, "1.20.1", "Fabric");
            assertEquals(root.resolve("instances"), dir.getParent(), "escaped instances/ for: " + hostile);
            String name = dir.getFileName().toString();
            assertFalse(name.isEmpty() || name.equals(".") || name.equals(".."), "bad folder name for: " + hostile);
            assertTrue(name.matches("[A-Za-z0-9._-]+"), "unsafe characters survived for: " + hostile + " -> " + name);
        }
        assertEquals("pack-CON", ModpackMeta.folderSlug("CON"), "Windows device names get a prefix");
    }

    @Test
    void theIconFallsBackToTheCopyInsideTheInstanceAndNeverThrows(@TempDir Path root) throws Exception {
        Path dir = root.resolve("instances").resolve("iconic");
        ModpackMeta meta = pack("Iconic", "1.20.1", "Fabric");
        meta.iconPath = root.resolve("modpacks/icons/gone.png").toString();
        meta.write(dir);
        assertNull(meta.iconFile(root), "no icon anywhere -> null, the UI keeps its generic glyph");

        Files.writeString(dir.resolve("icon.png"), "x");
        assertEquals(dir.resolve("icon.png"), meta.iconFile(root),
                "the cached copy was cleared; the pack's own icon.png still shows");

        Path cached = root.resolve("modpacks/icons/cached.png");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "x");
        meta.iconPath = cached.toString();
        assertEquals(cached, meta.iconFile(root));

        meta.iconPath = "bad\u0000path";
        assertEquals(dir.resolve("icon.png"), meta.iconFile(root), "an invalid stored path is ignored, not thrown");
    }

    // ---- a pack an older build installed into the plain <mc>-<loader> folder gets its own folder ----

    @Test
    void aLegacyPackIsMovedOutOfThePlainFolderSoPlainVersionsNeverSeeItsMods(@TempDir Path root) throws Exception {
        Path plain = ModpackMeta.instanceDirFor(root, "1.20.1", "Fabric");
        Files.createDirectories(plain.resolve("mods"));
        Files.writeString(plain.resolve("mods/rising-legends-core.jar"), "pack mod");
        Files.writeString(plain.resolve("options.txt"), "fov:90");
        pack("Rising Legends", "1.20.1", "Fabric").write(plain);

        List<String> notes = ModpackMeta.separateLegacyPacks(root);

        Path own = root.resolve("instances").resolve("Rising-Legends");
        assertEquals(1, notes.size());
        assertFalse(Files.exists(plain), "plain 1.20.1 Fabric no longer holds any of the pack's mods");
        assertEquals("pack mod", Files.readString(own.resolve("mods/rising-legends-core.jar")), "nothing is deleted");
        assertEquals("fov:90", Files.readString(own.resolve("options.txt")), "config and options travel with the pack");

        ModpackMeta moved = ModpackMeta.read(own);
        assertEquals("Rising-Legends", moved.instanceId, "the folder is the identity, so the record needs no edit");
        assertEquals(own, moved.instanceDir(root));

        ModpackSelection plainSelection = new ModpackSelection();
        ModpackSelection.Target t = plainSelection.resolve(root, "1.20.1", "Fabric");
        assertNull(t.pack(), "selecting plain 1.20.1 + Fabric (Vanilla or DEY) is no longer the pack");
        assertEquals(plain, t.instanceDir());

        ModpackSelection packSelection = new ModpackSelection();
        assertTrue(packSelection.attach(moved, "1.20.1", "Fabric", false));
        assertEquals(own, packSelection.resolve(root, "1.20.1", "Fabric").instanceDir(),
                "the pack still launches, from its own folder");
    }

    @Test
    void separatingLegacyPacksLeavesEverythingElseAloneAndIsRepeatable(@TempDir Path root) throws Exception {
        Path plainOnly = ModpackMeta.instanceDirFor(root, "1.21.1", "Fabric");
        Files.createDirectories(plainOnly.resolve("mods"));
        Files.writeString(plainOnly.resolve("mods/my-own-mod.jar"), "mine");           // no modpack.json
        Path isolated = root.resolve("instances").resolve("already-own");
        pack("Already Own", "1.20.1", "Fabric").write(isolated);                         // has its own folder
        Path plainVanilla = ModpackMeta.instanceDirFor(root, "1.16.5", "Vanilla");
        pack("Old Vanilla Pack", "1.16.5", "Vanilla").write(plainVanilla);              // Vanilla loader = "<mc>"

        List<String> first = ModpackMeta.separateLegacyPacks(root);

        assertEquals(1, first.size(), "only the pack that shared a plain folder is moved");
        assertEquals("mine", Files.readString(plainOnly.resolve("mods/my-own-mod.jar")), "a folder with no pack record is untouched");
        assertNotNull(ModpackMeta.read(isolated), "an isolated pack stays where it is");
        assertNull(ModpackMeta.read(plainVanilla));
        assertNotNull(ModpackMeta.read(root.resolve("instances").resolve("Old-Vanilla-Pack")));
        assertTrue(ModpackMeta.separateLegacyPacks(root).isEmpty(), "a second run has nothing left to do");
    }

    @Test
    void aLegacyPacksNewFolderNeverReusesAFolderThatAlreadyExists(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("instances").resolve("Taken"));
        Path plain = ModpackMeta.instanceDirFor(root, "1.20.1", "Forge");
        pack("Taken", "1.20.1", "Forge").write(plain);

        ModpackMeta.separateLegacyPacks(root);

        assertTrue(Files.isDirectory(root.resolve("instances").resolve("Taken")), "the existing folder is left alone");
        assertNotNull(ModpackMeta.read(root.resolve("instances").resolve("Taken-2")));
        assertFalse(Files.exists(plain));
    }

    @Test
    void separatingLegacyPacksWithNoInstancesFolderIsANoOp(@TempDir Path root) {
        assertTrue(ModpackMeta.separateLegacyPacks(root).isEmpty());
    }
}
