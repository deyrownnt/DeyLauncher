package com.deylauncher.modloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
        // Newest 26.x builds are matched BEFORE the generic "26" arm below, so each gets the jar
        // that actually declares a matching depends.minecraft (26.3 -> ~26.3, else the loader
        // abort/refuse path kicks in and the version would silently lose capes).
        if (v.startsWith("26.3")) return "DeyCapes-26.3.jar";
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
     *
     * Strict-version gate: the DeyCapes jar is only ever installed/kept when the selected Minecraft
     * version actually satisfies the dependency that jar itself declares ({@code depends.minecraft}
     * inside {@code fabric.mod.json}). Fabric's loader aborts at startup otherwise with a HARD_DEP
     * error (e.g. "requires 1.19.4 or later, but only 1.19.3 is present"). When the selected version
     * is below a jar's floor, the jar is NOT installed and any already-present DeyCapes jar is moved
     * to mods-disabled (never deleted) so the game can still launch without capes.
     *
     * Returns the installed file's name, or null if already up-to-date, incompatible, or no matching jar exists.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        String targetJar = resolveJarName(mcVersion);
        if (targetJar == null) return null;

        Files.createDirectories(modsDir);
        String targetLower = targetJar.toLowerCase();

        // Read the jar's own declared Minecraft requirement and verify the selected version satisfies it.
        String required = resolveMinecraftDependency(targetJar); // null when unknown -> treated as compatible
        if (!MinecraftVersionRange.matches(required, mcVersion)) {
            LOGGER.warning("DeyCapes " + targetJar + " requires minecraft " + required
                    + " but the selected version is " + mcVersion
                    + " -- not installing; disabling any existing jar so the game can still load.");
            ModsUtil.disableActiveFamily(modsDir, MOD_PREFIX);
            return null;
        }

        if (isInstalled(modsDir, targetJar)) {
            return null; // already installed and compatible
        }

        // Update/downgrade: drop any OTHER (wrong-version) DeyCapes jar, then install the target
        // (replacing it if an out-of-date copy is already there).
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

        return copyJar(targetJar, targetLower, modsDir);
    }

    /** Copies the named jar from bundled resources (preferred) or the dev dist folder. Returns the file name, or null. */
    private String copyJar(String targetJar, String targetLower, Path modsDir) {
        String resourcePath = "/deycapes-jars/" + targetJar;
        try (InputStream in = DeyCapesInstaller.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                Files.copy(in, modsDir.resolve(targetLower), StandardCopyOption.REPLACE_EXISTING);
                return targetLower;
            }
        } catch (Exception ignored) {
        }
        Path devDist = devDistJar(targetJar);
        if (Files.isRegularFile(devDist)) {
            try {
                Files.copy(devDist, modsDir.resolve(targetLower), StandardCopyOption.REPLACE_EXISTING);
                return targetLower;
            } catch (Exception ignored) {
            }
        }
        LOGGER.warning("Could not find " + targetJar + " in bundled resources or dev dist directory.");
        return null;
    }

    /**
     * Returns the {@code depends.minecraft} constraint the named jar declares, by inspecting the jar's
     * {@code fabric.mod.json} (bundled resource first, dev dist fallback). Returns null when it can't be
     * read -- the caller then treats the jar as compatible (never a false negative that blocks installs).
     */
    private String resolveMinecraftDependency(String targetJar) {
        String resourcePath = "/deycapes-jars/" + targetJar;
        try (InputStream in = DeyCapesInstaller.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                String dep = readMinecraftDepends(new ZipInputStream(in));
                if (dep != null) return dep;
            }
        } catch (Exception ignored) {
        }
        Path devDist = devDistJar(targetJar);
        if (Files.isRegularFile(devDist)) {
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(devDist))) {
                return readMinecraftDepends(zin);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Path devDistJar(String targetJar) {
        return Path.of(System.getProperty("user.home"), "Documents", "JavaProg", "DeyCapes", "dist", targetJar);
    }

    /** Extracts the raw {@code depends.minecraft} string from a jar's fabric.mod.json, or null. */
    private String readMinecraftDepends(ZipInputStream zin) throws Exception {
        ZipEntry entry;
        while ((entry = zin.getNextEntry()) != null) {
            if (!entry.getName().equals("fabric.mod.json")) continue;
            String content = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (root.has("depends")) {
                JsonObject depends = root.getAsJsonObject("depends");
                if (depends.has("minecraft")) return depends.get("minecraft").getAsString();
            }
            return null;
        }
        return null;
    }
}
