package com.deylauncher.server;

import java.util.UUID;

/**
 * One self-hosted server this DeyLauncher install owns/runs. Persisted by
 * ServerStore to ~/.deylauncher/servers/<id>/server.json, with the actual
 * server files (jar, world, server.properties, etc.) living alongside it
 * in that same folder.
 *
 * Permission fields live here (not in a separate file) because they're
 * small and always read together with the rest of the server's identity --
 * this is also where the future Multihosting toggle will live once that's
 * built, per the plan of keeping all "who else can do what with this
 * server" settings in one Permissions subtab.
 */
public class ServerInstance {
    public String id = UUID.randomUUID().toString();
    public String name;
    public ServerType type = ServerType.VANILLA;
    public String minecraftVersion;
    public int port = 25565;
    public int ramMinMb = 1024;
    public int ramMaxMb = 2048;
    public long createdAt = System.currentTimeMillis();
    public long lastJoinedAt = 0; // updated whenever this server is started or joined -- drives the "history" sort

    // ---- Settings subtab ----
    /** null = auto-pick the matching runtime for minecraftVersion (default); set to override with a specific installed runtime path. */
    public String javaOverridePath;

    // ---- Permissions subtab ----
    /** Publishes this server's address to friends (via presence) whenever it's running. */
    public boolean allowFriendsJoin = false;
    /** Whitelisted players may download a copy of the world/server to self-host later --
     *  matches the original "hand off hosting" design; not yet wired to real handoff logic. */
    public boolean allowPlayerSave = false;
    /** Reserved for the future Multihosting feature -- intentionally not implemented yet. */
    public boolean multihostingEnabled = false;

    public ServerInstance() {}

    public ServerInstance(String name, ServerType type, String minecraftVersion) {
        this.name = name;
        this.type = type;
        this.minecraftVersion = minecraftVersion;
    }
}
