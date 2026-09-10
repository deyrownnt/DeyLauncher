package com.deylauncher.deycapes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The separate, human-auditable "who owns which Dey cape" file (default
 * {@code capes-owned.json}) in the same repo. This is independent from the live
 * equipped map in {@code capes.json}: a player can own capes without having any
 * of them currently equipped, and the launcher's Skins tab only shows the Dey
 * capes this file says a player owns.
 *
 * Keys are the player's DeyLauncher username (and, when known, their uuid too so
 * ownership survives a name change). Values are the list of cape ids they own.
 */
public class OwnershipData {
    public int version = 1;

    /** playerKey (username or uuid) -> cape ids they own. */
    public Map<String, List<String>> ownership = new HashMap<>();

    /** Adds a cape id to a player's owned list if not already present. */
    public void grant(String playerKey, String capeId) {
        List<String> owned = ownership.computeIfAbsent(playerKey, k -> new ArrayList<>());
        if (!owned.contains(capeId)) owned.add(capeId);
    }

    /** True if playerKey (or any of its alias forms) owns the given cape id. */
    public boolean owns(String playerKey, String capeId) {
        List<String> owned = ownership.get(playerKey);
        return owned != null && owned.contains(capeId);
    }
}