package com.deylauncher.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Mojang's public version_manifest_v2.json -- this is the same file the
 * official launcher uses, so it always has every release/snapshot the moment
 * Mojang ships it (no waiting on us to add versions manually).
 */
public class VersionManifest {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http = HttpClient.newHttpClient();

    public record VersionEntry(String id, String type, String url, String releaseTime) {}

    /** Fetches the live list of every version Mojang currently serves. */
    public List<VersionEntry> fetchAll() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MANIFEST_URL)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray versions = root.getAsJsonArray("versions");

        List<VersionEntry> out = new ArrayList<>();
        for (var el : versions) {
            JsonObject v = el.getAsJsonObject();
            out.add(new VersionEntry(
                    v.get("id").getAsString(),
                    v.get("type").getAsString(),
                    v.get("url").getAsString(),
                    v.get("releaseTime").getAsString()
            ));
        }
        return out;
    }

    /**
     * Convenience for your two defaults. "26.2" doesn't exist as a Mojang
     * version id (Minecraft doesn't use that numbering) -- see the note in
     * Main.java. This returns whichever entry matches the id you pass in,
     * e.g. "1.21.1", or null if that id isn't in the manifest.
     */
    public VersionEntry findById(List<VersionEntry> all, String id) {
        for (VersionEntry v : all) {
            if (v.id().equals(id)) return v;
        }
        return null;
    }

    /** Fetches the full per-version JSON (libraries, main class, asset index, download URLs). */
    public JsonObject fetchVersionDetail(VersionEntry entry) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(entry.url())).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
