package com.deylauncher.modpack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The file-level primitives every modpack operation shares: which side of an install wants a file,
 * where a pack-declared path lands, whether a file on disk still matches what the pack asked for,
 * and how a file gets downloaded or unpacked.
 *
 * <p>Both {@link ModpackInstaller} (putting a pack in place the first time) and
 * {@link ModpackVerifier} (restoring whatever went missing afterwards) go through here, so the two
 * can never disagree about where a mod belongs or what counts as "already there" -- which is exactly
 * the class of bug that leaves a pack half-installed.
 *
 * <p>Downloads are streamed straight to disk (a pack has jars in the hundreds of megabytes, so they
 * are never buffered in memory), retried a few times, and verified against the pack's own size/hash
 * before the file is moved into place. The downloader is injectable so tests never touch the network.
 */
final class PackFileOps {

    private PackFileOps() {}

    /** Which half of an install we are working on. */
    enum Side { CLIENT, SERVER }

    /** How a file gets from the internet onto disk (injected so tests never touch the network). */
    @FunctionalInterface
    interface Downloader {
        boolean download(String url, Path dest, long expectedSizeBytes);
    }

    /** The real downloader: streamed, redirected, retried, size-checked. */
    static final Downloader HTTP = PackFileOps::httpDownload;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            // CurseForge serves files through edge.forgecdn.net with a 302 to the mediafilez CDN, so
            // redirects must be followed -- a plain newHttpClient() refuses to (default is NEVER).
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final int DOWNLOAD_ATTEMPTS = 3;

    /**
     * Which side of the install wants this file. The client takes everything the pack didn't mark
     * client-unsupported; a server takes only what its own addon folder (or config/) reads, and never
     * anything marked server-unsupported -- exactly how an .mrpack's env block is meant to be honoured.
     */
    static boolean wanted(ModpackFile f, Side side, String addonFolder) {
        String path = f.path();
        if (path == null || ModpackReader.isPackMetadata(path)) return false;
        if (side == Side.CLIENT) return !f.clientUnsupported();
        if (f.serverUnsupported()) return false;
        String top = topSegment(path);
        if (addonFolder != null && top.equals(addonFolder)) return true;
        return top.equals("config") || top.equals("defaultconfigs");
    }

    static String topSegment(String rel) {
        String p = rel.replace('\\', '/');
        int slash = p.indexOf('/');
        return (slash < 0 ? p : p.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    /** Resolves a pack path inside {@code base}, or null when it would escape it (zip-slip guard). */
    static Path resolve(Path base, String rel) {
        String safe = ModpackReader.safeRel(rel);
        if (safe == null) return null;
        // A pack that spells a game folder "Mods/" or "Config/" installs into a folder Minecraft never
        // reads on Linux (Windows doesn't care), so the pack silently does nothing there. Canonicalise
        // the top-level segment to the spelling the game itself uses, at the one choke point every
        // install, repair and disable-path already goes through.
        safe = ModpackReader.canonicalGameFolder(safe);
        if (!isWritableOnThisOs(safe, base)) return null;
        Path root = base.toAbsolutePath().normalize();
        Path out = root.resolve(safe).normalize();
        return out.startsWith(root) ? out : null;
    }

    /**
     * True when this pack-relative path can actually be created on THIS operating system. Windows
     * rejects characters ({@code : ? * " < > |}), trailing dots/spaces, device names ({@code CON},
     * {@code NUL}, {@code COM1}...) and long paths -- and a pack carrying one of those used to fail
     * with a bare "Invalid argument" (or, for a device name, resolve to the device). Linux accepts all
     * of them, so this is deliberately OS-specific: a Linux user installing a pack with such a file
     * should still get it.
     */
    static boolean isWritableOnThisOs(String rel, Path base) {
        return pathProblem(rel, base) == null;
    }

    /**
     * Why a pack path cannot be written on this OS, in plain words -- or null when it's fine. Used both
     * as the guard above and as the per-file error message, so a Windows user is told WHICH file and
     * WHY instead of getting a filesystem exception.
     */
    static String pathProblem(String rel, Path base) {
        return pathProblem(rel, base, isWindows());
    }

    /**
     * Testable core of {@link #pathProblem}: {@code windows} is supplied instead of read from the JVM, so
     * the Windows rules can be exercised (and their messages asserted) on any machine. That matters
     * because they are the half of this method a Linux CI runner would otherwise never execute.
     */
    static String pathProblem(String rel, Path base, boolean windows) {
        String p = rel == null ? "" : rel.replace('\\', '/');
        for (String part : p.split("/")) {
            if (part.isBlank()) continue;
            if (part.length() > 255) {
                return "\"" + part + "\" has a file name longer than Windows allows";
            }
            if (windows) {
                if (part.endsWith(".") || part.endsWith(" ") || !part.equals(part.strip())) {
                    return "\"" + part + "\" ends with a dot or a space, which Windows strips "
                            + "(the file could never be found again)";
                }
                for (char c : new char[]{':', '?', '*', '"', '<', '>', '|'}) {
                    if (part.indexOf(c) >= 0) {
                        return "\"" + part + "\" contains '" + c + "', which Windows doesn't allow in a file name";
                    }
                }
                String bare = part.contains(".") ? part.substring(0, part.indexOf('.')) : part;
                if (WINDOWS_DEVICE_NAMES.contains(bare.toUpperCase(Locale.ROOT))) {
                    return "\"" + part + "\" is a reserved Windows device name (CON, NUL, COM1...), not a file";
                }
            }
        }
        if (windows && base != null && base.toAbsolutePath().resolve(p).toString().length() > 259) {
            return "\"" + p + "\" makes a path longer than Windows' 260-character limit";
        }
        return null;
    }

    /** Windows device names that can never be used as a file name (with or without an extension). */
    private static final java.util.Set<String> WINDOWS_DEVICE_NAMES = java.util.Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /** True when a file already on disk is exactly what the pack asked for (hash, else size). */
    static boolean matches(Path file, ModpackFile f) {
        try {
            if (!Files.isRegularFile(file)) return false;
            if (f.hasHash()) return hashMatches(file, f);
            return f.sizeBytes() <= 0 || Files.size(file) == f.sizeBytes();
        } catch (IOException e) {
            return false;
        }
    }

    static boolean hashMatches(Path file, ModpackFile f) {
        try {
            return hashMatches(Files.readAllBytes(file), f);
        } catch (IOException e) {
            return false;
        }
    }

    /** Verifies against sha512 when the pack gave one (Modrinth packs usually give both), else sha1.
     *  A pack that gave no hash at all is accepted, since there is nothing to check it against. */
    static boolean hashMatches(byte[] bytes, ModpackFile f) {
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

    static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Streams {@code url} onto {@code dest} (through a .part file, so a failed attempt never leaves a
     * half-written mod behind), retrying a few times. When {@code expectedSizeBytes > 0} the result
     * must match it exactly -- pack manifests state the size, and CurseForge's file API states it too,
     * so a truncated download is caught here rather than by the game crashing later.
     */
    static boolean httpDownload(String url, Path dest, long expectedSizeBytes) {
        if (url == null || url.isBlank() || dest == null) return false;
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        for (int attempt = 0; attempt < DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.deleteIfExists(part);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "DeyLauncher/0.8")
                        .timeout(Duration.ofMinutes(5))
                        .GET().build();
                HttpResponse<Path> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(part));
                if (resp.statusCode() / 100 != 2) {
                    Files.deleteIfExists(part);
                    continue;
                }
                long size = Files.size(part);
                if (size <= 0 || (expectedSizeBytes > 0 && size != expectedSizeBytes)) {
                    Files.deleteIfExists(part);
                    continue;
                }
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                // Offline, timeout, DNS, a locked file -- try again, then report the failure to the caller.
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    /**
     * Copies one bundled pack file into place: out of the archive (a zip entry), or off disk when the
     * pack was an already-extracted folder. Returns false when the source can't be read.
     */
    static boolean unpackTo(ModpackFile f, ZipFile zip, Path dest) throws IOException {
        if (dest == null) return false;
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
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

    /**
     * True when the mod this path names has been DISABLED by the user -- it lives in
     * {@code mods-disabled/} instead of {@code mods/} (see ModsManager). A repair run must treat that
     * as "present", or switching a pack's mod off in the Mods window would just be undone by the next
     * launch.
     */
    static boolean disabledByUser(Path instanceDir, String rel) {
        // Canonicalise first: a pack that spells the folder "Mods/" installs into mods/ (see
        // ModpackReader#canonicalGameFolder), so the disabled copy lives under mods-disabled/ too.
        String p = ModpackReader.canonicalGameFolder(rel == null ? "" : rel.replace('\\', '/'));
        if (p == null || !p.startsWith("mods/")) return false;
        return Files.exists(instanceDir.resolve("mods-disabled").resolve(p.substring("mods/".length())));
    }
}