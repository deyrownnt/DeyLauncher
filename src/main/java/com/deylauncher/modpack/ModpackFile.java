package com.deylauncher.modpack;

/**
 * One file a modpack wants present, in one of two shapes:
 *
 * <ul>
 *   <li><b>remote</b> -- declared by the pack's index (Modrinth {@code files[]}): {@code url} points
 *       at the CDN, with the sizes/hashes the pack shipped so the download can be verified.
 *       {@code archiveEntry} is null.</li>
 *   <li><b>bundled</b> -- already inside the pack (a CurseForge {@code overrides/} file, a MultiMC
 *       {@code minecraft/} file, or anything in a plain zip/folder). {@code archiveEntry} locates it:
 *       a zip entry name, or the absolute path on disk for folder-sourced packs.</li>
 * </ul>
 *
 * {@code path} is always the path relative to the install root (the client instance folder, or the
 * server folder), e.g. {@code mods/sodium-0.6.5.jar}.
 *
 * The {@code env} flags mirror Modrinth's {@code env} block ({@code required} / {@code optional} /
 * {@code unsupported}): a client-only mod is {@code clientUnsupported == false} +
 * {@code serverUnsupported == true}, and a server-only mod is the other way round. Files with no
 * env block are assumed to be fine on both sides, which is how every non-Modrinth format behaves.
 */
public record ModpackFile(String path, String archiveEntry, String url, String sha1, String sha512,
                          long sizeBytes,
                          boolean clientRequired, boolean clientUnsupported,
                          boolean serverRequired, boolean serverUnsupported) {

    /** A file the pack downloads from the internet. */
    public static ModpackFile download(String path, String url, String sha1, String sha512, long sizeBytes,
                                       boolean clientRequired, boolean clientUnsupported,
                                       boolean serverRequired, boolean serverUnsupported) {
        return new ModpackFile(path, null, url, sha1, sha512, sizeBytes,
                clientRequired, clientUnsupported, serverRequired, serverUnsupported);
    }

    /** A file carried inside the pack itself, usable on both a client and a server. */
    public static ModpackFile bundled(String path, String archiveEntry, long sizeBytes) {
        return bundled(path, archiveEntry, sizeBytes, true, true);
    }

    /** A file carried inside the pack itself, with an explicit client/server split (the mrpack
     *  {@code client-overrides/} and {@code server-overrides/} folders). */
    public static ModpackFile bundled(String path, String archiveEntry, long sizeBytes,
                                      boolean usableOnClient, boolean usableOnServer) {
        return new ModpackFile(path, archiveEntry, null, null, null, sizeBytes,
                usableOnClient, !usableOnClient, usableOnServer, !usableOnServer);
    }

    /** Has a URL to fetch from. */
    public boolean downloadable() {
        return url != null && !url.isBlank();
    }

    /** Has at least one hash we can check the download against. */
    public boolean hasHash() {
        return (sha1 != null && !sha1.isBlank()) || (sha512 != null && !sha512.isBlank());
    }
}