package com.deylauncher.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Everything under the new "Launcher" settings tab, persisted to
 * ~/.deylauncher/launcher.properties so it's remembered next time the app
 * opens -- including the window size itself, which is the whole point of
 * "at what size will the app open."
 */
public class LauncherPrefs {

    public double uiScale = 1.0;     // affects padding/spacing/control sizing
    public double textScale = 1.0;   // affects font sizes only
    public String fontFamily = "Segoe UI";
    public double startWidth = 1280;
    public double startHeight = 820;
    public boolean rememberWindowSize = true;
    public boolean darkMode = true;
    public boolean launcherStartFullscreen = false; // the LAUNCHER's own window, not the game's

    // The unified Settings/Account/Skins dialog's own remembered size -- separate from the
    // main launcher window's startWidth/startHeight above.
    public double settingsWindowWidth = 1400;
    public double settingsWindowHeight = 900;

    // Remembered so the launcher reopens on whatever you last actually played, instead of
    // always resetting to the first tile.
    public String lastVersionId = "";
    public boolean lastDeyMode = true;

    // Game settings (Settings > Game), persisted here so they survive a restart -- previously
    // these lived only in LauncherApp's in-memory GameLauncher.LaunchSettings and were lost
    // every time the app closed.
    public int ramMinMb = 1024;
    public int ramMaxMb = 4096;
    public int gameWidth = 854;
    public int gameHeight = 480;
    public boolean fullscreen = false;

    // Friends / presence (Account tab, Settings > Launcher)
    public boolean invisibleMode = false;         // broadcast OFFLINE to friends even while actually online
    public boolean shareServerAddress = false;    // whether myServerAddress is published to friends.json at all
    public String myServerAddress = "";           // manually entered -- DeyLauncher can't detect this automatically

    private static Path file() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "launcher.properties");
    }

    public static LauncherPrefs load() {
        LauncherPrefs p = new LauncherPrefs();
        Path f = file();
        if (!Files.exists(f)) return p;
        try (InputStream in = Files.newInputStream(f)) {
            Properties props = new Properties();
            props.load(in);
            p.uiScale = parseDouble(props, "uiScale", p.uiScale);
            p.textScale = parseDouble(props, "textScale", p.textScale);
            p.fontFamily = props.getProperty("fontFamily", p.fontFamily);
            p.startWidth = parseDouble(props, "startWidth", p.startWidth);
            p.startHeight = parseDouble(props, "startHeight", p.startHeight);
            p.rememberWindowSize = Boolean.parseBoolean(props.getProperty("rememberWindowSize", "true"));
            p.darkMode = Boolean.parseBoolean(props.getProperty("darkMode", "true"));
            p.launcherStartFullscreen = Boolean.parseBoolean(props.getProperty("launcherStartFullscreen", "false"));
            p.settingsWindowWidth = parseDouble(props, "settingsWindowWidth", p.settingsWindowWidth);
            p.settingsWindowHeight = parseDouble(props, "settingsWindowHeight", p.settingsWindowHeight);
            p.lastVersionId = props.getProperty("lastVersionId", p.lastVersionId);
            p.lastDeyMode = Boolean.parseBoolean(props.getProperty("lastDeyMode", "true"));
            p.ramMinMb = (int) parseDouble(props, "ramMinMb", p.ramMinMb);
            p.ramMaxMb = (int) parseDouble(props, "ramMaxMb", p.ramMaxMb);
            p.gameWidth = (int) parseDouble(props, "gameWidth", p.gameWidth);
            p.gameHeight = (int) parseDouble(props, "gameHeight", p.gameHeight);
            p.fullscreen = Boolean.parseBoolean(props.getProperty("fullscreen", "false"));
            p.invisibleMode = Boolean.parseBoolean(props.getProperty("invisibleMode", "false"));
            p.shareServerAddress = Boolean.parseBoolean(props.getProperty("shareServerAddress", "false"));
            p.myServerAddress = props.getProperty("myServerAddress", "");
        } catch (IOException ignored) {
            // Missing/corrupt prefs file just means "use defaults" -- not worth failing startup over.
        }
        return p;
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Properties props = new Properties();
            props.setProperty("uiScale", String.valueOf(uiScale));
            props.setProperty("textScale", String.valueOf(textScale));
            props.setProperty("fontFamily", fontFamily);
            props.setProperty("startWidth", String.valueOf(startWidth));
            props.setProperty("startHeight", String.valueOf(startHeight));
            props.setProperty("rememberWindowSize", String.valueOf(rememberWindowSize));
            props.setProperty("darkMode", String.valueOf(darkMode));
            props.setProperty("launcherStartFullscreen", String.valueOf(launcherStartFullscreen));
            props.setProperty("settingsWindowWidth", String.valueOf(settingsWindowWidth));
            props.setProperty("settingsWindowHeight", String.valueOf(settingsWindowHeight));
            props.setProperty("lastVersionId", lastVersionId == null ? "" : lastVersionId);
            props.setProperty("lastDeyMode", String.valueOf(lastDeyMode));
            props.setProperty("ramMinMb", String.valueOf(ramMinMb));
            props.setProperty("ramMaxMb", String.valueOf(ramMaxMb));
            props.setProperty("gameWidth", String.valueOf(gameWidth));
            props.setProperty("gameHeight", String.valueOf(gameHeight));
            props.setProperty("fullscreen", String.valueOf(fullscreen));
            props.setProperty("invisibleMode", String.valueOf(invisibleMode));
            props.setProperty("shareServerAddress", String.valueOf(shareServerAddress));
            props.setProperty("myServerAddress", myServerAddress == null ? "" : myServerAddress);
            try (OutputStream out = Files.newOutputStream(file())) {
                props.store(out, "DeyLauncher preferences");
            }
        } catch (IOException ignored) {
            // Best-effort -- worst case, settings just don't persist to next run.
        }
    }

    private static double parseDouble(Properties props, String key, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
