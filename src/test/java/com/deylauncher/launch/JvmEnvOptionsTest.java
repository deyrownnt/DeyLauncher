package com.deylauncher.launch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmEnvOptionsTest {

    @Test
    void headlessTokensAreRemovedAndEverythingElseKept() {
        assertEquals("-Xmx2g -XX:+UseG1GC",
                JvmEnvOptions.stripHeadless("-Xmx2g -Djava.awt.headless=true -XX:+UseG1GC"));
        assertEquals("-Xmx2g -XX:+UseG1GC",
                JvmEnvOptions.stripHeadless("-Djava.awt.headless=false -Xmx2g -XX:+UseG1GC"));
        assertEquals("-Xmx2g", JvmEnvOptions.stripHeadless("  -Djava.awt.headless=true   -Xmx2g  "));
        // A variable with nothing left in it goes away entirely instead of becoming an empty JVM option.
        assertNull(JvmEnvOptions.stripHeadless("-Djava.awt.headless=true"));
        assertNull(JvmEnvOptions.stripHeadless("-Djava.awt.headless"));
    }

    @Test
    void onlyTheHeadlessPropertyItselfIsMatched() {
        assertTrue(JvmEnvOptions.mentionsHeadless("-Djava.awt.headless=true"));
        assertTrue(JvmEnvOptions.mentionsHeadless("-Xmx1g -Djava.awt.headless"));
        // A different property that merely starts with the same text must not be touched.
        assertFalse(JvmEnvOptions.mentionsHeadless("-Djava.awt.headlessness=true"));
        assertFalse(JvmEnvOptions.mentionsHeadless("-Djava.awt.headless.foo=true"));
        assertFalse(JvmEnvOptions.mentionsHeadless("-Xmx1g"));
    }

    @Test
    void theEnvironmentOverrideIsReportedByVariableName() {
        assertEquals("_JAVA_OPTIONS",
                JvmEnvOptions.awtHeadlessOverride(Map.of("_JAVA_OPTIONS", "-Djava.awt.headless=true")));
        assertEquals("JDK_JAVA_OPTIONS",
                JvmEnvOptions.awtHeadlessOverride(Map.of("JDK_JAVA_OPTIONS", "-Djava.awt.headless=true")));
        assertNull(JvmEnvOptions.awtHeadlessOverride(Map.of("_JAVA_OPTIONS", "-Xmx1g")));
        assertNull(JvmEnvOptions.awtHeadlessOverride(null));
    }

    /**
     * The real case, measured on a KDE/Wayland machine: {@code _JAVA_OPTIONS} is applied AFTER the command
     * line, so a {@code -Djava.awt.headless=true} in it beat the {@code -Djava.awt.headless=false} the
     * launcher pinned, and the mod's Swing window threw {@code HeadlessException} anyway. The child's
     * environment must no longer carry that setting, while every other option in those variables survives
     * untouched (they are the user's settings, not ours to drop).
     */
    @Test
    void theChildsEnvironmentLosesTheOverrideAndKeepsEverythingElse() {
        Map<String, String> childEnv = new HashMap<>();
        childEnv.put("_JAVA_OPTIONS", "-Djava.awt.headless=true -Dsun.java2d.uiScale=1");
        childEnv.put("JAVA_TOOL_OPTIONS", "-Xss2m");
        childEnv.put("PATH", "/usr/bin");

        assertEquals("_JAVA_OPTIONS", JvmEnvOptions.dropAwtHeadlessOverrides(childEnv));
        assertEquals("-Dsun.java2d.uiScale=1", childEnv.get("_JAVA_OPTIONS"));
        assertEquals("-Xss2m", childEnv.get("JAVA_TOOL_OPTIONS"), "an unrelated variable is untouched");
        assertEquals("/usr/bin", childEnv.get("PATH"));

        // With nothing but the headless token in it, the variable is removed altogether (an empty
        // _JAVA_OPTIONS would still make every launch print a "Picked up" line).
        Map<String, String> onlyToken = new HashMap<>();
        onlyToken.put("_JAVA_OPTIONS", "-Djava.awt.headless=true");
        assertEquals("_JAVA_OPTIONS", JvmEnvOptions.dropAwtHeadlessOverrides(onlyToken));
        assertFalse(onlyToken.containsKey("_JAVA_OPTIONS"));

        // Two variables at once are both reported and both cleaned.
        Map<String, String> two = new HashMap<>();
        two.put("_JAVA_OPTIONS", "-Djava.awt.headless=true");
        two.put("JDK_JAVA_OPTIONS", "-Djava.awt.headless=true -Xmx3g");
        assertEquals("_JAVA_OPTIONS, JDK_JAVA_OPTIONS", JvmEnvOptions.dropAwtHeadlessOverrides(two));
        assertEquals("-Xmx3g", two.get("JDK_JAVA_OPTIONS"));

        // A clean environment is left exactly as it is.
        Map<String, String> clean = new HashMap<>();
        clean.put("JAVA_TOOL_OPTIONS", "-Xss2m");
        assertNull(JvmEnvOptions.dropAwtHeadlessOverrides(clean));
        assertEquals("-Xss2m", clean.get("JAVA_TOOL_OPTIONS"));
        assertNull(JvmEnvOptions.dropAwtHeadlessOverrides(null));
    }
}