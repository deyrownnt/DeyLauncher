package com.deylauncher.server;

import com.deylauncher.modloader.FabricInstaller;
import com.deylauncher.modloader.ForgeInstaller;
import com.deylauncher.modloader.NeoForgeInstaller;
import com.deylauncher.version.VersionManifest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Each server type has a genuinely different download/setup story --
 * Vanilla and Purpur are a single jar, Fabric's official server launcher
 * jar is self-contained (downloads its own dependencies on first run),
 * and Forge needs its real installer run in --installServer mode (same
 * reasoning as ForgeInstaller for the client: reimplementing Forge's
 * internal patch pipeline ourselves would be fragile).
 */
public class ServerDownloader {

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionManifest manifest;

    public ServerDownloader(VersionManifest manifest) {
        this.manifest = manifest;
    }

    /** Returns the path to the thing that should actually be launched (a jar, or for modern Forge, null -- see ServerProcessManager). */
    public Path ensureServerJar(ServerInstance server, Path serverDir, String javaBinary) throws Exception {
        return ensureServerJar(server, serverDir, javaBinary, message -> { });
    }

    /**
     * Same, reporting anything it had to replace through {@code note} so the server's own console
     * explains itself (this runs inside a background task, so messages can't be shown directly).
     *
     * <p>The version check at the top is the fix for "created a server, changed it to a lower version,
     * and it crashes on the next launch": the folder may only be reused when it was installed for
     * exactly this type and version. Anything else -- the jar left over from the previous version and,
     * for Fabric/Forge, the loader's libraries, downloaded vanilla jar and generated launch args that
     * belong with it -- is removed first, so the next Start can never launch the old software against
     * the new version.
     */
    public Path ensureServerJar(ServerInstance server, Path serverDir, String javaBinary,
                               java.util.function.Consumer<String> note) throws Exception {
        Files.createDirectories(serverDir);

        ServerSoftwareMarker installed = ServerSoftwareMarker.read(serverDir);
        // No marker (a server folder created by an older build) is not treated as a mismatch by
        // itself -- but for Fabric and Forge the version can still be read out of the layout they
        // create, which is exactly the case where leftovers would otherwise be launched.
        String installedVersion = installed != null
                ? installed.minecraftVersion
                : ServerSoftwareMarker.detectInstalledVersion(serverDir, server.type);
        if (ServerSoftwareMarker.needsFreshInstall(installed, installedVersion, server)) {
            java.util.List<String> removed = ServerSoftwareMarker.purgeVersionBoundSoftware(serverDir);
            if (!removed.isEmpty()) {
                note.accept("Server files were for " + installedVersion + ", but this server is set to "
                        + server.minecraftVersion + " -- replacing " + String.join(", ", removed)
                        + " with a fresh download.");
            }
        }

        Path launched = switch (server.type) {
            case VANILLA -> downloadVanilla(server.minecraftVersion, serverDir);
            case PURPUR -> downloadPurpur(server.minecraftVersion, serverDir);
            case FABRIC -> downloadFabricServerLauncher(server.minecraftVersion, serverDir);
            case FORGE -> installForgeServer(server.minecraftVersion, serverDir, javaBinary);
            case NEOFORGE -> installNeoForgeServer(server.minecraftVersion, serverDir, javaBinary);
        };
        // Recorded only once the software is actually in place, so a failed/interrupted download stays
        // "unknown" and is retried on the next Start instead of being trusted.
        ServerSoftwareMarker.write(serverDir, ServerSoftwareMarker.forServer(server));
        return launched;
    }

    private Path downloadVanilla(String mcVersion, Path serverDir) throws Exception {
        Path jar = serverDir.resolve("server.jar");
        if (Files.exists(jar)) return jar;
        var all = manifest.fetchAll();
        var entry = manifest.findById(all, mcVersion);
        if (entry == null) throw new IllegalStateException("Version " + mcVersion + " not found in Mojang's manifest.");
        JsonObject versionJson = manifest.fetchVersionDetail(entry);
        if (!versionJson.getAsJsonObject("downloads").has("server")) {
            throw new IllegalStateException("Mojang doesn't publish a server jar for " + mcVersion
                    + " (some very old/snapshot versions have no dedicated server build).");
        }
        String url = versionJson.getAsJsonObject("downloads").getAsJsonObject("server").get("url").getAsString();
        downloadTo(url, jar);
        return jar;
    }

    private Path downloadPurpur(String mcVersion, Path serverDir) throws Exception {
        Path jar = serverDir.resolve("server.jar");
        if (Files.exists(jar)) return jar;
        String url = "https://api.purpurmc.org/v2/purpur/" + mcVersion + "/latest/download";
        HttpResponse<Path> resp = downloadToChecked(url, jar);
        if (resp.statusCode() == 404) {
            throw new IllegalStateException("Purpur has no build for Minecraft " + mcVersion + ".");
        }
        return jar;
    }

    private Path downloadFabricServerLauncher(String mcVersion, Path serverDir) throws Exception {
        Path jar = serverDir.resolve("server.jar");
        if (Files.exists(jar)) return jar;

        FabricInstaller helper = new FabricInstaller(manifest, serverDir); // only used for latestLoaderVersion() here
        String loaderVersion = helper.latestLoaderVersion(mcVersion);
        if (loaderVersion == null) throw new IllegalStateException("Fabric has no loader build for " + mcVersion + " yet.");
        String installerVersion = latestFabricInstallerVersion();

        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + loaderVersion
                + "/" + installerVersion + "/server/jar";
        downloadTo(url, jar);
        return jar; // self-contained -- downloads the vanilla server + loader itself on first run
    }

    private String latestFabricInstallerVersion() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://meta.fabricmc.net/v2/versions/installer")).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        var arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            if (obj.get("stable").getAsBoolean()) return obj.get("version").getAsString();
        }
        return arr.get(0).getAsJsonObject().get("version").getAsString();
    }

    /**
     * Returns null on success for modern Forge (which produces an @args-file launch, not a
     * single jar -- see ServerProcessManager.buildForgeCommand for how that gets launched).
     */
    private Path installForgeServer(String mcVersion, Path serverDir, String javaBinary) throws Exception {
        // Reuses the marker files the modern Forge server installer itself produces, so re-runs
        // are free once already installed.
        if (Files.exists(serverDir.resolve("run.sh")) || Files.exists(serverDir.resolve("run.bat"))) {
            return null;
        }
        ForgeInstaller forgeInstaller = new ForgeInstaller(manifest, serverDir.getParent().getParent());
        String forgeVersion = forgeInstaller.recommendedOrLatestVersion(mcVersion);
        if (forgeVersion == null) throw new IllegalStateException("Forge has no build for " + mcVersion + " yet.");

        String longVersion = mcVersion + "-" + forgeVersion;
        // Named per build on purpose. The old fixed "forge-installer.jar" was reused whenever it
        // existed, so after a version change the installer for the PREVIOUS version ran again.
        Path installerJar = serverDir.resolve("forge-installer-" + longVersion + ".jar");
        if (!Files.exists(installerJar)) {
            String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + longVersion
                    + "/forge-" + longVersion + "-installer.jar";
            downloadTo(url, installerJar);
        }

        ProcessBuilder pb = new ProcessBuilder(javaBinary, "-jar", installerJar.toString(),
                "--installServer", serverDir.toString());
        pb.directory(serverDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain the installer's output so it can't block on a full pipe, but KEEP the last lines:
        // "exit code 1" on its own is impossible to act on, and this is where Forge explains itself.
        Deque<String> tail = new ArrayDeque<>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == 40) tail.removeFirst();
                tail.addLast(line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Forge server installer exited with code " + exit
                    + ".\n" + tailText(tail, "Forge"));
        }
        return null;
    }

    /**
     * NeoForge's server install -- deliberately the same shape as {@link #installForgeServer}, because
     * NeoForge ships Forge's installer code and the same {@code --installServer} contract applies: it
     * downloads/patches into this folder and generates run.sh/run.bat plus the per-OS
     * {@code @..._args.txt} under {@code libraries/net/neoforged/neoforge/<neoforgeversion>/} that
     * {@link ServerProcessManager} launches with. Those generated files are also what makes a re-run
     * free once the server is already installed.
     *
     * <p>The build comes from NeoForge's own Maven for THIS Minecraft version (stable preferred, and
     * a brand-new Minecraft release legitimately only has betas for a while -- 26.3's first builds are
     * {@code 26.3.0.x-beta}). Returns null on success, meaning "launch via the generated @args file,
     * not a single jar".
     */
    private Path installNeoForgeServer(String mcVersion, Path serverDir, String javaBinary) throws Exception {
        if (Files.exists(serverDir.resolve("run.sh")) || Files.exists(serverDir.resolve("run.bat"))) {
            return null;
        }
        NeoForgeInstaller installer = new NeoForgeInstaller(manifest, serverDir.getParent().getParent());
        NeoForgeInstaller.Target target = NeoForgeInstaller.targetFor(mcVersion);
        String neoVersion = installer.latestVersion(mcVersion);
        if (target == null || neoVersion == null) {
            throw new IllegalStateException("NeoForge has no build for " + mcVersion + " yet.");
        }

        // Named per build on purpose, exactly like Forge's: a fixed "neoforge-installer.jar" would be
        // reused after a version change and re-install the PREVIOUS version.
        Path installerJar = serverDir.resolve("neoforge-installer-" + neoVersion + ".jar");
        if (!Files.exists(installerJar)) {
            downloadTo(NeoForgeInstaller.installerUrl(target, neoVersion), installerJar);
        }

        ProcessBuilder pb = new ProcessBuilder(javaBinary, "-jar", installerJar.toString(),
                "--installServer", serverDir.toString());
        pb.directory(serverDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain the installer's output so it can't block on a full pipe, but KEEP the last lines:
        // "-exit code 1" on its own is impossible to act on, and this is where NeoForge explains itself.
        Deque<String> tail = new ArrayDeque<>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == 40) tail.removeFirst();
                tail.addLast(line);
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("NeoForge server installer exited with code " + exit
                    + ".\n" + tailText(tail, "NeoForge"));
        }
        return null;
    }

    /** The captured installer output as indented lines, for attaching to a failure message. */
    private static String tailText(Deque<String> tail, String loaderName) {
        if (tail.isEmpty()) return "(the " + loaderName + " installer printed no output)";
        StringBuilder sb = new StringBuilder(loaderName + " installer said:\n");
        for (String line : tail) sb.append("  ").append(line).append('\n');
        return sb.toString();
    }

    private void downloadTo(String url, Path dest) throws Exception {
        HttpResponse<Path> resp = downloadToChecked(url, dest);
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Download failed (" + resp.statusCode() + "): " + url);
        }
    }

    private HttpResponse<Path> downloadToChecked(String url, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
    }
}
