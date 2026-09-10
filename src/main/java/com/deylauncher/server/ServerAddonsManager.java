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

    public record AddonEntry(String fileName, String displayName, boolean enabled, long sizeBytes) {}

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

    /** The mods/ or plugins/ directory itself (or null if unsupported), for direct downloads. */
    public Path folder() {
        return folder;
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
                    String displayName = readMetadataName(p, displayNameFor(fileName));
                    out.add(new AddonEntry(fileName, displayName, enabled, Files.size(p)));
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(AddonEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Human-friendly fallback name from a file name, e.g. "More-Mobs-1.2.3.jar" -> "More Mobs". */
    private String displayNameFor(String fileName) {
        String base = fileName.endsWith(DISABLED_SUFFIX)
                ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length()) : fileName;
        if (base.endsWith(".jar")) base = base.substring(0, base.length() - 4);
        // Strip a trailing version segment like "-1.2.3" / "_1.2.3" that chips off cleanly.
        base = base.replaceAll("[-_](\\d+(\\.\\d+)*.*)$", "");
        return base.replace('_', ' ').replace('-', ' ').trim();
    }

    /** Reads the addon's own declared name from Fabric/Forge/Paper metadata, falling back to the filename. */
    private String readMetadataName(Path jarPath, String fallback) {
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            var fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (var in = zip.getInputStream(fabricEntry)) {
                    var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
                    if (json.has("name") && !json.get("name").getAsString().isBlank()) {
                        return json.get("name").getAsString();
                    }
                }
            }
            var forgeEntry = zip.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                try (var in = zip.getInputStream(forgeEntry)) {
                    String toml = new String(in.readAllBytes());
                    var m = java.util.regex.Pattern.compile("displayName\\s*=\\s*\"([^\"]+)\"").matcher(toml);
                    if (m.find()) return m.group(1);
                }
            }
            var paperEntry = zip.getEntry("plugin.yml");
            if (paperEntry != null) {
                try (var in = zip.getInputStream(paperEntry)) {
                    String yml = new String(in.readAllBytes());
                    var m = java.util.regex.Pattern.compile("(?m)^\\s*name\\s*:\\s*(.+)\\s*$").matcher(yml);
                    if (m.find()) {
                        String nm = m.group(1).trim();
                        if (nm.startsWith("\"") && nm.endsWith("\"") && nm.length() >= 2) nm = nm.substring(1, nm.length() - 1);
                        if (!nm.isBlank()) return nm;
                    }
                }
            }
        } catch (Exception ignored) {
            // Not a real/readable addon jar (corrupt, or dropped by mistake) -- fall back to the filename.
        }
        return fallback;
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
