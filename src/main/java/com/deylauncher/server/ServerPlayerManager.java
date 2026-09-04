package com.deylauncher.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Standard Minecraft JSON player-list files -- same format the vanilla server itself reads/writes. */
public class ServerPlayerManager {

    private final Path opsFile;
    private final Path whitelistFile;
    private final Path bannedFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public record PlayerEntry(String name, String uuid) {}

    public ServerPlayerManager(Path serverDir) {
        this.opsFile = serverDir.resolve("ops.json");
        this.whitelistFile = serverDir.resolve("whitelist.json");
        this.bannedFile = serverDir.resolve("banned-players.json");
    }

    public List<PlayerEntry> listOps() { return readNames(opsFile); }
    public List<PlayerEntry> listWhitelist() { return readNames(whitelistFile); }
    public List<PlayerEntry> listBanned() { return readNames(bannedFile); }

    /** Name-only add -- the server itself resolves the UUID and rewrites the file the next time it starts. Works even offline; this is the same as editing these files by hand. */
    public void addByName(Path file, String name) throws IOException {
        JsonArray arr = readArray(file);
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("uuid", "");
        arr.add(entry);
        Files.writeString(file, gson.toJson(arr));
    }

    public void removeByName(Path file, String name) throws IOException {
        JsonArray arr = readArray(file);
        JsonArray filtered = new JsonArray();
        for (var el : arr) {
            if (!el.getAsJsonObject().has("name") || !el.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(name)) {
                filtered.add(el);
            }
        }
        Files.writeString(file, gson.toJson(filtered));
    }

    public Path opsFile() { return opsFile; }
    public Path whitelistFile() { return whitelistFile; }
    public Path bannedFile() { return bannedFile; }

    private JsonArray readArray(Path file) {
        if (!Files.exists(file)) return new JsonArray();
        try {
            var parsed = JsonParser.parseString(Files.readString(file));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    private List<PlayerEntry> readNames(Path file) {
        List<PlayerEntry> out = new ArrayList<>();
        for (var el : readArray(file)) {
            var obj = el.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "?";
            String uuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "";
            out.add(new PlayerEntry(name, uuid));
        }
        return out;
    }
}
