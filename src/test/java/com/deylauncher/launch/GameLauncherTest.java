package com.deylauncher.launch;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.auth.MicrosoftAuth;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLauncherTest {

    @Test
    void softwareGlOnLinuxPrependsMesaEnvViaEnvBinary() {
        List<String> command = List.of("java", "-Xmx2048M", "net.minecraft.client.Main");
        List<String> out = GameLauncher.applySoftwareGl(command, true, "Linux");
        assertEquals(8, out.size());
        assertEquals("/usr/bin/env", out.get(0));
        assertEquals("LIBGL_ALWAYS_SOFTWARE=1", out.get(1));
        // GLVND dispatcher must be forced onto Mesa, otherwise NVIDIA libGL silently ignores
        // LIBGL_ALWAYS_SOFTWARE and the "software rendering" fallback does nothing (crash, exit 134).
        assertEquals("__GLX_VENDOR_LIBRARY_NAME=mesa", out.get(2));
        assertEquals("GALLIUM_DRIVER=llvmpipe", out.get(3));
        assertEquals("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe", out.get(4));
        assertEquals("java", out.get(5));
        assertEquals("net.minecraft.client.Main", out.get(7));
    }

    @Test
    void softwareGlOffLeavesCommandUntouched() {
        List<String> command = List.of("java", "-Xmx2048M");
        assertEquals(command, GameLauncher.applySoftwareGl(command, false, "Linux"));
    }

    @Test
    void softwareGlOnWindowsIsNoop() {
        List<String> command = List.of("java", "-Xmx2048M");
        assertEquals(command, GameLauncher.applySoftwareGl(command, true, "Windows 11"));
    }

    @Test
    void envPrefixedCommandStillExecutableShape() {
        // Sanity: the whole original command is preserved verbatim after the env assignments.
        List<String> command = List.of("/home/u/java/bin/java", "-XX:+UseG1GC", "com.deylauncher.x");
        List<String> out = GameLauncher.applySoftwareGl(command, true, "Linux");
        // Previous env prefix (5 entries: /usr/bin/env + 4 Mesa vars) then the original command verbatim.
        assertTrue(out.subList(5, out.size()).equals(command));
    }

    /**
     * The Wayland switch (Settings > Game) is a separate option from the software-renderer one, and the
     * compatibility constructors have to keep it OFF: the GUI rebuilds a settings object every time a single
     * control changes, so a caller written before this flag existed must not be able to switch Wayland on by
     * accident -- and a caller that does pass it must have it survive.
     */
    @Test
    void theTwoLinuxCompatibilitySwitchesAreIndependent() {
        GameLauncher.LaunchSettings classic = new GameLauncher.LaunchSettings(1024, 4096, 854, 480, false);
        assertFalse(classic.softwareOpenGl());
        assertFalse(classic.nativeWayland());

        GameLauncher.LaunchSettings softwareGlOnly =
                new GameLauncher.LaunchSettings(1024, 4096, 854, 480, false, true);
        assertTrue(softwareGlOnly.softwareOpenGl());
        assertFalse(softwareGlOnly.nativeWayland(), "an older caller must not switch Wayland on");

        GameLauncher.LaunchSettings waylandOnly =
                new GameLauncher.LaunchSettings(1024, 4096, 854, 480, false, false, true);
        assertTrue(waylandOnly.nativeWayland());
        assertFalse(waylandOnly.softwareOpenGl());

        // defaults() is what tests and headless callers get: neither workaround forced on.
        assertFalse(GameLauncher.LaunchSettings.defaults().nativeWayland());
    }

    /**
     * Forge 1.17+ profiles put ${library_directory} and ${classpath_separator} in their own JVM args
     * (a -DlibraryDirectory=..., and a -p module path built from them). They are NOT Mojang
     * placeholders, so nothing else supplies them: if they are missing, the literal "${...}" text is
     * handed to the JVM and Forge's BootstrapLauncher cannot find its modules, so the modded game dies
     * before it even starts.
     */
    @Test
    void forgeModulePathPlaceholdersAreSupplied() {
        Path root = Path.of("/tmp/deyroot");
        GameFiles.PreparedVersion prepared = forgeStyleVersion(root);
        Map<String, String> placeholders = new GameLauncher().buildPlaceholders(
                prepared, AuthSession.offline("tester"),
                root.resolve("instances").resolve("1.20.1-forge"),
                GameLauncher.LaunchSettings.defaults());

        assertEquals(root.resolve("libraries").toString(), placeholders.get("library_directory"));
        assertEquals(java.io.File.pathSeparator, placeholders.get("classpath_separator"));

        // Resolve the exact argument shapes the Forge 1.20.1 profile uses; nothing may survive.
        String modulePath = "-DlibraryDirectory=${library_directory}";
        String modules = "${library_directory}/cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar"
                + "${classpath_separator}${library_directory}/cpw/mods/securejarhandler/2.1.10/securejarhandler-2.1.10.jar";
        for (var entry : placeholders.entrySet()) {
            String token = "${" + entry.getKey() + "}";
            modulePath = modulePath.replace(token, entry.getValue());
            modules = modules.replace(token, entry.getValue());
        }

        assertFalse(modulePath.contains("${"), "an unresolved placeholder would reach the JVM: " + modulePath);
        assertFalse(modules.contains("${"), "an unresolved placeholder would reach the JVM: " + modules);
        assertEquals("-DlibraryDirectory=" + root.resolve("libraries"), modulePath);
        assertTrue(modules.contains(java.io.File.pathSeparator));
    }

    /**
     * The REAL 1.20.1 argument list -- the one that used to ship "--clientId ${clientid}" and
     * "--xuid ${auth_xuid}" to Minecraft on every single launch, because neither placeholder existed
     * in the substitution table. Nothing may survive unsubstituted now, and ${clientid} must be the
     * app id this launcher actually authenticates with.
     */
    @Test
    void realVanilla1201ArgumentsLeaveNoPlaceholderBehind() {
        Path root = Path.of("/tmp/deyroot");
        List<String> command = new GameLauncher().buildCommand(
                vanillaStyleVersion(root), AuthSession.offline("tester"),
                root.resolve("instances").resolve("1.20.1"),
                GameLauncher.LaunchSettings.defaults(), "java", null);

        assertEquals(List.of(), GameLauncher.unresolvedPlaceholders(command),
                "a ${...} token that reaches the game is a silent argument bug");
        assertFalse(command.contains("${clientid}"), "the literal placeholder must never be passed again");
        assertFalse(command.contains("${auth_xuid}"), "the literal placeholder must never be passed again");

        int clientIdAt = command.indexOf("--clientId");
        assertTrue(clientIdAt > 0, "--clientId is part of Mojang's own 1.19+ argument list");
        assertEquals(MicrosoftAuth.CLIENT_ID, command.get(clientIdAt + 1));

        int xuidAt = command.indexOf("--xuid");
        assertTrue(xuidAt > 0);
        assertEquals("", command.get(xuidAt + 1), "an offline session has no Xbox id, so it passes none");
    }

    /**
     * Mojang gates {@code -Xss1M} on {@code os.arch == "x86"}. The old evaluator only looked at the OS
     * name, so a rule that names no OS matched unconditionally and every 64-bit launch got a 1 MB
     * thread stack the official launcher never adds.
     */
    @Test
    void archGatedArgumentsAreJudgedOnTheRealArchitecture() {
        JsonArray rules = JsonParser
                .parseString("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]").getAsJsonArray();

        assertTrue(GameLauncher.argRulesPass(rules, Map.of(),
                new RuleEvaluator.Platform("Linux", "i686", "6.10.0")));
        assertFalse(GameLauncher.argRulesPass(rules, Map.of(),
                new RuleEvaluator.Platform("Linux", "amd64", "6.10.0")));
        assertFalse(GameLauncher.argRulesPass(rules, Map.of(),
                new RuleEvaluator.Platform("Windows 11", "aarch64", "10.0")));
    }

    /** A 64-bit launch must not carry -Xss1M at all (the rule above is its only source). */
    @Test
    void threadStackOverrideOnlyAppearsOn32BitHosts() {
        Path root = Path.of("/tmp/deyroot");
        List<String> command = new GameLauncher().buildCommand(
                vanillaStyleVersion(root), AuthSession.offline("tester"),
                root.resolve("instances").resolve("1.20.1"),
                GameLauncher.LaunchSettings.defaults(), "java", null);

        boolean is32BitHost = "x86".equals(RuleEvaluator.Platform.current().archKey());
        assertEquals(is32BitHost, command.contains("-Xss1M"),
                "-Xss1M is gated on os.arch \"x86\" in the version JSON: " + command);
    }

    /**
     * The JVM's own crash dump repeats the whole command line (access token included), so the
     * launcher pins it inside the instance where it can find, scrub and lock it down afterwards.
     */
    @Test
    void jvmCrashDumpIsWrittenIntoTheInstance() {
        String arg = GameLauncher.errorFileArg(Path.of("/tmp/dey/instances/1.20.1"));
        assertTrue(arg.startsWith("-XX:ErrorFile="), arg);
        assertTrue(arg.contains("crash-reports"), arg);
        assertTrue(arg.contains("%p"), "the JVM expands %p so two crashes can't overwrite each other");
    }

    /** The audit that makes this bug class visible: it names exactly which tokens never resolved. */
    @Test
    void unresolvedPlaceholderAuditNamesWhatLeaked() {
        assertEquals(List.of("clientid"),
                GameLauncher.unresolvedPlaceholders(List.of("--clientId", "${clientid}")));
        assertEquals(List.of("clientid", "something_new"),
                GameLauncher.unresolvedPlaceholders(List.of("${clientid}", "${something_new}")));
        assertEquals(List.of(), GameLauncher.unresolvedPlaceholders(List.of("--clientId", "abc", "-Xmx4G")));
    }

    /** A PreparedVersion shaped like real 1.20.1 (its argument list is why those tests exist). */
    private static GameFiles.PreparedVersion vanillaStyleVersion(Path root) {
        JsonObject versionJson = JsonParser.parseString("""
                {
                  "id": "1.20.1",
                  "type": "release",
                  "assetIndex": {"id": "5"},
                  "mainClass": "net.minecraft.client.main.Main",
                  "arguments": {
                    "jvm": [
                      {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
                      {"rules": [{"action": "allow", "os": {"name": "windows"}}],
                       "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
                      {"rules": [{"action": "allow", "os": {"arch": "x86"}}], "value": "-Xss1M"},
                      "-Djava.library.path=${natives_directory}",
                      "-Djna.tmpdir=${natives_directory}",
                      "-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}",
                      "-Dio.netty.native.workdir=${natives_directory}",
                      "-Dminecraft.launcher.brand=${launcher_name}",
                      "-Dminecraft.launcher.version=${launcher_version}",
                      "-cp", "${classpath}"
                    ],
                    "game": [
                      "--username", "${auth_player_name}",
                      "--version", "${version_name}",
                      "--gameDir", "${game_directory}",
                      "--assetsDir", "${assets_root}",
                      "--assetIndex", "${assets_index_name}",
                      "--uuid", "${auth_uuid}",
                      "--accessToken", "${auth_access_token}",
                      "--clientId", "${clientid}",
                      "--xuid", "${auth_xuid}",
                      "--userType", "${user_type}",
                      "--versionType", "${version_type}",
                      {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
                       "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]},
                      {"rules": [{"action": "allow", "features": {"is_quick_play_multiplayer": true}}],
                       "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]}
                    ]
                  }
                }
                """).getAsJsonObject();

        Path versionDir = root.resolve("versions").resolve("1.20.1");
        return new GameFiles.PreparedVersion(
                versionDir.resolve("1.20.1.jar"),
                List.of(root.resolve("libraries/com/mojang/brigadier/1.1.8/brigadier-1.1.8.jar")),
                versionDir.resolve("natives"),
                "net.minecraft.client.main.Main",
                versionJson,
                root);
    }

    /** A PreparedVersion shaped like a Forge 1.17+ one (its JVM args are the reason the test above exists). */
    private static GameFiles.PreparedVersion forgeStyleVersion(Path root) {
        JsonObject versionJson = new JsonObject();
        versionJson.addProperty("id", "1.20.1-forge-47.4.20");
        JsonObject assetIndex = new JsonObject();
        assetIndex.addProperty("id", "5");
        versionJson.add("assetIndex", assetIndex);

        Path versionDir = root.resolve("versions").resolve("1.20.1-forge-47.4.20");
        return new GameFiles.PreparedVersion(
                versionDir.resolve("1.20.1-forge-47.4.20.jar"),
                List.of(root.resolve("libraries/cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar")),
                versionDir.resolve("natives"),
                "cpw.mods.bootstraplauncher.BootstrapLauncher",
                versionJson,
                root);
    }
}