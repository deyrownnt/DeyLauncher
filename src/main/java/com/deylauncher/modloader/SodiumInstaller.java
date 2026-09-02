package com.deylauncher.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Every DEY build ships a Sodium-family performance mod, fetched from Modrinth's public,
 * documented API (the same kind of official integration point as Fabric's meta API, see
 * FabricInstaller) -- not reverse-engineered or scraped.
 *
 * Real Sodium is Fabric/Quilt-only; it doesn't run on Forge at all. For a DEY Forge instance
 * this installs Embeddium instead -- the actual Forge-compatible continuation of Sodium (same
 * renderer, same author lineage), rather than silently doing nothing or claiming to install
 * something that can't work under Forge.
 */
public class SodiumInstaller {

    private final String projectSlug;
    private final String loaderName;
    private final HttpClient http = HttpClient.newHttpClient();

    public SodiumInstaller(String modLoader) {
        if ("Forge".equalsIgnoreCase(modLoader)) {
            this.projectSlug = "embeddium";
            this.loaderName = "forge";
        } else {
            this.projectSlug = "sodium";
            this.loaderName = "fabric";
        }
    }

    /** True if a matching performance-mod jar is already present in this instance's mods folder. */
    public boolean isInstalled(Path modsDir) throws Exception {
        if (!Files.isDirectory(modsDir)) return false;
        String prefix = projectSlug + "-";
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith(prefix));
        }
    }

    /**
     * Downloads the build matching mcVersion+loader into modsDir, unless one is already there.
     * Returns the installed file's name, or null if it's already installed or Modrinth has no
     * matching build for this Minecraft version yet.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        if (isInstalled(modsDir)) return null;

        String listUrl = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + projectSlug + "-auto-install)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Modrinth request failed: HTTP " + resp.statusCode());
        }
        JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();

        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!supportsLoader(v) || !supportsVersion(v, mcVersion)) continue;

            JsonArray files = v.getAsJsonArray("files");
            if (files.isEmpty()) continue;
            JsonObject file = primaryFile(files);

            String fileUrl = file.get("url").getAsString();
            String fileName = file.get("filename").getAsString();

            Files.createDirectories(modsDir);
            Path tmp = modsDir.resolve(fileName + ".part");
            HttpResponse<Path> fileResp = http.send(
                    HttpRequest.newBuilder(URI.create(fileUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (fileResp.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException(projectSlug + " download failed: HTTP " + fileResp.statusCode());
            }
            Files.move(tmp, modsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            return fileName;
        }
        return null; // no matching build published for this Minecraft version yet
    }

    private boolean supportsLoader(JsonObject version) {
        for (var l : version.getAsJsonArray("loaders")) {
            if (l.getAsString().equalsIgnoreCase(loaderName)) return true;
        }
        return false;
    }

    private boolean supportsVersion(JsonObject version, String mcVersion) {
        for (var gv : version.getAsJsonArray("game_versions")) {
            if (gv.getAsString().equals(mcVersion)) return true;
        }
        return false;
    }

    private JsonObject primaryFile(JsonArray files) {
        for (var f : files) {
            JsonObject obj = f.getAsJsonObject();
            if (obj.has("primary") && obj.get("primary").getAsBoolean()) return obj;
        }
        return files.get(0).getAsJsonObject();
    }
}
