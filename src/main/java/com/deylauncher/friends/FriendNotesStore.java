package com.deylauncher.friends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Personal notes the user writes about a friend, kept ONLY on their own machine (never uploaded to
 * the shared friends.json -- that file is readable by everyone). A simple {@code friend-notes.json}
 * map of friend-uuid -> note text, written best-effort on each save.
 */
public class FriendNotesStore {

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FriendNotesStore(Path launcherRoot) {
        this.file = launcherRoot.resolve("friend-notes.json");
    }

    /** The note for {@code friendUuid}, or an empty string if none was ever saved. */
    public synchronized String noteFor(String friendUuid) {
        return notes().getOrDefault(friendUuid, "");
    }

    /** Persists the note for {@code friendUuid} locally. Returns true on success (best-effort otherwise). */
    public synchronized boolean save(String friendUuid, String text) {
        Map<String, String> notes = notes();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            notes.remove(friendUuid);
        } else {
            notes.put(friendUuid, trimmed);
        }
        return persist(notes);
    }

    private Map<String, String> notes() {
        if (!Files.exists(file)) return new HashMap<String, String>();
        try {
            Map<String, String> loaded = gson.fromJson(Files.readString(file),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            return loaded != null ? loaded : new HashMap<String, String>();
        } catch (Exception e) {
            return new HashMap<String, String>();
        }
    }

    private boolean persist(Map<String, String> notes) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(notes));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
