package com.deylauncher.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ServerStore {

    private final Path root; // ~/.deylauncher/servers
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ServerStore(Path launcherRoot) {
        this.root = launcherRoot.resolve("servers");
    }

    public Path serverDir(String id) {
        return root.resolve(id);
    }

    private Path metaFile(String id) {
        return serverDir(id).resolve("server.json");
    }

    public List<ServerInstance> listAll() {
        List<ServerInstance> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                Path meta = dir.resolve("server.json");
                if (!Files.exists(meta)) continue;
                try {
                    ServerInstance instance = gson.fromJson(Files.readString(meta), ServerInstance.class);
                    if (instance != null) out.add(instance);
                } catch (Exception ignored) {
                    // Skip a corrupt entry rather than fail the whole list.
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong((ServerInstance s) -> s.createdAt).reversed());
        return out;
    }

    public ServerInstance load(String id) {
        Path meta = metaFile(id);
        if (!Files.exists(meta)) return null;
        try {
            return gson.fromJson(Files.readString(meta), ServerInstance.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(ServerInstance instance) {
        try {
            Files.createDirectories(serverDir(instance.id));
            Files.writeString(metaFile(instance.id), gson.toJson(instance));
        } catch (IOException ignored) {
            // Best-effort -- worst case this server's changes don't persist to next run.
        }
    }

    public void delete(String id) throws IOException {
        Path dir = serverDir(id);
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
