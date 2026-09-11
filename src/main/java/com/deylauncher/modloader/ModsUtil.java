package com.deylauncher.modloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Small filesystem helpers the bundled-mod installers share so each family handles its mods folder
 * identically: finding the active jar of a family, removing stale copies (an update/downgrade replace),
 * and -- crucially -- DISABLING (moving to {@code mods-disabled}, never deleting) a bundled mod that
 * has no compatible build for the selected Minecraft version.
 */
public final class ModsUtil {

    private ModsUtil() {}

    /** The sibling "mods-disabled" folder beside an instance's mods/ folder. */
    public static Path disabledDirFor(Path modsDir) {
        Path parent = modsDir.getParent();
        return parent == null ? modsDir.resolveSibling("mods-disabled") : parent.resolve("mods-disabled");
    }

    /** First active jar in {@code modsDir} whose name starts with {@code prefix} (lower-cased), or null. */
    public static Path firstFamilyJar(Path modsDir, String prefix) {
        if (!Files.isDirectory(modsDir)) return null;
        try (var stream = Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.startsWith(prefix.toLowerCase()) && n.endsWith(".jar")) return p;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Deletes active family jars except {@code keepName}, leaving one fresh copy after an update/downgrade. */
    public static void removeFamilyJarsExcept(Path modsDir, String prefix, String keepName) {
        if (!Files.isDirectory(modsDir)) return;
        String pfx = prefix.toLowerCase();
        try (var stream = Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (!n.startsWith(pfx) || !n.endsWith(".jar")) continue;
                if (p.getFileName().toString().equalsIgnoreCase(keepName)) continue;
                Files.deleteIfExists(p);
            }
        } catch (Exception ignored) {
        }
    }

    /** Moves every active {@code prefix} jar into mods-disabled (never deletes). Returns the file names moved. */
    public static List<String> disableActiveFamily(Path modsDir, String prefix) {
        List<String> moved = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return moved;
        Path disabled = disabledDirFor(modsDir);
        String pfx = prefix.toLowerCase();
        try (var stream = Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (!n.startsWith(pfx) || !n.endsWith(".jar")) continue;
                try {
                    Files.createDirectories(disabled);
                    Files.move(p, disabled.resolve(p.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    moved.add(p.getFileName().toString());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return moved;
    }

    /**
     * If the exact {@code fileName} already sits in the disabled folder, move it back into mods/ (a
     * re-enable) instead of re-downloading it. Returns true when it was moved.
     */
    public static boolean reenableDisabled(Path modsDir, Path fileToMove) {
        if (fileToMove == null || !Files.exists(fileToMove)) return false;
        Path target = modsDir.resolve(fileToMove.getFileName());
        try {
            Files.createDirectories(modsDir);
            Files.move(fileToMove, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}