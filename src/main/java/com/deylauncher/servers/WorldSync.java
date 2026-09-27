package com.deylauncher.servers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moves a server between a PC and the shared repo: pack it, chunk it, push it; fetch it, verify it,
 * unpack it.
 *
 * <p><b>What gets packed, and what deliberately does not.</b> Everything a world and its setup need (the
 * {@code world} folder, {@code server.properties}, ops/whitelist/bans, player data, addons, configs, the
 * icon) travels. Everything re-obtainable or machine-specific stays behind: the server software jars and
 * Forge/NeoForge {@code libraries/} and {@code versions/} folders (hundreds of MB that
 * {@link com.deylauncher.server.ServerDownloader} already fetches on demand), plus logs and crash
 * reports. That one rule is what keeps a synced server a sensible size instead of a copy of a whole
 * installation.
 *
 * <p><b>Two modes, and the second one is admitted to the user.</b> A full push carries the world; if the
 * archive is beyond {@link WorldChunker#MAX_TOTAL_BYTES} the push is retried as <i>settings-only</i> and
 * the returned {@link Outcome} says so, because silently syncing "most of" a world would be worse than
 * syncing none of it.
 */
public class WorldSync {

    /** Shown in the UI when a world was too big to push in full. */
    public static final String SETTINGS_ONLY_NOTE = "The full world archive was too large for the shared "
            + "repo, so only the server's settings, player data, addons and configs were uploaded.";

    /** Progress callback for the UI; {@code fraction} is 0..1, or negative when it is unknown. */
    public interface Progress {
        void report(String message, double fraction);

        /** A no-op sink for headless callers and tests. */
        Progress SILENT = (message, fraction) -> {
        };
    }

    /** What a push actually did -- including whether it had to downgrade. */
    public record Outcome(boolean settingsOnly, String fileName, long totalBytes, int parts, String note) {
        /** A sentence for the console/log. */
        public String describe() {
            return (settingsOnly ? "Settings-only sync" : "World sync") + " complete: "
                    + WorldChunker.humanSize(totalBytes) + " in " + parts + " part"
                    + (parts == 1 ? "" : "s") + (settingsOnly ? " -- " + note : "");
        }
    }

    /**
     * Top-level directories that never travel: the server software and its loader machinery (every host
     * re-installs those locally) plus logs, which are worthless to anyone else and grow without bound.
     */
    private static final List<String> SKIP_DIRS = List.of(
            "libraries", "versions", "logs", "crash-reports", "cache", "run", ".gradle", ".fabric",
            "backups", "backup");

    /** Root files that are software or noise rather than server state. */
    private static final List<String> SKIP_ROOT_FILES = List.of(
            "server.jar", "purpur.jar", "fabric-server-launch.jar", "fabric-server-launcher.jar",
            "paper.jar", "spigot.jar", "eula.txt");

    /** Root files a settings-only push still carries, because they ARE the server's configuration. */
    private static final List<String> SETTINGS_ROOT_FILES = List.of(
            "server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
            "server-icon.png", "bukkit.yml", "spigot.yml", "paper.yml", "pufferfish.yml",
            "commands.yml", "permissions.yml", "usercache.json", "usernamecache.json");

    /** Directories a settings-only push still carries (addons, configs, per-player progress). */
    private static final List<String> SETTINGS_DIRS = List.of(
            "mods", "plugins", "config", "datapacks", "resourcepacks", "scripts", "kubejs",
            "defaultconfigs");

    private WorldSync() {
    }

    /** The {@code level-name} from {@code server.properties}, or {@code "world"} when it isn't set. */
    public static String levelName(Path serverDir) {
        Path props = serverDir.resolve("server.properties");
        if (!Files.exists(props)) return "world";
        try {
            for (String line : Files.readAllLines(props)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("level-name=")) {
                    String value = trimmed.substring("level-name=".length()).trim();
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (IOException ignored) {
            // An unreadable properties file just means we fall back to the vanilla default name.
        }
        return "world";
    }

    /** Builds the zip for {@code serverDir} in memory, honouring {@code settingsOnly}. */
    public static byte[] buildArchive(Path serverDir, String levelName, boolean settingsOnly)
            throws IOException {
        List<Path> files = collect(serverDir, levelName, settingsOnly);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Path file : files) {
                String entryName = serverDir.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Every file that should travel, in a stable order. The selection rules are the class-level ones:
     * settings-only keeps the world's metadata/player data plus addons and configs, a full push keeps
     * everything except the software, libraries and logs.
     */
    public static List<Path> collect(Path serverDir, String levelName, boolean settingsOnly)
            throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(serverDir)) return out;
        String level = (levelName == null || levelName.isBlank()) ? "world" : levelName.trim();
        try (var walk = Files.walk(serverDir)) {
            List<Path> all = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : all) {
                String rel = serverDir.relativize(file).toString().replace('\\', '/');
                if (shouldInclude(rel, level, settingsOnly)) out.add(file);
            }
        }
        return out;
    }

    /** The include/exclude decision for one server-relative path. Package-private so tests can pin it. */
    static boolean shouldInclude(String relativePath, String levelName, boolean settingsOnly) {
        if (relativePath == null || relativePath.isBlank()) return false;
        String rel = relativePath.replace('\\', '/');
        String firstSegment = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
        String lower = rel.toLowerCase(Locale.ROOT);

        if (!firstSegment.isEmpty() && SKIP_DIRS.contains(firstSegment.toLowerCase(Locale.ROOT))) return false;
        if (lower.endsWith(".log") || lower.endsWith(".log.gz")) return false;
        // A root-level jar is the server software itself; jars deeper in (mods/, plugins/) are addons.
        if (firstSegment.isEmpty() && lower.endsWith(".jar")) return false;
        if (firstSegment.isEmpty() && SKIP_ROOT_FILES.contains(lower)) return false;

        if (!settingsOnly) return true;

        if (firstSegment.isEmpty()) return SETTINGS_ROOT_FILES.contains(lower);
        if (SETTINGS_DIRS.contains(firstSegment.toLowerCase(Locale.ROOT))) return true;
        // Inside the world folder, keep the bits that hold progress and structure, not the region files
        // -- those are exactly the bulk that a settings-only push exists to avoid.
        if (!firstSegment.equalsIgnoreCase(levelName)) return false;
        String withinWorld = rel.substring(firstSegment.length() + 1).toLowerCase(Locale.ROOT);
        return withinWorld.equals("level.dat")
                || withinWorld.equals("level.dat_old")
                || withinWorld.startsWith("playerdata/")
                || withinWorld.startsWith("advancements/")
                || withinWorld.startsWith("stats/")
                || withinWorld.startsWith("data/");
    }

    // ---------------------------------------------------------------------------------------------
    // Push / pull
    // ---------------------------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Repo path of the world manifest for a server. */
    public static String manifestPath(String serverId) {
        return serverId + "/world/manifest.json";
    }

    /** Repo path of one world part for a server. */
    public static String partPath(String serverId, String partName) {
        return serverId + "/world/" + partName;
    }

    /**
     * Packs {@code serverDir} and uploads it, downgrading to a settings-only push when the archive is
     * beyond the size limit. {@code progress} may be null.
     */
    public static Outcome push(ServersRepository repo, String serverId, Path serverDir, String pushedBy,
                        String minecraftVersion, String serverType, Progress progress) throws Exception {
        Progress sink = progress == null ? Progress.SILENT : progress;
        String level = levelName(serverDir);

        sink.report("Packing the server...", 0.05);
        byte[] payload = buildArchive(serverDir, level, false);
        boolean settingsOnly = false;
        String note = null;
        if (payload.length > WorldChunker.MAX_TOTAL_BYTES) {
            settingsOnly = true;
            note = SETTINGS_ONLY_NOTE + " (The full archive was "
                    + WorldChunker.humanSize(payload.length) + ".)";
            sink.report("World is too large for the shared repo -- packing settings only...", 0.12);
            payload = buildArchive(serverDir, level, true);
            if (payload.length > WorldChunker.MAX_TOTAL_BYTES) {
                throw new IllegalStateException("Even the settings-only archive is "
                        + WorldChunker.humanSize(payload.length)
                        + ", which is too large to upload. Remove some addons or modpacks first.");
            }
        }

        String fileName = WorldChunker.archiveName(level, System.currentTimeMillis());
        WorldManifest manifest = WorldChunker.manifestFor(payload, settingsOnly, fileName, pushedBy, note,
                WorldChunker.DEFAULT_PART_BYTES);
        manifest.minecraftVersion = minecraftVersion;
        manifest.serverType = serverType;

        // Remember the previous part count so parts of a now-smaller world don't linger and get
        // concatenated into the next download.
        WorldManifest previous = readManifest(repo, serverId);
        int previousParts = previous == null ? 0 : previous.partCount;

        int parts = manifest.parts.size();
        for (int i = 0; i < parts; i++) {
            WorldManifest.Part part = manifest.parts.get(i);
            int offset = i * WorldChunker.DEFAULT_PART_BYTES;
            byte[] bytes = java.util.Arrays.copyOfRange(payload, offset, offset + (int) part.size);
            String path = partPath(serverId, part.name);
            String sha = repo.read(path).sha();
            repo.writeBytes(path, bytes, sha, "DeyLauncher: world for " + serverId + " " + part.name);
            sink.report("Uploading " + part.name + " (" + WorldChunker.humanSize(part.size) + ")...",
                    0.15 + 0.8 * ((i + 1.0) / parts));
        }
        for (int i = parts; i < previousParts; i++) {
            repo.delete(partPath(serverId, WorldChunker.partName(i)),
                    "DeyLauncher: drop stale world part for " + serverId);
        }

        sink.report("Writing the manifest...", 0.97);
        ServersRepository.Snapshot existing = repo.read(manifestPath(serverId));
        repo.write(manifestPath(serverId), GSON.toJson(manifest), existing.sha(),
                "DeyLauncher: world manifest for " + serverId);
        sink.report("Sync complete.", 1.0);
        return new Outcome(settingsOnly, fileName, manifest.totalBytes, parts, note);
    }

    /** The pushed world manifest for a server, or null when nothing was ever pushed. */
    public static WorldManifest readManifest(ServersRepository repo, String serverId) throws Exception {
        String text = repo.read(manifestPath(serverId)).text();
        if (text == null || text.isBlank()) return null;
        try {
            WorldManifest manifest = GSON.fromJson(text, WorldManifest.class);
            if (manifest == null || !manifest.isUsable()) return null;
            return manifest;
        } catch (RuntimeException e) {
            // A manifest we cannot read means "no usable snapshot", never a crash on the Servers page.
            return null;
        }
    }

    /**
     * Downloads the latest pushed snapshot and unpacks it into {@code serverDir}, verifying every part and
     * the whole archive before a single file is written -- an interrupted or tampered download must never
     * leave somebody with half a world. Returns the manifest it applied, or null when nothing was pushed.
     */
    public static WorldManifest pull(ServersRepository repo, String serverId, Path serverDir,
                                     Progress progress) throws Exception {
        Progress sink = progress == null ? Progress.SILENT : progress;
        WorldManifest manifest = readManifest(repo, serverId);
        if (manifest == null) return null;

        List<WorldManifest.Part> ordered = manifest.orderedParts();
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            WorldManifest.Part part = ordered.get(i);
            sink.report("Downloading " + part.name + "...", 0.05 + 0.75 * ((i + 1.0) / ordered.size()));
            byte[] bytes = repo.readBytes(partPath(serverId, part.name));
            if (bytes == null) {
                throw new IllegalStateException("World part " + part.name
                        + " is missing from the shared repo -- ask the owner to sync the server again.");
            }
            if (part.sha256 != null && !part.sha256.equalsIgnoreCase(WorldChunker.sha256(bytes))) {
                throw new IllegalStateException("World part " + part.name
                        + " failed its checksum -- try again, or ask the owner to sync again.");
            }
            chunks.add(bytes);
        }

        byte[] archive = WorldChunker.join(chunks, manifest.totalBytes, manifest.sha256);
        sink.report("Unpacking...", 0.85);
        Files.createDirectories(serverDir);
        extract(archive, serverDir);
        sink.report("Download complete.", 1.0);
        return manifest;
    }

    /** Extracts a zip into {@code targetDir}, refusing entries that try to escape it (zip-slip). */
    public static void extract(byte[] archive, Path targetDir) throws IOException {
        Path base = targetDir.normalize();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = base.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(base)) {
                    throw new IOException("Refused an unsafe path inside the downloaded archive: "
                            + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                if (resolved.getParent() != null) Files.createDirectories(resolved.getParent());
                Files.copy(zip, resolved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
