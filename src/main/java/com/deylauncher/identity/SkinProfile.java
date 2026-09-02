package com.deylauncher.identity;

/**
 * One entry in a player's local skin library (profiles/<uuid>/skins/index.json + a sibling
 * <id>.png). This sits alongside the existing single "active" skin.png contract in
 * IdentityStore/PlayerIdentity rather than replacing it -- selecting a profile just copies
 * its file into skin.png (and, for an online account with a live token, re-uploads it), so
 * every other piece of code that already reads skin.png (the old Account & Skin panel,
 * the game session, a future companion mod) keeps working unmodified.
 */
public class SkinProfile {
    public String id;
    public String name;
    public String fileName;
    public SkinModel model = SkinModel.CLASSIC;
    public long addedAt;

    public SkinProfile() {
        // no-arg constructor for Gson deserialization
    }

    public SkinProfile(String id, String name, String fileName, SkinModel model) {
        this.id = id;
        this.name = name;
        this.fileName = fileName;
        this.model = model;
        this.addedAt = System.currentTimeMillis();
    }
}
