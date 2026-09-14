package com.deylauncher.optionskits;

import com.deylauncher.friends.GitHubConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Talks to GitHub's Contents API to read/write the one shared options-kits.json file, the same way
 * FriendsRepository does for friends.json (same repo, same bot-account token -- see GitHubConfig).
 * A 409/422 on write means someone else saved a change first; sync() re-fetches, re-applies the
 * caller's mutation, and retries rather than ever silently dropping a kit.
 */
public class OptionsKitsRepository {

    /** Free accounts (offline, no real Microsoft login) get 1 saved kit; real accounts get 5. */
    public static final int OFFLINE_ACCOUNT_LIMIT = 1;
    public static final int ONLINE_ACCOUNT_LIMIT = 5;

    private final GitHubConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().create();

    public OptionsKitsRepository(GitHubConfig config) {
        this.config = config;
    }

    private String contentsUrl() {
        return "https://api.github.com/repos/" + config.owner + "/" + config.repo
                + "/contents/" + config.optionsKitsPath;
    }

    private record FetchResult(OptionsKitsData data, String sha) {}

    private FetchResult fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(contentsUrl()))
                .header("Authorization", "Bearer " + config.token)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 404) {
            return new FetchResult(new OptionsKitsData(), null); // file doesn't exist yet -- first save
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("GitHub rejected the options-kits read ("
                    + resp.statusCode() + "): " + resp.body());
        }
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        String sha = json.get("sha").getAsString();
        String base64 = json.get("content").getAsString().replace("\n", "");
        String raw = new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
        OptionsKitsData data = gson.fromJson(raw, OptionsKitsData.class);
        if (data == null) data = new OptionsKitsData();
        if (data.kitsByUuid == null) data.kitsByUuid = new java.util.HashMap<>();
        return new FetchResult(data, sha);
    }

    private void put(OptionsKitsData data, String sha, String message) throws Exception {
        String content = Base64.getEncoder().encodeToString(
                gson.toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            throw new IllegalStateException("GitHub rejected the options-kits write ("
                    + resp.statusCode() + "): " + resp.body());
        }
    }

    static class ConflictException extends Exception {}

    /** Just this account's kits -- used to populate the Option Kits list. */
    public List<OptionsKit> listFor(String uuid) throws Exception {
        return new ArrayList<>(fetch().data().forUuid(uuid));
    }

    /**
     * Read-modify-write with retry, scoped to one account's kit list: mutator receives THAT
     * account's current list (never null, possibly empty) and returns the new list to save.
     */
    public List<OptionsKit> sync(String uuid, String commitMessage,
                                  UnaryOperator<List<OptionsKit>> mutator) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            FetchResult current = fetch();
            List<OptionsKit> updatedList = mutator.apply(new ArrayList<>(current.data().forUuid(uuid)));
            current.data().kitsByUuid.put(uuid, updatedList);
            try {
                put(current.data(), current.sha(), commitMessage);
                return updatedList;
            } catch (ConflictException e) {
                if (attempts >= 5) {
                    throw new IllegalStateException("Couldn't save after " + attempts
                            + " attempts -- too many people editing option kits at once. Try again.");
                }
                Thread.sleep(300L * attempts);
            }
        }
    }
}
