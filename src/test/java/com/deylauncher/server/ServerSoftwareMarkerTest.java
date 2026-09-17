package com.deylauncher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule that fixes the reported bug: a server folder may only be reused when the software in
 * it was installed for exactly the server's current type and Minecraft version. The old rule was
 * "server.jar exists", which is why changing a server to a lower version kept launching the old jar
 * (and, for Fabric/Forge, the old loader and its generated launch args) on the next start.
 */
class ServerSoftwareMarkerTest {

    private static ServerInstance server(ServerType type, String version) {
        ServerInstance s = new ServerInstance("test", type, version);
        s.id = "test-server";
        return s;
    }

    @Test
    void markerRoundTripsAndMatchesTheSameVersion(@TempDir Path dir) {
        ServerSoftwareMarker.write(dir, ServerSoftwareMarker.forServer(server(ServerType.FABRIC, "1.21.11")));

        ServerSoftwareMarker read = ServerSoftwareMarker.read(dir);
        assertTrue(read.matches(server(ServerType.FABRIC, "1.21.11")), "same type + version must be reused");
        assertFalse(read.matches(server(ServerType.FABRIC, "1.20.1")), "a lower version must not reuse it");
        assertFalse(read.matches(server(ServerType.FORGE, "1.21.11")), "another server type must not reuse it");
    }

    @Test
    void missingOrCorruptMarkerMeansUnknown(@TempDir Path dir) throws Exception {
        assertNull(ServerSoftwareMarker.read(dir), "no marker yet -> nothing trusted");

        Files.writeString(ServerSoftwareMarker.fileIn(dir), "not json at all");
        assertNull(ServerSoftwareMarker.read(dir), "a corrupt marker must read as unknown, not as a match");
    }

    @Test
    void needsFreshInstallPrefersTheMarkerThenTheFolderLayout() {
        ServerInstance fabric = server(ServerType.FABRIC, "1.20.1");

        // With a marker, the marker decides -- even if the layout disagrees.
        assertFalse(ServerSoftwareMarker.needsFreshInstall(
                ServerSoftwareMarker.forServer(fabric), "1.21.11", fabric));
        assertTrue(ServerSoftwareMarker.needsFreshInstall(
                ServerSoftwareMarker.forServer(server(ServerType.FABRIC, "1.21.11")), null, fabric));

        // Without one, the version read out of the folder decides when it can be read at all.
        assertTrue(ServerSoftwareMarker.needsFreshInstall(null, "1.21.11", fabric));
        assertFalse(ServerSoftwareMarker.needsFreshInstall(null, "1.20.1", fabric));
        // Vanilla/Purpur jars carry no readable version, so a marker-less folder is trusted once
        // (the pre-existing behaviour) rather than re-downloaded on every start.
        assertFalse(ServerSoftwareMarker.needsFreshInstall(null, null, fabric));
    }

    @Test
    void detectInstalledVersionReadsTheFabricLayout(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("versions").resolve("26.2"));
        assertEquals("26.2", ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.FABRIC));

        // Two version dirs (a folder that already survived a version change) is ambiguous, so nothing
        // is assumed and the folder is left alone -- the marker handles it from the next start on.
        Files.createDirectories(dir.resolve("versions").resolve("1.20.1"));
        assertNull(ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.FABRIC));
    }

    @Test
    void detectInstalledVersionReadsTheForgeLayout(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("libraries/net/minecraftforge/forge/1.20.1-47.4.20"));
        assertEquals("1.20.1", ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.FORGE));

        Files.createDirectories(dir.resolve("libraries/net/minecraftforge/forge/1.21.1-52.0.2"));
        assertNull(ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.FORGE));

        // Vanilla has no layout to read: the answer is honestly "unknown".
        assertNull(ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.VANILLA));
    }

    @Test
    void detectInstalledVersionReadsTheNeoForgeLayout(@TempDir Path dir) throws Exception {
        // Modern NeoForge: the directory is named after NEOFORGE's version, which carries the
        // Minecraft version in its first two segments.
        Files.createDirectories(dir.resolve("libraries/net/neoforged/neoforge/26.2.0.88"));
        assertEquals("26.2", ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.NEOFORGE));

        Files.createDirectories(dir.resolve("libraries/net/neoforged/neoforge/26.3.0.1-beta"));
        assertNull(ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.NEOFORGE),
                "two installed builds is ambiguous, so nothing is assumed");
    }

    @Test
    void detectInstalledVersionReadsTheLegacyNeoForgeLayout(@TempDir Path dir) throws Exception {
        // MC 1.20.1's NeoForge predates NeoForge's own numbering and installs Forge-style as
        // "1.20.1-47.1.x" under Forge's path, so the Forge fallback has to catch it.
        Files.createDirectories(dir.resolve("libraries/net/minecraftforge/forge/1.20.1-47.1.106"));
        assertEquals("1.20.1", ServerSoftwareMarker.detectInstalledVersion(dir, ServerType.NEOFORGE));
    }

    @Test
    void neoforgeVersionToMinecraftMapping() {
        assertEquals("26.2", ServerSoftwareMarker.neoforgeToMinecraft("26.2.0.88"));
        assertEquals("26.3", ServerSoftwareMarker.neoforgeToMinecraft("26.3.0.1-beta"));
        assertEquals("26.1", ServerSoftwareMarker.neoforgeToMinecraft("26.1.2.104"));
        assertEquals("1.21.1", ServerSoftwareMarker.neoforgeToMinecraft("21.1.72"));
        assertEquals("1.21.11", ServerSoftwareMarker.neoforgeToMinecraft("21.11.5"));
        assertEquals("1.20.4", ServerSoftwareMarker.neoforgeToMinecraft("20.4.237"));
        assertEquals("1.20.1", ServerSoftwareMarker.neoforgeToMinecraft("1.20.1-47.1.106"));
        assertNull(ServerSoftwareMarker.neoforgeToMinecraft("21"), "unreadable -> unknown, never guessed");
        assertNull(ServerSoftwareMarker.neoforgeToMinecraft("snapshot-x"));
        assertNull(ServerSoftwareMarker.neoforgeToMinecraft(null));
    }

    @Test
    void purgeRemovesVersionBoundSoftwareOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("server.jar"), "jar");
        Files.writeString(dir.resolve("run.sh"), "#!/bin/sh");
        Files.writeString(dir.resolve("run.bat"), "bat");
        Files.writeString(dir.resolve("user_jvm_args.txt"), "-Xmx1G");
        Files.writeString(dir.resolve("serverstarter.jar"), "neoforge server launcher");
        Files.writeString(dir.resolve("forge-installer-1.21.11-52.0.2.jar"), "installer");
        Files.writeString(dir.resolve("forge-installer.jar"), "old fixed-name installer");
        Files.writeString(dir.resolve("neoforge-installer-26.2.0.88.jar"), "neoforge installer");
        Files.createDirectories(dir.resolve("libraries/net/minecraftforge/forge"));
        Files.createDirectories(dir.resolve("libraries/net/neoforged/neoforge/26.2.0.88"));
        Files.createDirectories(dir.resolve("versions/1.21.11"));
        Files.createDirectories(dir.resolve(".fabric/server"));
        // User data that must survive a version change untouched.
        Files.writeString(dir.resolve("server.properties"), "level-name=world");
        Files.writeString(dir.resolve("eula.txt"), "eula=true");
        Files.createDirectories(dir.resolve("world/region"));
        Files.writeString(dir.resolve("world/level.dat"), "level");
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("mods/example.jar"), "mod");

        List<String> removed = ServerSoftwareMarker.purgeVersionBoundSoftware(dir);

        assertTrue(removed.contains("server.jar"));
        assertTrue(removed.contains("libraries"));
        assertTrue(removed.contains("versions"));
        assertTrue(removed.contains(".fabric"));
        assertTrue(removed.contains("forge-installer.jar"));
        assertTrue(removed.contains("forge-installer-1.21.11-52.0.2.jar"));
        assertTrue(removed.contains("neoforge-installer-26.2.0.88.jar"),
                "a version-named NeoForge installer is version-bound software too");
        assertTrue(removed.contains("serverstarter.jar"),
                "NeoForge's server launcher jar belongs to the installed build, not the user");
        assertFalse(Files.exists(dir.resolve("server.jar")));
        assertFalse(Files.exists(dir.resolve("libraries")));

        assertTrue(Files.exists(dir.resolve("world/level.dat")), "the world must never be purged");
        assertTrue(Files.exists(dir.resolve("mods/example.jar")), "mods are the user's, not the software");
        assertTrue(Files.exists(dir.resolve("server.properties")));
        assertTrue(Files.exists(dir.resolve("eula.txt")));
    }
}