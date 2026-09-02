package com.deylauncher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Mojang bundles its own JRE builds specifically so launchers don't have to
 * rely on whatever Java happens to be on the user's PATH -- this is exactly
 * what the official launcher uses. We fetch the same manifest and reuse it.
 *
 * Each Minecraft version JSON names which runtime "component" it needs
 * (e.g. "java-runtime-delta" for very new versions needing Java 25+, or
 * "jre-legacy" for old versions). We resolve that against Mojang's
 * platform-keyed manifest, download it once per component+OS (cached under
 * ~/.deylauncher/runtimes/), and hand back the path to its `java` binary.
 */
public class JavaRuntimeManager {

    private static final String RUNTIME_INDEX_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final Path runtimesRoot;

    public JavaRuntimeManager(Path launcherRoot) {
        this.runtimesRoot = launcherRoot.resolve("runtimes");
    }

    /**
     * Ensures the right JRE for this version is downloaded, returns the path
     * to its java executable ready to hand to ProcessBuilder.
     */
    public Path ensureRuntimeFor(JsonObject versionJson) throws Exception {
        String component = versionJson.has("javaVersion")
                ? versionJson.getAsJsonObject("javaVersion").get("component").getAsString()
                : "jre-legacy"; // versions old enough to omit this field only ever needed the legacy runtime

        String platformKey = currentPlatformKey();
        Path componentDir = runtimesRoot.resolve(platformKey).resolve(component);
        Path javaBin = componentDir.resolve(javaBinaryRelativePath());

        if (Files.exists(javaBin)) {
            return javaBin; // already downloaded from a previous run
        }

        JsonObject index = fetchJson(RUNTIME_INDEX_URL);
        if (!index.has(platformKey)) {
            throw new IllegalStateException("Mojang has no bundled Java runtime for platform \""
                    + platformKey + "\" -- please install a matching JDK yourself and let me know "
                    + "so I can add a fallback path for this platform.");
        }
        var platformEntry = index.getAsJsonObject(platformKey);
        if (!platformEntry.has(component) || platformEntry.getAsJsonArray(component).isEmpty()) {
            throw new IllegalStateException("No \"" + component + "\" runtime available for "
                    + platformKey + " -- this version may need a newer Mojang runtime manifest.");
        }
        JsonObject entry = platformEntry.getAsJsonArray(component).get(0).getAsJsonObject();
        String manifestUrl = entry.getAsJsonObject("manifest").get("url").getAsString();

        JsonObject manifest = fetchJson(manifestUrl);
        downloadAllFiles(manifest.getAsJsonObject("files"), componentDir);

        if (!Files.exists(javaBin)) {
            throw new IllegalStateException("Runtime downloaded but java binary not found at " + javaBin
                    + " -- Mojang's manifest layout may have changed.");
        }
        return javaBin;
    }

    private void downloadAllFiles(JsonObject files, Path componentDir) throws Exception {
        for (String relativePath : files.keySet()) {
            JsonObject fileEntry = files.getAsJsonObject(relativePath);
            String type = fileEntry.get("type").getAsString();
            Path target = componentDir.resolve(relativePath);

            switch (type) {
                case "directory" -> Files.createDirectories(target);
                case "file" -> {
                    JsonObject raw = fileEntry.getAsJsonObject("downloads").getAsJsonObject("raw");
                    long size = raw.get("size").getAsLong();
                    if (!Files.exists(target) || Files.size(target) != size) {
                        Files.createDirectories(target.getParent());
                        HttpRequest req = HttpRequest.newBuilder(URI.create(raw.get("url").getAsString())).GET().build();
                        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(target));
                        if (resp.statusCode() >= 400) {
                            throw new IOException("Failed downloading runtime file " + relativePath);
                        }
                    }
                    boolean executable = fileEntry.has("executable") && fileEntry.get("executable").getAsBoolean();
                    if (executable) markExecutable(target);
                }
                case "link" -> {
                    // Symlinks inside the JRE (mostly macOS/Linux bundles). Best-effort: if creating
                    // the symlink fails (e.g. Windows FS without privilege), skip it -- these are
                    // almost always convenience links, not files the JVM strictly needs at the exact
                    // path, so a failure here shouldn't abort the whole runtime download.
                    try {
                        Path linkTarget = Path.of(fileEntry.get("target").getAsString());
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            Files.createSymbolicLink(target, linkTarget);
                        }
                    } catch (Exception ignored) {
                    }
                }
                default -> { /* unknown entry type -- skip rather than fail the whole download */ }
            }
        }
    }

    private void markExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows has no POSIX permission bits -- .exe files are executable by extension already.
        }
    }

    private String javaBinaryRelativePath() {
        return currentPlatformKey().startsWith("windows") ? "bin/java.exe" : "bin/java";
    }

    /** Maps our OS/arch to the exact platform keys Mojang's manifest uses. */
    private String currentPlatformKey() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        if (os.contains("win")) {
            if (arch.contains("aarch64") || arch.contains("arm")) return "windows-arm64";
            return arch.contains("86") && !arch.contains("64") ? "windows-x86" : "windows-x64";
        }
        if (os.contains("mac")) {
            return (arch.contains("aarch64") || arch.contains("arm")) ? "mac-os-arm64" : "mac-os";
        }
        // Linux: Mojang only publishes x86/x86_64 builds -- ARM Linux (e.g. Raspberry Pi) has no
        // official bundle, so this will fall through to the "no runtime available" error above,
        // where the message tells the user to install a matching JDK themselves.
        return arch.contains("86") && !arch.contains("64") ? "linux-i386" : "linux";
    }

    private JsonObject fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
