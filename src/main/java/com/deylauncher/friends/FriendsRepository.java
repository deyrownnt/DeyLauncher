package com.deylauncher.friends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.function.UnaryOperator;

/**
 * Talks to GitHub's Contents API (repos.contents) to read/write the one
 * shared friends.json file. GitHub requires the current file's blob "sha"
 * to overwrite it -- if two people write at nearly the same moment, the
 * second write gets a 409/422 conflict. sync() handles that by re-fetching,
 * re-applying the caller's change, and retrying a few times rather than
 * ever silently dropping someone's update.
 */
public class FriendsRepository {

    private final GitHubConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().create();

    public FriendsRepository(GitHubConfig config) {
        this.config = config;
    }

    private String contentsUrl() {
        return "https://api.github.com/repos/" + config.owner + "/" + config.repo
                + "/contents/" + config.friendsPath;
    }

    private record FetchResult(FriendsData data, String sha) {}

    private FetchResult fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl()))
                .header("Authorization", "Bearer " + config.token)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 404) {
            return new FetchResult(new FriendsData(), null); // file doesn't exist yet -- first run
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected the friends-list read ("
                    + resp.statusCode() + "): " + resp.body());
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        String sha = json.get("sha").getAsString();
        String base64 = json.get("content").getAsString().replace("\n", "");
        String raw = new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
        FriendsData data = gson.fromJson(raw, FriendsData.class);
        if (data == null) data = new FriendsData();
        if (data.users == null) data.users = new java.util.HashMap<>();
        return new FetchResult(data, sha);
    }

    private void put(FriendsData data, String sha, String message) throws Exception {
        String content = Base64.getEncoder().encodeToString(gson.toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", content);
        if (sha != null) body.addProperty("sha", sha);

        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl()))
                .header("Authorization", "Bearer " + config.token)
                .header("Accept", "application/vnd.github+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409 || resp.statusCode() == 422) {
            throw new ConflictException();
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected the friends-list write ("
                    + resp.statusCode() + "): " + resp.body());
        }
    }

    static class ConflictException extends Exception {}

    /** Just reads the current shared state -- used for refreshing the friends list UI. */
    public FriendsData read() throws Exception {
        return fetch().data();
    }

    /**
     * Read-modify-write with retry: mutator receives the current data, changes it in place (or
     * returns a new instance), and this handles re-fetching + reapplying on a 409 conflict.
     */
    public FriendsData sync(String commitMessage, UnaryOperator<FriendsData> mutator) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            FetchResult current = fetch();
            FriendsData updated = mutator.apply(current.data());
            try {
                put(updated, current.sha(), commitMessage);
                return updated;
            } catch (ConflictException e) {
                if (attempts >= 5) {
                    throw new IllegalStateException("Couldn't save after " + attempts
                            + " attempts -- too many people editing the friends list at once. Try again.");
                }
                Thread.sleep(300L * attempts); // small backoff before retrying
            }
        }
    }
}
