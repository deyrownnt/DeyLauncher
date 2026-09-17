package com.deylauncher.modpack;

import com.deylauncher.launch.DownloadProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * The "and put it back" half of a modpack: what runs before the game starts, so a pack whose mods have
 * gone missing heals itself instead of launching broken.
 *
 * <p>A modpack's files can disappear for entirely ordinary reasons -- a half-finished antivirus scan, a
 * user clearing out an instance, a failed download the first time round, a mod deleted from the Mods
 * window, a fresh launcher install pointing at an old instance. The pack's own record
 * ({@code modpack.json}, written by {@link ModpackInstaller}) carries every file it owns along with the
 * URL/size/hash needed to fetch each one, so this class can compare that manifest against the disk and
 * re-download whatever no longer matches -- straight into the right folder of the right version's
 * instance, with no manual downloads and no "which jar goes where" guesswork.
 *
 * <p>Three things are deliberately NOT touched:
 * <ul>
 *   <li>a mod the user switched OFF in the Mods window (it lives in {@code mods-disabled/}) -- a repair
 *       must never undo that choice;</li>
 *   <li>a mod the user added by hand -- only the pack's own manifest is checked;</li>
 *   <li>a file whose pack archive has since been deleted, when it was bundled rather than downloadable
 *       ({@code overrides/} files) -- that is reported, not guessed at.</li>
 * </ul>
 */
public class ModpackVerifier {

    /** What one verification pass found and fixed. */
    public record Report(int checked, int missing, int restored, int failed, int skipped,
                         List<String> errors) {

        public boolean ok() {
            return failed == 0;
        }

        public boolean clean() {
            return missing == 0;
        }

        /** One line for the launcher log / a dialog. */
        public String summary() {
            if (clean()) return checked + " file(s) checked -- the pack is complete";
            StringBuilder sb = new StringBuilder();
            sb.append(missing).append(" missing file(s) found in ").append(checked).append(" checked");
            if (restored > 0) sb.append(", ").append(restored).append(" downloaded back into place");
            if (failed > 0) sb.append(", ").append(failed).append(" couldn't be fetched");
            if (skipped > 0) sb.append(", ").append(skipped).append(" skipped");
            return sb.toString();
        }
    }

    private final PackFileOps.Downloader downloader;

    public ModpackVerifier() {
        this(PackFileOps.HTTP);
    }

    /** Test seam: a downloader that never touches the network. */
    ModpackVerifier(PackFileOps.Downloader downloader) {
        this.downloader = downloader;
    }

    /**
     * Checks an installed CLIENT pack and restores whatever is missing, relative to its instance folder.
     * A pack whose record carries no manifest (installed by an older build) is reported as skipped
     * rather than guessed at.
     */
    public Report verifyClient(ModpackMeta meta, Path instanceDir, DownloadProgress progress) {
        return verify(meta, instanceDir, PackFileOps.Side.CLIENT, null, progress);
    }

    /** The same check for a pack installed into an owned server's {@code mods/} or {@code plugins/}. */
    public Report verifyServer(ModpackMeta meta, Path serverDir, String addonFolder,
                               DownloadProgress progress) {
        return verify(meta, serverDir, PackFileOps.Side.SERVER, addonFolder, progress);
    }

    // ---- one engine ----

    private Report verify(ModpackMeta meta, Path targetDir, PackFileOps.Side side, String addonFolder,
                          DownloadProgress progress) {
        if (meta == null || targetDir == null || !meta.managesFiles()) {
            return new Report(0, 0, 0, 0, 0, List.of());
        }
        List<ModpackFile> files = ModpackInstaller.ownedFiles(meta.packFiles(), side, addonFolder);
        int checked = 0, missing = 0, restored = 0, failed = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        ZipFile zip = openPackArchive(meta);

        try {
            int total = Math.max(1, files.size());
            for (ModpackFile f : files) {
                checked++;
                Path dest = PackFileOps.resolve(targetDir, f.path());
                if (dest == null) {
                    // This OS can't create that path (Windows-invalid character, reserved device
                    // name, too long) or it would escape the folder. Report it as a failure WITH the
                    // reason: a repair that silently skips a file the pack needs is not a repair.
                    failed++;
                    errors.add(f.path() + " -- " + unresolvableReason(f.path(), targetDir));
                } else if (PackFileOps.matches(dest, f)) {
                    // Present and correct -- the case for every file on a healthy instance.
                } else if (PackFileOps.disabledByUser(targetDir, f.path())) {
                    skipped++; // switched off on purpose in the Mods window: leave the choice alone
                } else {
                    missing++;
                    if (restore(f, dest, zip)) {
                        restored++;
                    } else {
                        failed++;
                        errors.add(f.path() + " -- " + unavailableReason(f));
                    }
                }
                if (progress != null) progress.onProgress((double) checked / total);
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
        return new Report(checked, missing, restored, failed, skipped, errors);
    }

    /**
     * Puts one missing file back: from its own download link, else from the pack archive it came from.
     *
     * <p>A download that fails its hash is DELETED rather than left on disk -- the installer already
     * behaves that way ({@code ModpackInstaller#fetch}), and without it a repair could hand the game a
     * truncated/foreign jar and then keep re-downloading over it on every launch, since the file is
     * never "correct" but is present.
     */
    private boolean restore(ModpackFile f, Path dest, ZipFile zip) {
        if (f.downloadable()) {
            if (!downloader.download(f.url(), dest, f.sizeBytes())) {
                deleteQuietly(dest);
                return false;
            }
            if (f.hasHash() && !PackFileOps.hashMatches(dest, f)) {
                deleteQuietly(dest);
                return false;
            }
            return true;
        }
        try {
            boolean unpacked = PackFileOps.unpackTo(f, zip, dest);
            // A bundled file whose source is gone/corrupt must not be left half-written either.
            if (!unpacked) deleteQuietly(dest);
            return unpacked;
        } catch (IOException e) {
            deleteQuietly(dest);
            return false;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Locked by a running game / no permission: nothing else to do, the report still says so.
        }
    }

    /** The pack archive, when it is still where the pack record says it is -- else null (no overrides). */
    private static ZipFile openPackArchive(ModpackMeta meta) {
        if (meta.source == null || meta.source.isBlank()) return null;
        try {
            Path pack = Path.of(meta.source);
            if (!Files.isRegularFile(pack)) return null;
            return new ZipFile(pack.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private static String unavailableReason(ModpackFile f) {
        if (f.downloadable()) return "the download failed (offline, or the file is gone upstream)";
        return "it was bundled inside the pack and that pack file isn't on disk any more";
    }

    /** Plain-language reason one pack path can't be placed here (see {@link PackFileOps#pathProblem}). */
    private static String unresolvableReason(String rel, Path targetDir) {
        String problem = PackFileOps.pathProblem(rel, targetDir);
        return problem != null ? problem : "the pack listed a path that would escape the instance folder";
    }
}