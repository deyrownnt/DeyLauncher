package com.deylauncher.launch;

import com.deylauncher.auth.AuthSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.*;

/**
 * Turns a PreparedVersion + AuthSession + user settings into an actual
 * `java ...` command and runs it. Handles the placeholder substitution
 * Mojang's version JSON uses (${auth_player_name}, ${classpath}, etc).
 */
public class GameLauncher {

    /** Everything the RAM/resolution/fullscreen settings screen controls. */
    public record LaunchSettings(int ramMinMb, int ramMaxMb, int width, int height, boolean fullscreen) {
        public static LaunchSettings defaults() {
            return new LaunchSettings(1024, 4096, 854, 480, false);
        }
    }

    public Process launch(GameFiles.PreparedVersion version, AuthSession session,
                           Path gameDirectory, LaunchSettings settings, String javaBinaryPath) throws Exception {
        return launch(version, session, gameDirectory, settings, javaBinaryPath, null);
    }

    /**
     * Same as the 5-arg launch(), with an optional quickPlayMultiplayerTarget ("host:port") for
     * the Friends "Join" button -- launches straight into that server via Mojang's own documented
     * quick-play argument instead of the normal main-menu boot. Pass null for a normal launch.
     */
    public Process launch(GameFiles.PreparedVersion version, AuthSession session, Path gameDirectory,
                           LaunchSettings settings, String javaBinaryPath, String quickPlayMultiplayerTarget) throws Exception {

        Map<String, String> placeholders = buildPlaceholders(version, session, gameDirectory, settings);
        Map<String, Boolean> features = currentFeatures();
        if (quickPlayMultiplayerTarget != null && !quickPlayMultiplayerTarget.isBlank()) {
            features.put("has_quick_plays_support", true);
            features.put("is_quick_play_multiplayer", true);
            placeholders.put("quickPlayMultiplayer", quickPlayMultiplayerTarget);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBinaryPath); // e.g. "java", or a full path to a per-version-appropriate JDK

        command.add("-Xms" + settings.ramMinMb() + "M");
        command.add("-Xmx" + settings.ramMaxMb() + "M");
        command.add("-Djava.library.path=" + version.nativesDir());

        // JVM arguments from the version JSON (modern format), falling back to sane defaults
        // for older-style version JSONs that only list game arguments as a flat string.
        JsonObject args = version.versionJson().has("arguments")
                ? version.versionJson().getAsJsonObject("arguments") : null;

        if (args != null && args.has("jvm")) {
            addResolvedArgs(command, args.getAsJsonArray("jvm"), placeholders, features);
        } else {
            command.add("-cp");
            command.add(placeholders.get("classpath"));
        }

        command.add(version.mainClass());

        if (args != null && args.has("game")) {
            addResolvedArgs(command, args.getAsJsonArray("game"), placeholders, features);
        } else if (version.versionJson().has("minecraftArguments")) {
            for (String token : version.versionJson().get("minecraftArguments").getAsString().split(" ")) {
                command.add(substitute(token, placeholders));
            }
        }

        if (settings.fullscreen()) {
            command.add("--fullscreen");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDirectory.toFile());
        pb.redirectErrorStream(true); // merge stderr into stdout so callers only read one stream
        return pb.start();
    }

    private Map<String, String> buildPlaceholders(GameFiles.PreparedVersion version, AuthSession session,
                                                    Path gameDirectory, LaunchSettings settings) {
        String classpath = String.join(java.io.File.pathSeparator,
                version.libraryJars().stream().map(Path::toString).toList())
                + java.io.File.pathSeparator + version.clientJar();

        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", session.username());
        m.put("version_name", version.versionJson().get("id").getAsString());
        m.put("game_directory", gameDirectory.toString());
        // gameDirectory is root/instances/<id>, but GameFiles downloads assets to root/assets --
        // that's two levels up from the instance dir (out of instances/, then into assets/), not one.
        m.put("assets_root", gameDirectory.resolve("../../assets").normalize().toString());
        m.put("assets_index_name", version.versionJson().getAsJsonObject("assetIndex").get("id").getAsString());
        m.put("auth_uuid", session.uuid());
        m.put("auth_access_token", session.accessToken());
        m.put("user_type", session.isOffline() ? "legacy" : "msa");
        m.put("version_type", version.versionJson().has("type")
                ? version.versionJson().get("type").getAsString() : "release");
        m.put("natives_directory", version.nativesDir().toString());
        m.put("launcher_name", "DeyLauncher");
        m.put("launcher_version", "0.1.0");
        m.put("classpath", classpath);
        m.put("resolution_width", String.valueOf(settings.width()));
        m.put("resolution_height", String.valueOf(settings.height()));
        return m;
    }

    // The modern "arguments" arrays mix plain strings with conditional {"rules": [...], "value": ...}
    // objects gated on either "os" (platform-specific flags like -XstartOnFirstThread) or "features"
    // (launcher-capability flags like quick-play variants, demo mode, custom resolution). We evaluate
    // both kinds of condition in argRulesPass() below, against whatever currentFeatures() declares.
    private void addResolvedArgs(List<String> command, JsonArray argsArray, Map<String, String> placeholders,
                                  Map<String, Boolean> features) {
        for (JsonElement el : argsArray) {
            if (el.isJsonPrimitive()) {
                command.add(substitute(el.getAsString(), placeholders));
            } else {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("rules") && !argRulesPass(obj.getAsJsonArray("rules"), features)) continue;
                JsonElement value = obj.get("value");
                if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) {
                        command.add(substitute(v.getAsString(), placeholders));
                    }
                } else {
                    command.add(substitute(value.getAsString(), placeholders));
                }
            }
        }
    }

    /**
     * Which launcher-capability "features" we actually support. DeyLauncher has no Quick Play
     * feature yet, so every is_quick_play_* flag is deliberately absent/false here -- that's what
     * keeps their argument rules from matching (see argRulesPass). We DO support custom resolution,
     * since the Settings > Game tab always supplies width/height, so that one is true.
     */
    private Map<String, Boolean> currentFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("has_custom_resolution", true);
        // Deliberately no entries for is_quick_play_singleplayer / is_quick_play_multiplayer /
        // is_quick_play_realms / has_quick_plays_support -- see argRulesPass()'s missing-key handling.
        return features;
    }

    private boolean argRulesPass(JsonArray rules, Map<String, Boolean> features) {
        String os = System.getProperty("os.name").toLowerCase();
        String osKey = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        // Mojang's rule semantics: default is disallow, and each matching rule
        // (in order) overwrites the running result -- the last matching rule
        // wins. A rule with neither "os" nor "features" matches unconditionally.
        boolean allowed = false;
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                matches = !osObj.has("name") || osObj.get("name").getAsString().equals(osKey);
            }

            if (matches && rule.has("features")) {
                JsonObject required = rule.getAsJsonObject("features");
                for (String key : required.keySet()) {
                    boolean requiredValue = required.get(key).getAsBoolean();
                    // A feature we never declared (e.g. any is_quick_play_* flag) is treated as
                    // false -- this is exactly what stops quick-play argument rules from matching
                    // when DeyLauncher never requested quick play.
                    boolean actualValue = features.getOrDefault(key, false);
                    if (actualValue != requiredValue) {
                        matches = false;
                        break;
                    }
                }
            }

            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    private String substitute(String token, Map<String, String> placeholders) {
        String out = token;
        for (var e : placeholders.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}
