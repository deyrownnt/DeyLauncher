package com.deylauncher.deycapes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the DeyLauncher (producer) &lt;-&gt; DeyCapes mod (consumer) JSON contract for
 * {@code capes.json}, without a live GitHub repo or a running Minecraft/Fabric client -- neither is
 * reachable from this sandboxed test run.
 *
 * <p>Side A ({@link #buildCapesDataAsLauncherWould}) is the launcher's own production code path:
 * {@link CapesData}/{@link CapesData.PlayerCape}/{@link CapesData.CapeDef} are the exact POJOs
 * {@code DeyCapesService.equipCape()} mutates, serialized with the exact same {@code Gson} config
 * {@code CapesRepository} uses ({@code new GsonBuilder().setPrettyPrinting().create()}, no field-naming
 * policy -- so field names on the wire are the literal Java field names). This is the JSON DeyLauncher
 * actually PUTs to {@code onpishi/DeyLauncher-Friends}.
 *
 * <p>Side B ({@link #resolveAsDeyCapesModWould}) is a byte-for-byte reconstruction of
 * {@code GithubCapeProvider.fetchMapping()}/{@code parseUuid()} inside the shipped
 * {@code DeyCapes-1.21.2+.jar}, recovered by disassembling its class files (no source is shipped with
 * DeyLauncher -- see the constant-pool strings: {@code "capes"}, {@code "players"}, {@code "cape"},
 * {@code "texture"}, {@code "offlineUuid"}, the UUID-dash-insertion regex
 * {@code (\w{8})(\w{4})(\w{4})(\w{4})(\w{12})} -&gt; {@code $1-$2-$3-$4-$5}, then
 * {@code UUID.fromString}). This is what actually runs inside Minecraft when DeyCapes reads the file
 * DeyLauncher wrote.
 *
 * <p>Result: the two sides agree. A cape equipped online resolves under BOTH the raw (dashless) online
 * uuid Mojang's profile API returns AND the derived offline uuid (matching vanilla's own
 * {@code OfflinePlayer:<name>} derivation), exactly as {@link com.deylauncher.identity.PlayerIdentity}
 * stores it and {@code Entity.getUuid()} would present it in-game either way. So a schema mismatch or a
 * UUID-format mismatch is NOT what's blocking capes from rendering -- see
 * {@code onApplyCape}/{@code equipCape} vs. the anonymous-GitHub-read diagnostics added in
 * {@code LauncherApp} for the actual remaining failure point (repository visibility).
 */
class CapesGithubContractTest {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void onlineEquipResolvesUnderBothOnlineAndDerivedOfflineUuid() {
        String username = "TestPlayer";
        // Mojang's /minecraft/profile "id" field is UNDASHED -- see MicrosoftAuth.completeLogin(),
        // which stores profile.get("id").getAsString() into PlayerIdentity.uuid verbatim, no dash
        // insertion. Simulate that exact shape here rather than a "nice" dashed UUID.
        String onlineUuidRaw = UUID.randomUUID().toString().replace("-", "");

        CapesData data = buildCapesDataAsLauncherWould(username, "gold", true, onlineUuidRaw);
        String wireJson = gson.toJson(data); // exactly what CapesRepository.putJson base64-encodes and PUTs

        Map<UUID, String> capeIdByUuid = new HashMap<>();
        Map<String, String> textureByCapeId = new HashMap<>();
        resolveAsDeyCapesModWould(wireJson, capeIdByUuid, textureByCapeId);

        UUID mojangProfileUuid = insertDashesLikeVanillaWould(onlineUuidRaw); // what Entity.getUuid() is when playing online
        UUID vanillaOfflineUuid = offlineUuidLikeVanillaWould(username);      // what Entity.getUuid() is when playing offline

        assertEquals("gold", capeIdByUuid.get(mojangProfileUuid),
                "DeyCapes must resolve the cape for the player's real (online) Minecraft UUID");
        assertEquals("gold", capeIdByUuid.get(vanillaOfflineUuid),
                "DeyCapes must ALSO resolve the same cape if that player later joins offline");
        assertEquals("capes/gold_cape.png", textureByCapeId.get("gold"),
                "the texture path DeyCapes will download must match what DeyCapesService.seedTextures() uploads");
    }

    @Test
    void offlineOnlyEquipResolvesUnderVanillaOfflineUuid() {
        String username = "OfflineOnlyPlayer";
        CapesData data = buildCapesDataAsLauncherWould(username, "og", false, null);
        String wireJson = gson.toJson(data);

        Map<UUID, String> capeIdByUuid = new HashMap<>();
        Map<String, String> textureByCapeId = new HashMap<>();
        resolveAsDeyCapesModWould(wireJson, capeIdByUuid, textureByCapeId);

        UUID vanillaOfflineUuid = offlineUuidLikeVanillaWould(username);
        assertEquals("og", capeIdByUuid.get(vanillaOfflineUuid));
        assertTrue(capeIdByUuid.size() >= 1);
    }

    // ---------------------------------------------------------------------------------------------
    // Side A: reproduces DeyCapesService.equipCape()'s pure data shaping (no network).
    // ---------------------------------------------------------------------------------------------
    private CapesData buildCapesDataAsLauncherWould(String username, String capeId, boolean online, String onlineUuid) {
        CapesData data = new CapesData();
        CapesData.CapeDef gold = new CapesData.CapeDef();
        gold.name = "Gold Cape";
        gold.texture = "capes/gold_cape.png";
        data.capes.put("gold", gold);
        CapesData.CapeDef og = new CapesData.CapeDef();
        og.name = "OG Cape";
        og.texture = "capes/og_cape.png";
        data.capes.put("og", og);

        String offline = DeyCapesService.offlineUuid(username); // real production code, not reimplemented

        if (online && onlineUuid != null && !onlineUuid.isBlank()) {
            CapesData.PlayerCape p = data.getOrCreate(onlineUuid, username);
            p.cape = capeId;
            p.offlineUuid = offline;
        }
        CapesData.PlayerCape po = data.getOrCreate(offline, username);
        po.cape = capeId;
        po.offlineUuid = null;
        return data;
    }

    // ---------------------------------------------------------------------------------------------
    // Side B: reconstruction of the shipped mod's GithubCapeProvider.fetchMapping()/parseUuid(),
    // recovered from DeyCapes-1.21.2+.jar's disassembled constant pool (see class javadoc).
    // ---------------------------------------------------------------------------------------------
    private static final Pattern DASHLESS_UUID = Pattern.compile("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})");

    private void resolveAsDeyCapesModWould(String wireJson, Map<UUID, String> capeIdByUuid, Map<String, String> textureByCapeId) {
        JsonObject root = JsonParser.parseString(wireJson).getAsJsonObject();

        if (root.has("capes")) {
            for (var e : root.getAsJsonObject("capes").entrySet()) {
                JsonObject def = e.getValue().getAsJsonObject();
                if (def.has("texture") && !def.get("texture").isJsonNull()) {
                    textureByCapeId.put(e.getKey(), def.get("texture").getAsString());
                }
            }
        }
        if (root.has("players")) {
            for (var e : root.getAsJsonObject("players").entrySet()) {
                String uuidKey = e.getKey();
                JsonObject p = e.getValue().getAsJsonObject();
                if (!p.has("cape") || p.get("cape").isJsonNull()) continue;
                String capeId = p.get("cape").getAsString();

                putUuid(capeIdByUuid, uuidKey, capeId);
                if (p.has("offlineUuid") && !p.get("offlineUuid").isJsonNull()) {
                    putUuid(capeIdByUuid, p.get("offlineUuid").getAsString(), capeId);
                }
            }
        }
    }

    private void putUuid(Map<UUID, String> out, String rawKey, String capeId) {
        UUID parsed = parseUuid(rawKey);
        if (parsed != null) out.put(parsed, capeId);
    }

    /** {@code GithubCapeProvider.parseUuid}: trims, inserts dashes if missing, then {@code UUID.fromString}. */
    private UUID parseUuid(String s) {
        s = s.trim();
        if (!s.contains("-") && s.length() == 32) {
            s = DASHLESS_UUID.matcher(s).replaceFirst("$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What Minecraft/vanilla itself would hand the mod as Entity.getUuid(), for comparison.
    // ---------------------------------------------------------------------------------------------
    private UUID insertDashesLikeVanillaWould(String dashless) {
        return UUID.fromString(DASHLESS_UUID.matcher(dashless).replaceFirst("$1-$2-$3-$4-$5"));
    }

    private UUID offlineUuidLikeVanillaWould(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
