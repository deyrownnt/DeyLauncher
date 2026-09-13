package com.deylauncher.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.ObjLongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-updater for DeyLauncher.
 *
 * The app is shipped as a jpackage app-image, bundled per OS into release assets like
 * DeyLauncher-Linux.tar.gz (Linux) and DeyLauncher-Windows.zip (Windows). This class:
 *
 *  1. QUERY -- reads the GitHub releases list for the DeyLauncher repo, picks the newest
 *     NON-prerelease release, and if its version is newer than the running one, selects
 *     the asset matching the current OS.
 *  2. DOWNLOAD -- streams the chosen asset to a temp file, reporting progress.
 *  3. INSTALL + RESTART -- resolves the running install layout (launcher binary + app dir),
 *     extracts the new archive over it and writes a tiny restart helper that waits for this
 *     process to exit, swaps the files, relaunches the launcher, then disappears.
 *
 * User data lives under ~/.deylauncher -- OUTSIDE the install directory -- so replacing the
 * install directory during an update never touches settings, accounts, friends, notes,
 * servers or the game cache.
 */
public final class AppUpdater {

    /** GitHub repo the launcher is published to. */
    public static final String OWNER = "deyrownnt";
    public static final String REPO = "DeyLauncher";
    public static final String RELEASE_PAGE = "https://github.com/" + OWNER + "/" + REPO + "/releases";

    /** Fallback version used when the running jar filename can't be read (keep in sync with build.gradle.kts). */
    private static final String FALLBACK_VERSION = "0.1.0";

    private AppUpdater() {}

    /** An available update plus the OS-specific asset to download. */
    public record UpdateInfo(String latestVersion, String tag, String assetName,
                             String assetUrl, long sizeBytes) {}

    /** The on-disk layout of the running jpackage app (install root + launcher binary). */
    public record InstallLayout(Path installRoot, Path launcher, boolean windows) {}

    /** A release (version, prerelease flag, asset list) parsed from the GitHub JSON. */
    private record Release(boolean prerelease, String tag, String version, JsonArray assets) {}

    /** A downloadable release asset. */
    private record Asset(String name, String url, long size) {}

    // ------------------------------------------------------------------ QUERY

    /** Returns an update to apply: newest non-prerelease release, strictly newer than running, with a matching OS asset. */
    public static Optional<UpdateInfo> findUpdate() throws Exception {
        List<Release> releases = fetchReleases();
        for (Release r : releases) {
            if (r.prerelease()) continue;                            // latest release, never a pre-release
            if (!isNewer(r.version(), currentVersion())) continue;   // only prompt when actually newer
            Asset asset = pickAsset(r.assets());
            if (asset == null) continue;
            return Optional.of(new UpdateInfo(r.version(), r.tag(), asset.name(), asset.url(), asset.size()));
        }
        return Optional.empty();
    }

    private static List<Release> fetchReleases() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", OWNER + "-DeyLauncher")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GitHub releases request failed with HTTP " + resp.statusCode());
        }
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        ArrayList<Release> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            boolean pre = o.has("prerelease") && o.get("prerelease").getAsBoolean();
            String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : "";
            JsonArray assets = o.has("assets") && o.get("assets").isJsonArray()
                    ? o.getAsJsonArray("assets") : new JsonArray();
            out.add(new Release(pre, tag, versionFromTag(tag), assets));
        }
        return out;
    }

    /** Picks the asset matching the running OS, preferring filenames that carry the OS keyword. */
    private static Asset pickAsset(JsonArray assets) {
        boolean win = isWindows();
        List<Asset> matches = new ArrayList<>();
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            String name = a.has("name") ? a.get("name").getAsString() : "";
            matches.add(new Asset(name,
                    a.has("browser_download_url") ? a.get("browser_download_url").getAsString() : "",
                    a.has("size") ? a.get("size").getAsLong() : 0));
        }
        List<Asset> fits = new ArrayList<>();
        for (Asset m : matches) if (fitsOs(win, m.name())) fits.add(m);
        if (fits.isEmpty()) return null;

        String kw = win ? "win" : "linux";
        for (Asset m : fits) if (m.name().toLowerCase().contains(kw)) return m; // -Linux.tar.gz / -Windows.zip
        return fits.get(0);
    }

    private static boolean fitsOs(boolean win, String name) {
        String n = name.toLowerCase();
        if (win) return n.endsWith(".zip") || n.endsWith(".exe") || n.endsWith(".msi");
        return n.endsWith(".tar.gz") || n.endsWith(".tar.zst") || n.endsWith(".tgz")
                || n.endsWith(".deb") || n.endsWith(".rpm") || n.endsWith(".appimage")
                || n.endsWith(".jar");
    }

    // ----------------------------------------------------------------- VERSION

    /** The version currently running, read from the shipped jar filename (DeyLauncher-0.1.0.jar). */
    public static String currentVersion() {
        Path jar = findCodeSourceJar();
        if (jar != null) {
            Matcher m = Pattern.compile("DeyLauncher-([0-9]+(?:\\.[0-9]+){1,3})\\.jar$")
                    .matcher(jar.getFileName().toString());
            if (m.find()) return m.group(1);
        }
        return FALLBACK_VERSION;
    }

    private static String versionFromTag(String tag) {
        if (tag == null) return "";
        String v = tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int cut = v.length();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!Character.isDigit(c) && c != '.') { cut = i; break; }
        }
        return v.substring(0, cut);
    }

    /** Numeric comparison of dotted versions; "0.2.0" < "0.10.0". */
    public static boolean isNewer(String newer, String older) {
        int[] a = parseVersion(newer);
        int[] b = parseVersion(older);
        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) return true;
            if (a[i] < b[i]) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        if (v == null) return out;
        String[] parts = v.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ----------------------------------------------------------------- LAYOUT

    /** Resolves where this running app is installed and its launcher binary, so an update can be
     *  applied in place. Returns null when NOT running from a packaged app (e.g. launched via
     *  Gradle / class files) -- the caller then falls back to opening the release page. */
    public static InstallLayout resolveInstallLayout() {
        Path jar = findCodeSourceJar();
        if (jar == null) return null;
        Path dir = jar.getParent();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path launcherWin = p.resolve("DeyLauncher.exe");
            Path launcherLin = p.resolve("bin").resolve("DeyLauncher");
            boolean hasRuntime = Files.isDirectory(p.resolve("runtime"));
            if (hasRuntime && (Files.exists(launcherWin) || Files.exists(launcherLin))) {
                return new InstallLayout(p, Files.exists(launcherWin) ? launcherWin : launcherLin,
                        Files.exists(launcherWin));
            }
        }
        return null;
    }

    private static Path findCodeSourceJar() {
        try {
            URI loc = AppUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(loc);
            if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
        } catch (Exception ignored) {
            // Running from a classes dir (Gradle) or a path that can't be resolved.
        }
        return null;
    }

    // ----------------------------------------------------------------- DOWNLOAD

    /** Downloads the update asset to a temp file, reporting bytesRead/total to {@code progress}. */
    public static Path downloadRelease(UpdateInfo info, ObjLongConsumer<Long> progress) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.assetUrl())).GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            try (InputStream in = resp.body()) { in.readAllBytes(); }
            throw new IOException("Download failed with HTTP " + resp.statusCode());
        }
        long total = info.sizeBytes() > 0 ? info.sizeBytes()
                : resp.headers().firstValueAsLong("Content-Length").orElse(0);

        String ext = extOf(info.assetName());
        Path tmp = Files.createTempFile("DeyLauncher-update", ext.isEmpty() ? ".dl" : ext);
        long done = 0;
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null && total > 0) progress.accept(done, total);
            }
        }
        return tmp;
    }

    private static String extOf(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i);
    }

    // ----------------------------------------------------------------- INSTALL + RESTART

    /** Extracts a downloaded archive under {@code staging} and returns the extracted app folder. */
    public static Path extractTo(Path archive, Path staging, boolean windows) throws Exception {
        Files.createDirectories(staging);
        if (windows) {
            unzip(archive, staging);
        } else {
            // Linux hosts always have GNU tar -- the simplest reliable .tar.gz extractor here.
            Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", staging.toString())
                    .redirectErrorStream(true).start();
            String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) throw new IOException("tar extraction failed (" + code + "): " + err);
        }
        Path app = staging.resolve("DeyLauncher");
        return Files.isDirectory(app) ? app : staging;
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) throw new IOException("Unsafe zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(e);
                         OutputStream out = Files.newOutputStream(target,
                                 StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
                        in.transferTo(out);
                    }
                }
            }
        }
    }

    /** Writes a small restart helper that swaps the new app dir into place and relaunces the launcher. */
    public static Path writeRestartScript(InstallLayout layout, Path stagingApp, Path stagingRoot) throws IOException {
        Path upd = Path.of(System.getProperty("user.home"), ".deylauncher", "updates");
        Files.createDirectories(upd);
        Path file;
        String script;
        if (layout.windows()) {
            file = upd.resolve("dl-update.bat");
            script = windowsScript(layout, stagingApp, stagingRoot);
        } else {
            file = upd.resolve("dl-update.sh");
            script = unixScript(layout, stagingApp, stagingRoot);
        }
        Files.writeString(file, script, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (!layout.windows()) file.toFile().setExecutable(true, false);
        return file;
    }

    private static String unixScript(InstallLayout layout, Path stagingApp, Path stagingRoot) {
        return "#!/bin/sh\n"
                + "set -e\n"
                + "APP_DIR=\"" + layout.installRoot() + "\"\n"
                + "STAGING=\"" + stagingApp + "\"\n"
                + "sleep 2\n"
                + "if [ -d \"$APP_DIR\" ]; then\n"
                + "  mv -f \"$APP_DIR\" \"$APP_DIR.old\"\n"
                + "fi\n"
                + "mkdir -p \"$APP_DIR\"\n"
                + "cp -a \"$STAGING\"/. \"$APP_DIR\"/\n"
                + "chmod +x \"$APP_DIR/bin/DeyLauncher\" 2>/dev/null || true\n"
                + "rm -rf \"$APP_DIR.old\"\n"
                + "cd /tmp\n"
                + "nohup \"$APP_DIR/bin/DeyLauncher\" >/dev/null 2>&1 &\n"
                + "rm -rf \"" + stagingRoot + "\"\n"
                + "exit 0\n";
    }

    private static String windowsScript(InstallLayout layout, Path stagingApp, Path stagingRoot) {
        return "@echo off\n"
                + "setlocal\n"
                + "set \"APP_DIR=" + layout.installRoot() + "\"\n"
                + "set \"STAGING=" + stagingApp + "\"\n"
                + "set OLD=%APP_DIR%.old\n"
                + "set LOG=" + updatesLogPath() + "\n"
                + "timeout /t 4 /nobreak >nul\n"
                + "if exist \"%STAGING%\" ( xcopy \"%STAGING%\"\\* \"%APP_DIR%\"\\ /E /I /Y /Q >>\"%LOG%\" 2>&1 )\n"
                + "if exist \"%OLD%\" rd /s /q \"%OLD%\" >nul 2>&1\n"
                + "cd /d \"%TEMP%\"\n"
                + "start \"\" \"%APP_DIR%\\DeyLauncher.exe\"\n"
                + "rmdir /s /q \"" + stagingRoot + "\" >nul 2>&1\n"
                + "exit /b 0\n";
    }

    private static String updatesLogPath() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "updates", "update.log").toString();
    }

    /** Launches the restart helper detached, so it survives this process exiting. */
    public static Process launchHelper(Path script, boolean windows) throws IOException {
        Path log = Path.of(System.getProperty("user.home"), ".deylauncher", "updates", "update.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder pb;
        if (windows) {
            pb = new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.toString());
        } else {
            pb = new ProcessBuilder("sh", script.toString());
        }
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        return pb.start();
    }

    /** Opens the GitHub releases page in the default browser (used when running from source). */
    public static void openReleasePage() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(RELEASE_PAGE));
        } catch (Exception ex) {
            System.out.println("Update available -- open " + RELEASE_PAGE);
        }
    }

    /** True on any Windows flavour (used to pick the release asset). */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}