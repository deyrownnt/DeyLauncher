package com.deylauncher.modpack;

import com.deylauncher.server.ModrinthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (offline) install of a CurseForge pack: the previously manual case -- ids only, every mod
 * downloaded from the website and placed by hand -- now installs itself and records what it owns.
 */
class ModpackInstallerTest {

    @Test
    void aCurseForgePackInstallsItsModsAndOverridesAndRecordsItsManifest(@TempDir Path pack,
                                                                        @TempDir Path root) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20","primary":true}]},
                 "name":"CF Pack","version":"1.0","overrides":"overrides",
                 "files":[{"projectID":238222,"fileID":8807823,"required":true}]}""");
        Files.writeString(pack.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/jei\">Just Enough Items (JEI)</a></li></ul>");
        Files.createDirectories(pack.resolve("overrides/config"));
        Files.writeString(pack.resolve("overrides/config/pack.cfg"), "cfg");

        CurseForgeFiles cf = new CurseForgeFiles(null, url ->
                "{\"data\":{\"fileName\":\"jei-1.20.1-forge-15.57.0.207.jar\",\"fileLength\":11,"
                        + "\"gameVersions\":[\"Client\",\"1.20.1\",\"Forge\",\"Server\"]}}");
        ModpackInstaller installer = new ModpackInstaller(
                new ModpackResolver(cf, new ModrinthClient()), ModpackVerifierTest.WRITER);

        Path instanceDir = root.resolve("instances").resolve("1.20.1-forge");
        ModpackInstaller.Result result = installer.installForClient(
                ModpackReader.read(pack), "1.20.1", "Forge", instanceDir, null, null);

        assertTrue(result.ok(), result.summary());
        assertEquals(1, result.downloaded(), "the mod named only by project/file id must be fetched");
        assertTrue(Files.isRegularFile(instanceDir.resolve("mods/jei-1.20.1-forge-15.57.0.207.jar")));
        assertTrue(Files.isRegularFile(instanceDir.resolve("config/pack.cfg")), "the pack's overrides still install");

        ModpackMeta meta = ModpackMeta.read(instanceDir);
        assertNotNull(meta, "the instance must record which pack lives in it");
        assertEquals("CF Pack", meta.name);
        assertEquals("Forge", meta.loader);
        assertEquals("1.20.1", meta.mcVersion);
        assertEquals(2, meta.packFiles().size(), "the manifest must cover the mod and the override");
        assertTrue(meta.managesFiles(), "so a later launch can restore anything that goes missing");
    }

    @Test
    void installingADifferentPackIntoSameInstanceDropsTheOldPacksFiles(@TempDir Path root,
                                                                       @TempDir Path packs) throws Exception {
        Path instanceDir = root.resolve("instances").resolve("1.20.1-forge");

        Path packA = packs.resolve("pack-a");
        Files.createDirectories(packA);
        Files.writeString(packA.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/a\">Mod A</a></li></ul>");
        Files.writeString(packA.resolve("manifest.json"), manifestFor("Pack A", 1, 101));
        Path packB = packs.resolve("pack-b");
        Files.createDirectories(packB);
        Files.writeString(packB.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/b\">Mod B</a></li></ul>");
        Files.writeString(packB.resolve("manifest.json"), manifestFor("Pack B", 2, 202));

        CurseForgeFiles cf = new CurseForgeFiles(null, url -> url.contains("/101")
                ? "{\"data\":{\"fileName\":\"mod-a.jar\",\"fileLength\":4,\"gameVersions\":[\"Client\",\"1.20.1\"]}}"
                : "{\"data\":{\"fileName\":\"mod-b.jar\",\"fileLength\":4,\"gameVersions\":[\"Client\",\"1.20.1\"]}}");
        ModpackInstaller installer = new ModpackInstaller(
                new ModpackResolver(cf, new ModrinthClient()), ModpackVerifierTest.WRITER);

        installer.installForClient(ModpackReader.read(packA), "1.20.1", "Forge", instanceDir, null, null);
        assertTrue(Files.exists(instanceDir.resolve("mods/mod-a.jar")));

        ModpackInstaller.Result second = installer.installForClient(
                ModpackReader.read(packB), "1.20.1", "Forge", instanceDir, null, null);

        assertTrue(Files.exists(instanceDir.resolve("mods/mod-b.jar")));
        assertFalse(Files.exists(instanceDir.resolve("mods/mod-a.jar")),
                "two packs' mods must never be stacked in one instance folder");
        assertFalse(second.warnings().isEmpty(), "the swap must say what it cleaned up");
    }

    @Test
    void aFailedDownloadIsReportedAndLeavesNoFileBehind(@TempDir Path pack, @TempDir Path root) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1"},
                 "files":[{"projectID":238222,"fileID":8807823,"required":true}]}""");
        ModpackInstaller installer = new ModpackInstaller(
                new ModpackResolver(new CurseForgeFiles(null, url ->
                        "{\"data\":{\"fileName\":\"dead-link.jar\",\"fileLength\":10,"
                                + "\"gameVersions\":[\"Client\",\"1.20.1\"]}}"), new ModrinthClient()),
                (url, dest, size) -> false);

        Path instanceDir = root.resolve("instances").resolve("1.20.1-forge");
        ModpackInstaller.Result result = installer.installForClient(
                ModpackReader.read(pack), "1.20.1", "Forge", instanceDir, null, null);

        assertFalse(result.ok());
        assertEquals(1, result.failed());
        assertFalse(result.errors().isEmpty(), "the reason must be reported, not swallowed");
        assertFalse(Files.exists(instanceDir.resolve("mods/dead-link.jar")), "no half-written mod is left behind");
        assertEquals(1, ModpackMeta.read(instanceDir).packFiles().size(),
                "the manifest still records it, so the next launch retries it");
    }

    private static String manifestFor(String name, long projectId, long fileId) {
        return "{\"minecraft\":{\"version\":\"1.20.1\"},\"name\":\"" + name + "\","
                + "\"files\":[{\"projectID\":" + projectId + ",\"fileID\":" + fileId + ",\"required\":true}]}";
    }
}