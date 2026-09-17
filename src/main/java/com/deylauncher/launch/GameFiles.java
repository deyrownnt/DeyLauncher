package com.deylauncher.launch;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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

    /**
     * Everything needed to launch one version. {@code launcherRoot} is the {@code ~/.deylauncher}
     * folder itself: the game's own JVM args reference it (Forge's {@code ${library_directory}},
     * {@code ${classpath_separator}}), and those only resolve correctly against the same root
     * GameFiles downloaded everything into.
     */
    public record PreparedVersion(Path clientJar, List<Path> libraryJars, Path nativesDir,
                                   String mainClass, JsonObject versionJson, Path launcherRoot) {}

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

                    // Modern version JSONs (1.20.1 and later) ship each platform's native libraries as
                    // their own top-level entry ("org.lwjgl:lwjgl-glfw:3.3.1:natives-linux") instead of
                    // an old-style "downloads.classifiers" block. Those used to be left entirely to
                    // LWJGL, which extracts them lazily and only for the libraries the game actually
                    // loads -- so a file of the same name already sitting in the natives folder (say
                    // from an older LWJGL, or another architecture) was silently reused, mixing native
                    // versions inside one java.library.path. Extracting them ourselves and checking
                    // every file against the checksum LWJGL ships inside the jar makes that folder
                    // deterministic: whatever ran last, it holds the natives for THIS version.
                    if (isNativesEntry(lib)) {
                        extractNatives(dest, nativesDir);
                    }
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
        return new PreparedVersion(clientJar, libraryJars, nativesDir, mainClass, versionJson, root);
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

    // ---- OS rule evaluation (libraries can be Windows/Linux/macOS/arch-specific) ----
    private boolean appliesToThisOs(JsonObject lib) {
        return appliesToThisOs(lib, RuleEvaluator.Platform.current());
    }

    /**
     * Testable core: whether a library entry applies to a given platform. Delegates to
     * {@link RuleEvaluator}, which is what finally honours {@code os.arch} and {@code os.version} --
     * the old copy here only ever looked at the OS name.
     */
    static boolean appliesToThisOs(JsonObject lib, RuleEvaluator.Platform platform) {
        if (!lib.has("rules")) return true; // no rules == usable everywhere, as vanilla's plain jars are
        return RuleEvaluator.allows(lib.getAsJsonArray("rules"), platform, null);
    }

    /**
     * True when a modern library entry is one platform's pack of NATIVE libraries, e.g.
     * {@code org.lwjgl:lwjgl-glfw:3.3.1:natives-linux} or
     * {@code org.lwjgl:lwjgl:3.3.1:natives-windows-arm64} -- i.e. its Maven classifier says
     * {@code natives-*}. Those jars carry no classes; they exist to be unpacked into the natives
     * folder.
     */
    static boolean isNativesEntry(JsonObject lib) {
        if (lib == null || !lib.has("name")) return false;
        String[] parts = lib.get("name").getAsString().split(":");
        return parts.length > 3 && parts[3].toLowerCase(Locale.ROOT).startsWith("natives");
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

    /**
     * Unpacks a natives jar into {@code outDir}, then verifies every file it wrote against the
     * checksum LWJGL ships NEXT TO the file inside that jar ({@code <lib>.sha1}).
     *
     * <p>Two things this buys over trusting whatever is already in the folder:
     * <ul>
     *   <li>a stale copy of {@code libglfw.so}/{@code openal.dll} from another LWJGL version or
     *       another CPU architecture is OVERWRITTEN, so {@code java.library.path} can never hand the
     *       game a native library that doesn't match the Java bindings on the classpath (mixing those
     *       is a classic silent {@code SIGSEGV});</li>
     *   <li>a jar that was truncated by a bad download fails HERE, with a message that says what to
     *       delete, instead of crashing the game later for no visible reason.</li>
     * </ul>
     * Extraction keeps only the file NAME (LWJGL's jars nest their libs under
     * {@code linux/x64/org/lwjgl/glfw/}), which is where the JVM finds them -- and the zip-slip guard
     * stays, because the entry paths come from a downloaded file.
     */
    private void extractNatives(Path nativeJar, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        try (var zip = new ZipFile(nativeJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("META-INF") || isChecksumSidecar(name)) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.isBlank()) continue;
                Path out = outDir.resolve(base).normalize();
                if (!out.startsWith(outDir)) continue; // zip-slip guard
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                String expected = sha1Sidecar(zip, name);
                if (expected != null && !expected.equalsIgnoreCase(sha1Of(out))) {
                    throw new IOException("the native library " + base + " extracted from "
                            + nativeJar.getFileName() + " does not match the checksum stored inside that "
                            + "jar, so it is corrupt. Delete " + outDir + " and launch again to re-download it.");
                }
            }
        }
    }

    /** {@code libglfw.so.sha1} / {@code glfw.dll.git} / stray digests: verification metadata, not a library. */
    private static boolean isChecksumSidecar(String entryName) {
        String n = entryName.toLowerCase(Locale.ROOT);
        return n.endsWith(".sha1") || n.endsWith(".sha256") || n.endsWith(".sha512")
                || n.endsWith(".md5") || n.endsWith(".asc") || n.endsWith(".git");
    }

    /** The sha1 a natives jar records for one of its files, or null when it records none. */
    private static String sha1Sidecar(ZipFile zip, String entryName) {
        ZipEntry sidecar = zip.getEntry(entryName + ".sha1");
        if (sidecar == null || sidecar.isDirectory()) return null;
        try (var in = zip.getInputStream(sidecar)) {
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            var m = java.util.regex.Pattern.compile("[0-9a-fA-F]{40}").matcher(text);
            return m.find() ? m.group() : null; // the file is "<hex>" or "<hex> <filename>"
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1Of(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable in this JVM", e);
        }
    }

    /**
     * Converts a Maven coordinate ("group:artifact:version" or
     * "group:artifact:version:classifier") into the standard Maven repo
     * layout path, e.g. "net.fabricmc:fabric-loader:0.16.9" becomes
     * "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar".
     */
    
    /**
     * Patches the native libglfw.so in the LWJGL GLFW native JAR (in the libraries directory)
     * to use the fixed version from LWJGL 3.3.2 (GLFW 3.4.1) instead of the buggy
     * version from LWJGL 3.3.1 (GLFW 3.4.0).
     *
     * This is necessary because LWJGL 3.3.1 bundles GLFW 3.4.0 which has a bug
     * causing SIGSEGV in glfwCreateWindow on both XWayland and native Wayland.
     * The fix is to replace the libglfw.so inside the native JAR before extraction.
     *
     * @param librariesDir the libraries directory where the native JAR is stored
     * @param log optional logger for progress messages
     * @throws IOException if the patch fails
     */
    public static void patchNativeGlfwInJar(Path librariesDir, java.util.function.Consumer<String> log) throws IOException {
        // Find the buggy native JAR: lwjgl-glfw-3.3.1-natives-linux.jar
        Path nativeJarPath = librariesDir.resolve("org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-natives-linux.jar");
        if (!Files.exists(nativeJarPath)) {
            if (log != null) log.accept("Native JAR not found at " + nativeJarPath + ", skipping patch");
            return;
        }

        if (log != null) log.accept("Patching libglfw.so in " + nativeJarPath);

        // Download the fixed native JAR from Mojang's library mirror
        // Using LWJGL 3.3.2 which has GLFW 3.4.1 (the fix)
        String fixedVersion = "3.3.2";
        String nativeJarUrl = "https://libraries.minecraft.net/org/lwjgl/lwjgl-glfw/"
                + fixedVersion + "/lwjgl-glfw-" + fixedVersion + "-natives-linux.jar";

        Path tempJar = Files.createTempFile("lwjgl-glfw-", "-natives-linux.jar");
        tempJar.toFile().deleteOnExit();

        try {
            if (log != null) log.accept("Downloading fixed libglfw.so from " + nativeJarUrl);
            var http = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(nativeJarUrl)).GET().build();
            java.net.http.HttpResponse<Path> response;
            try {
                response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(tempJar));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
            if (response.statusCode() >= 400) {
                throw new IOException("Failed to download fixed libglfw.so: HTTP " + response.statusCode());
            }

            // Extract libglfw.so from the fixed native JAR
            Path targetLibglfw = null;
            try (var zip = new java.util.zip.ZipFile(tempJar.toFile())) {
                var entry = zip.getEntry("linux/x64/org/lwjgl/glfw/libglfw.so");
                if (entry == null) {
                    throw new IOException("libglfw.so not found in fixed native jar");
                }
                targetLibglfw = Files.createTempFile("libglfw", ".so");
                targetLibglfw.toFile().deleteOnExit();
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, targetLibglfw, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (targetLibglfw == null) {
                throw new IOException("Failed to extract libglfw.so from fixed native jar");
            }

            // Replace libglfw.so in the original native JAR
            Path tempOutputJar = Files.createTempFile("lwjgl-glfw-patched-", "-natives-linux.jar");
            tempOutputJar.toFile().deleteOnExit();

            try (var inputZip = new java.util.zip.ZipFile(nativeJarPath.toFile());
                 var outputZip = new ZipOutputStream(Files.newOutputStream(tempOutputJar))) {
                for (var entry : java.util.Collections.list(inputZip.entries())) {
                    if (entry.getName().equals("linux/x64/org/lwjgl/glfw/libglfw.so")) {
                        // Skip the buggy libglfw.so, we'll add the fixed one
                        continue;
                    }
                    outputZip.putNextEntry(new ZipEntry(entry.getName()));
                    try (var in = inputZip.getInputStream(entry)) {
                        in.transferTo(outputZip);
                    }
                    outputZip.closeEntry();
                }
                // Add the fixed libglfw.so
                outputZip.putNextEntry(new ZipEntry("linux/x64/org/lwjgl/glfw/libglfw.so"));
                Files.copy(targetLibglfw, outputZip);
                outputZip.closeEntry();
            }

            // Replace the original JAR with the patched one
            Files.move(tempOutputJar, nativeJarPath, StandardCopyOption.REPLACE_EXISTING);

            if (log != null) log.accept("Patched native JAR: " + nativeJarPath);

        } catch (IOException e) {
            if (log != null) log.accept("Failed to patch native JAR: " + e.getMessage());
            throw e;
        }
    }

    // Overload without logger
    public static void patchNativeGlfwInJar(Path librariesDir) throws IOException {
        patchNativeGlfwInJar(librariesDir, null);
    }

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
