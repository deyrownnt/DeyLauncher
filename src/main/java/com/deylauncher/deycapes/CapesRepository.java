package com.deylauncher.deycapes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.UnaryOperator;

/**
 * Talks to GitHub's Contents API (repos.contents) to read/write the two shared
 * Dey-cape files (capes.json and capes-owned.json) plus the PNG texture bytes in
 * the repo's capes/ folder. Mirrors the pattern used by
 * {@link com.deylauncher.friends.FriendsRepository}: one GET to read, one PUT to
 * write, GitHub's blob-sha required to overwrite, and automatic re-fetch +
 * re-apply + retry when two clients write at the same moment (409/422 conflict).
 *
 * Because both cape files and friends.json are written by concurrent launcher
 * instances sharing one repo token, the read-modify-write retry here is what
 * keeps one player's cape assignment from silently clobbering another's.
 */
public class CapesRepository {

    private final GitConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Minimal owner/repo/token holder, decoupled from the friends GitHubConfig so it
     * can be reused (e.g. on the mod's side) without importing friends code.
     */
    public record GitConfig(String token, String owner, String repo,
                            String capesPath, String ownershipPath, String capesDir) {
        public boolean isConfigured() {
            return token != null && !token.isBlank() && owner != null && !owner.isBlank()
                    && repo != null && !repo.isBlank();
        }
    }

    public CapesRepository(GitConfig config) {
        this.config = config;
    }

    private String contentsUrl(String path) {
        return "https://api.github.com/repos/" + config.owner + "/" + config.repo
                + "/contents/" + path;
    }

    private record Fetch<T>(T data, String sha, boolean exists) {}

    private Fetch<JsonObject> fetchJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 404) {
            return new Fetch<>(null, null, false);
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected Dey-cape read of " + path
                    + " (" + resp.statusCode() + "): " + resp.body());
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        String sha = json.get("sha").getAsString();
        String base64 = json.get("content").getAsString().replace("\n", "");
        String raw = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        JsonObject data = JsonParser.parseString(raw).getAsJsonObject();
        return new Fetch<>(data, sha, true);
    }

    private void putJson(String path, JsonObject data, String sha, String message) throws Exception {
        String content = Base64.getEncoder().encodeToString(
                gson.toJson(data).getBytes(StandardCharsets.UTF_8));
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", content);
        if (sha != null) body.addProperty("sha", sha);

        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409 || resp.statusCode() == 422) {
            throw new ConflictException();
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected Dey-cape write to " + path
                    + " (" + resp.statusCode() + "): " + resp.body());
        }
    }

    /** Reads + parses one JSON file. Returns null if it doesn't exist (or isn't valid JSON yet). */
    public <T> T read(String path, Class<T> type) throws Exception {
        Fetch<JsonObject> f = fetchJson(path);
        if (!f.exists() || f.data() == null) return null;
        try {
            return gson.fromJson(f.data(), type);
        } catch (Exception e) {
            return null;
        }
    }

    /** read-modify-write with retry for one JSON file. Returns the final value. */
    public <T> T sync(String path, Class<T> type, T defaultVal, String commitMessage,
                      UnaryOperator<T> mutator) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            Fetch<JsonObject> current = fetchJson(path);
            T data = defaultVal;
            if (current.exists() && current.data() != null) {
                try {
                    T parsed = gson.fromJson(current.data(), type);
                    if (parsed != null) data = parsed;
                } catch (Exception ignored) {
                    // start fresh from default if the file became unparseable
                }
            }
            T updated = mutator.apply(data);
            try {
                putJson(path, gson.toJsonTree(updated).getAsJsonObject(), current.sha(), commitMessage);
                return updated;
            } catch (ConflictException e) {
                if (attempts >= 5) {
                    throw new IllegalStateException("Couldn't save " + path + " after " + attempts
                            + " attempts -- too many people editing the Dey-cape file at once. Try again.");
                }
                Thread.sleep(300L * attempts);
            }
        }
    }

    /** Returns true if a file already exists at the given repo path (any type). Path must be JSON-less. */
    public boolean exists(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200;
    }

    /** Returns the raw bytes of any file at a repo path (e.g. a cape PNG). Throws if missing. */
    public byte[] readBytes(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected read of " + path
                    + " (" + resp.statusCode() + "): " + resp.body());
        }
        JsonObject env = JsonParser.parseString(resp.body()).getAsJsonObject();
        String base64 = env.get("content").getAsString().replace("\n", "");
        return Base64.getDecoder().decode(base64);
    }

    /** uploads or overwrites a file (text or bytes) at a repo path via the Contents API (base64). */
    public void putBytes(String path, byte[] content, String message) throws Exception {
        String sha = null;
        // Fetch current sha without decoding content (works for binary files too.
        HttpRequest peek = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> peekResp = http.send(peek, HttpResponse.BodyHandlers.ofString());
        if (peekResp.statusCode() == 200) {
            JsonObject env = JsonParser.parseString(peekResp.body()).getAsJsonObject();
            if (env.has("sha")) sha = env.get("sha").getAsString();
        }

        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", Base64.getEncoder().encodeToString(content));
        if (sha != null) body.addProperty("sha", sha);

        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl(path)))
                .header("Authorization", "Bearer " + (config.token == null ? "" : config.token))
                .header("Accept", "application/vnd.github+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409 || resp.statusCode() == 422) {
            throw new ConflictException(); // caller may retry
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected upload of " + path
                    + " (" + resp.statusCode() + "): " + resp.body());
        }
    }

    static class ConflictException extends Exception {}
}
