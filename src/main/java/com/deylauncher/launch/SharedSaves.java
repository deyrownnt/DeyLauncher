package com.deylauncher.launch;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/**
 * Makes every version+loader instance (DEY or VANILLA, any Minecraft version) share ONE pool of
 * singleplayer worlds instead of each instance keeping its own isolated {@code saves/} folder.
 * Minecraft always reads/writes worlds at {@code <gameDir>/saves}, so the trick is simply to make
 * that path a link into one shared folder under the launcher root -- every instance then
 * transparently sees the exact same worlds, no matter which version or loader launched it.
 *
 * Only singleplayer worlds are affected: nothing else under an instance folder (mods, config,
 * options.txt, resourcepacks, server list, etc.) is touched or shared -- each instance keeps its
 * own copy of everything except {@code saves/}.
 */
public class SharedSaves {

    private static final String FOLDER_NAME = "shared-saves";

    private SharedSaves() {}

    /** The one shared saves folder every instance links to, e.g. ~/.deylauncher/shared-saves. */
    public static Path sharedDir(Path launcherRoot) {
        return launcherRoot.resolve(FOLDER_NAME);
    }

    /**
     * Makes {@code gameDir/saves} point at the shared saves folder, migrating any worlds that
     * already live there (the first time this runs for an existing instance) into the shared
     * folder first so nothing is lost -- a same-named world already shared is left alone (assumed
     * already migrated), and a genuine same-named-but-different world is kept under a
     * disambiguated name rather than ever being discarded.
     *
     * Best-effort, like the rest of this codebase's non-essential launch steps: any failure here
     * (permissions, no symlink support, etc.) just leaves this one instance with its own local
     * saves/ for this run instead of blocking the launch.
     */
    public static void ensureShared(Path launcherRoot, Path gameDir) {
        try {
            Path shared = sharedDir(launcherRoot);
            Files.createDirectories(shared);
            Path sharedReal = shared.toRealPath();
            Path saves = gameDir.resolve("saves");

            if (Files.isSymbolicLink(saves)) {
                Path target;
                try {
                    target = saves.toRealPath();
                } catch (IOException brokenLink) {
                    target = null;
                }
                if (target != null && target.equals(sharedReal)) return; // already linked correctly
                Files.delete(saves); // stale/broken link, or linked somewhere else -- relink below
            } else if (Files.isDirectory(saves)) {
                migrateWorldsInto(saves, shared);
                deleteTree(saves); // whatever's left after migrating every world out -- clear it for the link
            } else if (Files.exists(saves)) {
                return; // "saves" exists but is neither a directory nor a link -- leave it alone
            }

            if (!Files.exists(saves)) link(saves, shared);
        } catch (Exception ignored) {
            // Best-effort -- worlds just won't be shared for this particular launch.
        }
    }

    /** Moves each world folder out of an instance's local {@code saves/} into the shared pool. */
    private static void migrateWorldsInto(Path from, Path into) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var stream = Files.list(from)) {
            for (Path world : stream.toList()) {
                if (!Files.isDirectory(world)) continue; // saves/ should only ever hold world folders
                moveWorldAvoidingCollision(world, into);
            }
        }
    }

    /** Moves one world folder into {@code into}, never overwriting an existing folder there --
     *  an identical-looking world already present is treated as already migrated (skipped); a
     *  different world sharing the same name is kept under a disambiguated name instead. */
    private static void moveWorldAvoidingCollision(Path world, Path into) throws IOException {
        String name = world.getFileName().toString();
        Path dest = into.resolve(name);
        int suffix = 2;
        while (Files.exists(dest)) {
            if (looksLikeSameWorld(world, dest)) return; // already shared -- leave the local copy for cleanup
            dest = into.resolve(name + " (" + suffix++ + ")");
        }
        Files.move(world, dest);
    }

    /** Cheap heuristic (same level.dat size) good enough to avoid silently discarding a genuinely
     *  different world that happens to share a folder name with an already-shared one. */
    private static boolean looksLikeSameWorld(Path a, Path b) {
        try {
            Path levelA = a.resolve("level.dat");
            Path levelB = b.resolve("level.dat");
            return Files.exists(levelA) && Files.exists(levelB) && Files.size(levelA) == Files.size(levelB);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Leave anything that can't be removed (e.g. a world that couldn't be moved) --
                    // ensureShared() falls back to that instance keeping its own saves/ for this run.
                }
            });
        }
    }

    /** Symlink on Linux/macOS. On Windows, a directory junction (via {@code mklink /J}) instead of
     *  a real symlink -- junctions work on directories without Admin rights or Developer Mode,
     *  unlike Windows symlinks, and the JDK (12+) treats them the same as symlinks afterwards. */
    private static void link(Path link, Path target) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            if (!Files.exists(link)) throw new IOException("mklink /J failed for " + link);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
