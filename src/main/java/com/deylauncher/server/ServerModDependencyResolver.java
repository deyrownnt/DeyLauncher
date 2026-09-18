package com.deylauncher.server;

import com.deylauncher.launch.DownloadProgress;
import com.deylauncher.modloader.MinecraftVersionRange;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds and installs the mod dependencies a Fabric server's {@code mods/} folder is missing.
 *
 * <p>Why this exists: a modpack installed onto a server only carries the files the pack ships, and a
 * pack's manifest frequently assumes the SERVER already has the library mods its mods depend on
 * (Antique Atlas, Simply Tooltips, TabAPI, Fabric API, ...). Without them Fabric refuses to start with
 * an "Incompatible mod set" error. This class reads each installed jar's own {@code fabric.mod.json},
 * works out which required ids are not present, and fetches each missing one from Modrinth -- the same
 * source the launcher's own mod search uses, so no API key is required.
 *
 * <p>It is deliberately conservative: it never removes or replaces anything, only adds jars that are
 * genuinely missing; a dependency it can't confidently identify (or that has no build for this
 * Minecraft version/loader) is reported as a warning instead of guessed at. Dependencies are followed
 * transitively (a fetched dependency's own dependencies are checked too), bounded by
 * {@link #MAX_ROUNDS} so a cycle can never loop forever.
 *
 * <p>Only Fabric-family servers are handled. The launcher's {@link ServerType} has no Quilt constant,
 * so in practice {@code loader} is always {@code "Fabric"}; anything else is a no-op.
 */
public class ServerModDependencyResolver {

    /**
     * Dependency ids that name the game or the loader rather than a mod, so they must never be
     * fetched from Modrinth. {@code "fabric"} is deliberately NOT here -- it is Fabric API's own mod
     * id and is aliased to its Modrinth project in {@link #SLUG_ALIASES}.
     */
    private static final Set<String> NON_MOD_IDS = Set.of(
            "minecraft", "java", "fabricloader", "quiltloader", "quilt", "forge", "neoforge");

    /**
     * Mod ids that are not the Modrinth slug of the project they belong to. Fabric API publishes one
     * project under the slug {@code fabric-api} but its own mod id is {@code fabric}, which is why
     * every mod that "depends on Fabric API" names an id that is not a real Modrinth project.
     */
    private static final Map<String, String> SLUG_ALIASES = Map.of(
            "fabric", "fabric-api");

    /** How many dependency "waves" are followed before giving up (a dep's own deps are a new wave). */
    private static final int MAX_ROUNDS = 4;

    private final ModrinthClient modrinth;

    public ServerModDependencyResolver() {
        this(new ModrinthClient());
    }

    /** Test seam: a Modrinth client that never touches the network. */
    ServerModDependencyResolver(ModrinthClient modrinth) {
        this.modrinth = modrinth;
    }

    /** Result of one resolution pass. */
    public record Result(int scannedMods, int missingDepsFound, int resolved, int installed,
                         List<String> errors, List<String> warnings) {

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Scanned ").append(scannedMods).append(" mods, ")
              .append(missingDepsFound).append(" missing required deps");
            if (resolved > 0) sb.append(", ").append(resolved).append(" resolved via Modrinth");
            if (installed > 0) sb.append(", ").append(installed).append(" installed");
            if (!warnings.isEmpty()) sb.append(", ").append(warnings.size()).append(" could not be resolved");
            if (!errors.isEmpty()) sb.append(", ").append(errors.size()).append(" errors");
            return sb.toString();
        }
    }

    /**
     * Scans {@code serverDir/mods}, finds required dependencies that are not installed, and fetches
     * them from Modrinth.
     *
     * @param serverDir the server's root directory (its {@code mods/} folder is scanned)
     * @param mcVersion the server's Minecraft version (e.g. {@code "1.20.1"})
     * @param loader    the server's loader name ({@code "Fabric"}); anything else is a no-op
     * @param progress  optional progress callback (0.0 -&gt; 1.0)
     * @param log       optional sink for notable events (what was found / installed)
     */
    public Result resolveAndInstall(Path serverDir, String mcVersion, String loader,
                                    DownloadProgress progress, Consumer<String> log) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String normalized = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("fabric") && !normalized.equals("quilt")) {
            // Forge/NeoForge describe their dependencies in META-INF/mods.toml, a different format we
            // don't claim to handle here; Vanilla/Purpur have no mods. A no-op, not an error.
            return new Result(0, 0, 0, 0, errors, warnings);
        }
        if (serverDir == null) {
            return new Result(0, 0, 0, 0, errors, warnings);
        }

        Path modsDir = serverDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            logIf(log, "No mods/ folder yet -- nothing to check for dependencies.");
            return new Result(0, 0, 0, 0, errors, warnings);
        }

        // One read per jar: its own ids (id + provides) and its required dependencies.
        Map<Path, ModInfo> infos = new LinkedHashMap<>();
        Set<String> installedIds = new LinkedHashSet<>();
        int scannedMods = 0;
        for (Path jar : listJars(modsDir)) {
            scannedMods++;
            ModInfo info = readModInfo(readFabricJson(jar));
            if (info == null) continue; // not a Fabric mod (or unreadable) -- leave it alone
            infos.put(jar, info);
            installedIds.addAll(info.ids());
        }

        // Everything required by an installed mod that no installed mod provides.
        Map<String, Dependency> missing = new LinkedHashMap<>();
        for (ModInfo info : infos.values()) {
            for (Dependency dep : info.deps()) {
                if (installedIds.contains(dep.id()) || missing.containsKey(dep.id())) continue;
                missing.put(dep.id(), dep);
            }
        }

        int missingDepsFound = missing.size();
        if (missingDepsFound == 0) {
            logIf(log, "All required mod dependencies are present.");
            return new Result(scannedMods, 0, 0, 0, errors, warnings);
        }

        logIf(log, missingDepsFound + " required mod dependency(ies) are missing; resolving via Modrinth...");

        int resolved = 0;
        int installed = 0;
        int discovered = Math.max(1, missing.size());
        int processed = 0;
        double lastReported = 0;

        for (int round = 0; round < MAX_ROUNDS && !missing.isEmpty(); round++) {
            List<Dependency> batch = new ArrayList<>(missing.values());
            missing.clear();
            for (Dependency dep : batch) {
                processed++;
                Path added = null;
                try {
                    added = resolveAndInstallDep(dep, mcVersion, modsDir).orElse(null);
                } catch (Exception e) {
                    errors.add("Failed to install dependency " + dep.id() + ": " + e.getMessage());
                }

                if (added == null) {
                    warnings.add("Could not resolve " + dep.id()
                            + (dep.versionRange() == null || dep.versionRange().isBlank()
                               ? "" : " (" + dep.versionRange() + ")"));
                } else {
                    resolved++;
                    installed++;
                    logIf(log, "Installed missing dependency: " + dep.id() + " -> " + added.getFileName());
                    // A dependency can have dependencies of its own -- check the jar we just added.
                    ModInfo info = readModInfo(readFabricJson(added));
                    if (info != null) {
                        installedIds.addAll(info.ids());
                        for (Dependency nested : info.deps()) {
                            if (installedIds.contains(nested.id()) || missing.containsKey(nested.id())) continue;
                            missing.put(nested.id(), nested);
                            discovered++;
                        }
                    }
                }

                // Monotonic: a wave that discovers new work must never make the bar jump backwards.
                double fraction = Math.min(0.99, processed / (double) discovered);
                if (progress != null && fraction > lastReported) {
                    lastReported = fraction;
                    progress.onProgress(fraction);
                }
            }
        }

        return new Result(scannedMods, missingDepsFound, resolved, installed, errors, warnings);
    }

    /**
     * Resolves one dependency through Modrinth and downloads it into {@code modsDir}, returning the
     * installed path (empty when the mod is not on Modrinth, has no build for this version/loader, or
     * the download failed).
     */
    private Optional<Path> resolveAndInstallDep(Dependency dep, String mcVersion, Path modsDir)
            throws Exception {
        ModrinthClient.Hit hit = resolveProject(dep);
        if (hit == null || hit.slug() == null || hit.slug().isBlank()) return Optional.empty();

        List<ModrinthClient.ProjectVersion> versions;
        try {
            versions = modrinth.compatibleVersionsLenient(hit.slug(), mcVersion);
        } catch (Exception e) {
            return Optional.empty();
        }
        // Modrinth answers newest first, and a Fabric dependency must be built for Fabric.
        List<ModrinthClient.ProjectVersion> fabric = versions.stream()
                .filter(v -> v.supportsLoader("fabric"))
                .toList();
        if (fabric.isEmpty()) return Optional.empty();

        ModrinthClient.ProjectVersion chosen = pickBestVersion(fabric, dep.versionRange());
        if (chosen == null) return Optional.empty();

        ModrinthClient.FileRef jar = chosen.firstJar();
        if (jar == null || jar.url() == null || jar.url().isBlank()) return Optional.empty();

        String fileName = jar.filename() == null || jar.filename().isBlank()
                ? dep.id() + ".jar" : jar.filename();
        Path dest = modsDir.resolve(fileName);
        if (Files.exists(dest)) return Optional.of(dest); // already fetched under this exact name

        modrinth.download(jar.url(), fileName, modsDir);
        return Files.exists(dest) ? Optional.of(dest) : Optional.empty();
    }

    /**
     * Finds the Modrinth project a declared dependency id refers to. Exact slug lookups only (with the
     * id's usual underscore/hyphen spellings, plus known aliases), and a name search is accepted only
     * when it matches the id exactly once normalised -- a fuzzy hit would silently install the wrong
     * mod, which is worse than leaving the pack short a dependency it can be told about.
     */
    private ModrinthClient.Hit resolveProject(Dependency dep) {
        for (String candidate : dep.slugCandidates()) {
            ModrinthClient.Hit hit = modrinth.projectByExactSlug(candidate, "mod");
            if (hit != null) return hit;
        }
        ModrinthClient.Hit hit = modrinth.firstHitByName(dep.id(), "mod");
        return trustedName(dep.id(), hit) ? hit : null;
    }

    private static boolean trustedName(String wanted, ModrinthClient.Hit hit) {
        if (hit == null) return false;
        String key = normalizeName(wanted);
        return !key.isEmpty()
                && (key.equals(normalizeName(hit.slug())) || key.equals(normalizeName(hit.name())));
    }

    private static String normalizeName(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * The newest build whose own version satisfies the declared range, falling back to the newest
     * build when nothing satisfies it (a dependency declaring a range for a version it was never
     * published under is still far more useful installed than missing).
     */
    private static ModrinthClient.ProjectVersion pickBestVersion(
            List<ModrinthClient.ProjectVersion> versions, String range) {
        ModrinthClient.ProjectVersion fallback = null;
        for (ModrinthClient.ProjectVersion v : versions) {
            if (v.versionNumber() == null || v.versionNumber().isBlank()) continue;
            if (fallback == null) fallback = v;
            if (MinecraftVersionRange.matches(range, cleanVersion(v.versionNumber()))) return v;
        }
        return fallback;
    }

    /** A mod version without its build metadata / pre-release tail, so a range can be compared to it. */
    private static String cleanVersion(String version) {
        int cut = version.length();
        int plus = version.indexOf('+');
        if (plus >= 0) cut = Math.min(cut, plus);
        int dash = version.indexOf('-');
        if (dash >= 0) cut = Math.min(cut, dash);
        return cut > 0 && cut < version.length() ? version.substring(0, cut) : version;
    }

    private static List<Path> listJars(Path modsDir) {
        try (var stream = Files.list(modsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String readFabricJson(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads one {@code fabric.mod.json} into its ids (its own id plus everything it {@code provides})
     * and its required dependencies. Package-private and string-based so a test can exercise the
     * parsing without a real jar or any network access.
     *
     * @return the parsed info, or null when the text is not a readable Fabric metadata object
     */
    static ModInfo readModInfo(String fabricJson) {
        if (fabricJson == null || fabricJson.isBlank()) return null;
        JsonObject obj;
        try {
            obj = JsonParser.parseString(fabricJson).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }

        Set<String> ids = new LinkedHashSet<>();
        String id = stringValue(obj.get("id"));
        if (id != null) ids.add(id.toLowerCase(Locale.ROOT));
        JsonElement provides = obj.get("provides");
        if (provides != null && provides.isJsonArray()) {
            for (JsonElement el : provides.getAsJsonArray()) {
                String provided = stringValue(el);
                if (provided != null) ids.add(provided.toLowerCase(Locale.ROOT));
            }
        }

        List<Dependency> deps = new ArrayList<>();
        JsonElement depends = obj.get("depends");
        if (depends != null && depends.isJsonObject()) {
            JsonObject depsObject = depends.getAsJsonObject();
            for (String depId : depsObject.keySet()) {
                if (isNotAMod(depId)) continue;
                deps.add(new Dependency(depId, versionRange(depsObject.get(depId))));
            }
        }
        return new ModInfo(ids, deps);
    }

    /** True for ids that name the game/the loader or a Fabric API sub-module rather than a project. */
    private static boolean isNotAMod(String depId) {
        if (depId == null || depId.isBlank()) return true;
        String id = depId.toLowerCase(Locale.ROOT);
        if (NON_MOD_IDS.contains(id)) return true;
        // Fabric API is one project but hundreds of module ids ("fabric-api-base",
        // "fabric-lifecycle-events-v1", ...): each module is not separately installable. "fabric-api"
        // itself is the real project, so it is kept and resolved through the alias above.
        return id.startsWith("fabric-") && !id.equals("fabric-api");
    }

    /**
     * The version constraint of one {@code depends} entry. Fabric's metadata allows a plain string or
     * an array of alternatives; the first usable value is taken, and an unparseable entry is treated
     * as "any version".
     */
    private static String versionRange(JsonElement el) {
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                String value = stringValue(item);
                if (value != null) return value;
            }
            return "*";
        }
        String value = stringValue(el);
        return value == null ? "*" : value;
    }

    private static String stringValue(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        try {
            String s = el.getAsString();
            return s == null || s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static void logIf(Consumer<String> log, String message) {
        if (log != null) log.accept(message);
    }

    /** A required dependency declared by a mod: its id (usually a Modrinth slug) and version range. */
    public record Dependency(String id, String versionRange) {

        /** The id spelled the ways a Modrinth slug might be: as declared, underscored, or hyphenated. */
        List<String> slugCandidates() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            String alias = SLUG_ALIASES.get(id.toLowerCase(Locale.ROOT));
            if (alias != null) out.add(alias);
            out.add(id);
            out.add(id.replace('_', '-'));
            out.add(id.replace("-", ""));
            return new ArrayList<>(out);
        }
    }

    /** Everything read from one jar's {@code fabric.mod.json}: what it is and what it needs. */
    record ModInfo(Set<String> ids, List<Dependency> deps) {
    }
}
