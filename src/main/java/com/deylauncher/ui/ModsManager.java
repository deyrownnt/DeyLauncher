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

    /** The on-disk path of a mod jar (enabled or disabled), or null if it doesn't exist. */
    public Path jarPath(String fileName) {
        if (Files.exists(modsDir.resolve(fileName))) return modsDir.resolve(fileName);
        if (Files.exists(disabledDir.resolve(fileName))) return disabledDir.resolve(fileName);
        return null;
    }

    /**
     * The mod's own declared Fabric/Forge project id ("slug"), read from its metadata. This is
     * usually the exact Modrinth slug, so it lets the icon enrichment match mods that a plain
     * display-name search would miss. Returns null when the jar carries no usable id.
     */
    public String modSlug(String fileName) {
        Path p = jarPath(fileName);
        if (p == null) return null;
        try (var zip = new java.util.zip.ZipFile(p.toFile())) {
            var fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (var in = zip.getInputStream(fabricEntry)) {
                    var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes()))
                            .getAsJsonObject();
                    if (json.has("id") && !json.get("id").getAsString().isBlank()) {
                        return json.get("id").getAsString();
                    }
                }
            }
            var forgeEntry = zip.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                try (var in = zip.getInputStream(forgeEntry)) {
                    String toml = new String(in.readAllBytes());
                    var m = java.util.regex.Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]+)\"",
                            java.util.regex.Pattern.MULTILINE).matcher(toml);
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * The license the mod itself declares, read straight out of its own jar -- the signal
     * {@link com.deylauncher.modpack.ModDistributionPolicy} uses to decide whether a build of this mod
     * may be fetched from a content source on the user's behalf.
     *
     * <p>Read in the order the loaders themselves document it: {@code fabric.mod.json}'s
     * {@code license} (a string or an array of ids), then {@code META-INF/mods.toml}'s
     * {@code license = "..."} (which both Forge and NeoForge generate), then a bundled LICENSE file.
     * The LICENSE case is capped to its first few hundred characters, which is enough for an SPDX
     * identifier or an "All rights reserved" line while keeping this cheap.
     *
     * @return the declared license text, or null when the jar declares none.
     */
    public String modLicense(String fileName) {
        Path p = jarPath(fileName);
        if (p == null) return null;
        try (var zip = new java.util.zip.ZipFile(p.toFile())) {
            String fabric = fabricLicense(zip);
            if (fabric != null && !fabric.isBlank()) return fabric;
            String toml = tomlLicense(zip);
            if (toml != null && !toml.isBlank()) return toml;
            for (String entryName : new String[]{"META-INF/LICENSE", "META-INF/LICENSE.txt", "LICENSE",
                    "LICENSE.txt", "LICENCE", "LICENSE.md", "META-INF/licenses/LICENSE"}) {
                var entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) continue;
                try (var in = zip.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!text.isBlank()) return text.length() > 500 ? text.substring(0, 500) : text;
                }
            }
        } catch (Exception ignored) {
            // Unreadable/corrupt jar -> null, which the policy treats as "no permission established".
        }
        return null;
    }

    /** {@code fabric.mod.json}'s {@code license}, which may be a single id or an array of ids. */
    private String fabricLicense(java.util.zip.ZipFile zip) {
        var entry = zip.getEntry("fabric.mod.json");
        if (entry == null) return null;
        try (var in = zip.getInputStream(entry)) {
            var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();
            if (!json.has("license")) return null;
            var license = json.get("license");
            if (license == null || license.isJsonNull()) return null;
            if (license.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (var part : license.getAsJsonArray()) {
                    if (part == null || part.isJsonNull()) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(part.getAsString());
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            return license.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code META-INF/mods.toml}'s {@code license = "..."} line (Forge and NeoForge both emit one). */
    private String tomlLicense(java.util.zip.ZipFile zip) {
        var entry = zip.getEntry("META-INF/mods.toml");
        if (entry == null) return null;
        try (var in = zip.getInputStream(entry)) {
            String toml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("^\\s*license\\s*=\\s*\"([^\"]*)\"",
                    java.util.regex.Pattern.MULTILINE).matcher(toml);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
