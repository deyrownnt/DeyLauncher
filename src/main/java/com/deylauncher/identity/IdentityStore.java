package com.deylauncher.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk layout:
 *   ~/.deylauncher/accounts.json                 -- known accounts + which one is active
 *   ~/.deylauncher/profiles/<uuid>/profile.json   -- one account's identity + skin metadata
 *   ~/.deylauncher/profiles/<uuid>/skin.png       -- present only when skinSource != DEFAULT
 *
 * The profiles/<uuid>/ folder is deliberately the whole surface here -- it's
 * plain files, not hidden inside a database, specifically so a future
 * DeyLauncher Minecraft mod (or a sync mechanism) can read a player's skin
 * (and later cape) without going through this class or the launcher at all.
 */
public class IdentityStore {

    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public IdentityStore(Path launcherRoot) {
        this.root = launcherRoot;
    }

    private Path accountsIndexFile() {
        return root.resolve("accounts.json");
    }

    private Path profileDir(String uuid) {
        return root.resolve("profiles").resolve(uuid);
    }

    private Path profileFile(String uuid) {
        return profileDir(uuid).resolve("profile.json");
    }

    public Path skinFile(String uuid) {
        return profileDir(uuid).resolve("skin.png");
    }

    /** File behind one library tile. Kept here so the UI never reconstructs profile paths. */
    public Path skinProfileFile(String uuid, SkinProfile profile) {
        return skinsDir(uuid).resolve(profile.fileName);
    }

    /** Loads an existing identity, or creates a fresh DEFAULT-skin one if this uuid hasn't been seen before. */
    public PlayerIdentity loadOrCreate(String uuid, String username, AccountType accountType) {
        PlayerIdentity existing = load(uuid);
        if (existing != null) {
            // Username can change between sessions (offline: user retyped it; online: Mojang name
            // change) -- keep the record current, but never touch skin fields here.
            if (!existing.username.equals(username)) {
                existing.username = username;
                existing.lastUpdated = System.currentTimeMillis();
                save(existing);
            }
            registerInIndex(existing);
            return existing;
        }
        PlayerIdentity fresh = new PlayerIdentity(uuid, username, accountType);
        save(fresh);
        registerInIndex(fresh);
        return fresh;
    }

    public PlayerIdentity load(String uuid) {
        Path file = profileFile(uuid);
        if (!Files.exists(file)) return null;
        try {
            return gson.fromJson(Files.readString(file), PlayerIdentity.class);
        } catch (IOException e) {
            return null;
        }
    }

    public void save(PlayerIdentity identity) {
        try {
            Files.createDirectories(profileDir(identity.uuid));
            identity.lastUpdated = System.currentTimeMillis();
            Files.writeString(profileFile(identity.uuid), gson.toJson(identity));
            registerInIndex(identity);
        } catch (IOException ignored) {
            // Best-effort -- worst case the record just doesn't persist to next run.
        }
    }

    /** Copies a validated skin PNG into this account's profile folder and updates its metadata. */
    public void setOfflineCustomSkin(PlayerIdentity identity, Path sourcePng, SkinModel model) throws IOException {
        Files.createDirectories(profileDir(identity.uuid));
        Files.copy(sourcePng, skinFile(identity.uuid), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        identity.skinModel = model;
        identity.skinSource = SkinSource.OFFLINE_CUSTOM;
        save(identity);
    }

    /** Stores the skin that Minecraft Services says is currently equipped for a signed-in account. */
    public void setMojangOnlineSkin(PlayerIdentity identity, byte[] png, SkinModel model) throws IOException {
        if (png == null || png.length == 0) return;
        Files.createDirectories(profileDir(identity.uuid));
        Files.write(skinFile(identity.uuid), png);
        identity.skinModel = model == null ? SkinModel.CLASSIC : model;
        identity.skinSource = SkinSource.MOJANG_ONLINE;
        identity.activeSkinProfileId = null;
        save(identity);
    }

    public void removeCustomSkin(PlayerIdentity identity) throws IOException {
        Files.deleteIfExists(skinFile(identity.uuid));
        identity.skinSource = SkinSource.DEFAULT;
        identity.activeSkinProfileId = null;
        save(identity);
    }

    // ---- Skin profile library: profiles/<uuid>/skins/index.json + <id>.png ----
    // A separate library from the single "active" skin.png -- see SkinProfile's javadoc for why.

    private Path skinsDir(String uuid) {
        return profileDir(uuid).resolve("skins");
    }

    private Path skinsIndexFile(String uuid) {
        return skinsDir(uuid).resolve("index.json");
    }

    public List<SkinProfile> listSkinProfiles(String uuid) {
        Path file = skinsIndexFile(uuid);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            var arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            List<SkinProfile> out = new ArrayList<>();
            for (var el : arr) out.add(gson.fromJson(el, SkinProfile.class));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveSkinProfileIndex(String uuid, List<SkinProfile> profiles) {
        try {
            Files.createDirectories(skinsDir(uuid));
            Files.writeString(skinsIndexFile(uuid), gson.toJson(profiles));
        } catch (IOException ignored) {
        }
    }

    /**
     * Adds sourcePng as a new named entry in this account's skin library, then immediately
     * makes it the active skin (same as importing always has done) so the rest of the app
     * doesn't need two separate "import" concepts.
     */
    public SkinProfile addSkinProfile(PlayerIdentity identity, String displayName, Path sourcePng, SkinModel model) throws IOException {
        String id = java.util.UUID.randomUUID().toString();
        String fileName = id + ".png";
        Files.createDirectories(skinsDir(identity.uuid));
        Files.copy(sourcePng, skinsDir(identity.uuid).resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        SkinProfile profile = new SkinProfile(id, (displayName == null || displayName.isBlank())
                ? "Skin " + (listSkinProfiles(identity.uuid).size() + 1) : displayName, fileName, model);
        List<SkinProfile> profiles = listSkinProfiles(identity.uuid);
        profiles.add(profile);
        saveSkinProfileIndex(identity.uuid, profiles);

        activateSkinProfile(identity, profile);
        return profile;
    }

    /** Makes an already-imported library entry the active skin.png -- what selecting a profile in the Skins tab does. */
    public void selectSkinProfile(PlayerIdentity identity, String profileId) throws IOException {
        for (SkinProfile p : listSkinProfiles(identity.uuid)) {
            if (p.id.equals(profileId)) {
                activateSkinProfile(identity, p);
                return;
            }
        }
    }

    private void activateSkinProfile(PlayerIdentity identity, SkinProfile profile) throws IOException {
        Files.createDirectories(profileDir(identity.uuid));
        Files.copy(skinsDir(identity.uuid).resolve(profile.fileName), skinFile(identity.uuid),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        identity.skinModel = profile.model;
        identity.skinSource = SkinSource.OFFLINE_CUSTOM;
        identity.activeSkinProfileId = profile.id;
        save(identity);
    }

    // ---- accounts.json: the list of known accounts + which one is active ----

    public record AccountsIndex(String activeUuid, List<PlayerIdentity> accounts) {}

    public AccountsIndex loadIndex() {
        Path file = accountsIndexFile();
        if (!Files.exists(file)) return new AccountsIndex(null, new ArrayList<>());
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String active = obj.has("activeUuid") && !obj.get("activeUuid").isJsonNull()
                    ? obj.get("activeUuid").getAsString() : null;
            List<PlayerIdentity> accounts = new ArrayList<>();
            for (var el : obj.getAsJsonArray("accounts")) {
                accounts.add(gson.fromJson(el, PlayerIdentity.class));
            }
            return new AccountsIndex(active, accounts);
        } catch (Exception e) {
            return new AccountsIndex(null, new ArrayList<>());
        }
    }

    private void saveIndex(AccountsIndex index) {
        try {
            Files.createDirectories(root);
            JsonObject obj = new JsonObject();
            obj.addProperty("activeUuid", index.activeUuid());
            obj.add("accounts", gson.toJsonTree(index.accounts()));
            Files.writeString(accountsIndexFile(), gson.toJson(obj));
        } catch (IOException ignored) {
        }
    }

    private void registerInIndex(PlayerIdentity identity) {
        AccountsIndex index = loadIndex();
        List<PlayerIdentity> accounts = new ArrayList<>(index.accounts());
        accounts.removeIf(a -> a.uuid.equals(identity.uuid));
        accounts.add(identity);
        String active = index.activeUuid() != null ? index.activeUuid() : identity.uuid;
        saveIndex(new AccountsIndex(active, accounts));
    }

    public void setActive(String uuid) {
        AccountsIndex index = loadIndex();
        saveIndex(new AccountsIndex(uuid, index.accounts()));
    }

    public PlayerIdentity getActive() {
        AccountsIndex index = loadIndex();
        if (index.activeUuid() == null) return null;
        return load(index.activeUuid());
    }

    /**
     * Deactivates the current account without deleting anything: clears which account is
     * active (so the launcher returns to the "no account" state and getActive() -> null),
     * but leaves the account's saved profile, skin library, and every other account's data
     * on disk untouched -- signing back in (Microsoft) or recreating the same offline name
     * finds the same identity again. Live in-memory Microsoft tokens are a separate concern
     * held by LauncherApp, not this store; the caller clears those too.
     */
    public void logoutActive() {
        AccountsIndex index = loadIndex();
        saveIndex(new AccountsIndex(null, index.accounts()));
    }

    /**
     * Fully removes an account from the switch-account list (accounts.json only -- its saved
     * profile/skin library on disk is left alone, so signing back in with the same Microsoft
     * account finds the same skins again). Clears activeUuid too if this was the active account.
     * Used for Microsoft/online accounts specifically: unlike an offline "identity" (which is
     * just a locally chosen name with nothing to actually log out of), signing out of a real
     * Mojang session is expected to make it disappear from the switcher, not just deactivate it.
     */
    public void forgetAccount(String uuid) {
        AccountsIndex index = loadIndex();
        List<PlayerIdentity> accounts = new ArrayList<>(index.accounts());
        accounts.removeIf(a -> a.uuid.equals(uuid));
        String active = uuid.equals(index.activeUuid()) ? null : index.activeUuid();
        saveIndex(new AccountsIndex(active, accounts));
    }
}
