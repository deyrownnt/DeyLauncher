package com.deylauncher.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AddedServersStore {

    public static class AddedServer {
        public String id;
        public String name;
        public String address;
        public long lastJoinedAt = 0;

        public AddedServer() {}

        public static AddedServer create(String name, String address) {
            AddedServer s = new AddedServer();
            s.id = UUID.randomUUID().toString();
            s.name = name;
            s.address = address;
            return s;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String address() { return address; }
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public AddedServersStore(Path launcherRoot) {
        this.file = launcherRoot.resolve("added-servers.json");
    }

    public List<AddedServer> list() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            AddedServer[] arr = gson.fromJson(Files.readString(file), AddedServer[].class);
            var out = new ArrayList<AddedServer>();
            if (arr != null) out.addAll(java.util.Arrays.asList(arr));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void add(String name, String address) {
        var current = list();
        current.add(AddedServer.create(name, address));
        save(current);
    }

    public void remove(String id) {
        var current = list();
        current.removeIf(s -> s.id().equals(id));
        save(current);
    }

    public void touchLastJoined(String id) {
        var current = list();
        for (var s : current) {
            if (s.id().equals(id)) s.lastJoinedAt = System.currentTimeMillis();
        }
        save(current);
    }

    private void save(List<AddedServer> servers) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(servers));
        } catch (IOException ignored) {
        }
    }
}
