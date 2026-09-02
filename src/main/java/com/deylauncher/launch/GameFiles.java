package com.deylauncher.launch;

import com.google.gson.JsonObject;

import java.io.IOException;
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
        String versionId = versionJson.get("id").getAsString();
        Path versionDir = root.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        // --- client jar ---
        JsonObject clientDl = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = versionDir.resolve(versionId + ".jar");
        downloadIfMissing(clientDl.get("url").getAsString(), clientJar, clientDl.get("size").getAsInt());

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
                    downloadIfMissing(artifact.get("url").getAsString(), dest, artifact.get("size").getAsInt());
                    libraryJars.add(dest);
                }

                // Natives (LWJGL etc.) come as classifier jars that need extracting, not classpath'ing.
                if (downloads.has("classifiers")) {
                    String classifierKey = nativesClassifierFor(lib);
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifierKey != null && classifiers.has(classifierKey)) {
                        JsonObject nativeArtifact = classifiers.getAsJsonObject(classifierKey);
                        Path nativeJar = librariesDir.resolve(nativeArtifact.get("path").getAsString());
                        downloadIfMissing(nativeArtifact.get("url").getAsString(), nativeJar,
                                nativeArtifact.get("size").getAsInt());
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
                downloadIfMissing(baseUrl + mavenPath, dest);
                libraryJars.add(dest);
            }
        }

        // --- assets ---
        downloadAssets(versionJson);

        String mainClass = versionJson.get("mainClass").getAsString();
        return new PreparedVersion(clientJar, libraryJars, nativesDir, mainClass, versionJson);
    }

    private void downloadAssets(JsonObject versionJson) throws Exception {
        JsonObject assetIndexInfo = versionJson.getAsJsonObject("assetIndex");
        String indexId = assetIndexInfo.get("id").getAsString();
        Path indexDir = root.resolve("assets").resolve("indexes");
        Files.createDirectories(indexDir);
        Path indexFile = indexDir.resolve(indexId + ".json");
        downloadIfMissing(assetIndexInfo.get("url").getAsString(), indexFile, assetIndexInfo.get("size").getAsInt());

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
            downloadIfMissing(url, dest, obj.get("size").getAsInt());
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

    /** Overload for libraries with no known size (Maven-coordinate-style entries) -- existence check only. */
    private void downloadIfMissing(String url, Path dest) throws Exception {
        if (Files.exists(dest)) return;
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to download " + url + " (" + resp.statusCode() + ")");
        }
    }

    private void downloadIfMissing(String url, Path dest, long expectedSize) throws Exception {
        if (Files.exists(dest) && Files.size(dest) == expectedSize) return;
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to download " + url + " (" + resp.statusCode() + ")");
        }
    }
}
