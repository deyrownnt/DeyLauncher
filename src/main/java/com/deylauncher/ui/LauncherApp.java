package com.deylauncher.ui;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.auth.MicrosoftAuth;
import com.deylauncher.auth.TokenVault;
import com.deylauncher.friends.*;
import com.deylauncher.deycapes.DeyCapesService;
import com.deylauncher.identity.*;
import com.deylauncher.launch.CrashDumps;
import com.deylauncher.launch.DownloadProgress;
import com.deylauncher.launch.GameFiles;
import com.deylauncher.launch.GameLauncher;
import com.deylauncher.launch.JavaRuntimeManager;
import com.deylauncher.launch.LaunchDiagnostics;
import com.deylauncher.launch.ServerAddressMatch;
import com.deylauncher.launch.ServerSessionTracker;
import com.deylauncher.launch.WaylandSupport;
import com.deylauncher.modloader.FabricInstaller;
import com.deylauncher.modloader.FabricApiInstaller;
import com.deylauncher.modloader.ForgeInstaller;
import com.deylauncher.modloader.NeoForgeInstaller;
import com.deylauncher.modloader.SodiumInstaller;
import com.deylauncher.modloader.IrisInstaller;
import com.deylauncher.modloader.DeyCapesInstaller;
import com.deylauncher.modloader.ModPairResolver;
import com.deylauncher.modloader.ModsUtil;
import com.deylauncher.modpack.*;
import com.deylauncher.modrepair.InstalledModResolver;
import com.deylauncher.server.*;
import com.deylauncher.update.AppUpdater;
import com.deylauncher.platform.PointerProbe;
import com.google.gson.JsonObject;
import com.deylauncher.version.VersionManifest;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBase;
import javafx.scene.effect.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import javafx.scene.input.TransferMode;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import com.google.gson.Gson;

/**
 * Phase 3: the real window. Still wired to offline test mode underneath
 * (see Main.USE_MICROSOFT_AUTH) -- this is a UI layer on top of exactly
 * the same GameFiles/GameLauncher classes the console version used, so
 * everything that worked in console mode works here too.
 */
public class LauncherApp extends Application {

    private GameLauncher.LaunchSettings settings = GameLauncher.LaunchSettings.defaults();
    private LauncherPrefs prefs;
    private boolean darkMode = true;
    private TextArea logArea;
    private ComboBox<String> versionBox;
    private ComboBox<String> modLoaderBox;
    private Button modsBtn;
    private Button modpackBtn;   // icon button beside the DEY/VANILLA switch -- the modpack menu
    private Button playButton;
    private WaveLaunchBar launchProgress;
    private Scene scene;
    private Stage stage;
    private final GameFiles gameFiles = new GameFiles(); // just for .root -- no I/O until prepare() is called
    private IdentityStore identityStore;
    private String liveOnlineAccessToken; // in-memory only, never persisted -- see openPreferencesDialog()
    private String liveOnlineAccountUuid; // which account liveOnlineAccessToken actually belongs to
    private Label accountStatusNotice;
    private FriendsService friendsService; // null until github.properties/embedded config is set -- see GitHubConfig
    private DeyCapesService deyCapesService; // null until github config is set -- Dey capes are a github-backed feature
    private com.deylauncher.optionskits.OptionsKitsRepository optionsKitsRepository; // null until github config is set
    private FriendsCache friendsCache;
    private FriendNotesStore friendNotes; // per-friend personal notes, saved only on this PC
    private ServerStore serverStore;
    private AddedServersStore addedServersStore;
    private final java.util.Map<String, ServerProcessManager> runningServers = new java.util.HashMap<>(); // serverId -> process manager, for whatever's live this session
    private final java.util.Map<String, PlayitTunnel> serverTunnels = new java.util.HashMap<>(); // serverId -> playit agent for Internet sharing this session

    // Modrinth enrichment caches for installed addons/mods: display-name -> slug, slug -> local icon path.
    private final java.util.Map<String, String> addonSlugByBase = new java.util.HashMap<>();
    private final java.util.Map<String, String> modrinthIconCache = new java.util.HashMap<>(); // slug -> icon path ("" = known-none)
    // Online-player UUID lookup: player name (as parsed from console) -> UUID (for avatars).
    private final java.util.Map<String, String> playerUuidByName = new java.util.HashMap<>();

    // Presence staleness: a friend who shows status=ONLINE but hasn't heartbeated within this long
    // is treated as OFFLINE (their launcher force-closed / crashed, so their heartbeat stopped).
    // Keep it a little over 2x the presence heartbeat period (see presenceHeartbeat()).
    private static final long PRESENCE_STALE_MS = 120_000L;
    private static final long PRESENCE_HEARTBEAT_MS = 60_000L;
    private static final String PRESENCE_GRACEFUL_MARKER = "presence-graceful.txt";
    private volatile boolean appRunning = true;

    // ---- Live play state: what friends are told we're doing, derived ONLY from a game process this
    // launcher itself launched (see ServerSessionTracker). Deliberately NOT persisted anywhere -- an
    // address remembered on disk used to be republished forever, so friends kept seeing "playing on
    // <server>" from a launcher with no game open at all, and a single-player session looked the same
    // as being on that server. Written on the FX thread (applySessionEvent), read from the presence
    // heartbeat thread.
    private volatile PlayState livePlayState = PlayState.IN_LAUNCHER;
    private volatile String liveServerAddress;  // non-null only while livePlayState == SERVER
    private volatile String liveServerName;     // friendly name resolved when the join was announced
    /** How often a live game session checks whether a pending "leave" has timed out (see ServerSessionTracker.poll). */
    private static final long PRESENCE_TICK_MS = 2_000L;

    // ---- Account tab: the "share my server with friends" gold card ----
    // Held so its status line can be refreshed the instant the live session (or invisible mode)
    // changes, instead of only when the Account tab happens to be rebuilt. Null until the card has
    // been built once; a stale runnable pointing at a closed window's labels is harmless.
    private Runnable shareCardSync;

    // Dots currently being wave-pulsed (see WavePulse) -- unregistered when the containing page
    // re-renders so the animation engine never animates detached nodes.
    private final java.util.Set<Node> wavePulseDots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Main-page account button (face + name + online/offline dot) -- see buildAccountButton()/refreshAccountButton()
    private Button accountBtn;
    private ImageView accountBtnFace;
    private Label accountBtnName;
    private Region accountBtnDot;
    private Image selectedCapeImage;
    private String selectedCapeId;
    private String equippedCapeId;
    private boolean capeDirty;

    // ---- Top nav (Home / Library / Servers) ----
    private Button navHomeBtn, navFriendsBtn, navServersBtn;
    private StackPane pageHost;
    private Node mainPageRoot;

    // ---- Main-window custom chrome (borderless stage) + self-updater ----
    private javafx.scene.layout.BorderPane root;      // the themed content root of the main window
    private javafx.scene.layout.StackPane windowStack; // scene root: lets an overlay cover everything
    private HBox topBar;                              // drag handle for the undecorated main window
    private Button updateBtn;                        // orange download icon, shown only when an update is available
    private AppUpdater.UpdateInfo updateInfo;        // detected update waiting to be installed
    private javafx.scene.layout.StackPane updateOverlay;
    private ProgressBar updateProgress;
    private Label updateStatus;

    // ---- Shared "secondary" borderless windows (Settings / Server Management / Change Version) ----
    // All open like the Mods window (borderless + themed + draggable + resizable). Each opens on
    // its own over the launcher -- opening one does NOT reopen the others. Re-opening one that's
    // already open just focuses it instead of spawning a duplicate.
    private final java.util.LinkedHashMap<String, javafx.stage.Stage> shellWindows = new java.util.LinkedHashMap<>();

    // ---- Main page: VANILLA / DEY mode toggle + version-filter tiles ----
    /** How a tile narrows the full version list down to what shows in the Version dropdown. */
    private enum FilterKind { ALL, MIN_MAJOR, FAMILY }

    /**
     * One left-column tile. kind/matchValue decide which versions from allVersions show up:
     *  ALL        -- every version, no filtering.
     *  MIN_MAJOR  -- versions whose leading version number is >= matchValue (e.g. "26" means 26+).
     *  FAMILY     -- versions whose id is exactly matchValue or starts with matchValue + "." / "-"
     *                (e.g. "1.21" matches "1.21", "1.21.1", "1.21-rc1", but not "1.210").
     * releaseOnly, when true, additionally drops anything whose manifest type isn't "release".
     */
    private record VersionPreset(String label, FilterKind kind, String matchValue, boolean releaseOnly) {}

    private static final VersionPreset[] VANILLA_PRESETS = {
            new VersionPreset("26", FilterKind.MIN_MAJOR, "26", false),
            new VersionPreset("1.21", FilterKind.FAMILY, "1.21", false),
            new VersionPreset("1.20", FilterKind.FAMILY, "1.20", false),
            new VersionPreset("All Versions", FilterKind.ALL, null, false),
    };
    private static final VersionPreset[] DEY_PRESETS = {
            new VersionPreset("26", FilterKind.MIN_MAJOR, "26", true),
            new VersionPreset("1.21", FilterKind.FAMILY, "1.21", true),
            new VersionPreset("1.20", FilterKind.FAMILY, "1.20", true),
            new VersionPreset("1.19", FilterKind.FAMILY, "1.19", true),
            new VersionPreset("1.18", FilterKind.FAMILY, "1.18", true),
            new VersionPreset("1.17", FilterKind.FAMILY, "1.17", true),
            new VersionPreset("1.16", FilterKind.FAMILY, "1.16", true),
    };
    // "26.2" isn't a real Mojang id (see the note in Main.java) -- kept as a synthetic seed so
    // the "26"/"26+" filters have something to show before Mojang actually ships 26.x for real.
    private static final VersionManifest.VersionEntry SYNTHETIC_26 =
            new VersionManifest.VersionEntry("26.2", "release", null, null);

    private final List<VersionManifest.VersionEntry> allVersions = new ArrayList<>(List.of(SYNTHETIC_26));
    private boolean deyMode = false; // false = VANILLA, true = DEY
    private VersionPreset activePreset;
    private ToggleButton vanillaModeBtn, deyModeBtn;
    private VBox versionTileList;
    private Label mainHeadingLabel;
    private Label mainDescriptionLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.prefs = LauncherPrefs.load();
        boolean firstLaunch = !LauncherPrefs.exists();
        this.darkMode = prefs.darkMode;
        this.identityStore = new IdentityStore(gameFiles.root);
        this.settings = new GameLauncher.LaunchSettings(prefs.ramMinMb, prefs.ramMaxMb,
                prefs.gameWidth, prefs.gameHeight, prefs.fullscreen, prefs.softwareOpenGl, prefs.nativeWayland);
        this.friendsCache = new FriendsCache(gameFiles.root);
        this.friendNotes = new FriendNotesStore(gameFiles.root);
        this.serverStore = new ServerStore(gameFiles.root);
        this.addedServersStore = new AddedServersStore(gameFiles.root);
        GitHubConfig githubConfig = GitHubConfig.load();
        this.friendsService = githubConfig.isConfigured() ? new FriendsService(githubConfig) : null;
        this.deyCapesService = githubConfig.isConfigured() ? new DeyCapesService(githubConfig) : null;
        this.optionsKitsRepository = githubConfig.isConfigured()
                ? new com.deylauncher.optionskits.OptionsKitsRepository(githubConfig) : null;

        // Best-effort: seed the github repo's capes catalog + texture folder the first time
        // the app opens with github configured, so Dey capes exist even on a fresh repo.
        if (deyCapesService != null) {
            Task<Void> seedTask = new Task<>() {
                @Override protected Void call() throws Exception {
                    try { deyCapesService.ensureCatalog().hashCode(); } catch (Exception ignored) {}
                    try { deyCapesService.seedTextures(); } catch (Exception ignored) {}
                    return null;
                }
            };
            new Thread(seedTask, "dey-capes-seed").start();
        }

        // Dumps written by earlier crashes (including ones from before the launcher redacted them) still
        // sit in the instance folders repeating the launch command -- and therefore the account's access
        // token. Clean them up on startup, in the background, so the fix also repairs what already leaked.
        scrubLeftoverCrashDumps();

        stage.setTitle("DeyLauncher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/app-icon.png")));
        stage.initStyle(StageStyle.UNDECORATED);   // borderless main window, same as the Mods window

        root = new BorderPane();
        root.getStyleClass().add("root-pane");

        root.setTop(buildTopBar());
        root.setCenter(buildCenterArea());

        // Scene root is a StackPane so the update/loading overlay can cover the whole window.
        windowStack = new StackPane(root);
        windowStack.getStyleClass().add("window-stack");

        // A modpack file/folder can be dropped anywhere in the launcher window: that opens the
        // "Install Modpack" window already pointed at it (the drag & drop half of the feature).
        installModpackDropTarget(windowStack);

        if (firstLaunch) {
            javafx.geometry.Rectangle2D bounds =
            javafx.stage.Screen.getPrimary().getVisualBounds();

            prefs.startWidth = bounds.getWidth() * 0.75;
            prefs.startHeight = bounds.getHeight() * 0.75;
        }

        scene = new Scene(windowStack, prefs.startWidth, prefs.startHeight);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        applyDynamicStyle();
        applyTheme();

        stage.setScene(scene);
        // The top bar (brand, nav, account/settings, and the - [] x controls) must always stay fully
        // visible at any allowed window size -- so the minimum width accounts for the FULL rendered
        // top bar, including the update button the moment it becomes available (see topBarMinWidth).
        stage.setMinWidth(Math.max(860, topBarMinWidth()));
        stage.setMinHeight(560);
        stage.centerOnScreen();
        enableWindowChrome(stage, windowStack, topBar); // drag by the top bar, resize from any edge
        stage.setOnCloseRequest(e -> doCleanExit());
        stage.show();
        if (prefs.launcherStartFullscreen) stage.setFullScreen(true);

        loadVersionsAsync();
        syncPlayCardFromActiveIdentity();
        refreshAccountButton();
        restoreOnlineSessionAsync();
        // One-time cleanup for installs whose prefs still carry the address older builds auto-saved
        // and republished forever. Deliberately unconditional and early: it is about our own prefs
        // file, so it must not depend on the friends service being available (startPresenceTasks
        // returns early without one), and it has to run before anything can publish presence.
        clearLegacyAutoSavedAddress();
        publishPresenceQuietly();
        startPresenceTasks();
        checkForUpdatesAsync();
        reportPreviousUpdateResult();
    }

    /** If the previous run ended in a self-update, the restart helper left a one-line verdict behind.
     *  Show it once (and clear it) when it says the update did NOT go through -- otherwise a failed
     *  swap just looks like the launcher coming back up on the same version for no reason, which is
     *  indistinguishable from the update doing nothing at all. */
    private void reportPreviousUpdateResult() {
        String result = AppUpdater.consumeUpdateResult();
        if (result == null || result.regionMatches(true, 0, "OK", 0, 2)) return;
        Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                "The last update couldn't be applied, so DeyLauncher is still on "
                        + AppUpdater.currentVersion() + ".\n\n" + result
                        + "\n\nFull log: " + AppUpdater.updateLogFile(),
                ButtonType.OK).show());
    }

    /**
     * Kicks off the presence housekeeping: (1) if the previous run didn't cleanly exit (the
     * graceful-exit marker is missing because of a crash / force-kill), clear our stale ONLINE
     * presence so friends immediately see us go offline; (2) write THIS run's marker (deleted on
     * clean close); (3) start a periodic presence heartbeat so a future force-close is detected by
     * friends via staleness (lastSeen stops updating -> effectivelyOffline). All best-effort.
     */
    private void startPresenceTasks() {
        if (friendsService == null) return;
        Path marker = presenceMarkerFile();
        if (Files.exists(marker)) {
            // Previous run never closed cleanly -- clear our stale ONLINE presence in the background.
            new Thread(() -> {
                try {
                    publishOfflineFromIdentity();
                } catch (Exception ignored) {
                }
            }, "presence-crash-clear").start();
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
        Thread heartbeat = new Thread(() -> {
            while (appRunning) {
                try {
                    Thread.sleep(PRESENCE_HEARTBEAT_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                // Appearing offline means there is nothing to keep fresh: the entry is already
                // OFFLINE with no details, and refreshing lastSeen would be the one thing that still
                // leaks "their launcher is running". So the heartbeat simply idles while it's on --
                // flipping the switch back publishes the real state immediately.
                if (appRunning && !prefs.invisibleMode) publishPresenceQuietly();
            }
        }, "presence-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        // One-shot: publish the servers you own so your friend profile's "Servers they own" is
        // populated. Runs once per launch (the "owned servers" write is heavier than presence).
        PlayerIdentity ownedActive = identityStore.getActive();
        if (ownedActive != null) publishOwnedServers(ownedActive);
    }

    private Path presenceMarkerFile() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", PRESENCE_GRACEFUL_MARKER);
    }

    /**
     * One presence payload: exactly what friends should be told for the CURRENT moment. Built from
     * the live game session (and the user's sharing/invisible choices) -- never from anything saved
     * on disk, see {@link #currentPresence()}.
     */
    private record PresencePayload(String status, PlayState playState, String address, String name) {}

    /**
     * What friends should see right now, derived from live state only:
     * <ul>
     *   <li>invisible mode -&gt; OFFLINE with no state at all (nothing about where we are leaks);</li>
     *   <li>a game we launched, on a server we're sharing -&gt; SERVER + address + friendly name;</li>
     *   <li>a game we launched, on a server with sharing off -&gt; SERVER with no address (friends know
     *       you're on a server, just not which one);</li>
     *   <li>a game we launched, in a single-player world -&gt; SINGLE_PLAYER, never a server;</li>
     *   <li>no game process of ours -&gt; IN_LAUNCHER.</li>
     * </ul>
     */
    private PresencePayload currentPresence() {
        if (prefs.invisibleMode) return new PresencePayload("OFFLINE", null, null, null);
        PlayState state = livePlayState;
        String address = null;
        String name = null;
        // "Only show to friends", in the one place every presence write goes through: with an empty
        // friends list there is no audience at all, so not even the address is written. A friend added
        // later sees it from the very next write (the heartbeat picks it up within a minute, and
        // accepting a request also republishes).
        if (state == PlayState.SERVER && prefs.shareServerAddress && hasAnyFriendCached()) {
            address = liveServerAddress;
            name = liveServerName;
        }
        return new PresencePayload("ONLINE", state, address, name);
    }

    /**
     * Publishes current presence in the background -- never blocks the UI, never shows an error
     * dialog on failure (friends.json being briefly unreachable shouldn't interrupt anything else).
     * Called on startup, on invisible-mode toggle, on every live session change (see
     * applySessionEvent) and whenever the Friends page opens.
     *
     * <p>Note there is deliberately NO persisted "last server" involved any more: this used to send
     * an address auto-saved in launcher.properties, which is why a stale one could be re-announced
     * every 60 seconds forever, even with Minecraft closed or in a single-player world.
     */
    private void publishPresenceQuietly() {
        publishPresence(currentPresence());
    }

    /** Sends one already-computed presence payload, off the FX thread, swallowing failures. */
    private void publishPresence(PresencePayload payload) {
        if (friendsService == null) return;
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        // While appearing offline, do NOT refresh lastSeen: friends.json is one shared file, so an
        // entry whose lastSeen keeps ticking every minute is still a tell that the launcher is open,
        // even with status OFFLINE. Invisible mode therefore writes only on the transition itself
        // (see the toggle in buildAccountTab), and the heartbeat below skips while it is on.
        boolean touchLastSeen = !prefs.invisibleMode;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    friendsService.publishPresence(active.uuid, active.username, payload.status(),
                            payload.address(), payload.name(), null, payload.playState(), touchLastSeen);
                } catch (Exception ignored) {
                    // Best-effort -- a failed presence update isn't worth interrupting anything for.
                }
                return null;
            }
        };
        new Thread(task, "presence-publish").start();
    }

    /**
     * One-time cleanup for installs coming from a build that auto-saved the server you last joined
     * into launcher.properties and republished it as live presence. That value is no longer read at
     * all (see currentPresence), so leaving it behind would only be confusing -- drop it once, and
     * leave the user's own "share my server address" choice untouched.
     */
    private void clearLegacyAutoSavedAddress() {
        if (prefs.myServerAddress == null || prefs.myServerAddress.isBlank()) return;
        String stale = prefs.myServerAddress.trim();
        prefs.myServerAddress = "";
        prefs.save();
        log("Cleared an old auto-saved server address (" + stale + ") -- friends now only ever see "
                + "where you are while you're actually in a server.");
    }

    /**
     * Publishes presence for a server THIS install hosts: while it runs and the owner allowed friends
     * to join, its live (tunnel or LAN) address is advertised, so online friends see it under
     * "Friends Playing Now" and can join straight in. When it isn't running (or isn't joinable),
     * presence falls back to whatever the live game session says -- NOT to an address saved on disk,
     * which is what used to keep announcing a long-gone server after the game had stopped.
     */
    private void publishPresenceWithServer(ServerInstance server, boolean running) {
        if (friendsService == null) return;
        PresencePayload live = currentPresence();
        // null playState means invisible mode (currentPresence turned the whole payload into OFFLINE),
        // so there is nothing to advertise -- just publish that.
        boolean advertise = !prefs.invisibleMode && running && server.allowFriendsJoin
                && live.playState() != null
                && hasAnyFriendCached(); // nobody to join it yet -- nothing to advertise
        // Where the user actually IS beats where they're hosting: if the game we launched is already
        // connected to a server, that is the truthful presence (the local server is just something
        // they own), so it wins over advertising the hosted address.
        if (advertise && live.playState() == PlayState.SERVER && live.address() != null) {
            advertise = false;
        }
        if (!advertise) {
            publishPresence(live);
            return;
        }
        PlayitTunnel tunnel = serverTunnels.get(server.id);
        String pub = tunnel != null ? tunnel.publicAddress() : null;
        String address = (pub != null && !pub.isBlank()) ? pub : (localIpAddress() + ":" + server.port);
        publishPresence(new PresencePayload(live.status(), PlayState.SERVER, address, server.name));
    }

    /**
     * Best-effort "I'm closing" mark -- won't catch a hard crash / force-kill (that case is caught
     * next launch via the missing graceful-exit marker + presence staleness, see startPresenceTasks).
     * Also deletes THIS run's graceful marker so the next startup knows the previous run exited cleanly.
     */
    private void publishOfflineOnExit() {
        if (friendsService == null) return;
        try {
            publishOfflineFromIdentity();
        } catch (Exception ignored) {
            // Nothing useful to do here -- the window is already closing.
        }
        try {
            Files.deleteIfExists(presenceMarkerFile());
        } catch (Exception ignored) {
        }
    }

    /** Publishes OFFLINE presence for the active account, or no-ops (throws safely) when none/friends off. */
    private void publishOfflineFromIdentity() throws Exception {
        if (friendsService == null) return;
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        friendsService.publishPresence(active.uuid, active.username, "OFFLINE", null);
    }

    /**
     * Reflects the currently-active saved account (if any) into the Play card, so a saved
     * offline username or a live online sign-in is used immediately on the next Play click
     * without retyping -- this is what makes Account > Apply actually take effect right away.
     * Called at startup and after anything that changes the active account or its username.
     */
    private void syncPlayCardFromActiveIdentity() {
        PlayerIdentity active = identityStore.getActive();
        if (active == null) {
            accountStatusNotice.setText("No account set up yet -- open Account to sign in with "
                    + "Microsoft or create an offline account before playing.");
            return;
        }
        if (active.accountType == AccountType.ONLINE) {
            accountStatusNotice.setText(liveOnlineAccessToken != null
                    ? "Signed in as " + active.username + " -- Play will use your real online session."
                    : "Online account \"" + active.username + "\" selected, but not signed in this "
                            + "run -- open Account and sign in again to play online.");
        } else {
            accountStatusNotice.setText("Offline account \"" + active.username
                    + "\" -- local play only, can't join real online servers.");
        }
    }

    /** Rebuilds the runtime override stylesheet from current prefs and reapplies it -- called on load and any time a setting changes, so sliders update the window live. */
    private void applyDynamicStyle() {
        scene.getStylesheets().removeIf(s -> s.startsWith("data:text/css"));
        scene.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
    }

    // ---- Center area: a page host (Home / Library / Servers) over a full-bleed background,
    // the game-output log fills the rest and stays visible across every page. ----
    private BorderPane buildCenterArea() {
        BorderPane center = new BorderPane();
        center.getStyleClass().add("center-area");

        mainPageRoot = buildMainPage();
        pageHost = new StackPane(mainPageRoot);
        pageHost.getStyleClass().add("card-host");
        VBox.setVgrow(pageHost, Priority.ALWAYS);

        VBox logSection = buildLogPane();

        // SplitPane lets the user themselves resize how much of the window is
        // the page content vs. the log, instead of us guessing a fixed split --
        // this is what "auto scale to anything" really means in practice.
        SplitPane split = new SplitPane(pageHost, logSection);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        split.getStyleClass().add("main-split");
        VBox.setVgrow(split, Priority.ALWAYS);

        center.setCenter(split);
        return center;
    }

    // ---- Top bar: brand + Home/Library/Servers nav + settings + account ----
    private HBox buildTopBar() {
        ImageView logo = new ImageView(
        new Image(getClass().getResourceAsStream("/app-icon.png"))
        );
        logo.setFitWidth(34);
        logo.setFitHeight(34);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.getStyleClass().add("logo-icon");

        Label title = new Label("DEYLAUNCHER");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.getStyleClass().add("title-label");

        HBox brand = new HBox(10, logo, title);
        brand.setAlignment(Pos.CENTER_LEFT);

        navHomeBtn = new Button("Home");
        navFriendsBtn = new Button("Friends");
        navServersBtn = new Button("Servers");
        navHomeBtn.getStyleClass().addAll("nav-tab-button", "nav-tab-active");
        navFriendsBtn.getStyleClass().add("nav-tab-button");
        navServersBtn.getStyleClass().add("nav-tab-button");
        navHomeBtn.setOnAction(e -> selectNavTab(navHomeBtn));
        navFriendsBtn.setOnAction(e -> selectNavTab(navFriendsBtn));
        navServersBtn.setOnAction(e -> selectNavTab(navServersBtn));

        HBox navGroup = new HBox(4, navHomeBtn, navFriendsBtn, navServersBtn);
        navGroup.setAlignment(Pos.CENTER_LEFT);
        navGroup.getStyleClass().add("nav-group");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        accountBtn = buildAccountButton();

        Button settingsBtn = new Button();
        settingsBtn.setGraphic(icon(IconFactory.Icon.SETTINGS, 18));
        settingsBtn.setGraphicTextGap(0);
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setOnAction(e -> openSettingsDialog());

        // Orange download button -- appears only when a newer release is available on GitHub.
        updateBtn = new Button();
        updateBtn.setGraphic(icon(IconFactory.Icon.DOWNLOAD, 18));
        updateBtn.setGraphicTextGap(0);
        updateBtn.getStyleClass().addAll("icon-button", "update-button");
        updateBtn.setVisible(false);
        updateBtn.setManaged(false);
        updateBtn.setOnAction(e -> startUpdate());

        HBox bar = new HBox(20, brand, navGroup, spacer, updateBtn, accountBtn, settingsBtn, buildWindowControls());
        bar.setPadding(new Insets(16, 18, 16, 28));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        this.topBar = bar;
        return bar;
    }

    /** The smallest width the top bar can be laid out at without clipping any control. Measured with
     *  the update button temporarily made live (it starts hidden/managed=false and comes in later),
     *  so the window's minimum size keeps the whole top bar visible at every permitted window size. */
    private double topBarMinWidth() {
        boolean wasManaged = updateBtn.isManaged();
        boolean wasVisible = updateBtn.isVisible();
        updateBtn.setManaged(true);
        updateBtn.setVisible(true);
        double needed = topBar.prefWidth(-1);
        updateBtn.setManaged(wasManaged);
        updateBtn.setVisible(wasVisible);
        if (needed > 0) topBar.setMinWidth(needed); // never let the bar itself compress its controls
        return needed;
    }

    // ---- Window controls (- [] x) for the borderless main window ----
    private HBox buildWindowControls() {
        Button min = new Button();
        Rectangle minGlyph = new Rectangle(12, 1.6);
        minGlyph.setStrokeType(StrokeType.INSIDE);
        minGlyph.getStyleClass().add("win-glyph-bar");
        min.setGraphic(minGlyph);
        min.getStyleClass().add("win-ctl-button");
        min.setOnAction(e -> stage.setIconified(true));

        Button max = new Button();
        Rectangle maxGlyph = new Rectangle(11, 11);
        maxGlyph.setStrokeType(StrokeType.INSIDE);
        maxGlyph.setStrokeWidth(1.5);
        maxGlyph.setFill(Color.TRANSPARENT);
        maxGlyph.getStyleClass().add("win-glyph-rect");
        max.setGraphic(maxGlyph);
        max.getStyleClass().add("win-ctl-button");
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        Button close = new Button();
        Label xGlyph = new Label("\u2715");
        xGlyph.getStyleClass().add("win-glyph-x");
        close.setGraphic(xGlyph);
        close.getStyleClass().addAll("win-ctl-button", "win-ctl-close");
        close.setOnAction(e -> doCleanExit());

        HBox group = new HBox(2, min, max, close);
        group.setAlignment(Pos.CENTER_RIGHT);
        group.getStyleClass().add("win-ctl-group");
        return group;
    }

    /** Clean exit shared by the OS close action and the in-window × button: stops the wave pulse,
     *  saves window size prefs, publishes offline presence, then closes the stage.
     *
     *  IMPORTANT: this also force-terminates the JVM. stage.close() alone only tears down the FX
     *  toolkit -- it does NOT guarantee the process actually exits, because any background worker
     *  thread still running (friends refresh, presence, icon fetch, etc.) is a plain non-daemon
     *  Thread and will keep the JVM alive indefinitely. During a self-update this is exactly what
     *  makes the flow look "stuck" at Preparing restart: the restart helper script waits for this
     *  process's PID to actually die before it can swap the new files in, and it never does. */
    private void doCleanExit() {
        appRunning = false;
        WavePulse.instance().stop(); // stop the online-dot wave animation with the window
        if (prefs.rememberWindowSize) {
            prefs.startWidth = stage.getWidth();
            prefs.startHeight = stage.getHeight();
            prefs.save();
        }
        publishOfflineOnExit();
        stopAllRunningServersBlocking();
        stage.close();
        Platform.exit();
        System.exit(0); // guarantee real process death even if a background thread is still running
    }

    /**
     * Sends "stop" to every server this session started and waits (bounded) for each to exit,
     * instead of System.exit(0) immediately falling through and letting the JVM shutdown tear
     * down the child Minecraft process mid-save. A killed-mid-save world can leave
     * world/dimensions/.../world_gen_settings.dat half-written, which is what produces
     * "Unable to read or access the world gen settings file!" / "Overworld settings missing"
     * the *next* time that server starts -- even though the server itself is fine and this
     * only happens intermittently (only on the runs where the launcher got closed while it was
     * still up). ServerProcessManager.stop() already does the graceful "stop" command + wait;
     * this just makes sure doCleanExit() actually calls it for every still-running server
     * before tearing down the JVM, instead of only doing so via the Stop button.
     */
    private void stopAllRunningServersBlocking() {
        if (runningServers.isEmpty()) return;
        List<ServerProcessManager> toStop = new ArrayList<>(runningServers.values());
        runningServers.clear();
        List<Thread> stoppers = new ArrayList<>();
        for (ServerProcessManager pm : toStop) {
            Thread t = new Thread(pm::stop, "server-stop-on-exit");
            t.start();
            stoppers.add(t);
        }
        for (Thread t : stoppers) {
            try {
                t.join(35_000); // a little past ServerProcessManager's own 30s graceful-stop timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- Self-updater (GitHub releases) ----

    /** Background check: queries the latest stable release; if it's newer than the running version
     *  with a suitable asset, reveals the orange update button (with a small bounce-in). */
    private void checkForUpdatesAsync() {
        Task<AppUpdater.UpdateInfo> task = new Task<>() {
            @Override protected AppUpdater.UpdateInfo call() throws Exception {
                return AppUpdater.findUpdate().orElse(null);
            }
        };
        task.setOnSucceeded(e -> {
            AppUpdater.UpdateInfo info = task.getValue();
            if (info == null) return;                                   // already up to date (or nothing available)
            updateInfo = info;
            updateBtn.setTooltip(new Tooltip("Update DeyLauncher to " + info.tag()));
            updateBtn.setManaged(true);
            updateBtn.setVisible(true);
            FadeTransition in = new FadeTransition(Duration.millis(320), updateBtn);
            in.setFromValue(0); in.setToValue(1);
            ScaleTransition pop = new ScaleTransition(Duration.millis(320), updateBtn);
            pop.setFromX(0.4); pop.setFromY(0.4); pop.setToX(1); pop.setToY(1);
            new ParallelTransition(in, pop).play();
        });
        task.setOnFailed(e -> { /* offline / no repo / rate-limited -- just don't show the button */ });
        new Thread(task, "deylauncher-update-check").start();
    }

    /** Pressing the update button: a quick press-pulse, the button fades out, then the loading overlay
     *  slides in and the download begins automatically. */
    private void startUpdate() {
        if (updateInfo == null) return;
        AppUpdater.UpdateInfo info = updateInfo;
        updateBtn.setDisable(true);
        ScaleTransition press = new ScaleTransition(Duration.millis(90), updateBtn);
        press.setToX(0.88); press.setToY(0.88); press.setInterpolator(Interpolator.EASE_IN);
        press.setAutoReverse(true); press.setCycleCount(2);
        press.setOnFinished(e -> {
            FadeTransition hide = new FadeTransition(Duration.millis(130), updateBtn);
            hide.setToValue(0);
            hide.setOnFinished(e2 -> updateBtn.setVisible(false));
            hide.play();
            showUpdateOverlayAndDownload(info);
        });
        press.play();
    }

    /** Builds (once per update), displays and animates the full-window loading overlay, then starts the download. */
    private void showUpdateOverlayAndDownload(AppUpdater.UpdateInfo info) {
        if (updateOverlay != null) windowStack.getChildren().remove(updateOverlay);
        updateOverlay = buildUpdateOverlay(info);
        updateOverlay.setOpacity(0);
        windowStack.getChildren().add(updateOverlay);

        javafx.scene.Node card = updateOverlay.getChildren().get(0); // index 0 = card, index 1 = close button
        card.setOpacity(0);
        card.setTranslateY(18);
        card.setScaleX(0.96); card.setScaleY(0.96);

        FadeTransition darkFade = new FadeTransition(Duration.millis(260), updateOverlay);
        darkFade.setToValue(1);
        FadeTransition cardFade = new FadeTransition(Duration.millis(380), card);
        cardFade.setToValue(1);
        ScaleTransition cardScale = new ScaleTransition(Duration.millis(380), card);
        cardScale.setToX(1); cardScale.setToY(1);
        TranslateTransition cardSlide = new TranslateTransition(Duration.millis(380), card);
        cardSlide.setToY(0); cardSlide.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition enter = new ParallelTransition(darkFade, cardFade, cardScale, cardSlide);
        enter.setOnFinished(e -> beginUpdate(downloadTask()));
        enter.play();
    }

    /** Builds the background download Task for the detected update, binding progress to the overlay bar. */
    private javafx.concurrent.Task<Path> downloadTask() {
        AppUpdater.UpdateInfo info = updateInfo;
        javafx.concurrent.Task<Path> t = new javafx.concurrent.Task<>() {
            @Override protected Path call() throws Exception {
                return AppUpdater.downloadRelease(info, this::updateProgress);
            }
        };
        updateProgress.progressProperty().unbind();
        updateProgress.progressProperty().bind(t.progressProperty());
        t.setOnSucceeded(e -> {
            // Must read the Task's value here, while still on the FX Application Thread --
            // Task.getValue() throws IllegalStateException off that thread, so capture it into a
            // local now instead of calling t.getValue() lazily inside the background lambda below
            // (that silently killed the install thread before it ever got started).
            Path downloaded = t.getValue();
            updateStatus.setText("Downloaded " + info.assetName() + " -- applying update...");
            new Thread(() -> installLocalUpdate(downloaded, info), "deylauncher-update-install").start();
        });
        t.setOnFailed(e -> {
            updateBtn.setDisable(false);
            updateBtn.setVisible(true);
            updateStatus.setText("Update failed: " + (t.getException() == null ? "unknown error" : t.getException().getMessage()));
            if (updateOverlay != null && updateOverlay.getChildren().size() > 1)
                updateOverlay.getChildren().get(1).setVisible(true); // reveal the close button
        });
        return t;
    }

    private void beginUpdate(javafx.concurrent.Task<Path> t) {
        if (updateStatus != null) updateStatus.setText("Downloading " + updateInfo.assetName() + " ...");
        new Thread(t, "deylauncher-update-download").start();
    }

    /** After the download completes: extract, swap into place and restart via a tiny helper process.
     *  Runs on a background thread -- every UI touch is marshalled through Platform.runLater. */
    private void installLocalUpdate(Path downloaded, AppUpdater.UpdateInfo info) {
        try {
            // Belt-and-braces: only ever apply a release that is genuinely NEWER than what's running.
            // This guards against a stale cached button/overlay racing a changed repo after we've
            // already updated, and guarantees we never "update" to an equal or older version.
            if (!AppUpdater.isNewer(info.latestVersion(), AppUpdater.currentVersion())) {
                Platform.runLater(() -> {
                    updateStatus.setText("Already up to date (" + info.latestVersion() + ").");
                    updateBtn.setDisable(false);
                    updateBtn.setVisible(false);
                    updateBtn.setManaged(false);
                    updateInfo = null;
                    revealUpdateClose();
                });
                return;
            }
            AppUpdater.InstallLayout layout = AppUpdater.resolveInstallLayout();
            if (layout == null) {
                AppUpdater.openReleasePage();
                Platform.runLater(() -> {
                    updateStatus.setText("Running from source -- opening the release page instead.");
                    updateBtn.setDisable(false);
                    revealUpdateClose();
                });
                return;
            }
            // The restart helper copies into the install folder AFTER the launcher exits, so a folder
            // this user cannot write to (e.g. the zip was unzipped into Program Files) would otherwise
            // fail completely silently -- the old build just relaunches with the same version. Probe it
            // here so the user gets a real explanation instead of a no-op update.
            // The PARENT matters just as much as the install dir itself: the helper applies the update
            // by renaming <installRoot> to <installRoot>.old and putting the new build in its place,
            // and both of those are writes into the parent folder.
            try {
                Path writeProbe = layout.installRoot().resolve(".deylauncher-write-test");
                Files.writeString(writeProbe, "ok");
                Files.deleteIfExists(writeProbe);
                Path parent = layout.installRoot().getParent();
                if (parent != null) {
                    Path parentProbe = parent.resolve(".deylauncher-write-test");
                    Files.writeString(parentProbe, "ok");
                    Files.deleteIfExists(parentProbe);
                }
            } catch (Exception notWritable) {
                Platform.runLater(() -> {
                    updateBtn.setDisable(false);
                    updateBtn.setVisible(true);
                    updateStatus.setText("Cannot write to " + layout.installRoot()
                            + " -- move DeyLauncher to a folder you own (or run it as administrator) to update.");
                    revealUpdateClose();
                });
                return;
            }
            Platform.runLater(() -> updateStatus.setText("Extracting update..."));
            Path staging = AppUpdater.createStagingRoot(layout);
            Path stagingApp = AppUpdater.extractTo(downloaded, staging, layout.windows());
            Platform.runLater(() -> updateStatus.setText("Preparing restart..."));
            Path helper = AppUpdater.writeRestartScript(layout, stagingApp, staging,
                    ProcessHandle.current().pid());
            AppUpdater.launchHelper(helper, layout.windows());
            // doCleanExit() force-terminates the JVM (see its javadoc) -- required here so the
            // restart helper's "wait for this PID to die" loop doesn't hang on a leftover thread.
            Platform.runLater(this::doCleanExit);
        } catch (Exception ex) {
            Platform.runLater(() -> {
                updateBtn.setDisable(false);
                updateBtn.setVisible(true);
                updateStatus.setText("Update failed: " + ex.getMessage());
                revealUpdateClose();
            });
        }
    }

    private void revealUpdateClose() {
        if (updateOverlay != null && updateOverlay.getChildren().size() > 1)
            updateOverlay.getChildren().get(1).setVisible(true);
    }

    /** The dark full-window loading card shown while an update downloads/installs. */
    private StackPane buildUpdateOverlay(AppUpdater.UpdateInfo info) {
        VBox card = new VBox(18);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(470);
        card.setStyle("-fx-background-color:#17181b; -fx-background-radius:16; -fx-border-radius:16; -fx-border-color:#2a2b30; -fx-padding:34;");

        Label headline = new Label("Updating DeyLauncher");
        headline.setStyle("-fx-text-fill:#f5f5f6; -fx-font-size:22px; -fx-font-weight:bold;");
        Label ver = new Label(AppUpdater.currentVersion() + "   \u2192   " + info.tag());
        ver.setStyle("-fx-text-fill:#ff7a1f; -fx-font-size:15px; -fx-font-weight:bold;");
        ver.setAlignment(Pos.CENTER);

        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(46, 46);
        spin.setStyle("-fx-progress-color:#ff7a1f;");
        spin.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        updateProgress = new ProgressBar(0);
        updateProgress.setMaxWidth(Double.MAX_VALUE);
        updateProgress.getStyleClass().add("update-progress");

        updateStatus = new Label("Preparing...");
        updateStatus.setWrapText(true);
        updateStatus.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        updateStatus.setStyle("-fx-text-fill:#9a9ba3; -fx-font-size:13px;");

        card.getChildren().addAll(headline, ver, spin, updateProgress, updateStatus);

        Button closeBtn = new Button("\u2715");
        closeBtn.getStyleClass().add("mods-win-close");
        closeBtn.setVisible(false);
        closeBtn.setOnAction(e -> {
            windowStack.getChildren().remove(updateOverlay);
            updateOverlay = null;
            updateBtn.setDisable(false);
            updateBtn.setVisible(true);
        });

        StackPane dark = new StackPane(card, closeBtn);
        StackPane.setAlignment(closeBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(closeBtn, new Insets(14));
        dark.setStyle("-fx-background-color: rgba(8,8,10,0.88);");
        dark.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return dark;
    }

    /** Switches the page host between Home (the real play page) and the Library/Servers
     * placeholders -- those two are wired up as real nav destinations already, they just
     * don't have their own feature yet, per spec ("there to be there, doing nothing"). */
    private void selectNavTab(Button selected) {
        for (Button b : new Button[]{navHomeBtn, navFriendsBtn, navServersBtn}) {
            b.getStyleClass().remove("nav-tab-active");
        }
        selected.getStyleClass().add("nav-tab-active");
        if (selected == navHomeBtn) {
            pageHost.getChildren().setAll(mainPageRoot);
        } else if (selected == navFriendsBtn) {
            pageHost.getChildren().setAll(buildFriendsPage());
        } else {
            pageHost.getChildren().setAll(buildServersPage());
        }
    }

    private VBox buildPlaceholderPage(String title, String subtitle) {
        Node icon = icon(IconFactory.Icon.TOOLS, 34);
        icon.getStyleClass().add("logo-icon");
        Label heading = new Label(title);
        heading.getStyleClass().add("card-heading");
        Label sub = new Label(subtitle);
        sub.getStyleClass().add("notice-label");
        sub.setWrapText(true);
        sub.setMaxWidth(420);
        sub.setAlignment(Pos.CENTER);
        sub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox box = new VBox(14, icon, heading, sub);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60));
        box.getStyleClass().add("placeholder-page");
        return box;
    }

    // ---- Friends page (real nav destination, not a dialog) -- single shared friends.json via FriendsService ----
    private VBox friendsPageContent;
    private ScrollPane friendsPageScroll;
    private boolean friendsShellBuilt;      // once the static heading/input is attached we never rebuild it
    private VBox friendsIncomingBox;        // list containers rebuilt in place on each refresh
    private VBox friendsOutgoingBox;
    private VBox friendsFriendsBox;

    private javafx.scene.Node buildFriendsPage() {
        if (friendsPageScroll == null) {
            friendsPageContent = new VBox(18);
            friendsPageContent.setPadding(new Insets(32));
            friendsPageContent.setMaxWidth(760);
            VBox wrapper = new VBox(friendsPageContent);
            wrapper.setAlignment(Pos.TOP_CENTER);
            friendsPageScroll = new ScrollPane(wrapper);
            friendsPageScroll.setFitToWidth(true);
            friendsPageScroll.getStyleClass().add("settings-scroll");
        }

        PlayerIdentity active = identityStore.getActive();
        if (friendsService == null) {
            renderFriendsNotSetUp();
        } else if (active == null) {
            friendsPageContent.getChildren().setAll();
            Label notice = new Label("Set up an account first (Account button) before using Friends.");
            notice.getStyleClass().add("notice-label");
            friendsPageContent.getChildren().add(notice);
        } else {
            renderFriendsPageContent(active, friendsCache.load());
            refreshFriendsAsync(active);
            publishPresenceQuietly(); // opening Friends is also a natural moment to refresh presence
        }
        return friendsPageScroll;
    }

    private void renderFriendsNotSetUp() {
        friendsPageContent.getChildren().setAll(sectionLabel("NOT SET UP YET"));
        Label notice = new Label("Friends needs a shared GitHub backend configured first -- see "
                + "GITHUB_SETUP.md at the project root for the exact steps (create a bot GitHub "
                + "account, a private repo, a scoped token) and where the token file goes: "
                + "~/.deylauncher/github.properties");
        notice.getStyleClass().add("notice-label");
        notice.setWrapText(true);
        friendsPageContent.getChildren().add(notice);
    }

    private void refreshFriendsAsync(PlayerIdentity active) {
        Task<FriendsService.FriendsView> task = new Task<>() {
            @Override
            protected FriendsService.FriendsView call() throws Exception {
                return friendsService.load(active.uuid);
            }
        };
        task.setOnSucceeded(e -> {
            friendsCache.save(task.getValue());
            renderFriendsPageContent(active, task.getValue());
        });
        task.setOnFailed(e -> {
            Label err = new Label("Couldn't refresh from GitHub: " + task.getException().getMessage()
                    + " -- showing the last known state.");
            err.getStyleClass().add("notice-label");
            err.setWrapText(true);
            friendsPageContent.getChildren().add(0, err);
        });
        new Thread(task, "friends-refresh").start();
    }

    private void renderFriendsPageContent(PlayerIdentity active, FriendsService.FriendsView view) {
        friendsPageContent.getChildren().clear();
        clearWavePulseDots(); // stop animating the previous page's dots before we rebuild

        Label heading = new Label("Friends");
        heading.getStyleClass().add("card-heading");
        Button refreshFriendsBtn = refreshIconButton("Refresh friends", () -> {
            refreshFriendsAsync(active);
            publishPresenceQuietly(); // a manual refresh is also a good moment to re-announce presence
        });
        Region headingSpacer = new Region();
        HBox.setHgrow(headingSpacer, Priority.ALWAYS);
        HBox headingRow = new HBox(12, heading, headingSpacer, refreshFriendsBtn);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        friendsPageContent.getChildren().add(headingRow);

        TextField addField = new TextField();
        addField.setPromptText("Friend's DeyLauncher username");
        addField.getStyleClass().add("input-field");
        HBox.setHgrow(addField, Priority.ALWAYS);
        Button addBtn = new Button("Send Request");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setOnAction(e -> {
            String target = addField.getText().trim();
            if (target.isEmpty()) return;
            addBtn.setDisable(true);
            Task<FriendsService.FriendsView> task = new Task<>() {
                @Override
                protected FriendsService.FriendsView call() throws Exception {
                    return friendsService.sendRequest(active.uuid, active.username, target);
                }
            };
            task.setOnSucceeded(ev -> {
                addBtn.setDisable(false);
                friendsCache.save(task.getValue());
                renderFriendsPageContent(active, task.getValue());
            });
            task.setOnFailed(ev -> {
                addBtn.setDisable(false);
                new Alert(Alert.AlertType.ERROR, "Couldn't send request: " + task.getException().getMessage(),
                        ButtonType.OK).showAndWait();
            });
            new Thread(task, "friend-request-send").start();
        });
        HBox addRow = new HBox(10, addField, addBtn);
        friendsPageContent.getChildren().addAll(sectionLabel("ADD FRIEND"), addRow);

        if (view == null) {
            Label loading = new Label("Loading...");
            loading.getStyleClass().add("notice-label");
            friendsPageContent.getChildren().add(loading);
            return;
        }

        if (!view.incoming().isEmpty()) {
            friendsPageContent.getChildren().add(sectionLabel("INCOMING REQUESTS"));
            for (var req : view.incoming()) {
                Label name = new Label(req.fromUsername());
                name.getStyleClass().add("mod-name");
                Button acceptBtn = new Button("Accept");
                acceptBtn.getStyleClass().add("pill-button");
                Button declineBtn = new Button("Decline");
                declineBtn.getStyleClass().add("pill-button");
                acceptBtn.setOnAction(e -> runFriendsAction(active,
                        () -> friendsService.acceptRequest(active.uuid, active.username, req.fromUuid())));
                declineBtn.setOnAction(e -> runFriendsAction(active,
                        () -> friendsService.declineRequest(active.uuid, active.username, req.fromUuid())));
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(10, name, spacer, acceptBtn, declineBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("mod-row");
                friendsPageContent.getChildren().add(row);
            }
        }

        if (!view.outgoing().isEmpty()) {
            friendsPageContent.getChildren().add(sectionLabel("PENDING (SENT BY YOU)"));
            for (var req : view.outgoing()) {
                Label name = new Label(req.targetUsername + "  ·  waiting for them to open DeyLauncher");
                name.getStyleClass().add("notice-label");
                Button cancelBtn = new Button("Cancel");
                cancelBtn.getStyleClass().add("pill-button");
                cancelBtn.setOnAction(e -> runFriendsAction(active,
                        () -> friendsService.cancelOutgoingRequest(active.uuid, active.username, req.targetUsername)));
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(10, name, spacer, cancelBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("mod-row");
                friendsPageContent.getChildren().add(row);
            }
        }

        friendsPageContent.getChildren().add(sectionLabel("FRIENDS"));
        if (view.friends().isEmpty()) {
            Label none = new Label("No friends yet -- send a request above.");
            none.getStyleClass().add("notice-label");
            friendsPageContent.getChildren().add(none);
        } else {
            for (var friend : view.friends()) {
                var entry = view.allUsers().get(friend.uuid);
                boolean online = effectivelyOnline(entry);
                String address = entry != null ? entry.serverAddress : null;

                Region dot = new Region();
                dot.getStyleClass().addAll("account-status-dot", online ? "dot-online" : "dot-offline");
                if (online) {
                    WavePulse.instance().register(dot);
                    wavePulseDots.add(dot);
                }

                ImageView avatarView = new ImageView();
                avatarView.setFitWidth(30);
                avatarView.setFitHeight(30);
                avatarView.setSmooth(false); // keep skin pixels crisp, not blurred
                avatarView.getStyleClass().add("account-btn-face");
                Image cachedAvatar = friendFaceCached(friend.uuid);
                if (cachedAvatar != null) {
                    avatarView.setImage(cachedAvatar);
                } else {
                    avatarView.setImage(faceThumbnail(null)); // placeholder while a friend's real Mojang face fetches
                    // Clicking a friend's profile picture opens their detailed friend profile.
                    // When the real face finishes downloading, update ONLY this ImageView -- never
                    // rebuild the page (rebuilding every friend's face repeatedly was what made the
                    // page flicker and steal focus from the username field).
                    fetchFriendAvatarAsync(friend.uuid, () -> {
                        javafx.scene.image.Image fetched = friendFaceCached(friend.uuid);
                        if (fetched != null) avatarView.setImage(fetched);
                    });
                }
                avatarView.setCursor(javafx.scene.Cursor.HAND);
                avatarView.setOnMouseClicked(ev -> openFriendProfile(friend.uuid, friend.username));
                Tooltip.install(avatarView, new Tooltip("Open " + friend.username + "'s profile"));

                Label name = new Label(friend.username);
                name.getStyleClass().add("mod-name");
                name.setCursor(javafx.scene.Cursor.HAND);
                name.setOnMouseClicked(ev -> openFriendProfile(friend.uuid, friend.username));

                // What they're doing right now (playing on <server> / single player / in the launcher),
                // so the list itself says it without having to open each profile. An OFFLINE friend says
                // exactly that -- this used to print "Online" for every offline entry regardless of the
                // published status, which is what made an invisible-mode account look online to everyone.
                String statusText = online ? describePlayState(playStateOf(entry, true), entry) : "Offline";

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, avatarView, dot, name);
                if (statusText != null && !statusText.isBlank()) {
                    Label playLbl = new Label(statusText);
                    playLbl.getStyleClass().add("notice-label");
                    row.getChildren().add(playLbl);
                }
                row.getChildren().add(spacer);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("mod-row");

                if (online && address != null && !address.isBlank()) {
                    Button joinBtn = new Button();
                    setButtonIcon(joinBtn, IconFactory.Icon.PLAY, "Join");
                    joinBtn.getStyleClass().add("pill-button");
                    joinBtn.setOnAction(e -> {
                        selectNavTab(navHomeBtn);
                        onPlay(address);
                    });
                    row.getChildren().add(joinBtn);
                }

                Button removeBtn = new Button("Remove");
                removeBtn.getStyleClass().add("pill-button");
                removeBtn.setOnAction(e -> runFriendsAction(active,
                        () -> friendsService.removeFriend(active.uuid, active.username, friend.uuid)));
                row.getChildren().add(removeBtn);

                friendsPageContent.getChildren().add(row);
            }
        }
    }

    /**
     * A friend is only truly ONLINE if they say so AND heartbeat within the staleness window.
     * If they force-closed/crashed their launcher, their heartbeat stopped and status goes stale,
     * so they show as OFFLINE (white dot) -- see PRESENCE_STALE_MS.
     */
    private boolean effectivelyOnline(FriendsData.UserEntry entry) {
        return entry != null && "ONLINE".equals(entry.status)
                && (System.currentTimeMillis() - entry.lastSeen) < PRESENCE_STALE_MS;
    }

    /**
     * What to SHOW for a friend: null when they're offline (nothing to describe), else their published
     * play state. Entries written by an older build carry no {@code playState} -- those are rendered the
     * legacy way, where a published address still means "on a server", so nothing regresses for friends
     * who haven't updated yet.
     */
    private PlayState playStateOf(FriendsData.UserEntry entry, boolean online) {
        if (!online || entry == null) return null;
        PlayState state = PlayState.fromWire(entry.playState);
        if (state == PlayState.UNKNOWN && entry.serverAddress != null && !entry.serverAddress.isBlank()) {
            return PlayState.SERVER;
        }
        return state;
    }

    /**
     * One short line saying what a friend is doing right now, for the friends list / profile header,
     * or {@code null} when there is nothing worth adding.
     *
     * <p>{@code null} means "not online" (see playStateOf) and must never render as "Online": that
     * generic fallback is how an invisible-mode account leaked its real state -- it publishes OFFLINE,
     * but every friend's list still printed "Online" next to the dim offline dot. "Online" is now only
     * printed for someone who really is online yet published no play state at all.
     */
    private String describePlayState(PlayState state, FriendsData.UserEntry entry) {
        if (state == null) return null;                  // offline: the dot and the badge already say so
        if (state == PlayState.UNKNOWN) return "Online"; // online, but their build published no state
        return switch (state) {
            case SERVER -> {
                String address = entry != null ? entry.serverAddress : null;
                if (address == null || address.isBlank()) yield "On a server (address not shared)";
                String name = (entry.currentServerName != null && !entry.currentServerName.isBlank())
                        ? entry.currentServerName : resolveServerDisplay(address);
                yield "Playing on " + name;
            }
            case SINGLE_PLAYER -> "Playing single player";
            case IN_LAUNCHER -> "In the launcher";
            case UNKNOWN -> null; // handled above; listed so a new state can never fall through silently
        };
    }

    /** Unregisters every dot this page is wave-pulsing (called before a re-render). */
    private void clearWavePulseDots() {
        for (Node n : wavePulseDots) WavePulse.instance().unregister(n);
        wavePulseDots.clear();
    }

    /**
     * Turns a raw "host:port" server address into a friendly display for friends: if it matches one
     * of MY added/external servers (or a locally-hosted server), show that server's name, else fall
     * back to the bare address. Used with the SERVER icon so friends recognise where you're playing.
     *
     * <p>Candidates are SCORED rather than first-match-wins (see {@link ServerAddressMatch}): an exact
     * host:port hit always beats a same-host-different-or-default-port one, and among equally good
     * hits the most recently joined server wins. Matching on the host alone while walking the list in
     * order is what made a friend on one server show up under the name of a *different* bookmark on
     * the same host -- e.g. "the first server I ever added".
     */
    private String resolveServerDisplay(String address) {
        if (address == null || address.isBlank()) return address;
        String a = address.trim();
        String best = null;
        int bestScore = ServerAddressMatch.SCORE_NONE;
        long bestJoinedAt = Long.MIN_VALUE;
        for (var srv : addedServersStore.list()) {
            int score = ServerAddressMatch.score(srv.address(), a);
            if (score > bestScore || (score > ServerAddressMatch.SCORE_NONE && score == bestScore
                    && srv.lastJoinedAt > bestJoinedAt)) {
                bestScore = score;
                best = srv.name();
                bestJoinedAt = srv.lastJoinedAt;
            }
        }
        // Resolved once instead of once per candidate: localIpAddress() walks the network
        // interfaces (and can attempt a local-hostname lookup), so calling it inside the loop was
        // needless I/O on a path that runs every time a friend's address is resolved.
        String localIp = localIpAddress();
        for (var srv : serverStore.listAll()) {
            int score = Math.max(ServerAddressMatch.score("localhost:" + srv.port, a),
                    ServerAddressMatch.score(localIp + ":" + srv.port, a));
            if (score > bestScore || (score > ServerAddressMatch.SCORE_NONE && score == bestScore
                    && srv.lastJoinedAt > bestJoinedAt)) {
                bestScore = score;
                best = srv.name;
                bestJoinedAt = srv.lastJoinedAt;
            }
        }
        return bestScore > ServerAddressMatch.SCORE_NONE && best != null ? best : a;
    }

    /**
     * True when a friend could actually reach this address (i.e. it isn't loopback or this machine).
     * The rule itself lives in {@link ServerAddressMatch#isShareable}, so it stays unit-testable.
     */
    private boolean isShareableServerAddress(String address) {
        return ServerAddressMatch.isShareable(address, localIpAddress());
    }

    /**
     * Returns a cached, cropped face for a friend if we already have one, else null (the caller
     * shows a placeholder and kicks off fetchFriendAvatarAsync). Works for ONLINE and OFFLINE
     * friends alike -- a friend whose name maps to a real Minecraft account shows their Mojang
     * face either way. Local-only DeyLauncher accounts never resolve on Mojang, so they simply
     * keep the clean default face (a failed fetch is cached as absent, not as a broken image).
     */
    private Image friendFaceCached(String uuid) {
        java.nio.file.Path cache = gameFiles.root.resolve("friend-avatars").resolve(uuid + ".png");
        if (java.nio.file.Files.exists(cache)) {
            try {
                return faceThumbnail(new Image(cache.toUri().toString()));
            } catch (Exception ignored) {
                // Fall through -- treat a corrupt cache file the same as "not cached yet."
            }
        }
        return null;
    }

    /**
     * Looks up a friend's real skin via Mojang's public session-profile endpoint (no auth needed --
     * this is standard public player data) and caches the PNG locally, then re-renders so the
     * placeholder swaps for the real face. Called for both online and offline friends; a uuid that
     * isn't a real Minecraft account just never caches and keeps the placeholder.
     */
    private void fetchFriendAvatarAsync(String uuid, Runnable onDone) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    var http = java.net.http.HttpClient.newHttpClient();
                    var profileReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid)).GET().build();
                    var profileResp = http.send(profileReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (profileResp.statusCode() != 200) return null;

                    var json = com.google.gson.JsonParser.parseString(profileResp.body()).getAsJsonObject();
                    String texturesB64 = null;
                    for (var prop : json.getAsJsonArray("properties")) {
                        var obj = prop.getAsJsonObject();
                        if ("textures".equals(obj.get("name").getAsString())) {
                            texturesB64 = obj.get("value").getAsString();
                            break;
                        }
                    }
                    if (texturesB64 == null) return null;

                    String decoded = new String(java.util.Base64.getDecoder().decode(texturesB64),
                            java.nio.charset.StandardCharsets.UTF_8);
                    var texturesJson = com.google.gson.JsonParser.parseString(decoded).getAsJsonObject();
                    var skinObj = texturesJson.getAsJsonObject("textures").getAsJsonObject("SKIN");
                    if (skinObj == null) return null;
                    String skinUrl = skinObj.get("url").getAsString();

                    java.nio.file.Path cache = gameFiles.root.resolve("friend-avatars").resolve(uuid + ".png");
                    java.nio.file.Files.createDirectories(cache.getParent());
                    var imgReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(skinUrl)).GET().build();
                    http.send(imgReq, java.net.http.HttpResponse.BodyHandlers.ofFile(cache));
                } catch (Exception ignored) {
                    // Best-effort -- the placeholder just stays up if this fails.
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(onDone));
        new Thread(task, "friend-avatar-fetch").start();
    }

// ---- Friend Profile popup (opens when you click a friend's profile picture) ----
    private void openFriendProfile(String friendUuid, String friendUsername) {
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        FriendsService.FriendsView view = friendsCache.load();
        // Friends only: this popup exists to render someone's published presence (what they're
        // playing, which server to join). The shared friends file holds an entry for EVERY
        // DeyLauncher user, so "there is an entry for this uuid" is not the same as "they are my
        // friend" -- a uuid that isn't on our friends list must never reach the presence code below.
        if (!isFriend(friendUuid, view)) {
            new Alert(Alert.AlertType.INFORMATION,
                    (friendUsername != null ? friendUsername : "That player") + " isn't on your friends "
                            + "list, so their profile isn't shown. Send a friend request from the "
                            + "Friends page first.",
                    ButtonType.OK).showAndWait();
            return;
        }
        FriendsData.UserEntry entry = view.allUsers().get(friendUuid);
        boolean online = effectivelyOnline(entry);

        VBox content = buildFriendProfileBody(active, friendUuid, friendUsername, entry, view, online);
        Stage win = buildBorderlessStage("Friend Profile", content, stage,
                Modality.WINDOW_MODAL, 420, 520, 540, 660);
        win.show();
    }

    private VBox buildFriendProfileBody(PlayerIdentity me, String friendUuid, String friendUsername,
                                        FriendsData.UserEntry entry, FriendsService.FriendsView view,
                                        boolean online) {
        VBox root = new VBox(16);
        root.setPadding(new Insets(22));

        // Header: big avatar with the online/offline dot pinned to its bottom-right.
        Image face = friendFaceCached(friendUuid);
        ImageView bigAv = new ImageView(face != null ? face : faceThumbnail(null));
        bigAv.setFitWidth(72);
        bigAv.setFitHeight(72);
        bigAv.setSmooth(false);
        bigAv.getStyleClass().add("account-btn-face");
        if (face == null) {
            fetchFriendAvatarAsync(friendUuid, () -> {
                Image fetched = friendFaceCached(friendUuid);
                if (fetched != null) bigAv.setImage(fetched);
            });
        }
        Region dot = new Region();
        dot.getStyleClass().addAll("account-status-dot", online ? "dot-online" : "dot-offline");
        dot.setMinSize(18, 18);
        dot.setMaxSize(18, 18);
        if (online) {
            WavePulse.instance().register(dot);
            wavePulseDots.add(dot);
        }
        StackPane avatarWrap = new StackPane(bigAv);
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        avatarWrap.getChildren().add(dot);

        Label nameLbl = new Label(friendUsername);
        nameLbl.getStyleClass().add("card-heading");
        Label statusLbl = new Label(online ? "Online" : "Offline");
        statusLbl.getStyleClass().add(online ? "badge-online" : "badge-offline");
        HBox nameRow = new HBox(10, nameLbl, statusLbl);
        // One extra line saying WHAT they're doing: playing on <server> / single player / in the
        // launcher. Published by their own launcher's live game session (see PlayState), not guessed
        // from an address.
        if (online) {
            String playText = describePlayState(playStateOf(entry, true), entry);
            if (playText != null && !playText.isBlank()) {
                Label playLbl = new Label(playText);
                playLbl.getStyleClass().add("notice-label");
                nameRow.getChildren().add(playLbl);
            }
        }
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(14, avatarWrap, nameRow);
        header.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(header);

        // Now playing / Join card.
        root.getChildren().add(buildNowPlayingSection(entry, online));

        // Mutual friends & reciprocal servers.
        java.util.List<String> mutualFriends = mutualFriendNames(me.uuid, entry, view);
        java.util.List<String> mutualServers = mutualServerNames(entry, myServerNameSet());
        Label mutualStats = new Label("Mutual friends: " + mutualFriends.size()
                + "   ·   Mutual servers: " + mutualServers.size());
        mutualStats.getStyleClass().add("notice-label");
        root.getChildren().addAll(sectionLabel("MUTUAL WITH THEM"), mutualStats);
        if (!mutualFriends.isEmpty()) {
            Label l = new Label("Shared friends: " + String.join(", ", new java.util.TreeSet<>(mutualFriends)));
            l.getStyleClass().add("notice-label");
            l.setWrapText(true);
            root.getChildren().add(l);
        }
        if (!mutualServers.isEmpty()) {
            Label l = new Label("Shared servers: " + String.join(", ", new java.util.TreeSet<>(mutualServers)));
            l.getStyleClass().add("notice-label");
            l.setWrapText(true);
            root.getChildren().add(l);
        }

        // Socials.
        root.getChildren().add(sectionLabel("SOCIALS"));
        root.getChildren().add(entry != null && entry.socials != null && !entry.socials.isEmpty()
                ? socialsList(entry.socials)
                : noticeText("They haven't shared any socials yet."));

        // Servers they own.
        root.getChildren().add(sectionLabel("SERVERS THEY OWN"));
        root.getChildren().add(entry != null && entry.servers != null && !entry.servers.isEmpty()
                ? ownedServersList(entry.servers)
                : noticeText("They don't currently list any servers they own."));

        // Personal notes -- saved only on your PC, never uploaded.
        root.getChildren().add(sectionLabel("YOUR NOTES"));
        TextArea notesArea = new TextArea(friendNotes.noteFor(friendUuid));
        notesArea.setPrefRowCount(4);
        notesArea.setWrapText(true);
        notesArea.getStyleClass().add("input-field");
        Button notesSave = new Button("Save Notes");
        notesSave.getStyleClass().add("pill-button");
        Label notesSaved = new Label("");
        notesSaved.getStyleClass().add("notice-label");
        notesSave.setOnAction(e -> notesSaved.setText(friendNotes.save(friendUuid, notesArea.getText())
                ? "Saved locally." : "Couldn't save -- check disk permissions."));
        HBox notesRow = new HBox(10, notesSave, notesSaved);
        notesRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(notesArea, notesRow);
        return root;
    }
    /**
     * The profile's "what are they doing" card, driven by the published play state rather than by the
     * mere presence of an address:
     * <ul>
     *   <li>{@code SERVER} with a shared address -&gt; server tile + name + a glowing Join button;</li>
     *   <li>{@code SERVER} with sharing off -&gt; says they're on a server, without naming it;</li>
     *   <li>{@code SINGLE_PLAYER} / {@code IN_LAUNCHER} -&gt; says so (nothing to join);</li>
     *   <li>no state at all (older build) -&gt; an address still means "on a server", exactly as before.</li>
     * </ul>
     */
    private Node buildNowPlayingSection(FriendsData.UserEntry entry, boolean online) {
        VBox box = new VBox(10);
        box.getStyleClass().add("profile-now-section");
        // An offline friend -- including one in invisible mode, which publishes OFFLINE -- gets said
        // plainly, so nothing about where they are can leak through their profile.
        if (!online) {
            return noticeText("Offline right now, so there is nothing to show.");
        }
        PlayState state = playStateOf(entry, online);
        if (state == null || state == PlayState.UNKNOWN) {
            return noticeText("Online, with nothing shared right now.");
        }
        if (state == PlayState.SINGLE_PLAYER) {
            box.getChildren().add(playStateRow("Single player",
                    "Playing single player -- a local world, not a server."));
            return box;
        }
        if (state == PlayState.IN_LAUNCHER) {
            box.getChildren().add(playStateRow("In the launcher",
                    "In DeyLauncher, not in a game right now."));
            return box;
        }
        // SERVER: only an actually shared address can be shown/joined.
        String address = (entry != null) ? entry.serverAddress : null;
        if (address == null || address.isBlank()) {
            box.getChildren().add(playStateRow("On a server",
                    "Playing on a server, with the address not shared."));
            return box;
        }
        String displayName = (entry.currentServerName != null && !entry.currentServerName.isBlank())
                ? entry.currentServerName : resolveServerDisplay(address);
        // The icon itself is never published to friends.json (only name/address are) -- so unless
        // some other client set currentServerIconUrl, fetch the real favicon ourselves the same
        // way an Added Server row does, caching it under the address so it's instant next time.
        Node tile;
        if (entry.currentServerIconUrl != null && !entry.currentServerIconUrl.isBlank()) {
            tile = serverTile(displayName, entry.currentServerIconUrl, 40);
        } else {
            Path iconCache = serverIconCachePath(address);
            StackPane iconTile = serverIconTile(displayName, Files.exists(iconCache) ? iconCache : null, 40);
            if (!Files.exists(iconCache)) pingForIconAsync(address, iconTile, iconCache, 40);
            tile = iconTile;
        }
        Label placeLabel = new Label("Currently playing");
        placeLabel.getStyleClass().add("notice-label");
        Label srvName = new Label(displayName);
        srvName.getStyleClass().add("mod-name");
        srvName.setWrapText(true);
        VBox info = new VBox(4, placeLabel, srvName);
        HBox left = new HBox(10, tile, info);
        left.setAlignment(Pos.CENTER_LEFT);
        Button join = glowingJoinButton(address);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, left, spacer, join);
        row.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(row);
        return box;
    }

    /** A quiet two-line "what they're doing" block for play states with nothing to join. */
    private Node playStateRow(String title, String note) {
        Label head = new Label(title);
        head.getStyleClass().add("mod-name");
        Label detail = new Label(note);
        detail.getStyleClass().add("notice-label");
        detail.setWrapText(true);
        return new VBox(4, head, detail);
    }

    private java.util.List<String> mutualFriendNames(String myUuid, FriendsData.UserEntry entry, FriendsService.FriendsView view) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (entry == null || view == null) return out;
        FriendsData.UserEntry me = view.allUsers().get(myUuid);
        if (me == null) return out;
        java.util.Set<String> theirFriends = new java.util.HashSet<>();
        for (var f : entry.friends) theirFriends.add(f.username.toLowerCase());
        for (var f : me.friends) {
            if (theirFriends.contains(f.username.toLowerCase())) out.add(f.username);
        }
        return out;
    }

    private java.util.List<String> mutualServerNames(FriendsData.UserEntry entry, java.util.Set<String> myServerNames) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (entry == null || entry.servers == null) return out;
        for (var srv : entry.servers) {
            if (srv.name != null && myServerNames.contains(srv.name.toLowerCase())) out.add(srv.name);
        }
        return out;
    }

    private java.util.Set<String> myServerNameSet() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var srv : addedServersStore.list()) if (srv.name() != null) names.add(srv.name().toLowerCase());
        for (var srv : serverStore.listAll()) if (srv.name != null) names.add(srv.name.toLowerCase());
        return names;
    }

    /** Publishes the currently-owned servers (from this install) to the friend profile, in the
     *  background. Only servers the user marked visible (Account > Friend Profile > Owned Servers)
     *  are shared; the rest stay private to this PC. Name + port; no icon since self-hosted servers
     *  may have no hosted image. While "Appear offline" is on, an EMPTY list is published instead, so
     *  the profile cannot keep advertising what this install hosts. */
    private void publishOwnedServers(PlayerIdentity active) {
        if (friendsService == null) return;
        java.util.List<FriendsData.ServerInfo> owned = new java.util.ArrayList<>();
        // Appearing offline publishes nothing at all -- and that includes the servers you own, which
        // would otherwise still tell anyone reading the shared file what you host and on which port
        // while your profile claims to be offline. Pushing an empty list (rather than just skipping)
        // is what actually clears whatever is already on the wire; the invisible toggle calls this
        // immediately, and the one-shot publish at startup covers a launcher opened while invisible.
        if (!prefs.invisibleMode) {
            for (var srv : serverStore.listAll()) {
                if (srv.visibleToFriends && srv.name != null && !srv.name.isBlank()) {
                    owned.add(new FriendsData.ServerInfo(srv.name, srv.port, null));
                }
            }
        }
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                friendsService.updateOwnedServers(active.uuid, active.username, owned);
                return null;
            }
        };
        new Thread(task, "owned-servers-publish").start();
    }

    private Label noticeText(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("notice-label");
        l.setWrapText(true);
        return l;
    }

    private Node socialsList(java.util.List<FriendsData.Social> socials) {
        VBox box = new VBox(6);
        for (var s : socials) {
            String value = s.value;
            Button b = new Button((s.type != null ? s.type + ": " : "") + (value != null ? value : ""));
            b.getStyleClass().add("pill-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setCursor(javafx.scene.Cursor.HAND);
            b.setOnAction(e -> openExternal(value));
            box.getChildren().add(b);
        }
        return box;
    }

    private Node ownedServersList(java.util.List<FriendsData.ServerInfo> servers) {
        VBox box = new VBox(6);
        for (var srv : servers) {
            HBox row = new HBox(10, serverTile(srv.name, srv.iconUrl, 32));
            row.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(srv.name != null ? srv.name : "Unnamed server");
            name.getStyleClass().add("mod-name");
            row.getChildren().add(name);
            if (srv.port > 0) {
                Label port = new Label(":" + srv.port);
                port.getStyleClass().add("notice-label");
                row.getChildren().add(port);
            }
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().add(spacer);
            box.getChildren().add(row);
        }
        return box;
    }

    private Node serverTile(String name, String iconUrl, double size) {
        StackPane tile = new StackPane();
        tile.setMinSize(size, size);
        tile.setMaxSize(size, size);
        String initials = "";
        if (name != null && !name.isBlank()) {
            String t = name.trim();
            initials = t.substring(0, 1).toUpperCase();
            int sp = t.indexOf(' ');
            if (sp > 0 && sp + 1 < t.length()) initials += t.charAt(sp + 1);
        }
        double hue = (Math.abs((name == null ? "" : name).hashCode()) % 360);
        Circle bg = new Circle(size / 2.0, Color.hsb(hue, 0.45, 0.40));
        Label inits = new Label(initials);
        inits.setTextFill(javafx.scene.paint.Color.WHITE);
        inits.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, size * 0.38));
        tile.getChildren().addAll(bg, inits);

        if (iconUrl != null && !iconUrl.isBlank()) {
            java.nio.file.Path cache = serverIconCachePath(name);
            // Solid black backdrop that only appears once the real icon has loaded -- so any
            // transparent parts of it read as clean black instead of the letter avatar behind it.
            Circle imgBackdrop = new Circle(size / 2.0, Color.BLACK);
            imgBackdrop.setVisible(false);
            ImageView iv = new ImageView();
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setSmooth(true);
            Circle clip = new Circle(size / 2.0, size / 2.0, size / 2.0);
            iv.setClip(clip);
            iv.setUserData(Boolean.TRUE);
            tile.getChildren().addAll(imgBackdrop, iv);
            final ImageView ref = iv;
            Task<java.nio.file.Path> fetch = new Task<>() {
                @Override protected java.nio.file.Path call() {
                    try {
                        java.nio.file.Files.createDirectories(cache.getParent());
                        var h = java.net.http.HttpClient.newHttpClient();
                        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(iconUrl)).GET().build();
                        var resp = h.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(cache));
                        return resp.statusCode() == 200 ? cache : null;
                    } catch (Exception ignored) { return null; }
                }
            };
            fetch.setOnSucceeded(ev -> {
                java.nio.file.Path cached = fetch.getValue();
                if (cached != null && Boolean.TRUE.equals(ref.getUserData())) {
                    ref.setImage(new Image(cached.toUri().toString()));
                    // Real icon is in -- hide the letter avatar (effectively "deleted") and let
                    // the black backdrop show through any transparent pixels instead of it.
                    bg.setVisible(false);
                    inits.setVisible(false);
                    imgBackdrop.setVisible(true);
                }
            });
            new Thread(fetch, "profile-server-icon").start();
        }
        return tile;
    }

    /** Where a downscaled/cached server icon lives on disk (shared by favicons and friend icons). */
    private Path serverIconCachePath(String key) {
        long id = Math.abs((key == null ? "" : key).hashCode());
        return gameFiles.root.resolve("server-icons").resolve("srv" + id + ".png");
    }

    /**
     * A circular server avatar: a colored disc with the server's initials, plus an icon image
     * layered on top when a local file already exists. The ImageView is stashed in the tile's
     * properties so a background ping can drop in a freshly fetched favicon later without a rebuild.
     */
    private StackPane serverIconTile(String name, Path localIcon, double size) {
        StackPane tile = new StackPane();
        tile.setMinSize(size, size);
        tile.setMaxSize(size, size);
        String initials = "";
        if (name != null && !name.isBlank()) {
            String t = name.trim();
            initials = t.substring(0, 1).toUpperCase();
            int sp = t.indexOf(' ');
            if (sp > 0 && sp + 1 < t.length()) initials += t.charAt(sp + 1);
        }
        double hue = Math.abs((name == null ? "" : name).hashCode()) % 360;
        Circle bg = new Circle(size / 2.0, Color.hsb(hue, 0.45, 0.40));
        Label inits = new Label(initials);
        inits.setTextFill(javafx.scene.paint.Color.WHITE);
        inits.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, size * 0.38));
        // Solid black backdrop that only appears once a real server icon is showing -- so any
        // transparent pixels in that icon read as clean black instead of letting the colored
        // letter avatar (or a stale previous image) bleed through behind it.
        Circle imgBackdrop = new Circle(size / 2.0, Color.BLACK);
        imgBackdrop.setVisible(false);
        ImageView iv = new ImageView();
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setSmooth(true);
        iv.setClip(new Circle(size / 2.0, size / 2.0, size / 2.0));
        tile.getProperties().put("deyServerIconView", iv);
        tile.getProperties().put("deyServerIconLetters", new Node[]{bg, inits});
        tile.getProperties().put("deyServerIconBackdrop", imgBackdrop);
        tile.getChildren().addAll(bg, inits, imgBackdrop, iv);
        if (localIcon != null && Files.exists(localIcon)) {
            try {
                showServerIcon(tile, new Image(localIcon.toUri().toString(), size, size, true, true));
            } catch (Exception ignored) {
            }
        }
        return tile;
    }

    private ImageView serverIconImageView(Node tile) {
        if (tile == null) return null;
        Object v = tile.getProperties().get("deyServerIconView");
        return v instanceof ImageView iv ? iv : null;
    }

    /** Swaps a server tile from its lettered placeholder over to a real icon image: hides the
     *  colored initials avatar (so it's effectively "deleted" once a real icon exists) and reveals
     *  the black backdrop behind the image, so any transparent parts of the icon show clean black
     *  instead of the old letter avatar showing through. Safe to call more than once. */
    private void showServerIcon(Node tile, Image image) {
        if (tile == null || image == null) return;
        ImageView iv = serverIconImageView(tile);
        if (iv != null) iv.setImage(image);
        Object lettersObj = tile.getProperties().get("deyServerIconLetters");
        if (lettersObj instanceof Node[] letters) {
            for (Node n : letters) if (n != null) n.setVisible(false);
        }
        Object backdropObj = tile.getProperties().get("deyServerIconBackdrop");
        if (backdropObj instanceof Node backdrop) backdrop.setVisible(true);
    }

    /** Decodes a data:image/png;base64 favicon from a status response into the given PNG cache file. */
    private static void cacheFavicon(String dataUri, Path out) {
        try {
            if (dataUri == null || out == null) return;
            int comma = dataUri.indexOf(',');
            String b64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
            byte[] bytes = java.util.Base64.getDecoder().decode(b64.trim());
            if (bytes.length == 0) return;
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
        } catch (Exception ignored) {
            // A missing/odd favicon is never worth surfacing -- the initials tile already looks fine.
        }
    }

    /**
     * Adds the pulsing green "online / joinable" glow to a Join button. Idempotent (safe to call on
     * every ping refresh) and auto-stops the animation when the button leaves the scene, so
     * re-rendering the server list never leaves an orphan timeline running behind the scenes.
     */
    private void ensureJoinGlow(Button b) {
        if (b == null || b.getProperties().containsKey("deyJoinGlow")) return;
        b.getProperties().put("deyJoinGlow", Boolean.TRUE);
        DropShadow glow = new DropShadow();
        glow.setColor(Color.rgb(120, 255, 150, 0.95));
        glow.setRadius(14);
        b.setEffect(glow);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 8)),
                new KeyFrame(Duration.seconds(0.9), new KeyValue(glow.radiusProperty(), 22)));
        tl.setAutoReverse(true);
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        b.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene == null) {
                tl.stop();
                b.setEffect(null);
            }
        });
    }

    /** A "Join Server" button that pulses a green glow while the profile is open. */
    private Button glowingJoinButton(String address) {
        Button b = new Button("Join Server");
        b.getStyleClass().add("pill-button");
        setButtonIcon(b, IconFactory.Icon.PLAY, "Join");
        ensureJoinGlow(b);
        b.setOnAction(e -> {
            selectNavTab(navHomeBtn);
            onPlay(address);
        });
        return b;
    }

    /** The live parts of a server card/row a background ping updates in place. */
    private record ServerCardUi(Label badge, Button joinBtn, StackPane iconTile, Path faviconCache,
                                double iconSize, boolean owned, boolean running, boolean refreshIcon) {}

    /**
     * Pings a server in the background and reflects the result on its already-built card/row: the
     * status badge gains an "ONLINE · N/M players" reading, the Join button starts glowing, and a
     * favicon (when the server sends one) is cached and shown. Results are dropped if the row was
     * re-rendered meanwhile, so a slow ping can never scribble over a newer row.
     */
    private void pingServerAsync(String address, ServerCardUi ui) {
        if (address == null || address.isBlank() || ui == null || ui.badge() == null) return;
        // A stopped local server can't answer, so skip the socket attempt (and its timeout) entirely.
        if (ui.owned() && !ui.running()) return;
        Task<ServerStatusPing.Status> task = new Task<>() {
            @Override protected ServerStatusPing.Status call() {
                // Retry once on a miss -- see pingWithRetry's javadoc: a single 2.5s probe can miss a
                // server that's genuinely online (tunnel latency, a cold TCP path, SRV lookup eating
                // into the round trip), and this badge only ever pings once per row, so a lone failed
                // attempt used to stick as "OFFLINE" until the page was rebuilt.
                return ServerStatusPing.pingWithRetry(address, 2500, 1);
            }
        };
        task.setOnSucceeded(e -> {
            if (ui.badge().getScene() == null) return; // detached by a re-render: stale result
            ServerStatusPing.Status st = task.getValue();
            if (st.online()) {
                String players = st.maxPlayers() > 0
                        ? st.onlinePlayers() + "/" + st.maxPlayers() + " players"
                        : st.onlinePlayers() + " players";
                ui.badge().setText("ONLINE  ·  " + players);
                ui.badge().getStyleClass().setAll("badge-online");
                Node dot = ui.badge().getGraphic();
                if (dot instanceof Circle c) c.getStyleClass().setAll("status-dot-online");
                if (ui.joinBtn() != null) ensureJoinGlow(ui.joinBtn());
                if (st.faviconDataUri() != null && ui.faviconCache() != null && ui.refreshIcon()) {
                    ImageView iv = serverIconImageView(ui.iconTile());
                    if (iv != null) { // refreshIcon is false when a custom server-icon.png is in charge
                        cacheFavicon(st.faviconDataUri(), ui.faviconCache());
                        try {
                            showServerIcon(ui.iconTile(), new Image(ui.faviconCache().toUri().toString(),
                                    ui.iconSize(), ui.iconSize(), true, true));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else if (ui.owned()) {
                setBadge(ui.badge(), ui.running()); // still starting up? show RUNNING, not OFFLINE
            } else {
                ui.badge().setText("OFFLINE");
                ui.badge().getStyleClass().setAll("badge-offline");
                Node dot = ui.badge().getGraphic();
                if (dot instanceof Circle c) c.getStyleClass().setAll("status-dot-offline");
            }
        });
        Thread t = new Thread(task, "server-ping");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Grabs a server's favicon purely for display -- no status badge involved -- so a friend's
     * "Now Playing" tile (profile popup or the Friends Playing Now list) can show a real icon even
     * for a server we've never added ourselves. currentServerIconUrl is never actually published
     * by this launcher (friends.json only carries name/address, see publishPresence/currentPresence),
     * so without this the tile always fell back to a plain letter avatar. Drops the result if the
     * tile isn't on screen any more (popup closed / row re-rendered) instead of touching a stale node.
     */
    private void pingForIconAsync(String address, StackPane iconTile, Path cache, double size) {
        if (address == null || address.isBlank() || iconTile == null || cache == null) return;
        Task<ServerStatusPing.Status> task = new Task<>() {
            @Override protected ServerStatusPing.Status call() {
                return ServerStatusPing.pingWithRetry(address, 2500, 1);
            }
        };
        task.setOnSucceeded(e -> {
            if (iconTile.getScene() == null) return; // popup closed / row gone -- drop the result
            ServerStatusPing.Status st = task.getValue();
            if (st.online() && st.faviconDataUri() != null) {
                cacheFavicon(st.faviconDataUri(), cache);
                try {
                    showServerIcon(iconTile, new Image(cache.toUri().toString(), size, size, true, true));
                } catch (Exception ignored) {
                }
            }
        });
        Thread t = new Thread(task, "profile-server-icon-ping");
        t.setDaemon(true);
        t.start();
    }

    /** Opens a social link / email / website in the OS browser. Safe no-op if it isn't a link. */
    private void openExternal(String value) {
        if (value == null || value.isBlank()) return;
        String v = value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://") && !v.startsWith("mailto:")) {
            v = "https://" + v;
        }
        try {
            getHostServices().showDocument(v);
        } catch (Exception ignored) {
        }
    }

    // ---- Servers page: Your Servers / Added Servers / Friends Playing Now ----
    private VBox serversPageContent;
    private ScrollPane serversPageScroll;

    private Node buildServersPage() {
        if (serversPageScroll == null) {
            serversPageContent = new VBox(24);
            serversPageContent.setPadding(new Insets(32));
            serversPageContent.setMaxWidth(920);
            VBox wrapper = new VBox(serversPageContent);
            wrapper.setAlignment(Pos.TOP_CENTER);
            serversPageScroll = new ScrollPane(wrapper);
            serversPageScroll.setFitToWidth(true);
            serversPageScroll.getStyleClass().add("settings-scroll");
        }
        renderServersPageContent();
        return serversPageScroll;
    }

    private ToggleButton sortToggleButton(String label, ServerSortMode mode, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(label);
        btn.getStyleClass().add("pill-button");
        btn.setToggleGroup(group);
        btn.setSelected(serversSortMode == mode);
        btn.setOnAction(e -> {
            serversSortMode = mode;
            renderServersPageContent();
        });
        return btn;
    }

    private List<ServerInstance> sortInstances(List<ServerInstance> list) {
        var out = new ArrayList<>(list);
        switch (serversSortMode) {
            case NAME_ASC -> out.sort(Comparator.comparing(s -> s.name == null ? "" : s.name.toLowerCase()));
            case NAME_DESC -> out.sort(Comparator.comparing((ServerInstance s) -> s.name == null ? "" : s.name.toLowerCase()).reversed());
            case RECENTLY_PLAYED -> out.sort(Comparator.comparingLong((ServerInstance s) -> s.lastJoinedAt).reversed());
            case LEAST_RECENTLY_PLAYED -> out.sort(Comparator.comparingLong(s -> s.lastJoinedAt));
        }
        return out;
    }

    private List<AddedServersStore.AddedServer> sortAdded(List<AddedServersStore.AddedServer> list) {
        var out = new ArrayList<>(list);
        switch (serversSortMode) {
            case NAME_ASC -> out.sort(Comparator.comparing(s -> s.name().toLowerCase()));
            case NAME_DESC -> out.sort(Comparator.comparing((AddedServersStore.AddedServer s) -> s.name().toLowerCase()).reversed());
            case RECENTLY_PLAYED -> out.sort(Comparator.comparingLong((AddedServersStore.AddedServer s) -> s.lastJoinedAt).reversed());
            case LEAST_RECENTLY_PLAYED -> out.sort(Comparator.comparingLong(s -> s.lastJoinedAt));
        }
        return out;
    }

    private enum ServerSortMode { NAME_ASC, NAME_DESC, RECENTLY_PLAYED, LEAST_RECENTLY_PLAYED }
    private ServerSortMode serversSortMode = ServerSortMode.RECENTLY_PLAYED;
    /** Whether the Servers page's filter/sort row is shown -- hidden by default, toggled by the funnel button. */
    private boolean serversFilterVisible = false;

    private void renderServersPageContent() {
        serversPageContent.getChildren().clear();

        // ---- Header: title, sort buttons, Add Server, Create Server ----
        Label heading = new Label("Servers");
        heading.getStyleClass().add("card-heading");

        ToggleGroup sortGroup = new ToggleGroup();
        ToggleButton nameAscBtn = sortToggleButton("Name A-Z", ServerSortMode.NAME_ASC, sortGroup);
        ToggleButton nameDescBtn = sortToggleButton("Name Z-A", ServerSortMode.NAME_DESC, sortGroup);
        ToggleButton recentBtn = sortToggleButton("Recently Played", ServerSortMode.RECENTLY_PLAYED, sortGroup);
        ToggleButton leastRecentBtn = sortToggleButton("Least Recently Played", ServerSortMode.LEAST_RECENTLY_PLAYED, sortGroup);
        HBox sortBar = new HBox(6, nameAscBtn, nameDescBtn, recentBtn, leastRecentBtn);
        // The filter/sort row is hidden by default and revealed by the funnel button next to Add
        // Server. `managed` is toggled too so a hidden row leaves no gap in the layout.
        sortBar.setVisible(serversFilterVisible);
        sortBar.setManaged(serversFilterVisible);

        Button addServerBtn = new Button("+  Add Server");
        addServerBtn.getStyleClass().add("pill-button");
        addServerBtn.setOnAction(e -> openAddServerDialog());

        // Filter toggle: an SVG funnel (IconFactory), so it renders identically on every device
        // rather than depending on an emoji/unicode glyph being installed. Sits next to Add Server.
        ToggleButton filterToggle = new ToggleButton();
        filterToggle.getStyleClass().add("pill-button");
        setButtonIconOnly(filterToggle, IconFactory.Icon.FILTER);
        filterToggle.setSelected(serversFilterVisible);
        filterToggle.setTooltip(new Tooltip(serversFilterVisible ? "Hide filters" : "Show filters"));
        filterToggle.setOnAction(e -> {
            serversFilterVisible = filterToggle.isSelected();
            sortBar.setVisible(serversFilterVisible);
            sortBar.setManaged(serversFilterVisible);
            filterToggle.setTooltip(new Tooltip(serversFilterVisible ? "Hide filters" : "Show filters"));
        });

        Button createServerBtn = new Button("+  Create Server");
        createServerBtn.getStyleClass().add("play-button");
        createServerBtn.setOnAction(e -> openCreateServerDialog());

        Button refreshServersBtn = refreshIconButton("Refresh servers", this::renderServersPageContent);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, heading, headerSpacer, refreshServersBtn, filterToggle, addServerBtn, createServerBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        serversPageContent.getChildren().addAll(headerRow, sortBar);

        // ---- YOUR SERVERS ----
        serversPageContent.getChildren().add(sectionLabel("YOUR SERVERS"));
        var yourServers = sortInstances(serverStore.listAll());
        if (yourServers.isEmpty()) {
            Label none = new Label("No servers yet -- hit \"Create Server\" to host your first one.");
            none.getStyleClass().add("notice-label");
            serversPageContent.getChildren().add(none);
        } else {
            FlowPane yourGrid = new FlowPane(16, 16);
            for (var server : yourServers) yourGrid.getChildren().add(buildYourServerCard(server));
            serversPageContent.getChildren().add(yourGrid);
        }

        // ---- ADDED SERVERS ----
        serversPageContent.getChildren().add(sectionLabel("ADDED SERVERS"));
        var added = sortAdded(addedServersStore.list());
        if (added.isEmpty()) {
            Label none = new Label("No bookmarked servers yet -- \"Add Server\" saves a DeyServer or any Minecraft server address for quick joining later.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            serversPageContent.getChildren().add(none);
        } else {
            for (var s : added) serversPageContent.getChildren().add(buildAddedServerRow(s));
        }

        // ---- FRIENDS PLAYING NOW ----
        serversPageContent.getChildren().add(sectionLabel("FRIENDS PLAYING NOW"));
        if (friendsService == null) {
            Label none = new Label("Set up Friends (see GITHUB_SETUP.md) to see when friends are on a joinable server.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            serversPageContent.getChildren().add(none);
        } else {
            PlayerIdentity active = identityStore.getActive();
            var cached = active != null ? friendsCache.load() : null;
            renderFriendsPlayingNow(cached);
            if (active != null) {
                Task<FriendsService.FriendsView> task = new Task<>() {
                    @Override
                    protected FriendsService.FriendsView call() throws Exception {
                        return friendsService.load(active.uuid);
                    }
                };
                task.setOnSucceeded(e -> {
                    friendsCache.save(task.getValue());
                    // Only refresh this section in-place if we're still looking at the Servers page.
                    if (serversPageContent.getScene() != null) renderFriendsPlayingNow(task.getValue());
                });
                new Thread(task, "servers-friends-refresh").start();
            }
        }
    }

    private void renderFriendsPlayingNow(FriendsService.FriendsView view) {
        // Remove any previously-rendered "playing now" rows + stop their pulse before re-adding.
        serversPageContent.getChildren().removeIf(n -> "friends-playing-now-row".equals(n.getUserData()));
        clearWavePulseDots();
        if (view == null) return;

        var playing = view.friends().stream()
                .map(f -> java.util.Map.entry(f, view.allUsers().get(f.uuid)))
                // Confirmed friends only, checked explicitly: this row hands friends a Join button
                // straight into someone's server, so the friendship is re-verified here rather than
                // trusted to "the list we iterated came from friends".
                .filter(e -> isFriend(e.getKey().uuid, view)
                        && effectivelyOnline(e.getValue())
                        && e.getValue().serverAddress != null && !e.getValue().serverAddress.isBlank())
                .toList();

        if (playing.isEmpty()) {
            Label none = new Label("No friends currently playing on a joinable server.");
            none.getStyleClass().add("notice-label");
            none.setUserData("friends-playing-now-row");
            serversPageContent.getChildren().add(none);
            return;
        }
        for (var e : playing) {
            var friend = e.getKey();
            var entry = e.getValue();
            // Prefer the name the friend actually published for the server they're on (now set for
            // every join, not just their own hosted servers -- see rememberCurrentlyJoined), falling
            // back to matching the address against OUR OWN added/owned servers.
            String display = (entry.currentServerName != null && !entry.currentServerName.isBlank())
                    ? entry.currentServerName : resolveServerDisplay(entry.serverAddress);

            ImageView avatarView = new ImageView();
            avatarView.setFitWidth(28);
            avatarView.setFitHeight(28);
            avatarView.setSmooth(false);
            avatarView.getStyleClass().add("account-btn-face");
            Image cached = friendFaceCached(friend.uuid);
            avatarView.setImage(cached != null ? cached : faceThumbnail(null));
            if (cached == null) fetchFriendAvatarAsync(friend.uuid, () -> {
                Image fetched = friendFaceCached(friend.uuid);
                if (fetched != null) avatarView.setImage(fetched); // update only this avatar, no page rebuild
            });

            // Friend's online indicator -- wave-pulsed like the Friends-list dots.
            Region dot = new Region();
            dot.getStyleClass().addAll("account-status-dot", "dot-online");
            WavePulse.instance().register(dot);
            wavePulseDots.add(dot);

            // Server icon + friendly server name so friends recognise where they'd be joining.
            // Uses the friend's published icon when they have one, else fetches the real favicon
            // ourselves (cached to disk) instead of always falling back to a letter avatar.
            Node serverIcon;
            if (entry.currentServerIconUrl != null && !entry.currentServerIconUrl.isBlank()) {
                serverIcon = serverTile(display, entry.currentServerIconUrl, 28);
            } else {
                Path iconCache = serverIconCachePath(entry.serverAddress);
                StackPane iconTile = serverIconTile(display, Files.exists(iconCache) ? iconCache : null, 28);
                if (!Files.exists(iconCache)) pingForIconAsync(entry.serverAddress, iconTile, iconCache, 28);
                serverIcon = iconTile;
            }
            Label serverLabel = new Label(display);
            serverLabel.getStyleClass().add("notice-label");
            HBox serverBox = new HBox(7, serverIcon, serverLabel);
            serverBox.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(friend.username);
            name.getStyleClass().add("mod-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button joinBtn = new Button();
            joinBtn.getStyleClass().add("pill-button");
            setButtonIcon(joinBtn, IconFactory.Icon.PLAY, "Join");
            ensureJoinGlow(joinBtn); // the friend is online on this server right now, so it's joinable
            joinBtn.setOnAction(ev -> {
                selectNavTab(navHomeBtn);
                onPlay(entry.serverAddress);
            });

            HBox row = new HBox(10, avatarView, dot, name, serverBox, spacer, joinBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("mod-row");
            row.setUserData("friends-playing-now-row");
            serversPageContent.getChildren().add(row);
        }
    }

    private VBox buildYourServerCard(ServerInstance server) {
        boolean running = runningServers.containsKey(server.id) && runningServers.get(server.id).isRunning();

        // A custom server-icon.png (set in Properties) is authoritative; otherwise the pinged
        // favicon is cached here and shown once it arrives.
        Path localIcon = serverStore.serverDir(server.id).resolve("server-icon.png");
        boolean hasLocalIcon = Files.exists(localIcon);
        Path faviconCache = serverIconCachePath("owned:" + server.id);
        StackPane iconTile = serverIconTile(server.name, hasLocalIcon ? localIcon : faviconCache, 44);

        Label cardNameLabel = new Label(server.name);
        cardNameLabel.getStyleClass().add("mod-name");
        Label cardSubLabel = new Label(server.type.displayName() + "  ·  " + server.minecraftVersion);
        cardSubLabel.getStyleClass().add("notice-label");

        Label cardStatusBadge = badgeLabel(running);

        // Only this header (not the whole card) opens management on click, so the Join row
        // below has its own buttons that work independently without the click bubbling up.
        VBox cardText = new VBox(6, cardNameLabel, cardSubLabel, cardStatusBadge);
        HBox cardHeader = new HBox(12, iconTile, cardText);
        cardHeader.setAlignment(Pos.CENTER_LEFT);
        cardHeader.setOnMouseClicked(e -> openServerManagementDialog(server));

        HBox cardJoinRow = buildJoinSplitRow(server);
        Button joinMainBtn = cardJoinRow.getChildren().isEmpty()
                ? null : (Button) cardJoinRow.getChildren().get(0);

        VBox card = new VBox(10, cardHeader, cardJoinRow);
        card.setPadding(new Insets(16));
        card.setPrefWidth(260);
        card.getStyleClass().add("skin-library-tile");
        // Live status: only a RUNNING server can answer a status ping, so a stopped one keeps its
        // STOPPED badge without a needless socket attempt.
        if (running) {
            pingServerAsync("localhost:" + server.port,
                    new ServerCardUi(cardStatusBadge, joinMainBtn, iconTile, faviconCache, 44,
                            true, true, !hasLocalIcon));
        }
        return card;
    }

    /**
     * A Join button plus a "▼" arrow that reveals a small popup with the version (locked to
     * this server's exact Minecraft version -- protocol only allows an exact match) and, for
     * anything except Forge, a DEY/Vanilla toggle (defaulting to DEY, matching what was asked --
     * Forge has no DEY client build, so that toggle is skipped entirely for Forge servers).
     */
    private HBox buildJoinSplitRow(ServerInstance server) {
        boolean[] joinUseDey = { server.type != ServerType.FORGE }; // default DEY unless Forge

        Button joinMainBtn = new Button();
        joinMainBtn.getStyleClass().add("pill-button");
        setButtonIcon(joinMainBtn, IconFactory.Icon.PLAY, "Join");
        joinMainBtn.setOnAction(e -> launchIntoOwnServer(server, joinUseDey[0]));

        Button joinArrowBtn = new Button();
        joinArrowBtn.getStyleClass().add("pill-button");
        setButtonIconOnly(joinArrowBtn, IconFactory.Icon.CHEVRON_DOWN);
        joinArrowBtn.setOnAction(e -> {
            Popup joinPopup = new Popup();
            joinPopup.setAutoHide(true);

            VBox popupBox = new VBox(10);
            popupBox.setPadding(new Insets(14));
            popupBox.getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light", "device-code-box");
            popupBox.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
            popupBox.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));

            Label popupVersionValue = new Label(server.minecraftVersion);
            popupVersionValue.getStyleClass().add("mod-name");
            Label popupVersionNote = new Label("Only this exact version can connect to this server.");
            popupVersionNote.getStyleClass().add("notice-label");
            popupBox.getChildren().addAll(sectionLabel("VERSION"), popupVersionValue, popupVersionNote);

            if (server.type != ServerType.FORGE) {
                ToggleGroup popupModeGroup = new ToggleGroup();
                ToggleButton popupDeyBtn = new ToggleButton("DEY");
                popupDeyBtn.getStyleClass().add("pill-button");
                popupDeyBtn.setToggleGroup(popupModeGroup);
                ToggleButton popupVanillaBtn = new ToggleButton("Vanilla");
                popupVanillaBtn.getStyleClass().add("pill-button");
                popupVanillaBtn.setToggleGroup(popupModeGroup);
                (joinUseDey[0] ? popupDeyBtn : popupVanillaBtn).setSelected(true);
                popupDeyBtn.setOnAction(modeEvent -> joinUseDey[0] = true);
                popupVanillaBtn.setOnAction(modeEvent -> joinUseDey[0] = false);
                HBox popupModeRow = new HBox(8, popupDeyBtn, popupVanillaBtn);
                popupBox.getChildren().addAll(sectionLabel("MODE"), popupModeRow);
            }

            Button popupJoinBtn = new Button();
            popupJoinBtn.getStyleClass().add("play-button");
            setButtonIcon(popupJoinBtn, IconFactory.Icon.PLAY, "Join");
            popupJoinBtn.setOnAction(joinEvent -> {
                joinPopup.hide();
                launchIntoOwnServer(server, joinUseDey[0]);
            });
            popupBox.getChildren().add(popupJoinBtn);

            joinPopup.getContent().add(popupBox);
            var arrowBounds = joinArrowBtn.localToScreen(joinArrowBtn.getBoundsInLocal());
            joinPopup.show(joinArrowBtn, arrowBounds.getMinX(), arrowBounds.getMaxY() + 4);
        });

        return new HBox(4, joinMainBtn, joinArrowBtn);
    }

    /** Shared by the server-card Join button and its popup's confirm button -- launches straight into this locally-hosted server. */
    private void launchIntoOwnServer(ServerInstance server, boolean useDey) {
        if (identityStore.getActive() == null) {
            log("Set up an account first (Account button) before joining a server.");
            return;
        }
        boolean deyEligible = server.type != ServerType.FORGE;
        boolean actuallyDey = useDey && deyEligible;
        setMode(actuallyDey);
        // Bug fixed here: setMode(true) restricts modLoaderBox to ONLY "Fabric" (that's the
        // whole point of DEY mode), but this used to still try setting it to "Vanilla" for
        // Vanilla/Purpur servers while in DEY mode -- an invalid value not in that list, which
        // silently failed to select anything real and broke the join. DEY mode is always
        // Fabric-based regardless of server type (a Fabric client connects fine to
        // Vanilla/Purpur servers -- Fabric doesn't touch the network protocol), so it always
        // gets Fabric plus DEY's forced Sodium/Fabric API mods for that version. Only when NOT
        // using DEY does the loader need to actually match the server's own type.
        String loader = actuallyDey ? "Fabric" : switch (server.type) {
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
            case VANILLA, PURPUR -> "Vanilla";
        };
        if (!versionBox.getItems().contains(server.minecraftVersion)) versionBox.getItems().add(server.minecraftVersion);
        versionBox.setValue(server.minecraftVersion);
        modLoaderBox.setValue(loader);

        String target = "localhost:" + server.port;
        Platform.runLater(() -> {
            selectNavTab(navHomeBtn);
            onPlay(target);
        });
    }

    private HBox buildAddedServerRow(AddedServersStore.AddedServer s) {
        VBox textBox = new VBox(2);
        Label name = new Label(s.name());
        name.getStyleClass().add("mod-name");
        name.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(name, new Tooltip("Click the name to rename this server"));
        Label address = new Label(s.address());
        address.getStyleClass().add("notice-label");
        textBox.getChildren().addAll(name, address);
        name.setOnMouseClicked(ev -> startInlineRename(s, textBox, name));

        Path iconCache = serverIconCachePath(s.address());
        StackPane iconTile = serverIconTile(s.name(), iconCache, 40);

        // Filled in by the background ping (Checking... -> ONLINE · N/M players / OFFLINE).
        Label status = new Label("Checking...");
        status.getStyleClass().add("badge-offline");
        Circle statusDot = new Circle(3.6);
        statusDot.getStyleClass().add("status-dot-offline");
        status.setGraphic(statusDot);
        status.setGraphicTextGap(6);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button joinBtn = new Button();
        joinBtn.getStyleClass().add("pill-button");
        setButtonIcon(joinBtn, IconFactory.Icon.PLAY, "Join");
        joinBtn.setOnAction(e -> {
            addedServersStore.touchLastJoined(s.id());
            selectNavTab(navHomeBtn);
            onPlay(s.address());
        });
        Button removeBtn = new Button("Remove");
        removeBtn.getStyleClass().add("pill-button");
        removeBtn.setOnAction(e -> {
            addedServersStore.remove(s.id());
            renderServersPageContent();
        });

        HBox row = new HBox(12, iconTile, textBox, status, spacer, joinBtn, removeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        pingServerAsync(s.address(), new ServerCardUi(status, joinBtn, iconTile, iconCache, 40,
                false, false, true));
        return row;
    }

    /**
     * Turns an added server's name label into an inline editor. Enter or clicking away commits the
     * new name (persisted by AddedServersStore), Escape cancels; either way the row is re-rendered.
     */
    private void startInlineRename(AddedServersStore.AddedServer s, VBox textBox, Label name) {
        int idx = textBox.getChildren().indexOf(name);
        if (idx < 0) return;
        TextField editor = new TextField(s.name());
        editor.getStyleClass().add("input-field");
        editor.setPrefWidth(200);
        textBox.getChildren().set(idx, editor);
        editor.requestFocus();
        editor.selectAll();
        final boolean[] done = {false};
        Runnable commit = () -> {
            if (done[0]) return;
            done[0] = true;
            addedServersStore.rename(s.id(), editor.getText());
            renderServersPageContent();
        };
        editor.setOnAction(e -> commit.run());
        editor.setOnKeyPressed(k -> {
            if (k.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                done[0] = true;
                renderServersPageContent();
            }
        });
        editor.focusedProperty().addListener((o, was, is) -> {
            if (was && !is) commit.run();
        });
    }

    private void openAddServerDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Add Server");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        dialog.getDialogPane().getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        dialog.getDialogPane().getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        TextField nameField = new TextField();
        nameField.setPromptText("Display name, e.g. \"Bob's SMP\"");
        nameField.getStyleClass().add("input-field");
        TextField addressField = new TextField();
        addressField.setPromptText("Address, e.g. mc.example.com:25565");
        addressField.getStyleClass().add("input-field");
        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().addAll("settings-apply-button", "settings-apply-button-ready");
        saveBtn.setOnAction(e -> {
            if (addressField.getText().isBlank()) return;
            String name = nameField.getText().isBlank() ? addressField.getText() : nameField.getText();
            addedServersStore.add(name, addressField.getText().trim());
            renderServersPageContent();
            dialog.hide();
        });

        VBox content = new VBox(14, sectionLabel("NAME"), nameField, sectionLabel("ADDRESS"), addressField, saveBtn);
        content.setPadding(new Insets(24));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(420);
        dialog.showAndWait();
    }

    private void openCreateServerDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Create Server");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        dialog.getDialogPane().getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        dialog.getDialogPane().getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.setResizable(true);

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. \"Our SMP\"");
        nameField.getStyleClass().add("input-field");

        ComboBox<ServerType> typeBox = new ComboBox<>();
        typeBox.getItems().addAll(ServerType.values());
        typeBox.setValue(ServerType.VANILLA);
        typeBox.getStyleClass().add("input-field");
        typeBox.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> versionBox2 = new ComboBox<>();
        versionBox2.setPromptText("Loading versions...");
        versionBox2.getStyleClass().add("input-field");
        versionBox2.setMaxWidth(Double.MAX_VALUE);

        Task<List<VersionManifest.VersionEntry>> loadTask = new Task<>() {
            @Override
            protected List<VersionManifest.VersionEntry> call() throws Exception {
                return new VersionManifest().fetchAll();
            }
        };
        loadTask.setOnSucceeded(e -> {
            versionBox2.getItems().clear();
            for (var v : loadTask.getValue()) {
                if (v.type().equals("release")) versionBox2.getItems().add(v.id());
            }
            if (!versionBox2.getItems().isEmpty()) versionBox2.setValue(versionBox2.getItems().get(0));
        });
        new Thread(loadTask, "server-version-load").start();

        Slider minRam = new Slider(512, 8192, 1024);
        Slider maxRam = new Slider(1024, 16384, 2048);
        Label minRamLabel = new Label("Min RAM: 1024 MB");
        minRamLabel.getStyleClass().add("settings-value-label");
        Label maxRamLabel = new Label("Max RAM: 2048 MB");
        maxRamLabel.getStyleClass().add("settings-value-label");
        minRam.valueProperty().addListener((o, a, b) -> minRamLabel.setText("Min RAM: " + b.intValue() + " MB"));
        maxRam.valueProperty().addListener((o, a, b) -> maxRamLabel.setText("Max RAM: " + b.intValue() + " MB"));

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("notice-label");
        errorLabel.setWrapText(true);

        Button createBtn = new Button("Create Server");
        createBtn.getStyleClass().addAll("settings-apply-button", "settings-apply-button-ready");
        createBtn.setOnAction(e -> {
            if (nameField.getText().isBlank() || versionBox2.getValue() == null) {
                errorLabel.setText("Give it a name and pick a version first.");
                return;
            }
            ServerInstance server = new ServerInstance(nameField.getText().trim(), typeBox.getValue(), versionBox2.getValue());
            server.ramMinMb = (int) minRam.getValue();
            server.ramMaxMb = (int) maxRam.getValue();
            serverStore.save(server);
            renderServersPageContent();
            dialog.hide();
            // Deferred one pulse: opening a second modal Dialog synchronously in the same
            // handler that just called hide() on this one is a known JavaFX timing issue --
            // the new dialog's first layout/paint pass can land before its stylesheet is
            // actually applied, showing a blank white window for a frame (or longer). Platform.
            // runLater lets this dialog's close fully settle first.
            Platform.runLater(() -> openServerManagementDialog(server));
        });

        VBox content = new VBox(14,
                sectionLabel("NAME"), nameField,
                sectionLabel("SERVER TYPE"), typeBox,
                sectionLabel("MINECRAFT VERSION"), versionBox2,
                sectionLabel("MEMORY"), minRamLabel, minRam, maxRamLabel, maxRam,
                errorLabel, createBtn);
        content.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefSize(460, 620);
        dialog.showAndWait();
    }

    // ---- Server management dialog: Console/Version, Properties, Permissions, Players tabs ----
    private TextArea serverConsoleArea;

    private void openServerManagementDialog(ServerInstance server) {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("account-skin-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Console", buildServerConsoleTab(server)),
                new Tab("Properties", buildServerPropertiesTab(server)),
                new Tab("Players", buildServerPlayersTab(server)),
                new Tab("Addons", buildServerAddonsTab(server)),
                new Tab("Files", buildServerFilesTab(server)),
                new Tab("Settings", buildServerSettingsTab(server)),
                new Tab("Permissions", buildServerPermissionsTab(server))
        );
        // Borderless (Mods-style) window, sized larger so all the console/properties/players/etc.
        // tabs have room to breathe (proportional to the display, like the Mods window).
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.max(960, Math.min(1560, vb.getWidth() * 0.85));
        double h = Math.max(700, Math.min(1040, vb.getHeight() * 0.88));
        openShellWindow("server-" + server.id, server.name, tabs, 700, 540, w, h);
    }

/**
     * Internet hosting for a server via the free playit.ggs tunnel. Start/Stop controls plus the
     * resulting public address (auto-parsed from the agent, or pasted from the playit dashboard),
     * which -- while this server runs and "Allow friends to join" is on -- is what gets shared to
     * online friends automatically (see publishPresenceWithServer).
     */
    private Node buildInternetShareSection(ServerInstance server) {
        VBox box = new VBox(12);
        box.getStyleClass().add("server-share-card");

        Label title = sectionLabel("SHARE OVER THE INTERNET -- PLAYIT (FREE)");
        Label stateBadge = new Label("STOPPED");
        stateBadge.getStyleClass().add("badge-offline");
        stateBadge.getStyleClass().add("server-share-badge");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(10, title, titleSpacer, stateBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Expose this server to players on any network without touching a "
                + "router: with the free playit.ggs tunnel, add a TCP tunnel to 127.0.0.1:" + server.port
                + " in the playit dashboard, Start here, and your public address is shared automatically "
                + "to online friends whenever this server is running.");
        note.getStyleClass().add("notice-label");
        note.setWrapText(true);

        Button startBtn = new Button();
        setButtonIcon(startBtn, IconFactory.Icon.PLAY, "Start Tunnel");
        startBtn.getStyleClass().add("pill-button");
        Button stopBtn = new Button();
        setButtonIcon(stopBtn, IconFactory.Icon.STOP, "Stop Tunnel");
        stopBtn.getStyleClass().add("pill-button");
        stopBtn.setDisable(true);
        Button openBtn = new Button("Open playit.ggs");
        openBtn.getStyleClass().add("pill-button");
        openBtn.setOnAction(e -> openUrl("https://playit.gg"));
        Button pairBtn = new Button("Pair / Claim");
        pairBtn.getStyleClass().add("pill-button");

        Label status = new Label("Stopped.");
        status.getStyleClass().add("notice-label");
        status.setWrapText(true);

        TextField publicField = new TextField();
        publicField.getStyleClass().add("device-url-field");
        publicField.setPromptText("Public address appears here when connected");
        HBox.setHgrow(publicField, Priority.ALWAYS);
        Button copyBtn = new Button();
        setButtonIcon(copyBtn, IconFactory.Icon.CLIPBOARD, "Copy");
        copyBtn.getStyleClass().add("pill-button");
        copyBtn.setOnAction(ev -> {
            String a = publicField.getText().trim();
            if (a.isEmpty()) return;
            var cb = javafx.scene.input.Clipboard.getSystemClipboard();
            var c = new javafx.scene.input.ClipboardContent();
            c.putString(a);
            cb.setContent(c);
            setButtonIcon(copyBtn, IconFactory.Icon.CHECK, "Copied");
        });
        HBox fieldRow = new HBox(8, publicField, copyBtn);

        Runnable renderState = () -> {
            PlayitTunnel t = serverTunnels.get(server.id);
            boolean on = t != null && t.isRunning();
            startBtn.setDisable(on);
            stopBtn.setDisable(!on);
            if (on) {
                String pub = t.publicAddress();
                if (pub != null && !pub.isBlank()) publicField.setText(pub);
            }
            stateBadge.setText(on ? "LIVE" : "STOPPED");
            stateBadge.getStyleClass().removeAll("badge-online", "badge-offline");
            stateBadge.getStyleClass().add(on ? "badge-online" : "badge-offline");
        };

        startBtn.setOnAction(e -> startPlayitTunnel(server, status, publicField, renderState));
        stopBtn.setOnAction(e -> stopPlayitTunnel(server, status, renderState));
        pairBtn.setOnAction(e -> showPlayitPairDialog(server, status, renderState));

        HBox controls = new HBox(8, startBtn, stopBtn, openBtn, pairBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        HBox liveRow = new HBox(8, new Label("Public address:"), fieldRow);
        liveRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(headerRow, note, controls, liveRow, status);
        renderState.run();
        return box;
    }

    /** True if this server's process is currently running. */
    private boolean isServerRunning(ServerInstance server) {
        var pm = runningServers.get(server.id);
        return pm != null && pm.isRunning();
    }
/** Ensures the tunnel object exists, then starts the playit agent in the background. */
    private void startPlayitTunnel(ServerInstance server, Label status, TextField publicField,
                                   Runnable renderState) {
        PlayitTunnel t = serverTunnels.get(server.id);
        if (t == null) {
            t = new PlayitTunnel(gameFiles.root.resolve("tools"),
                    gameFiles.root.resolve("tunnels").resolve(server.id));
            serverTunnels.put(server.id, t);
        }
        PlayitTunnel tunnel = t;
        status.setText("Ensuring the free playit agent (one-time download)...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                tunnel.start(new PlayitTunnel.Listener() {
                    @Override
                    public void onLine(String line) {
                        String trim = line.length() > 150 ? line.substring(0, 150) : line;
                        Platform.runLater(() -> status.setText("Agent: " + trim));
                    }
                    @Override
                    public void onAddress(String addr) {
                        Platform.runLater(() -> {
                            publicField.setText(addr);
                            status.setText("Public address detected: " + addr
                                    + (isServerRunning(server) && server.allowFriendsJoin
                                    ? "  -- shared with online friends." : ""));
                            publishPresenceWithServer(server, isServerRunning(server));
                        });
                    }
                    @Override
                    public void onPairingNeeded(String hint) {
                        String trim = hint.length() > 150 ? hint.substring(0, 150) : hint;
                        Platform.runLater(() -> status.setText("Needs pairing: " + trim
                                + "  (start the tunnel already -- then Pair / Claim)"));
                    }
                });
                return null;
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            renderState.run();
            status.setText("Tunnel started. Add a TCP tunnel to 127.0.0.1:" + server.port
                    + " in the playit dashboard and its address should show above.");
        }));
        task.setOnFailed(ev -> Platform.runLater(() -> {
            renderState.run();
            status.setText("Couldn't start tunnel: " + task.getException().getMessage());
        }));
        new Thread(task, "playit-start-" + server.id).start();
    }

    private void stopPlayitTunnel(ServerInstance server, Label status, Runnable renderState) {
        PlayitTunnel t = serverTunnels.get(server.id);
        if (t == null) return;
        status.setText("Stopping tunnel...");
        new Thread(() -> {
            t.stop();
            Platform.runLater(() -> {
                renderState.run();
                status.setText("Tunnel stopped.");
                publishPresenceWithServer(server, isServerRunning(server));
            });
        }, "playit-stop-" + server.id).start();
    }

    /** Dialog to paste a one-time playit claim code and run the device-flow pairing against the agent. */
    private void showPlayitPairDialog(ServerInstance server, Label status, Runnable renderState) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("Pair with playit.ggs -- " + server.name);
        d.getDialogPane().getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        d.getDialogPane().getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        d.getDialogPane().getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        Label how = new Label("In the playit.ggs dashboard, get your agent claim code (or agent "
                + "token), paste it here and click Pair. This logs the agent into your free account; "
                + "it needs to happen once per server.");
        how.getStyleClass().add("notice-label");
        how.setWrapText(true);
        TextField codeField = new TextField();
        codeField.setPromptText("Claim code / agent token");
        codeField.getStyleClass().add("input-field");
        Button openBtn = new Button("Get claim code on playit.ggs");
        openBtn.getStyleClass().add("pill-button");
        openBtn.setOnAction(e -> openUrl("https://playit.gg/agent"));
        Button pairBtn = new Button();
        setButtonIcon(pairBtn, IconFactory.Icon.CHECK, "Pair");
        pairBtn.getStyleClass().addAll("settings-apply-button", "settings-apply-button-ready");
        Label out = new Label();
        out.getStyleClass().add("notice-label");
        out.setWrapText(true);
        pairBtn.setOnAction(ev -> {
            if (codeField.getText().isBlank()) {
                out.setText("Paste a claim code first.");
                return;
            }
            pairBtn.setDisable(true);
            out.setText("Pairing (up to ~45s)...");
            Task<PlayitTunnel.PairResult> task = new Task<>() {
                @Override
                protected PlayitTunnel.PairResult call() throws Exception {
                    PlayitTunnel t = serverTunnels.get(server.id);
                    if (t == null) {
                        t = new PlayitTunnel(gameFiles.root.resolve("tools"),
                                gameFiles.root.resolve("tunnels").resolve(server.id));
                        serverTunnels.put(server.id, t);
                    }
                    return t.pair(codeField.getText());
                }
            };
            task.setOnSucceeded(r -> Platform.runLater(() -> {
                pairBtn.setDisable(false);
                var res = task.getValue();
                if (res.paired()) {
                    out.setText("Paired! You can close this and Start the tunnel.");
                    renderState.run();
                } else {
                    String tail = res.output().isEmpty() ? "(no output)"
                            : res.output().get(res.output().size() - 1);
                    out.setText("Pairing didn't confirm. Last message: " + tail
                            + "  -- double-check the code.");
                }
            }));
            task.setOnFailed(r -> Platform.runLater(() -> {
                pairBtn.setDisable(false);
                out.setText("Pairing failed: " + task.getException().getMessage());
            }));
            new Thread(task, "playit-pair").start();
        });
        content.getChildren().addAll(how, codeField, new HBox(10, openBtn, pairBtn), out);
        d.getDialogPane().setContent(content);
        d.showAndWait();
    }
    /**
     * Applies a version change to a stopped server. This is the fix for "created a server, changed it
     * to a lower version, and it crashes on the next launch", and it works in three parts:
     *
     * <ol>
     *   <li><b>Know which way it goes.</b> The comparison is made against the version that actually
     *       loaded this world last time ({@code lastRunVersion}), not just the configured one, because
     *       that is what the world's data version really belongs to -- see {@link VersionChangePlan}.</li>
     *   <li><b>Protect what an older server cannot read.</b> A downgrade (or an id we can't order, such
     *       as a snapshot) offers to set the world aside first. Set aside means RENAMED, with the old
     *       version and the date in the name -- never deleted -- and the same treatment is applied to
     *       the mods folder, since mods built for a newer Minecraft version are the other thing that
     *       kills an older server at startup.</li>
     *   <li><b>Throw away the old software.</b> The jar, Forge's launch scripts and the Fabric/Forge
     *       libraries are removed so the next Start must download the ones for the chosen version
     *       ({@link ServerSoftwareMarker} records what ends up installed, and the downloader verifies
     *       it on every start).</li>
     * </ol>
     */
    private void applyServerVersionChange(ServerInstance server, String chosen,
                                          List<VersionManifest.VersionEntry> manifestList, Label note) {
        String worldVersion = (server.lastRunVersion != null && !server.lastRunVersion.isBlank())
                ? server.lastRunVersion : server.minecraftVersion;
        VersionChangePlan.Kind kind = VersionChangePlan.classify(worldVersion, chosen, manifestList);

        boolean backUpWorld = false;
        if (kind.needsWorldBackupOffer() && hasAnyWorld(server)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Change server version");
            confirm.setHeaderText("Minecraft " + worldVersion + " -> " + chosen
                    + (kind == VersionChangePlan.Kind.DOWNGRADE ? "  (an older version)" : ""));
            confirm.setContentText("This server's world was saved by Minecraft " + worldVersion + ", and "
                    + "an older server cannot read a world saved by a newer one -- that is what made a "
                    + "downgrade end in a crash on the next launch.\n\n"
                    + "BACK UP WORLD: the world is kept on disk under a new name (with " + worldVersion
                    + " and the date), mods from " + worldVersion + " are set aside too, and the server "
                    + "starts on a fresh world.\n\n"
                    + "KEEP WORLD: nothing is moved, but the server may still refuse to start.");
            ButtonType backupBtn = new ButtonType("Back up world & start fresh", ButtonBar.ButtonData.OK_DONE);
            ButtonType keepBtn = new ButtonType("Keep world (may crash)", ButtonBar.ButtonData.NO);
            confirm.getButtonTypes().setAll(backupBtn, keepBtn, ButtonType.CANCEL);
            var picked = confirm.showAndWait();
            if (picked.isEmpty() || picked.get() == ButtonType.CANCEL) return;
            backUpWorld = picked.get() == backupBtn;
        }

        Path dir = serverStore.serverDir(server.id);
        java.util.List<String> moved = new java.util.ArrayList<>();
        boolean moveFailed = false;
        if (backUpWorld) {
            java.time.LocalDateTime when = java.time.LocalDateTime.now();
            for (String worldDirName : VersionChangePlan.worldDirNames(serverLevelName(server, dir))) {
                Path from = dir.resolve(worldDirName);
                if (!Files.isDirectory(from)) continue;
                Path to = dir.resolve(VersionChangePlan.backupDirName(worldDirName, worldVersion, when));
                try {
                    Files.move(from, to);
                    moved.add(worldDirName + " -> " + to.getFileName());
                } catch (Exception ex) {
                    moveFailed = true;
                    note.setText("Couldn't set the world aside (" + ex.getMessage() + "), so nothing was "
                            + "changed. Close anything using the world and try again.");
                }
            }
            // Mods belong to the version that installed them: a mod built for a newer Minecraft version
            // is the other thing that kills an older server at startup, so they go with the world --
            // renamed, never deleted, so they can be moved back for the newer version.
            Path modsDir = dir.resolve("mods");
            if (!moveFailed && hasFiles(modsDir)) {
                Path to = dir.resolve(VersionChangePlan.backupDirName("mods", worldVersion, when));
                try {
                    Files.move(modsDir, to);
                    moved.add("mods -> " + to.getFileName());
                } catch (Exception ex) {
                    note.setText("The world was set aside, but mods/ couldn't be moved ("
                            + ex.getMessage() + "). Remove the mods built for " + worldVersion
                            + " by hand before starting, or the server may still refuse to start.");
                }
            }
        }
        if (moveFailed) return;

        // Old software must go even when the world is kept: launching the previous version's jar was
        // the other half of the same bug. (The downloader re-checks this on every start.)
        java.util.List<String> replaced = ServerSoftwareMarker.purgeVersionBoundSoftware(dir);
        server.minecraftVersion = chosen;
        serverStore.save(server);
        finishVersionChange(server, chosen, moved, replaced, note);
    }

    /**
     * Reports what a version change did -- including exactly what was set aside, so the user can find
     * their old world -- and rebuilds the servers page so the new version shows on the card.
     */
    private void finishVersionChange(ServerInstance server, String chosen, java.util.List<String> moved,
                                     java.util.List<String> replaced, Label note) {
        StringBuilder summary = new StringBuilder("Version changed to " + chosen + ".");
        if (!replaced.isEmpty()) {
            summary.append(" Removed the old server software (").append(String.join(", ", replaced))
                    .append(") -- the next Start downloads ")
                    .append(server.type != null ? server.type.displayName() : "the server")
                    .append(" ").append(chosen).append(".");
        } else {
            summary.append(" The next Start downloads this version's server software.");
        }
        if (!moved.isEmpty()) {
            summary.append(" Kept, renamed: ").append(String.join("; ", moved)).append(".");
        }
        note.setText(summary.toString());
        renderServersPageContent();

        if (!moved.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, summary + "\n\nNothing was deleted -- everything set "
                    + "aside is still inside this server's folder.", ButtonType.OK).showAndWait();
        }
    }

    /** This server's world folder name, from server.properties (defaults to "world"). */
    private String serverLevelName(ServerInstance server, Path serverDir) {
        try {
            String name = new ServerPropertiesManager(serverDir).read().get("level-name");
            return (name == null || name.isBlank()) ? "world" : name.trim();
        } catch (Exception e) {
            return "world";
        }
    }

    /** True when this server already has a world on disk, so a downgrade has something to protect. */
    private boolean hasAnyWorld(ServerInstance server) {
        Path dir = serverStore.serverDir(server.id);
        for (String worldDirName : VersionChangePlan.worldDirNames(serverLevelName(server, dir))) {
            if (Files.isDirectory(dir.resolve(worldDirName))) return true;
        }
        return false;
    }

    /** True when {@code dir} holds at least one entry (used to skip renaming an empty mods/ folder). */
    private static boolean hasFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private Node buildServerConsoleTab(ServerInstance server) {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20));

        boolean running = runningServers.containsKey(server.id) && runningServers.get(server.id).isRunning();
        Label statusBadge = badgeLabel(running);

        // ---- Version display + change ----
        Label typeLabel = new Label(server.type.displayName());
        typeLabel.getStyleClass().add("notice-label");
        ComboBox<String> serverVersionBox = new ComboBox<>();
        serverVersionBox.getItems().add(server.minecraftVersion);
        serverVersionBox.setValue(server.minecraftVersion);
        serverVersionBox.getStyleClass().add("input-field");
        Task<List<VersionManifest.VersionEntry>> versionsTask = new Task<>() {
            @Override
            protected List<VersionManifest.VersionEntry> call() throws Exception {
                return new VersionManifest().fetchAll();
            }
        };
        versionsTask.setOnSucceeded(ev -> {
            String current = serverVersionBox.getValue();
            serverVersionBox.getItems().clear();
            for (var v : versionsTask.getValue()) if (v.type().equals("release")) serverVersionBox.getItems().add(v.id());
            // Keep this server's own version selectable even when it isn't a "release" in the manifest
            // (the synthetic 26.2 seed, or a snapshot) -- otherwise the combo would come back blank.
            if (current != null && !current.isBlank() && !serverVersionBox.getItems().contains(current)) {
                serverVersionBox.getItems().add(0, current);
            }
            serverVersionBox.setValue(current);
        });
        new Thread(versionsTask, "server-console-versions").start();

        Button changeVersionBtn = new Button("Change Version");
        changeVersionBtn.getStyleClass().add("pill-button");
        Label versionChangeNote = new Label();
        versionChangeNote.getStyleClass().add("notice-label");
        versionChangeNote.setWrapText(true);
        changeVersionBtn.setOnAction(ev -> {
            String chosen = serverVersionBox.getValue();
            if (chosen == null || chosen.equals(server.minecraftVersion)) return;
            if (runningServers.containsKey(server.id) && runningServers.get(server.id).isRunning()) {
                versionChangeNote.setText("Stop the server before changing its version.");
                return;
            }
            applyServerVersionChange(server, chosen, versionsTask.getValue(), versionChangeNote);
        });

        // ---- Copyable server IP (top right) ----
        String localAddress = localIpAddress() + ":" + server.port;
        TextField ipField = new TextField(localAddress);
        ipField.setEditable(false);
        ipField.getStyleClass().add("device-url-field");
        ipField.setPrefWidth(180);
        Button copyIpBtn = new Button();
        copyIpBtn.getStyleClass().add("pill-button");
        setButtonIcon(copyIpBtn, IconFactory.Icon.CLIPBOARD, "Copy");
        copyIpBtn.setOnAction(ev -> {
            var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(localAddress);
            clipboard.setContent(content);
            setButtonIcon(copyIpBtn, IconFactory.Icon.CHECK, "Copied");
        });
        Label ipNote = new Label("Local address -- port-forward or use a tunnel for players outside your network.");
        ipNote.getStyleClass().add("notice-label");
        Region ipSpacer = new Region();
        HBox.setHgrow(ipSpacer, Priority.ALWAYS);
        HBox ipRow = new HBox(8, ipSpacer, ipField, copyIpBtn);
        ipRow.setAlignment(Pos.CENTER_RIGHT);

        Button startBtn = new Button();
        startBtn.getStyleClass().add("play-button");
        setButtonIcon(startBtn, IconFactory.Icon.PLAY, "Start");
        Button stopBtn = new Button();
        stopBtn.getStyleClass().add("pill-button");
        setButtonIcon(stopBtn, IconFactory.Icon.STOP, "Stop");
        stopBtn.setDisable(!running);
        startBtn.setDisable(running);

        // ---- Play on this Server: pick a profile + client version/loader, then launch straight in ----
        ComboBox<String> playAsBox = new ComboBox<>();
        PlayerIdentity currentActive = identityStore.getActive();
        for (var acc : identityStore.loadIndex().accounts()) playAsBox.getItems().add(acc.username);
        if (currentActive != null && !playAsBox.getItems().contains(currentActive.username)) {
            playAsBox.getItems().add(0, currentActive.username);
        }
        playAsBox.setValue(currentActive != null ? currentActive.username : null);
        playAsBox.getStyleClass().add("input-field");

        ComboBox<String> clientVersionBox = new ComboBox<>();
        clientVersionBox.getItems().add(server.minecraftVersion);
        clientVersionBox.setValue(server.minecraftVersion);
        clientVersionBox.getStyleClass().add("input-field");

        boolean[] playUseDey = { server.type != ServerType.FORGE };
        ToggleGroup playModeGroup = new ToggleGroup();
        ToggleButton playDeyBtn = new ToggleButton("DEY");
        playDeyBtn.getStyleClass().add("pill-button");
        playDeyBtn.setToggleGroup(playModeGroup);
        ToggleButton playVanillaBtn = new ToggleButton("Vanilla");
        playVanillaBtn.getStyleClass().add("pill-button");
        playVanillaBtn.setToggleGroup(playModeGroup);
        if (server.type == ServerType.FORGE) {
            playDeyBtn.setDisable(true);
            playVanillaBtn.setDisable(true);
            playVanillaBtn.setSelected(true);
        } else {
            (playUseDey[0] ? playDeyBtn : playVanillaBtn).setSelected(true);
        }
        playDeyBtn.setOnAction(modeEvent -> playUseDey[0] = true);
        playVanillaBtn.setOnAction(modeEvent -> playUseDey[0] = false);
        HBox playModeRow = new HBox(6, playDeyBtn, playVanillaBtn);

        Button playBtn = new Button();
        playBtn.getStyleClass().add("play-button");
        setButtonIcon(playBtn, IconFactory.Icon.PLAY, "Play on this Server");
        playBtn.setOnAction(ev -> {
            String chosenUsername = playAsBox.getValue();
            var match = identityStore.loadIndex().accounts().stream()
                    .filter(a -> a.username.equals(chosenUsername)).findFirst();
            match.ifPresent(a -> identityStore.setActive(a.uuid));

            boolean actuallyDeyForPlay = playUseDey[0] && server.type != ServerType.FORGE;
            setMode(actuallyDeyForPlay);
            // Same fix as launchIntoOwnServer: DEY mode only ever has "Fabric" as a valid
            // modLoaderBox value, so it must never fall through to "Vanilla"/"Forge" here either.
            String loader = actuallyDeyForPlay ? "Fabric" : switch (server.type) {
                case FABRIC -> "Fabric";
                case FORGE -> "Forge";
                case NEOFORGE -> "NeoForge";
                case VANILLA, PURPUR -> "Vanilla";
            };
            String clientVersion = clientVersionBox.getValue();
            if (!versionBox.getItems().contains(clientVersion)) versionBox.getItems().add(clientVersion);
            versionBox.setValue(clientVersion);
            modLoaderBox.setValue(loader);
            syncPlayCardFromActiveIdentity();

            String target = "localhost:" + server.port;
            // Dialogs stack awkwardly if we navigate synchronously out from under this one --
            // same deferred-close pattern as the create-server flow's white-window fix.
            Platform.runLater(() -> {
                selectNavTab(navHomeBtn);
                onPlay(target);
            });
        });

        // Server Controls reorganized into captioned groups inside one wrapping card: RUN
        // (Start/Stop + live status), VERSION (dropdown + Change Version), and PLAY (profile,
        // client version, mode toggle, Play button). Groups wrap to a second line only when the
        // dialog is too narrow to fit them on one row.
        Label playAsLbl = new Label("Play as:");
        playAsLbl.getStyleClass().add("field-label");
        Label clientVersionLbl = new Label("Version:");
        clientVersionLbl.getStyleClass().add("field-label");

        Font captionFont = Font.font(null, FontWeight.BOLD, 10.5);
        Label runCap = captionLabel("RUN", captionFont);
        HBox runCtrls = new HBox(8, typeLabel, statusBadge, startBtn, stopBtn);
        runCtrls.setAlignment(Pos.CENTER_LEFT);
        runCtrls.getStyleClass().add("server-control-group");
        Label verCap = captionLabel("VERSION", captionFont);
        HBox verCtrls = new HBox(8, serverVersionBox, changeVersionBtn);
        verCtrls.setAlignment(Pos.CENTER_LEFT);
        verCtrls.getStyleClass().add("server-control-group");
        Label playCap = captionLabel("PLAY", captionFont);
        HBox playCtrls = new HBox(8,
                playAsLbl, playAsBox, clientVersionLbl, clientVersionBox,
                playModeRow, playBtn);
        playCtrls.setAlignment(Pos.CENTER_LEFT);
        playCtrls.getStyleClass().add("server-control-group");

        FlowPane toolbar = new FlowPane(22, 12);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("server-toolbar");
        toolbar.setMaxWidth(Double.MAX_VALUE);
        toolbar.getChildren().addAll(
                new VBox(5, runCap, runCtrls),
                new VBox(5, verCap, verCtrls),
                new VBox(5, playCap, playCtrls));

        serverConsoleArea = new TextArea();
        serverConsoleArea.setEditable(false);
        serverConsoleArea.getStyleClass().add("log-area");
        serverConsoleArea.setPrefRowCount(16);
        VBox.setVgrow(serverConsoleArea, Priority.ALWAYS);

        TextField commandField = new TextField();
        commandField.setPromptText("Type a server command and press Enter...");
        commandField.getStyleClass().add("input-field");
        HBox.setHgrow(commandField, Priority.ALWAYS);
        Button sendBtn = new Button("Send");
        sendBtn.getStyleClass().add("pill-button");

        Runnable sendCommand = () -> {
            String cmd = commandField.getText().trim();
            if (cmd.isEmpty()) return;
            var pm = runningServers.get(server.id);
            if (pm != null && pm.isRunning()) {
                try {
                    pm.sendCommand(cmd);
                    commandField.clear();
                } catch (Exception ex) {
                    serverConsoleArea.appendText("[DeyLauncher] Couldn't send command: " + ex.getMessage() + "\n");
                }
            }
        };
        sendBtn.setOnAction(e -> sendCommand.run());
        commandField.setOnAction(e -> sendCommand.run());

        startBtn.setOnAction(e -> {
            startBtn.setDisable(true);
            server.lastJoinedAt = System.currentTimeMillis();
            serverStore.save(server);
            serverConsoleArea.appendText("[DeyLauncher] Preparing server files...\n");
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    Path serverDir = serverStore.serverDir(server.id);
                    JavaRuntimeManager runtimeManager = new JavaRuntimeManager(gameFiles.root);
                    // Reuse whatever runtime the equivalent client version needs -- servers have
                    // the same Java-version requirements as their matching client release.
                    VersionManifest manifest = new VersionManifest();
                    var all = manifest.fetchAll();
                    var entry = manifest.findById(all, server.minecraftVersion);
                    JsonObject versionJson = entry != null ? manifest.fetchVersionDetail(entry) : null;
                    String javaBinary = versionJson != null
                            ? runtimeManager.ensureRuntimeFor(versionJson).toString() : "java";

                    Platform.runLater(() -> serverConsoleArea.appendText("[DeyLauncher] Downloading/verifying "
                            + server.type.displayName() + " " + server.minecraftVersion + "...\n"));
                    ServerDownloader downloader = new ServerDownloader(manifest);
                    // The note consumer is how the downloader explains anything it had to replace (e.g.
                    // server files left over from another version) in this server's own console.
                    Path jar = downloader.ensureServerJar(server, serverDir, javaBinary,
                            message -> Platform.runLater(() ->
                                    serverConsoleArea.appendText("[DeyLauncher] " + message + "\n")));

                    // Seed server.properties with a sensible default online-mode the first time a
                    // server runs (before the user ever opens Properties), matching the account
                    // type they're launching with: online -> true, offline -> false. Only applied
                    // if the key doesn't exist yet -- a user's explicit choice is never overwritten.
                    ServerPropertiesManager seedProps = new ServerPropertiesManager(serverDir);
                    var seedMap = seedProps.read();
                    if (!seedMap.containsKey("online-mode")) {
                        PlayerIdentity seedIdentity = identityStore.getActive();
                        boolean seedOnline = seedIdentity != null && seedIdentity.accountType == AccountType.ONLINE;
                        seedMap.putIfAbsent("online-mode", String.valueOf(seedOnline));
                        try {
                            seedProps.write(seedMap);
                        } catch (java.io.IOException ignored) {
                        }
                    }

                    // Best-effort pre-start dependency check: a Fabric server missing a library mod
                    // its mods require dies immediately with "Incompatible mod set". Resolving that
                    // here turns a confusing crash into a one-line note. It costs nothing when
                    // nothing is missing, never blocks a start, and any failure is only logged --
                    // starting the server is still the user's call.
                    if (server.type == ServerType.FABRIC && server.minecraftVersion != null) {
                        try {
                            // Messages are buffered and shown only when something was actually
                            // installed -- otherwise "all dependencies present" would be printed on
                            // every single start.
                            java.util.List<String> depNotes = new java.util.ArrayList<>();
                            var deps = new ServerModDependencyResolver().resolveAndInstall(
                                    serverDir, server.minecraftVersion, server.type.displayName(),
                                    null, depNotes::add);
                            if (deps.installed() > 0) {
                                depNotes.add("Installed " + deps.installed()
                                        + " missing mod dependency(ies) before start.");
                                Platform.runLater(() -> {
                                    for (String note : depNotes) {
                                        serverConsoleArea.appendText("[DeyLauncher] " + note + "\n");
                                    }
                                });
                            }
                        } catch (Exception ignored) {
                            // A dependency lookup must never hold up a start.
                        }
                    }

                    Platform.runLater(() -> serverConsoleArea.appendText("[DeyLauncher] Starting "
                            + server.type.displayName() + " " + server.minecraftVersion
                            + (jar != null ? " (" + jar.getFileName() + ")" : " (Forge launch args)")
                            + "...\n"));
                    ServerProcessManager pm = new ServerProcessManager();
                    Process process = pm.start(server, serverDir, jar, javaBinary);
                    runningServers.put(server.id, pm);

                    // Hints for the failure signatures Minecraft/Forge/Fabric actually print, shown once
                    // each: a version change that still goes wrong must not look like a bare "exited with
                    // code 1" with no explanation (see ServerStartDiagnostics).
                    java.util.List<ServerStartDiagnostics.Hint> hintsShown = new java.util.ArrayList<>();
                    boolean[] reportedReady = { false };
                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String finalLine = line;
                            pm.observeConsoleLine(finalLine);
                            ServerStartDiagnostics.Hint hint = ServerStartDiagnostics.hintFor(finalLine);
                            if (ServerStartDiagnostics.isNew(hintsShown, hint)) {
                                hintsShown.add(hint);
                                Platform.runLater(() -> serverConsoleArea.appendText(
                                        "[DeyLauncher] " + hint.message() + "\n"));
                            }
                            // "Done (...)! For help, type..." is the server saying it is actually up, which
                            // is the only moment we know this version really can read the world -- so that
                            // is when it becomes the baseline the NEXT version change is measured against.
                            if (!reportedReady[0] && finalLine.contains("Done (")) {
                                reportedReady[0] = true;
                                server.lastRunVersion = server.minecraftVersion;
                                serverStore.save(server);
                            }
                            Platform.runLater(() -> serverConsoleArea.appendText(finalLine + "\n"));
                        }
                    }
                    int exit = process.waitFor();
                    Platform.runLater(() -> {
                        serverConsoleArea.appendText("[DeyLauncher] Server exited with code " + exit + "\n");
                        startBtn.setDisable(false);
                        stopBtn.setDisable(true);
                        setBadge(statusBadge, false);
                        publishPresenceWithServer(server, false);
                        renderServersPageContent();
                    });
                    return null;
                }
            };
            task.setOnFailed(failEvent -> Platform.runLater(() -> {
                serverConsoleArea.appendText("[DeyLauncher] Failed to start: " + task.getException().getMessage() + "\n");
                startBtn.setDisable(false);
            }));
            new Thread(task, "server-start-" + server.id).start();
            stopBtn.setDisable(false);
            setBadge(statusBadge, true);
            publishPresenceWithServer(server, true); // friends can now see & join this server
        });

        stopBtn.setOnAction(e -> {
            var pm = runningServers.get(server.id);
            if (pm != null) {
                serverConsoleArea.appendText("[DeyLauncher] Stopping...\n");
                new Thread(() -> {
                    pm.stop();
                    Platform.runLater(() -> {
                        stopBtn.setDisable(true);
                        startBtn.setDisable(false);
                        setBadge(statusBadge, false);
                        publishPresenceWithServer(server, false); // server no longer joinable
                        renderServersPageContent();
                    });
                }, "server-stop-" + server.id).start();
            }
        });

        HBox inputRow = new HBox(10, commandField, sendBtn);

        // Reorganized into clear labeled sections (plus the existing color-coded server-toolbar
        // and server-share-card cards from theme.css) so the tab reads top-to-bottom, and the
        // whole thing is wrapped in a ScrollPane so it scrolls instead of clipping when the
        // management dialog is too short -- the same pattern the Players/Addons tabs use.
        box.getChildren().setAll(
                sectionLabel("SERVER ADDRESS"),
                ipRow, ipNote,
                sectionLabel("SERVER CONTROLS"),
                toolbar, versionChangeNote,
                sectionLabel("CONSOLE"),
                serverConsoleArea, inputRow,
                buildInternetShareSection(server));

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        return scroll;
    }

    private Node buildServerPropertiesTab(ServerInstance server) {
        VBox box = new VBox(16);
        box.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        Path serverDir = serverStore.serverDir(server.id);
        ServerPropertiesManager propsManager = new ServerPropertiesManager(serverDir);
        var props = propsManager.read();

        // Tracks whether any control has been edited -- drives the pinned Save button's glow.
        SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        Runnable markDirty = () -> dirty.set(true);

        // ---- Server icon drag-and-drop ----
        Label iconLabel = sectionLabel("SERVER ICON (64x64)");
        Path iconPath = serverDir.resolve("server-icon.png");
        ImageView iconPreview = new ImageView();
        iconPreview.setFitWidth(64);
        iconPreview.setFitHeight(64);
        if (Files.exists(iconPath)) {
            try {
                iconPreview.setImage(new Image(iconPath.toUri().toString()));
            } catch (Exception ignored) {
            }
        }
        Label dropHint = new Label("Drag & drop any image -- it's converted to 64x64 for you");
        dropHint.getStyleClass().add("notice-label");
        dropHint.setWrapText(true);
        dropHint.setMaxWidth(150);
        VBox dropZone = new VBox(8, iconPreview, dropHint);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setPrefSize(150, 130);
        dropZone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            dropZone.getStyleClass().add("drop-zone-active");
        });
        dropZone.setOnDragExited(e -> dropZone.getStyleClass().remove("drop-zone-active"));
        dropZone.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (files != null && !files.isEmpty()) {
                try {
                    var img = javax.imageio.ImageIO.read(files.get(0));
                    if (img == null) {
                        dropHint.setText("Couldn't read that file as an image.");
                    } else {
                        // Accept ANY size/format: normalize to the exact 64x64 PNG the server needs.
                        // Non-square images are center-cropped first, so they aren't stretched.
                        writeServerIcon64(img, iconPath);
                        iconPreview.setImage(new Image(iconPath.toUri().toString()));
                        int w = img.getWidth(), h = img.getHeight();
                        dropHint.setText((w == 64 && h == 64)
                                ? "Saved."
                                : "Saved -- converted from " + w + "x" + h + " to 64x64.");
                    }
                } catch (Exception ex) {
                    dropHint.setText("Couldn't read that file.");
                }
            }
            e.setDropCompleted(true);
        });

        // ---- Common properties, real controls ----
        TextField motdField = new TextField(props.getOrDefault("motd", "A DeyLauncher server"));
        motdField.getStyleClass().add("input-field");
        TextField maxPlayersField = new TextField(props.getOrDefault("max-players", "20"));
        maxPlayersField.getStyleClass().add("input-field");

        ComboBox<String> difficultyBox = new ComboBox<>();
        difficultyBox.getItems().addAll("peaceful", "easy", "normal", "hard");
        difficultyBox.setValue(props.getOrDefault("difficulty", "easy"));
        difficultyBox.getStyleClass().add("input-field");

        CheckBox hardcoreBox = new CheckBox("Hardcore (permadeath -- forces difficulty to Hard)");
        hardcoreBox.setWrapText(true);
        hardcoreBox.setSelected("true".equals(props.getOrDefault("hardcore", "false")));
        difficultyBox.setDisable(hardcoreBox.isSelected());
        hardcoreBox.selectedProperty().addListener((o, a, b) -> {
            difficultyBox.setDisable(b);
            if (b) difficultyBox.setValue("hard");
        });

        ComboBox<String> gamemodeBox = new ComboBox<>();
        gamemodeBox.getItems().addAll("survival", "creative", "adventure", "spectator");
        gamemodeBox.setValue(props.getOrDefault("gamemode", "survival"));
        gamemodeBox.getStyleClass().add("input-field");

        CheckBox pvpBox = new CheckBox("PvP enabled");
        pvpBox.setWrapText(true);
        pvpBox.setSelected(!"false".equals(props.getOrDefault("pvp", "true")));
        CheckBox onlineModeBox = new CheckBox("Online mode (requires real Microsoft accounts)");
        onlineModeBox.setWrapText(true);
        // Default online-mode by the account type the owner last used: online account -> on,
        // offline account -> off. Matches what the vanilla server expects (an offline account
        // can't join an online-mode server anyway), and only applies before the key exists --
        // once set, we respect whatever was saved.
        if (props.containsKey("online-mode")) {
            onlineModeBox.setSelected(!"false".equals(props.get("online-mode")));
        } else {
            PlayerIdentity activeIdentity = identityStore.getActive();
            boolean onlineAccount = activeIdentity != null && activeIdentity.accountType == AccountType.ONLINE;
            onlineModeBox.setSelected(onlineAccount);
        }
        CheckBox whitelistBox = new CheckBox("Whitelist enabled");
        whitelistBox.setWrapText(true);
        whitelistBox.setSelected("true".equals(props.getOrDefault("white-list", "false")));
        CheckBox netherBox = new CheckBox("Nether enabled");
        netherBox.setWrapText(true);
        netherBox.setSelected(!"false".equals(props.getOrDefault("allow-nether", "true")));
        Label netherNote = new Label("Vanilla has no official server.properties toggle for The End "
                + "specifically (only the Nether has one) -- so there's no equivalent switch here to "
                + "wire up honestly. Paper/Purpur can restrict it via their own per-world config, not "
                + "covered by this tab.");
        netherNote.getStyleClass().add("notice-label");
        netherNote.setWrapText(true);

        TextField viewDistanceField = new TextField(props.getOrDefault("view-distance", "10"));
        viewDistanceField.getStyleClass().add("input-field");
        TextField simDistanceField = new TextField(props.getOrDefault("simulation-distance", "10"));
        simDistanceField.getStyleClass().add("input-field");

        Slider spawnProtectionSlider = new Slider(0, 32,
                parseIntSafe(props.getOrDefault("spawn-protection", "16"), 16));
        spawnProtectionSlider.setShowTickMarks(true);
        spawnProtectionSlider.setMajorTickUnit(8);
        Label spawnProtectionLabel = new Label("Spawn protection radius: " + (int) spawnProtectionSlider.getValue());
        spawnProtectionLabel.getStyleClass().add("settings-value-label");
        spawnProtectionSlider.valueProperty().addListener((o, a, b) ->
                spawnProtectionLabel.setText("Spawn protection radius: " + b.intValue()));

        // ---- Resource pack ----
        CheckBox requirePackBox = new CheckBox("Require resource pack");
        requirePackBox.setWrapText(true);
        requirePackBox.setSelected("true".equals(props.getOrDefault("require-resource-pack", "false")));
        TextField packUrlField = new TextField(props.getOrDefault("resource-pack", ""));
        packUrlField.setPromptText("Direct download URL for the .zip (must be reachable by players)");
        packUrlField.getStyleClass().add("input-field");
        TextField packPromptField = new TextField(props.getOrDefault("resource-pack-prompt", ""));
        packPromptField.setPromptText("Message shown asking players to accept the pack");
        packPromptField.getStyleClass().add("input-field");
        Label packSha1Label = new Label(props.containsKey("resource-pack-sha1")
                ? "SHA-1 on file: " + props.get("resource-pack-sha1") : "No pack hashed yet.");
        packSha1Label.getStyleClass().add("notice-label");
        String[] computedSha1 = { props.get("resource-pack-sha1") };

        Label packDropHint = new Label("Drag & drop the pack .zip here to compute its SHA-1");
        packDropHint.getStyleClass().add("notice-label");
        VBox packDropZone = new VBox(8, packDropHint);
        packDropZone.setAlignment(Pos.CENTER);
        packDropZone.getStyleClass().add("drop-zone");
        packDropZone.setPrefSize(300, 70);
        packDropZone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            packDropZone.getStyleClass().add("drop-zone-active");
        });
        packDropZone.setOnDragExited(e -> packDropZone.getStyleClass().remove("drop-zone-active"));
        packDropZone.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (files != null && !files.isEmpty() && files.get(0).getName().endsWith(".zip")) {
                try {
                    byte[] bytes = Files.readAllBytes(files.get(0).toPath());
                    var digest = java.security.MessageDigest.getInstance("SHA-1");
                    byte[] hash = digest.digest(bytes);
                    StringBuilder hex = new StringBuilder();
                    for (byte bb : hash) hex.append(String.format("%02x", bb));
                    computedSha1[0] = hex.toString();
                    packSha1Label.setText("SHA-1 computed: " + computedSha1[0]
                            + " -- now upload this exact file somewhere reachable and paste the URL above.");
                } catch (Exception ex) {
                    packSha1Label.setText("Couldn't hash that file: " + ex.getMessage());
                }
            } else {
                packSha1Label.setText("Drop the pack's .zip file specifically.");
            }
            e.setDropCompleted(true);
        });
        Label packHonestNote = new Label("DeyLauncher can't host this zip for you -- Minecraft needs a "
                + "real URL players can download it from. Drop the file here to get its SHA-1, then "
                + "upload it yourself (e.g. GitHub Releases) and paste the direct link above.");
        packHonestNote.getStyleClass().add("notice-label");
        packHonestNote.setWrapText(true);

        // ---- Dirty tracking: any edit below makes the pinned Save button glow ----
        motdField.textProperty().addListener((o, a, b) -> markDirty.run());
        maxPlayersField.textProperty().addListener((o, a, b) -> markDirty.run());
        difficultyBox.valueProperty().addListener((o, a, b) -> markDirty.run());
        gamemodeBox.valueProperty().addListener((o, a, b) -> markDirty.run());
        hardcoreBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        pvpBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        onlineModeBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        whitelistBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        netherBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        viewDistanceField.textProperty().addListener((o, a, b) -> markDirty.run());
        simDistanceField.textProperty().addListener((o, a, b) -> markDirty.run());
        spawnProtectionSlider.valueProperty().addListener((o, a, b) -> markDirty.run());
        requirePackBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        packUrlField.textProperty().addListener((o, a, b) -> markDirty.run());
        packPromptField.textProperty().addListener((o, a, b) -> markDirty.run());

        // ---- Save: pinned bottom bar that only glows once something changed ----
        Button saveBtn = buildDirtyButton(dirty);
        Label savedNote = new Label();
        savedNote.getStyleClass().add("notice-label");
        saveBtn.setOnAction(e -> {
            props.put("motd", motdField.getText());
            props.put("max-players", maxPlayersField.getText());
            props.put("difficulty", difficultyBox.getValue());
            props.put("hardcore", String.valueOf(hardcoreBox.isSelected()));
            props.put("gamemode", gamemodeBox.getValue());
            props.put("pvp", String.valueOf(pvpBox.isSelected()));
            props.put("online-mode", String.valueOf(onlineModeBox.isSelected()));
            props.put("white-list", String.valueOf(whitelistBox.isSelected()));
            props.put("allow-nether", String.valueOf(netherBox.isSelected()));
            props.put("view-distance", viewDistanceField.getText());
            props.put("simulation-distance", simDistanceField.getText());
            props.put("spawn-protection", String.valueOf((int) spawnProtectionSlider.getValue()));
            props.put("require-resource-pack", String.valueOf(requirePackBox.isSelected()));
            props.put("resource-pack", packUrlField.getText());
            props.put("resource-pack-prompt", packPromptField.getText());
            if (computedSha1[0] != null) props.put("resource-pack-sha1", computedSha1[0]);
            props.putIfAbsent("server-port", String.valueOf(server.port));
            try {
                propsManager.write(props);
                savedNote.setText("Saved -- takes effect next server start.");
                dirty.set(false);
                savedNote.getStyleClass().remove("notice-error");
            } catch (java.io.IOException ex) {
                savedNote.setText("Couldn't save: " + ex.getMessage());
            }
        });

        // ---- Layout: gamemode/difficulty side by side, toggles in a 2-column matrix ----
        GridPane modeGrid = new GridPane();
        modeGrid.setHgap(28);
        modeGrid.setVgap(8);
        modeGrid.add(sectionLabel("GAME MODE"), 0, 0);
        modeGrid.add(gamemodeBox, 0, 1);
        modeGrid.add(sectionLabel("DIFFICULTY"), 1, 0);
        modeGrid.add(difficultyBox, 1, 1);

        GridPane toggleMatrix = new GridPane();
        toggleMatrix.setHgap(24);
        toggleMatrix.setVgap(10);
        toggleMatrix.add(pvpBox, 0, 0);
        toggleMatrix.add(onlineModeBox, 1, 0);
        toggleMatrix.add(whitelistBox, 0, 1);
        toggleMatrix.add(netherBox, 1, 1);
        toggleMatrix.add(requirePackBox, 0, 2);
        toggleMatrix.add(hardcoreBox, 1, 2);

        box.getChildren().addAll(
                iconLabel, dropZone,
                sectionLabel("MOTD"), motdField,
                sectionLabel("MAX PLAYERS"), maxPlayersField,
                modeGrid,
                toggleMatrix, netherNote,
                sectionLabel("VIEW DISTANCE (CHUNKS)"), viewDistanceField,
                sectionLabel("SIMULATION DISTANCE (CHUNKS)"), simDistanceField,
                spawnProtectionLabel, spawnProtectionSlider,
                sectionLabel("RESOURCE PACK"), packUrlField, packPromptField,
                packDropZone, packSha1Label, packHonestNote);
        return tabShell(scroll, saveBtn, savedNote);
    }

    /** Normalizes any dropped image to the exact 64x64 PNG a Minecraft server wants. Non-square
     *  input is center-cropped to a square first so it isn't stretched, then scaled; the result is
     *  always written as a PNG (also normalizing JPG/WebP input) with alpha preserved. */
    private static void writeServerIcon64(java.awt.image.BufferedImage src, Path out) throws java.io.IOException {
        int side = Math.max(1, Math.min(src.getWidth(), src.getHeight()));
        int sx = (src.getWidth() - side) / 2;
        int sy = (src.getHeight() - side) / 2;
        java.awt.image.BufferedImage icon =
                new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = icon.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, 64, 64, sx, sy, sx + side, sy + side, null);
        g.dispose();
        Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(icon, "png", out.toFile());
    }

    private int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Node buildServerPlayersTab(ServerInstance server) {
        VBox box = new VBox(18);
        box.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        ServerPlayerManager playerManager = new ServerPlayerManager(serverStore.serverDir(server.id));

        // ---- Online now (from live join/leave parsing -- only populated while the server is running) ----
        VBox onlineSection = new VBox(10);
        Button refreshOnlineBtn = new Button("Refresh");
        refreshOnlineBtn.getStyleClass().add("pill-button");
        Runnable[] renderOnlineHolder = new Runnable[1];
        Runnable renderOnline = () -> {
            onlineSection.getChildren().setAll(sectionLabel("ONLINE NOW"), refreshOnlineBtn);
            var pm = runningServers.get(server.id);
            List<String> online = (pm != null && pm.isRunning()) ? pm.getOnlinePlayers() : List.of();
            if (pm == null || !pm.isRunning()) {
                Label offlineNote = new Label("Server isn't running -- start it to see who's online.");
                offlineNote.getStyleClass().add("notice-label");
                onlineSection.getChildren().add(offlineNote);
            } else if (online.isEmpty()) {
                Label none = new Label("Nobody online right now.");
                none.getStyleClass().add("notice-label");
                onlineSection.getChildren().add(none);
            } else {
                for (String playerName : online) {
                    onlineSection.getChildren().add(buildOnlinePlayerRow(playerName, playerManager, renderOnlineHolder[0]));
                }
            }
        };
        renderOnlineHolder[0] = renderOnline;
        // Refresh doesn't just re-render -- it asks the running server for its authoritative
        // player list (the vanilla `list` command). Its reply updates ServerProcessManager's
        // online set via observeConsoleLine, so after a short wait the re-render shows the true
        // current players instead of whatever join/leave lines happened to be captured.
        refreshOnlineBtn.setOnAction(e -> {
            var pm = runningServers.get(server.id);
            if (pm == null || !pm.isRunning()) {
                renderOnline.run();
                return;
            }
            refreshOnlineBtn.setDisable(true);
            try {
                pm.sendListCommand();
            } catch (Exception ignored) {
            }
            new Thread(() -> {
                try {
                    Thread.sleep(900);
                } catch (InterruptedException ignored) {
                }
                Platform.runLater(() -> {
                    refreshOnlineBtn.setDisable(false);
                    renderOnline.run();
                });
            }, "refresh-online-" + server.id).start();
        });
        renderOnline.run();

        // ---- Server managers (permission record for future remote management) ----
        VBox managersSection = new VBox(10, sectionLabel("SERVER MANAGERS"));
        Label managersNote = new Label("DeyLauncher friends recorded as permitted to manage this server. "
                + "Not enforced remotely yet -- this just records who's permitted for when "
                + "multihosting/remote management is built.");
        managersNote.getStyleClass().add("notice-label");
        managersNote.setWrapText(true);
        managersSection.getChildren().add(managersNote);
        Runnable renderManagers = () -> {
            managersSection.getChildren().setAll(sectionLabel("SERVER MANAGERS"), managersNote);
            for (String managerName : server.managerUsernames) {
                Label managerLabel = new Label(managerName);
                managerLabel.getStyleClass().add("mod-name");
                Region managerSpacer = new Region();
                HBox.setHgrow(managerSpacer, Priority.ALWAYS);
                Button removeManagerBtn = new Button("Remove");
                removeManagerBtn.getStyleClass().add("pill-button");
                removeManagerBtn.setOnAction(e -> {
                    server.managerUsernames.remove(managerName);
                    serverStore.save(server);
                });
                HBox managerRow = new HBox(10, managerLabel, managerSpacer, removeManagerBtn);
                managerRow.setAlignment(Pos.CENTER_LEFT);
                managerRow.getStyleClass().add("mod-row");
                managersSection.getChildren().add(managerRow);
            }
        };
        TextField addManagerField = new TextField();
        addManagerField.setPromptText("DeyLauncher username");
        addManagerField.getStyleClass().add("input-field");
        Button suggestManagerBtn = new Button("Suggest");
        suggestManagerBtn.getStyleClass().add("pill-button");
        suggestManagerBtn.setOnAction(e -> showOnlinePlayerSuggestions(addManagerField, server));
        Button addManagerBtn = new Button("Add");
        addManagerBtn.getStyleClass().add("pill-button");
        addManagerBtn.setOnAction(e -> {
            String username = addManagerField.getText().trim();
            if (username.isEmpty() || server.managerUsernames.contains(username)) return;
            server.managerUsernames.add(username);
            serverStore.save(server);
            addManagerField.clear();
            renderManagers.run();
        });
        HBox addManagerRow = new HBox(10, suggestManagerBtn, addManagerField, addManagerBtn);
        renderManagers.run();
        managersSection.getChildren().add(addManagerRow);

        box.getChildren().add(onlineSection);
        box.getChildren().add(buildPlayerListSection("OPERATORS (OP)", playerManager.listOps(), playerManager, playerManager.opsFile(), server));
        box.getChildren().add(buildPlayerListSection("WHITELIST", playerManager.listWhitelist(), playerManager, playerManager.whitelistFile(), server));
        box.getChildren().add(buildPlayerListSection("BANNED", playerManager.listBanned(), playerManager, playerManager.bannedFile(), server));
        box.getChildren().add(managersSection);
        return scroll;
    }

    /**
     * One online player's row: face avatar + name, click to expand quick op/whitelist/ban
     * account-info controls. Live inventory viewing is NOT implemented -- that would need a real
     * NBT parser reading playerdata (reflecting last save, not truly live), which is a
     * meaningfully bigger feature than fits honestly in this pass.
     */
    private VBox buildOnlinePlayerRow(String playerName, ServerPlayerManager playerManager, Runnable onAvatarLoaded) {
        // Try to show a real profile picture (front-facing head) once we've resolved the player's
        // UUID from the server's online-name list; otherwise a clean placeholder while the skin
        // fetch runs in the background.
        String knownUuid = playerUuidByName.get(playerName);
        Image cached = (knownUuid != null) ? friendFaceCached(knownUuid) : null;
        ImageView avatarView = new ImageView(cached != null ? cached : faceThumbnail(null));
        avatarView.setFitWidth(28);
        avatarView.setFitHeight(28);
        avatarView.setSmooth(false);
        avatarView.getStyleClass().add("account-btn-face");
        if (cached == null) {
            resolveOnlinePlayerAvatar(playerName, onAvatarLoaded);
        }

        Label nameLabel = new Label(playerName);
        nameLabel.getStyleClass().add("mod-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label expandHint = new Label("Server account info");
        expandHint.getStyleClass().add("notice-label");
        expandHint.setGraphic(icon(IconFactory.Icon.CHEVRON_DOWN));
        expandHint.setGraphicTextGap(6);

        HBox summaryRow = new HBox(10, avatarView, nameLabel, spacer, expandHint);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.getStyleClass().add("mod-row");

        VBox detailBox = new VBox(8);
        detailBox.setVisible(false);
        detailBox.setManaged(false);
        Button opToggleBtn = new Button(playerManager.listOps().stream().anyMatch(p -> p.name().equalsIgnoreCase(playerName))
                ? "Remove OP" : "Make OP");
        opToggleBtn.getStyleClass().add("pill-button");
        opToggleBtn.setOnAction(e -> {
            try {
                boolean isOp = playerManager.listOps().stream().anyMatch(p -> p.name().equalsIgnoreCase(playerName));
                if (isOp) playerManager.removeByName(playerManager.opsFile(), playerName);
                else playerManager.addByName(playerManager.opsFile(), playerName);
                opToggleBtn.setText(isOp ? "Make OP" : "Remove OP");
            } catch (Exception ignored) {
            }
        });
        Button banBtn = new Button("Ban");
        banBtn.getStyleClass().add("pill-button");
        banBtn.setOnAction(e -> {
            try {
                playerManager.addByName(playerManager.bannedFile(), playerName);
            } catch (Exception ignored) {
            }
        });
        Label inventoryNote = new Label("Live inventory viewing isn't implemented -- Minecraft doesn't "
                + "expose that through the console, only through the player's saved data file, "
                + "which only updates on save/disconnect (not truly live).");
        inventoryNote.getStyleClass().add("notice-label");
        inventoryNote.setWrapText(true);
        detailBox.getChildren().addAll(new HBox(10, opToggleBtn, banBtn), inventoryNote);

        summaryRow.setOnMouseClicked(e -> {
            boolean showing = detailBox.isVisible();
            detailBox.setVisible(!showing);
            detailBox.setManaged(!showing);
            expandHint.setGraphic(icon(showing ? IconFactory.Icon.CHEVRON_DOWN : IconFactory.Icon.CHEVRON_UP));
        });

        return new VBox(6, summaryRow, detailBox);
    }

    private VBox buildPlayerListSection(String title, List<ServerPlayerManager.PlayerEntry> entries,
                                         ServerPlayerManager playerManager, Path file, ServerInstance server) {
        VBox section = new VBox(10, sectionLabel(title));
        for (var entry : entries) {
            ImageView avatar = null;
            String uuid = entry.uuid();
            if (uuid != null && !uuid.isBlank()) {
                Image cachedFace = friendFaceCached(uuid);
                if (cachedFace != null) {
                    avatar = new ImageView(cachedFace);
                    avatar.setFitWidth(28);
                    avatar.setFitHeight(28);
                    avatar.setSmooth(false);
                    avatar.getStyleClass().add("account-btn-face");
                    avatar.setUserData("has-avatar");
                }
            }
            Label name = new Label(entry.name());
            name.getStyleClass().add("mod-name");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button removeBtn = new Button("Remove");
            removeBtn.getStyleClass().add("pill-button");
            removeBtn.setOnAction(e -> {
                try {
                    playerManager.removeByName(file, entry.name());
                    section.getChildren().clear();
                    section.getChildren().add(sectionLabel(title));
                } catch (Exception ignored) {
                }
            });
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("mod-row");
            if (avatar != null) row.getChildren().add(avatar);
            row.getChildren().addAll(name, spacer, removeBtn);
            section.getChildren().add(row);
        }
        TextField addField = new TextField();
        addField.setPromptText("Player name");
        addField.getStyleClass().add("input-field");
        Button suggestBtn = new Button("Suggest");
        suggestBtn.getStyleClass().add("pill-button");
        suggestBtn.setOnAction(e -> showOnlinePlayerSuggestions(addField, server));
        Button addBtn = new Button("Add");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setOnAction(e -> {
            if (addField.getText().isBlank()) return;
            try {
                playerManager.addByName(file, addField.getText().trim());
                addField.clear();
            } catch (Exception ignored) {
            }
        });
        HBox addRow = new HBox(10, suggestBtn, addField, addBtn);
        section.getChildren().add(addRow);
        return section;
    }

    private Node buildServerAddonsTab(ServerInstance server) {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        ServerAddonsManager addonsManager = new ServerAddonsManager(serverStore.serverDir(server.id), server.type);
        if (!addonsManager.supported()) {
            Label none = new Label("Vanilla servers don't support plugins or mods -- switch this "
                    + "server to Fabric, Forge, or Purpur to use addons.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            box.getChildren().add(none);
            return scroll;
        }

        String folderKind = server.type == ServerType.PURPUR ? "plugin" : "mod";
        Label heading = new Label((server.type == ServerType.PURPUR ? "Plugins" : "Mods")
                + " -- drag & drop " + folderKind + " jars below");
        heading.getStyleClass().add("card-heading");

        VBox listBox = new VBox(8);
        Runnable[] renderAddonsHolder = new Runnable[1];
        Runnable renderAddons = () -> {
            listBox.getChildren().clear();
            var addons = addonsManager.list();
            if (addons.isEmpty()) {
                Label none = new Label("No " + folderKind + "s installed yet.");
                none.getStyleClass().add("notice-label");
                listBox.getChildren().add(none);
                return;
            }
            for (var addon : addons) {
                CheckBox enabledBox = new CheckBox();
                enabledBox.setSelected(addon.enabled());
                enabledBox.getStyleClass().add("mod-checkbox");
                String baseName = addon.fileName().replace(".disabled", "");
                Label nameLabel = new Label(addon.displayName());
                nameLabel.getStyleClass().add("mod-name");
                Label fileLabel = new Label(baseName + "  ·  " + (addon.sizeBytes() / 1024) + " KB");
                fileLabel.getStyleClass().add("mod-filename");
                VBox textBox = new VBox(2, nameLabel, fileLabel);
                // If we've already enriched this addon to a Modrinth project, show its cached icon.
                String addonSlug = addonSlugByBase.get(normalizeAddonBase(baseName));
                Node iconTile = (addonSlug != null)
                        ? modIconNode(modrinthIconPath(addonSlug), 52)
                        : modIconNode(null, 52);
                if (addonSlug != null) {
                    iconTile.setCursor(javafx.scene.Cursor.HAND);
                    final String clickSlug = addonSlug;
                    iconTile.setOnMouseClicked(ev -> openUrl(ModrinthClient.projectPageUrl(clickSlug,
                            ModrinthClient.projectTypeFor(server.type))));
                    nameLabel.setCursor(javafx.scene.Cursor.HAND);
                    nameLabel.setOnMouseClicked(ev -> openUrl(ModrinthClient.projectPageUrl(clickSlug,
                            ModrinthClient.projectTypeFor(server.type))));
                }
                Region rowSpacer = new Region();
                HBox.setHgrow(rowSpacer, Priority.ALWAYS);
                // Change-version is always offered. If the addon isn't resolved to a Modrinth
                // project yet, we resolve it lazily (search by name) at click time instead of
                // hiding the option, so it always works even before background enrichment ran.
                Button changeBtn = new Button("Change version");
                changeBtn.getStyleClass().add("pill-button");
                final String rBase = baseName;
                final String rSlug = addonSlug;
                changeBtn.setOnAction(ev -> changeAddonVersion(rBase, rSlug, server, addonsManager, renderAddonsHolder[0]));
                Button deleteBtn = new Button();
                deleteBtn.getStyleClass().add("mod-delete-button");
                setButtonIconOnly(deleteBtn, IconFactory.Icon.TRASH);
                enabledBox.setOnAction(e -> {
                    try {
                        addonsManager.setEnabled(addon.fileName(), enabledBox.isSelected());
                    } catch (Exception ignored) {
                    }
                    renderAddonsHolder[0].run();
                });
                deleteBtn.setOnAction(e -> {
                    try {
                        addonsManager.delete(addon.fileName());
                    } catch (Exception ignored) {
                    }
                    renderAddonsHolder[0].run();
                });
                List<Node> rowNodes = new ArrayList<>();
                rowNodes.add(iconTile);
                rowNodes.add(enabledBox);
                rowNodes.add(textBox);
                rowNodes.add(rowSpacer);
                if (changeBtn != null) rowNodes.add(changeBtn);
                rowNodes.add(deleteBtn);
                HBox row = new HBox(10);
                row.getChildren().setAll(rowNodes);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("mod-row");
                if (!addon.enabled()) row.getStyleClass().add("mod-row-disabled");
                listBox.getChildren().add(row);
            }
            // Best-effort: attach icons (+ click-through pages) to installed addons by matching
            // their display/file names against Modrinth, in the background so the list never blocks.
            enrichAddonIconsAsync(addonsManager, addons, folderKind, listBox, server, renderAddonsHolder[0]);
        };
        renderAddonsHolder[0] = renderAddons;

        // ---- Modpacks: install a whole pack's server-side files in one go ----
        // A server only ever wants the part of a pack it can run: the mods/ (or plugins/) folder it
        // actually reads, plus config/. ModpackInstaller.installForServer enforces exactly that, and
        // skips every file the pack marks client-only.
        Button addPackBtn = new Button();
        setButtonIcon(addPackBtn, IconFactory.Icon.MODPACK, "Add Modpack");
        addPackBtn.getStyleClass().add("pill-button");
        Label packStatus = new Label();
        packStatus.getStyleClass().add("notice-label");
        packStatus.setWrapText(true);

        // The install's own progress, shown IN this tab (never a separate popup): a bar that fills as
        // the pack's files download and its missing dependencies resolve, plus the live phase text.
        // Both stay hidden until a pack is actually being installed.
        ProgressBar installBar = new ProgressBar(0);
        installBar.setMaxWidth(Double.MAX_VALUE);
        installBar.getStyleClass().add("play-progress");
        installBar.setVisible(false);
        installBar.setManaged(false);
        Label installStatus = new Label();
        installStatus.getStyleClass().add("notice-label");
        installStatus.setWrapText(true);
        installStatus.setVisible(false);
        installStatus.setManaged(false);

        addPackBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a modpack for this server");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Modpacks (.mrpack / .zip)", "*.mrpack", "*.zip"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            java.io.File picked = chooser.showOpenDialog(shellWindowOwner("server-" + server.id));
            if (picked != null) {
                installServerModpack(server, picked.toPath(), packStatus, installBar, installStatus, renderAddons);
            }
        });

        VBox dropZone = new VBox(new Label("Drop " + folderKind + " .jar files here, or a modpack (.mrpack / .zip)"));
        dropZone.setAlignment(Pos.CENTER);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setPrefHeight(70);
        for (var n : dropZone.getChildren()) if (n instanceof Label l) l.getStyleClass().add("notice-label");
        dropZone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            dropZone.getStyleClass().add("drop-zone-active");
        });
        dropZone.setOnDragExited(e -> dropZone.getStyleClass().remove("drop-zone-active"));
        dropZone.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            boolean addedJar = false;
            if (files != null) {
                for (var f : files) {
                    Path p = f.toPath();
                    if (p.toString().toLowerCase().endsWith(".jar")) {
                        try {
                            addonsManager.addFile(p);
                            addedJar = true;
                        } catch (Exception ignored) {
                        }
                    } else if (ModpackFormat.installable(p)) {
                        // A whole pack: install its server half in the background (it may download),
                        // reporting into this tab's own progress bar.
                        installServerModpack(server, p, packStatus, installBar, installStatus, renderAddons);
                    }
                }
            }
            if (addedJar) renderAddons.run();
            e.setDropCompleted(true);
        });

        // ---- Search the internet (Modrinth) for a compatible build ----
        ModrinthClient modrinth = new ModrinthClient();
        TextField searchField = new TextField();
        searchField.setPromptText("Search " + folderKind + "s on Modrinth (e.g. "
                + (server.type == ServerType.PURPUR ? "better-wolves" : "sodium") + ")...");
        searchField.getStyleClass().add("input-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchBtn = new Button();
        searchBtn.getStyleClass().add("pill-button");
        setButtonIcon(searchBtn, IconFactory.Icon.SEARCH, "Search");
        Label searchStatus = new Label("Search finds online builds for Minecraft " + server.minecraftVersion
                + ". Install opens a picker listing only the versions compatible with that Minecraft version.");
        searchStatus.getStyleClass().add("notice-label");
        searchStatus.setWrapText(true);
        VBox resultsBox = new VBox(8);

        searchBtn.setOnAction(e -> {
            String q = searchField.getText().trim();
            if (q.isEmpty()) return;
            resultsBox.getChildren().clear();
            searchStatus.setText("Searching Modrinth for \"" + q + "\"...");
            Task<java.util.List<ModrinthClient.Hit>> task = new Task<>() {
                @Override
                protected java.util.List<ModrinthClient.Hit> call() throws Exception {
                    return modrinth.search(q, ModrinthClient.projectTypeFor(server.type));
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                var hits = task.getValue();
                if (hits == null || hits.isEmpty()) {
                    searchStatus.setText("No " + folderKind + "s found for \"" + q + "\".");
                    return;
                }
                searchStatus.setText(hits.size() + " " + folderKind + (hits.size() == 1 ? "" : "s")
                        + " found -- choose one to install.");
                resultsBox.getChildren().clear();
                for (var hit : hits) {
                    resultsBox.getChildren().add(buildModrinthRow(hit, modrinth, addonsManager, renderAddons, server));
                }
            }));
            task.setOnFailed(ev -> Platform.runLater(() ->
                    searchStatus.setText("Search failed: " + task.getException().getMessage())));
            new Thread(task, "modrinth-search").start();
        });
        searchField.setOnAction(ev -> searchBtn.fire());
        HBox searchRow = new HBox(10, searchField, searchBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        renderAddons.run();
        box.getChildren().addAll(heading, dropZone, addPackBtn, packStatus, installBar, installStatus,
                sectionLabel("SEARCH ONLINE"), searchRow, searchStatus, resultsBox,
                listBox);
        return scroll;
    }

    /**
     * Installs the SERVER half of a modpack into an owned server -- the Addons tab's "Add Modpack"
     * button, and the same code path when a pack is dropped on that tab's drop zone.
     *
     * Only what this server can actually use is installed (its own mods/ or plugins/ folder, plus
     * config/), and the pack's own env block is honoured, so a client-only mod can never land on a
     * server that can't run it. A pack built for another loader or another Minecraft version is
     * refused with the reason: a server runs exactly one loader and one version, so installing anyway
     * would be a silent no-op at best and a startup crash at worst.
     */
    private void installServerModpack(ServerInstance server, Path pack, Label packStatus,
                                      ProgressBar installBar, Label installStatus, Runnable renderAddons) {
        String addonFolder = ServerAddonsManager.folderNameFor(server.type);
        if (addonFolder == null) {
            packStatus.setText("Switch this server to Fabric, Forge or Purpur first -- a Vanilla server "
                    + "has no mods/ or plugins/ folder for a pack to install into.");
            return;
        }

        // Progress lives IN this tab (the bar + label built beside the Add Modpack button): no popup
        // window, so nothing can be left dangling behind the tab. Both nodes are only un-hidden for
        // the duration of the install.
        installBar.setProgress(0);
        installBar.setVisible(true);
        installBar.setManaged(true);
        installStatus.setText("Reading " + (pack.getFileName() == null ? pack : pack.getFileName()) + "...");
        installStatus.setVisible(true);
        installStatus.setManaged(true);
        packStatus.setText("");

        // Phase helpers -- all called from the install thread; each one hops to the FX thread.
        Consumer<String> updateLabel = text -> Platform.runLater(() -> installStatus.setText(text));
        Consumer<String> updateDetail = text -> Platform.runLater(() -> installStatus.setText(text));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // Phase 1: Install the modpack
                updateLabel.accept("Reading modpack...");
                updateDetail.accept(pack.getFileName().toString());
                ModpackInfo info = ModpackReader.read(pack);
                String problem = info.blockingProblem();
                if (problem != null) throw new IllegalStateException(problem);
                if (info.loaderDeclared() && !info.launcherLoader().equalsIgnoreCase(server.type.displayName())) {
                    throw new IllegalStateException("this pack is built for " + info.launcherLoader()
                            + ", but this server runs " + server.type.displayName()
                            + " -- change the server type on the Settings tab first, or use a pack built for "
                            + server.type.displayName() + ".");
                }
                if (info.knowsMinecraftVersion() && server.minecraftVersion != null
                        && !ModrinthClient.matchesMinecraftVersion(info.mcVersion(), server.minecraftVersion)) {
                    throw new IllegalStateException("this pack is for Minecraft " + info.mcVersion()
                            + ", but this server runs Minecraft " + server.minecraftVersion + ".");
                }

                updateLabel.accept("Installing modpack files...");
                updateDetail.accept("Resolving and downloading modpack files...");

                // Install modpack with progress (this phase owns 5%..60% of the bar).
                DownloadProgress installerProgress = fraction -> {
                    double f = 0.05 + fraction * 0.55;
                    Platform.runLater(() -> installBar.setProgress(f));
                };
                Consumer<String> installerLog = msg -> Platform.runLater(() -> installStatus.setText(msg));

                ModpackInstaller.Result result = new ModpackInstaller().installForServer(info,
                        info.knowsMinecraftVersion() ? info.mcVersion() : server.minecraftVersion,
                        info.loaderDeclared() ? info.launcherLoader() : server.type.displayName(),
                        serverStore.serverDir(server.id), addonFolder, installerProgress, installerLog);

                if (!result.errors().isEmpty()) {
                    log("Server modpack files that failed:\n  " + String.join("\n  ", result.errors()));
                }
// Phase 2: fetch the required dependencies the pack's mods declare but the server
                // lacks (Fabric family only -- other loaders use a different metadata format). This is
                // what keeps a freshly installed server from dying with "Incompatible mod set".
                String mcVersion = server.minecraftVersion;
                String loader = server.type.displayName();
                if (("Fabric".equalsIgnoreCase(loader) || "Quilt".equalsIgnoreCase(loader))
                        && mcVersion != null && !mcVersion.isBlank()) {
                    updateLabel.accept("Resolving missing mod dependencies...");

                    DownloadProgress resolverProgress = fraction -> {
                        double f = 0.6 + fraction * 0.4; // 60% - 100%
                        Platform.runLater(() -> installBar.setProgress(f));
                    };
                    Consumer<String> resolverLog = msg -> {
                        Platform.runLater(() -> installStatus.setText(msg));
                        log(msg);
                    };

                    ServerModDependencyResolver.Result depResult = new ServerModDependencyResolver()
                            .resolveAndInstall(serverStore.serverDir(server.id), mcVersion, loader,
                                    resolverProgress, resolverLog);

                    Platform.runLater(() -> {
                        String summary = "Modpack installed: " + result.summary();
                        if (depResult.missingDepsFound() > 0) {
                            summary += " | Dependencies: " + depResult.summary();
                        }
                        if (depResult.hasErrors()) {
                            summary += " -- " + depResult.errors().size() + " dependency error(s), see log";
                            log("Server modpack dependency errors:\n  " + String.join("\n  ", depResult.errors()));
                        }
                        if (!depResult.warnings().isEmpty()) {
                            log("Server modpack dependency warnings:\n  " + String.join("\n  ", depResult.warnings()));
                        }
                        packStatus.setText(summary);
                        installStatus.setText("Modpack installed.");
                        // Always refresh the addons list after both phases.
                        renderAddons.run();
                    });
                } else {
                    Platform.runLater(() -> {
                        packStatus.setText("Modpack installed into this server's " + addonFolder + "/ folder: "
                                + result.summary() + (result.errors().isEmpty()
                                ? "" : " -- " + result.errors().size() + " file(s) failed, see the launcher log"));
                        renderAddons.run();
                    });
                }
                return null;
            }
        };

        // Task's own handlers already run on the FX thread, so the UI can be touched directly.
        task.setOnSucceeded(ev -> {
            installBar.setProgress(1);
            installStatus.setText("Done. See the summary below.");
        });

        task.setOnFailed(ev -> {
            Throwable ex = task.getException();
            String message = ex == null ? "unknown error" : ex.getMessage();
            installBar.setVisible(false);
            installBar.setManaged(false);
            installStatus.setVisible(false);
            installStatus.setManaged(false);
            packStatus.setText("Couldn't install that modpack: " + message);
            log("Modpack install failed: " + message);
        });

        new Thread(task, "server-modpack-install").start();
    }

    /**
     * Background enrichment for installed server addons: for each installed jar we don't yet know a
     * Modrinth project for, search by its base file name, cache the slug, download its icon, then
     * re-render the list so the icon + click-through appear and the placeholder tile gets replaced.
     */
    private void enrichAddonIconsAsync(ServerAddonsManager addonsManager,
                                       List<ServerAddonsManager.AddonEntry> addons, String folderKind,
                                       VBox listBox, ServerInstance server, Runnable renderAddons) {
        List<ServerAddonsManager.AddonEntry> pending = addons.stream()
                .filter(a -> !addonSlugByBase.containsKey(normalizeAddonBase(a.fileName())))
                .toList();
        if (pending.isEmpty()) return;
        Path iconDir = gameFiles.root.resolve("mod-icons");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                ModrinthClient client = new ModrinthClient();
                String type = ModrinthClient.projectTypeFor(server.type);
                for (var addon : pending) {
                    String key = normalizeAddonBase(addon.fileName());
                    var hit = client.firstHitBySlugOrName(addonsManager.addonSlug(addon.fileName()),
                            addon.displayName(), type);
                    if (hit == null || hit.slug().isBlank()) {
                        addonSlugByBase.put(key, "");
                        continue;
                    }
                    addonSlugByBase.put(key, hit.slug());
                    try {
                        Path icon = client.iconFor(hit.slug(), hit.iconUrl(), type, iconDir);
                        if (icon != null) modrinthIconCache.put(hit.slug(), icon.toString());
                    } catch (Exception ignored) {
                    }
                }
                return null;
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(renderAddons::run));
        new Thread(task, "enrich-addon-icons").start();
    }

    /** Strips ".disabled"/".jar" and a trailing version segment from an addon/mod file name so that
     *  different versions of the same project resolve to one slug cache key (and one Change-version row). */
    private static String normalizeAddonBase(String fileName) {
        String base = fileName.replace(".disabled", "");
        if (base.endsWith(".jar")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[-_]\\d+([.]\\d+)*.*$", "");
        return base.toLowerCase().trim();
    }

    /** Lazily resolves an addon's Modrinth slug if needed, then opens the compatible-version picker.
     *  Always available (even before background enrichment ran) and filters to the server's MC version. */
    private void changeAddonVersion(String baseName, String slugIfKnown, ServerInstance server,
                                    ServerAddonsManager addonsManager, Runnable renderAddons) {
        String key = normalizeAddonBase(baseName);
        if (slugIfKnown != null && !slugIfKnown.isBlank()) {
            showModrinthVersionPicker(baseName.substring(0, baseName.length() - 4), slugIfKnown,
                    ModrinthClient.projectTypeFor(server.type), server.minecraftVersion,
                    modrinthIconPath(slugIfKnown), null,
                    addonsManager.folder(), slugIfKnown, renderAddons);
            return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                ModrinthClient client = new ModrinthClient();
                String searchName = baseName.endsWith(".jar") ? baseName.substring(0, baseName.length() - 4) : baseName;
                var hit = client.firstHitByName(searchName, ModrinthClient.projectTypeFor(server.type));
                return hit == null ? null : hit.slug();
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            String slug = task.getValue();
            if (slug == null || slug.isBlank()) {
                new Alert(Alert.AlertType.WARNING, "Couldn't find \"" + baseName.replaceFirst("(?i)\\.jar$", "")
                        + "\" on Modrinth to list its versions.", ButtonType.OK).showAndWait();
                return;
            }
            addonSlugByBase.put(key, slug);
            renderAddons.run();
            showModrinthVersionPicker(baseName.replaceFirst("(?i)\\.jar$", ""), slug,
                    ModrinthClient.projectTypeFor(server.type), server.minecraftVersion,
                    modrinthIconPath(slug), null, addonsManager.folder(), slug, renderAddons);
        }));
        task.setOnFailed(e -> Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                "Couldn't reach Modrinth: " + task.getException().getMessage(), ButtonType.OK).showAndWait()));
        new Thread(task, "change-addon-version").start();
    }

    /**
     * Background enrichment for installed client mods (the main-launcher "Mods" dialog): search each
     * mod's display name on Modrinth, cache the slug + icon, then re-render the rows so the icon and
     * the click-through-to-Modrinth page appear. Client mods are always project_type "mod" on Modrinth.
     */
    private void enrichModIconsAsync(ModsManager modsManager, List<ModsManager.ModEntry> mods, Runnable refresh) {
        List<ModsManager.ModEntry> pending = mods.stream()
                .filter(m -> !addonSlugByBase.containsKey(normalizeAddonBase(m.fileName())))
                .toList();
        if (pending.isEmpty()) return;
        Path iconDir = gameFiles.root.resolve("mod-icons");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                ModrinthClient client = new ModrinthClient();
                for (var m : pending) {
                    String key = normalizeAddonBase(m.fileName());
                    var hit = client.firstHitBySlugOrName(modsManager.modSlug(m.fileName()),
                            m.displayName(), "mod");
                    if (hit == null || hit.slug().isBlank()) {
                        addonSlugByBase.put(key, "");
                        continue;
                    }
                    addonSlugByBase.put(key, hit.slug());
                    try {
                        Path icon = client.iconFor(hit.slug(), hit.iconUrl(), "mod", iconDir);
                        if (icon != null) modrinthIconCache.put(hit.slug(), icon.toString());
                    } catch (Exception ignored) {
                    }
                }
                return null;
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(refresh::run));
        new Thread(task, "enrich-mod-icons").start();
    }

    /**
     * Shared Modrinth "pick a version" dialog used by both the server Addons tab and the client Mods
     * dialog. It only lists the project's builds that support the given Minecraft version (newest
     * first), lets the player choose one, then downloads its .jar into targetFolder. If replaceSlug
     * is non-null, any previously-installed jar of the same project already sitting in targetFolder
     * is removed so there's never two copies of the same mod/plugin.
     */
    private void showModrinthVersionPicker(String projectName, String slug, String projectType,
                                           String mcVersion, java.nio.file.Path iconPath, String iconUrl,
                                           Path targetFolder, String replaceSlug, Runnable onInstalled) {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20));
        box.getStyleClass().add("mods-dialog-content");

        Node icon = iconPath != null ? modIconNode(iconPath, 56)
                : (iconUrl != null ? remoteModIcon(iconUrl, 56) : modIconNode(null, 56));
        final String pageUrl = ModrinthClient.projectPageUrl(slug, projectType);
        icon.setCursor(javafx.scene.Cursor.HAND);
        icon.setOnMouseClicked(ev -> openUrl(pageUrl));

        Label nameLbl = new Label(projectName);
        nameLbl.getStyleClass().add("mod-name");
        nameLbl.setFont(Font.font(null, FontWeight.BOLD, 17));
        nameLbl.setCursor(javafx.scene.Cursor.HAND);
        nameLbl.setOnMouseClicked(ev -> openUrl(pageUrl));

        Label mcLbl = new Label("Compatible builds for Minecraft " + mcVersion
                + " -- only these are listed.");
        mcLbl.getStyleClass().add("notice-label");
        mcLbl.setWrapText(true);
        VBox headerText = new VBox(2, nameLbl, mcLbl);
        HBox header = new HBox(12, icon, headerText);
        header.setAlignment(Pos.CENTER_LEFT);

        ComboBox<ModrinthClient.ProjectVersion> versionBox = new ComboBox<>();
        versionBox.getStyleClass().add("input-field");
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ModrinthClient.ProjectVersion v) {
                if (v == null) return "";
                String extra = (v.name() != null && !v.name().isBlank()
                        && !v.name().equals(v.versionNumber())) ? "   (" + v.name() + ")" : "";
                return v.versionNumber() + extra;
            }
            @Override
            public ModrinthClient.ProjectVersion fromString(String text) { return null; }
        });

    Label status = new Label();
        status.getStyleClass().add("notice-label");
        status.setWrapText(true);

        Button installBtn = new Button();
        installBtn.getStyleClass().add("settings-apply-button");
        setButtonIcon(installBtn, IconFactory.Icon.DOWNLOAD, "INSTALL");
        HBox buttonRow = new HBox(10, installBtn);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);

        Runnable load = () -> {
            versionBox.setDisable(true);
            installBtn.setDisable(true);
            status.setText("Looking up compatible builds for Minecraft " + mcVersion + "...");
            Task<List<ModrinthClient.ProjectVersion>> task = new Task<>() {
                @Override
                protected List<ModrinthClient.ProjectVersion> call() throws Exception {
                    return new ModrinthClient().compatibleVersions(slug, mcVersion);
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                var list = task.getValue();
                versionBox.getItems().setAll(list);
                boolean empty = list == null || list.isEmpty();
                if (empty) {
                    status.setText("No " + projectName + " build supports Minecraft " + mcVersion + " yet.");
                } else {
                    versionBox.setValue(list.get(0));
                    status.setText("Newest compatible build is selected first -- pick any listed version to install.");
                }
                versionBox.setDisable(empty);
                installBtn.setDisable(empty);
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                versionBox.setDisable(false);
                installBtn.setDisable(false);
                status.setText("Couldn't load versions: " + task.getException().getMessage());
            }));
            new Thread(task, "modrinth-version-picker").start();
        };

        installBtn.setOnAction(ev -> {
            ModrinthClient.ProjectVersion chosen = versionBox.getValue();
            if (chosen == null) return;
            installBtn.setDisable(true);
            status.setText("Installing " + chosen.versionNumber() + "...");
            Task<Path> installTask = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    ModrinthClient client = new ModrinthClient();
                    Path downloaded = client.download(chosen, targetFolder);
                    if (replaceSlug != null) deleteJarsForSlug(targetFolder, replaceSlug, downloaded);
                    return downloaded;
                }
            };
            installTask.setOnSucceeded(ev2 -> Platform.runLater(() -> {
                var p = installTask.getValue();
                status.setText("Installed " + chosen.versionNumber()
                        + (p == null ? "" : "  (" + p.getFileName() + ")"));
                if (onInstalled != null) onInstalled.run();
                installBtn.setDisable(false);
            }));
            installTask.setOnFailed(ev2 -> Platform.runLater(() -> {
                status.setText("Install failed: " + installTask.getException().getMessage());
                installBtn.setDisable(false);
            }));
            new Thread(installTask, "modrinth-version-install").start();
        });

        box.getChildren().addAll(header,
                sectionLabel("PICK A VERSION"), versionBox,
                buttonRow, status);
        // Owned by whichever window invoked it (the Mods window when changing a client mod), modal
        // so it reliably overlays that window -- but still borderless, styled like the Mods window.
        javafx.stage.Window owner = javafx.stage.Window.getWindows().stream()
                .filter(javafx.stage.Window::isFocused).findFirst().orElse(stage);
        Stage pick = buildBorderlessStage("Version -- " + projectName, box, owner,
                Modality.WINDOW_MODAL, 440, 300, 560, 460);
        load.run();
        pick.showAndWait();
    }

    /** Removes any jar (or disabled .jar.disabled copy) in folder whose name starts with the
     *  project slug -- except {@code keep}. Keeps one fresh copy when updating a project. */
    private void deleteJarsForSlug(Path folder, String slug, Path keep) {
        if (folder == null || !Files.isDirectory(folder)) return;
        String prefix = slug.trim().toLowerCase() + "-";
        try (var stream = Files.list(folder)) {
            for (Path p : (Iterable<Path>) stream
                    .filter(f -> f.toString().matches("(?i).*\\.jar(\\.disabled)?$"))::iterator) {
                if (keep != null && p.equals(keep)) continue;
                String name = p.getFileName().toString().toLowerCase();
                if (name.startsWith(prefix)) {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    private Node buildModrinthRow(ModrinthClient.Hit hit, ModrinthClient modrinth,
                                  ServerAddonsManager addonsManager, Runnable renderAddons,
                                  ServerInstance server) {
        // Project icon (from Modrinth), click-through to the project's Modrinth page for details.
        Node iconNode = remoteModIcon(hit.iconUrl(), 48);
        iconNode.setCursor(javafx.scene.Cursor.HAND);
        iconNode.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 1) {
                openUrl(ModrinthClient.projectPageUrl(hit.slug(), ModrinthClient.projectTypeFor(server.type)));
            }
        });
        Label name = new Label(hit.name());
        name.getStyleClass().add("mod-name");
        Label meta = new Label("by " + hit.author() + "  ·  " + formatDownloads(hit.downloads())
                + " downloads  ·  view on Modrinth ▸");
        meta.getStyleClass().add("notice-label");
        meta.setCursor(javafx.scene.Cursor.HAND);
        meta.setOnMouseClicked(ev -> openUrl(
                ModrinthClient.projectPageUrl(hit.slug(), ModrinthClient.projectTypeFor(server.type))));
        VBox text = new VBox(2, name, meta);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label status = new Label();
        status.getStyleClass().add("notice-label");
        Button installBtn = new Button();
        installBtn.getStyleClass().add("pill-button");
        setButtonIcon(installBtn, IconFactory.Icon.ADD, "Install");
        installBtn.setOnAction(e -> {
            // Opens the shared picker listing ONLY builds for this server's Minecraft version.
            String folderPath = addonsManager.folder() != null ? addonsManager.folder().toString() : null;
            showModrinthVersionPicker(hit.name(), hit.slug(), ModrinthClient.projectTypeFor(server.type),
                    server.minecraftVersion, modrinthIconPath(hit.slug()), hit.iconUrl(),
                    addonsManager.folder(), hit.slug(), renderAddons);
            if (folderPath == null || folderPath.isBlank()) {
                status.setText("This server type can't install addons here.");
                return;
            }
        });

        HBox row = new HBox(10, iconNode, text, spacer, status, installBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        return row;
    }

    private String formatDownloads(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(java.util.Locale.US, "%.0fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private Node buildServerFilesTab(ServerInstance server) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        Path serverRoot = serverStore.serverDir(server.id);

        Label pathLabel = new Label(serverRoot.toString());
        pathLabel.getStyleClass().add("notice-label");
        pathLabel.setWrapText(true);

        VBox listBox = new VBox(4);
        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("settings-scroll");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        Path[] currentDir = { serverRoot };
        Label currentPathLabel = new Label();
        currentPathLabel.getStyleClass().add("field-label");

        Runnable[] renderFiles = new Runnable[1];
        renderFiles[0] = () -> {
            currentPathLabel.setText(serverRoot.relativize(currentDir[0]).toString().isEmpty()
                    ? "/" : "/" + serverRoot.relativize(currentDir[0]));
            listBox.getChildren().clear();

            if (!currentDir[0].equals(serverRoot)) {
                Button upBtn = new Button(".. (up)");
                upBtn.getStyleClass().add("pill-button");
                upBtn.setOnAction(e -> {
                    currentDir[0] = currentDir[0].getParent();
                    renderFiles[0].run();
                });
                listBox.getChildren().add(upBtn);
            }

            try (var stream = Files.list(currentDir[0])) {
                var entries = stream.sorted(Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                        .toList();
                for (Path entry : entries) {
                    boolean isDir = Files.isDirectory(entry);
                    Label icon = new Label();
                    icon.setGraphic(icon(isDir ? IconFactory.Icon.FOLDER : IconFactory.Icon.FILE));
                    icon.getStyleClass().add("icon-svg");
                    icon.setGraphicTextGap(0);
                    Label nameLabel = new Label(entry.getFileName().toString());
                    nameLabel.getStyleClass().add(isDir ? "mod-name" : "mod-filename");
                    Region rowSpacer = new Region();
                    HBox.setHgrow(rowSpacer, Priority.ALWAYS);
                    Button deleteBtn = new Button();
                    deleteBtn.getStyleClass().add("mod-delete-button");
                    setButtonIconOnly(deleteBtn, IconFactory.Icon.TRASH);
                    deleteBtn.setOnAction(e -> {
                        try {
                            if (isDir) {
                                try (var walk = Files.walk(entry)) {
                                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                                        try {
                                            Files.delete(p);
                                        } catch (IOException ignored) {
                                        }
                                    });
                                }
                            } else {
                                Files.deleteIfExists(entry);
                            }
                        } catch (IOException ignored) {
                        }
                        renderFiles[0].run();
                    });
                    HBox row = new HBox(10, icon, nameLabel, rowSpacer, deleteBtn);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.getStyleClass().add("mod-row");
                    if (isDir) {
                        row.setOnMouseClicked(e -> {
                            // Security: never navigate above serverRoot -- entry is always a
                            // direct child of currentDir[0], which itself is only ever set from
                            // serverRoot or one of its descendants below, so this can't escape.
                            currentDir[0] = entry;
                            renderFiles[0].run();
                        });
                    }
                    listBox.getChildren().add(row);
                }
            } catch (IOException ignored) {
            }
        };
        renderFiles[0].run();

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("pill-button");
        refreshBtn.setOnAction(e -> renderFiles[0].run());
        HBox topRow = new HBox(10, currentPathLabel, refreshBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(pathLabel, topRow, listScroll);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node buildServerSettingsTab(ServerInstance server) {
        VBox box = new VBox(16);
        box.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        Slider minRamSlider = new Slider(512, 8192, server.ramMinMb);
        Label minRamLabel = new Label("Min RAM: " + server.ramMinMb + " MB");
        minRamLabel.getStyleClass().add("settings-value-label");
        minRamSlider.valueProperty().addListener((o, a, b) -> minRamLabel.setText("Min RAM: " + b.intValue() + " MB"));

        Slider maxRamSlider = new Slider(1024, 16384, server.ramMaxMb);
        Label maxRamLabel = new Label("Max RAM: " + server.ramMaxMb + " MB");
        maxRamLabel.getStyleClass().add("settings-value-label");
        maxRamSlider.valueProperty().addListener((o, a, b) -> maxRamLabel.setText("Max RAM: " + b.intValue() + " MB"));

        TextField portField = new TextField(String.valueOf(server.port));
        portField.getStyleClass().add("input-field");

        ComboBox<String> javaEnvBox = new ComboBox<>();
        javaEnvBox.getItems().add("Auto (recommended for this version)");
        Path runtimesRoot = gameFiles.root.resolve("runtimes");
        if (Files.isDirectory(runtimesRoot)) {
            try (var platforms = Files.list(runtimesRoot)) {
                for (Path platformDir : (Iterable<Path>) platforms.filter(Files::isDirectory)::iterator) {
                    try (var components = Files.list(platformDir)) {
                        for (Path componentDir : (Iterable<Path>) components.filter(Files::isDirectory)::iterator) {
                            String label = platformDir.getFileName() + " / " + componentDir.getFileName();
                            javaEnvBox.getItems().add(label);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        javaEnvBox.setValue(server.javaOverridePath == null ? javaEnvBox.getItems().get(0) : server.javaOverridePath);
        javaEnvBox.getStyleClass().add("input-field");
        javaEnvBox.setMaxWidth(Double.MAX_VALUE);
        Label javaEnvNote = new Label("Only already-downloaded runtimes show up here -- launch this "
                + "server's matching client version once first if you want a specific one available.");
        javaEnvNote.getStyleClass().add("notice-label");
        javaEnvNote.setWrapText(true);

        // ---- Dirty tracking ----
        SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        Runnable markDirty = () -> dirty.set(true);
        minRamSlider.valueProperty().addListener((o, a, b) -> markDirty.run());
        maxRamSlider.valueProperty().addListener((o, a, b) -> markDirty.run());
        portField.textProperty().addListener((o, a, b) -> markDirty.run());
        javaEnvBox.valueProperty().addListener((o, a, b) -> markDirty.run());

        // ---- Save: pinned bottom bar that only glows once something changed ----
        Button saveBtn = buildDirtyButton(dirty);
        Label savedNote = new Label();
        savedNote.getStyleClass().add("notice-label");
        saveBtn.setOnAction(e -> {
            server.ramMinMb = (int) minRamSlider.getValue();
            server.ramMaxMb = (int) maxRamSlider.getValue();
            server.port = parseIntSafe(portField.getText(), server.port);
            server.javaOverridePath = javaEnvBox.getValue().startsWith("Auto") ? null : javaEnvBox.getValue();
            serverStore.save(server);
            savedNote.setText("Saved -- some changes (port, RAM) take effect next server start.");
            dirty.set(false);
        });

        // Memory Min/Max side by side so the pair reads as one setting.
        GridPane memGrid = new GridPane();
        memGrid.setHgap(24);
        memGrid.setVgap(8);
        memGrid.add(minRamLabel, 0, 0);
        memGrid.add(maxRamLabel, 1, 0);
        memGrid.add(minRamSlider, 0, 1);
        memGrid.add(maxRamSlider, 1, 1);

        // ---- Danger zone: permanently delete this server ----
        Region dangerSpacer = new Region();
        HBox.setHgrow(dangerSpacer, Priority.ALWAYS);

        Button deleteBtn = new Button();
        setButtonIconOnly(deleteBtn, IconFactory.Icon.TRASH);
        deleteBtn.getStyleClass().add("danger-delete-button");
        deleteBtn.setTooltip(new Tooltip("Delete this server permanently"));

        Label dangerTitle = new Label("Delete this server");
        dangerTitle.getStyleClass().add("danger-title");
        HBox dangerHeader = new HBox(12, deleteBtn, dangerTitle, dangerSpacer);
        dangerHeader.setAlignment(Pos.CENTER_LEFT);

        Label dangerNote = new Label("Permanently removes this server and all of its world, "
                + "player and addon data from this PC. This can't be undone.");
        dangerNote.getStyleClass().add("notice-label");
        dangerNote.setWrapText(true);

        VBox dangerZone = new VBox(10, dangerHeader, dangerNote);
        dangerZone.getStyleClass().add("server-danger-zone");

        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete \"" + server.name + "\"?\nIts folder and all server data will be removed permanently.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Delete server");
            var pick = confirm.showAndWait();
            if (pick.isPresent() && pick.get() == ButtonType.OK) {
                ServerProcessManager pm = runningServers.remove(server.id);
                if (pm != null) pm.stop();
                try {
                    serverStore.delete(server.id);
                } catch (Exception ex) {
                    log("Failed to delete server: " + ex.getMessage());
                }
                PlayerIdentity me = identityStore.getActive();
                if (me != null) {
                    try { publishOwnedServers(me); } catch (Exception ignored) { }
                }
                renderServersPageContent();
                javafx.stage.Stage mgmt = shellWindows.get("server-" + server.id);
                if (mgmt != null) mgmt.close();
            }
        });

        box.getChildren().addAll(
                sectionLabel("MEMORY"), memGrid,
                sectionLabel("PORT"), portField,
                sectionLabel("JAVA ENVIRONMENT"), javaEnvBox, javaEnvNote,
                sectionLabel("DANGER ZONE"), dangerZone);
        return tabShell(scroll, saveBtn, savedNote);
    }

    private Node buildServerPermissionsTab(ServerInstance server) {
        VBox box = new VBox(16);
        box.setPadding(new Insets(20));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        CheckBox friendsJoinBox = new CheckBox("Allow friends to join while this server is running");
        friendsJoinBox.setSelected(server.allowFriendsJoin);
        Label friendsJoinNote = new Label("When on and this server is running, your online friends "
                + "see it under \"Friends Playing Now\" and can Join directly.");
        friendsJoinNote.getStyleClass().add("notice-label");
        friendsJoinNote.setWrapText(true);

        CheckBox saveBox = new CheckBox("Allow whitelisted players to save/download this server to self-host");
        saveBox.setSelected(server.allowPlayerSave);
        Label saveNote = new Label("Matches the original hand-off hosting design -- not wired to a "
                + "real download/handoff flow yet, this just records the permission for when it is.");
        saveNote.getStyleClass().add("notice-label");
        saveNote.setWrapText(true);

        CheckBox multihostBox = new CheckBox("Multihosting (coming later)");
        multihostBox.setSelected(false);
        multihostBox.setDisable(true);
        Label multihostNote = new Label("Reserved for the planned multihosting feature -- not implemented yet.");
        multihostNote.getStyleClass().add("notice-label");

        // ---- Dirty tracking ----
        SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        Runnable markDirty = () -> dirty.set(true);
        friendsJoinBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        saveBox.selectedProperty().addListener((o, a, b) -> markDirty.run());

        // ---- Save: pinned bottom bar that only glows once something changed ----
        Button saveBtn = buildDirtyButton(dirty);
        Label savedNote = new Label();
        savedNote.getStyleClass().add("notice-label");
        saveBtn.setOnAction(e -> {
            server.allowFriendsJoin = friendsJoinBox.isSelected();
            server.allowPlayerSave = saveBox.isSelected();
            serverStore.save(server);
            savedNote.setText("Saved -- takes effect immediately for presence, or on next save download.");
            dirty.set(false);
        });

        box.getChildren().addAll(
                sectionLabel("FRIENDS"), friendsJoinBox, friendsJoinNote,
                sectionLabel("PLAYER SAVE"), saveBox, saveNote,
                sectionLabel("MULTIHOSTING"), multihostBox, multihostNote);
        return tabShell(scroll, saveBtn, savedNote);
    }

    /** Best-effort local LAN address for the copyable server IP box -- falls back to loopback if detection fails. */
    private String localIpAddress() {
        try {
            var addr = java.net.InetAddress.getLocalHost();
            if (!addr.isLoopbackAddress()) return addr.getHostAddress();
        } catch (Exception ignored) {
        }
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                var addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "localhost";
    }

    /**
     * Records a LIVE join into {@code target}: this install's own game process is (or, for a launch
     * straight into a server, is about to be) on that server, and friends should see it right away.
     *
     * <p>Nothing here is saved to disk any more. The old version wrote the address into
     * launcher.properties and force-enabled sharing, which is exactly why a single join could keep
     * announcing "playing on &lt;server&gt;" forever -- including while Minecraft was closed or a
     * single-player world was open (see currentPresence).
     *
     * <p>Joining our OWN locally-hosted server (localhost / our own LAN IP) still counts as being on a
     * server, but no address is published: no friend could reach it.
     */
    private void rememberCurrentlyJoined(String target) {
        if (target == null || target.isBlank()) return;
        String trimmed = target.trim();
        // A loopback / own-LAN server is still a server we are on, but its address is useless to a
        // friend (they could not reach it), so the live state carries NO address for it and friends
        // read "on a server (address not shared)" -- see the javadoc above. How friends reach a
        // server we actually host is a different path (publishPresenceWithServer).
        boolean shareable = isShareableServerAddress(trimmed);
        String resolved = shareable ? resolveServerDisplay(trimmed) : null;
        // Only publish a friendly name when it actually adds something over the raw address.
        String friendly = (resolved != null && !resolved.equalsIgnoreCase(trimmed)) ? resolved : null;
        setLiveServer(shareable ? trimmed : null, friendly);
    }

    /** Live session: on a multiplayer server at {@code address} (never from persisted state). */
    private void setLiveServer(String address, String resolvedName) {
        if (liveStateAlready(PlayState.SERVER, address, resolvedName)) return;
        liveServerAddress = address;
        liveServerName = resolvedName;
        livePlayState = PlayState.SERVER;
        refreshShareCard(); // the Account tab's card says what is shared right now
        publishPresenceQuietly();
    }

    /** Live session: inside an integrated (single-player/LAN) world -- not a server, so no address. */
    private void setLiveSinglePlayer() {
        if (liveStateAlready(PlayState.SINGLE_PLAYER, null, null)) return;
        liveServerAddress = null;
        liveServerName = null;
        livePlayState = PlayState.SINGLE_PLAYER;
        refreshShareCard();
        publishPresenceQuietly();
    }

    /** Live session: in the launcher (or in the game's menus) -- not playing anywhere. */
    private void setLiveInLauncher() {
        if (liveStateAlready(PlayState.IN_LAUNCHER, null, null)) return;
        liveServerAddress = null;
        liveServerName = null;
        livePlayState = PlayState.IN_LAUNCHER;
        refreshShareCard();
        publishPresenceQuietly();
    }

    /**
     * True when the live session already says exactly this. Presence writes go through the shared bot
     * account's GitHub API, and a proxy hand-off can re-announce the same address repeatedly -- so a
     * no-change update is skipped rather than burning a write (the heartbeat still refreshes lastSeen).
     */
    private boolean liveStateAlready(PlayState state, String address, String name) {
        return livePlayState == state
                && java.util.Objects.equals(liveServerAddress, address)
                && java.util.Objects.equals(liveServerName, name);
    }

    /**
     * Applies one {@link ServerSessionTracker} event for the game process this launcher started. Must
     * run on the FX thread (the tracker is fed from the game's output thread, and this republishes
     * presence), which is what the callers' {@code Platform.runLater} wrappers are for.
     */
    private void applySessionEvent(ServerSessionTracker.Event event, ServerSessionTracker tracker) {
        switch (event) {
            case JOINED -> rememberCurrentlyJoined(tracker.address());
            case SINGLE_PLAYER_ENTERED -> setLiveSinglePlayer();
            case LEFT -> setLiveInLauncher();
            case NONE -> {
                // Nothing for friends to see changed.
            }
        }
    }

    private void runFriendsAction(PlayerIdentity active, java.util.concurrent.Callable<FriendsService.FriendsView> action) {
        Task<FriendsService.FriendsView> task = new Task<>() {
            @Override
            protected FriendsService.FriendsView call() throws Exception {
                return action.call();
            }
        };
        task.setOnSucceeded(e -> {
            friendsCache.save(task.getValue());
            renderFriendsPageContent(active, task.getValue());
        });
        task.setOnFailed(e -> new Alert(Alert.AlertType.ERROR,
                "Action failed: " + task.getException().getMessage(), ButtonType.OK).showAndWait());
        new Thread(task, "friends-action").start();
    }

    /** The main-page account button: skin face + player name + an online/offline status dot, instead of a plain "Account" label. */
    private Button buildAccountButton() {
        accountBtnFace = new ImageView();
        accountBtnFace.setFitWidth(22);
        accountBtnFace.setFitHeight(22);
        accountBtnFace.setSmooth(false);
        accountBtnFace.getStyleClass().add("account-btn-face");

        accountBtnDot = new Region();
        accountBtnDot.getStyleClass().add("account-status-dot");

        accountBtnName = new Label("No Account");
        accountBtnName.getStyleClass().add("account-btn-name");

        StackPane faceHost = new StackPane(accountBtnFace);
        faceHost.getStyleClass().add("account-btn-face-host");

        HBox content = new HBox(8, faceHost, accountBtnName, accountBtnDot);
        content.setAlignment(Pos.CENTER_LEFT);

        Button btn = new Button();
        btn.setGraphic(content);
        btn.getStyleClass().addAll("pill-button", "account-button");
        btn.setOnAction(e -> openPreferencesDialog("Account"));
        return btn;
    }

    /** Refreshes the main account button's face/name/status dot from the currently active account. Call after any account or skin change. */
    private void refreshAccountButton() {
        PlayerIdentity active = identityStore.getActive();
        accountBtnDot.getStyleClass().removeAll("dot-online", "dot-offline", "dot-none");
        if (active == null) {
            accountBtnName.setText("No Account");
            accountBtnFace.setImage(null);
            accountBtnDot.getStyleClass().add("dot-none");
            return;
        }
        accountBtnName.setText(active.username);
        accountBtnDot.getStyleClass().add(active.accountType == AccountType.ONLINE ? "dot-online" : "dot-offline");
        accountBtnFace.setImage(faceIcon(active));
    }

    /** Crops just the front-facing head (8x8 region at (8,8)) out of a skin texture, so the button shows a face, not the whole sheet. */
    private Image faceIcon(PlayerIdentity identity) {
        java.nio.file.Path skinPath = identityStore.skinFile(identity.uuid);
        if (identity.skinSource == SkinSource.DEFAULT || !java.nio.file.Files.exists(skinPath)) {
            return faceThumbnail(null); // no custom skin set yet -- still must be a cropped face, not the whole placeholder sheet
        }
        try {
            Image full = new Image(skinPath.toUri().toString());
            return faceThumbnail(full);
        } catch (Exception e) {
            return faceThumbnail(null);
        }
    }

    /**
     * Shared face crop used everywhere a small avatar is shown (main account button, Skin
     * Profile tiles, Friends list). Bug fixed here: every fallback branch used to return
     * defaultSteveImage() directly -- the WHOLE 64x64 placeholder sheet, uncropped -- instead of
     * a face. That's exactly why offline accounts with no custom skin set yet (the default state
     * for a brand-new offline account) showed the entire skin texture as their "face," while
     * online accounts never hit this path at all (their real skin syncs locally on sign-in, so
     * skinSource is never DEFAULT for them) -- which is why only offline looked broken. Every
     * return path below is now guaranteed to be an 8x8 crop, never the raw sheet.
     */
    private Image faceThumbnail(Image skin) {
        Image source = skin;
        if (source == null || source.getPixelReader() == null || source.getWidth() < 16 || source.getHeight() < 16) {
            source = defaultSteveImage(); // still a full sheet at this point -- cropped below, never returned as-is
        }
        try {
            return new javafx.scene.image.WritableImage(source.getPixelReader(), 8, 8, 8, 8);
        } catch (Exception e) {
            // Last-resort flat-color 8x8 face -- guarantees we never fall back to the whole sheet.
            var img = new javafx.scene.image.WritableImage(8, 8);
            var w = img.getPixelWriter();
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) w.setColor(x, y, Color.web("#dca57a"));
            }
            return img;
        }
    }

    // ---- Main (Home) page: left column is the VANILLA/DEY mode switch + version tiles,
    // right column is the details panel (loader, version, mods, launch) for whatever is
    // selected on the left -- same two-pane shape referenced from Lunar-style launchers,
    // built from scratch with our own three-color theme, not their layout or assets. ----
    private HBox buildMainPage() {
        // ---- Left column ----
        vanillaModeBtn = new ToggleButton("VANILLA");
        deyModeBtn = new ToggleButton("DEY");
        ToggleGroup modeGroup = new ToggleGroup();
        vanillaModeBtn.setToggleGroup(modeGroup);
        deyModeBtn.setToggleGroup(modeGroup);
        vanillaModeBtn.getStyleClass().addAll("mode-toggle-btn", "mode-vanilla");
        deyModeBtn.getStyleClass().addAll("mode-toggle-btn", "mode-dey");
        vanillaModeBtn.setMaxWidth(Double.MAX_VALUE);
        deyModeBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(vanillaModeBtn, Priority.ALWAYS);
        HBox.setHgrow(deyModeBtn, Priority.ALWAYS);
        vanillaModeBtn.setOnAction(e -> { if (vanillaModeBtn.isSelected()) setMode(false); });
        deyModeBtn.setOnAction(e -> { if (deyModeBtn.isSelected()) setMode(true); });

        HBox modeToggleRow = new HBox(4, vanillaModeBtn, deyModeBtn);
        modeToggleRow.getStyleClass().add("mode-toggle-group");
        modeToggleRow.setAlignment(Pos.CENTER);
        modeToggleRow.setMaxWidth(Double.MAX_VALUE);
        // At a raised text scale (Settings > Launcher) the two labels used to collapse into "...".
        // Reason: a Button's MINIMUM width is only the width of the ellipsis (see JavaFX's
        // LabeledSkinBase), so an HBox may shrink "VANILLA"/"DEY" down to nothing but dots.
        // USE_PREF_SIZE pins the minimum to the row's preferred (full-text) width, so the labels
        // always fit -- at any text scale and with any font.
        modeToggleRow.setMinWidth(Region.USE_PREF_SIZE);
        // ...and let the switch stretch across the whole row instead of sitting in the middle as a
        // small fixed block: both pills get wider, so each label has room to breathe beside the
        // modpack icon button.
        HBox.setHgrow(modeToggleRow, Priority.ALWAYS);

        // The modpack button, sitting directly beside the DEY / VANILLA switch (as asked): it opens
        // the modpack menu -- add one from disk, drop one in, or jump to an installed pack.
        modpackBtn = new Button();
        setButtonIconOnly(modpackBtn, IconFactory.Icon.MODPACK);
        modpackBtn.getStyleClass().add("icon-button");
        modpackBtn.setTooltip(new Tooltip("Modpacks -- add one, or drag & drop a .mrpack / .zip"));
        modpackBtn.setOnAction(e -> showModpackMenu());

        HBox modeRow = new HBox(6, modeToggleRow, modpackBtn);
        modeRow.setAlignment(Pos.CENTER);
        // Same guard one level up: the fixed-size modpack icon button must never eat into the
        // switch's full-label width.
        modeRow.setMinWidth(Region.USE_PREF_SIZE);

        versionTileList = new VBox(10);
        versionTileList.getStyleClass().add("tile-list");

        VBox left = new VBox(18, modeRow, versionTileList);
        left.getStyleClass().add("side-panel");
        left.setPadding(new Insets(20));
        // Width floor, invisible (0 tall, just reserves horizontal room): keeps the sidebar at the
        // familiar 240px at 100% UI/text scale -- 200px tile column + the panel's 20px padding on
        // each side -- while still letting it grow past that when a label genuinely needs the room.
        Region sidebarWidthFloor = new Region();
        sidebarWidthFloor.setPrefWidth(200);
        sidebarWidthFloor.setPrefHeight(0);
        left.getChildren().add(sidebarWidthFloor);
        // The sidebar is exactly as wide as its widest child -- the VANILLA/DEY switch, or a
        // "VANILLA All Versions"-style tile label -- and can never be squeezed below that. The old
        // hard 240px (210px minimum) was narrower than those labels at a raised text scale, which
        // is what truncated them to "..." instead of widening the column.
        left.setPrefWidth(Region.USE_COMPUTED_SIZE);
        left.setMinWidth(Region.USE_PREF_SIZE);

        // ---- Right column ----
        mainHeadingLabel = new Label("Minecraft");
        mainHeadingLabel.getStyleClass().add("card-heading");

        mainDescriptionLabel = new Label();
        mainDescriptionLabel.getStyleClass().add("notice-label");
        mainDescriptionLabel.setWrapText(true);

        Label offlineNotice = new Label(
                "Offline test mode -- local play only, can't join real online servers yet.");
        offlineNotice.getStyleClass().add("notice-label");
        this.accountStatusNotice = offlineNotice;

        Label modLoaderLabel = new Label("LOADER");
        modLoaderLabel.getStyleClass().add("field-label");
        modLoaderBox = new ComboBox<>();
        modLoaderBox.getStyleClass().add("input-field");
        modLoaderBox.setMaxWidth(Double.MAX_VALUE);
        modLoaderBox.valueProperty().addListener((o, a, b) -> {
            boolean showMods = b != null && !b.equals("Vanilla");
            modsBtn.setVisible(showMods);
            modsBtn.setManaged(showMods);
        });

        Label versionLabel = new Label("VERSION");
        versionLabel.getStyleClass().add("field-label");
        versionBox = new ComboBox<>();
        versionBox.setPromptText("Loading versions...");
        versionBox.getStyleClass().add("input-field");
        versionBox.setMaxWidth(Double.MAX_VALUE);

        modsBtn = new Button();
        setButtonIcon(modsBtn, IconFactory.Icon.PUZZLE, "Mods");
        modsBtn.getStyleClass().add("pill-button");
        modsBtn.setMaxWidth(Double.MAX_VALUE);
        modsBtn.setOnAction(e -> openModsDialog());
        modsBtn.setVisible(false);
        modsBtn.setManaged(false);

        playButton = new Button();
        setButtonIcon(playButton, IconFactory.Icon.PLAY, "PLAY");
        playButton.getStyleClass().add("play-button");
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setOnAction(e -> onPlay());

        launchProgress = new WaveLaunchBar();
        launchProgress.setMaxWidth(Double.MAX_VALUE);
        launchProgress.setVisible(false);
        launchProgress.setManaged(false);
        launchProgress.getStyleClass().add("play-progress");

        VBox right = new VBox(14,
                mainHeadingLabel, mainDescriptionLabel, offlineNotice,
                modLoaderLabel, modLoaderBox,
                versionLabel, versionBox,
                modsBtn, playButton, launchProgress);
        right.setPadding(new Insets(32));
        right.setAlignment(Pos.CENTER_LEFT);
        right.getStyleClass().add("play-card");
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox main = new HBox(20, left, right);
        main.setPadding(new Insets(24, 28, 8, 28));
        main.setAlignment(Pos.TOP_LEFT);

        setMode(prefs.lastDeyMode); // reopen in whichever mode you last played (DEY by default)
        restoreLastPlayedVersion();

        // Keep the modpack button showing the active pack's own icon as the selection changes (icon
        // compatibility: a pack you installed looks like itself right in the launcher).
        versionBox.valueProperty().addListener((o, a, b) -> refreshModpackButtonIcon(b, modLoaderBox.getValue()));
        modLoaderBox.valueProperty().addListener((o, a, b) -> refreshModpackButtonIcon(versionBox.getValue(), b));
        refreshModpackButtonIcon(versionBox.getValue(), modLoaderBox.getValue());
        return main;
    }

    /** Switches between VANILLA (clean Vanilla/Fabric/Forge, any version) and DEY (Fabric only,
     * curated mods with Sodium baked in, release builds only) and rebuilds the loader choices
     * and left-column filter tiles to match. Also recolors the Play button to match whichever
     * mode is active, the same way the mode toggle itself is colored. */
    private void setMode(boolean dey) {
        this.deyMode = dey;
        vanillaModeBtn.setSelected(!dey);
        deyModeBtn.setSelected(dey);

        playButton.getStyleClass().removeAll("play-button-vanilla", "play-button-dey");
        playButton.getStyleClass().add(dey ? "play-button-dey" : "play-button-vanilla");

        modLoaderBox.getItems().clear();
        if (dey) {
            modLoaderBox.getItems().addAll("Fabric");
            modLoaderBox.setValue("Fabric");
            modLoaderBox.setDisable(false);
            mainDescriptionLabel.setText(
                    "DEY builds run a curated set of performance and quality-of-life mods -- "
                            + "Sodium + Iris (shaders) + Fabric API install automatically, no setup needed.");
        } else {
            modLoaderBox.getItems().addAll("Vanilla", "Fabric", "Forge", "NeoForge");
            modLoaderBox.setValue("Vanilla");
            modLoaderBox.setDisable(false);
            mainDescriptionLabel.setText(
                    "Clean, unmodified loaders -- pick Vanilla, Fabric, Forge, or NeoForge yourself.");
        }
        rebuildVersionTiles();
        refreshModpackButtonIcon(versionBox.getValue(), modLoaderBox.getValue());
    }

    /** Rebuilds the left-column filter tiles for the current mode and selects the first one. */
    private void rebuildVersionTiles() {
        versionTileList.getChildren().clear();
        VersionPreset[] presets = deyMode ? DEY_PRESETS : VANILLA_PRESETS;
        String modePrefix = deyMode ? "DEY " : "VANILLA ";
        Button firstTile = null;
        for (VersionPreset preset : presets) {
            Button tile = new Button(modePrefix + preset.label());
            tile.getStyleClass().add("version-tile");
            tile.setMaxWidth(Double.MAX_VALUE);
            tile.setAlignment(Pos.CENTER_LEFT);
            tile.setOnAction(e -> selectVersionTile(preset, tile));
            versionTileList.getChildren().add(tile);
            if (firstTile == null) firstTile = tile;
        }
        if (firstTile != null) selectVersionTile(presets[0], firstTile);
    }

    /** A tile is a filter, not a single version -- selecting one narrows the Version dropdown
     * down to whatever in allVersions matches the tile's rule, instead of just picking one id. */
    private void selectVersionTile(VersionPreset preset, Button tile) {
        selectVersionTile(preset, tile, null);
    }

    private void selectVersionTile(VersionPreset preset, Button tile, String preferredVersionId) {
        for (var node : versionTileList.getChildren()) {
            node.getStyleClass().remove("version-tile-active");
        }
        tile.getStyleClass().add("version-tile-active");
        activePreset = preset;
        mainHeadingLabel.setText("Minecraft " + preset.label());
        applyVersionFilter(preset, preferredVersionId);
    }

    private void applyVersionFilter(VersionPreset preset) {
        applyVersionFilter(preset, null);
    }

    /** Filters allVersions by the given preset's rule and loads the result into versionBox.
     * preferredVersionId, if it's in the filtered result, is selected instead of the first
     * match -- used to restore the exact version you last played. The dropdown is never left
     * empty: if nothing matches yet (usually just because the real list hasn't loaded), this
     * falls back to the full list so there's always something selected and changeable. */
    private void applyVersionFilter(VersionPreset preset, String preferredVersionId) {
        List<String> matched = new ArrayList<>();
        for (VersionManifest.VersionEntry v : allVersions) {
            if (preset.releaseOnly() && !"release".equals(v.type())) continue;
            boolean ok = switch (preset.kind()) {
                case ALL -> true;
                case MIN_MAJOR -> parseLeadingMajor(v.id()) >= Integer.parseInt(preset.matchValue());
                case FAMILY -> matchesFamily(v.id(), preset.matchValue());
            };
            if (ok) matched.add(v.id());
        }
        if (matched.isEmpty()) {
            // Nothing matches this filter yet -- fall back to the full list (always at least
            // SYNTHETIC_26) instead of leaving the dropdown empty and unselectable.
            for (VersionManifest.VersionEntry v : allVersions) matched.add(v.id());
        }
        versionBox.getItems().setAll(matched);
        versionBox.setValue(preferredVersionId != null && matched.contains(preferredVersionId)
                ? preferredVersionId : matched.get(0));
    }

    /** Restores the exact version+mode you last hit Play with, if it still matches one of the
     * current mode's tiles. Called once right after the tiles are first built (best-effort, off
     * whatever's loaded so far) and again once the real version list finishes loading. */
    private void restoreLastPlayedVersion() {
        String last = prefs.lastVersionId;
        if (last == null || last.isBlank()) return;
        VersionPreset[] presets = deyMode ? DEY_PRESETS : VANILLA_PRESETS;
        for (int i = 0; i < presets.length && i < versionTileList.getChildren().size(); i++) {
            if (presetCouldMatch(presets[i], last)) {
                Button tile = (Button) versionTileList.getChildren().get(i);
                selectVersionTile(presets[i], tile, last);
                return;
            }
        }
        // No tile in the current mode covers it (e.g. a snapshot under DEY's release-only
        // tiles) -- leave whatever setMode()'s default tile selection already picked.
    }

    private boolean presetCouldMatch(VersionPreset preset, String versionId) {
        if (preset.releaseOnly()) {
            String type = null;
            for (var v : allVersions) if (v.id().equals(versionId)) { type = v.type(); break; }
            // Unknown type (list not loaded yet) is allowed through optimistically here --
            // the second restoreLastPlayedVersion() call after loading corrects it for real.
            if (type != null && !"release".equals(type)) return false;
        }
        return switch (preset.kind()) {
            case ALL -> true;
            case MIN_MAJOR -> parseLeadingMajor(versionId) >= Integer.parseInt(preset.matchValue());
            case FAMILY -> matchesFamily(versionId, preset.matchValue());
        };
    }

    /** Leading run of digits in a version id, e.g. "1" for "1.21.4", "26" for "26.2",
     * "24" for a "24w14a"-style snapshot. Returns -1 if the id doesn't start with a digit. */
    private static int parseLeadingMajor(String id) {
        int i = 0;
        while (i < id.length() && Character.isDigit(id.charAt(i))) i++;
        if (i == 0) return -1;
        try {
            return Integer.parseInt(id.substring(0, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True if id is exactly prefix, or starts with prefix followed by a boundary character
     * (".", "-", "_") -- so "1.21" matches "1.21", "1.21.1", "1.21-rc1", but not "1.210". */
    private static boolean matchesFamily(String id, String prefix) {
        if (!id.startsWith(prefix)) return false;
        if (id.length() == prefix.length()) return true;
        char next = id.charAt(prefix.length());
        return next == '.' || next == '-' || next == '_';
    }

    private VBox buildLogPane() {
        Label logLabel = new Label("GAME OUTPUT");
        logLabel.getStyleClass().add("field-label");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox box = new VBox(8, logLabel, logArea);
        box.setPadding(new Insets(16, 28, 24, 28));
        box.getStyleClass().add("log-section");
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    // ---- Modpacks: the icon button beside DEY/VANILLA, the installer window, and drag & drop ----

    /** The modpack menu (a themed Popup, like the online-player suggestions -- the launcher has no
     *  MenuButton anywhere, and this keeps one consistent surface). Lists the two ways to add a pack,
     *  then every pack already installed, so you can jump straight back into one. */
    private void showModpackMenu() {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light", "suggest-pop");
        box.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        box.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        box.setPrefWidth(300);

        Button addBtn = new Button("Add modpack (.mrpack / .zip)");
        addBtn.getStyleClass().addAll("pill-button", "suggest-item");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setAlignment(Pos.CENTER_LEFT);
        addBtn.setGraphic(icon(IconFactory.Icon.ADD, 16));
        addBtn.setGraphicTextGap(8);
        addBtn.setOnAction(e -> {
            popup.hide();
            chooseModpackFileAndInstall();
        });

        Button dropBtn = new Button("Drag & drop a modpack");
        dropBtn.getStyleClass().addAll("pill-button", "suggest-item");
        dropBtn.setMaxWidth(Double.MAX_VALUE);
        dropBtn.setAlignment(Pos.CENTER_LEFT);
        dropBtn.setGraphic(icon(IconFactory.Icon.DOWNLOAD, 16));
        dropBtn.setGraphicTextGap(8);
        dropBtn.setOnAction(e -> {
            popup.hide();
            openModpackInstaller(null);
        });

        box.getChildren().addAll(addBtn, dropBtn, sectionLabel("INSTALLED MODPACKS"));

        List<ModpackMeta> installed = ModpackMeta.listInstalled(gameFiles.root);
        if (installed.isEmpty()) {
            box.getChildren().add(suggestHint("No modpacks installed yet."));
        } else {
            for (ModpackMeta meta : installed) box.getChildren().add(modpackMenuRow(meta, popup));
        }

        popup.getContent().add(box);
        var bounds = modpackBtn.localToScreen(modpackBtn.getBoundsInLocal());
        if (bounds != null) popup.show(modpackBtn, bounds.getMinX(), bounds.getMaxY() + 6);
    }

    /** One installed pack in the modpack menu, with the pack's own icon. Clicking it selects that
     *  pack's Minecraft version + loader in the launcher, which is what makes it "the pack you play".
     *  A trailing delete icon button removes the pack entirely -- its modpack.json, its mods, and
     *  every other file installed under that version+loader's instance folder. */
    private HBox modpackMenuRow(ModpackMeta meta, Popup popup) {
        StringBuilder detail = new StringBuilder();
        if (meta.mcVersion != null && !meta.mcVersion.isBlank()) detail.append("Minecraft ").append(meta.mcVersion);
        if (meta.loader != null && !meta.loader.isBlank() && !meta.loader.equals("Vanilla")) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append(meta.loader);
        }
        Button row = new Button(meta.name + (detail.length() == 0 ? "" : "  ·  " + detail));
        row.getStyleClass().addAll("pill-button", "suggest-item");
        row.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(row, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setGraphic(modIconNode(meta.iconPath == null ? null : Path.of(meta.iconPath), 36));
        row.setGraphicTextGap(10);
        row.setTooltip(new Tooltip("Play this modpack"));
        row.setOnAction(e -> {
            popup.hide();
            if (deyMode) setMode(false); // a pack is a VANILLA-mode setup; DEY would inject its own mods
            if (!selectVersionAndLoader(meta.mcVersion, meta.loader)) {
                log("Couldn't switch to \"" + meta.name + "\" -- Minecraft " + meta.mcVersion
                        + " isn't in the launcher's version list yet.");
            }
        });

        Button repairBtn = new Button();
        repairBtn.setGraphic(icon(IconFactory.Icon.TOOLS, 16));
        repairBtn.setGraphicTextGap(0);
        repairBtn.getStyleClass().add("icon-button");
        repairBtn.setTooltip(new Tooltip("Check this pack's files and download anything missing"));
        repairBtn.setOnAction(e -> {
            popup.hide();
            repairInstalledModpack(meta);
        });

        Button deleteBtn = new Button();
        deleteBtn.setGraphic(icon(IconFactory.Icon.TRASH, 16));
        deleteBtn.setGraphicTextGap(0);
        deleteBtn.getStyleClass().add("mod-delete-button");
        deleteBtn.setTooltip(new Tooltip("Delete this modpack and its mods"));
        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete \"" + meta.name + "\"?\nIts mods and every file installed for this modpack "
                            + "will be removed permanently. Shared singleplayer worlds are not touched.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Delete modpack");
            var pick = confirm.showAndWait();
            if (pick.isEmpty() || pick.get() != ButtonType.OK) return;
            popup.hide();
            deleteInstalledModpack(meta);
        });

        HBox rowBox = new HBox(6, row, repairBtn, deleteBtn);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.setMaxWidth(Double.MAX_VALUE);
        return rowBox;
    }

    /**
     * Checks one installed pack against its own file manifest and downloads back anything that's
     * missing -- the same pass that runs automatically before a pack is launched (see onPlay), offered
     * on demand from the modpack menu so a pack can be verified without starting the game.
     */
    private void repairInstalledModpack(ModpackMeta meta) {
        Path instanceDir = ModpackMeta.instanceDirFor(gameFiles.root, meta.mcVersion, meta.loader);
        log("Checking \"" + meta.name + "\" for missing files...");
        Task<ModpackVerifier.Report> task = new Task<>() {
            @Override
            protected ModpackVerifier.Report call() {
                ModpackMeta fresh = ModpackMeta.read(instanceDir);
                return new ModpackVerifier().verifyClient(fresh == null ? meta : fresh, instanceDir, null);
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            ModpackVerifier.Report report = task.getValue();
            log("Modpack \"" + meta.name + "\": " + report.summary());
            if (!report.errors().isEmpty()) {
                String failed = "  " + String.join("\n  ", report.errors());
                log("Files that couldn't be restored:\n" + failed);
            }
            StringBuilder alert = new StringBuilder(report.summary());
            alert.append(report.clean()
                    ? "\n\nEvery file this pack asks for is in place."
                    : "\n\nThe missing ones were downloaded again into " + instanceDir + ".");
            if (!report.errors().isEmpty()) {
                alert.append("\n\nStill missing:\n")
                        .append(String.join("\n", report.errors().subList(0, Math.min(8, report.errors().size()))));
            }
            new Alert(Alert.AlertType.INFORMATION, alert.toString(), ButtonType.OK).showAndWait();
        }));
        task.setOnFailed(ev -> Platform.runLater(() ->
                log("Couldn't check \"" + meta.name + "\": " + task.getException())));
        new Thread(task, "modpack-verify").start();
    }

    /** Deletes an installed modpack's entire instance folder (modpack.json, mods, config, etc.).
     *  The instance's {@code saves/} is a link into the shared saves pool (see SharedSaves), never
     *  the worlds themselves, so this walk deletes the link but never touches shared world data. */
    private void deleteInstalledModpack(ModpackMeta meta) {
        try {
            Path instanceDir = ModpackMeta.instanceDirFor(gameFiles.root, meta.mcVersion, meta.loader);
            deleteFileTree(instanceDir);
            log("Deleted modpack \"" + meta.name + "\" and its mods.");
            refreshModpackButtonIcon(versionBox.getValue(), modLoaderBox.getValue());
        } catch (Exception ex) {
            log("Failed to delete modpack \"" + meta.name + "\": " + ex.getMessage());
        }
    }

    /** Recursively deletes a directory tree. Never follows symbolic links/junctions -- a linked
     *  child (e.g. an instance's saves/ pointing at the shared saves pool) has only the link itself
     *  removed, so files reachable solely through that link are left untouched. */
    private void deleteFileTree(Path root) throws java.io.IOException {
        if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = java.nio.file.Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.delete(p);
                } catch (java.io.IOException ignored) {
                    // Best-effort, same as elsewhere in this codebase -- leave anything that can't
                    // be removed (e.g. a file locked by a still-running game) rather than failing.
                }
            });
        }
    }

    /** File picker for a pack file (folder picks go through drag & drop), then the installer window. */
    private void chooseModpackFileAndInstall() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a modpack");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modpacks (.mrpack / .zip)", "*.mrpack", "*.zip"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File picked = chooser.showOpenDialog(stage);
        if (picked != null) openModpackInstaller(picked.toPath());
    }

    /**
     * The "Install Modpack" window: drop a pack (or browse / paste a Modrinth link), see exactly what
     * it is -- icon, name, Minecraft version, loader, file counts -- then install it into the matching
     * instance folder. Built like every other DeyLauncher window (borderless, themed, draggable,
     * resizable) so it feels native to the app rather than like an OS dialog.
     */
    private void openModpackInstaller(Path preselected) {
        Label dropZone = new Label("Drop a modpack anywhere in this window -- .mrpack, .zip, or an extracted instance folder");
        dropZone.setGraphic(icon(IconFactory.Icon.MODPACK, 22));
        dropZone.setGraphicTextGap(10);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setMaxWidth(Double.MAX_VALUE);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPrefHeight(92);
        dropZone.setWrapText(true);

        TextField urlField = new TextField();
        urlField.setPromptText("...or paste a Modrinth modpack link / slug");
        urlField.getStyleClass().add("input-field");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        Button fetchBtn = new Button();
        setButtonIcon(fetchBtn, IconFactory.Icon.DOWNLOAD, "Get");
        fetchBtn.getStyleClass().add("pill-button");
        Button browseBtn = new Button();
        setButtonIcon(browseBtn, IconFactory.Icon.FOLDER, "Browse...");
        browseBtn.getStyleClass().add("pill-button");
        HBox pickRow = new HBox(10, urlField, fetchBtn, browseBtn);
        pickRow.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label("Pick a pack above, drop one anywhere in this window, or paste a Modrinth link.");
        status.getStyleClass().add("notice-label");
        status.setWrapText(true);

        VBox preview = new VBox(12);
        preview.getStyleClass().add("mod-row");
        preview.setVisible(false);
        preview.setManaged(false);

        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("play-progress");
        bar.setVisible(false);
        bar.setManaged(false);

        Button installBtn = new Button();
        setButtonIcon(installBtn, IconFactory.Icon.DOWNLOAD, "INSTALL");
        installBtn.getStyleClass().add("settings-apply-button");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        installBtn.setDisable(true);

        ModpackInfo[] ready = new ModpackInfo[1];
        Stage[] winHolder = new Stage[1];

        VBox content = new VBox(14, dropZone, pickRow, status, preview, bar, installBtn);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("mods-dialog-content");

        Runnable clear = () -> {
            ready[0] = null;
            installBtn.setDisable(true);
            installBtn.getStyleClass().remove("settings-apply-button-ready");
            preview.getChildren().clear();
            preview.setVisible(false);
            preview.setManaged(false);
            bar.setVisible(false);
            bar.setManaged(false);
        };

        // Reading a pack never touches the UI thread, and never blocks the window: parse + icon
        // resolution happen in the background, then the preview is filled in on the FX thread.
        java.util.function.Consumer<Path> load = path -> {
            clear.run();
            status.setText("Reading " + (path.getFileName() == null ? path : path.getFileName()) + "...");
            Task<ModpackInfo> task = new Task<>() {
                @Override
                protected ModpackInfo call() throws Exception {
                    ModpackInfo info = ModpackReader.read(path);
                    Path icon = PackIcons.resolve(info, gameFiles.root); // cosmetic -- null is fine
                    return icon == null ? info : info.withIcon(icon);
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(
                    () -> showModpackPreview(ready, preview, status, installBtn, task.getValue())));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                Throwable ex = task.getException();
                status.setText("Couldn't read that pack: " + (ex == null ? "unknown error" : ex.getMessage()));
            }));
            new Thread(task, "modpack-read").start();
        };

        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a modpack");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Modpacks (.mrpack / .zip)", "*.mrpack", "*.zip"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            java.io.File picked = chooser.showOpenDialog(winHolder[0] != null ? winHolder[0] : stage);
            if (picked != null) load.accept(picked.toPath());
        });

        fetchBtn.setOnAction(e -> {
            String text = urlField.getText() == null ? "" : urlField.getText().trim();
            if (text.isBlank()) {
                status.setText("Paste a Modrinth modpack link or slug first, then press Get.");
                return;
            }
            status.setText("Fetching " + text + " from Modrinth...");
            Task<Path> fetch = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    return downloadModrinthPack(text);
                }
            };
            fetch.setOnSucceeded(ev -> load.accept(fetch.getValue()));
            fetch.setOnFailed(ev -> Platform.runLater(() -> {
                Throwable ex = fetch.getException();
                status.setText("Couldn't fetch that pack: " + (ex == null ? "unknown error" : ex.getMessage()));
            }));
            new Thread(fetch, "modpack-fetch").start();
        });
        urlField.setOnAction(ev -> fetchBtn.fire());

        installBtn.setOnAction(e -> {
            ModpackInfo info = ready[0];
            if (info == null) return;
            String mc = resolveModpackVersion(info);
            String loader = resolveModpackLoader(info);
            if (mc == null || mc.isBlank()) {
                status.setText("Pick a Minecraft version in the launcher first, then install this pack.");
                return;
            }
            Path instanceDir = ModpackMeta.instanceDirFor(gameFiles.root, mc, loader);
            installBtn.setDisable(true);
            installBtn.getStyleClass().remove("settings-apply-button-ready");
            bar.setVisible(true);
            bar.setManaged(true);
            bar.setProgress(0);
            status.setText(info.resolvesRemotely()
                    ? "Resolving " + info.name() + "'s files on CurseForge, then installing into Minecraft "
                      + mc + " (" + loader + ") -- its mods download themselves, nothing for you to fetch..."
                    : "Installing " + info.name() + " into Minecraft " + mc + " (" + loader + ") -- "
                      + info.downloads().size() + " file(s) to download, " + info.bundled().size()
                      + " bundled in the pack...");
            Task<ModpackInstaller.Result> task = new Task<>() {
                @Override
                protected ModpackInstaller.Result call() throws Exception {
                    // onProgress fires once per resolved/file (not per byte), so a runLater per step is
                    // cheap; the status sink reports the phases (resolving pack ids, then transferring).
                    return new ModpackInstaller().installForClient(info, mc, loader, instanceDir,
                            f -> Platform.runLater(() -> bar.setProgress(f)),
                            msg -> Platform.runLater(() -> status.setText(msg)));
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> finishModpackInstall(
                    task.getValue(), info, mc, loader, instanceDir, status, bar, installBtn)));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                Throwable ex = task.getException();
                bar.setVisible(false);
                bar.setManaged(false);
                installBtn.setDisable(false);
                status.setText("Install failed: " + (ex == null ? "unknown error" : ex.getMessage()));
                log("Modpack install failed: " + (ex == null ? "unknown error" : ex.getMessage()));
            }));
            new Thread(task, "modpack-install").start();
        });

        // Dropping a pack anywhere in this window loads it (the same "drop anywhere" feel as the Mods
        // window), and the whole content area highlights while a drag is over it.
        content.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        content.setOnDragEntered(e -> content.getStyleClass().add("drop-zone-active"));
        content.setOnDragExited(e -> content.getStyleClass().remove("drop-zone-active"));
        content.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            boolean handled = false;
            if (files != null) {
                for (var f : files) {
                    Path p = f.toPath();
                    if (ModpackFormat.installable(p)) {
                        load.accept(p);
                        handled = true;
                        break;
                    }
                }
            }
            content.getStyleClass().remove("drop-zone-active");
            if (!handled) {
                status.setText("That drop isn't a modpack -- expected a .mrpack, a pack .zip, or an instance folder.");
            }
            e.setDropCompleted(handled);
            e.consume();
        });

        Stage win = buildBorderlessStage("Install Modpack", content, stage, Modality.NONE,
                560, 500, 800, 700);
        winHolder[0] = win;
        win.centerOnScreen();
        win.show();
        win.toFront();
        if (preselected != null) load.accept(preselected);
    }

    /** Fills the installer's preview card from a parsed pack, and enables INSTALL only when the pack
     *  can actually be launched here (supported loader + a Minecraft version the launcher knows). */
    private void showModpackPreview(ModpackInfo[] ready, VBox preview, Label status, Button installBtn, ModpackInfo info) {
        ready[0] = info;
        preview.getChildren().clear();

        Node tile = modIconNode(info.iconPath(), 64);

        VBox text = new VBox(4);
        Label name = new Label(info.name()
                + (info.version() == null || info.version().isBlank() ? "" : "  " + info.version()));
        name.getStyleClass().add("mod-name");
        name.setWrapText(true);
        Label meta = new Label(info.format().displayName()
                + (info.knowsMinecraftVersion() ? "  ·  Minecraft " + info.mcVersion() : "  ·  no Minecraft version declared")
                + "  ·  " + (info.loaderName() == null ? "Vanilla" : info.loaderName())
                + (info.loaderVersion() == null || info.loaderVersion().isBlank() ? "" : " " + info.loaderVersion()));
        meta.getStyleClass().add("mod-filename");
        meta.setWrapText(true);
        Label counts = new Label(info.resolvesRemotely()
                ? "This pack's files live on CurseForge by id -- they're fetched for you during the "
                  + "install and dropped into the right mods folder automatically"
                  + (info.bundled().size() > 0 ? "  ·  " + info.bundled().size() + " bundled in the pack" : "")
                : info.downloads().size() + " file(s) to download  ·  "
                  + info.bundled().size() + " bundled in the pack"
                  + (info.unresolvedCount() > 0 ? "  ·  " + info.unresolvedCount() + " can't be fetched automatically" : ""));
        counts.getStyleClass().add("mod-filename");
        counts.setWrapText(true);
        text.getChildren().addAll(name, meta, counts);
        if (info.note() != null && !info.note().isBlank()) {
            Label note = new Label(info.note());
            note.getStyleClass().add("settings-hint-label");
            note.setWrapText(true);
            text.getChildren().add(note);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(14, tile, text);
        row.setAlignment(Pos.CENTER_LEFT);
        preview.getChildren().add(row);
        preview.setVisible(true);
        preview.setManaged(true);

        // Why this pack can't be installed, if it can't. A pack naming a Minecraft version the
        // launcher's manifest doesn't have yet is blocked too: it couldn't be launched afterwards.
        String problem = info.blockingProblem();
        if (problem == null && info.knowsMinecraftVersion() && allVersions.size() > 1
                && findVersionEntry(info.mcVersion()) == null) {
            problem = "Minecraft " + info.mcVersion() + " isn't in the launcher's version list, so this "
                    + "pack couldn't be launched after installing it.";
        }

        String mc = resolveModpackVersion(info);
        String loader = resolveModpackLoader(info);
        if (problem == null) {
            installBtn.setDisable(false);
            if (!installBtn.getStyleClass().contains("settings-apply-button-ready")) {
                installBtn.getStyleClass().add("settings-apply-button-ready");
            }
            status.setText(info.knowsMinecraftVersion()
                    ? "Ready to install into Minecraft " + mc + " (" + loader + ")."
                    : "This pack doesn't name a Minecraft version, so it installs into the version and "
                      + "loader currently selected in the launcher: " + mc + " (" + loader + ").");
        } else {
            installBtn.setDisable(true);
            installBtn.getStyleClass().remove("settings-apply-button-ready");
            status.setText(problem);
        }
    }

    /**
     * Reports a finished install, then points the launcher straight at the pack (VANILLA mode +
     * the pack's version/loader) so pressing PLAY starts it -- and logs every file that failed, so a
     * partial install is never silently passed off as a complete one.
     */
    private void finishModpackInstall(ModpackInstaller.Result result, ModpackInfo info, String mc,
                                      String loader, Path instanceDir, Label status, ProgressBar bar,
                                      Button installBtn) {
        bar.setProgress(1);
        installBtn.setDisable(false);
        status.setText(info.name() + " installed: " + result.summary()
                + (result.errors().isEmpty() ? "" : " (" + result.errors().size() + " file(s) failed -- see the log)"));

        // A modpack is a plain (VANILLA-mode) version + loader setup. DEY mode would add its own
        // curated Sodium/Iris/Fabric API on top, which is not what the pack author intended.
        if (deyMode) setMode(false);
        boolean selected = selectVersionAndLoader(mc, loader);
        refreshModpackButtonIcon(mc, loader);
        log("Modpack \"" + info.name() + "\" installed into " + instanceDir + " -- " + result.summary());
        if (!selected) {
            log("Minecraft " + mc + " isn't selectable in the Version dropdown yet -- pick it there once it appears.");
        }
        for (String warning : result.warnings()) {
            log("Modpack note: " + warning);
        }
        if (!result.errors().isEmpty()) {
            StringBuilder failed = new StringBuilder();
            int shown = 0;
            for (String err : result.errors()) {
                if (shown++ >= 8) {
                    failed.append("  ...and ").append(result.errors().size() - 8).append(" more\n");
                    break;
                }
                failed.append("  ").append(err).append('\n');
            }
            log("Modpack files that couldn't be installed:\n" + failed);
        }

        StringBuilder alert = new StringBuilder();
        alert.append(info.name()).append(" is installed into Minecraft ").append(mc);
        if (loader != null && !loader.isBlank()) alert.append(" (").append(loader).append(")");
        alert.append(".\n\n").append(result.summary()).append('\n');
        if (info.note() != null && !info.note().isBlank()) alert.append('\n').append(info.note());
        if (!result.errors().isEmpty()) {
            alert.append("\n\nFile(s) the pack wants but couldn't be installed:\n")
                    .append(String.join("\n", result.errors().subList(0, Math.min(8, result.errors().size()))));
        }
        if (result.ok()) {
            alert.append("\n\nPress PLAY to start the pack. Anything that goes missing later is checked and "
                    + "downloaded again automatically before the game starts.");
        } else {
            alert.append("\n\nThat install is incomplete. Press PLAY anyway: the launcher re-checks the pack "
                    + "and tries to fetch every missing file again before launching, so a flaky download "
                    + "usually fixes itself on the next run.");
        }
        new Alert(Alert.AlertType.INFORMATION, alert.toString(), ButtonType.OK).showAndWait();
    }

    /** The Minecraft version a pack installs into: its own when it declares one, otherwise whatever is
     *  selected in the launcher (the useful behaviour for a pack with no metadata of its own). */
    private String resolveModpackVersion(ModpackInfo info) {
        if (info.knowsMinecraftVersion()) return info.mcVersion();
        return versionBox.getValue();
    }

    /** The loader a pack installs into: its own when it declares one, otherwise the launcher's own
     *  current LOADER selection. */
    private String resolveModpackLoader(ModpackInfo info) {
        if (info.loaderDeclared()) return info.launcherLoader();
        String selected = modLoaderBox.getValue();
        return selected == null || selected.isBlank() ? "Vanilla" : selected;
    }

    private VersionManifest.VersionEntry findVersionEntry(String id) {
        if (id == null) return null;
        for (VersionManifest.VersionEntry v : allVersions) {
            if (v.id().equals(id)) return v;
        }
        return null;
    }

    /**
     * Selects a Minecraft version + loader in the launcher's controls -- used after installing a pack
     * and when clicking one in the modpack menu. Picks the version tile whose filter actually covers
     * that version (falling back to the "All Versions" tile), then returns whether the version really
     * ended up selected (it won't be there at all if the manifest hasn't loaded / doesn't have it).
     */
    private boolean selectVersionAndLoader(String mcVersion, String loader) {
        if (mcVersion == null || mcVersion.isBlank()) return false;
        VersionPreset[] presets = deyMode ? DEY_PRESETS : VANILLA_PRESETS;
        int chosen = -1, allVersionsTile = -1;
        for (int i = 0; i < presets.length && i < versionTileList.getChildren().size(); i++) {
            if (presets[i].kind() == FilterKind.ALL) allVersionsTile = i;
            if (presetCouldMatch(presets[i], mcVersion)) {
                chosen = i;
                break;
            }
        }
        if (chosen < 0) chosen = allVersionsTile;
        if (chosen < 0 || !(versionTileList.getChildren().get(chosen) instanceof Button tile)) return false;
        selectVersionTile(presets[chosen], tile, mcVersion);
        if (loader != null && !loader.isBlank() && modLoaderBox.getItems().contains(loader)) {
            modLoaderBox.setValue(loader);
        }
        return mcVersion.equals(versionBox.getValue());
    }

    /**
     * Shows the ACTIVE pack's own icon on the modpack button (falling back to the generic box glyph),
     * so the launcher visibly reflects the pack you're about to play -- icon compatibility in the one
     * place you always see, right next to the DEY / VANILLA switch.
     */
    private void refreshModpackButtonIcon(String mcVersion, String loader) {
        if (modpackBtn == null) return;
        Path icon = null;
        if (mcVersion != null && !mcVersion.isBlank()) {
            ModpackMeta meta = ModpackMeta.read(ModpackMeta.instanceDirFor(gameFiles.root, mcVersion, loader));
            if (meta != null && meta.iconPath != null && !meta.iconPath.isBlank()) {
                Path candidate = Path.of(meta.iconPath);
                if (Files.exists(candidate)) icon = candidate;
            }
        }
        if (icon != null) {
            try {
                ImageView iv = new ImageView(new Image(icon.toUri().toString()));
                iv.setFitWidth(24);
                iv.setFitHeight(24);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                modpackBtn.setGraphic(iv);
                modpackBtn.setText("");
                modpackBtn.setGraphicTextGap(0);
                return;
            } catch (Exception ignored) {
                // Unreadable icon -- fall through to the generic glyph.
            }
        }
        setButtonIconOnly(modpackBtn, IconFactory.Icon.MODPACK);
    }

    /** Downloads a Modrinth modpack's .mrpack from a pasted link/slug, returning where it landed. */
    private Path downloadModrinthPack(String urlOrSlug) throws Exception {
        String slug = urlOrSlug.trim();
        int marker = slug.indexOf("modrinth.com/modpack/");
        if (marker >= 0) {
            slug = slug.substring(marker + "modrinth.com/modpack/".length());
        } else if (slug.contains("/")) {
            slug = slug.substring(slug.lastIndexOf('/') + 1);
        }
        slug = slug.split("[?#]")[0].trim();
        if (slug.isBlank()) throw new IOException("Paste a Modrinth modpack link or slug first.");

        ModrinthClient client = new ModrinthClient();
        var versions = client.versions(slug); // newest first, as Modrinth returns them
        if (versions.isEmpty()) throw new IOException("Modrinth has no versions for \"" + slug + "\".");
        Path dir = gameFiles.root.resolve("modpacks").resolve("downloads");
        for (var version : versions) {
            for (var file : version.files()) {
                if (file.filename() != null && file.filename().toLowerCase().endsWith(".mrpack")) {
                    return client.download(file.url(), file.filename(), dir);
                }
            }
        }
        throw new IOException("That Modrinth project has no .mrpack file to download.");
    }

    /**
     * Lets a modpack be dropped anywhere in the launcher window: a .mrpack / pack .zip / extracted
     * instance folder opens the installer already pointed at it. Anything else is reported in the log
     * rather than silently ignored.
     */
    private void installModpackDropTarget(Node node) {
        node.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        node.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            boolean handled = false;
            if (files != null) {
                for (var f : files) {
                    Path p = f.toPath();
                    if (ModpackFormat.installable(p)) {
                        openModpackInstaller(p);
                        handled = true;
                        break;
                    }
                }
                if (!handled) {
                    log("That drop isn't a modpack -- expected a .mrpack, a pack .zip, or an instance folder.");
                }
            }
            e.setDropCompleted(handled);
            e.consume();
        });
    }

    /** The window a shell key's controls should parent their dialogs to, falling back to the launcher. */
    private Stage shellWindowOwner(String key) {
        Stage w = shellWindows.get(key);
        return (w != null && w.isShowing()) ? w : stage;
    }

    // ---- Mods dialog: drag-and-drop add, enable/disable toggle, delete ----
    private java.nio.file.Path currentInstanceDir() {
        var files = new GameFiles();
        String versionId = versionBox.getValue() != null ? versionBox.getValue() : "1.21.1";
        String loader = modLoaderBox.getValue();
        String suffix = (loader == null || loader.equals("Vanilla")) ? "" : "-" + loader.toLowerCase();
        return files.root.resolve("instances").resolve(versionId + suffix);
    }

    private void openModsDialog() {
        ModsManager mods = new ModsManager(currentInstanceDir());
        String windowTitle = "Mods -- " + versionBox.getValue() + " (" + modLoaderBox.getValue() + ")";
        String mcVersion = versionBox.getValue();
        String modLoader = modLoaderBox.getValue();

        VBox rowsBox = new VBox(10);
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mods-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label dropZone = new Label(
            "Drag & drop mod .jar files anywhere in this window, or use Add Mods below"
        );
        dropZone.setGraphic(icon(IconFactory.Icon.DOWNLOAD, 20));
        dropZone.setGraphicTextGap(8);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setMaxWidth(Double.MAX_VALUE);
        dropZone.setAlignment(Pos.CENTER);

        Button addBtn = new Button("+  Add Mods");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setMaxWidth(Double.MAX_VALUE);

        // Shown only when at least one installed (non-bundled) mod is incompatible with the
        // currently selected Minecraft version -- see refreshFixStatus(). Clicking it converts the
        // mods to a version that works on this MC version, or removes them if none exists on Modrinth.
        Button fixBtn = new Button();
        setButtonIcon(fixBtn, IconFactory.Icon.TOOLS, "Fix Mods");
        fixBtn.getStyleClass().addAll("pill-button", "fix-button");
        fixBtn.setMaxWidth(Double.MAX_VALUE);
        fixBtn.setVisible(false);
        fixBtn.setManaged(false);

        HBox buttonRow = new HBox(10, addBtn, fixBtn);
        HBox.setHgrow(addBtn, Priority.ALWAYS);
        HBox.setHgrow(fixBtn, Priority.ALWAYS);
        buttonRow.setAlignment(Pos.CENTER);

        Label fixStatus = new Label();
        fixStatus.getStyleClass().add("notice-label");
        fixStatus.setWrapText(true);
        fixStatus.setMaxWidth(Double.MAX_VALUE);
        fixStatus.setVisible(false);
        fixStatus.setManaged(false);

        // ---- Bulk selection: a "Select All" checkbox plus a per-row checkbox lets several mods
        // be picked at once, revealing a toolbar to delete / enable / disable / fix / update all of
        // them together instead of one at a time. Locked (bundled) mods are never selectable, same
        // as they're excluded from the per-row enable/delete controls above. ----
        java.util.Set<String> selectedFiles = new java.util.LinkedHashSet<>();
        java.util.Set<String> problemFiles = new java.util.LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        List<String>[] selectableFilesHolder = new List[]{ List.<String>of() };
        Runnable[] updateSelectionUiHolder = new Runnable[1];

        CheckBox selectAllBox = new CheckBox("Select All");
        selectAllBox.getStyleClass().add("mod-checkbox");
        selectAllBox.setVisible(false);
        selectAllBox.setManaged(false);

        Label selectedCountLabel = new Label();
        selectedCountLabel.getStyleClass().add("notice-label");

        Region selectionSpacer = new Region();
        HBox.setHgrow(selectionSpacer, Priority.ALWAYS);

        HBox selectionHeader = new HBox(12, selectAllBox, selectedCountLabel, selectionSpacer);
        selectionHeader.setAlignment(Pos.CENTER_LEFT);
        selectionHeader.setVisible(false);
        selectionHeader.setManaged(false);

        Button deleteSelectedBtn = new Button();
        setButtonIcon(deleteSelectedBtn, IconFactory.Icon.TRASH, "Delete Selected");
        deleteSelectedBtn.getStyleClass().add("pill-button");

        Button applySelectedBtn = new Button("Apply Selected");
        applySelectedBtn.getStyleClass().add("pill-button");
        applySelectedBtn.setTooltip(new Tooltip("Enable every selected mod"));

        Button unapplySelectedBtn = new Button("Unapply Selected");
        unapplySelectedBtn.getStyleClass().add("pill-button");
        unapplySelectedBtn.setTooltip(new Tooltip("Disable every selected mod"));

        // Only shown while at least one SELECTED mod is one of the incompatible ones tracked in
        // problemFiles (populated by refreshFixStatus below) -- "only if they have a problem".
        Button fixSelectedBtn = new Button();
        setButtonIcon(fixSelectedBtn, IconFactory.Icon.TOOLS, "Fix Selected");
        fixSelectedBtn.getStyleClass().addAll("pill-button", "fix-button");
        fixSelectedBtn.setVisible(false);
        fixSelectedBtn.setManaged(false);

        Button updateSelectedBtn = new Button("Update Selected");
        updateSelectedBtn.getStyleClass().add("pill-button");
        updateSelectedBtn.setTooltip(new Tooltip("Download the newest compatible version of every selected mod"));

        HBox bulkActionsBar = new HBox(8, deleteSelectedBtn, applySelectedBtn, unapplySelectedBtn,
                fixSelectedBtn, updateSelectedBtn);
        bulkActionsBar.setAlignment(Pos.CENTER_LEFT);
        bulkActionsBar.setVisible(false);
        bulkActionsBar.setManaged(false);

        VBox selectionBox = new VBox(8, selectionHeader, bulkActionsBar);

        Runnable updateSelectionUi = () -> {
            boolean any = !selectedFiles.isEmpty();
            bulkActionsBar.setVisible(any);
            bulkActionsBar.setManaged(any);
            selectedCountLabel.setText(any ? selectedFiles.size() + " selected" : "");
            boolean canFix = selectedFiles.stream().anyMatch(problemFiles::contains);
            fixSelectedBtn.setVisible(canFix);
            fixSelectedBtn.setManaged(canFix);

            List<String> selectable = selectableFilesHolder[0];
            selectAllBox.setVisible(!selectable.isEmpty());
            selectAllBox.setManaged(!selectable.isEmpty());
            selectionHeader.setVisible(!selectable.isEmpty());
            selectionHeader.setManaged(!selectable.isEmpty());
            boolean allSelected = !selectable.isEmpty() && selectedFiles.containsAll(selectable);
            selectAllBox.setSelected(allSelected); // pure state sync -- listener below re-fires safely either way
            selectAllBox.setIndeterminate(any && !allSelected);
        };
        updateSelectionUiHolder[0] = updateSelectionUi;

        selectAllBox.setOnAction(e -> {
            if (selectAllBox.isSelected()) selectedFiles.addAll(selectableFilesHolder[0]);
            else selectedFiles.clear();
            updateSelectionUiHolder[0].run();
        });

        Runnable[] refreshHolder = new Runnable[1];
        Runnable refresh = () -> {
            rowsBox.getChildren().clear();
            try {
                var list = mods.list();
                java.util.Set<String> lockedFamiliesShown = new java.util.HashSet<>();
                List<String> selectable = new ArrayList<>();
                for (var m : list) {
                    // A real sodium-*.jar/embeddium-*.jar or fabric-api-*.jar (auto-installed
                    // for DEY, see SodiumInstaller/FabricApiInstaller) is locked from the
                    // checkbox/delete controls, same as it is for the actual game -- everything
                    // else behaves like a normal mod. Each is dedup'd independently: a stray
                    // second copy of one doesn't affect the other.
                    String fn = m.fileName().toLowerCase();
                    String family = null;
                    if (deyMode) {
                        if (fn.startsWith("sodium-") || fn.startsWith("embeddium-")) family = "perf";
                        else if (fn.startsWith("iris-")) family = "iris";
                        else if (fn.startsWith("fabric-api-")) family = "fabric-api";
                        else if (fn.startsWith("deycapes-")) family = "deycapes";
                    }
                    if (family != null) {
                        if (!lockedFamiliesShown.add(family)) {
                            // A stray second jar for the same family (e.g. left over from before
                            // an update, or a race between two installs) -- clean it up instead
                            // of showing it twice for the same thing.
                            try {
                                mods.delete(m.fileName());
                            } catch (Exception ex) {
                                log("Failed to remove duplicate " + m.fileName() + ": " + ex.getMessage());
                            }
                            continue;
                        }
                    }
                    boolean locked = family != null;
                    if (!locked) selectable.add(m.fileName());
                    rowsBox.getChildren().add(buildModRow(m, mods, refreshHolder[0], locked,
                            selectedFiles, updateSelectionUiHolder[0]));
                }
                selectableFilesHolder[0] = selectable;
                selectedFiles.retainAll(selectable); // a selected mod that vanished (deleted/renamed) drops out
                // Best-effort: attach Modrinth icons (+ click-through pages) to installed mods by
                // matching their display names, in the background so the list never blocks.
                if (!list.isEmpty()) enrichModIconsAsync(mods, list, refreshHolder[0]);
                if (list.isEmpty()) {
                    Label empty = new Label(deyMode
                            ? "No mods yet -- Sodium, Iris, Fabric API, and DeyCapes install automatically the first time you hit Play."
                            : "No mods yet -- drag some in above.");
                    empty.getStyleClass().add("notice-label");
                    rowsBox.getChildren().add(empty);
                }
                // Reveal/hide the Fix button based on whether any installed mod is incompatible
                // with the currently selected Minecraft version. Runs in the background.
                refreshFixStatus(mods, list, fixBtn, fixStatus, mcVersion, problemFiles, updateSelectionUiHolder[0]);
                updateSelectionUiHolder[0].run();
            } catch (Exception ex) {
                log("Failed to list mods: " + ex.getMessage());
            }
        };
        refreshHolder[0] = refresh;

        // Best-effort: try to have the bundled mods ready by the time the dialog opens too, not
        // just on Play, so the list already reflects reality instead of only updating after a
        // launch. Sodium/Iris are resolved as a compatible pair (see ModPairResolver) rather than
        // "newest of each", which is what crashes the DEY 26.2 build. Fabric API: Fabric only.
        if (deyMode && mcVersion != null && !mcVersion.isBlank()) {
            Task<Void> installTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    new ModPairResolver().ensureDeyMods(modLoader, mcVersion, mods.modsDir());
                    return null;
                }
            };
            installTask.setOnSucceeded(e -> refresh.run());
            installTask.setOnFailed(e -> refresh.run());
            new Thread(installTask, "dey-mod-installer").start();
        }

        addBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select mod jars");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mod jars", "*.jar"));
            List<java.io.File> picked = chooser.showOpenMultipleDialog(modsWindowOwner.get());
            if (picked != null) {
                for (var f : picked) {
                    try {
                        mods.addMod(f.toPath());
                    } catch (Exception ex) {
                        log("Failed to add " + f.getName() + ": " + ex.getMessage());
                    }
                }
                refresh.run();
            }
        });

        fixBtn.setOnAction(e -> runFixIncompatibleMods(mods, fixBtn, fixStatus, refresh, mcVersion));

        deleteSelectedBtn.setOnAction(e -> {
            if (selectedFiles.isEmpty()) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + selectedFiles.size() + " selected mod(s)? This can't be undone.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Delete selected mods");
            var pick = confirm.showAndWait();
            if (pick.isEmpty() || pick.get() != ButtonType.OK) return;
            for (String fileName : new ArrayList<>(selectedFiles)) {
                try {
                    mods.delete(fileName);
                } catch (Exception ex) {
                    log("Failed to delete " + fileName + ": " + ex.getMessage());
                }
            }
            selectedFiles.clear();
            refresh.run();
        });

        applySelectedBtn.setOnAction(e -> {
            for (String fileName : selectedFiles) {
                try {
                    mods.setEnabled(fileName, true);
                } catch (Exception ex) {
                    log("Failed to apply " + fileName + ": " + ex.getMessage());
                }
            }
            refresh.run();
        });

        unapplySelectedBtn.setOnAction(e -> {
            for (String fileName : selectedFiles) {
                try {
                    mods.setEnabled(fileName, false);
                } catch (Exception ex) {
                    log("Failed to unapply " + fileName + ": " + ex.getMessage());
                }
            }
            refresh.run();
        });

        fixSelectedBtn.setOnAction(e -> runFixOrUpdateSelected(
                mods, selectedFiles, problemFiles, mcVersion, true, refresh, fixSelectedBtn));
        updateSelectedBtn.setOnAction(e -> runFixOrUpdateSelected(
                mods, selectedFiles, problemFiles, mcVersion, false, refresh, updateSelectedBtn));

        VBox content = new VBox(12, dropZone, fixStatus, selectionBox, scroll, buttonRow);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("mods-dialog-content");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Drag & drop works across the ENTIRE window, not just the drop-zone label.
        installModsDropTarget(content, mods, refresh);
        installModsDropTarget(rowsBox, mods, refresh);
        installModsDropTarget(scroll, mods, refresh);

        // Borderless, resizable Mods window sized proportionally to the display.
        Stage win = buildModsWindowStage(windowTitle, content);
        modsWindowOwner.set(win);
        refresh.run();
        win.showAndWait();
    }

    /**
     * "Option Kits": named, iconable snapshots of an instance's options.txt (video settings,
     * keybinds, language, etc.) that can be saved, re-applied to ANY version/loader, downloaded as
     * a standalone .json, or dragged back in. Kits live in the shared GitHub-backed store (see
     * OptionsKitsRepository) under the active account's uuid, same backend Friends/DeyCapes use --
     * so they follow the account across machines, not just this install. Free (offline) accounts
     * get 1 slot; real (online) accounts get 5, matching OptionsKitsRepository's limits.
     */
    private void openOptionsKitsDialog() {
        if (optionsKitsRepository == null) {
            log("Option Kits needs GitHub sync configured for this build -- see GITHUB_SETUP.md.");
            return;
        }
        PlayerIdentity activeIdentity = identityStore.getActive();
        if (activeIdentity == null) {
            log("Set up an account first (Account button) before saving Option Kits.");
            return;
        }
        final String uuid = activeIdentity.uuid;
        final int limit = activeIdentity.accountType == AccountType.ONLINE
                ? com.deylauncher.optionskits.OptionsKitsRepository.ONLINE_ACCOUNT_LIMIT
                : com.deylauncher.optionskits.OptionsKitsRepository.OFFLINE_ACCOUNT_LIMIT;

        VBox rowsBox = new VBox(10);
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mods-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label dropZone = new Label(
                "Drag & drop a kit .json file anywhere in this window to import it, or save your "
                        + "current options below");
        dropZone.setGraphic(icon(IconFactory.Icon.DOWNLOAD, 20));
        dropZone.setGraphicTextGap(8);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setMaxWidth(Double.MAX_VALUE);
        dropZone.setAlignment(Pos.CENTER);

        Label quotaLabel = new Label();
        quotaLabel.getStyleClass().add("notice-label");

        Button saveCurrentBtn = new Button("+  Save Current Options As Kit");
        saveCurrentBtn.getStyleClass().add("pill-button");
        saveCurrentBtn.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("notice-label");
        statusLabel.setWrapText(true);

        Runnable[] refreshHolder = new Runnable[1];
        Runnable refresh = () -> {
            rowsBox.getChildren().clear();
            statusLabel.setText("Loading...");
            Task<List<com.deylauncher.optionskits.OptionsKit>> loadTask = new Task<>() {
                @Override protected List<com.deylauncher.optionskits.OptionsKit> call() throws Exception {
                    return optionsKitsRepository.listFor(uuid);
                }
            };
            loadTask.setOnSucceeded(e -> {
                statusLabel.setText("");
                List<com.deylauncher.optionskits.OptionsKit> kits = loadTask.getValue();
                quotaLabel.setText(kits.size() + " / " + limit + " kits used"
                        + (activeIdentity.accountType == AccountType.OFFLINE
                        ? " (offline accounts get 1 -- sign in with a real account for 5)" : ""));
                saveCurrentBtn.setDisable(kits.size() >= limit);
                if (kits.isEmpty()) {
                    Label empty = new Label("No saved kits yet -- save your current options above, "
                            + "or drag a kit .json file into this window.");
                    empty.getStyleClass().add("notice-label");
                    rowsBox.getChildren().add(empty);
                } else {
                    for (var kit : kits) rowsBox.getChildren().add(buildOptionsKitRow(kit, uuid, refreshHolder[0]));
                }
            });
            loadTask.setOnFailed(e -> statusLabel.setText("Couldn't load kits: "
                    + loadTask.getException().getMessage()));
            new Thread(loadTask, "options-kits-load").start();
        };
        refreshHolder[0] = refresh;

        saveCurrentBtn.setOnAction(e -> promptSaveCurrentOptionsAsKit(uuid, limit, refresh, statusLabel));

        VBox content = new VBox(12, dropZone, quotaLabel, saveCurrentBtn, statusLabel, scroll);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("mods-dialog-content");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        installOptionsKitDropTarget(content, uuid, limit, refresh, statusLabel);
        installOptionsKitDropTarget(rowsBox, uuid, limit, refresh, statusLabel);
        installOptionsKitDropTarget(scroll, uuid, limit, refresh, statusLabel);

        Stage win = buildModsWindowStage("Option Kits", content);
        modsWindowOwner.set(win);
        refresh.run();
        win.showAndWait();
    }

    /** One saved kit's row: icon, name (+ version label), Apply / Rename / Icon / Download / Delete. */
    private HBox buildOptionsKitRow(com.deylauncher.optionskits.OptionsKit kit, String uuid, Runnable refresh) {
        StackPane iconTile = serverIconTile(kit.name == null ? "Kit" : kit.name, null, 40);
        if (kit.iconBase64 != null && !kit.iconBase64.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(kit.iconBase64);
                Image img = new Image(new java.io.ByteArrayInputStream(bytes), 40, 40, true, true);
                showServerIcon(iconTile, img);
            } catch (Exception ignored) {
            }
        }

        Label name = new Label(kit.name == null ? "Untitled Kit" : kit.name);
        name.getStyleClass().add("mod-name");
        Label sub = new Label(kit.crossVersion || kit.minecraftVersion == null
                ? "Cross-version" : kit.minecraftVersion);
        sub.getStyleClass().add("notice-label");
        VBox textBox = new VBox(2, name, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button applyBtn = new Button("Apply");
        applyBtn.getStyleClass().add("pill-button");
        applyBtn.setOnAction(e -> applyOptionsKit(kit));

        Button renameBtn = new Button("Rename");
        renameBtn.getStyleClass().add("pill-button");
        renameBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog(kit.name == null ? "" : kit.name);
            dialog.setHeaderText("Rename kit");
            dialog.setContentText("New name:");
            dialog.showAndWait().ifPresent(newName -> {
                if (newName == null || newName.isBlank()) return;
                mutateOptionsKit(uuid, kit.id, k -> k.name = newName.trim(), refresh);
            });
        });

        Button iconBtn = new Button("Icon");
        iconBtn.getStyleClass().add("pill-button");
        iconBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose a kit icon");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Images", "*.png", "*.jpg", "*.jpeg"));
            java.io.File picked = chooser.showOpenDialog(modsWindowOwner.get());
            if (picked == null) return;
            try {
                // Same square-crop-and-scale approach as writeServerIcon64, just kept in memory
                // (base64) instead of written to disk -- avoids pulling in the javafx.swing module
                // (not on this project's javafx { modules = ... } list) just to resize an icon.
                java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(picked);
                if (src == null) throw new java.io.IOException("Unrecognized image format");
                int side = Math.max(1, Math.min(src.getWidth(), src.getHeight()));
                int sx = (src.getWidth() - side) / 2;
                int sy = (src.getHeight() - side) / 2;
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                        64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, 64, 64, sx, sy, sx + side, sy + side, null);
                g.dispose();
                var out = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(scaled, "png", out);
                String b64 = Base64.getEncoder().encodeToString(out.toByteArray());
                mutateOptionsKit(uuid, kit.id, k -> k.iconBase64 = b64, refresh);
            } catch (Exception ex) {
                log("Couldn't set kit icon: " + ex.getMessage());
            }
        });

        Button downloadBtn = new Button("Download");
        downloadBtn.getStyleClass().add("pill-button");
        downloadBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save kit as...");
            String safeName = (kit.name == null ? "kit" : kit.name).replaceAll("[^a-zA-Z0-9_-]", "_");
            chooser.setInitialFileName(safeName + ".json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Option Kit", "*.json"));
            java.io.File target = chooser.showSaveDialog(modsWindowOwner.get());
            if (target == null) return;
            try {
                Files.writeString(target.toPath(), new Gson().toJson(kit));
            } catch (Exception ex) {
                log("Couldn't save kit file: " + ex.getMessage());
            }
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.getStyleClass().add("pill-button");
        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete \"" + kit.name + "\"? This can't be undone.", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.YES) return;
                Task<Void> task = new Task<>() {
                    @Override protected Void call() throws Exception {
                        optionsKitsRepository.sync(uuid, "Delete option kit " + kit.name, list -> {
                            list.removeIf(k -> k.id.equals(kit.id));
                            return list;
                        });
                        return null;
                    }
                };
                task.setOnSucceeded(ev -> refresh.run());
                task.setOnFailed(ev -> log("Couldn't delete kit: " + task.getException().getMessage()));
                new Thread(task, "options-kit-delete").start();
            });
        });

        HBox row = new HBox(10, iconTile, textBox, spacer, applyBtn, renameBtn, iconBtn, downloadBtn, deleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        return row;
    }

    /** Read-modify-write helper for a single field change on one kit, in the background. */
    private void mutateOptionsKit(String uuid, String kitId,
                                   java.util.function.Consumer<com.deylauncher.optionskits.OptionsKit> mutator,
                                   Runnable refresh) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                optionsKitsRepository.sync(uuid, "Update option kit " + kitId, list -> {
                    for (var k : list) {
                        if (k.id.equals(kitId)) {
                            mutator.accept(k);
                            k.updatedAt = System.currentTimeMillis();
                        }
                    }
                    return list;
                });
                return null;
            }
        };
        task.setOnSucceeded(e -> refresh.run());
        task.setOnFailed(e -> log("Couldn't update kit: " + task.getException().getMessage()));
        new Thread(task, "options-kit-update").start();
    }

    /** Writes a kit's saved options.txt over the CURRENTLY SELECTED version/loader's instance --
     *  cross-version by design (see OptionsKit's class doc): options.txt keys are stable enough
     *  across releases that restricting this would just make the feature less useful. */
    private void applyOptionsKit(com.deylauncher.optionskits.OptionsKit kit) {
        try {
            byte[] raw = Base64.getDecoder().decode(kit.optionsTxtBase64);
            java.nio.file.Path instanceDir = currentInstanceDir();
            Files.createDirectories(instanceDir);
            Files.write(instanceDir.resolve("options.txt"), raw);
            log("Applied \"" + kit.name + "\" to " + versionBox.getValue() + " (" + modLoaderBox.getValue()
                    + "). Restart the game if it's already running for it to take effect.");
        } catch (Exception ex) {
            log("Couldn't apply kit: " + ex.getMessage());
        }
    }

    /** Prompts for a name + cross-version choice, reads the current instance's options.txt, and
     *  saves it as a new kit -- enforcing the account-tier slot limit up front with a clear message
     *  rather than letting the sync fail unexplained. */
    private void promptSaveCurrentOptionsAsKit(String uuid, int limit, Runnable refresh, Label statusLabel) {
        java.nio.file.Path optionsFile = currentInstanceDir().resolve("options.txt");
        if (!Files.exists(optionsFile)) {
            statusLabel.setText("No options.txt yet for " + versionBox.getValue() + " ("
                    + modLoaderBox.getValue() + ") -- launch it at least once first so it has settings to save.");
            return;
        }
        TextInputDialog nameDialog = new TextInputDialog(versionBox.getValue() + " settings");
        nameDialog.setHeaderText("Save current options as a kit");
        nameDialog.setContentText("Kit name:");
        nameDialog.showAndWait().ifPresent(name -> {
            if (name == null || name.isBlank()) return;
            Alert crossVersionAsk = new Alert(Alert.AlertType.CONFIRMATION,
                    "Should this kit be labeled cross-version (works the same everywhere), or tagged "
                            + "to " + versionBox.getValue() + " specifically? Either way it can be applied to "
                            + "any version/loader later -- this only changes the label shown in the list.",
                    ButtonType.YES, ButtonType.NO);
            crossVersionAsk.setHeaderText("Cross-version kit?");
            ((Button) crossVersionAsk.getDialogPane().lookupButton(ButtonType.YES)).setText("Cross-version");
            ((Button) crossVersionAsk.getDialogPane().lookupButton(ButtonType.NO)).setText("Tag to this version");
            crossVersionAsk.showAndWait().ifPresent(bt -> {
                boolean crossVersion = bt == ButtonType.YES;
                try {
                    byte[] raw = Files.readAllBytes(optionsFile);
                    String b64 = Base64.getEncoder().encodeToString(raw);
                    String versionLabel = versionBox.getValue() + "-" + modLoaderBox.getValue();
                    saveNewOptionsKit(uuid, limit, name.trim(), versionLabel, crossVersion, b64, refresh, statusLabel);
                } catch (Exception ex) {
                    statusLabel.setText("Couldn't read options.txt: " + ex.getMessage());
                }
            });
        });
    }

    private void saveNewOptionsKit(String uuid, int limit, String name, String versionLabel,
                                    boolean crossVersion, String optionsTxtBase64, Runnable refresh,
                                    Label statusLabel) {
        statusLabel.setText("Saving...");
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                optionsKitsRepository.sync(uuid, "Save option kit " + name, list -> {
                    if (list.size() >= limit) {
                        throw new RuntimeException("You're at your " + limit + "-kit limit for this account -- "
                                + "delete one first, or sign in with a real account for more slots.");
                    }
                    list.add(new com.deylauncher.optionskits.OptionsKit(
                            name, null, versionLabel, crossVersion, optionsTxtBase64));
                    return list;
                });
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText("");
            refresh.run();
        });
        task.setOnFailed(e -> statusLabel.setText(task.getException().getMessage()));
        new Thread(task, "options-kit-save").start();
    }

    /** Drag & drop a kit .json anywhere in the Option Kits window to import it (respecting the
     *  account's slot limit, same as saving a new one). */
    private void installOptionsKitDropTarget(Node target, String uuid, int limit, Runnable refresh,
                                              Label statusLabel) {
        target.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            e.consume();
        });
        target.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            boolean handled = false;
            if (files != null) {
                for (var f : files) {
                    if (!f.getName().toLowerCase().endsWith(".json")) continue;
                    handled = true;
                    try {
                        String json = Files.readString(f.toPath());
                        com.deylauncher.optionskits.OptionsKit imported =
                                new Gson().fromJson(json, com.deylauncher.optionskits.OptionsKit.class);
                        if (imported == null || imported.optionsTxtBase64 == null) {
                            statusLabel.setText("That file doesn't look like a kit export.");
                            continue;
                        }
                        imported.id = java.util.UUID.randomUUID().toString(); // never collide with an existing id
                        statusLabel.setText("Importing...");
                        Task<Void> task = new Task<>() {
                            @Override protected Void call() throws Exception {
                                optionsKitsRepository.sync(uuid, "Import option kit " + imported.name, list -> {
                                    if (list.size() >= limit) {
                                        throw new RuntimeException("You're at your " + limit
                                                + "-kit limit for this account -- delete one first.");
                                    }
                                    list.add(imported);
                                    return list;
                                });
                                return null;
                            }
                        };
                        task.setOnSucceeded(ev -> {
                            statusLabel.setText("");
                            refresh.run();
                        });
                        task.setOnFailed(ev -> statusLabel.setText(task.getException().getMessage()));
                        new Thread(task, "options-kit-import").start();
                    } catch (Exception ex) {
                        statusLabel.setText("Couldn't import that file: " + ex.getMessage());
                    }
                }
            }
            e.setDropCompleted(handled);
            e.consume();
        });
    }

/** Lives only for the (blocking) openModsDialog() call, so the file chooser can center on it. */
    private final java.util.concurrent.atomic.AtomicReference<javafx.stage.Window> modsWindowOwner =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Attaches the "import mod .jar files" drag & drop behaviour to an arbitrary node, so dropping a
     * jar anywhere in the Mods window adds it, instead of only working inside the small drop-zone label.
     */
    private void installModsDropTarget(Node node, ModsManager mods, Runnable refresh) {
        node.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        node.setOnDragEntered(e -> node.getStyleClass().add("drop-zone-active"));
        node.setOnDragExited(e -> node.getStyleClass().remove("drop-zone-active"));
        node.setOnDragDropped(e -> {
            var db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                for (var f : db.getFiles()) {
                    if (f.getName().toLowerCase().endsWith(".jar")) {
                        try {
                            mods.addMod(f.toPath());
                            success = true;
                        } catch (Exception ex) {
                            log("Failed to add " + f.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }
            e.setDropCompleted(success);
            e.consume();
            if (success) refresh.run();
        });
    }

    /** True if a file belongs to one of the auto-bundled DEY mod families. Bundled mods can be
     *  updated/downgraded but NEVER deleted, so they're always excluded from the Fix logic. */
    private boolean isBundledFamily(String fileName) {
        String fn = fileName.toLowerCase();
        return fn.startsWith("sodium-") || fn.startsWith("embeddium-")
                || fn.startsWith("iris-") || fn.startsWith("fabric-api-") || fn.startsWith("deycapes-");
    }

    /** Hides the Fix controls (used whenever a scan finds nothing incompatible). Must run on the FX thread. */
    private void hideFixControls(Button fixBtn, Label fixStatus) {
        fixBtn.setVisible(false);
        fixBtn.setManaged(false);
        fixStatus.setVisible(false);
        fixStatus.setManaged(false);
    }
/**
     * Scans installed mods (skipping bundled ones) in the background and reveals the Fix button if at
     * least one is incompatible with the currently selected Minecraft version. Never touches the UI
     * from the worker thread -- only flips the button via Platform.runLater.
     */
    private void refreshFixStatus(ModsManager mods, List<ModsManager.ModEntry> list,
                                  Button fixBtn, Label fixStatus, String mcVersion) {
        refreshFixStatus(mods, list, fixBtn, fixStatus, mcVersion, new java.util.LinkedHashSet<>(), null);
    }

    /**
     * Same compatibility scan as above, but also fills {@code problemFiles} with the filename of
     * every non-bundled mod found incompatible with {@code mcVersion} -- the Mods window's
     * selection toolbar uses that set to decide whether "Fix Selected" should be shown (only when
     * the current selection actually includes at least one problem mod). {@code onDone}, if given,
     * runs once the scan settles (success, failure, or nothing to scan) so the caller can refresh
     * anything that depends on {@code problemFiles}.
     */
    /** Outcome of the background compatibility scan: which mods need fixing, and what it couldn't tell. */
    private record FixScan(java.util.Set<String> problems, int unverified, int unchecked) { }

    private void refreshFixStatus(ModsManager mods, List<ModsManager.ModEntry> list,
                                  Button fixBtn, Label fixStatus, String mcVersion,
                                  java.util.Set<String> problemFiles, Runnable onDone) {
        java.util.List<ModsManager.ModEntry> candidates = list.stream()
                .filter(m -> !isBundledFamily(m.fileName()))
                .toList();
        problemFiles.clear();
        if (candidates.isEmpty() || mcVersion == null || mcVersion.isBlank()) {
            hideFixControls(fixBtn, fixStatus);
            if (onDone != null) onDone.run();
            return;
        }
        Task<FixScan> task = new Task<>() {
            @Override
            protected FixScan call() {
                ModrinthClient client = new ModrinthClient();
                CurseForgeFallback curseForge = new CurseForgeFallback(gameFiles.root);
                InstalledModResolver resolver = new InstalledModResolver(client, curseForge);
                String loader = modLoaderBox.getValue();
                java.util.Set<String> problems = new java.util.LinkedHashSet<>();
                int unverified = 0, unchecked = 0;
                for (var m : candidates) {
                    // The SAME resolver the Fix action uses decides what counts as a problem here, so the
                    // button is offered exactly when the fixer can actually act: Modrinth first, then the
                    // keyless CurseForge fallback for a mod Modrinth doesn't carry or has no build for.
                    // Only a VERIFIED identity may decide "this mod is incompatible" -- anything else is
                    // counted instead, so the window never claims a mod is fine when we simply couldn't
                    // judge it. (A mod that is on Modrinth with a compatible build short-circuits before
                    // CurseForge is ever consulted, so a normal pack scan costs no extra requests.)
                    try {
                        InstalledModResolver.Resolution r = resolver.resolve(mods.modSlug(m.fileName()),
                                m.fileName(), mcVersion, loader, mods.modLicense(m.fileName()));
                        if (r.transientFailure()) { unchecked++; continue; }
                        boolean needsFix = r.needsReplacement()
                                || (r.source() == InstalledModResolver.Source.NONE && r.modrinthKnown()
                                    && r.noModrinthBuildForThisVersion());
                        if (needsFix) {
                            problems.add(m.fileName());
                        } else if (r.source() == InstalledModResolver.Source.NONE && !r.modrinthKnown()) {
                            unverified++; // not fixable from any permitted source: never touch it
                        }
                    } catch (Exception ignored) {
                        // Couldn't reach a source for this one (rate limit, offline) -- keep checking the rest.
                        unchecked++;
                    }
                }
                return new FixScan(problems, unverified, unchecked);
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            FixScan scan = task.getValue();
            problemFiles.addAll(scan.problems());
            boolean fix = !scan.problems().isEmpty();
            fixBtn.setVisible(fix);
            fixBtn.setManaged(fix);
            fixStatus.setVisible(fix);
            fixStatus.setManaged(fix);
            if (fix) {
                fixStatus.setText("Some installed mods aren't compatible with Minecraft " + mcVersion
                        + ". Click Fix Mods to convert them to a working version (or remove them if "
                        + "no compatible version exists)." + scanNote(scan));
            }
            if (onDone != null) onDone.run();
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            hideFixControls(fixBtn, fixStatus);
            if (onDone != null) onDone.run();
        }));
        new Thread(task, "mod-compat-scan").start();
    }

    /** Appends how many mods the scan could NOT judge, so "nothing to fix" is never misleading. */
    private static String scanNote(FixScan scan) {
        StringBuilder sb = new StringBuilder();
        if (scan.unverified() > 0) {
            sb.append(" (").append(scan.unverified()).append(scan.unverified() == 1
                    ? " mod can't be fixed automatically (it isn't on Modrinth, and its own license "
                      + "doesn't permit fetching it elsewhere), so it is left untouched.)"
                    : " mods can't be fixed automatically (they aren't on Modrinth, and their own "
                      + "licenses don't permit fetching them elsewhere), so they are left untouched.)");
        }
        if (scan.unchecked() > 0) {
            sb.append(" (").append(scan.unchecked()).append(" couldn't be checked right now.)");
        }
        return sb.toString();
    }

    /**
     * The Mods window's selection-toolbar version of the Fix action: "Fix Selected" converts only the
     * SELECTED mods that are actually incompatible (removing any with no compatible build for the current
     * Minecraft version, unless the instance is a managed modpack); "Update Selected" grabs the newest
     * compatible build for every selected mod that isn't already on it, and never deletes anything.
     * Both go through the same resolver chain as {@link #runFixIncompatibleMods} (Modrinth, then the
     * keyless CurseForge fallback), so a mod that only exists on CurseForge can be updated here too.
     * Bundled mods are skipped even if somehow selected. Runs in the background, same pattern as
     * {@link #runFixIncompatibleMods}.
     */
    private void runFixOrUpdateSelected(ModsManager mods, java.util.Set<String> selectedFiles,
                                        java.util.Set<String> problemFiles, String mcVersion,
                                        boolean fixOnly, Runnable refresh, Button triggerBtn) {
        if (mcVersion == null || mcVersion.isBlank() || selectedFiles.isEmpty()) return;
        List<String> targets = new ArrayList<>(selectedFiles);
        java.util.Set<String> problemsSnapshot = new java.util.LinkedHashSet<>(problemFiles);
        // Read both on the FX thread (this method runs there); a background task must not read the UI.
        boolean packManaged = ModpackMeta.read(currentInstanceDir()) != null;
        String loader = modLoaderBox.getValue();
        triggerBtn.setDisable(true);
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                ModrinthClient client = new ModrinthClient();
                CurseForgeFallback curseForge = new CurseForgeFallback(gameFiles.root);
                InstalledModResolver resolver = new InstalledModResolver(client, curseForge);
                StringBuilder detail = new StringBuilder();
                int changed = 0, removed = 0, kept = 0, unverified = 0, unchecked = 0, skipped = 0;
                try {
                    java.util.Map<String, ModsManager.ModEntry> byFileName = new java.util.HashMap<>();
                    for (var m : mods.list()) byFileName.put(m.fileName(), m);
                    for (String fileName : targets) {
                        if (fixOnly && !problemsSnapshot.contains(fileName)) { skipped++; continue; }
                        ModsManager.ModEntry m = byFileName.get(fileName);
                        if (m == null || isBundledFamily(m.fileName())) { skipped++; continue; }
                        // Same resolver chain as Fix Mods (Modrinth -> keyless CurseForge). "Update
                        // Selected" never removes anything, and "Fix Selected" never removes inside a
                        // managed pack -- see applyResolvedFix for both rules.
                        FixOutcome outcome = applyResolvedFix(client, resolver, curseForge, mods, m,
                                mcVersion, loader, fixOnly && !packManaged);
                        switch (outcome.kind()) {
                            case FIXED -> changed++;
                            case REMOVED -> removed++;
                            case KEPT -> kept++;
                            case UNIDENTIFIED -> unverified++;
                            case UNCHECKED -> unchecked++;
                            default -> skipped++; // already current, or not part of this action
                        }
                        if (outcome.detail() != null) detail.append(outcome.detail());
                    }
                } catch (Exception ex) {
                    return "ERROR\n" + ex.getMessage();
                }
                return "FIXED " + changed + "\nREMOVED " + removed + "\nKEPT " + kept
                        + "\nUNVERIFIED " + unverified + "\nUNCHECKED " + unchecked
                        + "\nSKIPPED " + skipped + "\n" + detail;
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            triggerBtn.setDisable(false);
            String result = task.getValue();
            selectedFiles.clear();
            refresh.run();
            if (result != null && !result.startsWith("ERROR")) log(result.replace('\n', ' '));
            new Alert(Alert.AlertType.INFORMATION, formatFixResult(result), ButtonType.OK).showAndWait();
        }));
        task.setOnFailed(ev -> Platform.runLater(() -> {
            triggerBtn.setDisable(false);
            selectedFiles.clear();
            refresh.run();
        }));
        new Thread(task, fixOnly ? "mod-fix-selected" : "mod-update-selected").start();
    }

    /** Resolves an installed mod to its Modrinth slug: metadata id first, display-name search as a fallback. */
    private String resolveModSlug(ModrinthClient client, ModsManager mods, ModsManager.ModEntry m) {
        String key = normalizeAddonBase(m.fileName());
        String cached = addonSlugByBase.get(key);
        if (cached != null && !cached.isBlank()) return cached;
        var hit = client.firstHitBySlugOrName(mods.modSlug(m.fileName()), m.displayName(), "mod");
        if (hit == null || hit.slug().isBlank()) return null;
        addonSlugByBase.put(key, hit.slug());
        return hit.slug();
    }

    /**
     * A Modrinth slug we can actually TRUST for this installed jar -- the mod's own declared id
     * (fabric.mod.json {@code id} / mods.toml {@code modId}) must resolve to a real Modrinth project
     * of the right kind (see {@link ModrinthClient#projectByExactSlug}).
     *
     * <p>Deliberately does NOT fall back to a display-name search, unlike {@link #resolveModSlug}:
     * that fuzzy path is fine for hanging an icon off a mod, but it must never decide that a jar is
     * "incompatible", because acting on a wrong match is how a modpack loses working mods.
     *
     * @return the verified slug, or null when the mod can't be identified -- meaning "leave it alone".
     */
    private String resolveVerifiedSlug(ModrinthClient client, ModsManager mods, ModsManager.ModEntry m) {
        String declared = mods.modSlug(m.fileName());
        if (declared == null || declared.isBlank()) return null;
        var hit = client.projectByExactSlug(declared, "mod");
        return hit == null || hit.slug().isBlank() ? null : hit.slug();
    }
    /** What one mod's Fix attempt did, so both Fix entry points report it identically. */
    private enum FixKind { UP_TO_DATE, FIXED, REMOVED, KEPT, UNIDENTIFIED, UNCHECKED }

    /** One mod's Fix result plus the human-readable line describing it (null when nothing noteworthy). */
    private record FixOutcome(FixKind kind, String detail) {
        static FixOutcome of(FixKind kind) { return new FixOutcome(kind, null); }
    }

    /**
     * Resolves and applies the fix for ONE installed mod through the shared resolver chain
     * ({@link InstalledModResolver}): Modrinth first, then the keyless CurseForge fallback when the mod's
     * own declared license permits it ({@link ModDistributionPolicy}).
     *
     * <p>This wiring is the point of the resolver: the launcher installs plenty of CurseForge packs, and
     * for a mod that isn't published on Modrinth the old code simply reported "unverified / left
     * untouched", so Fix did nothing useful for exactly the packs that needed it. Now such a mod is
     * fetched from CurseForge when its license allows, and when nothing can be done the report says why.
     *
     * <p>Two invariants are preserved from the Modrinth-only implementation:
     * <ul>
     *   <li>only a mod whose own declared id resolves to a real project is ever touched -- no fuzzy name
     *       matching (a wrong guess is how a modpack loses working mods), and</li>
     *   <li>inside a modpack instance nothing is DELETED ({@code allowRemove} is false): the pack's mod
     *       set is curated by its author.</li>
     * </ul>
     */
    private FixOutcome applyResolvedFix(ModrinthClient client, InstalledModResolver resolver,
                                        CurseForgeFallback curseForge, ModsManager mods,
                                        ModsManager.ModEntry m, String mcVersion, String loader,
                                        boolean allowRemove) {
        InstalledModResolver.Resolution r;
        try {
            r = resolver.resolve(mods.modSlug(m.fileName()), m.fileName(), mcVersion, loader,
                    mods.modLicense(m.fileName()));
        } catch (Exception e) {
            return new FixOutcome(FixKind.UNCHECKED, "  • Couldn't check " + m.fileName() + " right now ("
                    + brief(e) + ") -- run Fix again in a minute.\n");
        }

        if (r.upToDate()) return FixOutcome.of(FixKind.UP_TO_DATE);

        if (r.canFix()) {
            try {
                Path downloaded = r.source() == InstalledModResolver.Source.CURSEFORGE
                        ? curseForge.download(r.curseForgeCandidate(), mods.modsDir())
                        : client.download(r.downloadUrl(), r.targetFileName(), mods.modsDir());
                // "Converted" replaces the jar one-for-one: never leave two builds of the same mod installed.
                if (r.source() == InstalledModResolver.Source.MODRINTH) {
                    try { deleteJarsForSlug(mods.modsDir(), mods.modSlug(m.fileName()), downloaded); }
                    catch (Exception ignored) { }
                }
                try {
                    if (!downloaded.getFileName().toString().equals(m.fileName())) mods.delete(m.fileName());
                } catch (Exception ignored) { }
                return new FixOutcome(FixKind.FIXED, "  • Converted " + m.displayName() + " to "
                        + describeVersion(r.targetVersionLabel()) + " (" + sourceName(r.source()) + ").\n");
            } catch (Exception e) {
                return new FixOutcome(FixKind.UNCHECKED, "  • Found a replacement for " + m.fileName()
                        + " but couldn't download it (" + brief(e) + ").\n");
            }
        }

        // Nothing could be fetched. The ONLY case that still justifies removing a mod is the one the old
        // code used: a project Modrinth genuinely knows, with no build at all for this MC version, and
        // not inside a managed pack.
        if (allowRemove && r.modrinthKnown() && r.noModrinthBuildForThisVersion()) {
            try {
                mods.delete(m.fileName());
                return new FixOutcome(FixKind.REMOVED, "  • Removed " + m.fileName()
                        + " -- no version supports " + mcVersion + ".\n");
            } catch (Exception e) {
                return new FixOutcome(FixKind.KEPT, "  • Kept " + m.fileName()
                        + " -- it had to be removed but the file couldn't be deleted.\n");
            }
        }
        if (r.transientFailure()) {
            return new FixOutcome(FixKind.UNCHECKED, "  • Couldn't check " + m.fileName() + " -- "
                    + r.note() + ". Run Fix again in a minute to retry it.\n");
        }
        if (r.modrinthKnown()) {
            return new FixOutcome(FixKind.KEPT, "  • Kept " + m.fileName() + " -- " + r.note() + ".\n");
        }
        return new FixOutcome(FixKind.UNIDENTIFIED, "  • Left " + m.fileName() + " untouched -- "
                + r.note() + ".\n");
    }

    /** Where a replacement build came from, as shown to the user. */
    private static String sourceName(InstalledModResolver.Source source) {
        return source == InstalledModResolver.Source.CURSEFORGE ? "CurseForge" : "Modrinth";
    }

    /** "version 1.2.3" when the source stated one, else a neutral phrase. */
    private static String describeVersion(String label) {
        return label == null || label.isBlank() ? "a compatible build" : "version " + label;
    }

    /** One short clause out of an exception, for a log line. */
    private static String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 77) + "..." : msg;
    }

    /**
     * The Fix Mods action: for every installed NON-bundled mod, work out whether a working build of it
     * exists for the selected Minecraft version and, if so, swap the installed jar for it. The source is
     * the shared resolver chain -- Modrinth first, then the keyless CurseForge fallback when the mod's own
     * license permits it -- so a CurseForge-only pack (which this launcher installs a lot of) can finally
     * be fixed here instead of being reported as "unverified". If nothing at all can be fetched, the run
     * says why, per mod. Bundled DEY mods are never touched (their own installers repair those). Runs in
     * the background and re-renders + summarizes on completion.
     *
     * <p>Two rules keep this safe on a modpack, where a wrong guess is very expensive:
     * <ul>
     *   <li><b>Only a verified identity is acted on.</b> A jar whose own declared id doesn't resolve to a
     *       real project on either source, with a license that permits fetching, is reported and left
     *       alone rather than guessed at -- fuzzy matching is what used to delete working
     *       CurseForge-only pack mods.</li>
     *   <li><b>Inside a modpack nothing is ever deleted</b> ({@code allowRemove=false}). A pack's mod set
     *       is curated by its author and often pinned to a build no source carries, so "it has no build
     *       for this version, remove it" would silently break the pack. Those mods are reported as kept.</li>
     * </ul>
     */
    private void runFixIncompatibleMods(ModsManager mods, Button fixBtn, Label fixStatus,
                                        Runnable refresh, String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return;
        fixBtn.setDisable(true);
        fixStatus.setText("Checking each installed mod against Minecraft " + mcVersion + "...");
        fixStatus.setVisible(true);
        fixStatus.setManaged(true);
        boolean packManaged = ModpackMeta.read(currentInstanceDir()) != null;
        // Read on the FX thread (this method runs there); the background task must not touch the combo box.
        String loader = modLoaderBox.getValue();
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                ModrinthClient client = new ModrinthClient();
                CurseForgeFallback curseForge = new CurseForgeFallback(gameFiles.root);
                InstalledModResolver resolver = new InstalledModResolver(client, curseForge);
                StringBuilder detail = new StringBuilder();
                int fixed = 0, removed = 0, kept = 0, unverified = 0, unchecked = 0;
                java.util.List<ModsManager.ModEntry> all;
                try {
                    all = mods.list();
                } catch (Exception ex) {
                    return "ERROR\n" + ex.getMessage();
                }
                int total = all.size(), done = 0;
                for (var m : all) {
                    reportFixProgress(fixStatus, ++done, total, mcVersion);
                    if (isBundledFamily(m.fileName())) continue; // bundled mods: never delete
                    // EVERY per-mod failure is contained inside applyResolvedFix. Modrinth can rate-limit
                    // or drop a request mid-scan, and that used to escape this loop and turn the entire
                    // run into "Couldn't fix your mods" even though the other mods were handled fine.
                    // allowRemove=false inside a pack: its curated mod set is never deleted from.
                    FixOutcome outcome = applyResolvedFix(client, resolver, curseForge, mods, m,
                            mcVersion, loader, !packManaged);
                    switch (outcome.kind()) {
                        case FIXED -> fixed++;
                        case REMOVED -> removed++;
                        case KEPT -> kept++;
                        case UNIDENTIFIED -> unverified++;
                        case UNCHECKED -> unchecked++;
                        default -> { }
                    }
                    if (outcome.detail() != null) detail.append(outcome.detail());
                }
                return "FIXED " + fixed + "\nREMOVED " + removed + "\nKEPT " + kept
                        + "\nUNVERIFIED " + unverified + "\nUNCHECKED " + unchecked + "\n" + detail;
            }
        };
        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            fixBtn.setDisable(false);
            hideFixControls(fixBtn, fixStatus);
            String result = task.getValue();
            refresh.run();
            if (result != null && !result.startsWith("ERROR")) log(result.replace('\n', ' '));
            new Alert(Alert.AlertType.INFORMATION, formatFixResult(result), ButtonType.OK).showAndWait();
        }));
        task.setOnFailed(ev -> Platform.runLater(() -> {
            fixBtn.setDisable(false);
            hideFixControls(fixBtn, fixStatus);
            refresh.run();
        }));
        new Thread(task, "mod-compat-fix").start();
    }

    /** Turns the raw Fix task result into a short, human-friendly summary for the finishing alert. */
    private String formatFixResult(String result) {
        if (result == null) return "Nothing to fix -- all installed mods are already compatible with the current Minecraft version.";
        if (result.startsWith("ERROR")) {
            return "Couldn't fix your mods (please check the launcher log):\n" + result.substring(6);
        }
        int fixed = 0, removed = 0, kept = 0, unverified = 0, unchecked = 0;
        StringBuilder bullets = new StringBuilder();
        for (String line : result.split("\n")) {
            if (line.startsWith("FIXED ")) fixed = parseIntOr(line.substring(6).trim(), 0);
            else if (line.startsWith("REMOVED ")) removed = parseIntOr(line.substring(8).trim(), 0);
            else if (line.startsWith("KEPT ")) kept = parseIntOr(line.substring(5).trim(), 0);
            else if (line.startsWith("UNVERIFIED ")) unverified = parseIntOr(line.substring(11).trim(), 0);
            else if (line.startsWith("UNCHECKED ")) unchecked = parseIntOr(line.substring(10).trim(), 0);
            else if (line.startsWith("  •")) bullets.append(line).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        if (fixed == 0 && removed == 0) {
            sb.append("Nothing was changed -- every mod that could be checked is already compatible ")
                    .append("with this Minecraft version.");
        } else {
            sb.append("Done. Converted ").append(fixed).append(" mod(s)");
            if (removed > 0) {
                sb.append(" and removed ").append(removed).append(" incompatible mod(s) with no working version");
            }
            sb.append(".\n\nDetails:\n").append(bullets);
        }
        // Never let the summary hide the mods we did NOT judge: otherwise "nothing to fix" reads as
        // "everything is fine" when part of the list simply couldn't be identified or reached.
        if (kept > 0) {
            sb.append("\nKept ").append(kept).append(" mod(s) that Modrinth has no build for on this ")
              .append("Minecraft version -- removing them would break the modpack.");
        }
        if (unverified > 0) {
            sb.append("\nLeft ").append(unverified).append(" mod(s) untouched: nothing could be resolved for ")
              .append("them (not published on Modrinth, and their own license doesn't permit fetching a ")
              .append("build from CurseForge on your behalf), so there's nothing safe to compare them against.");
        }
        if (unchecked > 0) {
            sb.append("\nCouldn't check ").append(unchecked).append(" mod(s) right now (Modrinth was busy ")
              .append("or rate-limited us) -- run Fix again in a minute to retry those.");
        }
        return sb.toString();
    }

    /**
     * Throttled "N/M checked" progress for a background Fix run. Called from the worker thread, so it
     * only ever touches the label through Platform.runLater, and only every few mods -- a modpack has
     * hundreds of them, and one UI callback per mod would flood the FX event queue.
     */
    private void reportFixProgress(Label status, int done, int total, String mcVersion) {
        if (done % 10 != 0 && done != total) return;
        Platform.runLater(() -> {
            if (status.isVisible()) {
                status.setText("Checking mods against Minecraft " + mcVersion + "... " + done + "/" + total);
            }
        });
    }
/**
     * Builds the Mods window: an undecorated (borderless) window that stays in sync with the
     * launcher's theme, is draggable via its header, resizable from its edges, and opens by default
     * at a size proportional to the display -- wider and taller than the old 560x520 dialog.
     */
    private Stage buildModsWindowStage(String title, Node center) {
        Stage win = new Stage();
        win.initOwner(stage);
        win.initModality(Modality.WINDOW_MODAL);
        win.initStyle(javafx.stage.StageStyle.UNDECORATED);
        win.setTitle(title);

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("mods-win-title");

        Button closeBtn = new Button(" \u00D7 ");
        closeBtn.getStyleClass().add("mods-win-close");
        closeBtn.setOnAction(e -> win.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titleLbl, spacer, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mods-win-header");

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(center);
        root.getStyleClass().addAll("root-pane", "mods-win-root", darkMode ? "theme-dark" : "theme-light");

        Scene sc = new Scene(root);
        sc.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        sc.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        win.setScene(sc);

        // Default size proportional to the display (fits any screen) and comfortably large.
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.max(920, Math.min(1400, vb.getWidth() * 0.8));
        double h = Math.max(640, Math.min(900, vb.getHeight() * 0.82));
        win.setWidth(w);
        win.setHeight(h);
        win.setMinWidth(860);
        win.setMinHeight(580);
        win.centerOnScreen();

        enableWindowChrome(win, root, header);

        win.setOnCloseRequest(e -> win.hide());
        return win;
    }

    /** Lets a custom (borderless) undecorated window be moved (drag its header) and resized from any
     *  edge or corner. As you hover each boundary it shows the matching OS resize cursor arrow:
     *  N/S vertical, E/W horizontal, and both diagonals (NW/SE and NE/SW). Applied to EVERY custom
     *  window (Mods, Settings, Server Management, version picker) via the shared stage builders. */
    private void enableWindowChrome(Stage win, Node root, Node header) {
        final double BORDER = 14;   // whole perimeter resizes (not just the top bar); 14px is an easy grab zone
        final double[] start = new double[2];   // screen x,y at press
        final double[] orig = new double[4];    // window x,y,w,h at press
        final double[] lastPtr = new double[2]; // previous screen x,y during a drag (for delta movement)
        final boolean[] west = new boolean[1], east = new boolean[1],
                       north = new boolean[1], south = new boolean[1];
        final boolean[] resizing = new boolean[1], dragging = new boolean[1];
        final boolean[] snapping = new boolean[1]; // window's top edge at the screen top -> maximize on release
        final double MAX_SNAP = 8;                // px from the screen's top edge that counts as "snap to full screen"
        final double[] prevBounds = new double[4]; // last non-maximized x,y,w,h, so a maximized window can be restored on grab / double-click

        java.util.function.BiConsumer<Double, Double> zone = (px, py) -> {
            double w = root.getLayoutBounds().getWidth();
            double h = root.getLayoutBounds().getHeight();
            west[0] = px <= BORDER;
            east[0] = px >= w - BORDER;
            north[0] = py <= BORDER;
            south[0] = py >= h - BORDER;
        };

        java.util.function.DoubleSupplier headerBottom = () -> {
            double bh = header.getBoundsInParent().getHeight();
            return bh > 0 ? header.getBoundsInParent().getMaxY() : header.prefHeight(-1);
        };

        // Window can't be resized/dragged while maximized or full-screen, so never show those cursors then.
        final double[] lastY = new double[1];
        java.util.function.BooleanSupplier locked = () -> win.isMaximized() || win.isFullScreen();

        Runnable updateCursor = () -> {
            if (win.isFullScreen()) {
                root.setCursor(null); // full-screen: no resize or move, plain default
                return;
            }
            if (win.isMaximized()) {
                // Maximized: edges can't be resized, but the header (title bar) can still be grabbed
                // and dragged down to restore -- mirror a native window's move affordance.
                if (lastY[0] <= headerBottom.getAsDouble()) root.setCursor(javafx.scene.Cursor.MOVE);
                else root.setCursor(null);
                return;
            }
            if (west[0] && north[0]) root.setCursor(javafx.scene.Cursor.NW_RESIZE);      // top-left corner
            else if (east[0] && north[0]) root.setCursor(javafx.scene.Cursor.NE_RESIZE);  // top-right corner
            else if (west[0] && south[0]) root.setCursor(javafx.scene.Cursor.SW_RESIZE);  // bottom-left corner
            else if (east[0] && south[0]) root.setCursor(javafx.scene.Cursor.SE_RESIZE);  // bottom-right corner
            else if (west[0] || east[0]) root.setCursor(javafx.scene.Cursor.H_RESIZE);    // left/right edge
            else if (north[0] || south[0]) root.setCursor(javafx.scene.Cursor.V_RESIZE);  // top/bottom edge
            else if (lastY[0] <= headerBottom.getAsDouble()) root.setCursor(javafx.scene.Cursor.MOVE); // drag area
            // Outside any resize/move zone: release the forced cursor (null) so the node under the
            // pointer decides normally again (e.g. Hand over the nav buttons) -- the cursor "reverts".
            else root.setCursor(null);
        };

        // ---- Off-edge parking ---------------------------------------------------------------------
        // A drag follows the cursor through the MOUSE_DRAGGED handler below. The moment the pointer
        // reaches within a few px of ANY edge of the screen it is actually on, an AnimationTimer keeps
        // sliding the window outward in that direction while the button is held -- exactly like a native
        // window grab keeps moving the window even though the cursor itself is pinned against the edge.
        // That is what lets a window be parked partly or fully off the top / bottom / left / right of a
        // screen, without having to drag into the desktop's extreme outer corner.
        //
        // The live pointer comes from PointerProbe: pure-JavaFX Robot#getMousePosition() first, with the
        // optional JNA NativeInput as a fallback (it can also report the button state, which ends a drag
        // that is pinned at an edge on the true mouse-up even before a JavaFX release arrives). Every way
        // a drag can end funnels through endDrag(), so a slide can never get stuck on.
        final double EDGE = 6;                 // px from a screen edge that counts as "pinned"
        final double PUSH = 760;               // px/sec the window slides outward while pinned
        final long TOP_DWELL = 320_000_000L;   // hold in the top band this long to mean "push off the top"
        final double[] tmpDir = new double[2]; // reused per-frame result of EdgePush.slide
        final long[] pushPrev = new long[1];   // timestamp of the previous frame that actually slid
        final int[] noProgress = new int[1];   // consecutive frames the desktop refused to move the window
        final double[] prevStart = new double[2]; // window position at the start of the previous pushed frame
        final double[] prevReq = new double[2];   // what that frame asked the window to do
        final boolean[] prevSlid = new boolean[1];
        final boolean[] pushRefused = new boolean[1]; // this desktop refused to move a window off its screens
        final int[] snapEdge = new int[1];   // snap mode: the EdgePush.LEFT/RIGHT edge the window is armed on
        final boolean[] halfTiled = new boolean[1]; // currently filling a lateral half-tile from snap mode
        final double[] preTile = new double[4];     // window x,y,w,h captured the moment it was first tiled
        final long[] topBandSince = new long[1];
        final boolean[] topPushed = new boolean[1]; // top edge already slid off -> don't maximize on release
        final AnimationTimer[] pushTimer = new AnimationTimer[1];

        // Every screen's bounds, reused instead of re-allocated: refreshed only while the pointer
        // actually sits at an edge (see checkBand), so a normal drag never touches it.
        final java.util.List<javafx.geometry.Rectangle2D> allScreens = new java.util.ArrayList<>();
        Runnable refreshScreens = () -> {
            allScreens.clear();
            for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) allScreens.add(s.getBounds());
        };

        Runnable stopPush = () -> {
            if (pushTimer[0] != null) {
                try { pushTimer[0].stop(); } catch (IllegalStateException ignored) {}
                pushTimer[0] = null;
            }
            pushPrev[0] = 0;
            topBandSince[0] = 0;
            noProgress[0] = 0;
            prevSlid[0] = false;
            tmpDir[0] = 0; tmpDir[1] = 0;
        };

        java.util.function.Function<double[], javafx.stage.Screen> screenAt = xy -> {
            javafx.stage.Screen best = javafx.stage.Screen.getPrimary();
            double px = xy[0], py = xy[1];
            double bestArea = -1.0;
            for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
                javafx.geometry.Rectangle2D vb = s.getVisualBounds();
                double ox = Math.max(0, Math.min(vb.getMaxX(), px) - Math.max(vb.getMinX(), px));
                double oy = Math.max(0, Math.min(vb.getMaxY(), py) - Math.max(vb.getMinY(), py));
                double area = ox * oy;
                if (area > bestArea) { bestArea = area; best = s; }
            }
            return best;
        };

        // Live cue for the armed edge gesture: an orange bar on the side the window is about to be tiled
        // to (snap mode), or along the top when a release would maximize (drag-to-top, both modes).
        Runnable applySnapCue = () -> {
            if (snapEdge[0] == EdgePush.LEFT) {
                root.setStyle("-fx-border-color: transparent transparent transparent #ff7a1f; -fx-border-width: 0 0 0 6;");
            } else if (snapEdge[0] == EdgePush.RIGHT) {
                root.setStyle("-fx-border-color: transparent #ff7a1f transparent transparent; -fx-border-width: 0 6 0 0;");
            } else if (snapping[0]) {
                root.setStyle("-fx-border-color: #ff7a1f transparent transparent transparent; -fx-border-width: 6 0 0 0;");
            } else {
                root.setStyle("");
            }
        };

        // Snap mode: fill the half of the work area on the screen the pointer is on. Uses the VISUAL
        // bounds so taskbars/panels stay visible, exactly like the desktop's own edge tiling.
        Runnable applyHalfTile = () -> {
            javafx.geometry.Rectangle2D wa =
                    screenAt.apply(new double[]{lastPtr[0], lastPtr[1]}).getVisualBounds();
            javafx.geometry.Rectangle2D tile = EdgePush.halfTile(wa, snapEdge[0] == EdgePush.LEFT);
            // Remember the size the window had BEFORE it was first tiled, so grabbing its header
            // again can restore it (see the MOUSE_PRESSED handler). Re-tiling must keep the original.
            if (!halfTiled[0]) {
                preTile[0] = win.getX(); preTile[1] = win.getY();
                preTile[2] = win.getWidth(); preTile[3] = win.getHeight();
                halfTiled[0] = true;
            }
            win.setMaximized(false);
            win.setX(tile.getMinX());
            win.setY(tile.getMinY());
            win.setWidth(Math.max(win.getMinWidth(), tile.getWidth()));
            win.setHeight(Math.max(win.getMinHeight(), tile.getHeight()));
        };

        // The ONE place a drag/resize is torn down. Idempotent, so every terminal path can call it
        // without double-clearing anything: a JavaFX mouse release, a button-up the release event never
        // described, the window losing focus, or the window being hidden. This is what guarantees the
        // off-edge slide can never get stuck on.
        Runnable endDrag = () -> {
            stopPush.run();
            // Released at an edge. Snap mode tiles to the armed half; otherwise the drag-to-top cue
            // maximizes, and that maximize is skipped when the top edge was deliberately used to slide the
            // window off the top of the screen. The focus/hidden watchdogs clear these flags before they
            // call in here, so a watchdog teardown never snaps or maximizes.
            if (dragging[0] && !win.isFullScreen()) {
                if (snapEdge[0] != 0) applyHalfTile.run();
                else if (snapping[0] && !topPushed[0]) win.setMaximized(true);
            }
            dragging[0] = false;
            resizing[0] = false;
            snapping[0] = false;
            snapEdge[0] = 0;
            topPushed[0] = false;
            applySnapCue.run();
            if (!win.isMaximized() && !win.isFullScreen()) {
                prevBounds[0] = win.getX(); prevBounds[1] = win.getY();
                prevBounds[2] = win.getWidth(); prevBounds[3] = win.getHeight();
            }
            updateCursor.run();
        };

        // Starts the off-edge slide. Safe to call repeatedly: it only ever creates one timer.
        Runnable startPush = () -> {
            if (pushTimer[0] != null) return;
            pushPrev[0] = 0; // the first frame only baselines the clock, so the window never jumps
            AnimationTimer t = new AnimationTimer() {
                @Override public void handle(long now) {
                    if (!dragging[0] || !win.isShowing() || win.isFullScreen()) { stopPush.run(); return; }

                    // Prefer the global pointer (pure-JavaFX Robot first, optional JNA fallback): it keeps
                    // the slide going even once the window has slid out from under the cursor, and on the
                    // native tier a dropped button ends the drag on the true mouse-up. If no probe is
                    // available we hold the last drag-event position, which is all the push needs.
                    double px, py;
                    PointerProbe.Sample pt = PointerProbe.probe();
                    if (pt != null) {
                        if (pt.downKnown() && !pt.down()) { endDrag.run(); return; }
                        px = pt.x(); py = pt.y();
                    } else {
                        px = lastPtr[0]; py = lastPtr[1];
                    }

                    javafx.geometry.Rectangle2D b = screenAt.apply(new double[]{px, py}).getBounds();

                    // The top edge doubles as the drag-to-top maximize zone, so it only starts pushing
                    // once the pointer has dwelled in that band long enough to clearly mean "keep going".
                    boolean inTopBand = py <= b.getMinY() + EDGE;
                    if (inTopBand) { if (topBandSince[0] == 0) topBandSince[0] = now; }
                    else topBandSince[0] = 0;
                    boolean topEnabled = topBandSince[0] != 0 && (now - topBandSince[0]) >= TOP_DWELL;

                    int axes = EdgePush.axes(px, py, b, allScreens, EDGE, topEnabled);
                    long prev = pushPrev[0];
                    pushPrev[0] = now;
                    if (axes == 0) {
                        // Nothing to slide. Keep the timer alive only while the top dwell is still
                        // counting down, so "hold at the top" can still become "slide off the top".
                        if (inTopBand && !topEnabled) return;
                        stopPush.run();
                        return;
                    }
                    if ((axes & EdgePush.TOP) != 0 && !topPushed[0]) {
                        topPushed[0] = true;  // sliding off the top -> a release must not maximize
                        if (snapping[0]) { snapping[0] = false; applySnapCue.run(); }
                    }
                    if (prev == 0) return; // first sliding frame: baseline only

                    EdgePush.slide(axes, PUSH, (now - prev) / 1_000_000_000.0, tmpDir);

                    // Bounded slip guard: once the window is completely past the screen on an axis, stop
                    // pushing that axis. Even in the pathological case of a dropped mouse-up the window
                    // can then only end up parked off-screen -- it can never slide away forever.
                    if ((axes & EdgePush.LEFT) != 0 && win.getX() + win.getWidth() <= b.getMinX()) tmpDir[0] = 0;
                    if ((axes & EdgePush.RIGHT) != 0 && win.getX() >= b.getMaxX()) tmpDir[0] = 0;
                    if ((axes & EdgePush.TOP) != 0 && win.getY() + win.getHeight() <= b.getMinY()) tmpDir[1] = 0;
                    if ((axes & EdgePush.BOTTOM) != 0 && win.getY() >= b.getMaxY()) tmpDir[1] = 0;

                    if (tmpDir[0] == 0 && tmpDir[1] == 0) { prevSlid[0] = false; return; } // bounded guard parked it

                    // Did the PREVIOUS frame's request actually move the window? Desktops such as KWin on
                    // Wayland clamp every window back inside the screens, so pushing there is pointless --
                    // stop after a sustained refusal instead of calling setX 60x a second for nothing.
                    // This compares the position at the START of each frame (reading it back right after
                    // setX would just echo the request), so it can never trip on a desktop that does slide.
                    double startX = win.getX(), startY = win.getY();
                    if (prevSlid[0]) {
                        boolean moved = (prevReq[0] == 0 || Math.abs(startX - prevStart[0]) > 0.05)
                                && (prevReq[1] == 0 || Math.abs(startY - prevStart[1]) > 0.05);
                        if (moved) noProgress[0] = 0;
                        else if (++noProgress[0] > 45) {
                            // The desktop refuses to move the window outward (KWin on Wayland keeps every
                            // window inside the screen). Latch that and stop, so the rest of this hold does
                            // not keep probing and calling setX for nothing.
                            pushRefused[0] = true;
                            stopPush.run();
                            return;
                        }
                    }
                    prevStart[0] = startX; prevStart[1] = startY;
                    prevReq[0] = tmpDir[0]; prevReq[1] = tmpDir[1];
                    prevSlid[0] = true;

                    win.setX(startX + tmpDir[0]);
                    win.setY(startY + tmpDir[1]);
                }
            };
            pushTimer[0] = t;
            try { t.start(); } catch (IllegalStateException ignored) { pushTimer[0] = null; }
        };

        // Decides whether the pointer is currently pinned at an edge of the screen it is actually on,
        // and starts (or stops) the slide accordingly. Driven by the drag events, which keep arriving
        // while the button is held, so moving the pointer back inwards ends the slide immediately.
        Runnable checkBand = () -> {
            if (!dragging[0]) {
                if (snapEdge[0] != 0) { snapEdge[0] = 0; applySnapCue.run(); }
                stopPush.run();
                return;
            }
            double px = lastPtr[0], py = lastPtr[1];
            javafx.geometry.Rectangle2D b = screenAt.apply(new double[]{px, py}).getBounds();
            if (!EdgePush.inAnyBand(px, py, b, EDGE)) {
                pushRefused[0] = false; // left the edge: a later push gets a fresh chance
                if (snapEdge[0] != 0) { snapEdge[0] = 0; applySnapCue.run(); } // disarm the tile cue
                stopPush.run();
                return; // the common case: cheap
            }
            if (prefs.edgeSnapInsteadOfPark) {
                // SNAP MODE (Settings > Launcher): never push a window off the screen -- just arm the
                // left/right half-tile for release. The top edge needs no arming: it is the existing
                // drag-to-top maximize, and the bottom edge stays a plain move.
                refreshScreens.run();
                int axes = EdgePush.axes(px, py, b, allScreens, EDGE, true);
                int edge = (axes & EdgePush.LEFT) != 0 ? EdgePush.LEFT
                         : (axes & EdgePush.RIGHT) != 0 ? EdgePush.RIGHT : 0;
                if (snapEdge[0] != edge) { snapEdge[0] = edge; applySnapCue.run(); }
                stopPush.run();
                return;
            }
            // Right at an edge. If this desktop already refused to let the window slide outward, don't keep
            // hammering it -- the drag itself carries on normally, only the futile push is skipped.
            if (pushRefused[0]) return;
            // Only right at an edge is the screen list needed, so the ordinary drag path allocates nothing.
            refreshScreens.run();
            if (EdgePush.axes(px, py, b, allScreens, EDGE, true) != 0) startPush.run();
            else stopPush.run(); // edge shared with a neighbouring monitor -> not a real outer edge
        };

        // The per-root handlers don't reach over the main window's densely packed controls: in JavaFX,
        // MOUSE_MOVED / ENTERED / EXITED are delivered only to the single node under the pointer (picked
        // deepest-first) and do NOT bubble to ancestors. Registering scene-level event FILTERS catches every
        // such event no matter which child control is targeted, so the resize/move cursor tracks over real
        // content exactly like a fully-native window. The event origin may be any deep control, so we
        // re-project its coordinates back onto `root` (the scene root of this window).
        java.util.function.BiConsumer<javafx.scene.input.MouseEvent, double[]> readLocal = (e, out) -> {
            javafx.geometry.Point2D p = root.sceneToLocal(e.getSceneX(), e.getSceneY());
            out[0] = p.getX();
            out[1] = p.getY();
        };

        javafx.scene.Scene sc = win.getScene();
        if (sc != null) {
            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
                double[] p = new double[2];
                readLocal.accept(e, p);
                zone.accept(p[0], p[1]);
                lastY[0] = p[1];
                updateCursor.run();
            });

            // (Re)entering the window: immediately adopt the right cursor for that spot.
            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
                double[] p = new double[2];
                readLocal.accept(e, p);
                zone.accept(p[0], p[1]);
                lastY[0] = p[1];
                updateCursor.run();
            });

            // Left a resize/move zone (or the window itself) -> revert to the normal cursor. While actually
            // resizing/dragging we keep the shape, because MOUSE_MOVED already refreshes it on every move.
            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
                if (!resizing[0] && !dragging[0]) root.setCursor(null);
            });

            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (!e.isPrimaryButtonDown()) return;
                // The second click of a double-click must not start a drag -- it toggles maximize instead.
                if (e.getClickCount() >= 2) { resizing[0] = false; dragging[0] = false; return; }
                // A full-screen window is pinned: never move or resize it from a drag.
                if (win.isFullScreen()) { resizing[0] = false; dragging[0] = false; return; }
                // A fresh press always starts clean: no stale off-edge slide can survive into a new drag.
                stopPush.run();
                topPushed[0] = false;
                pushRefused[0] = false; // a new gesture gets a fresh chance, even after a refusal
                if (snapEdge[0] != 0) { snapEdge[0] = 0; applySnapCue.run(); }
                double[] p = new double[2];
                readLocal.accept(e, p);
                zone.accept(p[0], p[1]);
                start[0] = e.getScreenX(); start[1] = e.getScreenY();
                orig[0] = win.getX(); orig[1] = win.getY(); orig[2] = win.getWidth(); orig[3] = win.getHeight();

                // Grabbing a MAXIMIZED window's header restores it to its previous size and lets it follow
                // the pointer -- exactly how a native OS window behaves. Edges are ignored while maximized.
                if (win.isMaximized()) {
                    if (p[1] > headerBottom.getAsDouble()) { resizing[0] = false; dragging[0] = false; return; }
                    double gw = prevBounds[2] > 0 ? prevBounds[2] : orig[2];
                    double gh = prevBounds[3] > 0 ? prevBounds[3] : orig[3];
                    win.setMaximized(false);
                    win.setWidth(gw);
                    win.setHeight(gh);
                    win.setX(e.getScreenX() - gw * 0.5);
                    win.setY(e.getScreenY() - headerBottom.getAsDouble() * 0.5);
                    prevBounds[0] = win.getX(); prevBounds[1] = win.getY();
                    prevBounds[2] = win.getWidth(); prevBounds[3] = win.getHeight();
                    lastPtr[0] = e.getScreenX(); lastPtr[1] = e.getScreenY();
                    dragging[0] = true; resizing[0] = false;
                    halfTiled[0] = false; // maximizing replaced any half-tile state
                    return;
                }

                prevBounds[0] = orig[0]; prevBounds[1] = orig[1];
                prevBounds[2] = orig[2]; prevBounds[3] = orig[3];
                boolean edge = west[0] || east[0] || north[0] || south[0];
                if (edge) {
                    // Pressed an edge/corner -> start resizing. Direction is locked at press, so once the
                    // pointer leaves the corner it keeps resizing those two axes only ("lateral only").
                    resizing[0] = true; dragging[0] = false;
                    halfTiled[0] = false; // a manual resize retires the remembered pre-tile size
                } else if (p[1] <= headerBottom.getAsDouble()) {
                    // Grabbing the header of a window that snap mode half-tiled restores the size it had
                    // BEFORE it was pushed to the side and drops it under the pointer -- the same "drag a
                    // tiled window away to un-tile it" behaviour the desktop itself has.
                    if (halfTiled[0]) {
                        double rw = preTile[2] > 0 ? preTile[2] : orig[2];
                        double rh = preTile[3] > 0 ? preTile[3] : orig[3];
                        javafx.geometry.Rectangle2D restored = EdgePush.restoreUnderPointer(
                                rw, rh, e.getScreenX(), e.getScreenY(), headerBottom.getAsDouble());
                        win.setWidth(rw);
                        win.setHeight(rh);
                        win.setX(restored.getMinX());
                        win.setY(restored.getMinY());
                        prevBounds[0] = restored.getMinX(); prevBounds[1] = restored.getMinY();
                        prevBounds[2] = rw; prevBounds[3] = rh;
                        halfTiled[0] = false;
                    }
                    lastPtr[0] = e.getScreenX(); lastPtr[1] = e.getScreenY();
                    dragging[0] = true; resizing[0] = false;
                    // The edge gesture is armed by the drag events, never by the press alone: a plain
                    // click on a window sitting against an edge must not slide or tile it.
                } else {
                    dragging[0] = false; resizing[0] = false;
                }
            });

            // Double-click on the title bar / header toggles between maximized and the window's last
            // restored size -- the same gesture every desktop OS uses.
            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (e.getClickCount() < 2) return;
                if (win.isFullScreen()) return;
                double[] p = new double[2];
                readLocal.accept(e, p);
                if (p[1] > headerBottom.getAsDouble()) return; // only the title bar double-click toggles
                halfTiled[0] = false; // maximize/restore supersedes any remembered half-tile size
                if (!win.isMaximized()) {
                    prevBounds[0] = win.getX(); prevBounds[1] = win.getY();
                    prevBounds[2] = win.getWidth(); prevBounds[3] = win.getHeight();
                    win.setMaximized(true);
                } else {
                    win.setMaximized(false);
                    if (prevBounds[2] > 0) {
                        win.setWidth(prevBounds[2]); win.setHeight(prevBounds[3]);
                        win.setX(prevBounds[0]); win.setY(prevBounds[1]);
                    }
                }
                e.consume();
            });

            // Drag-to-top maximize helper: resolves the physical screen under the pointer, so on a
            // multi-monitor setup (even with different sizes) snapping maximizes to the right display.
            java.util.function.Function<javafx.scene.input.MouseEvent, javafx.stage.Screen> pointerScreen = ev ->
                    screenAt.apply(new double[]{ev.getScreenX(), ev.getScreenY()});

            // Edge overscroll is handled by startPush/checkBand above: whenever a drag puts the pointer
            // within a few px of an edge of the screen it is on, the window keeps sliding outward past
            // that edge while the button is held -- including off the top, once the top dwell elapses.

            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
                if (!e.isPrimaryButtonDown()) return;
                if (resizing[0]) {
                    double dx = e.getScreenX() - start[0];
                    double dy = e.getScreenY() - start[1];
                    double nw = orig[2] + (east[0] ? dx : 0) - (west[0] ? dx : 0);
                    double nh = orig[3] + (south[0] ? dy : 0) - (north[0] ? dy : 0);
                    nw = Math.max(win.getMinWidth(), nw);
                    nh = Math.max(win.getMinHeight(), nh);
                    double nx = orig[0];
                    double ny = orig[1];
                    if (west[0]) nx = orig[0] + (orig[2] - nw);
                    if (north[0]) ny = orig[1] + (orig[3] - nh);
                    win.setX(nx);
                    win.setY(ny);
                    win.setWidth(nw);
                    win.setHeight(nh);
                } else if (dragging[0]) {
                    // Delta-based drag: move the window by the pointer's movement since the last event,
                    // not by its absolute screen position. This keeps tracking smooth and lets the window
                    // be moved to ANY position -- including partly below the bottom of the screen.
                    double dx = e.getScreenX() - lastPtr[0];
                    double dy = e.getScreenY() - lastPtr[1];
                    lastPtr[0] = e.getScreenX();
                    lastPtr[1] = e.getScreenY();
                    win.setX(win.getX() + dx);
                    win.setY(win.getY() + dy);

                    // Drag-to-top maximize: when the window's top edge reaches the top of the screen under
                    // the pointer, flag it so release fills that screen's full bounds. Dragging back down
                    // clears the flag, so you stay in full-screen only while you hold it up there. Once the
                    // top edge has been used to slide the window OFF the top, the affordance stays muted
                    // until the window comes back down out of the snap zone.
                    javafx.stage.Screen scr = pointerScreen.apply(e);
                    javafx.geometry.Rectangle2D vb = scr.getVisualBounds();
                    if (topPushed[0]) {
                        if (win.getY() > vb.getMinY() + MAX_SNAP) topPushed[0] = false;
                    } else {
                        boolean snap = win.getY() <= vb.getMinY() + MAX_SNAP;
                        if (snap != snapping[0]) { snapping[0] = snap; applySnapCue.run(); }
                    }

                    // Pinned at a screen edge? Keep sliding the window outward while the button is held.
                    checkBand.run();
                }
            });

            sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
                // Release: stop any off-edge slide and clear the drag/maximize state -- this is the one
                // path that decides whether a drag-to-top becomes a real maximize.
                endDrag.run();
                double[] p = new double[2];
                readLocal.accept(e, p);
                zone.accept(p[0], p[1]);
                lastY[0] = p[1];
                updateCursor.run();
            });

            // Belt and braces: a drag can never outlive the window's own state, so an off-edge slide has
            // no way to keep running even if the release event is ever lost (app switch, window hidden...).
            // Hiding the maximize intent here keeps a focus change from being mistaken for a drag-to-top.
            win.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) { snapping[0] = false; snapEdge[0] = 0; endDrag.run(); }
            });
            win.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
                snapping[0] = false;
                snapEdge[0] = 0;
                endDrag.run();
            });

            // Keyboard nudging: with this window focused, arrow keys move it exactly in that direction --
            // including below/beside/above the desktop, where the mouse cursor can never reach (there is no
            // monitor there for the pointer to move into). Shift+arrow = a big step, arrow = fine step.
            // We never hijack the arrows while a text field is focused (Settings, notes, server inputs), and
            // maximized/full-screen windows don't nudge.
            sc.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (locked.getAsBoolean()) return;
                javafx.scene.Node owner = sc.getFocusOwner();
                // Don't steal arrows from controls that use them for selection/typing.
                if (owner instanceof javafx.scene.control.TextInputControl
                        || owner instanceof javafx.scene.control.ComboBoxBase
                        || owner instanceof javafx.scene.control.ListView
                        || owner instanceof javafx.scene.control.TableView) return;
                double step = e.isShiftDown() ? 64 : 12;
                double dx = 0, dy = 0;
                switch (e.getCode()) {
                    case UP:    dy = -step; break;
                    case DOWN:  dy =  step; break;
                    case LEFT:  dx = -step; break;
                    case RIGHT: dx =  step; break;
                    default:    return;
                }
                e.consume();
                win.setX(win.getX() + dx);
                win.setY(win.getY() + dy);
            });
        }
    }

    /** Builds a generic borderless (undecorated) window styled exactly like the Mods window --
     *  draggable via its header, resizable from its edges, and matching the launcher's theme.
     *  All the "secondary" windows (Settings, Server Management, Change Version) are built through
     *  this so they share the Mods window's look, instead of the old bordered OS dialogs. */
    private Stage buildBorderlessStage(String title, Node center, javafx.stage.Window owner,
                                       Modality modality, double minW, double minH,
                                       double prefW, double prefH) {
        Stage win = new Stage();
        if (owner != null) win.initOwner(owner);
        win.initModality(modality);
        win.initStyle(javafx.stage.StageStyle.UNDECORATED);
        win.setTitle(title);

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("mods-win-title");

        Button closeBtn = new Button(" \u00D7 ");
        closeBtn.getStyleClass().add("mods-win-close");
        closeBtn.setOnAction(e -> win.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titleLbl, spacer, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mods-win-header");

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(center);
        root.getStyleClass().addAll("root-pane", "mods-win-root", darkMode ? "theme-dark" : "theme-light");

        Scene sc = new Scene(root);
        sc.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        sc.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        win.setScene(sc);

        win.setMinWidth(minW);
        win.setMinHeight(minH);
        win.setWidth(prefW);
        win.setHeight(prefH);

        enableWindowChrome(win, root, header);
        win.setOnCloseRequest(e -> win.hide());
        return win;
    }


    /** The shared entry point every secondary window goes through. Re-opens (focuses) an already
     *  open one, otherwise builds it borderless and shows just that one window over the launcher --
     *  it no longer reopens the other secondary windows. */
    private Stage openShellWindow(String key, String title, Node content, double minW, double minH,
                                  double prefW, double prefH) {
        Stage existing = shellWindows.get(key);
        if (existing != null && existing.isShowing()) {
            existing.toFront();
            existing.requestFocus();
            return existing;
        }
        Stage win = buildBorderlessStage(title, content, stage, Modality.NONE,
                minW, minH, prefW, prefH);
        shellWindows.put(key, win);
        win.setOnCloseRequest(e -> {
            win.hide();
            e.consume();
            shellWindows.remove(key);
        });
        win.centerOnScreen();
        win.show();
        win.toFront();
        win.requestFocus();
        return win;
    }

    /** The BorderPane root of a shell window, or null -- used so the Launcher tab can re-theme the
     *  whole Settings window live without needing the old OS dialog's dialogPane. */
    private javafx.scene.layout.BorderPane stageShellRoot(String key) {
        javafx.stage.Stage w = shellWindows.get(key);
        if (w == null || w.getScene() == null) return null;
        javafx.scene.Node r = w.getScene().getRoot();
        return r instanceof javafx.scene.layout.BorderPane ? (javafx.scene.layout.BorderPane) r : null;
    }


    /** locked=true (a real sodium-*.jar under a DEY instance) shows a lock icon and a
     * "BUNDLED" badge instead of the enable checkbox and delete button -- it's a real jar
     * like any other, just not one the player is meant to disable or remove by hand. */
    private HBox buildModRow(ModsManager.ModEntry mod, ModsManager mods, Runnable refresh, boolean locked,
                              java.util.Set<String> selectedFiles, Runnable onSelectionChange) {
        // Bulk-selection checkbox, separate from the enable/disable checkbox below -- checking it
        // adds this mod to the Mods window's multi-select (Delete/Apply/Unapply/Fix/Update Selected)
        // without touching whether the mod itself is enabled. Locked (bundled) mods aren't
        // selectable, same as they're excluded from every other per-row control.
        Node selectBox;
        if (locked) {
            Region lockedSpacer = new Region();
            lockedSpacer.setPrefWidth(18);
            selectBox = lockedSpacer;
        } else {
            CheckBox pick = new CheckBox();
            pick.getStyleClass().add("mod-checkbox");
            pick.setSelected(selectedFiles.contains(mod.fileName()));
            pick.setOnAction(e -> {
                if (pick.isSelected()) selectedFiles.add(mod.fileName());
                else selectedFiles.remove(mod.fileName());
                if (onSelectionChange != null) onSelectionChange.run();
            });
            selectBox = pick;
        }

        Node leading;
        if (locked) {
            Node lockIcon = icon(IconFactory.Icon.LOCK, 18);
            lockIcon.getStyleClass().add("mod-lock-icon");
            leading = lockIcon;
        } else {
            CheckBox enabledBox = new CheckBox();
            enabledBox.getStyleClass().add("mod-checkbox");
            enabledBox.setSelected(mod.enabled());
            enabledBox.setOnAction(e -> {
                try {
                    mods.setEnabled(mod.fileName(), enabledBox.isSelected());
                    refresh.run();
                } catch (Exception ex) {
                    log("Failed to toggle " + mod.fileName() + ": " + ex.getMessage());
                }
            });
            leading = enabledBox;
        }

        Label name = new Label(mod.displayName());
        name.getStyleClass().add("mod-name");
        Label file = new Label(locked
                ? "Bundled with this DEY build -- can't be disabled or removed"
                : mod.fileName() + "  ·  " + (mod.sizeBytes() / 1024) + " KB");
        file.getStyleClass().add("mod-filename");
        VBox textBox = new VBox(2, name, file);

        // Modrinth icon (+ click-through to the project page) once we've resolved this mod's slug.
        String modSlug = addonSlugByBase.get(normalizeAddonBase(mod.fileName()));
        Node iconTile = modIconNode(modSlug != null ? modrinthIconPath(modSlug) : null, 52);
        if (modSlug != null) {
            iconTile.setCursor(javafx.scene.Cursor.HAND);
            final String clickSlug = modSlug;
            iconTile.setOnMouseClicked(ev -> openUrl(ModrinthClient.projectPageUrl(clickSlug, "mod")));
            name.setCursor(javafx.scene.Cursor.HAND);
            name.setOnMouseClicked(ev -> openUrl(ModrinthClient.projectPageUrl(clickSlug, "mod")));
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Node trailing;
        if (locked) {
            Label badge = new Label("BUNDLED");
            badge.getStyleClass().add("mod-bundled-badge");
            trailing = badge;
        } else {
            // Once the mod is resolved to a Modrinth project, let the player change its version too
            // -- the picker shows only builds for the currently-selected Minecraft version.
            Button changeBtn = null;
            final String rowModSlug = modSlug != null ? modSlug : null;
            if (rowModSlug != null) {
                changeBtn = new Button("Version");
                changeBtn.getStyleClass().add("pill-button");
                final String cSlug = rowModSlug;
                String mcVersion = versionBox.getValue() != null ? versionBox.getValue() : "1.21.1";
                changeBtn.setOnAction(e -> showModrinthVersionPicker(
                        mod.displayName(), cSlug, "mod", mcVersion,
                        modrinthIconPath(cSlug), null,
                        mods.modsDir(), cSlug, refresh));
            }
            Button deleteBtn = new Button();
            deleteBtn.setGraphic(icon(IconFactory.Icon.TRASH, 17));
            deleteBtn.setGraphicTextGap(0);
            deleteBtn.getStyleClass().add("mod-delete-button");
            deleteBtn.setOnAction(e -> {
                try {
                    mods.delete(mod.fileName());
                    refresh.run();
                } catch (Exception ex) {
                    log("Failed to delete " + mod.fileName() + ": " + ex.getMessage());
                }
            });
            List<Node> trailingNodes = new ArrayList<>();
            if (changeBtn != null) trailingNodes.add(changeBtn);
            trailingNodes.add(deleteBtn);
            HBox trailingBox = new HBox(8);
            trailingBox.getChildren().setAll(trailingNodes);
            trailingBox.setAlignment(Pos.CENTER_LEFT);
            trailing = trailingBox;
        }

        HBox row = new HBox(14, selectBox, iconTile, leading, textBox, spacer, trailing);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        if (locked) row.getStyleClass().add("mod-row-locked");
        else if (!mod.enabled()) row.getStyleClass().add("mod-row-disabled");
        return row;
    }

    private void applyTheme() {
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(darkMode ? "theme-dark" : "theme-light");
    }

    // ---- Account & Skin dialog: two tabs (Account / Skins) sharing one identityStore ----
    private Skin3DPreview skinPreview;

    // ==================== ACCOUNT TAB ====================

    /**
     * Builds the Account tab body for the current state: NO ACCOUNT / OFFLINE ACCOUNT /
     * ONLINE ACCOUNT, exactly the three states described in the spec, each with only the
     * actions valid for that state -- no username field is ever visible except right after
     * the user explicitly clicks "Create an offline account".
     */
    private VBox buildAccountTab(Runnable[] refreshHolder) {
        VBox content = new VBox(20);
        content.setPadding(new Insets(24));
        PlayerIdentity active = identityStore.getActive();

        content.getChildren().add(sectionLabel("ACTIVE ACCOUNT"));

        if (active == null) {
            Label none = new Label("No account set up yet.");
            none.getStyleClass().add("notice-label");
            content.getChildren().add(none);

            // ---- Sign in with Microsoft ----
            Label msNote = new Label("Uses DeyLauncher's real Microsoft login flow -- DeyLauncher's app "
                    + "registration is approved, so this connects to the real Minecraft account service.");
            msNote.getStyleClass().add("notice-label");
            msNote.setWrapText(true);
            Button signInBtn = new Button("Sign in with Microsoft");
            signInBtn.getStyleClass().add("pill-button");
            VBox deviceCodeBox = new VBox(10);
            Label statusLabel = new Label();
            statusLabel.getStyleClass().add("notice-label");
            statusLabel.setWrapText(true);
            signInBtn.setOnAction(e -> onSignInWithMicrosoft(signInBtn, deviceCodeBox, statusLabel, refreshHolder));
            content.getChildren().addAll(msNote, signInBtn, deviceCodeBox, statusLabel);

            // ---- Create an offline account: username field hidden until this is clicked ----
            Button createOfflineBtn = new Button("Create an offline account");
            createOfflineBtn.getStyleClass().add("pill-button");
            VBox offlineForm = new VBox(8);
            offlineForm.setVisible(false);
            offlineForm.setManaged(false);

            Label offlineNameLabel = new Label("Offline account name");
            offlineNameLabel.getStyleClass().add("field-label");
            TextField offlineNameField = new TextField();
            offlineNameField.setPromptText("e.g. Steve123");
            offlineNameField.getStyleClass().add("input-field");
            Button applyBtn = new Button();
            setButtonIcon(applyBtn, IconFactory.Icon.CHECK, "Apply");
            applyBtn.getStyleClass().addAll("settings-apply-button", "settings-apply-button-ready");
            applyBtn.setOnAction(e -> {
                String name = offlineNameField.getText().trim();
                if (name.isEmpty()) {
                    new Alert(Alert.AlertType.WARNING, "Enter a username first.", ButtonType.OK).showAndWait();
                    return;
                }
                // Don't let an offline account impersonate an existing online (Microsoft) account.
                // Same-display-name accounts on an online-mode server would otherwise collide; an
                // offline identity also can't actually authenticate as a real Mojang account.
                boolean nameTakenByOnline = identityStore.loadIndex().accounts().stream()
                        .anyMatch(a -> a.accountType == AccountType.ONLINE && a.username.equalsIgnoreCase(name));
                if (nameTakenByOnline) {
                    new Alert(Alert.AlertType.WARNING,
                            "\"" + name + "\" is already an online account on this launcher -- "
                                    + "choose a different offline name to avoid colliding on servers.",
                            ButtonType.OK).showAndWait();
                    return;
                }
                // Mojang check: creating the offline account after asking Mojang whether this name
                // already belongs to a REAL Minecraft account. If it does, refuse -- otherwise an
                // offline identity could log onto an online-mode server with the same name as a
                // genuine player and impersonate them. Fail-open (create anyway + tell the user) if
                // we can't reach Mojang, so a temporary network problem never locks you out.
                Runnable createOfflineNow = () -> {
                    AuthSession derived = AuthSession.offline(name);
                    PlayerIdentity identity = identityStore.loadOrCreate(derived.uuid(), derived.username(), AccountType.OFFLINE);
                    identityStore.setActive(identity.uuid);
                    syncPlayCardFromActiveIdentity();
                    refreshAccountButton();
                    refreshHolder[0].run();
                };
                applyBtn.setDisable(true);
                Task<Boolean> nameCheck = new Task<>() {
                    @Override
                    protected Boolean call() {
                        try {
                            var http = java.net.http.HttpClient.newHttpClient();
                            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    "https://api.mojang.com/users/profiles/minecraft/"
                                            + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)))
                                    .GET().build();
                            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                            // HTTP 200 = a REAL Mojang account with this name exists; 404 = free.
                            return resp.statusCode() == 200;
                        } catch (Exception ex) {
                            return null; // couldn't verify (offline / API down) -- fail open
                        }
                    }
                };
                nameCheck.setOnSucceeded(suc -> Platform.runLater(() -> {
                    applyBtn.setDisable(false);
                    Boolean taken = nameCheck.getValue();
                    if (taken == null) {
                        createOfflineNow.run();
                        new Alert(Alert.AlertType.INFORMATION,
                                "Couldn't verify \"" + name + "\" against Mojang (no internet?), so it "
                                        + "was created as an offline account anyway -- but it may still "
                                        + "be a real Minecraft account name.",
                                ButtonType.OK).showAndWait();
                    } else if (taken) {
                        new Alert(Alert.AlertType.WARNING,
                                "\"" + name + "\" is a real, existing Minecraft account name. Using it "
                                        + "offline would impersonate that player on servers -- please "
                                        + "choose a different name.",
                                ButtonType.OK).showAndWait();
                    } else {
                        createOfflineNow.run();
                    }
                }));
                nameCheck.setOnFailed(fail -> Platform.runLater(() -> {
                    applyBtn.setDisable(false);
                    createOfflineNow.run();
                    new Alert(Alert.AlertType.INFORMATION,
                            "Couldn't check \"" + name + "\" against Mojang -- created offline account "
                                    + "anyway, but it may be a real Minecraft account name.",
                            ButtonType.OK).showAndWait();
                }));
                new Thread(nameCheck, "offline-name-check").start();
            });
            HBox offlineRow = new HBox(10, offlineNameField, applyBtn);
            HBox.setHgrow(offlineNameField, Priority.ALWAYS);
            offlineForm.getChildren().addAll(offlineNameLabel, offlineRow);

            createOfflineBtn.setOnAction(e -> {
                offlineForm.setVisible(true);
                offlineForm.setManaged(true);
                createOfflineBtn.setVisible(false);
                createOfflineBtn.setManaged(false);
            });

            content.getChildren().addAll(createOfflineBtn, offlineForm);
        } else {
            Label badge = statusLabel(
                active.accountType == AccountType.ONLINE
            );
            badge.getStyleClass().add(active.accountType == AccountType.ONLINE ? "badge-online" : "badge-offline");

            Label nameLabel = new Label(active.username);
            nameLabel.getStyleClass().add("card-heading");

            Label uuidLabel = new Label("UUID: " + active.uuid);
            uuidLabel.getStyleClass().add("notice-label");

            HBox header = new HBox(12, nameLabel, badge);
            header.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().addAll(header, uuidLabel);

            if (active.accountType == AccountType.ONLINE) {
                Label onlineNote = new Label(liveOnlineAccessToken != null && active.uuid.equals(liveOnlineAccountUuid)
                        ? "Signed in this run -- Play will use your real online session."
                        : "Not signed in this run -- sign in with Microsoft again to play online.");
                onlineNote.getStyleClass().add("notice-label");
                onlineNote.setWrapText(true);
                content.getChildren().add(onlineNote);
            } else {
                Label offlineNote = new Label("Local play only -- can't join real online servers.");
                offlineNote.getStyleClass().add("notice-label");
                offlineNote.setWrapText(true);
                content.getChildren().add(offlineNote);
            }

            // ---- Appear offline (invisible mode) -------------------------------------------------
            // One gold card instead of a checkbox buried in a list of account details: this single
            // switch decides what EVERY friend sees about you, so it gets the accent and a sentence
            // spelling out exactly what it does. Publishing OFFLINE also clears the shared address and
            // play state in the same write (see currentPresence), so nobody keeps seeing a server the
            // user already left, and friends who are offline can be joined by nobody.
            ToggleButton invisibleToggle = new ToggleButton();
            invisibleToggle.getStyleClass().add("gold-switch");
            invisibleToggle.setSelected(prefs.invisibleMode);
            invisibleToggle.setFocusTraversable(false);
            Label invisibleTitle = new Label();
            invisibleTitle.getStyleClass().add("gold-card-title");
            Region invisibleSpacer = new Region();
            HBox.setHgrow(invisibleSpacer, Priority.ALWAYS);
            HBox invisibleHead = new HBox(10, invisibleTitle, invisibleSpacer, invisibleToggle);
            invisibleHead.setAlignment(Pos.CENTER_LEFT);
            Label invisibleNote = new Label();
            invisibleNote.getStyleClass().add("gold-card-note");
            invisibleNote.setWrapText(true);
            VBox invisibleCard = new VBox(8, invisibleHead, invisibleNote);
            invisibleCard.getStyleClass().add("gold-card");
            Runnable syncInvisibleCard = () -> {
                boolean hidden = prefs.invisibleMode;
                invisibleTitle.setText(hidden ? "You appear offline" : "Appear offline");
                invisibleToggle.setText(hidden ? "ON" : "OFF");
                invisibleNote.setText(hidden
                        ? "Friends see you as offline. They never see your server."
                        : "Friends see when you're online, and what you're playing.");
                invisibleCard.getStyleClass().remove("gold-card-on");
                if (hidden) invisibleCard.getStyleClass().add("gold-card-on");
            };
            invisibleToggle.selectedProperty().addListener((o, a, b) -> {
                prefs.invisibleMode = b;
                prefs.save();
                publishPresenceQuietly(); // reflect the change immediately, not just on next app start
                // The servers you own are unpublished while invisible too (see publishOwnedServers),
                // so that flips in the same click instead of at the next launch -- and the share card
                // right below says "paused" instead of claiming to be sharing something.
                publishOwnedServers(active);
                refreshShareCard();
                syncInvisibleCard.run();
            });
            syncInvisibleCard.run();
            content.getChildren().add(invisibleCard);

            // ---- Share my server with friends (the other half of the same privacy pair) --------
            content.getChildren().add(buildShareCard());

            // ---- Friend-profile socials (published so friends can see them on your profile) ----
            if (friendsService != null) {
                content.getChildren().add(sectionLabel("FRIEND PROFILE"));
                content.getChildren().add(buildSocialsEditor(active));
                content.getChildren().add(sectionLabel("OWNED SERVERS ON MY PROFILE"));
                content.getChildren().add(buildServerVisibilityEditor(active));
            }

            Button logoutBtn = new Button();
            setButtonIcon(logoutBtn, IconFactory.Icon.LOGOUT, "Log Out");
            logoutBtn.getStyleClass().addAll("pill-button", "logout-button");
            logoutBtn.setOnAction(e -> {
                // Session-only Microsoft state first, then the persisted account record. Online
                // (Mojang/Microsoft) accounts are fully forgotten on logout -- they disappear
                // from the switch-account list, since re-signing-in with the same Microsoft
                // account finds the same profile/skins again anyway. Offline identities have no
                // real session to sign out of, so logging out just deactivates them and leaves
                // them switchable, per IdentityStore.logoutActive()'s javadoc.
                if (active.uuid.equals(liveOnlineAccountUuid)) {
                    liveOnlineAccessToken = null;
                    liveOnlineAccountUuid = null;
                }
                if (active.accountType == AccountType.ONLINE) {
                    identityStore.forgetAccount(active.uuid);
                } else {
                    identityStore.logoutActive();
                }
                syncPlayCardFromActiveIdentity();
                refreshAccountButton();
                refreshHolder[0].run();
            });
            content.getChildren().add(logoutBtn);

            // ---- Known accounts / switch active ----
            var index = identityStore.loadIndex();
            if (index.accounts().size() > 1) {
                content.getChildren().add(sectionLabel("SWITCH ACCOUNT"));
                ComboBox<String> accountPicker = new ComboBox<>();
                accountPicker.getStyleClass().add("input-field");
                accountPicker.setMaxWidth(Double.MAX_VALUE);
                for (var acc : index.accounts()) {
                    accountPicker.getItems().add(acc.username + "  ·  " + acc.accountType + "  ·  " + acc.uuid);
                }
                Button switchBtn = new Button("Set Active");
                switchBtn.getStyleClass().add("pill-button");
                switchBtn.setOnAction(e -> {
                    int i = accountPicker.getSelectionModel().getSelectedIndex();
                    if (i >= 0) {
                        identityStore.setActive(index.accounts().get(i).uuid);
                        syncPlayCardFromActiveIdentity();
                        refreshAccountButton();
                        refreshHolder[0].run();
                    }
                });
                HBox switchRow = new HBox(10, accountPicker, switchBtn);
                HBox.setHgrow(accountPicker, Priority.ALWAYS);
                content.getChildren().add(switchRow);
            }
        }
        return content;
    }

    /**
     * The "Share my server with friends" card in the Account tab: the same gold treatment as "Appear
     * offline" directly above it, because the two switches together decide everything a friend can
     * see about you.
     *
     * <p>It applies the instant it is flipped -- prefs are saved and presence republished in the same
     * click, never through the Settings window's APPLY button -- and it deliberately does NOT mark the
     * window dirty. It used to be a checkbox in Launcher &gt; FRIENDS, where flipping it immediately
     * claimed there were unsaved changes even though the write had already gone through.
     *
     * <p>The status line is what makes the switch honest: presence is derived from a live game session
     * (see currentPresence), so with no game open there is genuinely nothing to publish yet. Instead of
     * quietly publishing an empty address -- which made the old checkbox look like it did nothing at
     * all -- the card spells out exactly what friends can and cannot see right now.
     */
    private Node buildShareCard() {
        ToggleButton shareToggle = new ToggleButton();
        shareToggle.getStyleClass().add("gold-switch");
        shareToggle.setSelected(prefs.shareServerAddress);
        shareToggle.setFocusTraversable(false);

        Label shareTitle = new Label();
        shareTitle.getStyleClass().add("gold-card-title");
        Region shareSpacer = new Region();
        HBox.setHgrow(shareSpacer, Priority.ALWAYS);
        HBox shareHead = new HBox(10, shareTitle, shareSpacer, shareToggle);
        shareHead.setAlignment(Pos.CENTER_LEFT);

        Label shareNote = new Label("Friends see the server you join automatically -- there is nothing to "
                + "type, and only a live game session is ever published. Sitting in the launcher, or "
                + "playing single player, never shows a server. Leave this off, or turn on Appear offline "
                + "above, and friends only see that you're online. Only friends see any of it: your entry "
                + "is published once you have at least one.");
        shareNote.getStyleClass().add("gold-card-note");
        shareNote.setWrapText(true);

        Label shareStatus = new Label();
        shareStatus.getStyleClass().add("gold-card-status");
        shareStatus.setWrapText(true);

        VBox card = new VBox(8, shareHead, shareNote, shareStatus);
        card.getStyleClass().add("gold-card");

        Runnable sync = () -> {
            boolean on = prefs.shareServerAddress;
            shareToggle.setText(on ? "ON" : "OFF");
            shareTitle.setText(on ? "Sharing your server" : "Share my server with friends");
            shareStatus.setText(shareStatusText(on));
            card.getStyleClass().remove("gold-card-on");
            if (on && !prefs.invisibleMode) card.getStyleClass().add("gold-card-on");
        };

        shareToggle.selectedProperty().addListener((o, a, b) -> {
            prefs.shareServerAddress = b;
            prefs.save();
            publishPresenceQuietly(); // publish (or clear) it right away, not on the next heartbeat
            sync.run();
        });

        sync.run();
        shareCardSync = sync;
        return card;
    }

    /**
     * What the share card must say for the current switch state -- always the truth about what friends
     * would see, so the toggle can never look like it did nothing. Kept separate from the nodes so the
     * wording lives in exactly one place, in step with {@link #currentPresence}.
     */
    private String shareStatusText(boolean sharing) {
        if (prefs.invisibleMode) {
            return "Paused -- you appear offline, so friends see nothing at all, server included.";
        }
        if (!sharing) {
            return "Off -- friends can see that you're online, but not which server you're on.";
        }
        if (!hasAnyFriendCached()) {
            return "No friends added yet -- nothing is published until you have at least one.";
        }
        PlayState state = livePlayState;
        if (state == PlayState.SERVER && liveServerAddress != null && !liveServerAddress.isBlank()) {
            String name = (liveServerName != null && !liveServerName.isBlank()) ? liveServerName : null;
            return "Sharing now: " + (name != null ? name + " (" + liveServerAddress + ")" : liveServerAddress);
        }
        if (state == PlayState.SERVER) {
            return "On -- you're on a server whose address can't be shared (it runs on this PC).";
        }
        return "On -- waiting for you to join a server; it's shared the moment you're in.";
    }

    /**
     * True when this install knows about at least one confirmed friend. Read from the local friends
     * cache rather than fetched, because it gates every presence write (including the heartbeat).
     *
     * <p>This is what "only show to friends" means in practice here: with an empty friends list there
     * is nobody to share anything with, so nothing about where you are is written to the shared file at
     * all. Adding a friend makes the very next presence write carry it.
     */
    private boolean hasAnyFriendCached() {
        FriendsService.FriendsView cached = friendsCache.load();
        return cached != null && cached.friends() != null && !cached.friends().isEmpty();
    }

    /** Refreshes the Account tab's share card, if it is currently built -- see {@link #buildShareCard}. */
    private void refreshShareCard() {
        Runnable sync = shareCardSync;
        if (sync == null || !Platform.isFxApplicationThread()) return;
        sync.run();
    }

    /**
     * True only when {@code uuid} is on OUR friends list. Every renderer that shows someone else's
     * presence (server address, current server, live play state) goes through this, so a stranger whose
     * entry merely exists in the shared friends file can never be presented as someone to join.
     */
    private boolean isFriend(String uuid, FriendsService.FriendsView view) {
        if (uuid == null || view == null || view.friends() == null) return false;
        for (var f : view.friends()) {
            if (uuid.equals(f.uuid)) return true;
        }
        return false;
    }

    /** Editor for the socials shown on your friend profile. Changes are published to friends.json. */
    private Node buildSocialsEditor(PlayerIdentity active) {
        VBox box = new VBox(10);
        FriendsService.FriendsView cached = friendsCache.load();
        FriendsData.UserEntry me = (cached != null) ? cached.allUsers().get(active.uuid) : null;
        java.util.List<FriendsData.Social> working = new java.util.ArrayList<>();
        if (me != null && me.socials != null) working.addAll(me.socials);

        ComboBox<String> typeBox = new ComboBox<>();
        typeBox.getItems().addAll("Discord", "YouTube", "Twitch", "X/Twitter", "Email", "Website", "Other");
        typeBox.getSelectionModel().selectFirst();
        typeBox.getStyleClass().add("input-field");
        TextField valueField = new TextField();
        valueField.setPromptText("handle / link (e.g. @user or https://...)");
        valueField.getStyleClass().add("input-field");
        HBox.setHgrow(valueField, Priority.ALWAYS);
        Button addBtn = new Button("Add");
        addBtn.getStyleClass().add("pill-button");

        VBox rows = new VBox(6);
        final Runnable[] refreshRows = new Runnable[1];
        refreshRows[0] = () -> {
            rows.getChildren().clear();
            for (int i = 0; i < working.size(); i++) {
                FriendsData.Social s = working.get(i);
                Label lbl = new Label((s.type != null ? s.type : "") + ": " + (s.value != null ? s.value : ""));
                lbl.getStyleClass().add("mod-name");
                Button rm = new Button("Remove");
                rm.getStyleClass().add("pill-button");
                int idx = i;
                rm.setOnAction(ev -> { working.remove(idx); refreshRows[0].run(); });
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                HBox r = new HBox(10, lbl, sp, rm);
                r.setAlignment(Pos.CENTER_LEFT);
                rows.getChildren().add(r);
            }
        };
        refreshRows[0].run();
        addBtn.setOnAction(e -> {
            String v = valueField.getText().trim();
            if (v.isEmpty()) return;
            working.add(new FriendsData.Social(typeBox.getSelectionModel().getSelectedItem(), v));
            valueField.clear();
            refreshRows[0].run();
        });

        Button saveBtn = new Button("Save to Profile");
        saveBtn.getStyleClass().add("pill-button");
        Label status = new Label("");
        status.getStyleClass().add("notice-label");
        saveBtn.setOnAction(e -> {
            java.util.List<FriendsData.Social> snapshot = new java.util.ArrayList<>(working);
            saveBtn.setDisable(true);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    friendsService.updateSocials(active.uuid, active.username, snapshot);
                    publishOwnedServers(active); // also refresh your owned servers on each profile save
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                saveBtn.setDisable(false);
                status.setText("Saved -- visible to friends on your profile.");
            });
            task.setOnFailed(ev -> {
                saveBtn.setDisable(false);
                status.setText("Couldn't save: " + task.getException().getMessage());
            });
            new Thread(task, "socials-save").start();
        });

        HBox addRow = new HBox(8, typeBox, valueField, addBtn);
        HBox.setHgrow(valueField, Priority.ALWAYS);
        box.getChildren().addAll(addRow, rows, new HBox(10, saveBtn, status));
        return box;
    }

    /**
     * Owned-servers visibility for the friend profile: one checkbox per server this install owns, so
     * you choose exactly which servers friends see under "Servers they own" on your profile. Hiding a
     * server immediately republishes presence without it (see {@link #publishOwnedServers}).
     */
    private Node buildServerVisibilityEditor(PlayerIdentity active) {
        VBox box = new VBox(8);
        var servers = serverStore.listAll();
        if (servers.isEmpty()) {
            box.getChildren().add(noticeText("You don't host any servers yet -- create one on the "
                    + "Servers page, then choose here whether friends can see it."));
            return box;
        }
        Label note = new Label("Check a server to show it on your friend profile; uncheck it to keep it "
                + "completely private to this PC. Changes save instantly.");
        note.getStyleClass().add("notice-label");
        note.setWrapText(true);
        box.getChildren().add(note);
        for (var srv : servers) {
            CheckBox cb = new CheckBox(srv.name != null && !srv.name.isBlank() ? srv.name : "Unnamed server");
            cb.setSelected(srv.visibleToFriends);
            cb.selectedProperty().addListener((o, was, is) -> {
                srv.visibleToFriends = is;
                serverStore.save(srv);
                publishOwnedServers(active); // republish so friends see the change right away
            });
            box.getChildren().add(cb);
        }
        return box;
    }

    // ==================== SKINS TAB ====================

    /** LEFT: skin profile list + capes. RIGHT: 3D preview + Import/Remove. Matches the spec's layout sketch. */
    private HBox buildSkinsTab() {
        VBox left = new VBox(18);
        left.setPadding(new Insets(24, 12, 24, 24));
        left.setPrefWidth(320);
        left.setMinWidth(260);

        TilePane profilesBox = new TilePane(10, 10);
        profilesBox.setPrefColumns(2);
        profilesBox.setPrefTileWidth(112);
        profilesBox.setPrefTileHeight(132);
        TilePane capesBox = new TilePane(10, 10);
        capesBox.setPrefColumns(2);
        capesBox.setPrefTileWidth(112);
        capesBox.setPrefTileHeight(132);
        // Dey capes (DeyLauncher/DeyCapes, github-backed) shown under their own section --
        // they render only inside the DeyCapes mod, never to real Mojang capes.
        TilePane deyCapesBox = new TilePane(10, 10);
        deyCapesBox.setPrefColumns(2);
        deyCapesBox.setPrefTileWidth(112);
        deyCapesBox.setPrefTileHeight(132);

        ScrollPane leftScroll = new ScrollPane(new VBox(20,
                sectionLabel("SKIN PROFILES"), profilesBox,
                sectionLabel("CAPES"), capesBox,
                sectionLabel("DEY CAPES"), deyCapesBox));
        leftScroll.setFitToWidth(true);
        leftScroll.setFitToHeight(false);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        leftScroll.setPannable(true);
        leftScroll.getStyleClass().add("skins-left-scroll");
        VBox.setVgrow(leftScroll, Priority.ALWAYS);
        left.setMinHeight(0);
        left.getChildren().add(leftScroll);

        VBox right = new VBox(16);
        right.setPadding(new Insets(24));
        right.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(right, Priority.ALWAYS);

        skinPreview = new Skin3DPreview(420, 460);
        StackPane previewHost = new StackPane(skinPreview);
        previewHost.getStyleClass().add("skin-preview-host");
        VBox.setVgrow(previewHost, Priority.ALWAYS);

        ToggleGroup modelGroup = new ToggleGroup();
        RadioButton classicBtn = new RadioButton("Classic (Steve)");
        RadioButton slimBtn = new RadioButton("Slim (Alex)");
        classicBtn.setToggleGroup(modelGroup);
        slimBtn.setToggleGroup(modelGroup);
        HBox modelRow = new HBox(16, classicBtn, slimBtn);
        modelRow.setAlignment(Pos.CENTER);

        Button importBtn = new Button();
        setButtonIcon(importBtn, IconFactory.Icon.FOLDER, "Import Skin");
        importBtn.getStyleClass().add("pill-button");
        Button removeBtn = new Button();
        setButtonIcon(
            removeBtn,
            IconFactory.Icon.TRASH,
            "Remove / Restore"
        );
        removeBtn.getStyleClass().add("pill-button");
        Label capeLimitNote = new Label();
        capeLimitNote.getStyleClass().add("notice-label");
        capeLimitNote.setWrapText(true);
        capeLimitNote.setManaged(false);
        capeLimitNote.setVisible(false);

        HBox actionRow = new HBox(10, importBtn, removeBtn);
        actionRow.setAlignment(Pos.CENTER);
        Button applyCapeBtn = new Button();
        setButtonIcon(
                applyCapeBtn,
                IconFactory.Icon.CHECK,
                "APPLY CAPE"
        );
        applyCapeBtn.getStyleClass().add("settings-apply-button");
        updateCapeApplyButton(applyCapeBtn);
        right.getChildren().addAll(previewHost, modelRow, actionRow, applyCapeBtn, capeLimitNote);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> refreshSkinsTab(profilesBox, capesBox, deyCapesBox, refresh, classicBtn, slimBtn, applyCapeBtn);
        refresh[0].run();
        applyCapeBtn.setOnAction(e -> onApplyCape(applyCapeBtn, refresh));

        modelGroup.selectedToggleProperty().addListener((o, a, b) -> {
            PlayerIdentity act = identityStore.getActive();
            if (act == null || b == null) return;
            SkinModel newModel = (b == slimBtn) ? SkinModel.SLIM : SkinModel.CLASSIC;
            if (act.skinModel != newModel) {
                act.skinModel = newModel;
                identityStore.save(act);
                refresh[0].run();
            }
        });

        importBtn.setOnAction(e -> onImportSkinProfile(refresh));
        removeBtn.setOnAction(e -> onRemoveSkin(refresh));

        HBox root = new HBox(left, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        return root;
    }

    /** Rebuilds skin profile rows, cape rows, and the 3D preview from current disk/account state. */
    private void refreshSkinsTab(TilePane profilesBox, TilePane capesBox, TilePane deyCapesBox, Runnable[] refresh,
                                  RadioButton classicBtn, RadioButton slimBtn, Button applyCapeBtn) {
        profilesBox.getChildren().clear();
        capesBox.getChildren().clear();
        deyCapesBox.getChildren().clear();
        PlayerIdentity active = identityStore.getActive();

        if (active == null) {
            Label none = new Label("No account set up yet -- use the Account tab first.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            profilesBox.getChildren().add(none);
            skinPreview.update(defaultSteveImage(), SkinModel.CLASSIC, null);
            capeDirty = false;
            updateCapeApplyButton(applyCapeBtn);
            Label deyNone = new Label("Set up an account to manage Dey capes.");
            deyNone.getStyleClass().add("notice-label");
            deyNone.setWrapText(true);
            deyCapesBox.getChildren().add(deyNone);
            return;
        }
        (active.skinModel == SkinModel.SLIM ? slimBtn : classicBtn).setSelected(true);

        // ---- Skin profiles ----
        var profiles = identityStore.listSkinProfiles(active.uuid);
        if (profiles.isEmpty()) {
            Label none = new Label("No skins imported yet.");
            none.getStyleClass().add("notice-label");
            profilesBox.getChildren().add(none);
        } else {
            for (var p : profiles) {
                boolean selected = p.id.equals(active.activeSkinProfileId);
                Button tile = skinTile(p.name, faceThumbnail(skinImage(identityStore.skinProfileFile(active.uuid, p))), selected);
                tile.setOnAction(e -> {
                    try {
                        identityStore.selectSkinProfile(active, p.id);
                        if (active.accountType == AccountType.ONLINE) {
                            // selectSkinProfile() already copied this profile's file into the
                            // account's active skin.png -- upload that, not the library copy.
                            uploadActiveSkinIfOnline(active, identityStore.skinFile(active.uuid));
                        }
                        refreshAccountButton();
                        refresh[0].run();
                    } catch (java.io.IOException ex) {
                        new Alert(Alert.AlertType.ERROR, "Couldn't select skin: " + ex.getMessage(), ButtonType.OK).showAndWait();
                    }
                });
                profilesBox.getChildren().add(tile);
            }
        }

        // ---- Capes: honest -- only real, owned capes from the account's live Minecraft profile ----
        if (active.accountType != AccountType.ONLINE) {
            Label note = new Label("Capes are only available for signed-in Microsoft accounts.");
            note.getStyleClass().add("notice-label");
            note.setWrapText(true);
            capesBox.getChildren().add(note);
        } else if (liveOnlineAccessToken == null || !active.uuid.equals(liveOnlineAccountUuid)) {
            Label note = new Label("Sign in with Microsoft again this run to load your real capes.");
            note.getStyleClass().add("notice-label");
            note.setWrapText(true);
            capesBox.getChildren().add(note);
        } else {
            Label loading = new Label("Loading capes...");
            loading.getStyleClass().add("notice-label");
            capesBox.getChildren().add(loading);
            String tokenSnapshot = liveOnlineAccessToken;
            Task<MinecraftSkinService.CapesResult> task = new Task<>() {
                @Override
                protected MinecraftSkinService.CapesResult call() {
                    return new MinecraftSkinService().fetchCapes(tokenSnapshot);
                }
            };
            task.setOnSucceeded(e -> {
                capesBox.getChildren().clear();
                var result = task.getValue();
                if (!result.success()) {
                    Label err = new Label(result.message());
                    err.getStyleClass().add("notice-label");
                    err.setWrapText(true);
                    capesBox.getChildren().add(err);
                } else if (result.capes().isEmpty()) {
                    Label none = new Label("This account doesn't own any capes.");
                    none.getStyleClass().add("notice-label");
                    capesBox.getChildren().add(none);
                } else {
                    MinecraftSkinService.CapeInfo activeCape = result.capes().stream()
                            .filter(MinecraftSkinService.CapeInfo::active).findFirst().orElse(null);
                    equippedCapeId = activeCape == null ? null : activeCape.id();
                    if (!capeDirty) {
                        selectedCapeId = equippedCapeId;
                        selectedCapeImage = null;
                    }
                    Button noneTile = skinTile("No cape", transparentCapeImage(), selectedCapeId == null);
                    noneTile.getStyleClass().add("cape-tile");
                    noneTile.setOnAction(ev -> chooseNoCape(applyCapeBtn, refresh));
                    capesBox.getChildren().add(noneTile);
                    for (var cape : result.capes()) {
                        // Load the source sheet once, but show only its front cape panel in
                        // the selector. A full 64x32 texture sheet is not useful as a tile.
                        Image image = cape.url() == null ? null : new Image(cape.url(), false);
                        if (cape.id().equals(selectedCapeId)) {
                            selectedCapeImage = image;
                        }
                        Button tile = skinTile(cape.alias(), capeFrontImage(image), cape.id().equals(selectedCapeId));
                        tile.getStyleClass().add("cape-tile");
                        tile.setOnAction(ev -> chooseCape(cape, image, applyCapeBtn, refresh));
                        capesBox.getChildren().add(tile);
                    }
                    updateCapeApplyButton(applyCapeBtn);
                }
            });
            task.setOnFailed(e -> {
                capesBox.getChildren().clear();
                Label err = new Label("Couldn't load capes: " + task.getException());
                err.getStyleClass().add("notice-label");
                err.setWrapText(true);
                capesBox.getChildren().add(err);
            });
            new Thread(task, "cape-fetch").start();
        }

        // ---- Dey capes: github-backed, owned-only, rendered by the DeyCapes mod ----
        refreshDeyCapes(deyCapesBox, active, applyCapeBtn, refresh);

        // ---- 3D preview ----
        java.nio.file.Path skinPath = identityStore.skinFile(active.uuid);
        Image skinImage = (active.skinSource != SkinSource.DEFAULT && java.nio.file.Files.exists(skinPath))
                ? new Image(skinPath.toUri().toString()) : defaultSteveImage();
        skinPreview.update(skinImage, active.skinModel, selectedCapeImage);
    }

    private Image skinImage(java.nio.file.Path path) {
        return java.nio.file.Files.exists(path) ? new Image(path.toUri().toString(), false) : defaultSteveImage();
    }

    /** A locally-loaded Dey cape candidate ready to render as a library tile. */
    private record DeyCapeRow(String id, String name, Image image) {}

    /** Populates the DEY CAPES section: only capes the active player owns, per the github ownership file. */
    private void refreshDeyCapes(TilePane deyCapesBox, PlayerIdentity active, Button applyCapeBtn, Runnable[] refresh) {
        if (deyCapesService == null || !deyCapesService.configured()) {
            Label note = new Label("Dey capes need GitHub set up (see GITHUB_SETUP.md in the repo root).");
            note.getStyleClass().add("notice-label");
            note.setWrapText(true);
            deyCapesBox.getChildren().add(note);
            return;
        }
        String onlineUuid = (active.accountType == AccountType.ONLINE) ? active.uuid : null;
        String offlineUuid = DeyCapesService.offlineUuid(active.username);
        Label loading = new Label("Loading your Dey capes...");
        loading.getStyleClass().add("notice-label");
        deyCapesBox.getChildren().add(loading);

        Task<List<DeyCapeRow>> task = new Task<>() {
            @Override
            protected List<DeyCapeRow> call() throws Exception {
                List<DeyCapesService.DeyCape> all = deyCapesService.allCapes();
                List<String> owned = deyCapesService.ownedCapeIds(active.username, onlineUuid, offlineUuid);
                List<DeyCapeRow> rows = new ArrayList<>();
                for (var c : all) {
                    if (!owned.contains(c.id())) continue;
                    Image img = null;
                    try {
                        img = new Image(deyCapesService.readCapeTexture(c.texturePath()));
                    } catch (Exception ignored) {
                    }
                    rows.add(new DeyCapeRow("dey:" + c.id(), c.name(), capeFrontImage(img)));
                }
                return rows;
            }
        };
        task.setOnSucceeded(e -> {
            deyCapesBox.getChildren().clear();
            var rows = task.getValue();
            if (rows.isEmpty()) {
                Label none = new Label("This account doesn't own any Dey capes yet.\n"
                        + "They're granted via the GitHub capes-owned.json file.");
                none.getStyleClass().add("notice-label");
                none.setWrapText(true);
                deyCapesBox.getChildren().add(none);
                return;
            }
            for (var row : rows) {
                boolean selected = row.id.equals(selectedCapeId);
                Button tile = skinTile(row.name, row.image, selected);
                tile.getStyleClass().add("cape-tile");
                tile.setOnAction(ev -> chooseDeyCape(row.id, row.image, applyCapeBtn, refresh));
                deyCapesBox.getChildren().add(tile);
            }
        });
        task.setOnFailed(e -> {
            deyCapesBox.getChildren().clear();
            Label err = new Label("Couldn't load Dey capes: " + task.getException().getMessage());
            err.getStyleClass().add("notice-label");
            err.setWrapText(true);
            deyCapesBox.getChildren().add(err);
        });
        new Thread(task, "dey-capes-fetch").start();
    }

    /** Minecraft's cape front panel is the 10x16 rectangle after the one-pixel side strip. */
    private Image capeFrontImage(Image cape) {
        if (cape == null || cape.getPixelReader() == null || cape.getWidth() < 11 || cape.getHeight() < 17) {
            return defaultSteveImage();
        }
        return new javafx.scene.image.WritableImage(cape.getPixelReader(), 1, 1, 10, 16);
    }

    private Image transparentCapeImage() {
        return new javafx.scene.image.WritableImage(10, 16);
    }

    /** A compact square library card. ImageView is deliberately unsmoothed so skin pixels stay crisp. */
    private Button skinTile(String title, Image image, boolean selected) {
        ImageView preview = new ImageView(image == null ? defaultSteveImage() : image);
        preview.setFitWidth(76);
        preview.setFitHeight(76);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        Label label = new Label(title);
        label.setWrapText(true);
        label.setMaxWidth(92);
        label.setAlignment(Pos.CENTER);
        VBox content = new VBox(7, preview, label);
        content.setAlignment(Pos.CENTER);
        Button tile = new Button();
        tile.setGraphic(content);
        tile.getStyleClass().add("skin-library-tile");
        if (selected) tile.getStyleClass().add("skin-library-tile-selected");
        return tile;
    }

    /** Picks a cape locally. Mojang is contacted only by the explicit Apply button. */
    private void chooseCape(MinecraftSkinService.CapeInfo cape, Image image, Button applyCapeBtn, Runnable[] refresh) {
        selectedCapeId = cape.id();
        selectedCapeImage = image;
        capeDirty = !java.util.Objects.equals(selectedCapeId, equippedCapeId);
        updateCapeApplyButton(applyCapeBtn);
        refresh[0].run();
    }

    private void chooseDeyCape(String deyCapeId, Image image, Button applyCapeBtn, Runnable[] refresh) {
        selectedCapeId = deyCapeId;
        selectedCapeImage = image;
        capeDirty = !java.util.Objects.equals(selectedCapeId, equippedCapeId);
        updateCapeApplyButton(applyCapeBtn);
        refresh[0].run();
    }

    private void chooseNoCape(Button applyCapeBtn, Runnable[] refresh) {
        selectedCapeId = null;
        selectedCapeImage = null;
        capeDirty = equippedCapeId != null;
        updateCapeApplyButton(applyCapeBtn);
        refresh[0].run();
    }

    private void updateCapeApplyButton(Button button) {
        button.getStyleClass().remove("settings-apply-button-ready");
        if (capeDirty) {
            button.getStyleClass().add("settings-apply-button-ready");
            button.setDisable(false);
            setButtonIcon(
                button,
                IconFactory.Icon.CHECK,
                "APPLY CAPE CHANGE"
            );
        } else {
            button.setDisable(true);
            setButtonIcon(
                button,
                IconFactory.Icon.CHECK,
                "CAPE APPLIED"
            );
        }
    }

    /** Commits the staged cape selection with one request, avoiding rate-limit spam. */
    private void onApplyCape(Button applyCapeBtn, Runnable[] refresh) {
        if (!capeDirty) return;
        final String targetCapeId = selectedCapeId;
        final PlayerIdentity active = identityStore.getActive();

        // Dey capes (id "dey:<slug>") are DeyLauncher/DeyCapes-only -- applied by writing the
        // equipped cape to the shared github file, never by touching the player's real Mojang account.
        final boolean isDeyCape = targetCapeId != null && targetCapeId.startsWith("dey:");
        // "No cape" while a Dey cape was equipped clears the github assignment instead of sending
        // a pointless (and failing, for offline accounts) Mojang unequip request.
        final boolean clearDeyCape = targetCapeId == null && equippedCapeId != null
                && equippedCapeId.startsWith("dey:");

        if (isDeyCape || clearDeyCape) {
            Task<MinecraftSkinService.SkinChangeResult> task = new Task<>() {
                @Override protected MinecraftSkinService.SkinChangeResult call() {
                    try {
                        String slug = targetCapeId == null ? null : targetCapeId.substring("dey:".length());
                        boolean online = active != null && active.accountType == AccountType.ONLINE;
                        String onlineUuid = online ? active.uuid : null;
                        deyCapesService.equipCape(active.username, slug, online, onlineUuid);
                        return new MinecraftSkinService.SkinChangeResult(true, "Dey cape updated.");
                    } catch (Exception ex) {
                        return new MinecraftSkinService.SkinChangeResult(false, "Couldn't update Dey cape: " + ex.getMessage());
                    }
                }
            };
            task.setOnSucceeded(e -> {
                var result = task.getValue();
                if (result.success()) {
                    equippedCapeId = targetCapeId;
                    capeDirty = false;
                    updateCapeApplyButton(applyCapeBtn);
                    refresh[0].run();
                } else {
                    new Alert(Alert.AlertType.WARNING, result.message() + " Your selection is still ready to Apply.", ButtonType.OK).showAndWait();
                }
            });
            task.setOnFailed(e -> new Alert(Alert.AlertType.WARNING,
                    "Couldn't update the Dey cape. Your current cape is unchanged.", ButtonType.OK).showAndWait());
            new Thread(task, "cape-apply-dey").start();
            return;
        }

        String mojangToken = liveOnlineAccessToken;
        Task<MinecraftSkinService.SkinChangeResult> task = new Task<>() {
            @Override protected MinecraftSkinService.SkinChangeResult call() {
                return targetCapeId == null
                        ? new MinecraftSkinService().unequipCape(mojangToken)
                        : new MinecraftSkinService().equipCape(mojangToken, targetCapeId);
            }
        };
        task.setOnSucceeded(e -> {
            var result = task.getValue();
            if (result.success()) {
                equippedCapeId = targetCapeId;
                capeDirty = false;
                updateCapeApplyButton(applyCapeBtn);
                refresh[0].run();
            } else {
                new Alert(Alert.AlertType.WARNING, result.message() + " Your selection is still ready to Apply.", ButtonType.OK).showAndWait();
            }
        });
        task.setOnFailed(e -> new Alert(Alert.AlertType.WARNING,
                "Couldn't apply the cape change. Your current cape is unchanged.", ButtonType.OK).showAndWait());
        new Thread(task, "cape-apply").start();
    }

    private void onImportSkinProfile(Runnable[] refresh) {
        PlayerIdentity active = identityStore.getActive();
        if (active == null) {
            new Alert(Alert.AlertType.WARNING, "Set up an account in the Account tab first.", ButtonType.OK).showAndWait();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Minecraft Skin");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG images", "*.png"));
        var file = chooser.showOpenDialog(stage);
        if (file == null) return;

        SkinValidator.Result result = new SkinValidator().validate(file.toPath());
        if (!result.valid()) {
            new Alert(Alert.AlertType.ERROR, result.reason(), ButtonType.OK).showAndWait();
            return;
        }

        TextInputDialog nameDialog = new TextInputDialog(file.getName().replaceFirst("(?i)\\.png$", ""));
        nameDialog.setTitle("Name this skin");
        nameDialog.setHeaderText(null);
        nameDialog.setContentText("Skin profile name:");
        var nameResult = nameDialog.showAndWait();
        String name = nameResult.orElse("").trim();

        try {
            identityStore.addSkinProfile(active, name, file.toPath(), active.skinModel);
            if (active.accountType == AccountType.ONLINE) {
                uploadActiveSkinIfOnline(active, file.toPath());
            }
            refreshAccountButton();
            refresh[0].run();
        } catch (java.io.IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Couldn't save skin: " + ex.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    /** Online accounts get the same real Mojang upload the old single-skin flow used, on top of the new local library entry. */
    private void uploadActiveSkinIfOnline(PlayerIdentity active, java.nio.file.Path pngFile) {
        if (liveOnlineAccessToken == null || !active.uuid.equals(liveOnlineAccountUuid)) {
            new Alert(Alert.AlertType.WARNING,
                    "Saved locally, but sign in with Microsoft again this run to also apply it to "
                            + "your real online skin.", ButtonType.OK).showAndWait();
            return;
        }
        Task<MinecraftSkinService.SkinChangeResult> task = new Task<>() {
            @Override
            protected MinecraftSkinService.SkinChangeResult call() {
                return new MinecraftSkinService().uploadSkin(liveOnlineAccessToken, pngFile, active.skinModel);
            }
        };
        task.setOnSucceeded(e -> {
            var r = task.getValue();
            if (r.success()) {
                active.skinSource = SkinSource.MOJANG_ONLINE;
                identityStore.save(active);
            }
            new Alert(r.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING, r.message(), ButtonType.OK).showAndWait();
        });
        new Thread(task, "skin-upload").start();
    }

    private void onRemoveSkin(Runnable[] refresh) {
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        if (active.accountType == AccountType.OFFLINE) {
            try {
                identityStore.removeCustomSkin(active);
                refreshAccountButton();
                refresh[0].run();
            } catch (java.io.IOException ex) {
                new Alert(Alert.AlertType.ERROR, "Couldn't remove skin: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        } else {
            if (liveOnlineAccessToken == null || !active.uuid.equals(liveOnlineAccountUuid)) {
                new Alert(Alert.AlertType.WARNING,
                        "Sign in with Microsoft again in this session before resetting your online skin.",
                        ButtonType.OK).showAndWait();
                return;
            }
            Task<MinecraftSkinService.SkinChangeResult> task = new Task<>() {
                @Override
                protected MinecraftSkinService.SkinChangeResult call() {
                    return new MinecraftSkinService().resetToDefault(liveOnlineAccessToken);
                }
            };
            task.setOnSucceeded(e -> {
                var r = task.getValue();
                if (r.success()) {
                    active.skinSource = SkinSource.DEFAULT;
                    identityStore.save(active);
                }
                refreshAccountButton();
                refresh[0].run();
                new Alert(r.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING, r.message(), ButtonType.OK).showAndWait();
            });
            new Thread(task, "skin-reset").start();
        }
    }

    /** Built-in pixel-art Steve-compatible fallback. It is a real 64x64 skin layout, not a gray blur. */
    private Image defaultSteveImage() {
        int size = 64;
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(size, size);
        var writer = img.getPixelWriter();
        Color transparent = Color.TRANSPARENT;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                writer.setColor(x, y, transparent);
            }
        }
        // Paint each standard 64x64 skin region; colours are intentionally simple but the
        // UV layout is valid, so the account face and 3D body both have a recognisable skin.
        fill(writer, 8, 8, 8, 8, Color.web("#dca57a"));      // face front
        fill(writer, 0, 8, 8, 8, Color.web("#704b35"));      // head side
        fill(writer, 16, 8, 8, 8, Color.web("#704b35"));     // head side
        fill(writer, 8, 0, 8, 8, Color.web("#704b35"));      // hair/top
        fill(writer, 20, 20, 8, 12, Color.web("#4c78c4"));   // torso front
        fill(writer, 4, 20, 4, 12, Color.web("#4c78c4"));    // torso side
        fill(writer, 28, 20, 4, 12, Color.web("#4c78c4"));
        fill(writer, 44, 20, 4, 12, Color.web("#dca57a"));   // right arm front
        fill(writer, 40, 20, 4, 12, Color.web("#dca57a"));
        fill(writer, 4, 20, 4, 12, Color.web("#3d5d9e"));    // legs front
        fill(writer, 20, 52, 4, 12, Color.web("#3d5d9e"));
        // eyes on the visible face
        fill(writer, 10, 11, 2, 2, Color.web("#2d354d"));
        fill(writer, 14, 11, 2, 2, Color.web("#2d354d"));
        return img;
    }

    private void fill(javafx.scene.image.PixelWriter writer, int x, int y, int w, int h, Color color) {
        for (int ix = x; ix < x + w; ix++) for (int iy = y; iy < y + h; iy++) writer.setColor(ix, iy, color);
    }

    private void onSignInWithMicrosoft(Button signInBtn, VBox deviceCodeBox, Label statusLabel, Runnable[] refreshHolder) {
        signInBtn.setDisable(true);
        deviceCodeBox.getChildren().clear();
        statusLabel.setText("Starting Microsoft sign-in...");

        Task<MicrosoftAuth.MinecraftSession> task = new Task<>() {
            @Override
            protected MicrosoftAuth.MinecraftSession call() throws Exception {
                MicrosoftAuth auth = new MicrosoftAuth();
                return auth.login((verificationUri, userCode, expiresInSeconds) ->
                        Platform.runLater(() -> {
                            statusLabel.setText("Waiting for you to finish signing in...");
                            deviceCodeBox.getChildren().setAll(buildDeviceCodePane(verificationUri, userCode));
                        }));
            }
        };
        task.setOnSucceeded(e -> {
            var ms = task.getValue();
            liveOnlineAccessToken = ms.minecraftAccessToken();
            liveOnlineAccountUuid = ms.uuid();
            PlayerIdentity identity = identityStore.loadOrCreate(ms.uuid(), ms.username(), AccountType.ONLINE);
            try {
                identityStore.setMojangOnlineSkin(identity, ms.currentSkin(), ms.skinModel());
                if (ms.refreshToken() != null) identity.refreshTokenCiphertext = TokenVault.encrypt(ms.refreshToken());
                identityStore.save(identity);
            } catch (java.io.IOException ignored) {
                // Login remains valid even if a local preview cache cannot be written.
            } catch (Exception ignored) {
                // A session still works for this run if the local vault is unavailable.
            }
            identityStore.setActive(identity.uuid);
            signInBtn.setDisable(false);
            deviceCodeBox.getChildren().clear();
            statusLabel.setText("Signed in as " + ms.username() + " -- Play will use this real session.");
            syncPlayCardFromActiveIdentity();
            refreshAccountButton();
            refreshHolder[0].run();
        });
        task.setOnFailed(e -> {
            signInBtn.setDisable(false);
            deviceCodeBox.getChildren().clear();
            statusLabel.setText("Sign-in failed: " + task.getException().getMessage());
        });
        new Thread(task, "microsoft-signin").start();
    }

    /** Rehydrates a remembered Microsoft account at startup. Failure is non-destructive: the
     * account remains selected and the user can simply sign in again. */
    private void restoreOnlineSessionAsync() {
        PlayerIdentity active = identityStore.getActive();
        if (active == null || active.accountType != AccountType.ONLINE || active.refreshTokenCiphertext == null) return;
        Task<MicrosoftAuth.MinecraftSession> task = new Task<>() {
            @Override protected MicrosoftAuth.MinecraftSession call() throws Exception {
                return new MicrosoftAuth().resume(TokenVault.decrypt(active.refreshTokenCiphertext));
            }
        };
        task.setOnSucceeded(e -> {
            var ms = task.getValue();
            liveOnlineAccessToken = ms.minecraftAccessToken();
            liveOnlineAccountUuid = ms.uuid();
            PlayerIdentity updated = identityStore.loadOrCreate(ms.uuid(), ms.username(), AccountType.ONLINE);
            try {
                identityStore.setMojangOnlineSkin(updated, ms.currentSkin(), ms.skinModel());
                if (ms.refreshToken() != null) updated.refreshTokenCiphertext = TokenVault.encrypt(ms.refreshToken());
                identityStore.save(updated);
            } catch (Exception ignored) { }
            syncPlayCardFromActiveIdentity();
            refreshAccountButton();
        });
        new Thread(task, "microsoft-session-restore").start();
    }

    /**
     * The actual sign-in visual: "Open <clickable, underlined, selectable link>" then a large,
     * unmissable code with its own Copy button. Two controls represent the same URL on purpose --
     * a Hyperlink (click to open in the system browser via JavaFX's HostServices) plus a read-only
     * TextField underneath holding the identical text (so it can be selected/highlighted and
     * copied like normal text, which a Hyperlink's button-style label cannot do).
     */
    private VBox buildDeviceCodePane(String verificationUri, String userCode) {
        Label step1 = new Label("1. Open:");
        step1.getStyleClass().add("field-label");

        Hyperlink link = new Hyperlink(verificationUri);
        link.getStyleClass().add("device-link");
        link.setOnAction(e -> getHostServices().showDocument(verificationUri));

        TextField urlSelectable = new TextField(verificationUri);
        urlSelectable.setEditable(false);
        urlSelectable.getStyleClass().add("device-url-field");

        Label step2 = new Label("2. Enter this code:");
        step2.getStyleClass().add("field-label");

        Label codeLabel = new Label(userCode);
        codeLabel.getStyleClass().add("device-code-label");

        Button copyBtn = new Button();
        setButtonIcon(
            copyBtn,
            IconFactory.Icon.CLIPBOARD,
            "Copy Code"
        );
        copyBtn.getStyleClass().add("pill-button");
        copyBtn.setOnAction(e -> {
            var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            var contentToCopy = new javafx.scene.input.ClipboardContent();
            contentToCopy.putString(userCode);
            clipboard.setContent(contentToCopy);
            setButtonIcon(
                copyBtn,
                IconFactory.Icon.CHECK,
                "Copied!"
            );
        });

        VBox box = new VBox(8, step1, link, urlSelectable, step2, codeLabel, copyBtn);
        box.getStyleClass().add("device-code-box");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }


    // ---- Settings dialog: Game tab (RAM/resolution/fullscreen) + Launcher tab (scale/font/window) ----
    // Game-tab fields stage their changes locally and only take effect (and get persisted to
    // LauncherPrefs) when APPLY is pressed. Launcher-tab fields already live-preview the window
    // as you drag them (existing behavior, kept) -- APPLY additionally guarantees everything is
    // written to disk together, with one clear confirmation, rather than relying on each control's
    // own listener to have saved correctly.
    private void openSettingsDialog() {
        openPreferencesDialog("Game");
    }

    /** One unified Settings window: Account, Skins, Game, and Launcher as tabs -- instead of
     * two separate popup windows -- so there's a single place for all of this, and it opens
     * already on whichever tab makes sense for how it was invoked. Uses the same borderless
     * window style as the Mods window. */
    private void openPreferencesDialog(String initialTabTitle) {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // ---- Account ----
        ScrollPane accountScroll = new ScrollPane();
        accountScroll.setFitToWidth(true);
        accountScroll.getStyleClass().add("account-tab-scroll");
        Tab accountTab = new Tab("Account", accountScroll);
        Runnable[] refreshAccountHolder = new Runnable[1];
        refreshAccountHolder[0] = () -> accountScroll.setContent(buildAccountTab(refreshAccountHolder));
        refreshAccountHolder[0].run();

        // ---- Skins ----
        Tab skinsTab = new Tab("Skins", buildSkinsTab());

        // ---- Game ----
        GameLauncher.LaunchSettings[] pending = { settings };
        SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        Runnable markDirty = () -> dirty.set(true);

        ScrollPane gameScroll = new ScrollPane(buildGameSettingsPane(pending, markDirty));
        gameScroll.setFitToWidth(true);
        gameScroll.getStyleClass().add("settings-scroll");
        Tab gameTab = new Tab("Game", gameScroll);

        tabs.getTabs().addAll(accountTab, skinsTab, gameTab);

        Label savedLabel = new Label();
        savedLabel.getStyleClass().add("notice-label");

        Button applyBtn = new Button();
        setButtonIcon(
            applyBtn,
            IconFactory.Icon.CHECK,
            "APPLY"
        );
        applyBtn.getStyleClass().add("settings-apply-button");
        dirty.addListener((obs, old, changed) -> {
            if (changed) {
                if (!applyBtn.getStyleClass().contains("settings-apply-button-ready"))
                    applyBtn.getStyleClass().add("settings-apply-button-ready");
                setButtonIcon(
                    applyBtn,
                    IconFactory.Icon.CHECK,
                    "APPLY CHANGES"
                );
            } else {
                applyBtn.getStyleClass().remove("settings-apply-button-ready");
                setButtonIcon(
                    applyBtn,
                    IconFactory.Icon.CHECK,
                    "APPLY"
                );
            }
        });
        applyBtn.setOnAction(e -> {
            settings = pending[0];
            prefs.ramMinMb = settings.ramMinMb();
            prefs.ramMaxMb = settings.ramMaxMb();
            prefs.gameWidth = settings.width();
            prefs.gameHeight = settings.height();
            prefs.fullscreen = settings.fullscreen();
            prefs.softwareOpenGl = settings.softwareOpenGl();
            prefs.nativeWayland = settings.nativeWayland();
            prefs.save();
            savedLabel.setText("Settings saved.");
            dirty.set(false);
        });

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(12, savedLabel, actionSpacer, applyBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(0, 18, 0, 18));
        VBox root = new VBox(14, tabs, actions);
        root.setPadding(new Insets(0, 0, 18, 0));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // ---- Launcher (built after root so its theme toggle can re-theme this whole window) ----
        ScrollPane launcherScroll = new ScrollPane(buildLauncherSettingsPane(markDirty, () -> {
            javafx.scene.layout.BorderPane wroot = stageShellRoot("settings");
            if (wroot == null) return;
            wroot.getStyleClass().removeAll("theme-dark", "theme-light");
            wroot.getStyleClass().add(darkMode ? "theme-dark" : "theme-light");
            wroot.getStylesheets().removeIf(s -> s.startsWith("data:text/css"));
            wroot.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        }));
        launcherScroll.setFitToWidth(true);
        launcherScroll.getStyleClass().add("settings-scroll");
        Tab launcherTab = new Tab("Launcher", launcherScroll);
        tabs.getTabs().add(launcherTab);

        for (Tab t : tabs.getTabs()) {
            if (t.getText().equals(initialTabTitle)) {
                tabs.getSelectionModel().select(t);
                break;
            }
        }

        // Configurable from Launcher > Settings Window Size -- defaults big enough for the
        // Skins tab's profile/cape list + large 3D preview to actually have room to breathe.
        Stage win = openShellWindow("settings", "Settings", root,
                760, 520, prefs.settingsWindowWidth, prefs.settingsWindowHeight);
        win.setOnHiding(e -> { if (skinPreview != null) skinPreview.stop(); });
    }


    private GridPane buildGameSettingsPane(GameLauncher.LaunchSettings[] pending, Runnable markDirty) {
        Slider ramSlider = new Slider(1024, 16384, settings.ramMaxMb());
        ramSlider.setShowTickLabels(true);
        ramSlider.setShowTickMarks(true);
        ramSlider.setMajorTickUnit(4096);
        ramSlider.setPrefWidth(320);
        Label ramLabel = new Label(settings.ramMaxMb() + " MB max RAM");
        ramLabel.getStyleClass().add("settings-value-label");
        ramSlider.valueProperty().addListener((obs, old, val) -> {
            markDirty.run();
            ramLabel.setText((int) val.doubleValue() + " MB max RAM");
            pending[0] = new GameLauncher.LaunchSettings(pending[0].ramMinMb(), (int) val.doubleValue(),
                    pending[0].width(), pending[0].height(), pending[0].fullscreen(), pending[0].softwareOpenGl(),
                    pending[0].nativeWayland());
        });

        TextField widthField = new TextField(String.valueOf(settings.width()));
        TextField heightField = new TextField(String.valueOf(settings.height()));
        widthField.setPrefWidth(90);
        heightField.setPrefWidth(90);
        Runnable applyRes = () -> pending[0] = new GameLauncher.LaunchSettings(pending[0].ramMinMb(), pending[0].ramMaxMb(),
                parseIntOr(widthField.getText(), pending[0].width()),
                parseIntOr(heightField.getText(), pending[0].height()), pending[0].fullscreen(), pending[0].softwareOpenGl(),
                pending[0].nativeWayland());
        widthField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });
        heightField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });

        CheckBox fullscreenBox = new CheckBox("Launch fullscreen");
        fullscreenBox.setSelected(settings.fullscreen());
        fullscreenBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(), b,
                pending[0].softwareOpenGl(), pending[0].nativeWayland()));
        fullscreenBox.selectedProperty().addListener((o, a, b) -> markDirty.run());

        CheckBox softwareGlBox = new CheckBox("Software rendering (compatibility)");
        softwareGlBox.setSelected(settings.softwareOpenGl());
        softwareGlBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(),
                pending[0].fullscreen(), b, pending[0].nativeWayland()));
        softwareGlBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        Label softwareGlHint = new Label("For machines whose GPU can't expose OpenGL 3.3 "
                + "(\"GLXBadFBConfig\" / \"Driver does not support OpenGL 3.3\"). Renders on the CPU "
                + "(slower but works) without admin rights. Linux only; no effect on Windows.");
        softwareGlHint.setWrapText(true);
        softwareGlHint.getStyleClass().add("settings-hint-label");

        CheckBox waylandBox = new CheckBox("Run natively on Wayland");
        waylandBox.setSelected(settings.nativeWayland());
        waylandBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(),
                pending[0].fullscreen(), pending[0].softwareOpenGl(), b));
        waylandBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        Label waylandHint = new Label("On a Wayland session the game is otherwise started through XWayland "
                + "(GLFW picks X11 whenever DISPLAY is set). This starts it on Wayland's own driver path "
                + "instead, which is what fixes a native crash at window creation -- software rendering cannot, "
                + "because that fault happens before any GPU work. On by default in a Wayland session; needs a "
                + "GLFW with Wayland support (Minecraft 1.20 and newer). Ignored on Windows and on X11.");
        waylandHint.setWrapText(true);
        waylandHint.getStyleClass().add("settings-hint-label");

        Button optionsKitsBtn = new Button();
        setButtonIcon(optionsKitsBtn, IconFactory.Icon.SETTINGS, "Option Kits");
        optionsKitsBtn.getStyleClass().add("pill-button");
        optionsKitsBtn.setOnAction(e -> openOptionsKitsDialog());
        Label optionsKitsHint = new Label("Save and swap between full options.txt presets -- "
                + "per-version or cross-version -- instead of hand-editing settings every time you switch.");
        optionsKitsHint.setWrapText(true);
        optionsKitsHint.getStyleClass().add("settings-hint-label");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(18);
        grid.setPadding(new Insets(24));
        grid.add(sectionLabel("MEMORY"), 0, 0, 2, 1);
        grid.add(ramLabel, 0, 1, 2, 1);
        grid.add(ramSlider, 0, 2, 2, 1);
        grid.add(sectionLabel("WINDOW"), 0, 3, 2, 1);
        grid.add(new Label("Resolution:"), 0, 4);
        HBox resBox = new HBox(6, widthField, new Label("x"), heightField);
        resBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(resBox, 1, 4);
        grid.add(fullscreenBox, 0, 5, 2, 1);
        grid.add(sectionLabel("COMPATIBILITY"), 0, 6, 2, 1);
        grid.add(softwareGlBox, 0, 7, 2, 1);
        grid.add(softwareGlHint, 0, 8, 2, 1);
        grid.add(waylandBox, 0, 9, 2, 1);
        grid.add(waylandHint, 0, 10, 2, 1);
        grid.add(sectionLabel("OPTION KITS"), 0, 11, 2, 1);
        grid.add(optionsKitsBtn, 0, 12, 2, 1);
        grid.add(optionsKitsHint, 0, 13, 2, 1);
        return grid;
    }

    private GridPane buildLauncherSettingsPane(Runnable markDirty, Runnable restyleWindow) {
        ToggleButton themeToggle = new ToggleButton();
        setButtonIcon(
            themeToggle,
            darkMode
                ? IconFactory.Icon.MOON
                : IconFactory.Icon.SUN,
            darkMode ? "Dark" : "Light"
        );
        themeToggle.getStyleClass().add("pill-button");
        themeToggle.setSelected(darkMode);
        themeToggle.setOnAction(e -> {
            darkMode = themeToggle.isSelected();
            setButtonIcon(
                themeToggle,
                darkMode
                    ? IconFactory.Icon.MOON
                    : IconFactory.Icon.SUN,
                darkMode ? "Dark" : "Light"
            );
            prefs.darkMode = darkMode;
            prefs.save();
            applyTheme();
            // applyTheme() only re-themes the main window's scene -- this borderless window
            // is its own top-level Stage, so it needs its own style classes swapped too, right
            // now, instead of waiting for the user to close and reopen Settings.
            restyleWindow.run();
        });

        Slider uiScaleSlider = new Slider(0.75, 2.5, prefs.uiScale);
        uiScaleSlider.setShowTickMarks(true);
        uiScaleSlider.setMajorTickUnit(0.25);
        uiScaleSlider.setPrefWidth(300);
        Label uiScaleLabel = new Label(Math.round(prefs.uiScale * 100) + "%");
        uiScaleLabel.getStyleClass().add("settings-value-label");

        Slider textScaleSlider = new Slider(0.75, 2.5, prefs.textScale);
        textScaleSlider.setShowTickMarks(true);
        textScaleSlider.setMajorTickUnit(0.25);
        textScaleSlider.setPrefWidth(300);
        Label textScaleLabel = new Label(Math.round(prefs.textScale * 100) + "%");
        textScaleLabel.getStyleClass().add("settings-value-label");

        ComboBox<String> fontBox = new ComboBox<>();
        fontBox.getItems().addAll("Segoe UI", "Inter", "Helvetica Neue", "Roboto", "Arial", "Consolas");
        fontBox.setValue(prefs.fontFamily);
        fontBox.getStyleClass().add("input-field");

        // Live preview: every slider/combo change re-renders the actual window immediately,
        // including this dialog itself, so the effect is visible before hitting APPLY -- APPLY
        // (in openSettingsDialog) still does an explicit prefs.save() afterwards regardless.
        Runnable livePreview = () -> {
            markDirty.run();
            prefs.uiScale = uiScaleSlider.getValue();
            prefs.textScale = textScaleSlider.getValue();
            prefs.fontFamily = fontBox.getValue();
            uiScaleLabel.setText(Math.round(prefs.uiScale * 100) + "%");
            textScaleLabel.setText(Math.round(prefs.textScale * 100) + "%");
            applyDynamicStyle();
            // applyDynamicStyle() only touches the main window's scene -- this borderless window
            // carries its own separate copy of the same data: URI stylesheet, added once at open
            // time, so it needs to be swapped out here too or the scale only visibly changes on the
            // window behind it until you close and reopen Settings.
            restyleWindow.run();
            prefs.save();
        };
        uiScaleSlider.valueProperty().addListener((o, a, b) -> livePreview.run());
        textScaleSlider.valueProperty().addListener((o, a, b) -> livePreview.run());
        fontBox.valueProperty().addListener((o, a, b) -> livePreview.run());

        TextField startWidthField = new TextField(String.valueOf((int) prefs.startWidth));
        TextField startHeightField = new TextField(String.valueOf((int) prefs.startHeight));
        startWidthField.setPrefWidth(90);
        startHeightField.setPrefWidth(90);

        Button useCurrentSizeBtn = new Button("Use current window size");
        useCurrentSizeBtn.getStyleClass().add("pill-button");
        useCurrentSizeBtn.setOnAction(e -> {
            startWidthField.setText(String.valueOf((int) stage.getWidth()));
            startHeightField.setText(String.valueOf((int) stage.getHeight()));
        });

        CheckBox fullscreenStartBox = new CheckBox("Start the launcher in fullscreen");
        fullscreenStartBox.setSelected(prefs.launcherStartFullscreen);
        fullscreenStartBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.launcherStartFullscreen = b;
            prefs.save();
        });

        TextField settingsWidthField = new TextField(String.valueOf((int) prefs.settingsWindowWidth));
        TextField settingsHeightField = new TextField(String.valueOf((int) prefs.settingsWindowHeight));
        settingsWidthField.setPrefWidth(90);
        settingsHeightField.setPrefWidth(90);
        Runnable applySettingsWindowSize = () -> {
            markDirty.run();
            prefs.settingsWindowWidth = parseIntOr(settingsWidthField.getText(), (int) prefs.settingsWindowWidth);
            prefs.settingsWindowHeight = parseIntOr(settingsHeightField.getText(), (int) prefs.settingsWindowHeight);
            prefs.save();
            // Takes effect the next time this window is opened -- resizing it out from under
            // yourself while it's open would be a jarring, not helpful, live preview.
        };
        settingsWidthField.textProperty().addListener((o, a, b) -> applySettingsWindowSize.run());
        settingsHeightField.textProperty().addListener((o, a, b) -> applySettingsWindowSize.run());
        Label settingsSizeNote = new Label("Applies next time this window is opened.");
        settingsSizeNote.getStyleClass().add("notice-label");

        CheckBox rememberBox = new CheckBox("Remember window size automatically on close");
        rememberBox.setSelected(prefs.rememberWindowSize);
        rememberBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.rememberWindowSize = b;
            prefs.save();
        });

        // Modpacks (Settings > Launcher): whether Play first checks the active pack's own file list and
        // downloads back whatever has gone missing, instead of launching a pack with mods missing.
        CheckBox verifyPackBox = new CheckBox("Check modpacks for missing mods before every launch");
        verifyPackBox.setSelected(prefs.verifyModpackOnLaunch);
        verifyPackBox.setTooltip(new Tooltip("Uses the pack's own file list to re-download anything that "
                + "went missing, straight into that version's mods folder. Mods you disabled stay disabled."));
        verifyPackBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.verifyModpackOnLaunch = b;
            prefs.save();
        });
        Label verifyPackNote = new Label("Anything missing is fetched automatically before the game starts.");
        verifyPackNote.getStyleClass().add("notice-label");

        Runnable applyStartupSize = () -> {
            markDirty.run();
            prefs.startWidth = parseIntOr(startWidthField.getText(), (int) prefs.startWidth);
            prefs.startHeight = parseIntOr(startHeightField.getText(), (int) prefs.startHeight);
            prefs.save();
        };
        startWidthField.textProperty().addListener((o, a, b) -> applyStartupSize.run());
        startHeightField.textProperty().addListener((o, a, b) -> applyStartupSize.run());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(16);
        grid.setPadding(new Insets(24));
        int row = 0;
        grid.add(sectionLabel("THEME"), 0, row++, 2, 1);
        grid.add(themeToggle, 0, row++, 2, 1);
        row++;
        grid.add(sectionLabel("INTERFACE SCALE"), 0, row++, 2, 1);
        grid.add(uiScaleLabel, 0, row, 2, 1);
        grid.add(uiScaleSlider, 0, ++row, 2, 1);
        row++;
        grid.add(sectionLabel("TEXT SIZE"), 0, row++, 2, 1);
        grid.add(textScaleLabel, 0, row, 2, 1);
        grid.add(textScaleSlider, 0, ++row, 2, 1);
        row++;
        grid.add(sectionLabel("FONT"), 0, row++, 2, 1);
        grid.add(fontBox, 0, row++, 2, 1);
        grid.add(sectionLabel("STARTUP WINDOW SIZE"), 0, row++, 2, 1);
        HBox sizeBox = new HBox(6, startWidthField, new Label("x"), startHeightField);
        sizeBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(sizeBox, 0, row, 1, 1);
        grid.add(useCurrentSizeBtn, 1, row++, 1, 1);
        grid.add(rememberBox, 0, row++, 2, 1);
        grid.add(fullscreenStartBox, 0, row++, 2, 1);

        row++;
        grid.add(sectionLabel("WINDOW EDGES"), 0, row++, 2, 1);
        CheckBox edgeSnapBox = new CheckBox("Snap windows to screen edges instead of pushing them off-screen");
        edgeSnapBox.setSelected(prefs.edgeSnapInsteadOfPark);
        Label edgeSnapNote = new Label("ON: dragging a borderless window to the left or right edge tiles it to "
                + "that half of the screen and the top edge maximises it -- the same edge gestures the "
                + "desktop itself uses. OFF (default): the window keeps sliding outward so it can be parked "
                + "partly or fully off any edge, which needs a desktop that allows windows off-screen "
                + "(Windows, or an X11 window manager that permits it). KDE Plasma Wayland refuses to let "
                + "any window leave the screen, so use this mode there.");
        edgeSnapNote.getStyleClass().add("notice-label");
        edgeSnapNote.setWrapText(true);
        edgeSnapBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.edgeSnapInsteadOfPark = b;
            prefs.save();
        });
        grid.add(edgeSnapBox, 0, row++, 2, 1);
        grid.add(edgeSnapNote, 0, row++, 2, 1);

        row++;
        grid.add(sectionLabel("SETTINGS WINDOW SIZE"), 0, row++, 2, 1);
        HBox settingsSizeBox = new HBox(6, settingsWidthField, new Label("x"), settingsHeightField);
        settingsSizeBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(settingsSizeBox, 0, row++, 2, 1);
        grid.add(settingsSizeNote, 0, row++, 2, 1);

        row++;
        grid.add(sectionLabel("MODPACKS"), 0, row++, 2, 1);
        grid.add(verifyPackBox, 0, row++, 2, 1);
        grid.add(verifyPackNote, 0, row++, 2, 1);

        // NOTE: "Share my current server address with friends" used to live here. It is now a gold
        // card in the Account tab, right next to "Appear offline" (see buildShareCard): both decide
        // what friends can see about you, both apply instantly without the APPLY button, and neither
        // belongs in a Launcher-appearance panel.

        return grid;
    }


    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }

    /** Small uppercase caption above a control group (RUN / VERSION / PLAY) in the Console tab. */
    private Label captionLabel(String text, Font font) {
        Label l = new Label(text.toUpperCase());
        l.setFont(font);
        l.getStyleClass().add("server-caption");
        return l;
    }

    private int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- Data loading + play action, off the UI thread ----
    private void loadVersionsAsync() {
        Task<List<VersionManifest.VersionEntry>> task = new Task<>() {
            @Override
            protected List<VersionManifest.VersionEntry> call() throws Exception {
                return new VersionManifest().fetchAll();
            }
        };
        task.setOnSucceeded(e -> {
            allVersions.clear();
            allVersions.add(SYNTHETIC_26);
            allVersions.addAll(task.getValue());
            // Re-apply whichever tile is currently selected now that the real list is in --
            // before this, the dropdown only had SYNTHETIC_26 (and whatever a tile click added).
            if (activePreset != null) applyVersionFilter(activePreset);
            restoreLastPlayedVersion(); // correct the best-effort restore now that types are known
            log("Loaded " + task.getValue().size() + " versions from Mojang.");
        });
        task.setOnFailed(e -> log("Failed to load version list: " + task.getException()));
        new Thread(task, "version-loader").start();
    }

    private void onPlay() {
        onPlay(null);
    }

    /** quickPlayTarget: "host:port" to join directly via Friends > Join, or null for a normal launch. */
    private void onPlay(String quickPlayTarget) {
        PlayerIdentity activeForPlay = identityStore.getActive();
        String versionId = versionBox.getValue();
        if (versionId == null) {
            log("Pick a version first.");
            return;
        }

        if (activeForPlay == null) {
            log("No account set up yet -- open Account and sign in with Microsoft or create an "
                    + "offline account before playing.");
            return;
        }

        String modLoader = modLoaderBox.getValue();
        // Remember exactly what was played so the launcher reopens here next time.
        prefs.lastVersionId = versionId;
        prefs.lastDeyMode = deyMode;
        prefs.save();
        // Whenever we join a specific server, auto-remember its address as our current server
        // address so Friends can join us there without anyone having to type it in manually
        // (see rememberCurrentlyJoined below). null = a normal launch, nothing to capture.
        if (quickPlayTarget != null) rememberCurrentlyJoined(quickPlayTarget);
        // Snapshot both together -- a stale token from a *different* previously-signed-in
        // account must never get used for whichever account happens to be active now.
        String capturedOnlineToken = (activeForPlay.accountType == AccountType.ONLINE
                && activeForPlay.uuid.equals(liveOnlineAccountUuid)) ? liveOnlineAccessToken : null;
        playButton.setDisable(true);
        // The bar's progressProperty may still be bound to a previous launch's task (bind never
        // auto-releases on completion), and a bound value can't be set -- release it before resetting.
        launchProgress.progressProperty().unbind();
        launchProgress.setProgress(0.0);
        launchProgress.setManaged(true);
        launchProgress.setVisible(true);
        launchProgress.start(); // begin the orange sine-wave animation

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // Report absolute launch progress (0..1) as a (workDone, totalWork) pair -- Task's
                // updateProgress takes two args; the wave bar is bound to its progressProperty.
                java.util.function.DoubleConsumer report = p -> {
                    double v = p < 0 ? 0 : (p > 1 ? 1 : p);
                    updateProgress((long) Math.round(v * 1_000_000L), 1_000_000L);
                };
                // Session selection, in order of trust: a live online account with a real token
                // from this run > a saved offline account (set via Account > Apply) > a one-off
                // typed name for someone who hasn't set up an account at all yet. The launcher's
                // saved account is always the source of truth once one exists -- we do NOT
                // silently create/rename an account here anymore (that used to happen on every
                // Play click, which is exactly what made a retyped name mint a disconnected
                // identity instead of updating the real one -- see Account > Apply instead).
                AuthSession session;
                if (activeForPlay.accountType == AccountType.ONLINE) {
                    if (capturedOnlineToken == null) {
                        Platform.runLater(() -> log("Active account is online but there's no live "
                                + "session from this run -- sign in with Microsoft again to play "
                                + "online. Launching offline for now."));
                        session = AuthSession.offline(activeForPlay.username);
                    } else {
                        updateMessage("Using signed-in Microsoft session...");
                        session = new AuthSession(capturedOnlineToken, activeForPlay.uuid, activeForPlay.username, false);
                    }
                } else {
                    updateMessage("Using saved offline account...");
                    session = AuthSession.offline(activeForPlay.username);
                }

                updateMessage("Fetching version details...");
                VersionManifest manifest = new VersionManifest();
                var all = manifest.fetchAll();
                var entry = manifest.findById(all, versionId);
                if (entry == null) throw new IllegalStateException("Version not found: " + versionId);
                var vanillaJson = manifest.fetchVersionDetail(entry);
                report.accept(0.10); // version resolved; from here the big work begins

                GameFiles files = new GameFiles();
                JavaRuntimeManager runtimeManager = new JavaRuntimeManager(files.root);

                updateMessage("Checking Java runtime...");
                var javaBinary = runtimeManager.ensureRuntimeFor(vanillaJson,
                        f -> report.accept(0.10 + f * 0.20)); // runtime phase: 10% -> 30%

                JsonObject versionJson;
                if (modLoader.equals("Fabric")) {
                    updateMessage("Installing Fabric...");
                    FabricInstaller fabric = new FabricInstaller(manifest, files.root);
                    // A modpack pins the exact loader build it was made for (see ModpackMeta), and
                    // using it is what makes a pack run exactly as its author intended. If that build
                    // can't be installed any more, fall back to the newest stable one instead of
                    // failing the launch.
                    String pinnedFabric = ModpackMeta.pinnedLoaderVersion(files.root, entry.id(), "Fabric");
                    versionJson = null;
                    if (pinnedFabric != null) {
                        try {
                            updateMessage("Installing the modpack's Fabric " + pinnedFabric + "...");
                            versionJson = fabric.install(entry.id(), pinnedFabric);
                        } catch (Exception pinnedFailed) {
                            updateMessage("The modpack's pinned Fabric build isn't available -- using the newest stable one...");
                        }
                    }
                    if (versionJson == null) {
                        String loaderVersion = fabric.latestLoaderVersion(entry.id());
                        if (loaderVersion == null) throw new IllegalStateException(
                                "Fabric has no build for " + entry.id() + " yet.");
                        versionJson = fabric.install(entry.id(), loaderVersion);
                    }
                } else if (modLoader.equals("Forge")) {
                    updateMessage("Installing Forge (this runs Forge's own installer, may take a minute)...");
                    ForgeInstaller forge = new ForgeInstaller(manifest, files.root);
                    // Same idea as Fabric above: a pack's own Forge build wins, otherwise the
                    // recommended/latest one. (Forge's installer is far too slow to retry blindly, so
                    // a pinned-but-broken build surfaces its own error rather than being retried here.)
                    String forgeVersion = ModpackMeta.pinnedLoaderVersion(files.root, entry.id(), "Forge");
                    if (forgeVersion == null) forgeVersion = forge.recommendedOrLatestVersion(entry.id());
                    if (forgeVersion == null) throw new IllegalStateException(
                            "Forge has no build for " + entry.id() + " yet.");
                    // Forge's own installer is the ONLY thing that explains a Forge install failure, so
                    // stream its output into the launcher log (hopping to the FX thread, since log()
                    // appends to a TextArea) rather than discarding it as we used to.
                    versionJson = forge.withOutputSink(line -> {
                        String trimmed = line == null ? "" : line.trim();
                        if (!trimmed.isEmpty()) Platform.runLater(() -> log("Forge: " + trimmed));
                    }).install(entry.id(), forgeVersion, javaBinary.toString());
                } else if (modLoader.equals("NeoForge")) {
                    updateMessage("Installing NeoForge (this runs NeoForge's own installer, may take a minute)...");
                    NeoForgeInstaller neoForge = new NeoForgeInstaller(manifest, files.root);
                    // Same idea as Forge above: a modpack's own NeoForge build wins, otherwise the
                    // newest one NeoForge publishes FOR THIS Minecraft version -- stable preferred,
                    // beta only when that is all that exists yet (26.3's first builds are betas, and
                    // refusing them would mean "NeoForge doesn't work" on the newest version).
                    String neoVersion = ModpackMeta.pinnedLoaderVersion(files.root, entry.id(), "NeoForge");
                    if (neoVersion == null) neoVersion = neoForge.latestVersion(entry.id());
                    if (neoVersion == null) throw new IllegalStateException(
                            "NeoForge has no build for " + entry.id() + " yet.");
                    // NeoForge's own installer is the ONLY thing that explains a NeoForge install
                    // failure, so stream its output into the launcher log exactly like Forge's.
                    versionJson = neoForge.withOutputSink(line -> {
                        String trimmed = line == null ? "" : line.trim();
                        if (!trimmed.isEmpty()) Platform.runLater(() -> log("NeoForge: " + trimmed));
                    }).install(entry.id(), neoVersion, javaBinary.toString());
                } else {
                    versionJson = vanillaJson;
                }

                report.accept(0.30); // loader installed (or not needed) -- downloads are next

                updateMessage("Downloading files (cached after first run)...");
                var prepared = files.prepare(versionJson,
                        f -> report.accept(0.30 + f * 0.56)); // download phase: 30% -> 86%

                // Replace buggy libglfw.so (GLFW 3.4.0 in LWJGL 3.3.1) with fixed version (GLFW 3.4.1 in LWJGL 3.3.2+)
                // for modpacks on Wayland sessions to avoid SIGSEGV in glfwCreateWindow.
                // Sodium requires LWJGL 3.3.1 at runtime, so we keep the version JSON but patch the native JAR
                // in the libraries directory BEFORE extraction, so LWJGL always gets the fixed version.
                if (isLinuxWaylandSession() && WaylandSupport.usesBuggyLwjgl(versionJson)) {
                    log("Patching native libglfw.so in libraries directory for Wayland compatibility");
                    GameFiles.patchNativeGlfwInJar(files.root.resolve("libraries"), msg -> log(msg));
                }

                var gameDir = files.root.resolve("instances").resolve(entry.id()
                        + (modLoader.equals("Vanilla") ? "" : "-" + modLoader.toLowerCase()));
                java.nio.file.Files.createDirectories(gameDir);

                // Every DEY and VANILLA instance, any Minecraft version, shares one pool of
                // singleplayer worlds -- see SharedSaves. Everything else about the instance
                // (mods, config, options, etc.) stays exactly as isolated as before.
                com.deylauncher.launch.SharedSaves.ensureShared(files.root, gameDir);

                // ---- Modpack self-repair, before the game starts. A pack's mods can go missing for
                // ordinary reasons (an interrupted first install, a deleted instance folder, a mod
                // removed by hand, antivirus quarantine), and the pack's own record lists every file it
                // owns. So instead of launching a broken pack and leaving the player to download jars
                // one by one, anything missing is fetched again -- into this exact version's mods
                // folder -- right here. Mods the user switched OFF (mods-disabled/) are left alone. ----
                if (prefs.verifyModpackOnLaunch) {
                    ModpackMeta packMeta = ModpackMeta.read(gameDir);
                    if (packMeta != null && packMeta.managesFiles()) {
                        updateMessage("Checking \"" + packMeta.name + "\" for missing mods...");
                        ModpackVerifier.Report packReport = new ModpackVerifier().verifyClient(packMeta, gameDir,
                                f -> report.accept(0.86 + f * 0.06)); // 86% -> 92% of the launch bar
                        String line = "Modpack \"" + packMeta.name + "\": " + packReport.summary();
                        Platform.runLater(() -> log(line));
                        if (!packReport.errors().isEmpty()) {
                            String failed = "  " + String.join("\n  ", packReport.errors());
                            Platform.runLater(() -> log("Files the pack wants that couldn't be fetched:\n" + failed));
                        }
                    }
                }

                // DeyCapes integration only receives public repository coordinates. Never copy a GitHub
                // credential into a game instance: instance folders, logs and mod jars are user-accessible.
                // Private cape repositories require a server-side/public proxy rather than a shipped token.
                if (deyMode && modLoader.equals("Fabric") && deyCapesService != null && !"Vanilla".equals(modLoader)) {
                    try {
                        var cfgDir = gameDir.resolve("config").resolve("deycapes");
                        java.nio.file.Files.createDirectories(cfgDir);
                        var cfg = new java.util.Properties();
                        cfg.setProperty("owner", deyCapesService.gitConfig().owner());
                        cfg.setProperty("repo", deyCapesService.gitConfig().repo());
                        cfg.setProperty("capesPath", deyCapesService.gitConfig().capesPath());
                        cfg.setProperty("capesOwnedPath", deyCapesService.gitConfig().ownershipPath());
                        cfg.setProperty("capesDir", deyCapesService.gitConfig().capesDir());
                        try (var out = java.nio.file.Files.newOutputStream(cfgDir.resolve("github.properties"))) {
                            cfg.store(out, "DeyCapes public repository settings");
                        }
                    } catch (Exception cfgEx) {
                        Platform.runLater(() -> log("Couldn't write DeyCapes config (continuing without remote capes): " + cfgEx.getMessage()));
                    }
                }

                if (deyMode) {
                    // All four bundled mods in one coordinated pass: Sodium/Iris are resolved as a
                    // COMPATIBLE pair (never "the newest of each" independently -- that is the DEY 26.2
                    // crash), Fabric API is newest, DeyCapes stays the local bundled jar. See ModPairResolver.
                    updateMessage("Making sure bundled mods are installed (auto-pairing Sodium/Iris)...");
                    try {
                        String summary = new ModPairResolver().ensureDeyMods(modLoader, entry.id(), gameDir.resolve("mods"));
                        if (summary != null) {
                            String done = summary;
                            Platform.runLater(() -> log("Bundled mods ready: " + done));
                        }
                    } catch (Exception modEx) {
                        String msg = modEx.getMessage();
                        Platform.runLater(() -> log("Couldn't fully auto-install bundled mods (continuing): " + msg));
                    }
                }

                report.accept(0.94); // everything downloaded/installed, just spinning up the game now
                updateMessage(quickPlayTarget != null ? "Launching straight into " + quickPlayTarget + "..." : "Launching...");
                report.accept(1.0); // "Minecraft open" -- bar is fully done
                Platform.runLater(() -> launchProgress.finishLaunch());

                // The live session tracker turns the game's own console output into play state (see
                // ServerSessionTracker): it knows a proxy region hand-off is not a disconnect, that
                // single player is not a server, and that leaving a server prints no "disconnect" line
                // at all. quickPlayTarget (a Friends > Join, or a server card's Join) starts it off
                // already on that server until the game's own log says otherwise.
                ServerSessionTracker tracker = new ServerSessionTracker(session.username(), quickPlayTarget);
                java.util.concurrent.atomic.AtomicBoolean sessionAlive = new java.util.concurrent.atomic.AtomicBoolean(true);
                java.util.function.Consumer<ServerSessionTracker.Event> onSessionEvent = event ->
                        Platform.runLater(() -> applySessionEvent(event, tracker));
                // A real "Disconnect" can be followed by total log silence (verified: one line, then
                // nothing for twelve minutes), so a line-only state machine would stay stuck on a
                // server you already left. This light ticker lets the tracker's grace window expire.
                Thread presenceTick = new Thread(() -> {
                    while (sessionAlive.get() && appRunning) {
                        try {
                            Thread.sleep(PRESENCE_TICK_MS);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        if (!sessionAlive.get()) return;
                        if (tracker.poll(System.currentTimeMillis()) != ServerSessionTracker.Event.NONE) {
                            onSessionEvent.accept(ServerSessionTracker.Event.LEFT);
                        }
                    }
                }, "presence-session-tick");
                presenceTick.setDaemon(true);
                presenceTick.start();

                // Self-healing, one retry, and WHICH retry now depends on what failed (see retrySettings):
                // on a Wayland session the useful move is to hand the game Wayland's own backend instead of
                // XWayland, because a native fault inside GLFW's window creation is not something Mesa's
                // software renderer can avoid (measured: identical crash with libGLX_mesa/llvmpipe loaded
                // and no NVIDIA GLX library at all), while native Wayland fixes both that window-layer fault
                // and a GLX context failure at once (it uses EGL). Everything else keeps the older
                // software-rendering retry.
                LaunchOutcome first = runGameAndWait(prepared, session, gameDir, settings, javaBinary, quickPlayTarget, tracker, onSessionEvent);
                GameLauncher.LaunchSettings retry = retrySettings(first, settings, prepared);
                if (retry != null) {
                    boolean waylandRetry = retry.nativeWayland() && !settings.nativeWayland();
                    boolean xwaylandFallbackRetry = !retry.nativeWayland() && settings.nativeWayland();
                    String retryMessage = waylandRetry
                                    ? "Retrying once on Wayland directly (XWayland bypassed)..."
                                    : xwaylandFallbackRetry
                                    ? "Retrying once through XWayland (this version's native Wayland backend just crashed)..."
                                    : "Retrying once with software rendering (compatibility) enabled...";
                    Platform.runLater(() -> log(retryMessage));
                    runGameAndWait(prepared, session, gameDir, retry, javaBinary, quickPlayTarget, tracker, onSessionEvent);
                }
                // The game process (whichever attempt actually ran last) has now exited, so this
                // session is over: reset the tracker and stop advertising wherever it last was. This is
                // the case that used to leave presence claiming a server forever, because the address
                // was kept on disk (see rememberCurrentlyJoined's doc).
                sessionAlive.set(false);
                tracker.processExited();
                Platform.runLater(LauncherApp.this::setLiveInLauncher);
                return null;
            }
        };
        task.messageProperty().addListener((obs, old, msg) -> log(msg));
        // Pump the background launch's 0..1 progress into the gold sine-wave bar (bound on the FX thread,
        // same pattern the update overlay uses) so the percentage + fill track the real launch state.
        launchProgress.progressProperty().unbind();
        launchProgress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            playButton.setDisable(false);
            launchProgress.stop();
            // Release the task binding so a future launch can reset the bar from 0 again.
            launchProgress.progressProperty().unbind();
            launchProgress.setManaged(false);
            launchProgress.setVisible(false);
        });
        task.setOnFailed(e -> {
            playButton.setDisable(false);
            launchProgress.stop();
            launchProgress.progressProperty().unbind();
            launchProgress.setManaged(false);
            launchProgress.setVisible(false);
            log("Error: " + task.getException());
            // The launch never got anywhere (bad download, no runtime, a process that died before it
            // could tell us anything), so the "heading to <server>" presence published just before it
            // (see rememberCurrentlyJoined in play()) must not outlive it -- otherwise friends keep
            // seeing a server we never actually reached until the launcher is restarted, which is the
            // exact bug the live session state replaced.
            setLiveInLauncher();
        });
        new Thread(task, "play-task").start();
    }

    private void log(String line) {
        logArea.appendText(line + "\n");
    }

    /**
     * Result of one game run: the exit code, a human-readable crash diagnosis (null on a normal exit),
     * and whether THIS run already had native Wayland switched on and still failed in the GLFW window
     * layer -- either one of the two known feature-unavailable/no-DISPLAY messages (see
     * LaunchDiagnostics#isNativeWaylandBackfire), or a straight native SIGSEGV inside libglfw.so (see
     * LaunchDiagnostics#isGlfwWindowLayerCrash), most commonly hit by a MODPACK pinned to an older
     * Minecraft/LWJGL build whose Wayland backend isn't production-safe even though it passes every
     * static check DeyLauncher can do ahead of time. In every one of these cases it is native Wayland
     * itself that broke this run, and the only real fix is running THIS launch through XWayland instead
     * -- see {@link #retrySettings}.
     */
    private record LaunchOutcome(int exit, String diagnosis, boolean nativeWaylandBackfired) {}

    /**
     * The single retry worth making after a failed run, or null when a retry would change nothing.
     *
     * <p>Only fires when {@link LaunchDiagnostics} actually recognized a graphics/window failure (a normal
     * exit diagnoses nothing). The preference order is deliberate:
     * <ol>
     *   <li><b>Turn native Wayland back OFF</b> -- when THIS run already had it on and it is what crashed
     *       ({@link LaunchOutcome#nativeWaylandBackfired}). Checked first and unconditionally, because
     *       retrying with the same switch in the same position would just reproduce the identical crash
     *       (the modpack-on-an-older-LWJGL-build case).</li>
     *   <li><b>Native Wayland ON</b> -- when the switch is off but this machine/version could safely use
     *       it (Linux, a live Wayland socket, a GLFW with the Wayland backend, and an LWJGL release known
     *       not to crash on it: see {@link WaylandSupport}). This covers both failure families at once: a
     *       native fault inside {@code glfwCreateWindow} via XWayland (which software rendering
     *       demonstrably does not avoid) and a broken GLX context (Wayland uses EGL, so the GLX path isn't
     *       even touched).</li>
     *   <li><b>Software rendering</b> -- the older remedy, for an X11 session or a GLFW without Wayland
     *       support, where Mesa's CPU renderer is the only lever left.</li>
     * </ol>
     * The flipped switch applies to this one retry only; nothing is persisted, so a healthy machine never
     * gets either workaround forced on it.
     */
    private GameLauncher.LaunchSettings retrySettings(LaunchOutcome first, GameLauncher.LaunchSettings settings,
                                                      GameFiles.PreparedVersion prepared) {
        if (first.diagnosis() == null) return null;

        // Checked FIRST and unconditionally: this run already had native Wayland on and it is what
        // crashed (see LaunchOutcome#nativeWaylandBackfired's doc) -- e.g. a MODPACK pinned to an older
        // Minecraft/LWJGL build whose Wayland backend is not production-safe, which passes every static
        // check WaylandSupport can do ahead of time (a "normal"/custom instance on a current LWJGL never
        // hits this). Retrying with native Wayland on again would just reproduce the identical crash, so
        // this is the one case where the fix is turning the switch OFF, not on.
        if (first.nativeWaylandBackfired()) {
            return new GameLauncher.LaunchSettings(settings.ramMinMb(), settings.ramMaxMb(), settings.width(),
                    settings.height(), settings.fullscreen(), settings.softwareOpenGl(), false);
        }
        if (!settings.nativeWayland()
                && WaylandSupport.shouldUseNativeWayland(true, System.getProperty("os.name", ""),
                        System.getenv(), prepared.nativesDir(), prepared.versionJson())) {
            return new GameLauncher.LaunchSettings(settings.ramMinMb(), settings.ramMaxMb(), settings.width(),
                    settings.height(), settings.fullscreen(), settings.softwareOpenGl(), true);
        }
        if (!settings.softwareOpenGl()) {
            return new GameLauncher.LaunchSettings(settings.ramMinMb(), settings.ramMaxMb(), settings.width(),
                    settings.height(), settings.fullscreen(), true, settings.nativeWayland());
        }
        return null;
    }

    /**
     * Launches the game and streams its output into the log, keeping a bounded recent-output tail so a
     * native/GL crash can be explained (see {@link LaunchDiagnostics}) instead of just showing the raw
     * exit code. Returns the exit code and diagnosis so the caller can decide whether to self-heal.
     *
     * <p>Every line is also fed to {@code tracker} (see {@link ServerSessionTracker}), and any state
     * change it reports is handed to {@code onSessionEvent} -- which is how a server joined by hand
     * from Minecraft's own Multiplayer screen, a proxy region hand-off, a single-player world, and a
     * plain "Disconnect" all end up reflected in what friends see. Both parameters may be null (a
     * caller that doesn't care about presence).
     */
    private LaunchOutcome runGameAndWait(GameFiles.PreparedVersion prepared, AuthSession session, Path gameDir,
                                         GameLauncher.LaunchSettings s, Path javaBinary, String quickPlayTarget,
                                         ServerSessionTracker tracker,
                                         java.util.function.Consumer<ServerSessionTracker.Event> onSessionEvent) throws Exception {
        // The log sink carries the launch's own one-line notes into the launcher's log as well -- most
        // usefully "starting the game natively on Wayland" and any ${placeholder} the version profile
        // asked for that we couldn't fill in.
        Process process = new GameLauncher().launch(prepared, session, gameDir, s, javaBinary.toString(),
                quickPlayTarget, line -> Platform.runLater(() -> log(line)));

        // Keep a bounded recent-output tail so that, if the game ends in a native/GL crash, we can
        // explain it in plain words instead of just the raw exit code.
        Deque<String> recent = new ArrayDeque<>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String finalLine = line;
                Platform.runLater(() -> log(finalLine));
                recent.addLast(finalLine);
                if (recent.size() > 120) recent.pollFirst();
                if (tracker != null && onSessionEvent != null) {
                    ServerSessionTracker.Event event = tracker.feed(finalLine, System.currentTimeMillis());
                    if (event != ServerSessionTracker.Event.NONE) onSessionEvent.accept(event);
                }
            }
        }
        int exit = process.waitFor();
        // The JVM writes the ENTIRE launch command line -- including --accessToken -- into its own crash
        // dump (hs_err_pid<pid>.log) when it dies natively, and creates that file readable by everyone.
        // Redact the session token and restrict the file to this user before anything else reads it.
        // Best-effort by design: a launch is never failed over a dump.
        CrashDumps.Result dumps = CrashDumps.scrubInstance(gameDir, java.util.List.of(session.accessToken()));
        Platform.runLater(() -> log("Game exited with code " + exit));
        if (dumps.changedAnything()) {
            Platform.runLater(() -> log("Crash dump cleaned up: " + dumps.summary() + "."));
        }
        java.util.ArrayList<String> recentList = new java.util.ArrayList<>(recent);
        String diagnosis = LaunchDiagnostics.analyze(recentList, exit, s.nativeWayland());
        if (diagnosis != null) {
            Platform.runLater(() -> log("\n==== Crash diagnostic ====\n" + diagnosis));
        }
        // "Backfired" means THIS run already had native Wayland switched on and still hit a GLFW
        // window-layer failure -- either one of the two known feature-unavailable/no-DISPLAY messages,
        // or a straight native SIGSEGV inside libglfw.so (the crash a MODPACK on an older LWJGL build's
        // Wayland backend produces even though it passed every earlier safety check -- see
        // WaylandSupport's LWJGL-version gate). When native Wayland was OFF for this run, the same
        // libglfw.so crash means the opposite thing (the original XWayland-crashes-natively bug that
        // switching TO Wayland fixes), which is already handled by retrySettings' existing branch --
        // so this flag must only ever fire while native Wayland was actually in use.
        boolean nativeWaylandBackfired = s.nativeWayland()
                && (LaunchDiagnostics.isNativeWaylandBackfire(recentList)
                    || LaunchDiagnostics.isGlfwWindowLayerCrash(recentList));
        return new LaunchOutcome(exit, diagnosis, nativeWaylandBackfired);
    }

    // ---- Cross-platform icon helpers ----

    /**
     * Redacts + locks JVM crash dumps left behind by earlier runs, across every instance.
     *
     * <p>The value of the leaked token is not known at startup (it may belong to a previous session), so
     * this relies on the form the JVM always quotes back verbatim -- {@code --accessToken <token>},
     * {@code accessToken=<token>}, and the {@code token:<token>:<uuid>} of pre-1.13 argument lists. That
     * is exactly why {@link CrashDumps} redacts by shape as well as by known value.
     *
     * <p>Runs on a daemon thread and never blocks, fails, or throws into startup: worst case a dump stays
     * as it was, and the next launch's own scrub (which DOES know the token) handles it.
     */
    private void scrubLeftoverCrashDumps() {
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                Path instances = gameFiles.root.resolve("instances");
                if (!Files.isDirectory(instances)) return 0;
                int redacted = 0;
                try (var stream = Files.list(instances)) {
                    for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                        CrashDumps.Result result = CrashDumps.scrubInstance(dir, java.util.List.of());
                        if (result.changedAnything()) redacted += result.redacted();
                    }
                } catch (Exception ignored) {
                    // A folder we can't read simply isn't cleaned up here.
                }
                return redacted;
            }
        };
        task.setOnSucceeded(e -> {
            int redacted = task.getValue() == null ? 0 : task.getValue();
            if (redacted > 0) {
                log("Cleaned up " + redacted + " old JVM crash dump(s) that still contained your account "
                        + "token -- they are redacted and now readable only by you.");
            }
        });
        Thread thread = new Thread(task, "crash-dump-scrub");
        thread.setDaemon(true);
        thread.start();
    }

    private Node icon(IconFactory.Icon icon) {
        return IconFactory.create(icon, 18);
    }

    private Node icon(IconFactory.Icon icon, double size) {
        return IconFactory.create(icon, size);
    }

    private void setButtonIcon(ButtonBase button, IconFactory.Icon icon, String text) {
        button.setGraphic(icon(icon, 17));
        button.setText(text);
        button.setGraphicTextGap(7);
    }

    /**
     * Keeps the original ONLINE/OFFLINE appearance: text + a small status dot.
     * The dot is a JavaFX Circle instead of a Unicode glyph, so it renders
     * identically on Windows/Linux without depending on an emoji font.
     */
    private Label statusLabel(boolean online) {
        Label label = new Label(online ? "ONLINE" : "OFFLINE");
        Circle dot = new Circle(4.0);
        dot.getStyleClass().add(online ? "status-dot-online" : "status-dot-offline");
        label.setGraphic(dot);
        label.setGraphicTextGap(6);
        return label;
    }

    /** Server running/stopped badge: an SVG-free colored dot + text, so it renders the same on
     *  every device rather than depending on a Unicode \"●\"/\"○\" glyph being installed. */
    private Label badgeLabel(boolean running) {
        Label label = new Label(running ? "RUNNING" : "STOPPED");
        label.getStyleClass().add(running ? "badge-online" : "badge-offline");
        Circle dot = new Circle(3.6);
        dot.getStyleClass().add(running ? "status-dot-online" : "status-dot-offline");
        label.setGraphic(dot);
        label.setGraphicTextGap(6);
        return label;
    }

    /** Updates an existing badgeLabel() in place (text + dot color + pill color). */
    private void setBadge(Label label, boolean running) {
        label.setText(running ? "RUNNING" : "STOPPED");
        label.getStyleClass().setAll(running ? "badge-online" : "badge-offline");
        Node g = label.getGraphic();
        if (g instanceof Circle c) c.getStyleClass().setAll(running ? "status-dot-online" : "status-dot-offline");
    }

    /** Sets an SVG icon as the sole button content (text empty) -- used for pure-glyph buttons
     *  (delete, file rows) so they render identically on every device. */
    private void setButtonIconOnly(ButtonBase button, IconFactory.Icon icon) {
        button.setGraphic(icon(icon, 17));
        button.setText("");
        button.setGraphicTextGap(0);
    }

    /**
     * The compact icon-only refresh button used in the Friends and Servers headers.
     *
     * The glyph is an IconFactory SVG path rather than a "\u21BB" / emoji character, so it draws
     * from the same vector data on every Windows and Linux machine instead of depending on whichever
     * symbol font happens to be installed (a missing glyph would render as a tofu box). Clicking
     * spins it once so a refresh that finds nothing new still gives visible feedback.
     */
    private Button refreshIconButton(String tooltip, Runnable action) {
        Button btn = new Button();
        btn.getStyleClass().add("pill-button");
        setButtonIconOnly(btn, IconFactory.Icon.REFRESH);
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> {
            Node graphic = btn.getGraphic();
            if (graphic != null) {
                RotateTransition spin = new RotateTransition(Duration.millis(520), graphic);
                spin.setByAngle(360);
                spin.setInterpolator(Interpolator.EASE_BOTH);
                spin.play();
            }
            action.run();
        });
        return btn;
    }

    /** Opens a URL in the user's default browser via JavaFX HostServices (same as the sign-in links). */
    private void openUrl(String url) {
        try {
            getHostServices().showDocument(url);
        } catch (Exception ignored) {
        }
    }

    /**
     * A square "tile" for a mod/addon icon: a styled rounded Region that shows a small default
     * glyph when there's no image yet, and the real thumbnail once iconPath is supplied by
     * refresh. Click-handling is added by the caller (whole row is clickable).
     */
    private Node modIconNode(java.nio.file.Path iconPath, double size) {
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        pane.getStyleClass().add("mod-icon-tile");
        pane.getChildren().add(icon(IconFactory.Icon.PUZZLE, size * 0.5));
        if (iconPath != null && Files.exists(iconPath)) {
            try {
                javafx.scene.image.Image fx = decodeCachedIcon(iconPath);
                if (fx != null) {
                    ImageView iv = new ImageView(fx);
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().add("mod-icon-img");
                    pane.getChildren().add(iv);
                }
            } catch (Exception ignored) {
            }
        }
        return pane;
    }

    /**
     * Same tile but loading a remote image in the background; shows the glyph placeholder until it arrives.
     * Modrinth serves most icons as WebP, which JavaFX's {@code Image} can't decode natively, so WebP
     * URLs are downloaded and decoded through ImageIO (the bundled TwelveMonkeys plugin) on a background
     * thread and then rendered. Non-WebP URLs use JavaFX's native loader.
     */
    private Node remoteModIcon(String uri, double size) {
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        pane.getStyleClass().add("mod-icon-tile");
        pane.getChildren().add(icon(IconFactory.Icon.PUZZLE, size * 0.5));
        if (uri != null && !uri.isBlank()) {
            boolean webp = uri.toLowerCase().contains(".webp");
            if (!webp) {
                try {
                    ImageView iv = new ImageView(new Image(uri, size, size, true, false, true));
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().add("mod-icon-img");
                    pane.getChildren().add(iv);
                    return pane;
                } catch (Exception ignored) {
                    // fall through to the background decoder
                }
            }
            ImageView iv = new ImageView();
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.getStyleClass().add("mod-icon-img");
            pane.getChildren().add(iv);
            Task<Void> decode = new Task<>() {
                @Override
                protected Void call() {
                    byte[] bytes = new ModrinthClient().downloadBytes(uri);
                    if (bytes != null && bytes.length > 0) {
                        try {
                            javafx.scene.image.Image fx = decodeIconBytes(bytes);
                            if (fx != null) {
                                Platform.runLater(() -> iv.setImage(fx));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return null;
                }
            };
            new Thread(decode, "icon-decode").start();
        }
        return pane;
    }

    /** Loads a cached icon tile: JavaFX natively for PNG/etc, ImageIO (twelvemonkeys) for WebP leftovers. */
    private javafx.scene.image.Image decodeCachedIcon(java.nio.file.Path iconPath) {
        try {
            return new javafx.scene.image.Image(iconPath.toUri().toString());
        } catch (Exception notNative) {
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(iconPath.toFile());
                return bi == null ? null : bufferedToFx(bi);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Decodes icon bytes (any ImageIO-supported format, incl. WebP) into a JavaFX image. */
    private javafx.scene.image.Image decodeIconBytes(byte[] bytes) {
        try {
            var in = new java.io.ByteArrayInputStream(bytes);
            javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(in);
            var reader = javax.imageio.ImageIO.getImageReaders(iis);
            if (!reader.hasNext()) return null;
            var r = reader.next();
            r.setInput(iis);
            java.awt.image.BufferedImage bi = r.read(0);
            r.dispose();
            return bi == null ? null : bufferedToFx(bi);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts an AWT image to a JavaFX writable image (needed because JavaFX can't read WebP directly). */
    private javafx.scene.image.Image bufferedToFx(java.awt.image.BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        javafx.scene.image.WritableImage wi = new javafx.scene.image.WritableImage(w, h);
        javafx.scene.image.PixelWriter pw = wi.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                pw.setArgb(x, y, argb);
            }
        }
        return wi;
    }
/**
     * Resolves an online player's UUID by name via Mojang's public API, then fetches/caches their
     * skin face (reusing the same pipeline as the Friends list). Running this after we already know
     * the uuid just re-fetches the skin in case it changed. Calls onDone on the FX thread.
     */
    private void resolveOnlinePlayerAvatar(String playerName, Runnable onDone) {
        String uuid = playerUuidByName.get(playerName);
        if (uuid != null) {
            fetchFriendAvatarAsync(uuid, onDone);
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    var http = java.net.http.HttpClient.newHttpClient();
                    var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "https://api.mojang.com/users/profiles/minecraft/" + playerName)).GET().build();
                    var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        var json = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (json.has("id")) {
                            String plain = json.get("id").getAsString();
                            String dashed = plain.replaceAll(
                                    "(.{8})(.{4})(.{4})(.{4})(.+)", "$1-$2-$3-$4-$5");
                            playerUuidByName.put(playerName, dashed);
                            fetchFriendAvatarAsync(dashed, onDone); // run in this same background thread
                        }
                    }
                } catch (Exception ignored) {
                    // No UUID (offline-mode server or name lookup failed) -- keep the placeholder face.
                }
                return null;
            }
        };
        new Thread(task, "resolve-uuid-" + playerName).start();
    }

/**
     * Shows a small themed popup under a player-name field listing who's online right now on this
     * server; clicking one fills the field. This is the "helper that suggests the online players"
     * for the Operators / Whitelist / Banned / Server-managers add boxes.
     */
    private void showOnlinePlayerSuggestions(TextField field, ServerInstance server) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light", "suggest-pop");
        box.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        box.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        box.setPrefWidth(Math.max(240, field.getWidth()));
        box.setMaxHeight(280);

        // 1) Players currently online on this server (live join/leave + authoritative `list`).
        var pm = runningServers.get(server.id);
        List<String> online = (pm != null && pm.isRunning()) ? pm.getOnlinePlayers() : List.of();
        box.getChildren().add(sectionLabel("ONLINE PLAYERS"));
        if (online.isEmpty()) {
            Label none = new Label("Server isn't running, or nobody's online right now.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            box.getChildren().add(none);
        } else {
            for (String name : online) {
                box.getChildren().add(suggestButton(field, popup, name));
            }
        }

        // 2) Friends (their DeyLauncher usernames) -- handy for whitelisting/OP just the people
        //    you actually play with, even if they're not on this server right now. Loaded from
        //    the shared friends graph (with an instant local cache so the popup isn't empty).
        PlayerIdentity active = identityStore.getActive();
        if (friendsService != null && active != null) {
            box.getChildren().add(sectionLabel("FRIENDS"));
            FriendsService.FriendsView cached = friendsCache.load();
            if (cached != null) appendSuggestedFriends(box, field, popup, cached, online);
            else box.getChildren().add(suggestHint("Loading friends..."));
            Task<FriendsService.FriendsView> task = new Task<>() {
                @Override
                protected FriendsService.FriendsView call() throws Exception {
                    return friendsService.load(active.uuid);
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                FriendsService.FriendsView view = task.getValue();
                appendSuggestedFriends(box, field, popup, view, online);
                friendsCache.save(view);
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                // No heading/buttons yet (cache was empty) -- say why, don't leave it guessing.
                boolean hasFriends = box.getChildren().stream()
                        .anyMatch(n -> "suggest-friend".equals(n.getUserData()));
                if (!hasFriends) box.getChildren().add(suggestHint("Couldn't load friends right now."));
            }));
            new Thread(task, "suggest-friends").start();
        }

        popup.getContent().add(box);
        var bounds = field.localToScreen(field.getBoundsInLocal());
        popup.show(field, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    /** One clickable suggestion button that fills the field and closes the popup. */
    private Button suggestButton(TextField field, Popup popup, String name) {
        Button b = new Button(name);
        b.getStyleClass().addAll("pill-button", "suggest-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            field.setText(name);
            popup.hide();
        });
        return b;
    }

    private Label suggestHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("notice-label");
        l.setWrapText(true);
        return l;
    }

    /** Replaces the FRIENDS section with buttons for every DeyLauncher friend (online ones first). */
    private void appendSuggestedFriends(VBox box, TextField field, Popup popup,
                                        FriendsService.FriendsView view, List<String> onlinePlayers) {
        // Drop any previous friends sub-list (heading + buttons), kept fresh as the task refreshes.
        box.getChildren().removeIf(n -> "suggest-friends-head".equals(n.getUserData()));
        box.getChildren().removeIf(n -> "suggest-friend".equals(n.getUserData()));
        Label head = sectionLabel("FRIENDS");
        head.setUserData("suggest-friends-head");
        box.getChildren().add(head);

        List<FriendsData.FriendRef> friends = new ArrayList<>(view.friends());
        friends.sort(java.util.Comparator.comparing((FriendsData.FriendRef f) -> {
            FriendsData.UserEntry e = view.allUsers().get(f.uuid);
            return effectivelyOnline(e) ? 0 : 1;
        }));
        if (friends.isEmpty()) {
            box.getChildren().add(suggestHint("No friends yet -- add people on the Friends page."));
            return;
        }
        for (var friend : friends) {
            FriendsData.UserEntry e = view.allUsers().get(friend.uuid);
            boolean isOnline = effectivelyOnline(e);
            boolean alreadyListed = onlinePlayers.stream()
                    .anyMatch(n -> n.equalsIgnoreCase(friend.username));
            if (alreadyListed) continue; // already shown in the ONLINE PLAYERS section
            Button b = suggestButton(field, popup,
                    isOnline ? friend.username + "   (online)" : friend.username);
            b.setUserData("suggest-friend");
            box.getChildren().add(b);
        }
    }
    /** Latest local icon path for a Modrinth slug (enrichment cache), or null if unknown/failed. */
    private java.nio.file.Path modrinthIconPath(String slug) {
        String cached = modrinthIconCache.get(slug);
        if (cached == null || cached.isEmpty()) return null;
        java.nio.file.Path p = Path.of(cached);
        return Files.exists(p) ? p : null;
    }

    /** Shared SAVE/SAVE CHANGES button that glows (theme accent) only once something has actually
     *  changed -- mirrors the Settings dialog's APPLY button, so every server tab feels consistent. */
    private Button buildDirtyButton(SimpleBooleanProperty dirty) {
        Button btn = new Button();
        btn.getStyleClass().add("settings-apply-button");
        setButtonIcon(btn, IconFactory.Icon.CHECK, "SAVE");
        dirty.addListener((obs, oldV, newV) -> {
            if (newV) {
                if (!btn.getStyleClass().contains("settings-apply-button-ready"))
                    btn.getStyleClass().add("settings-apply-button-ready");
                setButtonIcon(btn, IconFactory.Icon.CHECK, "SAVE CHANGES");
            } else {
                btn.getStyleClass().remove("settings-apply-button-ready");
                setButtonIcon(btn, IconFactory.Icon.CHECK, "SAVE");
            }
        });
        return btn;
    }

    /** Wraps a tab's scrollable body in a BorderPane whose bottom edge is a pinned action bar
     *  (the Save button + status), so the Save control never scrolls out of view and always sits
     *  in the same spot no matter the window size. */
    private Node tabShell(ScrollPane scroll, Node... barChildren) {
        HBox bar = new HBox(12, barChildren);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("tab-action-bar");
        for (Node n : barChildren) if (n instanceof javafx.scene.control.Label l) HBox.setHgrow(l, Priority.ALWAYS);
        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(bar);
        return root;
    }

    /**
     * Checks if the launcher is running on Linux in a Wayland session.
     * Uses the same detection as {@link LauncherPrefs#defaultNativeWayland()}.
     */
    private boolean isLinuxWaylandSession() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!osName.contains("linux")) return false;
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        return waylandDisplay != null && !waylandDisplay.isBlank();
    }
}
