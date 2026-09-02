package com.deylauncher.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Changes an ONLINE account's real Minecraft skin via Mojang's actual
 * Minecraft Services API -- this is the officially documented endpoint
 * (POST /minecraft/profile/skins, multipart/form-data with "variant" and
 * "file"), not a reverse-engineered or simulated call. It requires a real
 * Minecraft access token from MicrosoftAuth's login chain, which in turn
 * requires DeyLauncher's Azure app to be approved
 * (https://aka.ms/mce-reviewappid, see MicrosoftAuth.java).
 *
 * Until that approval lands, calls here will fail the same way the rest of
 * MicrosoftAuth currently does -- with a clear 403 "Invalid app
 * registration" surfaced back to the caller, never a silent no-op and
 * never a pretend success. Nothing here fakes an online skin change.
 */
public class MinecraftSkinService {

    private static final String SKINS_URL = "https://api.minecraftservices.com/minecraft/profile/skins";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String CAPES_ACTIVE_URL = "https://api.minecraftservices.com/minecraft/profile/capes/active";

    private final HttpClient http = HttpClient.newHttpClient();

    public record SkinChangeResult(boolean success, String message) {}

    /**
     * A cape the account genuinely owns, as reported by Mojang's own profile endpoint --
     * never invented locally. "active" reflects whichever cape (if any) Mojang currently has
     * equipped for real, independent of anything shown in this UI.
     */
    public record CapeInfo(String id, String alias, String url, boolean active) {}

    public record CapesResult(boolean success, String message, java.util.List<CapeInfo> capes) {
        public static CapesResult failure(String message) {
            return new CapesResult(false, message, java.util.List.of());
        }
    }

    /** Reads the account's real owned capes from GET /minecraft/profile. Requires a live Minecraft access token. */
    public CapesResult fetchCapes(String accessToken) {
        if (accessToken == null || accessToken.equals("0")) {
            return CapesResult.failure("This is an offline account -- capes require a real Microsoft sign-in.");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(PROFILE_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 403 && resp.body() != null && resp.body().contains("Invalid app registration")) {
                return CapesResult.failure("Can't read capes yet -- DeyLauncher's Microsoft app is still "
                        + "awaiting approval (https://aka.ms/mce-reviewappid).");
            }
            if (resp.statusCode() != 200) {
                return CapesResult.failure("Minecraft Services rejected the request (" + resp.statusCode() + ").");
            }
            var json = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
            java.util.List<CapeInfo> capes = new java.util.ArrayList<>();
            if (json.has("capes")) {
                for (var el : json.getAsJsonArray("capes")) {
                    var c = el.getAsJsonObject();
                    String state = c.has("state") ? c.get("state").getAsString() : "INACTIVE";
                    capes.add(new CapeInfo(
                            c.get("id").getAsString(),
                            c.has("alias") ? c.get("alias").getAsString() : "Cape",
                            c.has("url") ? c.get("url").getAsString() : null,
                            "ACTIVE".equalsIgnoreCase(state)));
                }
            }
            return new CapesResult(true, capes.isEmpty()
                    ? "This account doesn't own any capes." : "Loaded from your real Minecraft profile.", capes);
        } catch (Exception e) {
            return CapesResult.failure("Couldn't reach Minecraft Services: " + e.getMessage());
        }
    }

    /**
     * Equips one of the account's already-owned capes via the real, documented
     * PUT /minecraft/profile/capes/active endpoint. This can only select among capes the
     * account actually owns (see fetchCapes) -- there is no Mojang API to grant a new cape,
     * and this method never pretends otherwise.
     */
    public SkinChangeResult equipCape(String accessToken, String capeId) {
        if (accessToken == null || accessToken.equals("0")) {
            return new SkinChangeResult(false, "This is an offline account -- capes require a real Microsoft sign-in.");
        }
        try {
            String body = "{\"capeId\":\"" + capeId + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(CAPES_ACTIVE_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return new SkinChangeResult(true, "Cape equipped on your real Minecraft account.");
            }
            if (resp.statusCode() == 403 && resp.body() != null && resp.body().contains("Invalid app registration")) {
                return new SkinChangeResult(false, "Can't equip a cape yet -- DeyLauncher's Microsoft app is "
                        + "still awaiting approval.");
            }
            return new SkinChangeResult(false, "Minecraft Services rejected the request ("
                    + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return new SkinChangeResult(false, "Couldn't reach Minecraft Services: " + e.getMessage());
        }
    }

    /** Unequips any active cape via DELETE, mirroring resetToDefault's approach for skins. */
    public SkinChangeResult unequipCape(String accessToken) {
        if (accessToken == null || accessToken.equals("0")) {
            return new SkinChangeResult(false, "This is an offline account -- capes require a real Microsoft sign-in.");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CAPES_ACTIVE_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 204) {
                return new SkinChangeResult(true, "Cape removed on your real Minecraft account.");
            }
            return new SkinChangeResult(false, "Minecraft Services rejected the request ("
                    + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return new SkinChangeResult(false, "Couldn't reach Minecraft Services: " + e.getMessage());
        }
    }

    /** Uploads and applies pngFile as this account's real skin. accessToken must be a real Minecraft token (not an offline "0"). */
    public SkinChangeResult uploadSkin(String accessToken, Path pngFile, SkinModel model) {
        if (accessToken == null || accessToken.equals("0")) {
            return new SkinChangeResult(false,
                    "This is an offline account -- online skin changes need a real Microsoft sign-in.");
        }
        try {
            String boundary = "DeyLauncher-" + UUID.randomUUID();
            byte[] body = buildMultipartBody(boundary, model.apiValue(), pngFile);

            HttpRequest req = HttpRequest.newBuilder(URI.create(SKINS_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                return new SkinChangeResult(true, "Skin updated on your real Minecraft account.");
            }
            if (resp.statusCode() == 403 && resp.body() != null && resp.body().contains("Invalid app registration")) {
                return new SkinChangeResult(false,
                        "Can't change your online skin yet -- DeyLauncher's Microsoft app is still "
                                + "awaiting approval (https://aka.ms/mce-reviewappid). This will work "
                                + "automatically once that's approved.");
            }
            if (resp.statusCode() == 429) {
                return new SkinChangeResult(false, "Rate limited by Mojang -- try again in a minute.");
            }
            return new SkinChangeResult(false, "Minecraft Services rejected the request ("
                    + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return new SkinChangeResult(false, "Couldn't reach Minecraft Services: " + e.getMessage());
        }
    }

    /** Resets the account's skin to Minecraft's own default. Same approval dependency as uploadSkin. */
    public SkinChangeResult resetToDefault(String accessToken) {
        if (accessToken == null || accessToken.equals("0")) {
            return new SkinChangeResult(false,
                    "This is an offline account -- online skin changes need a real Microsoft sign-in.");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SKINS_URL + "/active"))
                    .header("Authorization", "Bearer " + accessToken)
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 204) {
                return new SkinChangeResult(true, "Skin reset to default on your real Minecraft account.");
            }
            if (resp.statusCode() == 403 && resp.body() != null && resp.body().contains("Invalid app registration")) {
                return new SkinChangeResult(false,
                        "Can't reset your online skin yet -- DeyLauncher's Microsoft app is still "
                                + "awaiting approval.");
            }
            return new SkinChangeResult(false, "Minecraft Services rejected the reset ("
                    + resp.statusCode() + "): " + resp.body());
        } catch (Exception e) {
            return new SkinChangeResult(false, "Couldn't reach Minecraft Services: " + e.getMessage());
        }
    }

    private byte[] buildMultipartBody(String boundary, String variant, Path pngFile) throws IOException {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n" + variant + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        byte[] fileBytes = Files.readAllBytes(pngFile);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(prefix.getBytes());
        out.writeBytes(fileBytes);
        out.writeBytes(suffix.getBytes());
        return out.toByteArray();
    }
}
