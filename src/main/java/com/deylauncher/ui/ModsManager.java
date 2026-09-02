package com.deylauncher.ui;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Handles mods for one specific version+loader instance folder (e.g.
 * ~/.deylauncher/instances/1.21.1-fabric/). "Disabling" a mod moves its jar
 * into a sibling mods-disabled/ folder instead of deleting it or renaming
 * its extension -- this is the same approach tools like MultiMC/Prism use,
 * and it means the mod loader (which only scans mods/) simply never sees
 * disabled jars, no loader-specific "disabled" convention required.
 */
public class ModsManager {

    private final Path modsDir;
    private final Path disabledDir;

    public ModsManager(Path instanceDir) {
        this.modsDir = instanceDir.resolve("mods");
        this.disabledDir = instanceDir.resolve("mods-disabled");
    }

    /** The enabled-mods folder itself, e.g. for external installers (SodiumInstaller) that
     * need to drop a jar straight into the active mods/ directory. */
    public Path modsDir() {
        return modsDir;
    }

    public record ModEntry(String fileName, String displayName, boolean enabled, long sizeBytes) {}

    public List<ModEntry> list() throws IOException {
        List<ModEntry> entries = new ArrayList<>();
        addFrom(modsDir, true, entries);
        addFrom(disabledDir, false, entries);
        entries.sort(Comparator.comparing(ModEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private void addFrom(Path dir, boolean enabled, List<ModEntry> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".jar"))::iterator) {
                String fileName = p.getFileName().toString();
                String name = readMetadataName(p, displayNameFor(fileName));
                out.add(new ModEntry(fileName, name, enabled, Files.size(p)));
            }
        }
    }

    /** Reads the mod's own declared name from Fabric/Forge metadata, falling back to a cleaned-up filename. */
    private String displayNameFor(String fileName) {
        String cleanedFallback = (fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName)
                .replace('_', ' ').replace('-', ' ');
        // Real name lookup happens in addFrom() where we already have the full path -- see readMetadataName().
        return cleanedFallback;
    }

    private String readMetadataName(Path jarPath, String fallback) {
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            var fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (var in = zip.getInputStream(fabricEntry)) {
                    var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
                    if (json.has("name")) return json.get("name").getAsString();
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
        } catch (Exception ignored) {
            // Not a real/readable mod jar (corrupt, or dropped by mistake) -- fall back to the filename.
        }
        return fallback;
    }

    public void setEnabled(String fileName, boolean enabled) throws IOException {
        Path from = enabled ? disabledDir.resolve(fileName) : modsDir.resolve(fileName);
        Path to = enabled ? modsDir.resolve(fileName) : disabledDir.resolve(fileName);
        if (!Files.exists(from)) return;
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(String fileName) throws IOException {
        Files.deleteIfExists(modsDir.resolve(fileName));
        Files.deleteIfExists(disabledDir.resolve(fileName));
    }

    /** Copies an external jar (e.g. drag-and-dropped) in as an enabled mod. */
    public void addMod(Path sourceJar) throws IOException {
        Files.createDirectories(modsDir);
        Files.copy(sourceJar, modsDir.resolve(sourceJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }
}
