package com.deylauncher.launch;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.update.AppUpdater;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Turns a PreparedVersion + AuthSession + user settings into an actual
 * `java ...` command and runs it. Handles the placeholder substitution
 * Mojang's version JSON uses (${auth_player_name}, ${classpath}, etc).
 */
public class GameLauncher {

    /**
     * Everything the RAM/resolution/fullscreen settings screen controls.
     *
     * <p>The last two are the Linux compatibility switches: {@code softwareOpenGl} runs the game through
     * Mesa's CPU renderer (for a machine whose GPU can't expose an OpenGL 3.3 context), and
     * {@code nativeWayland} starts it on Wayland's own driver path instead of XWayland (see
     * {@link WaylandSupport} -- that is what a native crash inside {@code glfwCreateWindow} needs, since
     * software rendering cannot avoid a fault that happens before any GPU work).
     */
    public record LaunchSettings(int ramMinMb, int ramMaxMb, int width, int height, boolean fullscreen,
                                 boolean softwareOpenGl, boolean nativeWayland) {
        public static LaunchSettings defaults() {
            return new LaunchSettings(1024, 4096, 854, 480, false, false, false);
        }

        /** Convenience: older callers that only set the classic five fields keep both switches off. */
        public LaunchSettings(int ramMinMb, int ramMaxMb, int width, int height, boolean fullscreen) {
            this(ramMinMb, ramMaxMb, width, height, fullscreen, false, false);
        }

        /** Convenience for callers written before the Wayland switch existed. */
        public LaunchSettings(int ramMinMb, int ramMaxMb, int width, int height, boolean fullscreen,
                              boolean softwareOpenGl) {
            this(ramMinMb, ramMaxMb, width, height, fullscreen, softwareOpenGl, false);
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
        return launch(version, session, gameDirectory, settings, javaBinaryPath, quickPlayMultiplayerTarget, null);
    }

    /**
     * The same launch, with an optional one-line warning sink. It carries the problems that are
     * invisible otherwise -- most importantly a mod-loader profile referencing a {@code ${placeholder}}
     * the launcher doesn't know, which would reach the game as literal text instead of a value
     * (see {@link #unresolvedPlaceholders}).
     */
    public Process launch(GameFiles.PreparedVersion version, AuthSession session, Path gameDirectory,
                           LaunchSettings settings, String javaBinaryPath, String quickPlayMultiplayerTarget,
                           Consumer<String> log) throws Exception {

        List<String> command = buildCommand(version, session, gameDirectory, settings,
                javaBinaryPath, quickPlayMultiplayerTarget);

        // A ${...} token we never substituted is a real bug class: the official launcher fills these
        // in, so a version or mod-loader profile that asks for one would otherwise hand the game
        // literal "${clientid}" text (which is exactly what happened here for ${clientid} and
        // ${auth_xuid} on every 1.19+ launch). Rather than failing a launch that may still work, say
        // so instead of letting it disappear into a log nobody reads.
        List<String> unresolved = unresolvedPlaceholders(command);
        if (!unresolved.isEmpty() && log != null) {
            log.accept("Warning: this version asks for placeholder(s) DeyLauncher doesn't fill in: "
                    + String.join(", ", unresolved)
                    + " -- the game received them as literal text. Please report this.");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDirectory.toFile());
        pb.redirectErrorStream(true); // merge stderr into stdout so callers only read one stream
        // The JVM only writes its hs_err dump to the -XX:ErrorFile above if that file's PARENT FOLDER
        // already exists -- it never creates it, and silently falls back to the working directory when
        // it can't. Creating it here is what keeps a crash dump where the launcher expects to find it,
        // redact the account token out of it and lock it down (see CrashDumps).
        CrashDumps.prepareDumpLocation(gameDirectory);
        // On a Wayland session, explicitly request GLFW's Wayland backend (Settings > Game > "Run natively
        // on Wayland"). Do not hide DISPLAY: doing so left modern GLFW on a DISPLAY-less X11 backend and
        // crashed Minecraft, while also breaking mods that initialise Java's X11/AWT support.
        if (WaylandSupport.enableNativeWayland(settings.nativeWayland(), System.getProperty("os.name", ""),
                pb.environment(), version.nativesDir(), version.versionJson()) && log != null) {
            log.accept("Wayland session detected: requesting GLFW's native Wayland backend.");
        }
        return pb.start();
    }

    /**
     * Every argument the game's JVM will be started with -- everything {@link #launch} does except
     * actually starting it. Package-private so tests can assert the finished command line: which
     * placeholders resolved, which Mojang argument rules applied on which platform, and where the
     * JVM is told to put its crash dump.
     */
    List<String> buildCommand(GameFiles.PreparedVersion version, AuthSession session, Path gameDirectory,
                              LaunchSettings settings, String javaBinaryPath, String quickPlayMultiplayerTarget) {

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
        // Keep the JVM's own crash dump (hs_err_pid<pid>.log) inside the instance, next to Minecraft's
        // own crash reports, instead of the working directory. That file repeats the whole command
        // line -- including ${auth_access_token} -- so the launcher scrubs and locks it down after a
        // crash (see CrashDumps); putting it somewhere predictable is what makes that possible.
        command.add(errorFileArg(gameDirectory));

        // JVM arguments from the version JSON (modern format), falling back to sane defaults
        // for older-style version JSONs that only list game arguments as a flat string.
        JsonObject args = version.versionJson().has("arguments")
                ? version.versionJson().getAsJsonObject("arguments") : null;

        // Headless mode: prevent Swing/AWT mods (like EarlyLoadingBar) from trying to create windows
        // when no display is available (CI, servers, headless Linux). This must be added before
        // the version JSON's JVM args so it takes effect early.
        // Also enable when software rendering is used (strong indicator of headless/CI environment).
        // On Linux, always enable headless for modpack launches unless native Wayland is explicitly requested,
        // because modpacks often include mods (EarlyLoadingBar, etc.) that create Swing windows at startup.
        // The launcher process may have a display, but the game process often ends up with software rendering (llvmpipe).
        boolean headless = isHeadlessEnvironment() || settings.softwareOpenGl() || 
                (System.getProperty("os.name", "").toLowerCase().contains("linux") && !settings.nativeWayland());
        // Additionally, always enable headless on Linux for Fabric/Forge modpack launches as a safety net
        // since many mods (EarlyLoadingBar, etc.) crash when trying to create Swing windows in software rendering mode.
        if (!headless && System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            // Check if this is a modpack/modded launch by looking for Fabric/Forged indicators
            String mainClass = version.mainClass();
            if (mainClass != null && (mainClass.contains("fabric") || mainClass.contains("forge") || mainClass.contains("knot"))) {
                headless = true;
            }
        }
        if (headless) {
            command.add("-Djava.awt.headless=true");
        }

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

        // Software-OpenGL compatibility mode (Settings > Game): on Linux machines whose GPU driver
        // can't expose OpenGL 3.3 (classic symptom: "GLXBadFBConfig" / "Driver does not support
        // OpenGL 3.3"), launching through `/usr/bin/env LIBGL_ALWAYS_SOFTWARE=1 ...` makes Mesa render
        // in software on the CPU, which does provide OpenGL 3.3 -- so Minecraft gets a window without
        // needing a working GPU driver, a display reconfig, or any admin rights. Off by default so
        // healthy machines keep GPU acceleration.
        return applySoftwareGl(command, settings.softwareOpenGl(), System.getProperty("os.name", ""));
    }

    /**
     * The JVM's {@code -XX:ErrorFile} argument for one launch: the instance's own crash-reports folder,
     * with the JVM's own {@code %p} (pid) placeholder kept for uniqueness.
     *
     * <p>The directory is created by {@link CrashDumps#prepareDumpLocation} at launch time, because the
     * JVM itself never creates an {@code ErrorFile}'s parent: without it the dump silently lands in the
     * working directory instead, which is precisely what happened before that call existed. Nothing is
     * created at build-command time -- a launch that fails before the game starts should leave no trace.
     */
    static String errorFileArg(Path gameDirectory) {
        Path dumps = gameDirectory.resolve("crash-reports").resolve("hs_err_pid%p.log");
        return "-XX:ErrorFile=" + dumps;
    }

    /**
     * Every distinct {@code ${...}} token still present in the finished command line -- i.e. a
     * placeholder nobody substituted. Package-private so a test can assert a real 1.20.1-shaped
     * version JSON leaves none behind (the version that shipped {@code --clientId ${clientid}}).
     */
    static List<String> unresolvedPlaceholders(List<String> command) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\}").matcher("");
        for (String arg : command) {
            m.reset(arg);
            while (m.find()) out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /**
     * Testable: when {@code softwareGL} is on AND we're on Linux, prepend Mesa's software-OpenGL env
     * assignments (through the standard {@code /usr/bin/env}) so the child Minecraft process renders
     * on the CPU with OpenGL 3.3 -- no GPU driver or admin required. Returns the command unchanged on
     * Windows/macOS (where the LXGL/GLX workaround is meaningless) and when the toggle is off.
     *
     * <p>On a GLVND system (virtually every modern NVIDIA + mesa distro) {@code LIBGL_ALWAYS_SOFTWARE=1}
     * alone is silently IGNORED -- the GLVND dispatcher keeps resolving to the proprietary libGLX_nvidia
     * vendor, which fails to create a GL 3.3 context on a broken/mismatched display, and the game aborts
     * with "Driver does not support OpenGL 3.3" + exit 134. Forcing {@code __GLX_VENDOR_LIBRARY_NAME=mesa}
     * (plus {@code GALLIUM_DRIVER=llvmpipe}/{@code MESA_LOADER_DRIVER_OVERRIDE=llvmpipe} as belt-and-braces)
     * is what actually switches GLVND onto Mesa's software llvmpipe renderer, which always provides a
     * GL 4.x context. This is why the fix must set the vendor, not just ALWAYS_SOFTWARE.
     */
    static List<String> applySoftwareGl(List<String> command, boolean softwareGL, String osName) {
        if (!softwareGL) return command;
        String os = osName == null ? "" : osName.toLowerCase();
        if (!os.contains("linux")) return command;
        List<String> out = new ArrayList<>(command.size() + 5);
        out.add("/usr/bin/env");
        // Force Mesa's llvmpipe (software) through the GLVND dispatcher -- ALWAYS_SOFTWARE alone is
        // ignored when the default GLX vendor is NVIDIA's proprietary driver.
        out.add("LIBGL_ALWAYS_SOFTWARE=1");
        out.add("__GLX_VENDOR_LIBRARY_NAME=mesa");
        out.add("GALLIUM_DRIVER=llvmpipe");
        out.add("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe");
        out.addAll(command);
        return out;
    }

    /**
     * Detects if we're running in a headless environment (no display server).
     * This is used to set -Djava.awt.headless=true to prevent Swing/AWT mods
     * (like EarlyLoadingBar) from trying to create windows when no display is available.
     */
    private static boolean isHeadlessEnvironment() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("linux")) return false;

        // Check for display servers
        String display = System.getenv("DISPLAY");
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String xdgSessionType = System.getenv("XDG_SESSION_TYPE");

        // No display at all = headless
        if ((display == null || display.isBlank()) &&
            (waylandDisplay == null || waylandDisplay.isBlank()) &&
            (xdgSessionType == null || xdgSessionType.equals("tty"))) {
            return true;
        }

        // DISPLAY set but points to nothing accessible (common in CI)
        if (display != null && !display.isBlank() && !display.startsWith(":") && !display.contains(":")) {
            return true;
        }

        return false;
    }


    /**
     * The full ${...} substitution table for one launch. Package-private so a test can assert that
     * the mod-loader-only placeholders (${library_directory}, ${classpath_separator}) are actually
     * present -- leaking those literally onto the command line is a silent "modded game won't start".
     */
    Map<String, String> buildPlaceholders(GameFiles.PreparedVersion version, AuthSession session,
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
        // Mojang's own argument list for 1.19+ contains "--clientId ${clientid}" and
        // "--xuid ${auth_xuid}" right after the access token, and both were MISSING here -- so every
        // launch handed the game the literal strings "${clientid}" and "${auth_xuid}" (visible in the
        // JVM's own crash dump: "--clientId ${clientid} --xuid ${auth_xuid}"). The client id is the
        // app id this launcher's Microsoft sign-in uses (that is what the game expects it to be), and
        // xuid is the account's Xbox id, which an offline session simply doesn't have -- the official
        // launcher passes those through as-is for offline play too, so an empty string is the honest
        // value there rather than a made-up id.
        m.put("clientid", com.deylauncher.auth.MicrosoftAuth.CLIENT_ID);
        m.put("auth_xuid", "");
        // Placeholders used by the pre-1.13 "minecraftArguments" format (a flat string with
        // ${user_properties} / ${profile_name} / ${auth_session} in it). Vanilla JSONs for 1.7-1.12
        // still list them, and none of them existed here either, so an old version launched from this
        // launcher got literal text where its launcher-specific option blob should have been.
        m.put("user_properties", "{}");
        m.put("profile_name", session.username());
        m.put("auth_session", "token:" + session.accessToken() + ":" + session.uuid());
        m.put("user_type", session.isOffline() ? "legacy" : "msa");
        m.put("version_type", version.versionJson().has("type")
                ? version.versionJson().get("type").getAsString() : "release");
        m.put("natives_directory", version.nativesDir().toString());
        m.put("launcher_name", "DeyLauncher");
        m.put("launcher_version", AppUpdater.currentVersion()); // must match the actual running version (embedded at build)
        m.put("classpath", classpath);
        // Forge 1.17+ profiles supply their own JVM args that reference these two: a
        // -DlibraryDirectory=${library_directory}, a -p module path built from
        // ${library_directory}/cpw/mods/..., and ${classpath_separator} between those module jars.
        // They are not Mojang placeholders (vanilla version JSONs never use them), so without them
        // the literal "${library_directory}" reached the JVM and Forge's BootstrapLauncher could not
        // find its modules -- a modded launch that dies before the game even starts. The official
        // launcher fills them in the same way: the launcher's own libraries folder, and the
        // platform's path separator.
        m.put("library_directory", version.launcherRoot().resolve("libraries").toString());
        m.put("classpath_separator", java.io.File.pathSeparator);
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

    /**
     * Whether one entry's {@code rules} array lets an ARGUMENT through. Delegates to
     * {@link RuleEvaluator} so libraries and arguments can never disagree again about what a rule
     * means (they used to be two copies, and both ignored {@code os.arch}/{@code os.version}).
     *
     * <p>A rule with none of {@code os}/{@code features} matches unconditionally, and a feature we
     * never declared (any {@code is_quick_play_*} flag) counts as false -- which is what keeps the
     * quick-play argument variants off a normal launch.
     */
    private boolean argRulesPass(JsonArray rules, Map<String, Boolean> features) {
        return RuleEvaluator.allows(rules, RuleEvaluator.Platform.current(), features);
    }

    /** Testable core of {@link #argRulesPass}: the platform is supplied instead of read from the JVM. */
    static boolean argRulesPass(JsonArray rules, Map<String, Boolean> features, RuleEvaluator.Platform platform) {
        return RuleEvaluator.allows(rules, platform, features);
    }

    private String substitute(String token, Map<String, String> placeholders) {
        String out = token;
        for (var e : placeholders.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}
