package com.deylauncher.modloader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Every DEY Fabric build automatically installs the corresponding DeyCapes mod jar
 * into the instance's mods folder, similar to how Sodium and Fabric API are installed.
 *
 * DeyCapes provides custom player capes fetched from GitHub and configured per-player.
 * It ships bundled inside DeyLauncher's resources (/deycapes-jars/), falling back to
 * the local dist folder during development.
 */
public class DeyCapesInstaller {
    private static final Logger LOGGER = Logger.getLogger("DeyCapesInstaller");
    private static final String MOD_PREFIX = "deycapes-";

    /** Resolves which version of DeyCapes to install based on the target Minecraft version. */
    public static String resolveJarName(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return null;
        String v = mcVersion.trim();
        if (v.startsWith("1.16")) return "DeyCapes-1.16.jar";
        if (v.startsWith("1.17")) return "DeyCapes-1.17.jar";
        if (v.startsWith("1.18")) return "DeyCapes-1.18.jar";
        if (v.startsWith("1.19")) return "DeyCapes-1.19.jar";
        if (v.startsWith("1.20")) return "DeyCapes-1.20.jar";
        if (v.equals("1.21") || v.equals("1.21.1")) return "DeyCapes-1.21.1.jar";
        if (v.startsWith("1.21.")) return "DeyCapes-1.21.2+.jar";
        if (v.startsWith("26")) return "DeyCapes-26.2.jar";
        return null;
    }

    /** True if the matching DeyCapes jar is already present in modsDir. */
    public boolean isInstalled(Path modsDir, String targetJarName) throws Exception {
        if (!Files.isDirectory(modsDir)) return false;
        String targetLower = targetJarName.toLowerCase();
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().equals(targetLower));
        }
    }

    /**
     * Ensures the matching DeyCapes mod jar is installed into modsDir.
     * If an outdated or different version's DeyCapes jar exists, cleans it up first.
     * Returns the installed file's name, or null if already up-to-date or no matching jar exists.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        String targetJar = resolveJarName(mcVersion);
        if (targetJar == null) return null;

        Files.createDirectories(modsDir);

        String targetLower = targetJar.toLowerCase();
        if (isInstalled(modsDir, targetJar)) {
            return null; // already installed
        }

        // Clean up any other DeyCapes jars in the folder
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return (name.startsWith(MOD_PREFIX) || name.startsWith("deycapes")) && !name.equals(targetLower);
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {}
            });
        }

        // Try reading from bundled resources first
        String resourcePath = "/deycapes-jars/" + targetJar;
        try (InputStream in = DeyCapesInstaller.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                Path dest = modsDir.resolve(targetLower);
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                return targetLower;
            }
        }

        // Fallback: check the local DeyCapes project dist directory (dev environment)
        Path devDist = Path.of(System.getProperty("user.home"), "Documents", "JavaProg", "DeyCapes", "dist", targetJar);
        if (Files.isRegularFile(devDist)) {
            Path dest = modsDir.resolve(targetLower);
            Files.copy(devDist, dest, StandardCopyOption.REPLACE_EXISTING);
            return targetLower;
        }

        LOGGER.warning("Could not find " + targetJar + " in bundled resources or dev dist directory.");
        return null;
    }
}
