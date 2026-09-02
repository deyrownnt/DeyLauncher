package com.deylauncher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.deylauncher.identity.SkinModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Full Microsoft -> Xbox Live -> XSTS -> Minecraft login chain, using the
 * OAuth 2.0 "device code" flow (no embedded browser, no redirect server needed
 * on our end -- the user just opens a page and types a code).
 *
 * Client ID below is DeyLauncher's registered Azure app (public client,
 * "Any Entra ID Tenant + Personal Microsoft accounts").
 */
public class MicrosoftAuth {

    // Registered in Azure Portal -> App registrations -> DeyLauncher
    private static final String CLIENT_ID = "bef65492-f622-4b08-a124-9535e7b0ffde";

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String MC_ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore";

    private final HttpClient http = HttpClient.newHttpClient();

    /** Final result the rest of the launcher cares about. */
    /** Includes the skin bytes returned by the authenticated Minecraft profile, so the UI can
     * cache and render the skin currently worn by the account without inventing a placeholder. */
    public record MinecraftSession(String minecraftAccessToken, String uuid, String username,
                                   byte[] currentSkin, SkinModel skinModel, String refreshToken) {}

    /** Called once we have a code + URL the user needs to visit. */
    public interface DeviceCodePrompt {
        void show(String verificationUri, String userCode, int expiresInSeconds);
    }

    public MinecraftSession login(DeviceCodePrompt prompt) throws Exception {
        JsonObject deviceCodeResp = requestDeviceCode();
        String deviceCode = deviceCodeResp.get("device_code").getAsString();
        String userCode = deviceCodeResp.get("user_code").getAsString();
        String verificationUri = deviceCodeResp.get("verification_uri").getAsString();
        int interval = deviceCodeResp.get("interval").getAsInt();
        int expiresIn = deviceCodeResp.get("expires_in").getAsInt();

        prompt.show(verificationUri, userCode, expiresIn);

        JsonObject msTokens = pollForToken(deviceCode, interval, expiresIn);
        return completeLogin(msTokens);
    }

    /** Restores a remembered account without showing a new device code. */
    public MinecraftSession resume(String refreshToken) throws Exception {
        String form = "grant_type=refresh_token&client_id=" + CLIENT_ID + "&refresh_token=" + urlEncode(refreshToken)
                + "&scope=" + urlEncode(SCOPE);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return completeLogin(sendJson(req));
    }

    private MinecraftSession completeLogin(JsonObject msTokens) throws Exception {
        String msAccessToken = msTokens.get("access_token").getAsString();

        JsonObject xblResp = authenticateXboxLive(msAccessToken);
        String xblToken = xblResp.get("Token").getAsString();
        String userHash = xblResp.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

        JsonObject xstsResp = authenticateXsts(xblToken);
        String xstsToken = xstsResp.get("Token").getAsString();

        String mcAccessToken = loginToMinecraft(userHash, xstsToken);

        if (!ownsMinecraft(mcAccessToken)) {
            throw new IllegalStateException(
                    "This Microsoft account has no Minecraft: Java Edition license attached.");
        }

        JsonObject profile = fetchProfile(mcAccessToken);
        String uuid = profile.get("id").getAsString();
        String username = profile.get("name").getAsString();
        String skinUrl = null;
        SkinModel skinModel = SkinModel.CLASSIC;
        if (profile.has("skins")) {
            for (var element : profile.getAsJsonArray("skins")) {
                JsonObject skin = element.getAsJsonObject();
                if (skinUrl == null || "ACTIVE".equalsIgnoreCase(skin.has("state") ? skin.get("state").getAsString() : "")) {
                    skinUrl = skin.has("url") ? skin.get("url").getAsString() : null;
                    skinModel = SkinModel.fromApiValue(skin.has("variant") ? skin.get("variant").getAsString() : null);
                    if ("ACTIVE".equalsIgnoreCase(skin.has("state") ? skin.get("state").getAsString() : "")) break;
                }
            }
        }
        byte[] skinBytes = skinUrl == null ? null : downloadSkin(skinUrl);
        String refresh = msTokens.has("refresh_token") ? msTokens.get("refresh_token").getAsString() : null;
        return new MinecraftSession(mcAccessToken, uuid, username, skinBytes, skinModel, refresh);
    }

    // ---- Step 1: ask for a device code ----
    private JsonObject requestDeviceCode() throws Exception {
        String form = "client_id=" + CLIENT_ID + "&scope=" + urlEncode(SCOPE);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DEVICE_CODE_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return sendJson(req);
    }

    // ---- Step 2: poll until the user finishes signing in on their phone/browser ----
    private JsonObject pollForToken(String deviceCode, int intervalSeconds, int expiresIn) throws Exception {
        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                + "&client_id=" + CLIENT_ID
                + "&device_code=" + deviceCode;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalSeconds * 1000L);
            HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (resp.statusCode() == 200) {
                return body;
            }
            String error = body.has("error") ? body.get("error").getAsString() : "unknown_error";
            if (!error.equals("authorization_pending")) {
                throw new IllegalStateException("Microsoft sign-in failed: " + error);
            }
            // otherwise: user hasn't finished yet, keep polling
        }
        throw new IllegalStateException("Sign-in timed out. Please try again.");
    }

    // ---- Step 3: Xbox Live token ----
    private JsonObject authenticateXboxLive(String msAccessToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msAccessToken);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        HttpRequest req = HttpRequest.newBuilder(URI.create(XBL_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return sendJson(req);
    }

    // ---- Step 4: XSTS token (this is the one that actually authorizes Minecraft use) ----
    private JsonObject authenticateXsts(String xblToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        var tokens = new com.google.gson.JsonArray();
        tokens.add(xblToken);
        properties.add("UserTokens", tokens);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        HttpRequest req = HttpRequest.newBuilder(URI.create(XSTS_AUTH_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            // Common causes: no Xbox account tied to this MS account (needs creating once
            // at xbox.com), or child account requiring family approval.
            throw new IllegalStateException(
                    "Xbox authorization failed (401). This account may not have an Xbox profile "
                            + "set up yet -- ask the user to visit xbox.com and finish account setup once.");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ---- Step 5: exchange for the actual Minecraft access token ----
    private String loginToMinecraft(String userHash, String xstsToken) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_LOGIN_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            JsonObject resp = sendJson(req);
            return resp.get("access_token").getAsString();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Invalid app registration")) {
                throw new IllegalStateException(
                        "Your Azure app (Client ID " + CLIENT_ID + ") hasn't been approved to use "
                                + "the Minecraft API yet. New app registrations must apply for access "
                                + "via https://aka.ms/mce-reviewappid -- submit that form and try again "
                                + "once approved. This is a one-time step for the whole app, not per-user.",
                        e);
            }
            throw e;
        }
    }

    private boolean ownsMinecraft(String mcAccessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_ENTITLEMENTS_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        JsonObject resp = sendJson(req);
        return resp.has("items") && resp.getAsJsonArray("items").size() > 0;
    }

    private JsonObject fetchProfile(String mcAccessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        return sendJson(req);
    }

    private byte[] downloadSkin(String skinUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(skinUrl)).GET().build();
        HttpResponse<byte[]> response = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException("Couldn't download active skin (" + response.statusCode() + ").");
        return response.body();
    }

    private JsonObject sendJson(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Request to " + req.uri() + " failed ("
                    + resp.statusCode() + "): " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
