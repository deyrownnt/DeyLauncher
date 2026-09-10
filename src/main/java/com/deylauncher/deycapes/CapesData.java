package com.deylauncher.deycapes;

import java.util.HashMap;
import java.util.Map;

/**
 * The live, shared "equipped Dey cape" file (default {@code capes.json}) in the
 * same GitHub repo DeyLauncher already uses for friends. It does two jobs:
 *
 * <ul>
 *   <li><b>Rendering (read by the DeyCapes Minecraft mod):</b> {@code players}
 *       maps a player uuid -- the online uuid when signed in online, the derived
 *       offline uuid when not -- to the cape they currently have equipped. The
 *       mod looks up the entity's uuid in this map to decide whose cape to draw.</li>
 *   <li><b>Catalog (read by the launcher's Skins tab):</b> {@code capes}
 *       describes every known Dey cape (display name + path of its PNG in the
 *       repo's {@code capes/} folder).</li>
 * </ul>
 *
 * This is deliberately the same shape of file the {@code friends} feature uses
 * (one shared JSON in a private repo), so it inherits the same tradeoffs and
 * the same token model -- see GITHUB_SETUP.md in the repo root.
 */
public class CapesData {
    public int version = 1;

    /** Cape id (slug, e.g. "gold") -> its display metadata. */
    public Map<String, CapeDef> capes = new HashMap<>();

    /** Player uuid (online or offline) -> what cape they currently have equipped. */
    public Map<String, PlayerCape> players = new HashMap<>();

    public static class CapeDef {
        public String name;
        /** Path of the cape PNG inside the repo, e.g. "capes/gold_cape.png". */
        public String texture;
    }

    public static class PlayerCape {
        /** The player's DeyLauncher / in-game username (for humans reading the file). */
        public String username;
        /** Cape id (slug) currently equipped, or null for "no Dey cape". */
        public String cape;
        /**
         * When this entry's key is the player's ONLINE uuid, this records their derived
         * offline uuid ("OfflinePlayer:<name>" hash) so that if that same person ever
         * plays without a Microsoft session, the offline uuid they present still resolves
         * to the same cape. Empty/null when the key is already an offline uuid.
         */
        public String offlineUuid;
    }

    /** Returns the entry for a uuid, creating it (with the given username) if missing. */
    public PlayerCape getOrCreate(String uuid, String username) {
        PlayerCape p = players.get(uuid);
        if (p == null) {
            p = new PlayerCape();
            p.username = username;
            players.put(uuid, p);
        } else if (!p.username.equals(username)) {
            p.username = username;
        }
        return p;
    }
}