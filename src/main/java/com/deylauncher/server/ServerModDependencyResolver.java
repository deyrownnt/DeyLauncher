package com.deylauncher.server;

import com.deylauncher.launch.DownloadProgress;
import com.deylauncher.modloader.MinecraftVersionRange;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

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
     *
     * <p>Simply Swords is the same story for a library it needs: it declares {@code simplytooltips},
     * while the project's slug is {@code simply-tooltips} and Modrinth's search returns nothing for
     * the raw id -- so without this entry the dependency was reported as "could not be resolved"
     * and the whole server refused to start. Architectury and Cardinal Components are the same kind of
     * case ({@code architectury} -> {@code architectury-api}, {@code cardinal-components} ->
     * {@code cardinal-components-api}) and matter because they are the libraries half of Fabric
     * depends on. Keep this list short and verified: an alias that points at the wrong project would
     * install the wrong mod, which is worse than leaving it missing.
     */
    private static final Map<String, String> SLUG_ALIASES = Map.of(
            "fabric", "fabric-api",
            "simplytooltips", "simply-tooltips",
            "architectury", "architectury-api",
            "cardinal-components", "cardinal-components-api");

    /** How many dependency "waves" are followed before giving up (a dep's own deps are a new wave). */
    private static final int MAX_ROUNDS = 4;

    /**
     * How deep jar-in-jar nesting is followed. Fabric loads every jar under {@code META-INF/jars/} as a
     * mod of its own, and mods nest their libraries there -- Cardinal Components ships its
     * {@code cardinal-components-base} / {@code -entity} modules that way, and BCLib carries WunderLib.
     * Without reading them the resolver reports dependencies that ARE installed as missing, which made
     * it install duplicates and (with the set-aside below) move perfectly good mods.
     */
    private static final int MAX_NESTED_DEPTH = 3;

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
                         List<String> errors, List<String> warnings, List<String> setAsideMods) {

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
            if (!setAsideMods.isEmpty()) sb.append(", ").append(setAsideMods.size()).append(" mod(s) set aside");
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
        List<String> setAsideMods = new ArrayList<>();

        String normalized = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("fabric") && !normalized.equals("quilt")) {
            // Forge/NeoForge describe their dependencies in META-INF/mods.toml, a different format we
            // don't claim to handle here; Vanilla/Purpur have no mods. A no-op, not an error.
            return new Result(0, 0, 0, 0, errors, warnings, setAsideMods);
        }
        if (serverDir == null) {
            return new Result(0, 0, 0, 0, errors, warnings, setAsideMods);
        }

        Path modsDir = serverDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            logIf(log, "No mods/ folder yet -- nothing to check for dependencies.");
            return new Result(0, 0, 0, 0, errors, warnings, setAsideMods);
        }

        // One read per jar: its own ids (id + provides) and its required dependencies. Jar-in-jar
        // libraries are folded in here, because Fabric treats them as installed mods and this pass has
        // to agree with Fabric or it will "fix" problems that do not exist.
        Map<Path, ModInfo> infos = new LinkedHashMap<>();
        Set<String> installedIds = new LinkedHashSet<>();
        int scannedMods = 0;
        for (Path jar : listJars(modsDir)) {
            scannedMods++;
            ModInfo info = readModInfo(readFabricJson(jar));
            if (info == null) continue; // not a Fabric mod (or unreadable) -- leave it alone
            info = withNestedMods(jar, info);
            infos.put(jar, info);
            installedIds.addAll(info.ids());
        }

        // Everything required by an installed mod that no installed mod provides, together with which
        // jar(s) declared it -- a dependency that cannot be supplied has to name the mod it breaks.
        Map<String, Dependency> missing = new LinkedHashMap<>();
        Map<String, List<Path>> requiringJars = new LinkedHashMap<>();
        for (var entry : infos.entrySet()) {
            // A client-only mod is not loaded on a dedicated server, so Fabric does not resolve its
            // dependencies there -- demanding them would install needless jars and could set the mod
            // aside over a dependency nothing was going to ask for.
            if (entry.getValue().clientOnly()) continue;
            for (Dependency dep : entry.getValue().deps()) {
                requiringJars.computeIfAbsent(dep.id(), k -> new ArrayList<>()).add(entry.getKey());
                if (installedIds.contains(dep.id()) || missing.containsKey(dep.id())) continue;
                missing.put(dep.id(), dep);
            }
        }

        int missingDepsFound = missing.size();
        if (missingDepsFound == 0) {
            logIf(log, "All required mod dependencies are present.");
            return new Result(scannedMods, 0, 0, 0, errors, warnings, setAsideMods);
        }

        logIf(log, missingDepsFound + " required mod dependency(ies) are missing; resolving via Modrinth...");

        int resolved = 0;
        int installed = 0;
        int discovered = Math.max(1, missing.size());
        int processed = 0;
        double lastReported = 0;
        // dep id -> why it could not be supplied; drives both the warning text and the set-aside note.
        Map<String, String> unresolved = new LinkedHashMap<>();

        for (int round = 0; round < MAX_ROUNDS && !missing.isEmpty(); round++) {
            List<Dependency> batch = new ArrayList<>(missing.values());
            missing.clear();
            for (Dependency dep : batch) {
                processed++;
                Resolution resolution = null;
                try {
                    resolution = resolveAndInstallDep(dep, mcVersion, modsDir);
                } catch (Exception e) {
                    errors.add("Failed to install dependency " + dep.id() + ": " + e.getMessage());
                }

                Path added = resolution == null ? null : resolution.installed();
                if (added == null) {
                    String reason = resolution == null ? "the download failed" : resolution.reason();
                    unresolved.put(dep.id(), reason);
                    warnings.add("Could not resolve " + dep.id()
                            + (dep.versionRange() == null || dep.versionRange().isBlank()
                               ? "" : " (" + dep.versionRange() + ")")
                            + " -- " + reason);
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

        // A hard dependency we could not supply makes Fabric refuse to start the ENTIRE server
        // ("Incompatible mods found! -- X requires Y, which is missing"), so leaving the requiring jar
        // in place buys nothing at all. It is moved aside instead: reversibly, and only when we are
        // sure it is the mod that names the dependency.
        setAsideMods.addAll(setAsideModsRequiring(unresolved, requiringJars, infos, modsDir, log));

        return new Result(scannedMods, missingDepsFound, resolved, installed, errors, warnings, setAsideMods);
    }

    /**
     * Moves the jars that need a dependency this launcher could not supply out of {@code mods/} into a
     * {@code mods-disabled/} folder next to it, returning one line per mod moved.
     *
     * <p>Why move rather than only warn: Fabric fails the whole launch over a single unsatisfied hard
     * dependency, so a mod whose library never got a build for this Minecraft version (a pack that
     * still ships a 1.20.1 addon built against a library that stopped at 1.18, say) makes the server
     * unstartable rather than merely degraded. Setting that one mod aside is the only end state that
     * lets the server actually run -- and it is deliberately reversible: the jar is moved, never
     * deleted, and the folder carries a README explaining the way back.
     */
    private static List<String> setAsideModsRequiring(Map<String, String> unresolved,
                                                      Map<String, List<Path>> requiringJars,
                                                      Map<Path, ModInfo> infos,
                                                      Path modsDir, Consumer<String> log) {
        List<String> moved = new ArrayList<>();
        if (unresolved.isEmpty() || modsDir.getParent() == null) return moved;

        Path disabledDir = modsDir.getParent().resolve("mods-disabled");
        Set<Path> alreadyMoved = new LinkedHashSet<>(); // one mod may lack several dependencies
        Set<String> idsOthersNeed = requiringJars.keySet();

        for (var entry : unresolved.entrySet()) {
            for (Path jar : requiringJars.getOrDefault(entry.getKey(), List.of())) {
                if (!alreadyMoved.add(jar)) continue;

                // Never move a library. A mod that fails on one dependency can still be the only
                // provider of ids other mods require -- Cardinal Components and PuzzlesLib are exactly
                // that -- and taking it away would break them in turn, turning one bad mod into a
                // cascade of "missing dependency" failures. Report and leave it; the user's real
                // problem is the unresolvable dependency, not this jar.
                List<String> neededIds = infos.containsKey(jar)
                        ? infos.get(jar).ids().stream().filter(idsOthersNeed::contains).sorted().toList()
                        : List.of();
                if (!neededIds.isEmpty()) {
                    logIf(log, "Left " + jar.getFileName() + " in place: other mods require "
                            + String.join(", ", neededIds) + ", which only it provides. The server will "
                            + "keep refusing to start until " + entry.getKey() + " is available, but "
                            + "removing this mod would break those too.");
                    continue;
                }

                try {
                    Files.createDirectories(disabledDir);
                    Files.move(jar, disabledDir.resolve(jar.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    moved.add(jar.getFileName() + " (requires " + entry.getKey()
                            + " -- " + entry.getValue() + ")");
                    logIf(log, "Set aside " + jar.getFileName() + ": it requires " + entry.getKey()
                            + " (" + entry.getValue() + "). Move it back into mods/ once that dependency "
                            + "exists for this Minecraft version.");
                } catch (IOException e) {
                    logIf(log, "Couldn't set aside " + jar.getFileName() + " (" + e.getMessage()
                            + ") -- the server will still refuse to start until " + entry.getKey()
                            + " is available.");
                }
            }
        }
        if (!moved.isEmpty()) writeDisabledReadme(disabledDir);
        return moved;
    }

    /** Explains the set-aside folder to whoever finds it later, written once per folder. */
    private static void writeDisabledReadme(Path disabledDir) {
        Path readme = disabledDir.resolve("README.txt");
        if (Files.exists(readme)) return;
        String text = """
                DeyLauncher moved the mods in this folder here.

                They require a dependency that has no build for this server's Minecraft version and mod
                loader, and Fabric refuses to start the whole server when a hard dependency is missing --
                so the server could not run at all while they were in mods/.

                Nothing was deleted. To put a mod back, move its .jar up one level into the server's
                mods/ folder. The server will fail to start again unless the dependency it needs is
                found for this Minecraft version first.
                """;
        try {
            Files.writeString(readme, text);
        } catch (IOException ignored) {
            // The moves themselves are what matter; a missing README costs nothing.
        }
    }

    /**
     * Resolves one dependency through Modrinth and downloads it into {@code modsDir}, saying WHY when
     * it fails: "not on Modrinth at all" and "on Modrinth, but with no build for this Minecraft
     * version" are completely different problems for the user, and the old single "could not resolve"
     * line left them identical.
     */
    private Resolution resolveAndInstallDep(Dependency dep, String mcVersion, Path modsDir)
            throws Exception {
        ModrinthClient.Hit hit = resolveProject(dep);
        if (hit == null || hit.slug() == null || hit.slug().isBlank()) {
            return new Resolution(null, "no Modrinth project matches this id");
        }

        List<ModrinthClient.ProjectVersion> versions;
        try {
            versions = modrinth.compatibleVersionsLenient(hit.slug(), mcVersion);
        } catch (Exception e) {
            return new Resolution(null, "the Modrinth lookup failed (" + e.getMessage() + ")");
        }
        // Modrinth answers newest first, and a Fabric dependency must be built for Fabric.
        List<ModrinthClient.ProjectVersion> fabric = versions.stream()
                .filter(v -> v.supportsLoader("fabric"))
                .toList();
        if (fabric.isEmpty()) {
            return new Resolution(null, versions.isEmpty()
                    ? hit.slug() + " has no build for Minecraft " + mcVersion
                    : hit.slug() + " has a build for Minecraft " + mcVersion + ", but not for Fabric");
        }

        ModrinthClient.ProjectVersion chosen = pickBestVersion(fabric, dep.versionRange());
        if (chosen == null) return new Resolution(null, "no usable build was published");

        ModrinthClient.FileRef jar = chosen.firstJar();
        if (jar == null || jar.url() == null || jar.url().isBlank()) {
            return new Resolution(null, "the matching build ships no jar file");
        }

        String fileName = jar.filename() == null || jar.filename().isBlank()
                ? dep.id() + ".jar" : jar.filename();
        Path dest = modsDir.resolve(fileName);
        if (Files.exists(dest)) return new Resolution(dest, null); // already fetched under this exact name

        modrinth.download(jar.url(), fileName, modsDir);
        return Files.exists(dest)
                ? new Resolution(dest, null)
                : new Resolution(null, "the download did not complete");
    }

    /** One dependency attempt: the jar that got installed, or the reason none could be. */
    private record Resolution(Path installed, String reason) {}

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
        // Some mods declare a library id that is a readable rendering of the project's slug
        // ("simply_tooltips" for "simply-tooltips"). Searching that spelling finds projects the raw id
        // misses entirely -- and trustedName() still rejects anything that isn't an exact match, so
        // this can never install a lookalike.
        String readable = dep.id().replace('_', ' ').replace('-', ' ').trim();
        if (!readable.equalsIgnoreCase(dep.id())) {
            ModrinthClient.Hit byReadable = modrinth.firstHitByName(readable, "mod");
            if (trustedName(readable, byReadable)) return byReadable;
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

        // A client-only mod is never loaded on a dedicated server, so Fabric never resolves its
        // dependencies there either. The flag lets the caller skip them while still counting the id --
        // Fabric DOES require a client-only jar to be present to satisfy another mod's hard dependency
        // on it (the simplytooltips case this whole class exists for), which is why "ids yes, deps no"
        // is exactly the distinction that matters.
        String environment = stringValue(obj.get("environment"));
        boolean clientOnly = environment != null && environment.equalsIgnoreCase("client");
        return new ModInfo(ids, deps, clientOnly);
    }

    /**
     * One installed mod's metadata plus everything it nests under {@code META-INF/jars/} -- Fabric loads
     * each of those as a mod of its own, so their ids satisfy dependencies and their dependencies must
     * be resolved too.
     */
    private static ModInfo withNestedMods(Path jar, ModInfo top) {
        if (top.clientOnly()) return top; // a disabled client mod's nested jars stay client-side too
        List<ModInfo> nested = readNestedModInfos(jar);
        if (nested.isEmpty()) return top;
        Set<String> ids = new LinkedHashSet<>(top.ids());
        List<Dependency> deps = new ArrayList<>(top.deps());
        for (ModInfo inner : nested) {
            ids.addAll(inner.ids());
            if (!inner.clientOnly()) deps.addAll(inner.deps());
        }
        return new ModInfo(ids, deps, false);
    }

    /** Every nested mod found inside {@code jar}, following nested-in-nested to {@link #MAX_NESTED_DEPTH}. */
    private static List<ModInfo> readNestedModInfos(Path jar) {
        List<ModInfo> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isNestedJarEntry(entry.getName())) continue;
                try (var in = zip.getInputStream(entry)) {
                    out.addAll(readModInfosFromBytes(in.readAllBytes(), 0));
                } catch (Exception ignored) {
                    // A nested jar we can't read just isn't counted; the outer jar still is.
                }
            }
        } catch (Exception ignored) {
            // Unreadable nesting is not an error -- it only means fewer ids are known.
        }
        return out;
    }

    /** Fabric's nested-jar location. Anything else (Forge's {@code META-INF/jarjar/}) is not loaded. */
    private static boolean isNestedJarEntry(String name) {
        return name != null && name.startsWith("META-INF/jars/") && name.endsWith(".jar");
    }

    private static List<ModInfo> readModInfosFromBytes(byte[] jarBytes, int depth) {
        List<ModInfo> out = new ArrayList<>();
        if (depth > MAX_NESTED_DEPTH) return out;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if ("fabric.mod.json".equals(entry.getName())) {
                    ModInfo info = readModInfo(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
                    if (info != null) out.add(info);
                } else if (isNestedJarEntry(entry.getName())) {
                    out.addAll(readModInfosFromBytes(zin.readAllBytes(), depth + 1));
                }
            }
        } catch (Exception ignored) {
            // Same as above: partial information is fine, a wrong "missing" is not.
        }
        return out;
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
    record ModInfo(Set<String> ids, List<Dependency> deps, boolean clientOnly) {
    }
}
