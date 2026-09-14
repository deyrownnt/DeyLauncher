package com.deylauncher.modpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the modpack the user added into a {@link ModpackInfo}: which Minecraft version and loader it
 * was built for, what has to be downloaded, and what is already bundled inside it.
 *
 * Deliberately offline-only (no network at all) so a read can't hang the UI on a bad connection --
 * the one thing that may need the internet (an {@code .mrpack}'s icon, which the format doesn't
 * carry) is fetched separately by {@link PackIcons}.
 *
 * Supported layouts are listed on {@link ModpackFormat}; the readers below all share the same
 * helpers, so a folder and a zip of the same pack behave identically.
 */
public final class ModpackReader {

    private ModpackReader() {}

    /** Top-level folders a Minecraft client instance uses, so a pack with NO metadata of its own
     *  can't scatter arbitrary junk through the instance. Packs that declare their own paths (every
     *  real pack format) bypass this list and install exactly what they list. */
    private static final Set<String> GAME_FOLDERS = Set.of(
            "mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks", "shaders", "kubejs",
            "scripts", "datapacks", "saves", "local", "openloader", "patchouli_books", "customnpcs",
            "journeymap", "mapconfig", "world", "worlds", "serverconfig", "tacz", "blueprints",
            "computercraft", "computers", "flansmod", "tconstruct", "xaerominimap", "xaeroworldmap",
            "replaymod", "essential", "iris", "sodium", "crash-reports", "logs", ".minecraft");

    /** Root-level files a client instance uses (options/keybinds/etc.). */
    private static final Set<String> ROOT_GAME_FILES = Set.of(
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old",
            "hotbar.nbt", "commandhistory.txt", "realms_persistence.json", "usercache.json",
            "sodium-options.json", "iris.properties");

    /**
     * Reads a pack (zip or already-extracted folder) into a {@link ModpackInfo}. Throws with a
     * user-readable message when the file isn't a pack we recognise.
     */
    public static ModpackInfo read(Path pack) throws IOException {
        ModpackFormat format = ModpackFormat.detect(pack);
        if (format == ModpackFormat.UNKNOWN) {
            throw new IOException("This doesn't look like a modpack -- expected a Modrinth .mrpack, a "
                    + "CurseForge .zip, a MultiMC/Prism instance export, or an instance folder.");
        }
        if (format == ModpackFormat.FOLDER) {
            // A folder holds no clue in its name, so sniff its metadata to pick the right reader.
            if (Files.isRegularFile(pack.resolve("modrinth.index.json"))) return readModrinth(pack, true, format);
            if (Files.isRegularFile(pack.resolve("manifest.json"))) return readCurseForge(pack, true);
            if (Files.isRegularFile(pack.resolve("mmc-pack.json")) || Files.isRegularFile(pack.resolve("instance.cfg"))) {
                return readMultiMc(pack, true);
            }
            if (Files.isRegularFile(pack.resolve("instance.json"))) return readATLauncher(pack, true);
            return readGeneric(pack, format, "This folder has no pack metadata, so every mod/config "
                    + "file inside it is installed as-is.");
        }
        return switch (format) {
            case MODRINTH_MRPACK, MODRINTH_ZIP -> readModrinth(pack, false, format);
            case CURSEFORGE_ZIP -> readCurseForge(pack, false);
            case MULTIMC_PRISM_ZIP -> readMultiMc(pack, false);
            case ATLAUNCHER_ZIP -> readATLauncher(pack, false);
            default -> readGeneric(pack, format, "This zip has no pack metadata, so every mod/config "
                    + "file inside it is installed as-is.");
        };
    }

    // ---- Modrinth (.mrpack / Modrinth-flavoured zip) ----

    private static ModpackInfo readModrinth(Path pack, boolean folder, ModpackFormat format) throws IOException {
        JsonObject index = readJson(pack, folder, "modrinth.index.json");
        if (index == null) {
            return readGeneric(pack, format, "No modrinth.index.json inside this pack, so every "
                    + "mod/config file in it is installed as-is.");
        }
        String name = str(index, "name");
        if (name.isBlank()) name = fallbackName(pack);
        String version = str(index, "versionId");

        JsonObject deps = obj(index, "dependencies");
        String mc = str(deps, "minecraft");
        String loader = null, loaderVersion = null;
        if (deps != null) {
            // These keys are the Modrinth pack spec's loader ids, exactly as the launcher names them
            // (except NeoForge/Quilt, which we report as unsupported instead of mis-installing).
            if (deps.has("fabric-loader")) { loader = "Fabric"; loaderVersion = str(deps, "fabric-loader"); }
            else if (deps.has("forge")) { loader = "Forge"; loaderVersion = str(deps, "forge"); }
            else if (deps.has("neoforge")) { loader = "NeoForge"; loaderVersion = str(deps, "neoforge"); }
            else if (deps.has("quilt-loader")) { loader = "Quilt"; loaderVersion = str(deps, "quilt-loader"); }
        }

        List<ModpackFile> downloads = new ArrayList<>();
        for (JsonObject f : objArray(index, "files")) {
            String path = safeRel(str(f, "path"));
            if (path == null) continue;
            String url = "";
            JsonElement dl = f.get("downloads");
            if (dl != null && dl.isJsonArray()) {
                for (JsonElement d : dl.getAsJsonArray()) {
                    if (d != null && !d.isJsonNull() && !d.getAsString().isBlank()) { url = d.getAsString(); break; }
                }
            }
            JsonObject hashes = obj(f, "hashes");
            String sha1 = hashes == null ? "" : str(hashes, "sha1");
            String sha512 = hashes == null ? "" : str(hashes, "sha512");
            long size = f.has("fileSize") && f.get("fileSize").isJsonPrimitive() ? f.get("fileSize").getAsLong() : -1L;
            JsonObject env = obj(f, "env");
            boolean clientUnsupported = env != null && "unsupported".equalsIgnoreCase(str(env, "client"));
            boolean serverUnsupported = env != null && "unsupported".equalsIgnoreCase(str(env, "server"));
            downloads.add(ModpackFile.download(path, url, sha1, sha512, size,
                    !clientUnsupported, clientUnsupported, !serverUnsupported, serverUnsupported));
        }

        // overrides/ (pack spec v1) plus the v1.1 split folders, if the author used them.
        List<ModpackFile> bundled = new ArrayList<>();
        bundled.addAll(bundledEntries(pack, folder, "overrides/", false, true, true));
        bundled.addAll(bundledEntries(pack, folder, "client-overrides/", false, true, false));
        bundled.addAll(bundledEntries(pack, folder, "server-overrides/", false, false, true));

        return new ModpackInfo(format, name, version, mc,
                loader == null ? "Vanilla" : loader, loaderVersion,
                downloads, bundled, 0, null, pack, null);
    }

    // ---- CurseForge ----

    private static ModpackInfo readCurseForge(Path pack, boolean folder) throws IOException {
        JsonObject manifest = readJson(pack, folder, "manifest.json");
        if (manifest == null) {
            return readGeneric(pack, ModpackFormat.CURSEFORGE_ZIP, "manifest.json couldn't be read, so "
                    + "every mod/config file in this pack is installed as-is.");
        }
        String name = str(manifest, "name");
        if (name.isBlank()) name = fallbackName(pack);
        String version = str(manifest, "version");

        JsonObject mcObj = obj(manifest, "minecraft");
        String mc = mcObj == null ? "" : str(mcObj, "version");
        String loader = null, loaderVersion = null;
        if (mcObj != null) {
            JsonObject chosen = null, first = null;
            for (JsonObject l : objArray(mcObj, "modLoaders")) {
                if (first == null) first = l;
                if (l.has("primary") && l.get("primary").isJsonPrimitive() && l.get("primary").getAsBoolean()) {
                    chosen = l;
                    break;
                }
            }
            if (chosen == null) chosen = first;
            if (chosen != null) {
                // "forge-47.2.0" / "fabric-0.15.7" / "neoforge-21.1.0" -- split on the FIRST dash,
                // since the version part has dots (and occasionally dashes) of its own.
                String id = str(chosen, "id");
                int dash = id.indexOf('-');
                if (dash > 0) { loader = id.substring(0, dash); loaderVersion = id.substring(dash + 1); }
                else if (!id.isBlank()) loader = id;
            }
        }
        loader = canonicalLoader(loader);

        String overridesRoot = str(manifest, "overrides");
        if (overridesRoot.isBlank()) overridesRoot = "overrides";
        List<ModpackFile> bundled = new ArrayList<>(
                bundledEntries(pack, folder, withTrailingSlash(overridesRoot), false, true, true));

        int unresolved = objArray(manifest, "files").size();
        String note = unresolved == 0 ? null
                : "CurseForge lists its " + unresolved + " pack file(s) by project/file id only, and "
                + "resolving those needs a CurseForge API key DeyLauncher doesn't ship -- the pack's "
                + "bundled (overrides) files install normally; the rest need the pack author's own "
                + "overrides or the Modrinth edition of the pack.";
        return new ModpackInfo(ModpackFormat.CURSEFORGE_ZIP, name, version, mc,
                loader == null ? "Vanilla" : loader, loaderVersion,
                List.of(), bundled, unresolved, null, pack, note);
    }

    // ---- MultiMC / Prism Launcher exported instance ----

    private static ModpackInfo readMultiMc(Path pack, boolean folder) throws IOException {
        JsonObject mmc = readJson(pack, folder, "mmc-pack.json");
        String mc = "", loader = null, loaderVersion = null;
        if (mmc != null) {
            for (JsonObject c : objArray(mmc, "components")) {
                String uid = str(c, "uid");
                String ver = str(c, "version");
                // Component uids are MultiMC's own naming; the loader ones line up one-to-one with
                // the launcher's loaders (except NeoForge/Quilt -- reported, never mis-installed).
                switch (uid) {
                    case "net.minecraft" -> mc = ver;
                    case "net.fabricmc.fabric-loader" -> { loader = "Fabric"; loaderVersion = ver; }
                    case "net.minecraftforge" -> { loader = "Forge"; loaderVersion = ver; }
                    case "net.neoforged" -> { loader = "NeoForge"; loaderVersion = ver; }
                    case "org.quiltmc.quilt-loader" -> { loader = "Quilt"; loaderVersion = ver; }
                    default -> { }
                }
            }
        }
        String name = instanceCfgName(pack, folder);
        if (name.isBlank()) name = fallbackName(pack);

        // MultiMC keeps every game file in a "minecraft/" subfolder of the exported instance.
        List<ModpackFile> bundled = new ArrayList<>(
                bundledEntries(pack, folder, "minecraft/", true, true, true));
        if (bundled.isEmpty()) bundled.addAll(bundledEntries(pack, folder, ".minecraft/", true, true, true));
        if (bundled.isEmpty()) bundled.addAll(allEntries(pack, folder, true, true, true));

        return new ModpackInfo(ModpackFormat.MULTIMC_PRISM_ZIP, name, "", mc,
                loader == null ? "Vanilla" : loader, loaderVersion,
                List.of(), bundled, 0, null, pack,
                "Imported from a MultiMC / Prism instance -- its mods and config are installed into a "
                + "DeyLauncher instance of the same Minecraft version and loader.");
    }

    // ---- ATLauncher ----

    private static ModpackInfo readATLauncher(Path pack, boolean folder) throws IOException {
        JsonObject inst = readJson(pack, folder, "instance.json");
        String name = inst == null ? "" : str(inst, "name");
        if (name.isBlank()) name = fallbackName(pack);
        String mc = "";
        String loader = null;
        if (inst != null) {
            for (String key : List.of("minecraft", "mcVersion", "minecraftVersion", "version")) {
                mc = str(inst, key);
                if (!mc.isBlank()) break;
            }
            loader = canonicalLoader(str(inst, "loader"));
        }
        List<ModpackFile> bundled = allEntries(pack, folder, true, true, true);
        return new ModpackInfo(ModpackFormat.ATLAUNCHER_ZIP, name, "", mc,
                loader == null ? "Vanilla" : loader, null,
                List.of(), bundled, 0, null, pack, null);
    }

    // ---- Anything else: treat every mod/config-looking file as bundled ----

    private static ModpackInfo readGeneric(Path pack, ModpackFormat format, String note) throws IOException {
        boolean folder = format == ModpackFormat.FOLDER;
        List<ModpackFile> bundled = allEntries(pack, folder, true, true, true);
        return new ModpackInfo(format, fallbackName(pack), "", "", "Vanilla", null,
                List.of(), bundled, 0, null, pack, note);
    }

    // ---- shared reading helpers (one implementation, so a folder and a zip behave identically) ----

    /** Parses one JSON entry out of the pack (a zip entry, or a file inside the folder), or null. */
    private static JsonObject readJson(Path source, boolean folder, String entry) throws IOException {
        if (folder) {
            Path file = source.resolve(entry);
            if (!Files.isRegularFile(file)) return null;
            try {
                JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                return el.isJsonObject() ? el.getAsJsonObject() : null;
            } catch (Exception e) {
                return null;
            }
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null || e.isDirectory()) return null;
            try (var in = zip.getInputStream(e)) {
                JsonElement el = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return el.isJsonObject() ? el.getAsJsonObject() : null;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Every regular file under {@code prefix} as a bundled {@link ModpackFile}, with the prefix
     * stripped so the reported path is relative to the install root.
     *
     * @param whitelistOnly keep only paths a Minecraft instance actually uses -- on for the
     *                      no-metadata packs, off for real pack formats that declare their own paths.
     */
    private static List<ModpackFile> bundledEntries(Path source, boolean folder, String prefix,
                                                   boolean whitelistOnly, boolean clientOk, boolean serverOk)
            throws IOException {
        List<ModpackFile> out = new ArrayList<>();
        if (folder) {
            Path base = prefix.isEmpty() ? source : source.resolve(stripTrailingSlash(prefix));
            if (!Files.isDirectory(base)) return out;
            try (var walk = Files.walk(base)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String full = source.relativize(p).toString().replace('\\', '/');
                    String target = safeRel(stripPrefix(full, prefix));
                    if (target == null || isPackMetadata(target)) continue;
                    if (whitelistOnly && !isGamePath(target)) continue;
                    // archiveEntry doubles as "where to read it from": an absolute path for folders,
                    // a zip entry name for archives (see ModpackInstaller).
                    out.add(ModpackFile.bundled(target, p.toAbsolutePath().toString(), Files.size(p), clientOk, serverOk));
                }
            }
            return out;
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!prefix.isEmpty() && !n.startsWith(prefix)) continue;
                String target = safeRel(stripPrefix(n, prefix));
                if (target == null || isPackMetadata(target)) continue;
                if (whitelistOnly && !isGamePath(target)) continue;
                out.add(ModpackFile.bundled(target, n, e.getSize(), clientOk, serverOk));
            }
        }
        return out;
    }

    private static List<ModpackFile> allEntries(Path source, boolean folder, boolean whitelistOnly,
                                               boolean clientOk, boolean serverOk) throws IOException {
        return bundledEntries(source, folder, "", whitelistOnly, clientOk, serverOk);
    }

    /** True for paths a Minecraft instance actually uses (see GAME_FOLDERS / ROOT_GAME_FILES). */
    private static boolean isGamePath(String rel) {
        String p = rel.replace('\\', '/');
        int slash = p.indexOf('/');
        if (slash < 0) return ROOT_GAME_FILES.contains(p.toLowerCase(Locale.ROOT));
        String top = p.substring(0, slash).toLowerCase(Locale.ROOT);
        return GAME_FOLDERS.contains(top) && !isPackMetadata(p);
    }

    /** Files that belong to the pack itself (its metadata/icon/the archive) rather than to the game,
     *  so they're never copied into an instance or a server folder. */
    static boolean isPackMetadata(String rel) {
        if (rel == null) return true;
        String p = rel.replace('\\', '/');
        if (p.contains("/")) return false; // pack metadata only ever sits at the pack root
        String lower = p.toLowerCase(Locale.ROOT);
        return lower.equals("modrinth.index.json") || lower.equals("manifest.json")
                || lower.equals("mmc-pack.json") || lower.equals("instance.cfg")
                || lower.equals("instance.json") || lower.equals("modlist.html")
                || lower.equals("icon.png") || lower.equals("pack.png") || lower.equals("logo.png")
                || lower.equals("overrides.json")
                || lower.endsWith(".mrpack") || lower.endsWith(".zip");
    }

    /** Normalises a pack-declared relative path, rejecting anything that tries to escape the install
     *  folder (".."), so a broken or hostile pack can't write outside the instance/server folder. */
    static String safeRel(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isBlank()) return null;
        for (String part : p.split("/")) {
            if (part.equals("..")) return null;
        }
        return p;
    }

    private static String stripPrefix(String entry, String prefix) {
        if (prefix.isEmpty()) return entry;
        return entry.startsWith(prefix) ? entry.substring(prefix.length()) : entry;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String withTrailingSlash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }

    /** The display name out of a MultiMC/Prism instance.cfg ("name=My Pack"), or "". */
    private static String instanceCfgName(Path pack, boolean folder) {
        try {
            String text = folder
                    ? (Files.isRegularFile(pack.resolve("instance.cfg")) ? Files.readString(pack.resolve("instance.cfg")) : null)
                    : readZipText(pack, "instance.cfg");
            if (text == null) return "";
            for (String line : text.split("\n")) {
                if (line.startsWith("name=")) return line.substring(5).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String readZipText(Path zip, String entry) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null || e.isDirectory()) return null;
            try (var in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static String fallbackName(Path pack) {
        String n = pack == null || pack.getFileName() == null ? "" : pack.getFileName().toString();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.isBlank()) return "Modpack";
        return n.replace('_', ' ').replace('-', ' ').trim();
    }

    /** Maps whatever a format calls its loader to the name the launcher uses, or null if unknown.
     *  NeoForge is checked before Forge because "neoforge" contains "forge". */
    private static String canonicalLoader(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String l = raw.toLowerCase(Locale.ROOT);
        if (l.contains("neoforge")) return "NeoForge";
        if (l.contains("quilt")) return "Quilt";
        if (l.contains("forge")) return "Forge";
        if (l.contains("fabric")) return "Fabric";
        if (l.contains("vanilla")) return "Vanilla";
        return null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        try {
            JsonElement el = o.get(key);
            return el == null || el.isJsonNull() ? "" : el.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement el = o.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static List<JsonObject> objArray(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o == null || !o.has(key)) return out;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : el.getAsJsonArray()) {
            if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        return out;
    }
}