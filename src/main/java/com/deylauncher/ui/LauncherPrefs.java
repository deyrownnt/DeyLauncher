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
    // Edge gesture for borderless windows (Settings > Launcher > WINDOW EDGES):
    //   false (default) -> dragging to a screen edge keeps sliding the window outward, so it can be
    //                      parked partly/fully off any edge (needs a desktop that allows it: Windows,
    //                      or an X11 WM that permits off-screen windows).
    //   true            -> the desktop's own edge gesture: the left/right edge tiles the window to that
    //                      half of the work area and the top edge maximizes. Needed on desktops that
    //                      refuse to let a window leave the screen at all (e.g. KDE Plasma Wayland).
    public boolean edgeSnapInsteadOfPark = false;

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
    // Software-OpenGL compatibility (Settings > Game): on Linux machines without a usable OpenGL 3.3
    // GPU context, this launches Minecraft through Mesa's software renderer (LIBGL_ALWAYS_SOFTWARE).
    // Defaults off so healthy machines keep GPU acceleration.
    public boolean softwareOpenGl = false;
    // Native Wayland (Settings > Game): explicitly ask GLFW for Wayland while preserving DISPLAY for
    // Java/AWT-based mods. Defaults to ON for anyone running the
    // launcher inside a Wayland session -- that is the configuration whose XWayland window path can die in
    // native code during window creation, which no driver update or software renderer fixes. On X11
    // (DISPLAY without WAYLAND_DISPLAY) it defaults off and does nothing. See WaylandSupport.
    public boolean nativeWayland = defaultNativeWayland();

    // Friends / presence (Account tab, Settings > Launcher)
    public boolean invisibleMode = false;         // broadcast OFFLINE to friends even while actually online
    public boolean shareServerAddress = false;    // whether the server you're on is published to friends.json at all (pure opt-in -- the launcher never turns this on itself)
    /** Legacy field from the builds that auto-filled this with the last server you joined and then
     *  republished it as live presence (which is why a stale "playing on ..." could stick around
     *  forever). Presence is now built from the live game session only -- see
     *  LauncherApp.currentPresence -- and any leftover value here is cleared once at startup, so the
     *  field is kept purely so older prefs files still load. */
    public String myServerAddress = "";

    /**
     * Whether pressing PLAY first checks the active modpack's own file list and downloads back anything
     * that has gone missing (see ModpackVerifier). On by default: an incomplete pack launching into a
     * crash screen is never what anyone wants, and on a healthy instance the check is a few stat calls.
     */
    public boolean verifyModpackOnLaunch = true;

    /**
     * Whether this launcher is running inside a Wayland session -- i.e. whether starting the game natively
     * on Wayland should be on by default. Wayland sessions are the only ones where GLFW otherwise routes
     * window creation through XWayland, so an X11 user keeps the previous behaviour untouched.
     */
    public static boolean defaultNativeWayland() {
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        return waylandDisplay != null && !waylandDisplay.isBlank();
    }

    private static Path file() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "launcher.properties");
    }
    
    public static boolean exists() {
    return Files.exists(file());
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
            p.edgeSnapInsteadOfPark = Boolean.parseBoolean(props.getProperty("edgeSnapInsteadOfPark", "false"));
            p.settingsWindowWidth = parseDouble(props, "settingsWindowWidth", p.settingsWindowWidth);
            p.settingsWindowHeight = parseDouble(props, "settingsWindowHeight", p.settingsWindowHeight);
            p.lastVersionId = props.getProperty("lastVersionId", p.lastVersionId);
            p.lastDeyMode = Boolean.parseBoolean(props.getProperty("lastDeyMode", "true"));
            p.ramMinMb = (int) parseDouble(props, "ramMinMb", p.ramMinMb);
            p.ramMaxMb = (int) parseDouble(props, "ramMaxMb", p.ramMaxMb);
            p.gameWidth = (int) parseDouble(props, "gameWidth", p.gameWidth);
            p.gameHeight = (int) parseDouble(props, "gameHeight", p.gameHeight);
            p.fullscreen = Boolean.parseBoolean(props.getProperty("fullscreen", "false"));
            p.softwareOpenGl = Boolean.parseBoolean(props.getProperty("softwareOpenGl", "false"));
            // Deliberately NOT "false" as the fallback: on a Wayland session the default is on, so a prefs
            // file written before this option existed still gets the launch path that works. Storing the
            // key (Apply) makes the user's own choice stick either way.
            p.nativeWayland = Boolean.parseBoolean(
                    props.getProperty("nativeWayland", String.valueOf(defaultNativeWayland())));
            p.invisibleMode = Boolean.parseBoolean(props.getProperty("invisibleMode", "false"));
            p.shareServerAddress = Boolean.parseBoolean(props.getProperty("shareServerAddress", "false"));
            p.myServerAddress = props.getProperty("myServerAddress", "");
            p.verifyModpackOnLaunch = Boolean.parseBoolean(props.getProperty("verifyModpackOnLaunch", "true"));
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
            props.setProperty("edgeSnapInsteadOfPark", String.valueOf(edgeSnapInsteadOfPark));
            props.setProperty("settingsWindowWidth", String.valueOf(settingsWindowWidth));
            props.setProperty("settingsWindowHeight", String.valueOf(settingsWindowHeight));
            props.setProperty("lastVersionId", lastVersionId == null ? "" : lastVersionId);
            props.setProperty("lastDeyMode", String.valueOf(lastDeyMode));
            props.setProperty("ramMinMb", String.valueOf(ramMinMb));
            props.setProperty("ramMaxMb", String.valueOf(ramMaxMb));
            props.setProperty("gameWidth", String.valueOf(gameWidth));
            props.setProperty("gameHeight", String.valueOf(gameHeight));
            props.setProperty("fullscreen", String.valueOf(fullscreen));
            props.setProperty("softwareOpenGl", String.valueOf(softwareOpenGl));
            props.setProperty("nativeWayland", String.valueOf(nativeWayland));
            props.setProperty("invisibleMode", String.valueOf(invisibleMode));
            props.setProperty("shareServerAddress", String.valueOf(shareServerAddress));
            props.setProperty("myServerAddress", myServerAddress == null ? "" : myServerAddress);
            props.setProperty("verifyModpackOnLaunch", String.valueOf(verifyModpackOnLaunch));
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
