package com.deylauncher.modpack;

import com.deylauncher.launch.DownloadProgress;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts a {@link ModpackInfo} onto disk: downloads what the pack declares (verifying the hashes the
 * pack shipped), unpacks what the pack carries, and never writes outside the folder it was given.
 *
 * Two entry points, one engine:
 * <ul>
 *   <li>{@link #installForClient} -- into {@code ~/.deylauncher/instances/<mc>-<loader>}, i.e. the
 *       same instance folder Play uses, so the pack is immediately launchable. Also writes the
 *       instance's {@code modpack.json} + {@code icon.png}.</li>
 *   <li>{@link #installForServer} -- into an owned server's folder, taking only the files a server
 *       can actually use (its {@code mods/} or {@code plugins/} folder, plus {@code config/}).</li>
 * </ul>
 *
 * Robustness notes worth knowing when reading this: every path that comes out of a pack goes through
 * {@link #resolve} (zip-slip guard), a pre-existing correct file is reused instead of re-downloaded
 * (so re-running an install is cheap), and a per-file failure is collected rather than aborting the
 * whole install -- one dead CDN link shouldn't throw away the other 200 mods.
 */
public class ModpackInstaller {

    private enum Side { CLIENT, SERVER }

    private final HttpClient http = HttpClient.newHttpClient();

    /** Outcome of one install: what actually happened, per file. */
    public record Result(int downloaded, int extracted, int reused, int failed, int skippedForThisSide,
                         List<String> errors) {

        public boolean ok() {
            return failed == 0;
        }

        /** One-line, human-readable summary shown in the installer window. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(downloaded).append(" downloaded, ").append(extracted).append(" unpacked");
            if (reused > 0) sb.append(", ").append(reused).append(" already in place");
            if (skippedForThisSide > 0) sb.append(", ").append(skippedForThisSide).append(" not needed here");
            if (failed > 0) sb.append(", ").append(failed).append(" FAILED");
            return sb.toString();
        }
    }

    /**
     * Installs the CLIENT half of a pack into an instance folder. Writes the per-instance modpack
     * record + icon afterwards, so the launcher can list the pack and reuse its exact loader version.
     */
    public Result installForClient(ModpackInfo info, Path instanceDir, DownloadProgress progress) throws IOException {
        Result result = install(info, instanceDir, Side.CLIENT, null, progress);
        PackIcons.copyIntoInstance(info.iconPath(), instanceDir);
        ModpackMeta.of(info).write(instanceDir);
        return result;
    }

    /**
     * Installs the SERVER half of a pack into an owned server's folder.
     *
     * @param addonFolder the folder that server type reads -- "mods" (Fabric/Forge) or "plugins"
     *                    (Purpur) -- so a client-only mod never lands in a server it can't run.
     */
    public Result installForServer(ModpackInfo info, Path serverDir, String addonFolder,
                                   DownloadProgress progress) throws IOException {
        return install(info, serverDir, Side.SERVER, addonFolder, progress);
    }

    // ---- the shared engine ----

    private Result install(ModpackInfo info, Path targetDir, Side side, String addonFolder,
                           DownloadProgress progress) throws IOException {
        Files.createDirectories(targetDir);

        List<ModpackFile> toFetch = new ArrayList<>();
        List<ModpackFile> toUnpack = new ArrayList<>();
        for (ModpackFile f : info.downloads()) if (wanted(f, side, addonFolder)) toFetch.add(f);
        for (ModpackFile f : info.bundled()) if (wanted(f, side, addonFolder)) toUnpack.add(f);

        int total = Math.max(1, toFetch.size() + toUnpack.size());
        int done = 0;
        int downloaded = 0, extracted = 0, reused = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        for (ModpackFile f : toFetch) {
            try {
                if (fetch(f, targetDir)) downloaded++;
                else reused++;
            } catch (Exception e) {
                failed++;
                errors.add(f.path() + " -- " + e.getMessage());
            }
            report(progress, ++done, total);
        }

        ZipFile zip = null;
        try {
            if (info.format() != null && info.format().archive() && !toUnpack.isEmpty()) {
                zip = new ZipFile(info.source().toFile());
            }
            for (ModpackFile f : toUnpack) {
                if (unpack(f, zip, targetDir)) {
                    extracted++;
                } else {
                    failed++;
                    errors.add(f.path() + " -- couldn't be read out of the pack");
                }
                report(progress, ++done, total);
            }
        } finally {
            if (zip != null) zip.close();
        }

        int considered = toFetch.size() + toUnpack.size();
        int skippedForThisSide = (info.downloads().size() + info.bundled().size()) - considered;
        return new Result(downloaded, extracted, reused, failed, Math.max(0, skippedForThisSide), errors);
    }

    /**
     * Which side of the install wants this file. The client takes everything the pack didn't mark
     * client-unsupported; a server takes only what its own addon folder (or config/) reads, and never
     * anything marked server-unsupported -- which is exactly how an .mrpack's env block is meant to
     * be honoured.
     */
    private boolean wanted(ModpackFile f, Side side, String addonFolder) {
        String path = f.path();
        if (path == null || ModpackReader.isPackMetadata(path)) return false;
        if (side == Side.CLIENT) return !f.clientUnsupported();
        if (f.serverUnsupported()) return false;
        String top = topSegment(path);
        if (addonFolder != null && top.equals(addonFolder)) return true;
        return top.equals("config") || top.equals("defaultconfigs");
    }

    private static String topSegment(String rel) {
        String p = rel.replace('\\', '/');
        int slash = p.indexOf('/');
        return (slash < 0 ? p : p.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    /**
     * Makes sure one declared file exists and matches the pack's own hash. Returns true when it was
     * actually downloaded, false when an already-correct copy was reused. Throws with a readable
     * reason when it can't be done (offline, link removed upstream, hash mismatch, unsafe path).
     */
    private boolean fetch(ModpackFile f, Path targetDir) throws IOException {
        Path dest = resolve(targetDir, f.path());
        if (dest == null) throw new IOException("the pack listed an unsafe path");
        if (Files.exists(dest)) {
            if (f.hasHash() && hashMatches(dest, f)) return false;
            if (!f.hasHash() && f.sizeBytes() > 0 && Files.size(dest) == f.sizeBytes()) return false;
        }
        if (!f.downloadable()) throw new IOException("the pack gives no download link for it");
        byte[] bytes = download(f.url());
        if (bytes == null) throw new IOException("the download failed (offline, or the file is gone upstream)");
        if (f.hasHash() && !hashMatches(bytes, f)) throw new IOException("the downloaded file didn't match the pack's hash");
        Files.createDirectories(dest.getParent());
        Files.write(dest, bytes);
        return true;
    }

    /**
     * Copies one bundled pack file into place: out of the archive (a zip entry), or off disk when the
     * pack was an already-extracted folder. Returns false when the source can't be read.
     */
    private boolean unpack(ModpackFile f, ZipFile zip, Path targetDir) throws IOException {
        Path dest = resolve(targetDir, f.path());
        if (dest == null) return false;
        Files.createDirectories(dest.getParent());
        if (zip != null) {
            ZipEntry entry = zip.getEntry(f.archiveEntry());
            if (entry == null || entry.isDirectory()) return false;
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        Path src = f.archiveEntry() == null ? null : Path.of(f.archiveEntry());
        if (src == null || !Files.isRegularFile(src)) return false;
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** Resolves a pack path inside {@code base}, or null when it would escape it (zip-slip guard). */
    private static Path resolve(Path base, String rel) {
        String safe = ModpackReader.safeRel(rel);
        if (safe == null) return null;
        Path root = base.toAbsolutePath().normalize();
        Path out = root.resolve(safe).normalize();
        return out.startsWith(root) ? out : null;
    }

    private byte[] download(String url) {
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

    private static boolean hashMatches(Path file, ModpackFile f) {
        try {
            return hashMatches(Files.readAllBytes(file), f);
        } catch (IOException e) {
            return false;
        }
    }

    /** Verifies against sha512 when the pack gave one (Modrinth packs usually give both), else sha1.
     *  A pack that gave no hash at all is accepted, since there is nothing to check it against. */
    private static boolean hashMatches(byte[] bytes, ModpackFile f) {
        try {
            if (f.sha512() != null && !f.sha512().isBlank()) {
                return hex(MessageDigest.getInstance("SHA-512").digest(bytes)).equalsIgnoreCase(f.sha512());
            }
            if (f.sha1() != null && !f.sha1().isBlank()) {
                return hex(MessageDigest.getInstance("SHA-1").digest(bytes)).equalsIgnoreCase(f.sha1());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void report(DownloadProgress progress, int done, int total) {
        if (progress != null) progress.onProgress(Math.min(1.0, (double) done / Math.max(1, total)));
    }
}