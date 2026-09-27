package com.deylauncher.servers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sync travels with, and what it must never travel with. These rules decide whether a shared server
 * is a few megabytes or a copy of a whole installation, so they are pinned rather than left to inspection.
 */
class WorldSyncTest {

    @Test
    void aFullPushCarriesTheWorldButNotTheServerSoftwareOrLogs() {
        assertTrue(WorldSync.shouldInclude("world/region/r.0.0.mca", "world", false));
        assertTrue(WorldSync.shouldInclude("server.properties", "world", false));
        assertTrue(WorldSync.shouldInclude("mods/sodium.jar", "world", false));
        assertTrue(WorldSync.shouldInclude("config/iris.properties", "world", false));

        // The software and its loader machinery are re-downloaded per host -- hundreds of MB that would
        // otherwise be uploaded and stored for every single sync.
        assertFalse(WorldSync.shouldInclude("server.jar", "world", false));
        assertFalse(WorldSync.shouldInclude("libraries/org/ow2/asm/asm.jar", "world", false));
        assertFalse(WorldSync.shouldInclude("versions/1.20.1/1.20.1.jar", "world", false));
        assertFalse(WorldSync.shouldInclude("logs/latest.log", "world", false));
        assertFalse(WorldSync.shouldInclude("debug.log", "world", false));
        assertFalse(WorldSync.shouldInclude("crash-reports/crash-1.txt", "world", false));
        assertFalse(WorldSync.shouldInclude("", "world", false));
    }

    @Test
    void aSettingsOnlyPushKeepsProgressAndConfigAndDropsTheRegionFiles() {
        assertTrue(WorldSync.shouldInclude("server.properties", "world", true));
        assertTrue(WorldSync.shouldInclude("ops.json", "world", true));
        assertTrue(WorldSync.shouldInclude("whitelist.json", "world", true));
        assertTrue(WorldSync.shouldInclude("mods/sodium.jar", "world", true));
        assertTrue(WorldSync.shouldInclude("plugins/EssentialsX.jar", "world", true));
        assertTrue(WorldSync.shouldInclude("world/level.dat", "world", true));
        assertTrue(WorldSync.shouldInclude("world/playerdata/abc.dat", "world", true));

        // These are the bulk a settings-only push exists to avoid.
        assertFalse(WorldSync.shouldInclude("world/region/r.0.0.mca", "world", true));
        assertFalse(WorldSync.shouldInclude("world/DIM-1/region/r.0.0.mca", "world", true));
        assertFalse(WorldSync.shouldInclude("world_rivals/region/r.0.0.mca", "world", true));
        assertFalse(WorldSync.shouldInclude("random-notes.txt", "world", true));
    }

    @Test
    void aCustomLevelNameIsHonoured() {
        assertTrue(WorldSync.shouldInclude("smp/level.dat", "smp", true));
        assertFalse(WorldSync.shouldInclude("world/level.dat", "smp", true));
    }

    @Test
    void levelNameComesFromServerProperties(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("server.properties"), "#Generated\nlevel-name=my_smp\nmotd=hi\n");
        assertTrue(WorldSync.levelName(tmp).equals("my_smp"));

        Path plain = Files.createDirectories(tmp.resolve("other"));
        assertTrue(WorldSync.levelName(plain).equals("world"), "a missing properties file uses the default");
    }

    @Test
    void archivesRoundTripThroughExtraction(@TempDir Path tmp) throws IOException {
        Path serverDir = Files.createDirectories(tmp.resolve("server"));
        Path world = Files.createDirectories(serverDir.resolve("world").resolve("region"));
        Files.writeString(serverDir.resolve("server.properties"), "level-name=world\n");
        Files.writeString(world.resolve("r.0.0.mca"), "region bytes");
        Path mods = Files.createDirectories(serverDir.resolve("mods"));
        Files.writeString(mods.resolve("mod.jar"), "jar");
        Files.createDirectories(serverDir.resolve("logs"));
        Files.writeString(serverDir.resolve("logs").resolve("latest.log"), "noise");

        byte[] archive = WorldSync.buildArchive(serverDir, "world", false);
        assertTrue(archive.length > 0);

        Path restored = Files.createDirectories(tmp.resolve("restored"));
        WorldSync.extract(archive, restored);

        assertTrue(Files.exists(restored.resolve("world").resolve("region").resolve("r.0.0.mca")));
        assertTrue(Files.exists(restored.resolve("mods").resolve("mod.jar")));
        assertFalse(Files.exists(restored.resolve("logs").resolve("latest.log")),
                "logs must not travel");
    }

    @Test
    void extractionRefusesAPathThatEscapesTheTargetDirectory(@TempDir Path tmp) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("nope".getBytes());
            zip.closeEntry();
        }
        Path target = Files.createDirectories(tmp.resolve("target"));
        assertThrows(IOException.class, () -> WorldSync.extract(buffer.toByteArray(), target));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")), "nothing may be written outside the target");
    }
}
