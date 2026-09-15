package com.deylauncher.update;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locks in the semantic choices that matter for the self-updater: {@code isNewer} must compare
 * dotted versions numerically (so 0.10.0 &gt; 0.2.0), tolerate a leading "v", and treat equal
 * versions as NOT new. Only the pure logic is tested here -- the GitHub network query is not.
 */
class AppUpdaterTest {

    @Test
    void isNewerComparesNumerically() {
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.0"));
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"));
        assertTrue(AppUpdater.isNewer("0.10.0", "0.2.0"));   // 10 > 2, not lexicographic
        assertTrue(AppUpdater.isNewer("26.2", "1.21.1"));    // missing parts are 0
    }

    @Test
    void isNewerToleratesLeadingV() {
        assertTrue(AppUpdater.isNewer("v0.3.0", "0.2.9"));
        assertTrue(AppUpdater.isNewer("v1.2.0", "0.1.0"));
    }

    @Test
    void equalOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.0"));
        assertFalse(AppUpdater.isNewer("0.1.0", "0.2.0"));
        assertFalse(AppUpdater.isNewer("1.2.0", "1.2.5"));
    }

    @Test
    void currentVersionReadsEmbeddedOrFallsBackToDottedConstant() {
        // The build bakes /deylauncher-version.properties from project.version, so this should be the
        // real release version. If the resource is ever absent (e.g. unusual launch), the fallback
        // must still look like a dotted version -- never an empty/blank string.
        assertTrue(AppUpdater.currentVersion().matches("[0-9]+(\\.[0-9]+){1,3}"));
    }

    @Test
    void embeddedVersionResourceIsPresentAndSanelyVersioned() {
        try (InputStream in = AppUpdater.class.getResourceAsStream("/deylauncher-version.properties")) {
            assertNotNull(in, "deylauncher-version.properties must be baked in by processResources");
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            assertNotNull(v, "resource must define 'version'");
            assertTrue(v.matches("[0-9]+(\\.[0-9]+){1,3}"), "version must be a dotted number: " + v);
            // Sanity: it should be a real release, not the old 0.1.0 starting point.
            assertTrue(AppUpdater.isNewer(v.trim(), "0.0.0"), "running version should be above 0.0.0");
        } catch (Exception ex) {
            fail("reading embedded version failed: " + ex);
        }
    }

    // ---- the restart helpers themselves ---------------------------------------
    // The Windows updater used to sleep a fixed 4 seconds and then xcopy over the top, which lost the
    // race against a still-running launcher (Windows keeps the exe, the app jar and jvm.dll locked) and
    // then silently relaunched the unchanged build. These lock in the shape of the fixed helper.

    private static AppUpdater.InstallLayout windowsLayout() {
        return new AppUpdater.InstallLayout(Path.of("C:\\Users\\Test\\Apps\\DeyLauncher"),
                Path.of("C:\\Users\\Test\\Apps\\DeyLauncher\\DeyLauncher.exe"), true);
    }

    @Test
    void windowsRestartScriptWaitsForTheOldProcessInsteadOfSleepingBlindly() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 4321L);
        assertTrue(s.contains("set \"OLD_PID=4321\""), s);
        assertTrue(s.contains("tasklist /FI \"PID eq %OLD_PID%\""), s);
        assertTrue(s.contains("if errorlevel 1 goto exited"), s);
        assertFalse(s.contains("timeout /t"),
                "a fixed sleep is exactly the Windows bug being fixed:\n" + s);
    }

    @Test
    void windowsRestartScriptLeavesTheInstallDirBeforeMovingIt() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        int cd = s.indexOf("cd /d \"%TEMP%\"");
        int move = s.indexOf("move \"%APP_DIR%\" \"%OLD%\"");
        assertTrue(cd >= 0, "the helper must leave the install dir, since Windows refuses to rename a live CWD:\n" + s);
        assertTrue(move > cd, "the cd must come BEFORE the install dir is moved:\n" + s);
    }

    @Test
    void windowsRestartScriptRenamesTheOldInstallAsideAndNeverMirrorsOverALiveOne() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        assertTrue(s.contains("move \"%APP_DIR%\" \"%OLD%\""), s);
        assertTrue(s.contains("move \"%STAGING%\" \"%APP_DIR%\""),
                "the staged image should be renamed into place when it's on the same volume:\n" + s);
        assertTrue(s.contains("robocopy \"%STAGING%\" \"%APP_DIR%\" /E"), s);
        assertFalse(s.contains("/MIR"),
                "mirroring over a still-locked install is what half-applied the update -- "
                        + "the new jar landed while DeyLauncher.cfg kept pointing at the old one:\n" + s);
        assertTrue(s.contains("goto lockedout"),
                "if the install dir can't be renamed aside, the helper must give up untouched:\n" + s);
    }

    @Test
    void windowsRestartScriptVerifiesTheCfgAndNotJustTheJar() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        assertTrue(s.contains("%APP_DIR%\\app\\%EXPECT_JAR%"), s);
        // app\DeyLauncher.cfg carries the classpath: a new jar next to a stale cfg still boots the
        // OLD build, which is exactly the "updated but same version" symptom.
        assertTrue(s.contains("find /I \"%EXPECT_JAR%\" \"%APP_DIR%\\app\\DeyLauncher.cfg\""), s);
        assertTrue(s.contains("if errorlevel 1 goto rollback"), s);
    }

    @Test
    void windowsRestartScriptOnlyDeletesTheInstallWhenABackupExists() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        int guard = s.indexOf("if not exist \"%OLD%\\DeyLauncher.exe\" goto rollbackdone");
        int wipe = s.indexOf("rd /s /q \"%APP_DIR%\"");
        assertTrue(guard >= 0, "rollback must check for a backup first:\n" + s);
        assertTrue(wipe > guard, "the install dir must never be wiped without a backup to restore:\n" + s);
        assertTrue(s.contains("move \"%OLD%\" \"%APP_DIR%\""), s);
        assertTrue(s.contains("start \"\" /D \"%APP_DIR%\" \"%APP_DIR%\\DeyLauncher.exe\""), s);
    }

    @Test
    void windowsRestartScriptOnlyJumpsToLabelsThatExist() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        // Catches both the bare `goto X` and the conditional `if ... goto X` forms, and also flags a
        // label nothing jumps to -- a typo in either direction would silently break the update.
        Set<String> targets = new TreeSet<>();
        Matcher jumps = Pattern.compile("(?<![A-Za-z0-9_])goto +([A-Za-z0-9_]+)").matcher(s);
        while (jumps.find()) targets.add(jumps.group(1));
        Set<String> labels = new TreeSet<>();
        for (String line : s.split("\r\n")) {
            if (line.startsWith(":") && line.length() > 1) labels.add(line.substring(1).trim());
        }
        assertFalse(targets.isEmpty(), "no goto statements found -- did the script shape change?\n" + s);
        assertEquals(labels, targets, "every goto needs a label, and every label a goto:\n" + s);
    }

    @Test
    void windowsRestartScriptUsesCrlfLineEndings() {
        String s = AppUpdater.restartScript(windowsLayout(),
                Path.of("C:\\Temp\\dl-extract\\DeyLauncher"), Path.of("C:\\Temp\\dl-extract"), 7L);
        assertTrue(s.contains("\r\n"), "cmd.exe wants CRLF:\n" + s);
        assertFalse(Pattern.compile("(?<!\r)\n").matcher(s).find(),
                "every LF must be part of a CRLF pair:\n" + s);
    }

    @Test
    void unixRestartScriptStillWaitsOnThePidWithLfEndings() {
        AppUpdater.InstallLayout linux = new AppUpdater.InstallLayout(Path.of("/opt/DeyLauncher"),
                Path.of("/opt/DeyLauncher/bin/DeyLauncher"), false);
        String s = AppUpdater.restartScript(linux, Path.of("/tmp/dl/DeyLauncher"), Path.of("/tmp/dl"), 99L);
        assertTrue(s.contains("kill -0 \"$OLD_PID\""), s);
        assertFalse(s.contains("\r\n"), "the .sh helper must stay LF-only:\n" + s);
    }
}
