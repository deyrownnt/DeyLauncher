package com.deylauncher.friends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FriendsCache {

    private final Path file;
    private final Gson gson = new GsonBuilder().create();

    public FriendsCache(Path launcherRoot) {
        this.file = launcherRoot.resolve("friends-cache.json");
    }

    public void save(FriendsService.FriendsView view) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(view));
        } catch (IOException ignored) {
            // Best-effort -- worst case the next open just has no instant cache to show.
        }
    }

    public FriendsService.FriendsView load() {
        if (!Files.exists(file)) return null;
        try {
            return gson.fromJson(Files.readString(file), FriendsService.FriendsView.class);
        } catch (Exception e) {
            return null;
        }
    }
}
