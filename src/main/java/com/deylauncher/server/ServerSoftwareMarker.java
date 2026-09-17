package com.deylauncher.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Remembers WHICH server software is actually installed in a server folder -- the server type and the
 * Minecraft version -- in a small {@code deylauncher-software.json} next to it.
 *
 * <p>Why this exists: a server folder is downloaded once and then reused forever (a start must not
 * re-download a 50 MB jar every time). The old code expressed that as "if server.jar exists, use it",
 * which is correct for the first version and silently wrong for every later one: changing a server
 * from, say, 1.21 to 1.20.1 left the 1.21 jar -- and, for Fabric/Forge, the 1.21 loader, its
 * libraries and its generated launch-args files -- in place, so the next Start launched the OLD
 * software against the newly selected version. That is the "created a server, changed it to a lower
 * version, and it crashes on the next launch" bug: the marker is what lets a start tell "already
 * installed for exactly this version" apart from "left over from a different one".
 *
 * <p>The marker is written only after a successful download/install, so a failed or interrupted
 * download is retried instead of trusted. A missing marker (a server created by an older build) is
 * handled by {@link #detectInstalledVersion}, which reads the version straight out of the layout
 * Fabric and Forge produce.
 */
public final class ServerSoftwareMarker {

    /** File name inside a server's folder. */
    public static final String FILE_NAME = "deylauncher-software.json";

    public String type;
    public String minecraftVersion;
    public long installedAt;

    public ServerSoftwareMarker() {}

    public ServerSoftwareMarker(String type, String minecraftVersion) {
        this.type = type;
        this.minecraftVersion = minecraftVersion;
        this.installedAt = System.currentTimeMillis();
    }

    public static Path fileIn(Path serverDir) {
        return serverDir.resolve(FILE_NAME);
    }

    /** Builds the marker describing the software a server should end up with. */
    public static ServerSoftwareMarker forServer(ServerInstance server) {
        return new ServerSoftwareMarker(
                server.type != null ? server.type.name() : null,
                server.minecraftVersion);
    }

    /**
     * True when the marker describes exactly the software this server is configured for. Compared by
     * type AND version: a folder whose files are for another version must be re-downloaded, never reused.
     */
    public boolean matches(ServerInstance server) {
        if (server == null || server.type == null) return false;
        return server.type.name().equals(type)
                && server.minecraftVersion != null
                && server.minecraftVersion.equals(minecraftVersion);
    }

    /** Reads the marker, or null when it is absent/unreadable/blank (i.e. "we don't know"). */
    public static ServerSoftwareMarker read(Path serverDir) {
        Path file = fileIn(serverDir);
        if (!Files.exists(file)) return null;
        try {
            ServerSoftwareMarker marker = new Gson().fromJson(Files.readString(file), ServerSoftwareMarker.class);
            if (marker == null || marker.minecraftVersion == null || marker.minecraftVersion.isBlank()) return null;
            return marker;
        } catch (Exception ignored) {
            return null; // a corrupt marker means "unknown", which is safe: it forces a fresh download
        }
    }

    /** Records what is installed now. Best-effort: a marker that can't be written only costs one re-download. */
    public static void write(Path serverDir, ServerSoftwareMarker marker) {
        try {
            Files.createDirectories(serverDir);
            Files.writeString(fileIn(serverDir),
                    new GsonBuilder().setPrettyPrinting().create().toJson(marker));
        } catch (IOException ignored) {
        }
    }

    /**
     * True when the software in a server folder must be replaced instead of reused -- the decision that
     * used to be "does server.jar exist?" and is now "was it installed for exactly this version?".
     *
     * @param installed       the marker found in the folder, or null when there is none
     * @param detectedVersion the version read out of the folder layout when there is no marker (null
     *                        when it can't be told, e.g. a Vanilla jar), see {@link #detectInstalledVersion}
     */
    public static boolean needsFreshInstall(ServerSoftwareMarker installed, String detectedVersion,
                                            ServerInstance server) {
        if (installed != null) return !installed.matches(server);
        return detectedVersion != null && !detectedVersion.equals(server.minecraftVersion);
    }

    /**
     * Everything in a server folder that belongs to ONE specific Minecraft version / loader build:
     * the jar itself, Forge's generated launch scripts, and the loader homes that Fabric and Forge
     * fill with their own libraries, generated args files and downloaded vanilla jars. The world,
     * {@code server.properties}, the ops/whitelist files and player data are deliberately NOT in this
     * list -- those belong to the user, not to the software.
     */
    public static List<String> versionBoundPaths() {
        // serverstarter.jar belongs to NeoForge: its server installer drops that launcher jar in the
        // server root (the modern args-file launch goes through it), so it is version-bound exactly
        // like Forge's generated run scripts.
        return List.of("server.jar", "run.sh", "run.bat", "user_jvm_args.txt", "serverstarter.jar",
                "libraries", "versions", ".fabric");
    }

    /**
     * Deletes every version-bound file/folder from {@code serverDir}, and returns the names it removed
     * (for an honest "here's what was replaced" message). Version-named Forge/NeoForge installers are
     * removed too, along with the fixed {@code forge-installer.jar} that older builds reused for
     * every version.
     */
    public static List<String> purgeVersionBoundSoftware(Path serverDir) {
        List<String> removed = new ArrayList<>();
        for (String name : versionBoundPaths()) {
            if (deleteRecursively(serverDir.resolve(name))) removed.add(name);
        }
        try (Stream<Path> stream = Files.list(serverDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String fileName = p.getFileName().toString();
                boolean versionNamedInstaller = (fileName.startsWith("forge-installer")
                        || fileName.startsWith("neoforge-installer")) && fileName.endsWith(".jar");
                if (versionNamedInstaller && deleteRecursively(p)) {
                    removed.add(fileName);
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }

    /**
     * Best-effort answer to "which Minecraft version is the software in this folder actually for?" for
     * server folders that predate the marker (or whose marker was lost), read from the layout the
     * loaders create rather than guessed:
     *
     * <ul>
     *   <li><b>Fabric</b> -- the vanilla jar it downloaded lives at
     *       {@code versions/<mcversion>/server-<mcversion>.jar}, so the single version directory names
     *       the version. Several directories (or none) means we cannot tell, and null is returned.</li>
     *   <li><b>Forge</b> -- its libraries land under
     *       {@code libraries/net/minecraftforge/forge/<mcversion>-<forgeversion>/}, so the prefix
     *       before the dash is the Minecraft version. Again: ambiguous layouts report null.</li>
     *   <li><b>NeoForge</b> -- its own libraries land under
     *       {@code libraries/net/neoforged/neoforge/<neoforgeversion>/}, where NeoForge's numbering
     *       carries the Minecraft version in its first two segments ({@code 26.2.0.88} -&gt; 26.2,
     *       {@code 21.1.72} -&gt; 1.21.1). The legacy 1.20.1 line is installed Forge-style as
     *       {@code 1.20.1-47.1.x} under Forge's own path, which is checked as a fallback.</li>
     *   <li><b>Vanilla / Purpur</b> -- the jar carries no reliable version marker, so this returns
     *       null and the caller falls back to trusting the folder once (the pre-existing behaviour);
     *       from then on the marker written on the next start keeps it honest.</li>
     * </ul>
     */
    public static String detectInstalledVersion(Path serverDir, ServerType type) {
        if (serverDir == null || type == null) return null;
        return switch (type) {
            case FABRIC -> singleFabricVersion(serverDir);
            case FORGE -> singleForgeVersion(serverDir);
            case NEOFORGE -> singleNeoForgeVersion(serverDir);
            case VANILLA, PURPUR -> null;
        };
    }

    private static String singleFabricVersion(Path serverDir) {
        Path versionsDir = serverDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return null;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            List<String> names = stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .distinct()
                    .toList();
            return names.size() == 1 ? names.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String singleForgeVersion(Path serverDir) {
        Path forgeDir = serverDir.resolve("libraries").resolve("net").resolve("minecraftforge").resolve("forge");
        if (!Files.isDirectory(forgeDir)) return null;
        try (Stream<Path> stream = Files.list(forgeDir)) {
            List<String> versions = stream.filter(Files::isDirectory)
                    // "1.20.1-47.4.20" -> "1.20.1"; anything without the separator can't be read, so skip it.
                    .map(name -> {
                        String dirName = name.getFileName().toString();
                        int dash = dirName.indexOf('-');
                        return dash > 0 ? dirName.substring(0, dash) : null;
                    })
                    .filter(v -> v != null && !v.isBlank())
                    .distinct()
                    .toList();
            return versions.size() == 1 ? versions.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * NeoForge's own version, read out of the layout its server installer creates.
     *
     * <p>Two shapes have to be recognised, because NeoForge changed both its artifact and its
     * numbering scheme (both verified against the real Maven repository):
     * <ul>
     *   <li><b>Modern (1.20.2+ and 26.x)</b> -- {@code libraries/net/neoforged/neoforge/<neoforgeversion>/},
     *       e.g. {@code 26.2.0.88} or {@code 21.1.72}. NeoForge's own version already carries the
     *       Minecraft version as its first two segments, so it can be read straight back out.</li>
     *   <li><b>Legacy 1.20.1</b> -- installed as Forge-style {@code 1.20.1-47.1.x} under
     *       {@code libraries/net/minecraftforge/forge/}, which {@link #singleForgeVersion} already
     *       understands, so that is the fallback.</li>
     * </ul>
     * Ambiguous layouts (several installed builds) still report null, exactly like Fabric/Forge.
     */
    private static String singleNeoForgeVersion(Path serverDir) {
        Path modernDir = serverDir.resolve("libraries").resolve("net").resolve("neoforged").resolve("neoforge");
        if (Files.isDirectory(modernDir)) {
            try (Stream<Path> stream = Files.list(modernDir)) {
                List<String> versions = stream.filter(Files::isDirectory)
                        .map(name -> neoforgeToMinecraft(name.getFileName().toString()))
                        .filter(v -> v != null && !v.isBlank())
                        .distinct()
                        .toList();
                if (versions.size() == 1) return versions.get(0);
                if (!versions.isEmpty()) return null; // several builds: can't tell which one is installed
            } catch (IOException ignored) {
                // Fall through to the legacy check below.
            }
        }
        return singleForgeVersion(serverDir);
    }

    /**
     * Maps a NeoForge version/directory name onto the Minecraft version it belongs to, or null when it
     * can't be read:
     * <pre>
     *   26.2.0.88        -> 26.2      (leading segment IS the Minecraft major)
     *   21.1.72          -> 1.21.1    (1.21.1-era scheme: "1." is implied)
     *   20.4.237         -> 1.20.4
     *   1.20.1-47.1.106  -> 1.20.1    (legacy 1.20.1 line, Forge-style name)
     * </pre>
     * Package-private so the mapping table is unit-testable without touching the disk.
     */
    static String neoforgeToMinecraft(String neoforgeVersion) {
        if (neoforgeVersion == null) return null;
        String v = neoforgeVersion.trim();
        if (v.isEmpty()) return null;

        // Legacy 1.20.1: "<mcversion>-<forge-style build>".
        int dash = v.indexOf('-');
        if (dash > 0 && v.startsWith("1.")) return v.substring(0, dash);

        String[] parts = v.split("\\.");
        if (parts.length < 2) return null;
        if (!parts[0].chars().allMatch(Character::isDigit) || parts[0].isEmpty()) return null;
        int major = Integer.parseInt(parts[0]);
        // 26.x and later dropped the "1." prefix of the Minecraft version entirely; the 20.x/21.x
        // lines are the second-half versions of "1.x" (20.4 -> 1.20.4, 21.1 -> 1.21.1).
        String minecraft = major >= 26 ? parts[0] + "." + parts[1] : "1." + parts[0] + "." + parts[1];
        return minecraft;
    }

    /** Deletes a file, or a whole directory tree; returns true when nothing of it is left behind. */
    private static boolean deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return false;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort: a locked file just stays behind; the caller still re-downloads.
                }
            });
        } catch (IOException ignored) {
            return false;
        }
        return !Files.exists(path);
    }
}