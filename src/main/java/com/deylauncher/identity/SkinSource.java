package com.deylauncher.identity;

/**
 * Deliberately three states, not a boolean "has custom skin" -- the UI needs
 * to tell the person plainly whether what they're looking at is their real
 * Mojang skin, a DeyLauncher-only custom skin, or nothing set at all. This
 * is also the flag that stops the launcher from ever pretending a local
 * change is an official one: an OFFLINE_CUSTOM skin is never uploaded
 * anywhere, and a MOJANG_ONLINE skin is never faked without a real API call.
 */
public enum SkinSource {
    DEFAULT,          // no custom skin set; Minecraft's own default (Steve/Alex) applies
    OFFLINE_CUSTOM,    // a DeyLauncher-local custom skin, stored under profiles/<uuid>/skin.png
    MOJANG_ONLINE      // the account's real skin, set via Minecraft Services (online accounts only)
}
