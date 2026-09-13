package com.deylauncher.launch;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads everything a given version needs to run: the client jar, its
 * libraries (filtered by OS rules), native libraries (extracted to a
 * per-version natives folder), and the asset index + objects.
 *
 * Everything lives under ~/.deylauncher/ so multiple versions can coexist
 * (this is how "play any version, switch anytime" actually works -- each
 * version's files are cached once and reused).
 */
public class GameFiles {

    private final HttpClient http = HttpClient.newHttpClient();
    public final Path root;

    public GameFiles() {
        this.root = Path.of(System.getProperty("user.home"), ".deylauncher");
    }

    public record PreparedVersion(Path clientJar, List<Path> libraryJars, Path nativesDir,
                                   String mainClass, JsonObject versionJson) {}

    /** Downloads (or reuses cached) files for one version, returns everything needed to launch it. */
    public PreparedVersion prepare(JsonObject versionJson) throws Exception {
        return prepare(versionJson, null);
    }

    /** Downloads (or reuses cached) files for one version. When {@code progress} is non-null it is fed a
     *  0.0 -> 1.0 fraction of the download bytes written so far (client + libs + natives + assets), so the
     *  UI can show a real "how far through the launch are we" percentage. */
    public PreparedVersion prepare(JsonObject versionJson, DownloadProgress progress) throws Exception {
        String versionId = versionJson.get("id").getAsString();
        Path versionDir = root.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        JsonObject clientDl = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = versionDir.resolve(versionId + ".jar");

        double[] done = { 0.0 };
        double total = totalBytesNeeded(versionJson, clientJar);

        // --- client jar ---
        downloadTo(clientDl.get("url").getAsString(), clientJar, clientDl.get("size").getAsInt(),
                progress, done, total);

        // --- libraries + natives ---
        Path librariesDir = root.resolve("libraries");
        Path nativesDir = versionDir.resolve("natives");
        Files.createDirectories(nativesDir);
        List<Path> libraryJars = new ArrayList<>();

        for (var el : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!appliesToThisOs(lib)) continue;

            if (lib.has("downloads")) {
                // Modern vanilla-style entry: exact URL/path/size given directly.
                JsonObject downloads = lib.getAsJsonObject("downloads");

                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    Path dest = librariesDir.resolve(artifact.get("path").getAsString());
                    downloadTo(artifact.get("url").getAsString(), dest, artifact.get("size").getAsInt(),
                            progress, done, total);
                    libraryJars.add(dest);
                }

                // Natives (LWJGL etc.) come as classifier jars that need extracting, not classpath'ing.
                if (downloads.has("classifiers")) {
                    String classifierKey = nativesClassifierFor(lib);
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifierKey != null && classifiers.has(classifierKey)) {
                        JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                        Path nativeJar = librariesDir.resolve(nativeArtifact.get("path").getAsString());
                        downloadTo(nativeArtifact.get("url").getAsString(), nativeJar,
                                nativeArtifact.get("size").getAsInt(), progress, done, total);
                        extractNatives(nativeJar, nativesDir);
                    }
                }
            } else if (lib.has("name")) {
                // Older Maven-coordinate style entry -- this is what Fabric's (and some Forge)
                // profile JSONs actually use: {"name": "group:artifact:version", "url": "<repo base>"}
                // with no "downloads" block at all. No exact path/size is given, so we derive the
                // standard Maven layout path ourselves and build the URL from the declared repo
                // (falling back to Mojang's own library host if "url" is omitted, same as the
                // official launcher does for this format).
                String coordinate = lib.get("name").getAsString();
                String mavenPath = mavenCoordinateToPath(coordinate);
                String baseUrl = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                Path dest = librariesDir.resolve(mavenPath);
                downloadTo(baseUrl + mavenPath, dest, -1, progress, done, total);
                libraryJars.add(dest);
            }
        }

        // --- assets ---
        downloadAssets(versionJson, progress, done, total);
        if (progress != null) progress.onProgress(1.0); // whatever wasn't byte-counted, we're done here

        String mainClass = versionJson.get("mainClass").getAsString();
        return new PreparedVersion(clientJar, libraryJars, nativesDir, mainClass, versionJson);
    }

/** Best-effort sum (in bytes) of the files this prepare will actually download, so the UI can turn
     *  bytes-written into a percentage. Confirmed-cached files are skipped; Maven-coordinate libraries
     *  with no declared size get a small nominal figure so they still nudge the bar forward. */
    private double totalBytesNeeded(JsonObject versionJson, Path clientJar) {
        double total = 0.0;
        try {
            JsonObject clientDl = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
            if (!(Files.exists(clientJar) && Files.size(clientJar) == clientDl.get("size").getAsInt())) {
                total += clientDl.get("size").getAsDouble();
            }
            JsonObject assetIndexInfo = versionJson.getAsJsonObject("assetIndex");
            String indexId = assetIndexInfo.get("id").getAsString();
            Path indexFile = root.resolve("assets").resolve("indexes").resolve(indexId + ".json");
            if (!(Files.exists(indexFile) && Files.size(indexFile) == assetIndexInfo.get("size").getAsInt())) {
                total += assetIndexInfo.get("size").getAsDouble();
            }
            if (Files.exists(indexFile)) {
                JsonObject index = com.google.gson.JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
                Path objectsDir = root.resolve("assets").resolve("objects");
                for (String key : index.getAsJsonObject("objects").keySet()) {
                    JsonObject obj = index.getAsJsonObject("objects").getAsJsonObject(key);
                    String hash = obj.get("hash").getAsString();
                    Path dest = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                    if (!Files.exists(dest)) total += obj.get("size").getAsDouble();
                }
            }
        } catch (Exception ignored) {
            // Can't fully count (e.g. missing asset index) -- the download loop just clamps at 100%.
        }
        Path librariesDir = root.resolve("libraries");
        try {
            for (var el : versionJson.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                if (!appliesToThisOs(lib)) continue;
                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject a = downloads.getAsJsonObject("artifact");
                        Path dest = librariesDir.resolve(a.get("path").getAsString());
                        if (!(Files.exists(dest) && Files.size(dest) == a.get("size").getAsInt())) {
                            total += a.get("size").getAsDouble();
                        }
                    }
                    if (downloads.has("classifiers")) {
                        String classifierKey = nativesClassifierFor(lib);
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        if (classifierKey != null && classifiers.has(classifierKey)) {
                            JsonObject nat = classifiers.getAsJsonObject(classifierKey);
                            Path nj = librariesDir.resolve(nat.get("path").getAsString());
                            if (!(Files.exists(nj) && Files.size(nj) == nat.get("size").getAsInt())) {
                                total += nat.get("size").getAsDouble();
                            }
                        }
                    }
                } else if (lib.has("name")) {
                    String mavenPath = mavenCoordinateToPath(lib.get("name").getAsString());
                    if (!Files.exists(librariesDir.resolve(mavenPath))) total += 1_000_000; // nominal size
                }
            }
        } catch (Exception ignored) {
        }
        return total;
    }
    private void downloadAssets(JsonObject versionJson, DownloadProgress progress, double[] done, double total) throws Exception {
        JsonObject assetIndexInfo = versionJson.getAsJsonObject("assetIndex");
        String indexId = assetIndexInfo.get("id").getAsString();
        Path indexDir = root.resolve("assets").resolve("indexes");
        Files.createDirectories(indexDir);
        Path indexFile = indexDir.resolve(indexId + ".json");
        downloadTo(assetIndexInfo.get("url").getAsString(), indexFile, assetIndexInfo.get("size").getAsInt(),
                progress, done, total);

        JsonObject index = com.google.gson.JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        Path objectsDir = root.resolve("assets").resolve("objects");

        for (String key : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(key);
            String hash = obj.get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path dest = objectsDir.resolve(prefix).resolve(hash);
            if (Files.exists(dest)) continue; // most assets rarely change; skip re-checking size for speed
            String url = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
            downloadTo(url, dest, obj.get("size").getAsInt(), progress, done, total);
        }
    }

    // ---- OS rule evaluation (libraries can be Windows/Linux/macOS-only) ----
    private boolean appliesToThisOs(JsonObject lib) {
        if (!lib.has("rules")) return true;
        boolean allowed = false;
        for (var el : lib.getAsJsonArray("rules")) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                String osName = rule.getAsJsonObject("os").has("name")
                        ? rule.getAsJsonObject("os").get("name").getAsString() : null;
                matches = osName == null || osName.equals(currentOsName());
            }
            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    private String nativesClassifierFor(JsonObject lib) {
        // e.g. "natives-linux", "natives-windows" -- only present on older-style library entries;
        // versions using the newer format list natives as separate top-level libraries instead,
        // which appliesToThisOs() already filters correctly.
        String os = currentOsName();
        if (!lib.has("natives")) return null;
        JsonObject natives = lib.getAsJsonObject("natives");
        if (!natives.has(os)) return null;
        return natives.get(os).getAsString().replace("${arch}", System.getProperty("sun.arch.data.model", "64"));
    }

    private String currentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private void extractNatives(Path nativeJar, Path outDir) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(nativeJar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF")) continue;
                Path out = outDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(outDir)) continue; // zip-slip guard
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Converts a Maven coordinate ("group:artifact:version" or
     * "group:artifact:version:classifier") into the standard Maven repo
     * layout path, e.g. "net.fabricmc:fabric-loader:0.16.9" becomes
     * "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar".
     */
    private String mavenCoordinateToPath(String coordinate) {
        String[] parts = coordinate.split(":");
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;

        String groupPath = group.replace('.', '/');
        String fileName = artifact + "-" + version + (classifier != null ? "-" + classifier : "") + ".jar";
        return groupPath + "/" + artifact + "/" + version + "/" + fileName;
    }

    /**
     * Downloads a file to {@code dest} (skipping it when the cached file is already correct), streaming from
     * the HTTP response and counting bytes so the caller can turn bytes-downloaded into a 0..1 phase fraction.
     * {@code expectedSize <= 0} means "no size known". When {@code progress} is non-null and {@code total > 0}
     * the fraction {@code done/total} is reported after every chunk.
     */
    private void downloadTo(String url, Path dest, long expectedSize,
                            DownloadProgress progress, double[] done, double total) throws Exception {
        if (Files.exists(dest) && expectedSize > 0 && Files.size(dest) == expectedSize) return;
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to download " + url + " (" + resp.statusCode() + ")");
        }
        boolean streaming = progress != null && total > 0;
        try (var in = resp.body(); var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (streaming) {
                    done[0] += n;
                    progress.onProgress(Math.min(1.0, done[0] / total));
                }
            }
        }
    }
}
