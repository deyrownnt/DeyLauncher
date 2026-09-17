package com.deylauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Mojang's rule language, in one place.
 *
 * <p>Version JSONs use a {@code "rules"} array to gate BOTH libraries and arguments: a rule can test
 * the OS <em>name</em>, the CPU <em>architecture</em>, the OS <em>version</em> and launcher
 * <em>features</em>. Mojang's own semantics are "default deny, last matching rule wins", and a rule
 * with none of those fields matches everything.
 *
 * <p>This class exists because that evaluation was previously duplicated (with slightly different
 * behaviour) in {@link GameFiles} and {@link GameLauncher} -- and both copies ignored
 * {@code os.arch} completely. The visible consequence was real: vanilla 1.20.1 gates {@code -Xss1M}
 * on {@code {"os":{"arch":"x86"}}}, and since a rule with no {@code "name"} "matched" unconditionally,
 * every 64-bit launch got a 1 MB thread stack Mojang never intended to hand it. On an ARM64 machine
 * the same gap would also let x86-only entries through.
 *
 * <p>The platform is injectable, so the tests never depend on the machine they run on.
 */
public final class RuleEvaluator {

    private RuleEvaluator() {}

    /** The machine facts a rule is matched against. */
    public record Platform(String osName, String osArch, String osVersion) {

        /** The real machine, as the JVM describes it. */
        public static Platform current() {
            return new Platform(
                    System.getProperty("os.name", ""),
                    System.getProperty("os.arch", ""),
                    System.getProperty("os.version", ""));
        }

        /** Mojang's short OS key: {@code "windows"}, {@code "osx"} or {@code "linux"}. */
        public String osKey() {
            String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (os.contains("win")) return "windows";
            if (os.contains("mac") || os.contains("darwin")) return "osx";
            return "linux";
        }

        /**
         * Normalised CPU architecture: {@code "x86"} (32-bit), {@code "x86_64"}, {@code "arm64"},
         * {@code "arm32"}, or {@code ""} when the JVM reports something we don't recognise -- in which
         * case an arch-gated rule never matches, so we never hand a machine the wrong binaries.
         */
        public String archKey() {
            String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
            switch (a) {
                case "x86", "i386", "i486", "i586", "i686", "x86_32", "x86-32" -> {
                    return "x86";
                }
                case "amd64", "x86_64", "x86-64", "x64" -> {
                    return "x86_64";
                }
                case "aarch64", "arm64" -> {
                    return "arm64";
                }
                case "arm", "arm32", "armv6l", "armv7l" -> {
                    return "arm32";
                }
                default -> {
                    return "";
                }
            }
        }
    }

    /**
     * True when an entry carrying {@code rules} applies to {@code platform}. A null/empty rules array
     * means "no restriction" (plain vanilla library entries have none).
     */
    public static boolean allows(JsonArray rules, Platform platform, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonElement el : rules) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject rule = el.getAsJsonObject();
            if (!matches(rule, platform, features)) continue;
            JsonElement action = rule.get("action");
            // A matching rule overwrites the running result; a malformed one denies rather than
            // accidentally allowing something (fails closed, exactly like the official launcher).
            allowed = action != null && action.isJsonPrimitive() && "allow".equals(action.getAsString());
        }
        return allowed;
    }

    private static boolean matches(JsonObject rule, Platform platform, Map<String, Boolean> features) {
        if (rule.has("os") && rule.get("os").isJsonObject()) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equals(platform.osKey())) return false;
            if (os.has("arch") && !archMatches(os.get("arch").getAsString(), platform.archKey())) return false;
            if (os.has("version") && !versionMatches(os.get("version").getAsString(), platform.osVersion())) return false;
        }
        if (rule.has("features") && rule.get("features").isJsonObject()) {
            for (var entry : rule.getAsJsonObject("features").entrySet()) {
                boolean required = entry.getValue().getAsBoolean();
                // A feature we never declared is false -- which is what stops the quick-play
                // argument rules from matching when the launcher never asked for quick play.
                boolean actual = features == null || features.getOrDefault(entry.getKey(), false);
                if (actual != required) return false;
            }
        }
        return true;
    }

    /** Maps a rule's {@code os.arch} value onto the normalized architecture it is asking for. */
    static boolean archMatches(String ruleArch, String hostArch) {
        String want = ruleArch == null ? "" : ruleArch.toLowerCase(Locale.ROOT).trim();
        switch (want) {
            case "x86", "x86_32", "x86-32", "i386", "i486", "i586", "i686" -> {
                return "x86".equals(hostArch);
            }
            case "x86_64", "x86-64", "amd64", "x64" -> {
                return "x86_64".equals(hostArch);
            }
            case "arm64", "aarch64" -> {
                return "arm64".equals(hostArch);
            }
            case "arm32", "arm", "armv7l", "armv6l" -> {
                return "arm32".equals(hostArch);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Mojang's {@code os.version} is a REGEX matched against the OS version string (e.g. Windows-only
     * entries ship {@code {"version":"^10\\.}}), not a literal comparison.
     */
    static boolean versionMatches(String ruleVersion, String hostVersion) {
        String version = hostVersion == null ? "" : hostVersion;
        try {
            return Pattern.compile(ruleVersion).matcher(version).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
