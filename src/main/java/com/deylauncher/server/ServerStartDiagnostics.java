package com.deylauncher.server;

import java.util.List;

/**
 * Turns the raw console output of a server that died on startup into one actionable line for the user.
 *
 * <p>This exists because the version-change bug ended in a silent-looking failure: the server printed
 * a Java stack trace, exited, and the console showed "Server exited with code 1" -- which says nothing
 * about the actual cause (a world saved by a newer version, or mods built for one). Recognising the
 * handful of signatures Minecraft/Forge/Fabric actually print lets the launcher say what to do next
 * instead of leaving the user with a crash and no explanation.
 *
 * <p>Every hint is worded as a likely cause, not a certainty: these are log signatures, not a
 * diagnosis. Pure string matching, so the whole table is unit-testable.
 */
public final class ServerStartDiagnostics {

    /** One hint to show, with a stable key so a run only prints each hint once. */
    public record Hint(String key, String message) {}

    /**
     * The hint for one console line, or null when the line carries no known failure signature. Matching
     * is case-insensitive and deliberately broad -- a false positive only costs one extra informational
     * line in the console.
     */
    public static Hint hintFor(String consoleLine) {
        if (consoleLine == null || consoleLine.isBlank()) return null;
        String line = consoleLine.toLowerCase(java.util.Locale.ROOT);

        if (containsAny(line, "data version", "newer version of minecraft", "downgrade")) {
            return new Hint("world-too-new",
                    "This world was saved by a NEWER Minecraft version than the one this server is now "
                            + "set to, and an older server cannot read it. Open Change Version, choose "
                            + "\"Back up world & start fresh\", then start the server again -- the old "
                            + "world is kept in this folder as a backup.");
        }
        if (containsAny(line, "incompatible mod set", "incompatible mods", "requires minecraft",
                "requires forge", "requires neoforge", "mixin apply failed", "mixin transformation failed")) {
            return new Hint("mods-version",
                    "A mod in the mods/ folder was built for a different Minecraft version, so the "
                            + "server refuses to load. Set the mods for the old version aside (Change "
                            + "Version does this automatically) or remove them from mods/, then start "
                            + "the server again.");
        }
        if (containsAny(line, "failed to load datapacks", "pack format", "incompatible datapack",
                "incompatible pack")) {
            return new Hint("datapacks-version",
                    "A datapack or resource pack in this server's world/ or datapacks/ folder is not "
                            + "compatible with this Minecraft version. Move it out of the folder, or use "
                            + "a version that matches it.");
        }
        if (containsAny(line, "unsupportedclassversionerror", "class file version",
                "has been compiled by a more recent version")) {
            return new Hint("java-too-old",
                    "This server jar needs a newer Java runtime than the one it was started with. Pick "
                            + "a newer runtime under Settings > JAVA ENVIRONMENT, or recreate the server "
                            + "on a version whose Java matches.");
        }
        if (containsAny(line, "could not find or load main class", "unable to access jarfile",
                "no launch args file found")) {
            return new Hint("missing-software",
                    "The server software this folder is trying to launch isn't there any more, or its "
                            + "launch files belong to another version. DeyLauncher re-downloads it on the "
                            + "next Start; if this keeps happening, recreate the server.");
        }
        if (containsAny(line, "you need to agree to the eula")) {
            return new Hint("eula",
                    "The server is asking for its eula.txt to be accepted. DeyLauncher writes that file "
                            + "on Start -- check that this server folder is writable.");
        }
        return null;
    }

    private static boolean containsAny(String line, String... needles) {
        for (String needle : needles) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    /** Convenience for callers that keep a set of already-shown keys: true when this hint is new. */
    public static boolean isNew(List<Hint> shown, Hint hint) {
        if (hint == null) return false;
        for (Hint h : shown) {
            if (h.key().equals(hint.key())) return false;
        }
        return true;
    }
}