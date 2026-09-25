package com.deylauncher.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps the launcher's own JVM decisions from being silently overridden by the environment the game's
 * JVM inherits.
 *
 * <p>Three environment variables make the JVM read extra options of its own, and they do <b>not</b> all
 * behave the same way:
 * <ul>
 *   <li>{@code JAVA_TOOL_OPTIONS} and {@code JDK_JAVA_OPTIONS} are <b>prepended</b>, so a {@code -D} on
 *       the command line still wins.</li>
 *   <li>{@code _JAVA_OPTIONS} is applied <b>after</b> the command line (the JVM prints "Picked up
 *       _JAVA_OPTIONS: ..."), so it <b>overrides</b> it. Measured on a KDE/Wayland machine: with
 *       {@code _JAVA_OPTIONS=-Djava.awt.headless=true} and {@code -Djava.awt.headless=false} on the
 *       command line, {@code java.awt.headless} came out <b>true</b> and an AWT/Swing window threw
 *       {@code HeadlessException} -- the launcher's own flag was powerless. "Java on this desktop"
 *       guides hand out exactly that variable, which is why this launcher's early attempts to pin the
 *       flag (and to stop pinning it) could both end in the same crash.</li>
 * </ul>
 *
 * <p>So the game's environment gets the {@code java.awt.headless} setting taken out of those variables,
 * and nothing else: every other option the user put there is passed through untouched. That is what
 * makes the per-launch decision in {@link GameLauncher#awtHeadlessMode} the one that actually counts.
 * Deliberately platform-independent -- the same override would defeat a Windows launch too.
 */
public final class JvmEnvOptions {

    /** The variables the JVM itself reads for extra options. Package-private for tests/diagnostics. */
    static final List<String> JVM_OPTION_VARS = List.of("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS");

    /** The property AWT reads to decide whether this process has a display. */
    private static final String HEADLESS = "-Djava.awt.headless";

    private JvmEnvOptions() {}

    /**
     * Removes any {@code -Djava.awt.headless} token from those variables in the given child environment,
     * leaving everything else in them exactly as it was. Returns the names of the variables it changed
     * (for the launch log), or {@code null} when there was nothing to change.
     */
    public static String dropAwtHeadlessOverrides(Map<String, String> childEnv) {
        if (childEnv == null) return null;
        List<String> changed = new ArrayList<>();
        for (String name : JVM_OPTION_VARS) {
            String value = childEnv.get(name);
            if (value == null || value.isBlank() || !mentionsHeadless(value)) continue;
            String cleaned = stripHeadless(value);
            if (cleaned == null) {
                childEnv.remove(name);
            } else {
                childEnv.put(name, cleaned);
            }
            changed.add(name);
        }
        return changed.isEmpty() ? null : String.join(", ", changed);
    }

    /**
     * The name of the first of those variables that asks for headless AWT, or {@code null}. Used to
     * explain a headless crash in plain words when it happens anyway (see {@code LaunchDiagnostics}).
     */
    public static String awtHeadlessOverride(Map<String, String> env) {
        if (env == null) return null;
        for (String name : JVM_OPTION_VARS) {
            String value = env.get(name);
            if (value != null && mentionsHeadless(value)) return name;
        }
        return null;
    }

    /** Whether a value carries the headless property, as {@code -Djava.awt.headless} or with {@code =…}. */
    static boolean mentionsHeadless(String value) {
        for (String token : value.split("\\s+")) {
            if (token.equals(HEADLESS) || token.startsWith(HEADLESS + "=")) return true;
        }
        return false;
    }

    /** The same value without its headless tokens, or {@code null} when nothing else was left in it. */
    static String stripHeadless(String value) {
        StringBuilder out = new StringBuilder();
        for (String token : value.split("\\s+")) {
            if (token.isBlank() || token.equals(HEADLESS) || token.startsWith(HEADLESS + "=")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.length() == 0 ? null : out.toString();
    }
}