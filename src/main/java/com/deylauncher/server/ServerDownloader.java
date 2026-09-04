package com.deylauncher.server;

import com.deylauncher.modloader.FabricInstaller;
import com.deylauncher.modloader.ForgeInstaller;
import com.deylauncher.version.VersionManifest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

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
        Files.createDirectories(serverDir);
        return switch (server.type) {
            case VANILLA -> downloadVanilla(server.minecraftVersion, serverDir);
            case PURPUR -> downloadPurpur(server.minecraftVersion, serverDir);
            case FABRIC -> downloadFabricServerLauncher(server.minecraftVersion, serverDir);
            case FORGE -> installForgeServer(server.minecraftVersion, serverDir, javaBinary);
        };
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
        Path installerJar = serverDir.resolve("forge-installer.jar");
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
        try (var in = process.getInputStream()) {
            in.readAllBytes(); // drain so the installer never blocks on a full pipe
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Forge server installer exited with code " + exit);
        }
        return null;
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
