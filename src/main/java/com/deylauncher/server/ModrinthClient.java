package com.deylauncher.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for Modrinth's free public API (no key required). Used by the server Addons
 * tab to search for mods/plugins from inside the launcher and auto-pick a release that matches
 * the server's own Minecraft version. Networking is the JDK's built-in java.net.http -- the same
 * stack the rest of DeyLauncher already uses -- so no new dependency is introduced.
 */
public class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2";
    private final HttpClient http = HttpClient.newHttpClient();

    /** A single search result (a project). */
    public record Hit(String slug, String name, String author, int downloads, String description, String iconUrl) {}

    /** A downloadable file inside a project version. */
    public record FileRef(String url, String filename, long sizeBytes) {}

    /** One published version of a project, with the Minecraft versions it supports and its files. */
    public record ProjectVersion(String id, String name, String versionNumber,
                                 List<String> gameVersions, List<FileRef> files) {}

    /**
     * The Modrinth project_type string for a server kind: "mod" for Fabric/Forge, "plugin" for
     * Purpur. These map to the facets the search endpoint filters by.
     */
    public static String projectTypeFor(ServerType type) {
        return type == ServerType.PURPUR ? "plugin" : "mod";
    }
/** Human-facing Modrinth page for a project -- the "view online / full details" link target. */
    public static String projectPageUrl(String slug, String projectType) {
        String kind = "plugin".equals(projectType) ? "plugin" : "mod";
        return "https://modrinth.com/" + kind + "/" + slug;
    }

    /**
     * Downloads a project's icon image into iconDir as slug.png (cached), returning the local path
     * (or null if the project has no icon / the download fails). Used to show a thumbnail next to
     * search results and installed addons/mods, matching "icon + click through to Modrinth".
     *
     * Modrinth serves most icons as WebP. JavaFX's {@code javafx.scene.image.Image} cannot decode
     * WebP (it does not use java.awt ImageIO plugins), so we always decode whatever comes back with
     * ImageIO (the bundled TwelveMonkeys plugin handles WebP) and cache it as a normal PNG that
     * JavaFX can render natively. Icons are downscaled to 256px so the cache stays tiny.
     */
    public Path iconFor(String slug, String iconUrl, String projectType, Path iconDir) throws Exception {
        if (iconUrl == null || iconUrl.isBlank()) return null;
        Files.createDirectories(iconDir);
        String safe = slug != null ? slug.replaceAll("[^A-Za-z0-9._-]", "_") : "unknown";
        Path out = iconDir.resolve(safe + ".png");
        if (Files.exists(out) && Files.size(out) > 0) return out;

        try {
            byte[] bytes = downloadBytes(iconUrl);
            if (bytes == null || bytes.length == 0) return null;
            javax.imageio.stream.ImageInputStream iis =
                    javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes));
            var reader = javax.imageio.ImageIO.getImageReaders(iis);
            if (!reader.hasNext()) return null;
            var r = reader.next();
            r.setInput(iis);
            java.awt.image.BufferedImage src = r.read(0);
            r.dispose();
            if (src == null) return null;
            java.awt.image.BufferedImage norm = scaleTo(src, 256);
            javax.imageio.ImageIO.write(norm, "png", out.toFile());
            return out;
        } catch (Exception e) {
            Files.deleteIfExists(out);
            return null;
        }
    }

    /** Scales a BufferedImage down to {@code maxSide} (keeping aspect) when it's larger; returns it as-is otherwise. */
    private static java.awt.image.BufferedImage scaleTo(java.awt.image.BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSide && h <= maxSide) return src;
        double k = Math.min((double) maxSide / w, (double) maxSide / h);
        int nw = Math.max(1, (int) Math.round(w * k));
        int nh = Math.max(1, (int) Math.round(h * k));
        java.awt.image.BufferedImage scaled =
                new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return scaled;
    }

    /** GETs a small blob (an icon) and returns its bytes, or null on any error. */
    public byte[] downloadBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "DeyLauncher/0.8")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) return null;
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * First search hit for a name -- used to attach a Modrinth page/icon to an already-installed
     * jar we only know by its display name (client mods and installed server addons). Returns null
     * when there's nothing or the search fails.
     */
    public Hit firstHitByName(String name, String projectType) {
        try {
            var hits = search(name, projectType);
            for (var h : hits) {
                if (h.name().equalsIgnoreCase(name)) return h; // exact-ish match wins
            }
            return hits.isEmpty() ? null : hits.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves a project by its exact Modrinth slug (the id a mod declares in its own metadata, and
     * usually the same as the jar's {@code fabric.mod.json} {@code id}). This is the most reliable
     * way to match mods that a display-name search would miss, so the icon/click-through enrichment
     * covers the majority of Modrinth projects -- not just popular, well-named ones. Falls back to
     * {@link #firstHitByName} when the slug lookup fails or the slug points at the wrong kind.
     */
    public Hit firstHitBySlugOrName(String slug, String name, String projectType) {
        if (slug != null && !slug.isBlank()) {
            try {
                JsonObject o = getJson(API + "/project/" + enc(slug));
                String type = str(o, "project_type");
                if (type.isBlank() || type.equalsIgnoreCase(projectType)) {
                    return new Hit(str(o, "slug"),
                            str(o, "title").isBlank() ? str(o, "name") : str(o, "title"),
                            "", num(o, "downloads"), str(o, "description"), str(o, "icon_url"));
                }
            } catch (Exception ignored) {
                // fall through to a name search
            }
        }
        return firstHitByName(name, projectType);
    }

    public List<Hit> search(String query, String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"]]";
        String url = API + "/search?query=" + enc(query)
                + "&facets=" + enc(facets) + "&limit=20";
        JsonObject json = getJson(url);
        List<Hit> out = new ArrayList<>();
        if (json.has("hits")) {
            for (var e : json.getAsJsonArray("hits")) {
                var o = e.getAsJsonObject();
                out.add(new Hit(str(o, "slug"), str(o, "name"), str(o, "author"),
                        num(o, "downloads"), str(o, "description"), str(o, "icon_url")));
            }
        }
        return out;
    }

    /**
     * All published versions of a project, newest first (as Modrinth returns them).
     */
    public List<ProjectVersion> versions(String slug) throws Exception {
        // Modrinth renamed this route from `/project/{id}/versions` (plural) to
        // `/project/{id}/version` (singular); the old plural path now returns HTTP 404, which
        // broke every install. We call the current singular route, with a fallback to the old
        // plural path so a future rename (or an edge CDN that still serves it) keeps working.
        JsonArray arr = getJsonArrayWithFallback(
                API + "/project/" + enc(slug) + "/version",
                API + "/project/" + enc(slug) + "/versions");
        List<ProjectVersion> out = new ArrayList<>();
        for (var e : arr) {
            var o = e.getAsJsonObject();
            List<String> gv = new ArrayList<>();
            if (o.has("game_versions")) {
                for (var g : o.getAsJsonArray("game_versions")) gv.add(g.getAsString());
            }
            List<FileRef> files = new ArrayList<>();
            if (o.has("files")) {
                for (var f : o.getAsJsonArray("files")) {
                    var fo = f.getAsJsonObject();
                    files.add(new FileRef(str(fo, "url"), str(fo, "filename"),
                            fo.has("size") ? fo.get("size").getAsLong() : -1L));
                }
            }
            out.add(new ProjectVersion(str(o, "id"), str(o, "name"), str(o, "version_number"), gv, files));
        }
        return out;
    }

    /**
     * Only the project versions that explicitly support the given Minecraft version, newest first
     * (the order Modrinth itself returns them). Used by the version picker so a player sees exactly
     * the builds that will run on the Minecraft version currently selected in the launcher -- e.g.
     * for Minecraft 1.21.7 only Sodium builds tagged with 1.21.7 show up.
     */
    public List<ProjectVersion> compatibleVersions(String slug, String mcVersion) throws Exception {
        List<ProjectVersion> all = versions(slug);
        List<ProjectVersion> out = new ArrayList<>();
        for (var v : all) {
            if (v.gameVersions().contains(mcVersion)) out.add(v);
        }
        return out;
    }

    /**
     * True if a Modrinth build tagged {@code versionTag} runs on {@code mcVersion}: either an exact
     * match, or the tag is a release-family ancestor of {@code mcVersion} (e.g. a build tagged
     * "1.21" covers "1.21.1" and "1.21.4", but a build tagged "1.21.1" does NOT cover "1.21.2").
     * Most Fabric/Forge mods tag the whole release family or the patches they were built against, so
     * this exact-or-family rule is what lets the bundled mods install for a patch the project tags
     * only once per family, without ever "helpfully" picking a build for a different release.
     */
    public static boolean matchesMinecraftVersion(String versionTag, String mcVersion) {
        if (versionTag == null || mcVersion == null) return false;
        versionTag = versionTag.trim();
        mcVersion = mcVersion.trim();
        if (versionTag.equals(mcVersion)) return true;
        if (!versionTag.isBlank() && mcVersion.startsWith(versionTag + ".")) return true;
        return false;
    }

    /**
     * Same as {@link #compatibleVersions} but uses {@link #matchesMinecraftVersion} (exact or family)
     * instead of an exact-string match, so a project tagged only "1.21" still counts as compatible
     * with a launcher-selected "1.21.1".
     */
    public List<ProjectVersion> compatibleVersionsLenient(String slug, String mcVersion) throws Exception {
        List<ProjectVersion> out = new ArrayList<>();
        for (var v : versions(slug)) {
            if (v.gameVersions().stream().anyMatch(g -> matchesMinecraftVersion(g, mcVersion))) out.add(v);
        }
        return out;
    }

    /** True if {@code installedFileName} is one of the files shipped by {@code version}. */
    public static boolean versionFileMatches(ProjectVersion version, String installedFileName) {
        if (version == null || installedFileName == null) return false;
        for (var f : version.files()) {
            if (f.filename().equalsIgnoreCase(installedFileName)) return true;
        }
        return false;
    }

    /** Downloads whichever file of a version is meant to be run (first jar file) into targetDir. */
    public Path download(ProjectVersion version, Path targetDir) throws Exception {
        FileRef chosen = null;
        for (var f : version.files()) {
            if (f.filename().endsWith(".jar")) { chosen = f; break; }
        }
        if (chosen == null) throw new IOException("This version has no downloadable .jar file.");
        return download(chosen.url(), chosen.filename(), targetDir);
    }

    public Path download(String url, String filename, Path targetDir) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) throw new IOException("Download failed: HTTP " + resp.statusCode());
        Files.createDirectories(targetDir);
        String name = filename == null || filename.isBlank()
                ? url.substring(url.lastIndexOf('/') + 1) : filename;
        Path out = targetDir.resolve(name);
        Files.write(out, resp.body());
        return out;
    }

    private JsonObject getJson(String url) throws Exception {
        return get(url).getAsJsonObject();
    }

    private JsonArray getJsonArray(String url) throws Exception {
        return get(url).getAsJsonArray();
    }

    /**
     * Tries {@code primary} first; if Modrinth answers with HTTP 404 (a removed/renamed route),
     * retries {@code fallback}. Any other failure propagates. This keeps us resilient to Modrinth
     * renaming endpoints -- see {@link #versions(String)}.
     */
    private JsonArray getJsonArrayWithFallback(String primary, String fallback) throws Exception {
        try {
            return getJsonArray(primary);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return getJsonArray(fallback);
            }
            throw e;
        }
    }

    private com.google.gson.JsonElement get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "DeyLauncher/0.8")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("Modrinth API error: HTTP " + resp.statusCode());
        return com.google.gson.JsonParser.parseString(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsString() : ""; } catch (Exception e) { return ""; }
    }

    private static int num(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsInt() : 0; } catch (Exception e) { return 0; }
    }
}