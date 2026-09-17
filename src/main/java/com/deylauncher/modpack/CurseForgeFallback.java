package com.deylauncher.modpack;

import com.deylauncher.server.ModrinthClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A KEY-LESS, best-effort fallback for mods that Modrinth can't serve, used only after Modrinth has
 * already failed to provide a compatible build.
 *
 * <p>DeyLauncher never ships or asks for a content-provider API key, so CurseForge itself can only be
 * consulted the way its own website's keyless route works (see {@link CurseForgeFiles}, which resolves
 * a file's name/size from a project/file id pair). The missing piece for a mod that only has a NAME is
 * a way to find it -- and CurseForge's keyless search endpoint answers HTTP 403 to clients, so it can't
 * be used. {@code api.cfwidget.com} is a free, public, third-party mirror of CurseForge project
 * metadata that answers keyless; it is used here for exactly one narrow job:
 *
 * <blockquote>
 *   given an EXACT project slug and the Minecraft version + loader we need, which published
 *   CurseForge file matches?
 * </blockquote>
 *
 * <p>What this class deliberately does NOT do:
 * <ul>
 *   <li><b>no fuzzy matching</b> -- only an exact slug (the mod's own declared id) is ever looked up,
 *       so a near-match can't install a different mod than the one the user has;</li>
 *   <li><b>no permission decisions</b> -- it returns a candidate and stops. Whether we may download it
 *       is decided by {@link ModDistributionPolicy} from the mod's own declared license, and a mod that
 *       doesn't establish permission is never fetched (see that class for why the keyless routes can't
 *       state a per-file distribution flag);</li>
 *   <li><b>no hard dependency</b> -- every failure (offline, unknown slug, no matching build, HTTP
 *       error, unparseable answer) is simply "no candidate", so the caller keeps its previous
 *       behaviour and reports that automatic fixing isn't available for that mod.</li>
 * </ul>
 *
 * <p>Answers are cached in memory and in {@code <root>/modpacks/cf-widget.json}, requests are paced to
 * one per 200ms and retried on 429/503, and the network call itself is injectable so tests never touch
 * the internet -- the same shape as {@link CurseForgeFiles}.
 */
public class CurseForgeFallback {

    private static final String API = "https://api.cfwidget.com/minecraft/mc-mods/";

    /** Steady request rate (5/s), matching the pacing the other content-source clients use. */
    private static final long MIN_REQUEST_GAP_MS = 200;

    private static final Object PACE_LOCK = new Object();
    private static long lastRequestAt = 0L;

    /**
     * {@code slug|mc|loader} -> the remembered answer, where an empty Optional is a remembered
     * "couldn't resolve".
     *
     * <p>Per-instance on purpose, and an {@link java.util.Optional} rather than a plain candidate
     * because a "miss" has to be remembered too: the lookup is pace-limited, and a pack full of mods
     * CurseForge doesn't know would otherwise ask about each of them on every Fix run. A
     * {@code ConcurrentHashMap} can't hold a null value, so storing the miss directly would throw --
     * which is exactly what it used to do, turning "this mod can't be resolved" into an exception.
     *
     * <p>NOT static: a shared cache would be consulted before the (injectable) fetcher, so one
     * instance's answer would leak into another's -- wrong in tests, and wrong for any code that
     * deliberately drives this class with a different source. The on-disk cache is what carries
     * knowledge between runs.
     */
    private final Map<String, java.util.Optional<Candidate>> memoryCache = new ConcurrentHashMap<>();

    /** CurseForge CDN downloads: streamed to a .part file, size-checked, retried. */
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * One published CurseForge file that matches the requested Minecraft version + loader.
     *
     * <p>{@code url()} is built by {@link CurseForgeFiles#cdnUrl} -- the single place in this codebase
     * that knows CurseForge's keyless download layout -- so both CurseForge paths agree on it.
     */
    public record Candidate(long projectId, long fileId, String projectTitle, String fileName,
                            long sizeBytes, boolean clientOk, boolean serverOk, String versionLabel) {

        /** Where the file lives, or null when the answer had no usable name. */
        public String url() {
            return fileName == null || fileName.isBlank() ? null : CurseForgeFiles.cdnUrl(fileId, fileName);
        }
    }
    private static final String USER_AGENT = "DeyLauncher/0.9";

    private final Path cacheFile;
    private final Function<String, String> fetchText;
    private final Map<String, Candidate> diskCache = new ConcurrentHashMap<>();
    private volatile boolean diskCacheLoaded = false;
    private final Object diskLock = new Object();

    /** Fallback with no on-disk cache (used where a launcher root isn't known). */
    public CurseForgeFallback() {
        this(null, CurseForgeFallback::httpGetText);
    }

    /**
     * Fallback that remembers each answer under {@code launcherRoot/modpacks/cf-widget.json}, so a
     * second autofix run for the same mods costs no requests.
     *
     * @param launcherRoot the launcher's root folder ({@code ~/.deylauncher}), or null to skip the cache.
     */
    public CurseForgeFallback(Path launcherRoot) {
        this(launcherRoot == null ? null : launcherRoot.resolve("modpacks").resolve("cf-widget.json"),
                CurseForgeFallback::httpGetText);
    }

    /**
     * Test/harness seam: drive the lookup with any text fetcher (and any cache location) instead of the
     * real service, so the whole fallback can be exercised offline. Public because
     * {@code InstalledModResolverTest} lives in its own package, exactly like the resolver's own seam.
     */
    public CurseForgeFallback(Path cacheFile, Function<String, String> fetchText) {
        this.cacheFile = cacheFile;
        this.fetchText = fetchText;
    }

    /**
     * The newest CurseForge build of the project at {@code slug} that is published for {@code mcVersion}
     * and {@code loader}, or null when nothing usable can be established.
     *
     * @param slug      the mod's EXACT project slug (its own declared id) -- never a search term.
     * @param mcVersion the Minecraft version the instance runs.
     * @param loader    the loader the instance runs ("Fabric"/"Forge"/"NeoForge"); a build whose tags
     *                  don't name it is skipped rather than guessed at.
     */
    public Candidate byModSlug(String slug, String mcVersion, String loader) {
        if (slug == null || slug.isBlank() || mcVersion == null || mcVersion.isBlank()) return null;
        String key = cacheKey(slug, mcVersion, loader);

        // A remembered answer (including a remembered miss) wins; otherwise fall to the on-disk cache,
        // then to the service.
        java.util.Optional<Candidate> remembered = memoryCache.get(key);
        if (remembered != null) return remembered.orElse(null);
        Candidate cached = diskCache().get(key);
        if (cached != null) {
            memoryCache.put(key, java.util.Optional.of(cached));
            return cached;
        }

        Candidate found = parse(fetchText.apply(API + encodeSlug(slug)), mcVersion, loader);
        // A miss is remembered too (as an empty Optional -- see memoryCache), so a pack full of mods
        // CurseForge doesn't know asks about each of them once, not on every run.
        memoryCache.put(key, java.util.Optional.ofNullable(found));
        if (found != null) rememberOnDisk(key, found);
        return found;
    }

    /**
     * Downloads a candidate into {@code targetDir}, size-checked (so a truncated answer or an error page
     * is never mistaken for a mod) and retried a few times. Returns the written file, or null on failure.
     *
     * <p>Only ever called for a candidate the caller has already cleared through
     * {@link ModDistributionPolicy} -- this method makes no permission judgement of its own.
     */
    public Path download(Candidate candidate, Path targetDir) {
        String url = candidate == null ? null : candidate.url();
        if (url == null || targetDir == null) return null;
        try {
            Files.createDirectories(targetDir);
        } catch (Exception e) {
            return null;
        }
        Path dest = targetDir.resolve(candidate.fileName());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");

        for (int attempt = 0; attempt < DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(part);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofMinutes(5))
                        .GET().build();
                HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(part));
                if (resp.statusCode() / 100 != 2) {
                    Files.deleteIfExists(part);
                    continue;
                }
                long size = Files.size(part);
                if (size <= 0 || (candidate.sizeBytes() > 0 && size != candidate.sizeBytes())) {
                    Files.deleteIfExists(part);
                    continue;
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return dest;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }
// ---- parsing / selection ----

    /**
     * Picks the file to use out of a CFWidget project answer. Package-private so tests drive the
     * selection with canned JSON, exactly like {@code CurseForgeFilesTest} does for the id route.
     *
     * <p>Selection is strict on purpose: the file must be a .jar, must carry a game-version tag that
     * matches (or is the family of) {@code mcVersion}, and must name {@code loader} in its tags. A
     * release build always wins over a beta/alpha one; within a tier the newest upload wins.
     */
    static Candidate parse(String body, String mcVersion, String loader) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject project = root.getAsJsonObject();
            long projectId = project.has("id") ? project.get("id").getAsLong() : -1L;
            if (projectId <= 0) return null;
            String title = str(project, "title");
            JsonElement filesEl = project.get("files");
            if (filesEl == null || !filesEl.isJsonArray()) return null;

            Candidate bestRelease = null, bestFallback = null;
            String bestReleaseAt = null, bestFallbackAt = null;
            for (JsonElement el : filesEl.getAsJsonArray()) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject f = el.getAsJsonObject();
                String name = str(f, "name");
                long fileId = f.has("id") ? f.get("id").getAsLong() : -1L;
                if (fileId <= 0 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;

                List<String> tags = tags(f);
                if (!matchesVersion(tags, mcVersion) || !matchesLoader(tags, loader)) continue;

                Candidate candidate = new Candidate(projectId, fileId, title, name,
                        f.has("filesize") ? f.get("filesize").getAsLong() : -1L,
                        clientOk(tags), serverOk(tags), str(f, "display"));
                String uploadedAt = str(f, "uploaded_at");
                String type = str(f, "type").toLowerCase(Locale.ROOT);
                if ("release".equals(type)) {
                    if (isNewer(uploadedAt, bestReleaseAt) || bestRelease == null) {
                        bestRelease = candidate;
                        bestReleaseAt = uploadedAt;
                    }
                } else if (!"alpha".equals(type)) {
                    if (bestFallback == null || isNewer(uploadedAt, bestFallbackAt)) {
                        bestFallback = candidate;
                        bestFallbackAt = uploadedAt;
                    }
                }
            }
            // A release build is always preferred; otherwise the newest beta (alpha only as a last resort).
            if (bestRelease != null) return bestRelease;
            if (bestFallback != null) return bestFallback;
            return bestAnyAlpha(filesEl, mcVersion, loader, projectId, title);
        } catch (Exception e) {
            return null; // malformed/renamed answer -> "couldn't resolve", which the caller reports
        }
    }

    /** Alpha builds: used only when a project publishes nothing but alphas for this version. */
    private static Candidate bestAnyAlpha(JsonElement filesEl, String mcVersion, String loader,
                                          long projectId, String title) {
        Candidate best = null;
        String bestAt = null;
        for (JsonElement el : filesEl.getAsJsonArray()) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            String name = str(f, "name");
            long fileId = f.has("id") ? f.get("id").getAsLong() : -1L;
            if (fileId <= 0 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            List<String> tags = tags(f);
            if (!matchesVersion(tags, mcVersion) || !matchesLoader(tags, loader)) continue;
            if (best == null || isNewer(str(f, "uploaded_at"), bestAt)) {
                best = new Candidate(projectId, fileId, title, name,
                        f.has("filesize") ? f.get("filesize").getAsLong() : -1L,
                        clientOk(tags), serverOk(tags), str(f, "display"));
                bestAt = str(f, "uploaded_at");
            }
        }
        return best;
    }

    /** A file's own tags: game versions plus the "Client"/"Server" markers, as published. */
    private static List<String> tags(JsonObject file) {
        List<String> out = new ArrayList<>();
        if (file.has("versions") && file.get("versions").isJsonArray()) {
            for (JsonElement v : file.getAsJsonArray("versions")) {
                if (v != null && !v.isJsonNull()) out.add(v.getAsString());
            }
        }
        return out;
    }

    /** True when a tag matches the wanted Minecraft version exactly or as its family (1.21 => 1.21.1). */
    private static boolean matchesVersion(List<String> tags, String mcVersion) {
        for (String tag : tags) {
            if (ModrinthClient.matchesMinecraftVersion(tag, mcVersion)) return true;
        }
        return false;
    }

    /** True when a tag names the loader we run. NeoForge is checked before Forge ("neoforge" has "forge"). */
    private static boolean matchesLoader(List<String> tags, String loader) {
        if (loader == null || loader.isBlank()) return true;
        String want = loader.replace("-", "").toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (tag.replace("-", "").equalsIgnoreCase(want)) return true;
        }
        return false;
    }

    /** A file marked Client, or not marked either way -- mirrors {@code CurseForgeFiles.Resolved}. */
    private static boolean clientOk(List<String> tags) {
        for (String t : tags) if ("Client".equalsIgnoreCase(t)) return true;
        for (String t : tags) if ("Server".equalsIgnoreCase(t)) return false;
        return true;
    }

    private static boolean serverOk(List<String> tags) {
        for (String t : tags) if ("Server".equalsIgnoreCase(t)) return true;
        for (String t : tags) if ("Client".equalsIgnoreCase(t)) return false;
        return true;
    }

    /** ISO-8601 upload timestamps compare correctly as strings; a missing one loses. */
    private static boolean isNewer(String uploadedAt, String thanUploadedAt) {
        if (uploadedAt == null) return false;
        if (thanUploadedAt == null) return true;
        return uploadedAt.compareTo(thanUploadedAt) > 0;
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

    /** Percent-encodes the slug so a stray character can't turn into a different request path. */
    private static String encodeSlug(String slug) {
        StringBuilder sb = new StringBuilder();
        for (byte b : slug.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }

    // ---- cache ----

    private static String cacheKey(String slug, String mcVersion, String loader) {
        return slug.toLowerCase(Locale.ROOT) + "|" + mcVersion.toLowerCase(Locale.ROOT) + "|"
                + (loader == null ? "" : loader.toLowerCase(Locale.ROOT));
    }

    private Map<String, Candidate> diskCache() {
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
                String name = str(o, "fileName");
                long fileId = o.has("fileId") ? o.get("fileId").getAsLong() : -1L;
                if (name.isBlank() || fileId <= 0) continue;
                diskCache.put(e.getKey(), new Candidate(
                        o.has("projectId") ? o.get("projectId").getAsLong() : -1L, fileId,
                        str(o, "projectTitle"), name,
                        o.has("sizeBytes") ? o.get("sizeBytes").getAsLong() : -1L,
                        !o.has("clientOk") || o.get("clientOk").getAsBoolean(),
                        !o.has("serverOk") || o.get("serverOk").getAsBoolean(),
                        str(o, "versionLabel")));
            }
        } catch (Exception ignored) {
            // A corrupt/old cache file just means we ask again -- never worth failing over.
        }
    }

    private void rememberOnDisk(String key, Candidate candidate) {
        if (cacheFile == null) return;
        synchronized (diskLock) {
            diskCache.put(key, candidate);
            try {
                JsonObject root = new JsonObject();
                for (var entry : diskCache.entrySet()) {
                    Candidate c = entry.getValue();
                    JsonObject o = new JsonObject();
                    o.addProperty("projectId", c.projectId());
                    o.addProperty("fileId", c.fileId());
                    o.addProperty("projectTitle", c.projectTitle());
                    o.addProperty("fileName", c.fileName());
                    o.addProperty("sizeBytes", c.sizeBytes());
                    o.addProperty("clientOk", c.clientOk());
                    o.addProperty("serverOk", c.serverOk());
                    o.addProperty("versionLabel", c.versionLabel());
                    root.add(entry.getKey(), o);
                }
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, root.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Best-effort: the in-memory cache still saves the requests for this run.
            }
        }
    }

    // ---- the real fetch ----

    /** GETs the project page, paced and retried -- null on anything that isn't a usable answer. */
    private static String httpGetText(String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            pace();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 == 2) return resp.body();
                if (code == 429 || code == 503 || code >= 500) {
                    Thread.sleep(500L * (attempt + 1)); // rate limited / briefly unavailable -- retry
                    continue;
                }
                return null; // 403/404/... -- not resolvable through this route
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                // Offline / timeout / DNS -- one more try is worth it, then give up quietly.
            }
        }
        return null;
    }

    /** Keeps consecutive CFWidget requests at a steady 5/second instead of bursting. */
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
}
