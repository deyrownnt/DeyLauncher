package com.deylauncher.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ServerAddonsManager {

    private static final String DISABLED_SUFFIX = ".disabled";

    public record AddonEntry(String fileName, boolean enabled, long sizeBytes) {}

    /** Returns "mods", "plugins", or null if this server type has no addon support at all (Vanilla). */
    public static String folderNameFor(ServerType type) {
        return switch (type) {
            case FABRIC, FORGE -> "mods";
            case PURPUR -> "plugins";
            case VANILLA -> null;
        };
    }

    private final Path folder;

    public ServerAddonsManager(Path serverDir, ServerType type) {
        String folderName = folderNameFor(type);
        this.folder = folderName == null ? null : serverDir.resolve(folderName);
    }

    public boolean supported() {
        return folder != null;
    }

    public List<AddonEntry> list() {
        List<AddonEntry> out = new ArrayList<>();
        if (folder == null) return out;
        try {
            Files.createDirectories(folder);
            try (var stream = Files.list(folder)) {
                for (Path p : (Iterable<Path>) stream
                        .filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".jar" + DISABLED_SUFFIX))
                        ::iterator) {
                    String fileName = p.getFileName().toString();
                    boolean enabled = !fileName.endsWith(DISABLED_SUFFIX);
                    out.add(new AddonEntry(fileName, enabled, Files.size(p)));
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(AddonEntry::fileName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public void setEnabled(String currentFileName, boolean enabled) throws IOException {
        String base = currentFileName.endsWith(DISABLED_SUFFIX)
                ? currentFileName.substring(0, currentFileName.length() - DISABLED_SUFFIX.length()) : currentFileName;
        Path current = folder.resolve(currentFileName);
        Path target = folder.resolve(enabled ? base : base + DISABLED_SUFFIX);
        if (!current.equals(target)) Files.move(current, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(String fileName) throws IOException {
        Files.deleteIfExists(folder.resolve(fileName));
    }

    public void addFile(Path sourceJar) throws IOException {
        if (folder == null || !sourceJar.toString().endsWith(".jar")) return;
        Files.createDirectories(folder);
        Files.copy(sourceJar, folder.resolve(sourceJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }
}
