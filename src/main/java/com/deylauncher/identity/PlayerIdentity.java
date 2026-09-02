package com.deylauncher.identity;

/**
 * One account's persistent identity + skin metadata. Not tied to a single
 * play session -- AuthSession (auth package) is the short-lived thing
 * GameLauncher actually uses to launch; PlayerIdentity is the durable
 * record IdentityStore keeps on disk across runs, keyed by uuid.
 *
 * Deliberately PlayerIdentity -> Skin only for now. A future DeyCape mod
 * can extend this to PlayerIdentity -> Skin + Cape by adding a capeSource
 * field here and a sibling cape.png next to skin.png in the same
 * profiles/<uuid>/ folder -- nothing about this shape has to change to
 * make room for that, it's just not built yet per the current phase scope.
 */
public class PlayerIdentity {
    public String uuid;
    public String username;
    public AccountType accountType;
    public SkinModel skinModel = SkinModel.CLASSIC;
    public SkinSource skinSource = SkinSource.DEFAULT;
    /** Which entry in this account's skin library (see SkinProfile) is currently active, if any. Null for the online/default skin or a skin set before this field existed. */
    public String activeSkinProfileId;
    /** AES-GCM ciphertext only; the per-user key lives outside the launcher profile files. */
    public String refreshTokenCiphertext;
    public long lastUpdated;

    public PlayerIdentity() {
        // no-arg constructor for Gson deserialization
    }

    public PlayerIdentity(String uuid, String username, AccountType accountType) {
        this.uuid = uuid;
        this.username = username;
        this.accountType = accountType;
        this.lastUpdated = System.currentTimeMillis();
    }
}
