package com.deylauncher.modloader;

import com.deylauncher.version.VersionManifest;
import com.deylauncher.version.VersionResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric publishes a clean public "meta" API specifically so third-party
 * launchers can integrate it without reimplementing its installer --
 * this is the officially documented way to do it, not reverse-engineered.
 */
public class FabricInstaller {

    private static final String META_BASE = "https://meta.fabricmc.net/v2/versions";

    private final HttpClient http = HttpClient.newHttpClient();
    private final VersionManifest manifest;
    private final VersionResolver resolver;
    private final Path root;

    public FabricInstaller(VersionManifest manifest, Path launcherRoot) {
        this.manifest = manifest;
        this.resolver = new VersionResolver(manifest);
        this.root = launcherRoot;
    }

    /** Latest stable loader build for this Minecraft version, or null if Fabric hasn't published one yet. */
    public String latestLoaderVersion(String mcVersion) throws Exception {
        JsonArray loaders = fetchJsonArray(META_BASE + "/loader/" + mcVersion);
        for (var el : loaders) {
            JsonObject entry = el.getAsJsonObject();
            JsonObject loader = entry.getAsJsonObject("loader");
            if (loader.get("stable").getAsBoolean()) {
                return loader.get("version").getAsString();
            }
        }
        return loaders.isEmpty() ? null : loaders.get(0).getAsJsonObject()
                .getAsJsonObject("loader").get("version").getAsString();
    }

    /** Downloads/caches the Fabric profile for mcVersion+loaderVersion and returns it fully merged with vanilla. */
    public JsonObject install(String mcVersion, String loaderVersion) throws Exception {
        String versionId = "fabric-loader-" + loaderVersion + "-" + mcVersion;
        Path cached = root.resolve("versions").resolve(versionId).resolve(versionId + ".json");

        JsonObject profile;
        if (Files.exists(cached)) {
            profile = JsonParser.parseString(Files.readString(cached)).getAsJsonObject();
        } else {
            profile = fetchJsonObject(META_BASE + "/loader/" + mcVersion + "/" + loaderVersion + "/profile/json");
            Files.createDirectories(cached.getParent());
            Files.writeString(cached, profile.toString());
        }
        return resolver.resolve(profile);
    }

    private JsonObject fetchJsonObject(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            throw new IllegalStateException("Fabric has no build for this Minecraft version yet.");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private JsonArray fetchJsonArray(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(resp.body()).getAsJsonArray();
    }
}
