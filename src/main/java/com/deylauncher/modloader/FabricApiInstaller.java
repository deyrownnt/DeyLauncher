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
 * Fabric API ships bundled with every DEY Fabric build -- most Fabric mods (Sodium included,
 * for some versions) declare it as a dependency, so having it missing is a common source of
 * "the game won't even start" reports. Fetched from Modrinth's public, documented API, the
 * same integration point SodiumInstaller and FabricInstaller already use.
 *
 * Fabric API is Fabric/Quilt-only by definition -- there's no Forge build and no Forge
 * equivalent to substitute (Forge's own API is built into Forge itself), so this only runs
 * for DEY Fabric instances, never Forge ones.
 */
public class FabricApiInstaller {

    private static final String PROJECT_SLUG = "fabric-api";
    private static final String LOADER_NAME = "fabric";

    private final HttpClient http = HttpClient.newHttpClient();

    /** True if a fabric-api-*.jar is already present in this instance's mods folder. */
    public boolean isInstalled(Path modsDir) throws Exception {
        if (!Files.isDirectory(modsDir)) return false;
        try (var stream = Files.list(modsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith(PROJECT_SLUG + "-"));
        }
    }

    /**
     * Downloads the Fabric API build matching mcVersion into modsDir, unless one is already
     * there. Returns the installed file's name, or null if it's already installed or Modrinth
     * has no matching build for this Minecraft version yet.
     */
    public String ensureInstalled(String mcVersion, Path modsDir) throws Exception {
        if (isInstalled(modsDir)) return null;

        String listUrl = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
                .header("User-Agent", "DeyLauncher/0.1 (+" + PROJECT_SLUG + "-auto-install)")
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
                throw new IllegalStateException(PROJECT_SLUG + " download failed: HTTP " + fileResp.statusCode());
            }
            Files.move(tmp, modsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            return fileName;
        }
        return null; // no matching build published for this Minecraft version yet
    }

    private boolean supportsLoader(JsonObject version) {
        for (var l : version.getAsJsonArray("loaders")) {
            if (l.getAsString().equalsIgnoreCase(LOADER_NAME)) return true;
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
