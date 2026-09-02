package com.deylauncher.auth;

import java.util.UUID;

/**
 * A fake local session so we can build/test version downloading and game
 * launching *right now*, without waiting on Microsoft's app approval
 * (https://aka.ms/mce-reviewappid).
 *
 * IMPORTANT LIMITATION: this only works for offline-mode testing. Real
 * Minecraft servers running in online-mode (the default, and what your
 * player-hosted servers will use) verify the session token with Mojang's
 * servers -- an offline session will be rejected. This exists purely so we
 * can develop/test the launcher's downloading and launching logic while the
 * real MicrosoftAuth flow waits on approval. Swap AuthSession.offline(...)
 * for MicrosoftAuth.login(...) once approved; nothing else in the launcher
 * needs to change, since both produce the same AuthSession shape.
 */
public record AuthSession(String accessToken, String uuid, String username, boolean isOffline) {

    public static AuthSession offline(String username) {
        // Deterministic "offline-style" UUID, same approach the vanilla
        // launcher uses for offline accounts (based on "OfflinePlayer:<name>").
        UUID fakeUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
        return new AuthSession("0", fakeUuid.toString().replace("-", ""), username, true);
    }

    public static AuthSession fromMicrosoft(MicrosoftAuth.MinecraftSession s) {
        return new AuthSession(s.minecraftAccessToken(), s.uuid(), s.username(), false);
    }
}
