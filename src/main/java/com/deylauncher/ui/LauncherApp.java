package com.deylauncher.ui;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.auth.MicrosoftAuth;
import com.deylauncher.auth.TokenVault;
import com.deylauncher.friends.*;
import com.deylauncher.deycapes.DeyCapesService;
import com.deylauncher.identity.*;
import com.deylauncher.launch.GameFiles;
import com.deylauncher.launch.GameLauncher;
import com.deylauncher.launch.JavaRuntimeManager;
import com.deylauncher.launch.LaunchDiagnostics;
import com.deylauncher.modloader.FabricInstaller;
import com.deylauncher.modloader.FabricApiInstaller;
import com.deylauncher.modloader.ForgeInstaller;
import com.deylauncher.modloader.SodiumInstaller;
import com.deylauncher.modloader.IrisInstaller;
import com.deylauncher.modloader.DeyCapesInstaller;
import com.deylauncher.modloader.ModPairResolver;
import com.deylauncher.modloader.ModsUtil;
import com.deylauncher.server.*;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import javafx.scene.input.TransferMode;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

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
    private Button playButton;
    private ProgressBar progressBar;
    private Scene scene;
    private Stage stage;
    private final GameFiles gameFiles = new GameFiles(); // just for .root -- no I/O until prepare() is called
    private IdentityStore identityStore;
    private String liveOnlineAccessToken; // in-memory only, never persisted -- see openPreferencesDialog()
    private String liveOnlineAccountUuid; // which account liveOnlineAccessToken actually belongs to
    private Label accountStatusNotice;
    private FriendsService friendsService; // null until github.properties/embedded config is set -- see GitHubConfig
    private DeyCapesService deyCapesService; // null until github config is set -- Dey capes are a github-backed feature
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
                prefs.gameWidth, prefs.gameHeight, prefs.fullscreen, prefs.softwareOpenGl);
        this.friendsCache = new FriendsCache(gameFiles.root);
        this.friendNotes = new FriendNotesStore(gameFiles.root);
        this.serverStore = new ServerStore(gameFiles.root);
        this.addedServersStore = new AddedServersStore(gameFiles.root);
        GitHubConfig githubConfig = GitHubConfig.load();
        this.friendsService = githubConfig.isConfigured() ? new FriendsService(githubConfig) : null;
        this.deyCapesService = githubConfig.isConfigured() ? new DeyCapesService(githubConfig) : null;

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

        stage.setTitle("DeyLauncher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/app-icon.png")));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        root.setTop(buildTopBar());
        root.setCenter(buildCenterArea());

        if (firstLaunch) {
            javafx.geometry.Rectangle2D bounds =
            javafx.stage.Screen.getPrimary().getVisualBounds();

            prefs.startWidth = bounds.getWidth() * 0.75;
            prefs.startHeight = bounds.getHeight() * 0.75;
        }

        scene = new Scene(root, prefs.startWidth, prefs.startHeight);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        applyDynamicStyle();
        applyTheme();

        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.centerOnScreen();
        stage.setOnCloseRequest(e -> {
            appRunning = false;
            WavePulse.instance().stop(); // stop the online-dot wave animation with the window
            if (prefs.rememberWindowSize) {
                prefs.startWidth = stage.getWidth();
                prefs.startHeight = stage.getHeight();
                prefs.save();
            }
            publishOfflineOnExit();
        });
        stage.show();
        if (prefs.launcherStartFullscreen) stage.setFullScreen(true);

        loadVersionsAsync();
        syncPlayCardFromActiveIdentity();
        refreshAccountButton();
        restoreOnlineSessionAsync();
        publishPresenceQuietly();
        startPresenceTasks();
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
                if (appRunning) publishPresenceQuietly();
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
     * Publishes current presence in the background -- never blocks the UI, never shows an error
     * dialog on failure (friends.json being briefly unreachable shouldn't interrupt anything else).
     * Called on startup, on invisible-mode toggle, and whenever the Friends page opens.
     */
    private void publishPresenceQuietly() {
        if (friendsService == null) return;
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        String status = prefs.invisibleMode ? "OFFLINE" : "ONLINE";
        String address = (prefs.shareServerAddress && !prefs.invisibleMode
                && prefs.myServerAddress != null && !prefs.myServerAddress.isBlank())
                ? prefs.myServerAddress.trim() : null;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    friendsService.publishPresence(active.uuid, active.username, status, address);
                } catch (Exception ignored) {
                    // Best-effort -- a failed presence update isn't worth interrupting anything for.
                }
                return null;
            }
        };
        new Thread(task, "presence-publish").start();
    }

    /**
     * Publishes presence with this server's live address while it runs (only if the owner turned
     * on "Allow friends to join" for it), so online friends see it under "Friends Playing Now" and
     * can join straight in. On stop / when disabled, falls back to the manual Settings address.
     */
    private void publishPresenceWithServer(ServerInstance server, boolean running) {
        if (friendsService == null) return;
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        String status = prefs.invisibleMode ? "OFFLINE" : "ONLINE";
        String address;
        if (!prefs.invisibleMode && running && server.allowFriendsJoin) {
            PlayitTunnel tunnel = serverTunnels.get(server.id);
            String pub = tunnel != null ? tunnel.publicAddress() : null;
            address = (pub != null && !pub.isBlank()) ? pub : (localIpAddress() + ":" + server.port);
        } else {
            address = (prefs.shareServerAddress && prefs.myServerAddress != null && !prefs.myServerAddress.isBlank())
                    ? prefs.myServerAddress.trim() : null;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    friendsService.publishPresence(active.uuid, active.username, status, address,
                            running ? server.name : null, null);
                } catch (Exception ignored) {
                }
                return null;
            }
        };
        new Thread(task, "presence-publish").start();
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

        HBox bar = new HBox(20, brand, navGroup, spacer, accountBtn, settingsBtn);
        bar.setPadding(new Insets(16, 28, 16, 28));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
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
        friendsPageContent.getChildren().add(heading);

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

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, avatarView, dot, name, spacer);
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

    /** Unregisters every dot this page is wave-pulsing (called before a re-render). */
    private void clearWavePulseDots() {
        for (Node n : wavePulseDots) WavePulse.instance().unregister(n);
        wavePulseDots.clear();
    }

    /**
     * Turns a raw "host:port" server address into a friendly display for friends: if it matches one
     * of MY added/external servers (or a locally-hosted server), show that server's name, else fall
     * back to the bare address. Used with the SERVER icon so friends recognise where you're playing.
     */
    private String resolveServerDisplay(String address) {
        if (address == null || address.isBlank()) return address;
        String a = address.trim();
        String host = a;
        int colon = a.lastIndexOf(':');
        if (colon > 0) host = a.substring(0, colon).trim();
        for (var srv : addedServersStore.list()) {
            if (matchesAddress(srv.address(), a)) return srv.name();
        }
        for (var srv : serverStore.listAll()) {
            if (matchesAddress("localhost:" + srv.port, a) || matchesAddress(localIpAddress() + ":" + srv.port, a)) {
                return srv.name;
            }
        }
        return a;
    }

    private boolean matchesAddress(String candidate, String address) {
        if (candidate == null || address == null) return false;
        String c = candidate.trim();
        String a = address.trim();
        if (c.equalsIgnoreCase(a)) return true;
        String ch = c.contains(":") ? c.substring(0, c.lastIndexOf(':')).toLowerCase() : c.toLowerCase();
        String ah = a.contains(":") ? a.substring(0, a.lastIndexOf(':')).toLowerCase() : a.toLowerCase();
        return ch.equals(ah); // same host, default port implied
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
        FriendsData.UserEntry entry = (view != null) ? view.allUsers().get(friendUuid) : null;
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
    private Node buildNowPlayingSection(FriendsData.UserEntry entry, boolean online) {
        VBox box = new VBox(10);
        box.getStyleClass().add("profile-now-section");
        String address = (online && entry != null) ? entry.serverAddress : null;
        if (address == null || address.isBlank()) {
            return noticeText("Not playing on a shared server right now.");
        }
        String displayName = (entry.currentServerName != null && !entry.currentServerName.isBlank())
                ? entry.currentServerName : resolveServerDisplay(address);
        Node tile = serverTile(displayName, entry.currentServerIconUrl, 40);
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
     *  background. Name + port; no icon since self-hosted servers have no hosted image. */
    private void publishOwnedServers(PlayerIdentity active) {
        if (friendsService == null) return;
        java.util.List<FriendsData.ServerInfo> owned = new java.util.ArrayList<>();
        for (var srv : serverStore.listAll()) {
            if (srv.name != null && !srv.name.isBlank()) owned.add(new FriendsData.ServerInfo(srv.name, srv.port, null));
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
            long id = Math.abs((name == null ? "" : name).hashCode());
            ImageView iv = new ImageView();
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setSmooth(true);
            Circle clip = new Circle(size / 2.0);
            iv.setClip(clip);
            iv.setUserData(Boolean.TRUE);
            tile.getChildren().add(iv);
            final ImageView ref = iv;
            Task<java.nio.file.Path> fetch = new Task<>() {
                @Override protected java.nio.file.Path call() {
                    try {
                        java.nio.file.Path cache = gameFiles.root.resolve("server-icons")
                                .resolve("srv" + id + ".png");
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
                }
            });
            new Thread(fetch, "profile-server-icon").start();
        }
        return tile;
    }

    /** A "Join Server" button that pulses a green glow while the profile is open. */
    private Button glowingJoinButton(String address) {
        Button b = new Button("Join Server");
        b.getStyleClass().add("pill-button");
        setButtonIcon(b, IconFactory.Icon.PLAY, "Join");
        DropShadow glow = new DropShadow();
        glow.setColor(javafx.scene.paint.Color.rgb(120, 255, 150, 0.95));
        glow.setRadius(14);
        b.setEffect(glow);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 8)),
                new KeyFrame(Duration.seconds(0.9), new KeyValue(glow.radiusProperty(), 22)));
        tl.setAutoReverse(true);
        tl.setCycleCount(Animation.INDEFINITE);
        tl.play();
        b.setOnAction(e -> {
            selectNavTab(navHomeBtn);
            onPlay(address);
        });
        return b;
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

        Button addServerBtn = new Button("+  Add Server");
        addServerBtn.getStyleClass().add("pill-button");
        addServerBtn.setOnAction(e -> openAddServerDialog());

        Button createServerBtn = new Button("+  Create Server");
        createServerBtn.getStyleClass().add("play-button");
        createServerBtn.setOnAction(e -> openCreateServerDialog());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, heading, headerSpacer, addServerBtn, createServerBtn);
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
                .filter(e -> effectivelyOnline(e.getValue())
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
            String display = resolveServerDisplay(entry.serverAddress);

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
            Node serverIcon = icon(IconFactory.Icon.SERVER, 15);
            serverIcon.getStyleClass().add("server-icon");
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

        Label cardNameLabel = new Label(server.name);
        cardNameLabel.getStyleClass().add("mod-name");
        Label cardSubLabel = new Label(server.type.displayName() + "  ·  " + server.minecraftVersion);
        cardSubLabel.getStyleClass().add("notice-label");

        Label cardStatusBadge = badgeLabel(running);

        // Only this header (not the whole card) opens management on click, so the Join row
        // below has its own buttons that work independently without the click bubbling up.
        VBox cardHeader = new VBox(6, cardNameLabel, cardSubLabel, cardStatusBadge);
        cardHeader.setOnMouseClicked(e -> openServerManagementDialog(server));

        HBox cardJoinRow = buildJoinSplitRow(server);

        VBox card = new VBox(10, cardHeader, cardJoinRow);
        card.setPadding(new Insets(16));
        card.setPrefWidth(260);
        card.getStyleClass().add("skin-library-tile");
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
        Label name = new Label(s.name());
        name.getStyleClass().add("mod-name");
        Label address = new Label(s.address());
        address.getStyleClass().add("notice-label");
        VBox textBox = new VBox(2, name, address);

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

        HBox row = new HBox(12, textBox, spacer, joinBtn, removeBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        return row;
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
            serverVersionBox.setValue(current);
        });
        new Thread(versionsTask, "server-console-versions").start();

        Button changeVersionBtn = new Button("Change Version");
        changeVersionBtn.getStyleClass().add("pill-button");
        Label versionChangeNote = new Label();
        versionChangeNote.getStyleClass().add("notice-label");
        changeVersionBtn.setOnAction(ev -> {
            String chosen = serverVersionBox.getValue();
            if (chosen == null || chosen.equals(server.minecraftVersion)) return;
            if (runningServers.containsKey(server.id) && runningServers.get(server.id).isRunning()) {
                versionChangeNote.setText("Stop the server before changing its version.");
                return;
            }
            server.minecraftVersion = chosen;
            serverStore.save(server);
            Path dir = serverStore.serverDir(server.id);
            // The old server.jar (and, for Forge, its generated run.sh/run.bat/libraries) is for
            // the PREVIOUS version -- delete just the software, not the world, so the next Start
            // downloads a fresh server for the new version instead of silently reusing the old one.
            try {
                Files.deleteIfExists(dir.resolve("server.jar"));
                Files.deleteIfExists(dir.resolve("run.sh"));
                Files.deleteIfExists(dir.resolve("run.bat"));
                Files.deleteIfExists(dir.resolve("user_jvm_args.txt"));
            } catch (Exception ignored) {
            }
            versionChangeNote.setText("Version changed to " + chosen + " -- will download fresh on next Start.");
            renderServersPageContent();
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
                    Path jar = downloader.ensureServerJar(server, serverDir, javaBinary);

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

                    Platform.runLater(() -> serverConsoleArea.appendText("[DeyLauncher] Starting...\n"));
                    ServerProcessManager pm = new ServerProcessManager();
                    Process process = pm.start(server, serverDir, jar, javaBinary);
                    runningServers.put(server.id, pm);

                    try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String finalLine = line;
                            pm.observeConsoleLine(finalLine);
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
        Label iconLabel = sectionLabel("SERVER ICON (64x64 PNG)");
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
        Label dropHint = new Label("Drag & drop a 64x64 PNG here");
        dropHint.getStyleClass().add("notice-label");
        VBox dropZone = new VBox(8, iconPreview, dropHint);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setPrefSize(140, 120);
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
                    if (img != null && img.getWidth() == 64 && img.getHeight() == 64) {
                        Files.copy(files.get(0).toPath(), iconPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        iconPreview.setImage(new Image(iconPath.toUri().toString()));
                        dropHint.setText("Saved.");
                    } else {
                        dropHint.setText("Must be exactly 64x64 PNG.");
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

        VBox dropZone = new VBox(new Label("Drop " + folderKind + " .jar files here"));
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
            if (files != null) {
                for (var f : files) {
                    try {
                        addonsManager.addFile(f.toPath());
                    } catch (Exception ignored) {
                    }
                }
            }
            renderAddons.run();
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
        box.getChildren().addAll(heading, dropZone,
                sectionLabel("SEARCH ONLINE"), searchRow, searchStatus, resultsBox,
                listBox);
        return scroll;
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

        box.getChildren().addAll(
                sectionLabel("MEMORY"), memGrid,
                sectionLabel("PORT"), portField,
                sectionLabel("JAVA ENVIRONMENT"), javaEnvBox, javaEnvNote);
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
     * Auto-remembers the address of the server we're joining so it can be shared with friends
     * without anyone typing it in (replacing the old purely-manual Settings entry). The address is
     * only kept if it's actually a remote server -- joining our OWN locally-hosted server
     * (localhost / our own LAN IP) is never advertised, since no friend could reach it.
     */
    private void rememberCurrentlyJoined(String target) {
        if (target == null || target.isBlank()) return;
        String trimmed = target.trim();
        String host = trimmed;
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0) host = trimmed.substring(0, colon).trim();
        if (host.isEmpty()) return;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1")) return;
        try {
            String local = localIpAddress();
            if (local != null && local.equalsIgnoreCase(host)) return; // our own machine -- not joinable remotely
        } catch (Exception ignored) {
        }
        prefs.myServerAddress = trimmed;
        // Auto-enable sharing + publish right away so friends see "currently playing on <address>".
        prefs.shareServerAddress = true;
        prefs.save();
        publishPresenceQuietly();
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

        versionTileList = new VBox(10);
        versionTileList.getStyleClass().add("tile-list");

        VBox left = new VBox(18, modeToggleRow, versionTileList);
        left.getStyleClass().add("side-panel");
        left.setPadding(new Insets(20));
        left.setPrefWidth(240);
        left.setMinWidth(210);

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

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.getStyleClass().add("play-progress");

        VBox right = new VBox(14,
                mainHeadingLabel, mainDescriptionLabel, offlineNotice,
                modLoaderLabel, modLoaderBox,
                versionLabel, versionBox,
                modsBtn, playButton, progressBar);
        right.setPadding(new Insets(32));
        right.setAlignment(Pos.CENTER_LEFT);
        right.getStyleClass().add("play-card");
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox main = new HBox(20, left, right);
        main.setPadding(new Insets(24, 28, 8, 28));
        main.setAlignment(Pos.TOP_LEFT);

        setMode(prefs.lastDeyMode); // reopen in whichever mode you last played (DEY by default)
        restoreLastPlayedVersion();
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
            modLoaderBox.getItems().addAll("Vanilla", "Fabric", "Forge");
            modLoaderBox.setValue("Vanilla");
            modLoaderBox.setDisable(false);
            mainDescriptionLabel.setText(
                    "Clean, unmodified loaders -- pick Vanilla, Fabric, or Forge yourself.");
        }
        rebuildVersionTiles();
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

        Runnable[] refreshHolder = new Runnable[1];
        Runnable refresh = () -> {
            rowsBox.getChildren().clear();
            try {
                var list = mods.list();
                java.util.Set<String> lockedFamiliesShown = new java.util.HashSet<>();
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
                    rowsBox.getChildren().add(buildModRow(m, mods, refreshHolder[0], family != null));
                }
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
                refreshFixStatus(mods, list, fixBtn, fixStatus, mcVersion);
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

        VBox content = new VBox(12, dropZone, fixStatus, scroll, buttonRow);
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
        java.util.List<ModsManager.ModEntry> candidates = list.stream()
                .filter(m -> !isBundledFamily(m.fileName()))
                .toList();
        if (candidates.isEmpty() || mcVersion == null || mcVersion.isBlank()) {
            hideFixControls(fixBtn, fixStatus);
            return;
        }
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                ModrinthClient client = new ModrinthClient();
                for (var m : candidates) {
                    String slug = resolveModSlug(client, mods, m);
                    if (slug == null) continue; // can't identify the project -> leave untouched
                    try {
                        var compatible = client.compatibleVersionsLenient(slug, mcVersion);
                        if (compatible.isEmpty()) return true; // no build for this MC version -> incompatible
                        boolean installedMatches = compatible.stream()
                                .anyMatch(v -> ModrinthClient.versionFileMatches(v, m.fileName()));
                        if (!installedMatches) return true; // a compatible version exists but installed build is stale/wrong
                    } catch (Exception ignored) {
                        // Couldn't reach Modrinth for this one -- keep checking the rest.
                    }
                }
                return false;
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            boolean fix = Boolean.TRUE.equals(task.getValue());
            fixBtn.setVisible(fix);
            fixBtn.setManaged(fix);
            fixStatus.setVisible(fix);
            fixStatus.setManaged(fix);
            if (fix) {
                fixStatus.setText("Some installed mods aren't compatible with Minecraft " + mcVersion
                        + ". Click Fix Mods to convert them to a working version (or remove them if "
                        + "no compatible version exists).");
            }
        }));
        task.setOnFailed(e -> Platform.runLater(() -> hideFixControls(fixBtn, fixStatus)));
        new Thread(task, "mod-compat-scan").start();
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
     * The Fix action: for each installed NON-bundled mod that is incompatible with the current MC
     * version, download the newest compatible build from Modrinth and replace the installed jar; if
     * the project has no build for this MC version at all, remove the mod. Bundled DEY mods are never
     * touched here (they're repaired by their own installers). Runs in the background and re-renders +
     * summarizes on completion.
     */
    private void runFixIncompatibleMods(ModsManager mods, Button fixBtn, Label fixStatus,
                                        Runnable refresh, String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return;
        fixBtn.setDisable(true);
        fixStatus.setText("Checking each installed mod against Minecraft " + mcVersion + "...");
        fixStatus.setVisible(true);
        fixStatus.setManaged(true);
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                ModrinthClient client = new ModrinthClient();
                StringBuilder detail = new StringBuilder();
                int fixed = 0, removed = 0, skipped = 0;
                try {
                    for (var m : mods.list()) {
                        if (isBundledFamily(m.fileName())) { skipped++; continue; } // bundled mods: never delete
                        String slug = resolveModSlug(client, mods, m);
                        if (slug == null) { skipped++; continue; }
                        var compatible = client.compatibleVersionsLenient(slug, mcVersion);
                        if (compatible.isEmpty()) {
                            // No version of this mod supports the current MC version on Modrinth -> remove it.
                            try {
                                mods.delete(m.fileName());
                                removed++;
                                detail.append("  • Removed ").append(m.fileName())
                                        .append(" -- no version supports ").append(mcVersion).append(".\n");
                            } catch (Exception ex) {
                                skipped++;
                            }
                            continue;
                        }
                        boolean installedMatches = compatible.stream()
                                .anyMatch(v -> ModrinthClient.versionFileMatches(v, m.fileName()));
                        if (installedMatches) continue; // already a compatible build
                        ModrinthClient.ProjectVersion target = compatible.get(0); // newest compatible
                        try {
                            Path downloaded = client.download(target, mods.modsDir());
                            try { deleteJarsForSlug(mods.modsDir(), slug, downloaded); } catch (Exception ignored) {}
                            // "Converted" means the replacement replaces it one-for-one: remove the
                            // original (incompatible) jar too, so only the new, compatible version
                            // remains -- never leave both versions of the same mod installed.
                            try {
                                if (!downloaded.getFileName().toString().equals(m.fileName())) {
                                    mods.delete(m.fileName());
                                }
                            } catch (Exception ignored) {}
                            fixed++;
                            detail.append("  • Converted ").append(m.displayName()).append(" to version ")
                                    .append(target.versionNumber()).append(" (").append(mcVersion).append(").\n");
                        } catch (Exception ex) {
                            skipped++;
                        }
                    }
                } catch (Exception ex) {
                    return "ERROR\n" + ex.getMessage();
                }
                return "FIXED " + fixed + "\nREMOVED " + removed + "\n" + detail;
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
        int fixed = 0, removed = 0;
        StringBuilder bullets = new StringBuilder();
        for (String line : result.split("\n")) {
            if (line.startsWith("FIXED ")) fixed = parseIntOr(line.substring(6).trim(), 0);
            else if (line.startsWith("REMOVED ")) removed = parseIntOr(line.substring(8).trim(), 0);
            else if (line.startsWith("  •")) bullets.append(line).append('\n');
        }
        StringBuilder sb = new StringBuilder();
        if (fixed == 0 && removed == 0) {
            sb.append("All installed mods are already compatible -- nothing to fix.");
        } else {
            sb.append("Done. Converted ").append(fixed).append(" mod(s) and removed ").append(removed)
                    .append(" incompatible mod(s) with no working version.\n\nDetails:\n");
            sb.append(bullets);
        }
        return sb.toString();
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
        final double BORDER = 8;
        final double[] start = new double[2];   // screen x,y at press
        final double[] orig = new double[4];    // window x,y,w,h at press
        final double[] dragOff = new double[2]; // grab offset within the header
        final boolean[] west = new boolean[1], east = new boolean[1],
                       north = new boolean[1], south = new boolean[1];
        final boolean[] resizing = new boolean[1], dragging = new boolean[1];

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

        Runnable updateCursor = () -> {
            if (west[0] && north[0]) root.setCursor(javafx.scene.Cursor.NW_RESIZE);      // diagonal
            else if (east[0] && north[0]) root.setCursor(javafx.scene.Cursor.NE_RESIZE);  // other diagonal
            else if (west[0] && south[0]) root.setCursor(javafx.scene.Cursor.SW_RESIZE);  // other diagonal
            else if (east[0] && south[0]) root.setCursor(javafx.scene.Cursor.SE_RESIZE);  // diagonal
            else if (west[0] || east[0]) root.setCursor(javafx.scene.Cursor.H_RESIZE);    // horizontal
            else if (north[0] || south[0]) root.setCursor(javafx.scene.Cursor.V_RESIZE);  // vertical
            else root.setCursor(javafx.scene.Cursor.DEFAULT);
        };

        root.setOnMouseMoved(e -> {
            zone.accept(e.getX(), e.getY());
            updateCursor.run();
        });

        root.setOnMousePressed(e -> {
            if (!e.isPrimaryButtonDown()) return;
            zone.accept(e.getX(), e.getY());
            start[0] = e.getScreenX(); start[1] = e.getScreenY();
            orig[0] = win.getX(); orig[1] = win.getY(); orig[2] = win.getWidth(); orig[3] = win.getHeight();
            boolean edge = west[0] || east[0] || north[0] || south[0];
            if (edge) {
                resizing[0] = true; dragging[0] = false;
            } else if (e.getY() <= headerBottom.getAsDouble()) {
                dragOff[0] = e.getScreenX() - win.getX();
                dragOff[1] = e.getScreenY() - win.getY();
                dragging[0] = true; resizing[0] = false;
            } else {
                dragging[0] = false; resizing[0] = false;
            }
        });

        root.setOnMouseDragged(e -> {
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
                win.setX(e.getScreenX() - dragOff[0]);
                win.setY(e.getScreenY() - dragOff[1]);
            }
        });

        root.setOnMouseReleased(e -> {
            resizing[0] = false;
            dragging[0] = false;
            zone.accept(e.getX(), e.getY());
            updateCursor.run();
        });

        root.setOnMouseExited(e -> {
            if (!resizing[0] && !dragging[0]) root.setCursor(javafx.scene.Cursor.DEFAULT);
        });
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
    private HBox buildModRow(ModsManager.ModEntry mod, ModsManager mods, Runnable refresh, boolean locked) {
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

        HBox row = new HBox(14, iconTile, leading, textBox, spacer, trailing);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mod-row");
        if (locked) row.getStyleClass().add("mod-row-locked");
        else if (!mod.enabled()) row.getStyleClass().add("mod-row-disabled");
        return row;
    }

    private void applyTheme() {
        scene.getRoot().getStyleClass().removeAll("theme-dark", "theme-light");
        scene.getRoot().getStyleClass().add(darkMode ? "theme-dark" : "theme-light");
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

            CheckBox invisibleBox = new CheckBox("Appear offline to friends (invisible mode)");
            invisibleBox.setSelected(prefs.invisibleMode);
            invisibleBox.selectedProperty().addListener((o, a, b) -> {
                prefs.invisibleMode = b;
                prefs.save();
                publishPresenceQuietly(); // reflect the change immediately, not just on next app start
            });
            content.getChildren().add(invisibleBox);

            // ---- Friend-profile socials (published so friends can see them on your profile) ----
            if (friendsService != null) {
                content.getChildren().add(sectionLabel("FRIEND PROFILE"));
                content.getChildren().add(buildSocialsEditor(active));
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
                    pending[0].width(), pending[0].height(), pending[0].fullscreen(), pending[0].softwareOpenGl());
        });

        TextField widthField = new TextField(String.valueOf(settings.width()));
        TextField heightField = new TextField(String.valueOf(settings.height()));
        widthField.setPrefWidth(90);
        heightField.setPrefWidth(90);
        Runnable applyRes = () -> pending[0] = new GameLauncher.LaunchSettings(pending[0].ramMinMb(), pending[0].ramMaxMb(),
                parseIntOr(widthField.getText(), pending[0].width()),
                parseIntOr(heightField.getText(), pending[0].height()), pending[0].fullscreen(), pending[0].softwareOpenGl());
        widthField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });
        heightField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });

        CheckBox fullscreenBox = new CheckBox("Launch fullscreen");
        fullscreenBox.setSelected(settings.fullscreen());
        fullscreenBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(), b, pending[0].softwareOpenGl()));
        fullscreenBox.selectedProperty().addListener((o, a, b) -> markDirty.run());

        CheckBox softwareGlBox = new CheckBox("Software rendering (compatibility)");
        softwareGlBox.setSelected(settings.softwareOpenGl());
        softwareGlBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(), pending[0].fullscreen(), b));
        softwareGlBox.selectedProperty().addListener((o, a, b) -> markDirty.run());
        Label softwareGlHint = new Label("For machines whose GPU can't expose OpenGL 3.3 "
                + "(\"GLXBadFBConfig\" / \"Driver does not support OpenGL 3.3\"). Renders on the CPU "
                + "(slower but works) without admin rights. Linux only; no effect on Windows.");
        softwareGlHint.setWrapText(true);
        softwareGlHint.getStyleClass().add("settings-hint-label");

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
        grid.add(sectionLabel("SETTINGS WINDOW SIZE"), 0, row++, 2, 1);
        HBox settingsSizeBox = new HBox(6, settingsWidthField, new Label("x"), settingsHeightField);
        settingsSizeBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(settingsSizeBox, 0, row++, 2, 1);
        grid.add(settingsSizeNote, 0, row++, 2, 1);

        row++;
        grid.add(sectionLabel("FRIENDS"), 0, row++, 2, 1);
        CheckBox shareAddressBox = new CheckBox("Share my current server address with friends");
        shareAddressBox.setSelected(prefs.shareServerAddress);
        Label addressNote = new Label("Turned on, the address you're playing on is published to friends "
                + "automatically -- there's nothing to type. It fills itself with the last server you "
                + "joined (any external server such as mc.example.com:25565, or your own DEY server's "
                + "tunnel address). Ignored entirely while invisible mode (Account tab) is on.");
        addressNote.getStyleClass().add("notice-label");
        addressNote.setWrapText(true);
        shareAddressBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.shareServerAddress = b;
            prefs.save();
            publishPresenceQuietly(); // publish (or clear) my shared address right away
        });
        grid.add(shareAddressBox, 0, row++, 2, 1);
        grid.add(addressNote, 0, row++, 2, 1);

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
        progressBar.setVisible(true);
        progressBar.setProgress(-1); // indeterminate while downloading

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
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

                GameFiles files = new GameFiles();
                JavaRuntimeManager runtimeManager = new JavaRuntimeManager(files.root);

                updateMessage("Checking Java runtime...");
                var javaBinary = runtimeManager.ensureRuntimeFor(vanillaJson);

                JsonObject versionJson;
                if (modLoader.equals("Fabric")) {
                    updateMessage("Installing Fabric...");
                    FabricInstaller fabric = new FabricInstaller(manifest, files.root);
                    String loaderVersion = fabric.latestLoaderVersion(entry.id());
                    if (loaderVersion == null) throw new IllegalStateException(
                            "Fabric has no build for " + entry.id() + " yet.");
                    versionJson = fabric.install(entry.id(), loaderVersion);
                } else if (modLoader.equals("Forge")) {
                    updateMessage("Installing Forge (this runs Forge's own installer, may take a minute)...");
                    ForgeInstaller forge = new ForgeInstaller(manifest, files.root);
                    String forgeVersion = forge.recommendedOrLatestVersion(entry.id());
                    if (forgeVersion == null) throw new IllegalStateException(
                            "Forge has no build for " + entry.id() + " yet.");
                    versionJson = forge.install(entry.id(), forgeVersion, javaBinary.toString());
                } else {
                    versionJson = vanillaJson;
                }

                updateMessage("Downloading files (cached after first run)...");
                var prepared = files.prepare(versionJson);

                var gameDir = files.root.resolve("instances").resolve(entry.id()
                        + (modLoader.equals("Vanilla") ? "" : "-" + modLoader.toLowerCase()));
                java.nio.file.Files.createDirectories(gameDir);

                // DeyCapes mod integration: hand the github repo credentials to the installed mod so
                // it can fetch the capes.json map + cape textures for the private repo at runtime.
                // Only for DEY builds (the only ones that bundle DeyCapes). Best-effort.
                if (deyMode && modLoader.equals("Fabric") && deyCapesService != null && !"Vanilla".equals(modLoader)) {
                    try {
                        var cfgDir = gameDir.resolve("config").resolve("deycapes");
                        java.nio.file.Files.createDirectories(cfgDir);
                        var cfg = new java.util.Properties();
                        cfg.setProperty("owner", deyCapesService.gitConfig().owner());
                        cfg.setProperty("repo", deyCapesService.gitConfig().repo());
                        cfg.setProperty("token", deyCapesService.gitConfig().token());
                        cfg.setProperty("capesPath", deyCapesService.gitConfig().capesPath());
                        cfg.setProperty("capesOwnedPath", deyCapesService.gitConfig().ownershipPath());
                        cfg.setProperty("capesDir", deyCapesService.gitConfig().capesDir());
                        try (var out = java.nio.file.Files.newOutputStream(cfgDir.resolve("github.properties"))) {
                            cfg.store(out, "DeyCapes - read by the DeyCapes mod to fetch capes from the DeyLauncher repo");
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

                updateMessage(quickPlayTarget != null ? "Launching straight into " + quickPlayTarget + "..." : "Launching...");

                // Self-healing: on machines whose GPU can't provide OpenGL 3.3 (the classic Linux
                // "GLXBadFBConfig" / "Driver does not support OpenGL 3.3" crash), retry ONCE with Mesa
                // software rendering enabled, so the game gets a window even when the software-rendering
                // setting was left off. Only fires when the first attempt actually died from that
                // signature (runGameAndWait's diagnosis is null on a normal exit), and it flips the
                // toggle only for this single retry -- it is never persisted or forced on healthy runs.
                LaunchOutcome first = runGameAndWait(prepared, session, gameDir, settings, javaBinary, quickPlayTarget);
                if (first.diagnosis() != null && !settings.softwareOpenGl()) {
                    Platform.runLater(() -> log("Retrying once with software rendering (compatibility) enabled..."));
                    GameLauncher.LaunchSettings compat = new GameLauncher.LaunchSettings(
                            settings.ramMinMb(), settings.ramMaxMb(), settings.width(), settings.height(),
                            settings.fullscreen(), true);
                    runGameAndWait(prepared, session, gameDir, compat, javaBinary, quickPlayTarget);
                }
                return null;
            }
        };
        task.messageProperty().addListener((obs, old, msg) -> log(msg));
        task.setOnSucceeded(e -> {
            playButton.setDisable(false);
            progressBar.setVisible(false);
        });
        task.setOnFailed(e -> {
            playButton.setDisable(false);
            progressBar.setVisible(false);
            log("Error: " + task.getException());
        });
        new Thread(task, "play-task").start();
    }

    private void log(String line) {
        logArea.appendText(line + "\n");
    }

    /** Result of one game run: the exit code plus a human-readable crash diagnosis (null on a normal exit). */
    private record LaunchOutcome(int exit, String diagnosis) {}

    /**
     * Launches the game and streams its output into the log, keeping a bounded recent-output tail so a
     * native/GL crash can be explained (see {@link LaunchDiagnostics}) instead of just showing the raw
     * exit code. Returns the exit code and diagnosis so the caller can decide whether to self-heal.
     */
    private LaunchOutcome runGameAndWait(GameFiles.PreparedVersion prepared, AuthSession session, Path gameDir,
                                         GameLauncher.LaunchSettings s, Path javaBinary, String quickPlayTarget) throws Exception {
        Process process = new GameLauncher().launch(prepared, session, gameDir, s, javaBinary.toString(), quickPlayTarget);

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
            }
        }
        int exit = process.waitFor();
        Platform.runLater(() -> log("Game exited with code " + exit));
        String diagnosis = LaunchDiagnostics.analyze(new java.util.ArrayList<>(recent), exit);
        if (diagnosis != null) {
            Platform.runLater(() -> log("\n==== Crash diagnostic ====\n" + diagnosis));
        }
        return new LaunchOutcome(exit, diagnosis);
    }

    // ---- Cross-platform icon helpers ----

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
}
