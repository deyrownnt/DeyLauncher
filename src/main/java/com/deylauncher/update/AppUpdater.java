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
import java.util.Properties;
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

    /** Fallback version used when neither an embedded version resource nor a parseable jar
     *  filename is available (keep loosely in sync with build.gradle.kts). Kept low on purpose:
     *  if we can't determine the running version, we'd rather prompt for an update than hide it. */
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

    /**
     * The version currently running. Priority:
     *   1. the embedded {@code /deylauncher-version.properties} resource written by the build
     *      (project.version in build.gradle.kts) -- correct in the packaged app AND in-run;
     *   2. the shipped jar filename ({@code DeyLauncher-0.1.3.jar}) for older installs that
     *      predate the embedded resource;
     *   3. {@link #FALLBACK_VERSION}.
     */
    public static String currentVersion() {
        String embedded = readEmbeddedVersion();
        if (embedded != null) return embedded;
        Path jar = findCodeSourceJar();
        if (jar != null) {
            Matcher m = Pattern.compile("DeyLauncher-([0-9]+(?:\\.[0-9]+){1,3})\\.jar$")
                    .matcher(jar.getFileName().toString());
            if (m.find()) return m.group(1);
        }
        return FALLBACK_VERSION;
    }

    /** Reads the version baked in by the build ({@code deylauncher-version.properties}), or null. */
    private static String readEmbeddedVersion() {
        try (InputStream in = AppUpdater.class.getResourceAsStream("/deylauncher-version.properties")) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            if (v != null && v.matches("[0-9]+(\\.[0-9]+){1,3}")) return v.trim();
        } catch (Exception ignored) {
            // Missing/unparseable resource just means fall through to the jar-name / fallback logic.
        }
        return null;
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
     *  Gradle / class files) -- the caller then falls back to opening the release page.
     *
     *  jpackage app-image layouts put the launcher and the bundled runtime at DIFFERENT relative
     *  depths depending on OS (confirmed against JEP 392):
     *    Windows: <installRoot>/DeyLauncher.exe   + <installRoot>/runtime        + <installRoot>/app/*.jar
     *    Linux:   <installRoot>/bin/DeyLauncher   + <installRoot>/lib/runtime    + <installRoot>/lib/app/*.jar
     *  so each OS needs its own check per candidate root -- checking both at the SAME directory
     *  level (as this used to) never matches on Linux and always fell back to "running from source",
     *  even for a real installed app. */
    public static InstallLayout resolveInstallLayout() {
        Path jar = findCodeSourceJar();
        if (jar == null) return null;
        boolean win = isWindows();
        for (Path p = jar.getParent(); p != null; p = p.getParent()) {
            if (win) {
                Path launcher = p.resolve("DeyLauncher.exe");
                if (Files.exists(launcher) && Files.isDirectory(p.resolve("runtime"))) {
                    return new InstallLayout(p, launcher, true);
                }
            } else {
                Path launcher = p.resolve("bin").resolve("DeyLauncher");
                if (Files.exists(launcher) && Files.isDirectory(p.resolve("lib").resolve("runtime"))) {
                    return new InstallLayout(p, launcher, false);
                }
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
        // GitHub (and the objects.githubusercontent.com CDN it redirects release downloads to) can
        // reject or error out on requests with no User-Agent -- fetchReleases() already sends one for
        // the API call, this needs the same for the asset download itself.
        HttpRequest req = HttpRequest.newBuilder(URI.create(info.assetUrl()))
                .header("User-Agent", OWNER + "-DeyLauncher")
                .header("Accept", "application/octet-stream")
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            // Surface enough detail (asset, exact URL, response headers/body) to actually diagnose a
            // failure like this instead of just "HTTP 500" -- that alone isn't actionable.
            String body;
            try (InputStream in = resp.body()) {
                byte[] bytes = in.readNBytes(1024);
                body = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            }
            throw new IOException("Download failed with HTTP " + resp.statusCode()
                    + " for asset '" + info.assetName() + "' at " + info.assetUrl()
                    + (body.isEmpty() ? "" : " -- response: " + body));
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

    /** Writes a small restart helper that swaps the new app dir into place and relaunches the launcher.
     *  {@code pid} is the running app's process id, so the helper waits for this process to fully exit
     *  before swapping -- otherwise the swap can fail while the old JVM still holds the install dir. */
    public static Path writeRestartScript(InstallLayout layout, Path stagingApp, Path stagingRoot, long pid) throws IOException {
        Path upd = updatesDir();
        Files.createDirectories(upd);
        Path file = layout.windows() ? upd.resolve("dl-update.bat") : upd.resolve("dl-update.sh");
        Files.writeString(file, restartScript(layout, stagingApp, stagingRoot, pid),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (!layout.windows()) file.toFile().setExecutable(true, false);
        return file;
    }

    /** The restart-helper text for this install layout: CRLF-joined for the Windows .bat (cmd.exe is
     *  only fully reliable on CRLF, and this script jumps around with labels) and LF-joined for the
     *  POSIX .sh. Package-private so the script-shape invariants can be unit-tested without Windows. */
    static String restartScript(InstallLayout layout, Path stagingApp, Path stagingRoot, long pid) {
        return layout.windows()
                ? windowsScript(layout, stagingApp, stagingRoot, pid)
                : unixScript(layout, stagingApp, stagingRoot, pid);
    }

    private static String unixScript(InstallLayout layout, Path stagingApp, Path stagingRoot, long pid) {
        return "#!/bin/sh\n"
                // Take the install dir out of this shell's CWD first, so the directory move below
                // can't hit Linux's "device or resource busy" while a process is chdir'd inside it.
                + "cd /\n"
                + "APP_DIR=\"" + layout.installRoot() + "\"\n"
                + "STAGING=\"" + stagingApp + "\"\n"
                + "OLD_PID=" + pid + "\n"
                // Wait (up to 90s) for the running launcher to actually exit before touching its files.
                // doCleanExit() already kicked it off; without this wait the swap can race the shutdown.
                + "i=0\n"
                + "while kill -0 \"$OLD_PID\" 2>/dev/null && [ \"$i\" -lt 90 ]; do sleep 1; i=$((i+1)); done\n"
                // Swap the new app dir into place, retrying briefly in case the old dir is still busy,
                // and never letting one failed move abort the whole update (no `set -e`).
                + "mv -f \"$APP_DIR\" \"$APP_DIR.old\" 2>/dev/null || true\n"
                + "n=0\n"
                + "while [ ! -d \"$APP_DIR.old\" ] && [ \"$n\" -lt 10 ]; do sleep 1; mv -f \"$APP_DIR\" \"$APP_DIR.old\" 2>/dev/null || true; n=$((n+1)); done\n"
                + "mkdir -p \"$APP_DIR\"\n"
                + "cp -a \"$STAGING\"/. \"$APP_DIR\"/\n"
                + "chmod +x \"$APP_DIR/bin/DeyLauncher\" 2>/dev/null || true\n"
                + "rm -rf \"$APP_DIR.old\"\n"
                + "cd /tmp\n"
                // Relaunch the launcher only once the swap is done (restart at the END of the update).
                + "if [ -x \"$APP_DIR/bin/DeyLauncher\" ]; then\n"
                + "  nohup \"$APP_DIR/bin/DeyLauncher\" >/dev/null 2>&1 &\n"
                + "fi\n"
                + "rm -rf \"" + stagingRoot + "\"\n"
                + "exit 0\n";
    }

    /**
     * The Windows restart helper.
     *
     * Windows cannot replace (or even rename) files a running process still holds open: while the old
     * launcher is alive it keeps DeyLauncher.exe, app\DeyLauncher.cfg, app\*.jar and
     * runtime\bin\server\jvm.dll locked, so a copy attempted too early fails with "Access is denied" --
     * and since the old files were never replaced, the relaunched exe reports the SAME version, which
     * looks exactly like "the update did nothing". So this helper POLLS THE OLD PID (the Windows
     * counterpart of the .sh helper's {@code kill -0} loop) instead of sleeping a fixed 4 seconds:
     * doCleanExit() publishes OFFLINE presence over the network and can wait up to 35s PER running
     * server before it even reaches System.exit(0).
     *
     * It also refuses to leave a half-applied install behind: the old app dir is moved aside first, the
     * new one is robocopy'd into a fresh directory, the version-bearing jar is verified, and any failure
     * restores the previous install -- then whatever is at <installRoot>\DeyLauncher.exe is relaunched,
     * so the user is never left without a launcher. Every step goes to ~/.deylauncher/updates/update.log.
     */
    static String windowsScript(InstallLayout layout, Path stagingApp, Path stagingRoot, long pid) {
        // CRLF matters here: cmd.exe is only fully reliable on CRLF line endings and this script jumps
        // around with labels -- an LF-only .bat can make a goto miss its own target.
        List<String> s = new ArrayList<>();
        s.add("@echo off");
        s.add("setlocal");
        setVar(s, "APP_DIR", layout.installRoot().toString());
        setVar(s, "STAGING", stagingApp.toString());
        setVar(s, "STAGING_ROOT", stagingRoot.toString());
        setVar(s, "OLD", "%APP_DIR%.old");
        setVar(s, "LOG", updatesLogPath());
        setVar(s, "RESULT", updateResultPath());
        setVar(s, "OLD_PID", Long.toString(pid));
        setVar(s, "WAITED", "0");
        logLine(s, "DeyLauncher update: waiting for pid %OLD_PID% to exit");

        // Windows refuses to rename or delete a directory that is a live process's CURRENT directory,
        // and the launcher is started from its own folder -- leave it before touching anything.
        s.add("cd /d \"%TEMP%\"");

        // 1) Wait for the running launcher to really exit, polling the pid (up to ~2 minutes).
        //    `ping` is the 1-second sleep: `timeout` aborts immediately when it has no console
        //    (redirected stdin), which silently skipped even the delay it was there to provide.
        s.add("rem -- step 1: wait for the running launcher to exit --");
        s.add(":waitpid");
        s.add("tasklist /FI \"PID eq %OLD_PID%\" /NH 2>nul | find /I \"%OLD_PID%\" >nul");
        s.add("if errorlevel 1 goto exited");
        s.add("set /a WAITED+=1");
        s.add("if %WAITED% GEQ 120 goto stillalive");
        s.add("ping -n 2 127.0.0.1 >nul 2>&1");
        s.add("goto waitpid");
        s.add("");
        s.add(":stillalive");
        logLine(s, "WARNING: pid %OLD_PID% is still running -- trying anyway");
        s.add("");
        s.add(":exited");
        logLine(s, "pid %OLD_PID% exited after %WAITED%s");

        // 2) Sanity-check the extracted update: the version-bearing jar name is read out of the staging
        //    dir so that the same name can then be asserted in the install dir, after the copy.
        s.add("rem -- step 2: check the extracted update --");
        s.add("if not exist \"%STAGING%\\DeyLauncher.exe\" goto badstaging");
        s.add("if not exist \"%STAGING%\\app\" goto badstaging");
        setVar(s, "EXPECT_JAR", "");
        s.add("for %%J in (\"%STAGING%\\app\\DeyLauncher-*.jar\") do set \"EXPECT_JAR=%%~nxJ\"");
        s.add("if \"%EXPECT_JAR%\"==\"\" goto badstaging");
        logLine(s, "new build is %EXPECT_JAR%");

        // 3) Move the old install aside (retrying: antivirus/Explorer can hold the folder for a moment)
        //    and copy the new one into a clean directory. If the move never succeeds, mirror over the
        //    top instead -- /MIR also drops files the new version no longer ships, which the plain
        //    xcopy this replaced never did.
        s.add("rem -- step 3: swap in the new install --");
        s.add("if exist \"%OLD%\" rd /s /q \"%OLD%\" >nul 2>&1");
        s.add("set /a MOVED=0");
        s.add(":movetry");
        s.add("move \"%APP_DIR%\" \"%OLD%\" >nul 2>&1");
        s.add("if exist \"%OLD%\\DeyLauncher.exe\" goto moved");
        s.add("set /a MOVED+=1");
        s.add("if %MOVED% GEQ 30 goto inplace");
        s.add("ping -n 2 127.0.0.1 >nul 2>&1");
        s.add("goto movetry");
        s.add("");
        s.add(":moved");
        logLine(s, "old install moved aside after %MOVED%s");
        s.add("robocopy \"%STAGING%\" \"%APP_DIR%\" /E /R:3 /W:1 /NFL /NDL /NJH /NJS /NP >>\"%LOG%\" 2>&1");
        s.add("if errorlevel 8 goto rollback");
        s.add("goto verify");
        s.add("");
        s.add(":inplace");
        logLine(s, "could not move the install dir -- mirroring in place");
        s.add("robocopy \"%STAGING%\" \"%APP_DIR%\" /MIR /R:3 /W:1 /NFL /NDL /NJH /NJS /NP >>\"%LOG%\" 2>&1");
        s.add("if errorlevel 8 goto rollback");

        // 4) Verify that the files carrying the version actually landed, then clean up and relaunch.
        s.add("");
        s.add(":verify");
        s.add("if not exist \"%APP_DIR%\\DeyLauncher.exe\" goto rollback");
        s.add("if not exist \"%APP_DIR%\\app\\%EXPECT_JAR%\" goto rollback");
        s.add("if not exist \"%APP_DIR%\\runtime\" goto rollback");
        logLine(s, "update applied and verified -- %EXPECT_JAR%");
        s.add("rd /s /q \"%OLD%\" >nul 2>&1");
        s.add("rd /s /q \"%STAGING_ROOT%\" >nul 2>&1");
        s.add(">>\"%RESULT%\" echo OK %date% %time% -- installed %EXPECT_JAR%");
        s.add("goto relaunch");
        s.add("");
        s.add(":rollback");
        logLine(s, "ERROR: new build could not be applied -- restoring the old one");
        s.add("rd /s /q \"%APP_DIR%\" >nul 2>&1");
        s.add("if exist \"%OLD%\" move \"%OLD%\" \"%APP_DIR%\" >nul 2>&1");
        s.add(">>\"%RESULT%\" echo FAILED %date% %time% -- nothing was applied, see %LOG%");
        s.add("goto relaunchfail");
        s.add("");
        s.add(":badstaging");
        logLine(s, "ERROR: the extracted update is not a complete app-image -- nothing changed");
        s.add(">>\"%RESULT%\" echo FAILED %date% %time% -- the downloaded archive was incomplete");
        s.add("goto relaunchfail");
        s.add("");
        s.add(":relaunch");
        s.add("start \"\" /D \"%APP_DIR%\" \"%APP_DIR%\\DeyLauncher.exe\"");
        s.add("endlocal");
        s.add("exit /b 0");
        s.add("");
        s.add(":relaunchfail");
        s.add("start \"\" /D \"%APP_DIR%\" \"%APP_DIR%\\DeyLauncher.exe\"");
        s.add("endlocal");
        s.add("exit /b 1");
        return String.join("\r\n", s) + "\r\n";
    }

    /** Appends one timestamped line to the helper's log: {@code >>"%LOG%" echo [date time] text}. */
    private static void logLine(List<String> s, String text) {
        s.add(">>\"%LOG%\" echo [%date% %time%] " + text);
    }

    /** Appends {@code set "name=value"} -- cmd's quoted form, which tolerates spaces in the value. */
    private static void setVar(List<String> s, String name, String value) {
        s.add("set \"" + name + "=" + value + "\"");
    }

    private static Path updatesDir() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "updates");
    }

    private static String updatesLogPath() {
        return updatesDir().resolve("update.log").toString();
    }

    /** Where the helper records how the swap went, so an update that could not be applied stops being
     *  silent (the launcher can read this back on the next start). */
    private static String updateResultPath() {
        return updatesDir().resolve("update-result.txt").toString();
    }

    /** Launches the restart helper detached, so it survives this process exiting. */
    public static Process launchHelper(Path script, boolean windows) throws IOException {
        Path log = updatesDir().resolve("update.log");
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