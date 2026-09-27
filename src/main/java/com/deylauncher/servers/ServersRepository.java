package com.deylauncher.servers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Talks to GitHub's Contents API for ONE semi self-hosted servers repo.
 *
 * <p>Deliberately mirrors {@link com.deylauncher.friends.FriendsRepository} and
 * {@link com.deylauncher.deycapes.CapesRepository}: one GET to read, one PUT to write, GitHub's blob
 * {@code sha} required to overwrite, and a read-modify-write retry when two clients write at nearly the
 * same moment (409/422). That retry is what lets several PCs share one server without anyone's change
 * being silently dropped -- and it is also how "only one host at a time" is enforced, because the loser
 * of a race re-reads and sees the winner (see {@link ServerRuntimeDoc}).
 *
 * <p><b>One instance per repo.</b> Several servers repos may exist ({@code DeyLauncher-Servers},
 * {@code DeyLauncher-Servers1}, ...) and they all use the same bot token, so this class never guesses a
 * repo from configuration: callers pass the repo a server actually lives in (its
 * {@link HostedServer#repo}) and this object writes exactly there. {@link SemiHostedService} keeps one
 * instance per configured repo and routes each operation to the right one.
 *
 * <p>Unlike the friends/capes clients this one also needs byte reads/writes (the chunked world parts)
 * and deletes (removing a server's cloud data), so those live here rather than in a second near-duplicate
 * class.
 */
public class ServersRepository {

    /** Everything needed to talk to one servers repo; decoupled from friends' GitHubConfig on purpose. */
    public record Config(String token, String owner, String repo, String dir) {

        public boolean isConfigured() {
            return token != null && !token.isBlank() && owner != null && !owner.isBlank()
                    && repo != null && !repo.isBlank();
        }

        /** The repo-relative directory every servers file lives under (default {@code servers}). */
        public String dirOrDefault() {
            return (dir == null || dir.isBlank()) ? "servers" : dir.trim();
        }

        /** Joins this repo's servers directory with a server-relative path. */
        public String path(String relative) {
            String base = dirOrDefault();
            if (relative == null || relative.isBlank()) return base;
            String rel = relative.startsWith("/") ? relative.substring(1) : relative;
            return base + "/" + rel;
        }
    }

    /** A read result: the content (null when the file doesn't exist yet) and the blob sha to write with. */
    public record Snapshot(String text, String sha) {
        public boolean exists() {
            return sha != null;
        }
    }

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Cached answer to "may this token write here?", so the check costs one request per session. */
    private Boolean writeAccess;

    public ServersRepository(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** The repo name this instance writes to -- what {@link HostedServer#repo} records. */
    public String repoName() {
        return config.repo();
    }

    /**
     * The Contents API URL for a <b>server-relative</b> path. The configured servers directory
     * ({@code servers/}) is applied here, once, so no caller ever has to remember to prefix it -- and no
     * caller can accidentally apply it twice.
     */
    private String contentsUrl(String path) {
        return "https://api.github.com/repos/" + config.owner() + "/" + config.repo()
                + "/contents/" + config.path(path);
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + config.token())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    /** Raised when GitHub reports a write race, so callers can re-read and re-apply the change. */
    static class ConflictException extends Exception {
    }

    /** A failure already phrased for the user, so the UI can show {@code getMessage()} as-is. */
    public static class ServersRepoException extends RuntimeException {
        public ServersRepoException(String message) {
            super(message);
        }
    }

    /** Reads a text file. A missing file is NOT an error -- it comes back with a null sha. */
    public Snapshot read(String path) throws Exception {
        HttpResponse<String> resp = http.send(request(contentsUrl(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return new Snapshot(null, null);
        if (resp.statusCode() >= 400) throw failure("read " + path, resp);
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        String sha = json.has("sha") ? json.get("sha").getAsString() : null;
        String text = null;
        if (json.has("content") && !json.get("content").isJsonNull()) {
            String base64 = json.get("content").getAsString().replace("\n", "").replace("\r", "");
            text = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        }
        return new Snapshot(text, sha);
    }

    /** Writes a text file; {@code sha} must be the one just read, or GitHub rejects the overwrite. */
    public void write(String path, String text, String sha, String message) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content",
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        if (sha != null) body.addProperty("sha", sha);
        putJson(path, body);
    }

    /** Writes raw bytes (a world part). Binary files are stored as base64 by the Contents API. */
    public void writeBytes(String path, byte[] bytes, String sha, String message) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("content", Base64.getEncoder().encodeToString(bytes));
        if (sha != null) body.addProperty("sha", sha);
        putJson(path, body);
    }

    private void putJson(String path, JsonObject body) throws Exception {
        HttpResponse<String> resp = http.send(request(contentsUrl(path))
                        .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409 || resp.statusCode() == 422) throw new ConflictException();
        if (resp.statusCode() >= 400) throw failure("write " + path, resp);
    }

    /** Reads raw file bytes, or null when the file is missing. */
    public byte[] readBytes(String path) throws Exception {
        JsonObject json = fetchRaw(path);
        if (json == null || !json.has("content") || json.get("content").isJsonNull()) return null;
        return decode(json.get("content").getAsString());
    }

    /** Deletes a file, or does nothing when it is already gone. */
    public void delete(String path, String message) throws Exception {
        Snapshot snap = read(path);
        if (!snap.exists()) return;
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("sha", snap.sha());
        HttpResponse<String> resp = http.send(request(contentsUrl(path))
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString()))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 409 || resp.statusCode() == 422) throw new ConflictException();
        // 404 on delete means somebody else already removed it -- that is success, not failure.
        if (resp.statusCode() >= 400 && resp.statusCode() != 404) throw failure("delete " + path, resp);
    }

    /**
     * Applies {@code mutator} to the JSON document at {@code path}, creating it from {@code fresh} when it
     * doesn't exist yet, with the same read-modify-write-on-conflict loop the friends repo uses.
     *
     * <p>This is the ONLY way callers should change a shared document such as {@code runtime.json}: the
     * retry is what makes "two people pressed Start in the same second" resolve to exactly one winner,
     * because the loser's write is refused (its sha is stale), it re-reads, and it then sees the claim
     * that got there first.
     *
     * <p><b>Returning null from {@code mutator} aborts without writing.</b> That is how a losing host claim
     * or a heartbeat from a PC that no longer holds the server avoids touching the file at all -- writing
     * an unchanged document would only create pointless commits and conflicts for everyone else. On abort
     * the CURRENT (unmodified) document is returned, which is exactly what the caller needs in order to
     * report who holds the server instead.
     */
    public <T> T syncJson(String path, Class<T> type, Supplier<T> fresh, String message,
                          UnaryOperator<T> mutator) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            Snapshot snap = read(path);
            T current = snap.text() == null ? fresh.get() : gson.fromJson(snap.text(), type);
            if (current == null) current = fresh.get();
            T updated = mutator.apply(current);
            if (updated == null) return current; // the mutator decided nothing should change
            try {
                write(path, gson.toJson(updated), snap.sha(), message);
                return updated;
            } catch (ConflictException e) {
                if (attempts >= 5) {
                    throw new ServersRepoException("Couldn't save to " + repoName() + " after " + attempts
                            + " attempts -- another DeyLauncher edited this server at the same moment. "
                            + "Try again in a moment.");
                }
                Thread.sleep(350L * attempts); // small backoff, then re-read and re-apply
            }
        }
    }

    /** The file names directly inside a repo directory, or an empty list when the directory is absent. */
    public List<String> listNames(String path) throws Exception {
        List<String> names = new ArrayList<>();
        JsonObject json = fetchRaw(path);
        if (json == null) return names;
        JsonArray array = JsonParser.parseString(json.toString()).getAsJsonArray();
        for (JsonElement el : array) {
            if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                names.add(el.getAsJsonObject().get("name").getAsString());
            }
        }
        return names;
    }

    /** One GET returning the raw Contents API object, or null on 404. */
    private JsonObject fetchRaw(String path) throws Exception {
        HttpResponse<String> resp = http.send(request(contentsUrl(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() >= 400) throw failure("read " + path, resp);
        JsonElement parsed = JsonParser.parseString(resp.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64.replace("\n", "").replace("\r", ""));
    }

    /**
     * Whether this token may write to this repo (Contents: read and write).
     *
     * <p>GitHub reports the authenticated user's permissions on the repository object, so this needs no
     * write attempt: a mis-scoped token is caught once, up front, and reported as "this build cannot
     * publish servers" instead of surfacing later as a confusing 403 in the middle of a world upload.
     */
    public boolean hasWriteAccess() {
        if (writeAccess != null) return writeAccess;
        try {
            HttpResponse<String> resp = http.send(request(
                            "https://api.github.com/repos/" + config.owner() + "/" + config.repo())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                writeAccess = Boolean.FALSE;
                return false;
            }
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            boolean push = json.has("permissions")
                    && json.getAsJsonObject("permissions").has("push")
                    && json.getAsJsonObject("permissions").get("push").getAsBoolean();
            writeAccess = push;
            return push;
        } catch (Exception e) {
            // Offline or rate-limited: do not remember "no" for the whole session, but report false so a
            // caller can surface the failure instead of starting an upload that cannot finish.
            return false;
        }
    }

    /** Turns an HTTP failure into a message a user can act on, without ever echoing the token. */
    private ServersRepoException failure(String action, HttpResponse<String> resp) {
        int code = resp.statusCode();
        String hint;
        if (code == 401) {
            hint = "the GitHub token was rejected (401).";
        } else if (code == 403) {
            hint = "this token may not write to " + config.repo()
                    + " (403) -- it needs Contents: read and write on that repo.";
        } else if (code == 404) {
            hint = config.owner() + "/" + config.repo()
                    + " was not found (404) -- check the repo name, or the token's access to it.";
        } else if (code == 413) {
            hint = "the file is too large for GitHub (413). Lower the cloud size limit or expect a "
                    + "settings-only push.";
        } else {
            hint = "GitHub returned " + code + ".";
        }
        return new ServersRepoException("Couldn't " + action + " in " + config.owner() + "/"
                + config.repo() + ": " + hint);
    }
}
