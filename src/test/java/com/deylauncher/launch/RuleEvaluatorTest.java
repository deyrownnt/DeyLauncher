package com.deylauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mojang's rule language. The gap this class closes was real: {@code os.arch} was ignored, so the
 * vanilla {@code -Xss1M} rule ("only on 32-bit") matched on 64-bit machines, and an ARM64 host would
 * have been handed x86 entries.
 */
class RuleEvaluatorTest {

    private static final RuleEvaluator.Platform LINUX_X64 = new RuleEvaluator.Platform("Linux", "amd64", "6.10.0");
    private static final RuleEvaluator.Platform LINUX_X86 = new RuleEvaluator.Platform("Linux", "i686", "6.10.0");
    private static final RuleEvaluator.Platform WINDOWS_11 = new RuleEvaluator.Platform("Windows 11", "amd64", "10.0");
    private static final RuleEvaluator.Platform WINDOWS_ARM = new RuleEvaluator.Platform("Windows 11", "aarch64", "10.0");

    private static JsonArray rules(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void x86GatedEntryDoesNotMatchA64BitHost() {
        JsonArray rule = rules("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]");
        assertTrue(RuleEvaluator.allows(rule, LINUX_X86, Map.of()));
        assertFalse(RuleEvaluator.allows(rule, LINUX_X64, Map.of()));
        assertFalse(RuleEvaluator.allows(rule, WINDOWS_11, Map.of()));
    }

    @Test
    void arm64EntriesOnlyMatchArm64() {
        JsonArray rule = rules("[{\"action\":\"allow\",\"os\":{\"arch\":\"arm64\"}}]");
        assertTrue(RuleEvaluator.allows(rule, WINDOWS_ARM, Map.of()));
        assertFalse(RuleEvaluator.allows(rule, WINDOWS_11, Map.of()));
    }

    @Test
    void osNameAndArchHaveToMatchTogether() {
        JsonArray linuxX64 = rules("[{\"action\":\"allow\",\"os\":{\"name\":\"linux\",\"arch\":\"x86_64\"}}]");
        assertTrue(RuleEvaluator.allows(linuxX64, LINUX_X64, Map.of()));
        assertFalse(RuleEvaluator.allows(linuxX64, WINDOWS_11, Map.of()));

        JsonArray windowsOnly = rules("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}]");
        assertTrue(RuleEvaluator.allows(windowsOnly, WINDOWS_ARM, Map.of()));
        assertFalse(RuleEvaluator.allows(windowsOnly, LINUX_X64, Map.of()));
    }

    /** Mojang's {@code os.version} is a regex, not a literal (Windows-only entries ship "^10\\."). */
    @Test
    void osVersionIsMatchedAsARegex() {
        JsonArray windows10 = rules("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\",\"version\":\"^10\\\\.\"}}]");
        assertTrue(RuleEvaluator.allows(windows10,
                new RuleEvaluator.Platform("Windows 11", "amd64", "10.0"), Map.of()),
                "Windows 10 and 11 both report 10.0, which is exactly what the regex is for");
        assertFalse(RuleEvaluator.allows(windows10,
                new RuleEvaluator.Platform("Windows 7", "amd64", "6.1"), Map.of()));
        assertFalse(RuleEvaluator.allows(windows10,
                new RuleEvaluator.Platform("Windows 11", "amd64", ""), Map.of()),
                "no OS version at all must not match a version-gated entry");
    }

    @Test
    void noRulesMeansUsableEverywhere() {
        assertTrue(RuleEvaluator.allows(null, LINUX_X64, Map.of()));
        assertTrue(RuleEvaluator.allows(new JsonArray(), LINUX_X64, Map.of()));
    }

    /** A rule with neither os nor features matches unconditionally -- unless it says "disallow". */
    @Test
    void disallowAndLastMatchWins() {
        assertTrue(RuleEvaluator.allows(rules("[{\"action\":\"allow\"}]"), LINUX_X64, Map.of()));
        assertFalse(RuleEvaluator.allows(rules("[{\"action\":\"disallow\"}]"), LINUX_X64, Map.of()));
        // allow, then a disallow that matches -> the later rule decides
        assertFalse(RuleEvaluator.allows(
                rules("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"linux\"}}]"),
                LINUX_X64, Map.of()));
    }

    /** An undeclared feature is false, which is what keeps quick-play arguments off a normal launch. */
    @Test
    void featuresMustMatchAndUndeclaredOnesAreFalse() {
        JsonArray quickPlay = rules("[{\"action\":\"allow\",\"features\":{\"is_quick_play_multiplayer\":true}}]");
        assertFalse(RuleEvaluator.allows(quickPlay, LINUX_X64, Map.of()));
        assertFalse(RuleEvaluator.allows(quickPlay, LINUX_X64, Map.of("is_quick_play_multiplayer", false)));
        assertTrue(RuleEvaluator.allows(quickPlay, LINUX_X64, Map.of("is_quick_play_multiplayer", true)));
    }

    /** An unknown arch string never matches, so a machine is never handed the wrong binaries. */
    @Test
    void unknownArchitectureInARuleNeverMatches() {
        assertFalse(RuleEvaluator.allows(rules("[{\"action\":\"allow\",\"os\":{\"arch\":\"sparc\"}}]"),
                LINUX_X64, Map.of()));
        assertFalse(RuleEvaluator.archMatches("sparc", "x86_64"));
        assertFalse(RuleEvaluator.archMatches("x86_64", "")); // host arch unknown -> fail closed
    }
}
