package com.deylauncher.deycapes;

import com.deylauncher.friends.GitHubConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * High-level operations the launcher's Skins tab needs for Dey capes, all backed
 * by the shared GitHub repo (see {@link CapesRepository}). Everything here is
 * deliberately additive to the existing Mojang-capes flow: Dey capes are a
 * DeyLauncher/DeyCapes-mod-only feature and never touch the player's real Mojang
 * account.
 */
public class DeyCapesService {

    private final GitHubConfig config;
    private final CapesRepository repo;

    /** Canonical catalog of the Dey capes shipped with the launcher (textures in the repo's capes/ dir). */
    private static final Map<String, String[]> DEFAULT_CAPES;
    static {
        DEFAULT_CAPES = new LinkedHashMap<>();
        DEFAULT_CAPES.put("dev", new String[]{"Dev Cape", "capes/dev_cape.png"});
        DEFAULT_CAPES.put("gold", new String[]{"Gold Cape", "capes/gold_cape.png"});
        DEFAULT_CAPES.put("og", new String[]{"OG Cape", "capes/og_cape.png"});
        DEFAULT_CAPES.put("premium", new String[]{"Premium Cape", "capes/premium_cape.png"});
        DEFAULT_CAPES.put("tester", new String[]{"Tester Cape", "capes/tester_cape.png"});
    }

    public DeyCapesService(GitHubConfig config) {
        this.config = config;
        this.repo = new CapesRepository(new CapesRepository.GitConfig(
                config.token, config.owner, config.repo,
                config.capesPath, config.capesOwnedPath, config.capesDir));
    }

    /** The GitHub repo is only usable if the friends-style credentials are configured. */
    public boolean configured() {
        return config.isConfigured();
    }

    public CapesRepository.GitConfig gitConfig() {
        return new CapesRepository.GitConfig(config.token, config.owner, config.repo,
                config.capesPath, config.capesOwnedPath, config.capesDir);
    }

    /** Mojang's own offline-uuid derivation, used to link the online and offline identities of one player. */
    public static String offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** A cape the player can see in the Skins tab (they own it, per the repo's ownership file). */
    public record DeyCape(String id, String name, String texturePath) {}

    /** Ensures capes.json exists and is seeded with the default catalog (no-op if it already exists). */
    public CapesData ensureCatalog() throws Exception {
        return repo.sync(config.capesPath, CapesData.class, new CapesData(), "DeyCapes: ensure catalog", data -> {
            if (data.version == 0) data.version = 1;
            for (var e : DEFAULT_CAPES.entrySet()) {
                if (!data.capes.containsKey(e.getKey())) {
                    CapesData.CapeDef def = new CapesData.CapeDef();
                    def.name = e.getValue()[0];
                    def.texture = e.getValue()[1];
                    data.capes.put(e.getKey(), def);
                }
            }
            return data;
        });
    }

    /**
     * All known Dey capes (from the catalog). The Skins tab calls {@link #ownedCapes}
     * to decide which of these a given player actually owns.
     */
    public List<DeyCape> allCapes() throws Exception {
        CapesData data = ensureCatalog();
        List<DeyCape> out = new ArrayList<>();
        for (Map.Entry<String, CapesData.CapeDef> e : data.capes.entrySet()) {
            out.add(new DeyCape(e.getKey(), e.getValue().name, e.getValue().texture));
        }
        return out;
    }

    /**
     * The ids of the capes the given DeyLauncher login owns, per the repo's
     * capes-owned.json. Association is by DeyLauncher username; the online/offline
     * uuids are also considered so ownership survives a name change.
     */
    public List<String> ownedCapeIds(String username, String onlineUuid, String offlineUuid) throws Exception {
        OwnershipData ownership = repo.read(config.capesOwnedPath, OwnershipData.class);
        if (ownership == null || ownership.ownership == null) return new ArrayList<>();
        java.util.LinkedHashSet<String> owned = new java.util.LinkedHashSet<>();
        addOwnedFor(owned, ownership, username);
        if (onlineUuid != null) addOwnedFor(owned, ownership, onlineUuid);
        if (offlineUuid != null) addOwnedFor(owned, ownership, offlineUuid);
        // Only keep ids that actually exist in the catalog.
        java.util.Set<String> known = new java.util.HashSet<>(allCapes().stream()
                .map(DeyCape::id).toList());
        owned.removeIf(id -> !known.contains(id));
        return new ArrayList<>(owned);
    }

    private void addOwnedFor(java.util.Set<String> out, OwnershipData ownership, String key) {
        List<String> owned = ownership.ownership.get(key);
        if (owned != null) out.addAll(owned);
    }

    /**
     * Equips a Dey cape for the active player and writes it to the github file.
     *
     * <ul>
     *   <li><b>Online:</b> associates the cape with BOTH the online uuid and the
     *       derived offline uuid (so the same player sees it whether their game
     *       session presents the online or offline uuid).</li>
     *   <li><b>Offline:</b> associates the cape with the offline uuid only.</li>
     * </ul>
     *
     * Also records ownership for this player in capes-owned.json so the cape then
     * stays visible to them in the Skins tab.
     */
    public CapesData equipCape(String username, String capeId, boolean online, String onlineUuid) throws Exception {
        final String offline = offlineUuid(username);
        CapesData data = repo.sync(config.capesPath, CapesData.class, new CapesData(),
                "DeyCapes: equip " + capeId + " for " + username, d -> {
                    for (var e : DEFAULT_CAPES.entrySet()) {
                        if (!d.capes.containsKey(e.getKey())) {
                            CapesData.CapeDef def = new CapesData.CapeDef();
                            def.name = e.getValue()[0];
                            def.texture = e.getValue()[1];
                            d.capes.put(e.getKey(), def);
                        }
                    }
                    if (online && onlineUuid != null && !onlineUuid.isBlank()) {
                        CapesData.PlayerCape p = d.getOrCreate(onlineUuid, username);
                        p.cape = capeId;
                        p.offlineUuid = offline;
                    }
                    CapesData.PlayerCape po = d.getOrCreate(offline, username);
                    po.cape = capeId;
                    po.offlineUuid = null;
                    return d;
                });
        // Persist ownership so the cape shows up (and stays) for this player.
        repo.sync(config.capesOwnedPath, OwnershipData.class, new OwnershipData(),
                "DeyCapes: record ownership", o -> { o.grant(username, capeId); if (onlineUuid != null) o.grant(onlineUuid, capeId); return o; });
        return data;
    }

    public String capesDir() {
        return config.capesDir;
    }

    /** Fetches a cape texture's raw PNG bytes from the repo by its catalog texture path (e.g. "capes/gold_cape.png"). */
    public java.io.ByteArrayInputStream readCapeTexture(String texturePath) throws Exception {
        return new java.io.ByteArrayInputStream(repo.readBytes(texturePath));
    }

    /**
     * Ensures the repo's capes/ folder contains every bundled cape texture. Reads
     * the PNGs shipped inside this launcher jar (see resources/deycapes-textures/)
     * and uploads any that are missing from the repo, so a fresh repo is seeded by
     * the launcher itself. Retries once per file on a concurrent-write conflict.
     */
    public void seedTextures() throws Exception {
        for (Map.Entry<String, String[]> e : DEFAULT_CAPES.entrySet()) {
            String texturePath = e.getValue()[1]; // e.g. "capes/dev_cape.png"
            String resourceName = "/deycapes-textures/" + texturePath.substring(texturePath.lastIndexOf('/') + 1);
            byte[] png;
            try (var in = DeyCapesService.class.getResourceAsStream(resourceName)) {
                if (in == null) continue;
                png = in.readAllBytes();
            }
            String repoPath = config.capesDir + "/" + resourceName.substring(resourceName.lastIndexOf('/') + 1);
            try {
                if (repo.exists(repoPath)) continue; // already seeded
                repo.putBytes(repoPath, png, "DeyCapes: seed texture " + repoPath);
            } catch (Exception conflict) {
                // already uploaded by someone else meanwhile -- fine
            }
        }
    }
}
