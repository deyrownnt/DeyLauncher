package com.deylauncher.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Turns a CurseForge {@code (projectID, fileID)} pair into a real, downloadable file -- which is the
 * one thing a CurseForge modpack's {@code manifest.json} can't tell us on its own.
 *
 * <p>Two requests are all it takes, both key-less:
 * <ol>
 *   <li>{@code https://www.curseforge.com/api/v1/mods/<projectID>/files/<fileID>} answers with the
 *       file's own {@code fileName}, {@code fileLength} and {@code gameVersions} (including the
 *       {@code Client}/{@code Server} markers, i.e. a real client/server split). CurseForge's
 *       documented API needs a key, but this is the route their own website uses, so a pack can be
 *       installed without shipping anyone's API key.</li>
 *   <li>the file itself comes from CurseForge's CDN, whose layout is fully derivable:
 *       {@code https://edge.forgecdn.net/files/<fileID/1000>/<fileID%1000, 3 digits>/<fileName>}
 *       (verified: it 302-redirects to the mediafilez CDN and the body length matches
 *       {@code fileLength} exactly, so downloads can be size-checked).</li>
 * </ol>
 *
 * <p>Every answer is cached twice -- in memory and in {@code ~/.deylauncher/modpacks/cf-files.json} --
 * so re-installing or repairing a 300-mod pack costs zero requests for the mods already resolved.
 * Requests are paced at Modrinth's own 5/second steady rate, to stay polite to a host that isn't
 * documented as having a budget.
 *
 * <p>The network call is a constructor parameter so tests can drive the whole thing offline.
 */
public class CurseForgeFiles {

    private static final String API = "https://www.curseforge.com/api/v1/mods/";

    /** Steady request rate (5/s) -- same pacing the Modrinth client uses. */
    private static final long MIN_REQUEST_GAP_MS = 200;

    private static final Object PACE_LOCK = new Object();
    private static long lastRequestAt = 0L;

    /** fileID -> resolved file, shared across installs in this run. */
    private static final Map<Long, Resolved> MEMORY_CACHE = new ConcurrentHashMap<>();

    private static final Map<Long, Boolean> MEMORY_MISSES = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** One resolved CurseForge file: its name, its CDN URL, its size and what it runs on. */
    public record Resolved(long projectId, long fileId, String fileName, String url,
                           long sizeBytes, List<String> gameVersions) {

        private boolean has(String marker) {
            for (String g : gameVersions) {
                if (marker.equalsIgnoreCase(g)) return true;
            }
            return false;
        }

        /** Runs on a client: explicitly marked Client, or not marked either way (most resource packs). */
        public boolean clientOk() {
            if (has("Client")) return true;
            return !has("Server");
        }

        /** Runs on a server: explicitly marked Server, or not marked either way. */
        public boolean serverOk() {
            if (has("Server")) return true;
            return !has("Client");
        }
    }

    private final Path cacheFile;
    private final Function<String, String> fetchText;
    private final Map<Long, Resolved> diskCache = new ConcurrentHashMap<>();
    private volatile boolean diskCacheLoaded = false;
    private final Object diskLock = new Object();

    /** Resolver with no on-disk cache (used where a launcher root isn't known). */
    public CurseForgeFiles() {
        this(null, CurseForgeFiles::httpGetText);
    }

    /**
     * Resolver that remembers every answer under {@code launcherRoot/modpacks/cf-files.json}.
     *
     * @param launcherRoot the launcher's root folder ({@code ~/.deylauncher}), or null to skip the
     *                     on-disk cache.
     */
    public CurseForgeFiles(Path launcherRoot) {
        this(launcherRoot == null ? null : launcherRoot.resolve("modpacks").resolve("cf-files.json"),
                CurseForgeFiles::httpGetText);
    }

    /** Test seam: drive the resolver with any text fetcher instead of the real endpoint. */
    CurseForgeFiles(Path cacheFile, Function<String, String> fetchText) {
        this.cacheFile = cacheFile;
        this.fetchText = fetchText;
    }

    /**
     * Resolves one manifest entry, or null when CurseForge doesn't know it (a removed file, no
     * connection, rate-limited out after retries) -- callers then fall back to Modrinth by name.
     */
    public Resolved resolve(long projectId, long fileId) {
        if (projectId <= 0 || fileId <= 0) return null;
        Resolved cached = MEMORY_CACHE.get(fileId);
        if (cached != null) return cached;
        if (MEMORY_MISSES.containsKey(fileId)) return null;
        Resolved fromDisk = diskCache().get(fileId);
        if (fromDisk != null) {
            MEMORY_CACHE.put(fileId, fromDisk);
            return fromDisk;
        }
        Resolved resolved = parse(fetchText.apply(API + projectId + "/files/" + fileId), projectId, fileId);
        if (resolved == null) {
            MEMORY_MISSES.put(fileId, Boolean.TRUE);
        } else {
            MEMORY_CACHE.put(fileId, resolved);
            rememberOnDisk(resolved);
        }
        return resolved;
    }

    /** The CDN URL for a CurseForge file: derivable from the id alone (plus the real file name). */
    public static String cdnUrl(long fileId, String fileName) {
        long bucket = fileId / 1000;
        long remainder = fileId % 1000;
        return "https://edge.forgecdn.net/files/" + bucket + "/" + String.format("%03d", remainder)
                + "/" + encodeSegment(fileName);
    }

    /** True when a resolved file name looks like a mod jar rather than a pack/shader archive. */
    static boolean looksLikeModJar(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    // ---- internals ----

    private static Resolved parse(String body, long projectId, long fileId) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return null;
            JsonElement data = root.getAsJsonObject().get("data");
            if (data == null || !data.isJsonObject()) return null; // 404s answer {"data":null}
            JsonObject o = data.getAsJsonObject();
            String name = str(o, "fileName");
            if (name.isBlank()) return null;
            long size = o.has("fileLength") && o.get("fileLength").isJsonPrimitive()
                    ? o.get("fileLength").getAsLong() : -1L;
            List<String> gameVersions = new ArrayList<>();
            if (o.has("gameVersions") && o.get("gameVersions").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("gameVersions")) {
                    if (e != null && !e.isJsonNull()) gameVersions.add(e.getAsString());
                }
            }
            return new Resolved(projectId, fileId, name, cdnUrl(fileId, name), size, gameVersions);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<Long, Resolved> diskCache() {
        if (cacheFile == null) return diskCache;
        if (!diskCacheLoaded) {
            synchronized (diskLock) {
                if (!diskCacheLoaded) {
                    loadDiskCache();
                    diskCacheLoaded = true;
                }
            }
        }
        return diskCache;
    }

    private void loadDiskCache() {
        try {
            if (!Files.isRegularFile(cacheFile)) return;
            JsonElement root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) return;
            for (var e : root.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (v == null || !v.isJsonObject()) continue;
                JsonObject o = v.getAsJsonObject();
                long fileId = o.has("fileId") ? o.get("fileId").getAsLong() : -1L;
                String name = str(o, "fileName");
                if (fileId <= 0 || name.isBlank()) continue;
                long projectId = o.has("projectId") ? o.get("projectId").getAsLong() : -1L;
                long size = o.has("sizeBytes") ? o.get("sizeBytes").getAsLong() : -1L;
                List<String> gv = new ArrayList<>();
                if (o.has("gameVersions") && o.get("gameVersions").isJsonArray()) {
                    for (JsonElement g : o.getAsJsonArray("gameVersions")) {
                        if (g != null && !g.isJsonNull()) gv.add(g.getAsString());
                    }
                }
                diskCache.put(fileId, new Resolved(projectId, fileId, name, cdnUrl(fileId, name), size, gv));
            }
        } catch (Exception ignored) {
            // A corrupt/old cache file just means we re-ask CurseForge -- never worth failing over.
        }
    }

    private void rememberOnDisk(Resolved r) {
        if (cacheFile == null) return;
        synchronized (diskLock) {
            diskCache.put(r.fileId(), r);
            try {
                JsonObject root = new JsonObject();
                for (Resolved value : diskCache.values()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("projectId", value.projectId());
                    o.addProperty("fileId", value.fileId());
                    o.addProperty("fileName", value.fileName());
                    o.addProperty("sizeBytes", value.sizeBytes());
                    JsonArray gv = new JsonArray();
                    for (String g : value.gameVersions()) gv.add(g);
                    o.add("gameVersions", gv);
                    root.add(String.valueOf(value.fileId()), o);
                }
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, root.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Best-effort: the in-memory cache still saves the requests for this run.
            }
        }
    }

    /** GETs the endpoint, paced and retried -- returns null on anything that isn't a usable answer. */
    private static String httpGetText(String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            pace();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "DeyLauncher/0.8")
                        .timeout(Duration.ofSeconds(20))
                        .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 == 2) return resp.body();
                if (code == 429 || code == 503 || code >= 500) {
                    Thread.sleep(500L * (attempt + 1)); // rate limited / briefly unavailable -- retry
                    continue;
                }
                return null; // 403/404/... -- this file isn't reachable through this route
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                // Offline / timeout / DNS -- one more try is worth it, then give up quietly.
            }
        }
        return null;
    }

    /** Keeps consecutive CurseForge requests at a steady 5/second instead of bursting. */
    private static void pace() {
        long wait;
        synchronized (PACE_LOCK) {
            long now = System.currentTimeMillis();
            wait = Math.max(0L, lastRequestAt + MIN_REQUEST_GAP_MS - now);
            lastRequestAt = now + wait;
        }
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Percent-encodes one URL path segment (file names carry '+', spaces, brackets...). */
    private static String encodeSegment(String raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
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
}