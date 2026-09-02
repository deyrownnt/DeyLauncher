package com.deylauncher.ui;

import com.deylauncher.auth.AuthSession;
import com.deylauncher.auth.MicrosoftAuth;
import com.deylauncher.auth.TokenVault;
import com.deylauncher.friends.*;
import com.deylauncher.identity.*;
import com.deylauncher.launch.GameFiles;
import com.deylauncher.launch.GameLauncher;
import com.deylauncher.launch.JavaRuntimeManager;
import com.deylauncher.modloader.FabricInstaller;
import com.deylauncher.modloader.FabricApiInstaller;
import com.deylauncher.modloader.ForgeInstaller;
import com.deylauncher.modloader.SodiumInstaller;
import com.google.gson.JsonObject;
import com.deylauncher.version.VersionManifest;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.input.TransferMode;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
    private FriendsCache friendsCache;

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
        this.darkMode = prefs.darkMode;
        this.identityStore = new IdentityStore(gameFiles.root);
        this.settings = new GameLauncher.LaunchSettings(prefs.ramMinMb, prefs.ramMaxMb,
                prefs.gameWidth, prefs.gameHeight, prefs.fullscreen);
        this.friendsCache = new FriendsCache(gameFiles.root);
        GitHubConfig githubConfig = GitHubConfig.load();
        this.friendsService = githubConfig.isConfigured() ? new FriendsService(githubConfig) : null;

        stage.setTitle("DeyLauncher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/app-icon.png")));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        root.setTop(buildTopBar());
        root.setCenter(buildCenterArea());

        scene = new Scene(root, prefs.startWidth, prefs.startHeight);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        applyDynamicStyle();
        applyTheme();

        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.centerOnScreen();
        stage.setOnCloseRequest(e -> {
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

    /** Best-effort "I'm closing" mark -- won't catch a hard crash, only a normal window close. */
    private void publishOfflineOnExit() {
        if (friendsService == null) return;
        PlayerIdentity active = identityStore.getActive();
        if (active == null) return;
        try {
            friendsService.publishPresence(active.uuid, active.username, "OFFLINE", null);
        } catch (Exception ignored) {
            // Nothing useful to do here -- the window is already closing.
        }
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
        Label logo = new Label("⛏");
        logo.getStyleClass().add("logo-glyph");

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

        Button settingsBtn = new Button("⚙");
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
            pageHost.getChildren().setAll(buildPlaceholderPage("Servers",
                    "Browse and quick-join self-hosted servers from here -- coming soon."));
        }
    }

    private VBox buildPlaceholderPage(String title, String subtitle) {
        Label icon = new Label("🛠");
        icon.getStyleClass().add("logo-glyph");
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
                boolean online = entry != null && "ONLINE".equals(entry.status);
                String address = entry != null ? entry.serverAddress : null;

                Region dot = new Region();
                dot.getStyleClass().addAll("account-status-dot", online ? "dot-online" : "dot-offline");

                Label name = new Label(friend.username);
                name.getStyleClass().add("mod-name");

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox row = new HBox(10, dot, name, spacer);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("mod-row");

                if (online && address != null && !address.isBlank()) {
                    Button joinBtn = new Button("▶  Join");
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
        if (identity.skinSource == SkinSource.DEFAULT || !java.nio.file.Files.exists(skinPath)) return defaultSteveImage();
        try {
            Image full = new Image(skinPath.toUri().toString());
            return faceThumbnail(full);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shared face crop used both by the main account button and the Skin Profile tiles in the
     * Skins tab -- the tiles used to pass the whole raw skin sheet to skinTile() instead of this,
     * which is why offline skin-profile thumbnails showed the entire texture instead of a face.
     */
    private Image faceThumbnail(Image skin) {
        if (skin == null) return defaultSteveImage();
        javafx.scene.image.PixelReader reader = skin.getPixelReader();
        if (reader == null || skin.getWidth() < 16 || skin.getHeight() < 16) return defaultSteveImage();
        try {
            return new javafx.scene.image.WritableImage(reader, 8, 8, 8, 8);
        } catch (Exception e) {
            return defaultSteveImage();
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

        modsBtn = new Button("🧩  Mods");
        modsBtn.getStyleClass().add("pill-button");
        modsBtn.setMaxWidth(Double.MAX_VALUE);
        modsBtn.setOnAction(e -> openModsDialog());
        modsBtn.setVisible(false);
        modsBtn.setManaged(false);

        playButton = new Button("▶   PLAY");
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
            modLoaderBox.getItems().addAll("Fabric", "Forge");
            modLoaderBox.setValue("Fabric");
            modLoaderBox.setDisable(false);
            mainDescriptionLabel.setText(
                    "DEY builds run a curated set of performance and quality-of-life mods -- "
                            + "Sodium + Fabric API install automatically on Fabric, Embeddium on Forge, no setup needed.");
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

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Mods -- " + versionBox.getValue() + " (" + modLoaderBox.getValue() + ")");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        dialog.getDialogPane().getStylesheets().add(
                DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        dialog.getDialogPane().getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(560, 520);
        dialog.getDialogPane().setMinWidth(420);
        dialog.getDialogPane().setMinHeight(360);

        VBox rowsBox = new VBox(10);
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("mods-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label dropZone = new Label("⬇  Drag & drop mod .jar files here, or use Add Mods below");
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setMaxWidth(Double.MAX_VALUE);
        dropZone.setAlignment(Pos.CENTER);

        Button addBtn = new Button("+  Add Mods");
        addBtn.getStyleClass().add("pill-button");

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
                        else if (fn.startsWith("fabric-api-")) family = "fabric-api";
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
                if (list.isEmpty()) {
                    Label empty = new Label(deyMode
                            ? "No mods yet -- Sodium/Embeddium and Fabric API install automatically the first time you hit Play."
                            : "No mods yet -- drag some in above.");
                    empty.getStyleClass().add("notice-label");
                    rowsBox.getChildren().add(empty);
                }
            } catch (Exception ex) {
                log("Failed to list mods: " + ex.getMessage());
            }
        };
        refreshHolder[0] = refresh;

        // Best-effort: try to have the bundled mods ready by the time the dialog opens too, not
        // just on Play, so the list already reflects reality instead of only updating after a
        // launch. Performance mod: Sodium on Fabric, Embeddium on Forge. Fabric API: Fabric only.
        if (deyMode && versionBox.getValue() != null) {
            String mcVersion = versionBox.getValue();
            String modLoader = modLoaderBox.getValue();
            Task<Void> installTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    new SodiumInstaller(modLoader).ensureInstalled(mcVersion, mods.modsDir());
                    if ("Fabric".equals(modLoader)) {
                        new FabricApiInstaller().ensureInstalled(mcVersion, mods.modsDir());
                    }
                    return null;
                }
            };
            installTask.setOnSucceeded(e -> refresh.run());
            installTask.setOnFailed(e -> { /* silent here -- onPlay() reports failures to the log */ });
            new Thread(installTask, "dey-mod-installer").start();
        }

        addBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select mod jars");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mod jars", "*.jar"));
            List<java.io.File> picked = chooser.showOpenMultipleDialog(dialog.getOwner());
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

        dropZone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        dropZone.setOnDragEntered(e -> dropZone.getStyleClass().add("drop-zone-active"));
        dropZone.setOnDragExited(e -> dropZone.getStyleClass().remove("drop-zone-active"));
        dropZone.setOnDragDropped(e -> {
            var db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                for (var f : db.getFiles()) {
                    if (f.getName().endsWith(".jar")) {
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
            refresh.run();
        });

        VBox content = new VBox(14, dropZone, scroll, addBtn);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("mods-dialog-content");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        dialog.getDialogPane().setContent(content);
        refresh.run();
        dialog.showAndWait();
    }

    /** locked=true (a real sodium-*.jar under a DEY instance) shows a lock icon and a
     * "BUNDLED" badge instead of the enable checkbox and delete button -- it's a real jar
     * like any other, just not one the player is meant to disable or remove by hand. */
    private HBox buildModRow(ModsManager.ModEntry mod, ModsManager mods, Runnable refresh, boolean locked) {
        Node leading;
        if (locked) {
            Label lockIcon = new Label("🔒");
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Node trailing;
        if (locked) {
            Label badge = new Label("BUNDLED");
            badge.getStyleClass().add("mod-bundled-badge");
            trailing = badge;
        } else {
            Button deleteBtn = new Button("🗑");
            deleteBtn.getStyleClass().add("mod-delete-button");
            deleteBtn.setOnAction(e -> {
                try {
                    mods.delete(mod.fileName());
                    refresh.run();
                } catch (Exception ex) {
                    log("Failed to delete " + mod.fileName() + ": " + ex.getMessage());
                }
            });
            trailing = deleteBtn;
        }

        HBox row = new HBox(14, leading, textBox, spacer, trailing);
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
    private VBox buildAccountTab(Dialog<Void> dialog, Runnable[] refreshHolder) {
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
            signInBtn.setOnAction(e -> onSignInWithMicrosoft(signInBtn, deviceCodeBox, statusLabel, dialog, refreshHolder));
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
            Button applyBtn = new Button("✔  Apply");
            applyBtn.getStyleClass().addAll("settings-apply-button", "settings-apply-button-ready");
            applyBtn.setOnAction(e -> {
                String name = offlineNameField.getText().trim();
                if (name.isEmpty()) {
                    new Alert(Alert.AlertType.WARNING, "Enter a username first.", ButtonType.OK).showAndWait();
                    return;
                }
                AuthSession derived = AuthSession.offline(name);
                PlayerIdentity identity = identityStore.loadOrCreate(derived.uuid(), derived.username(), AccountType.OFFLINE);
                identityStore.setActive(identity.uuid);
                syncPlayCardFromActiveIdentity();
                refreshAccountButton();
                refreshHolder[0].run();
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
            Label badge = new Label(active.accountType == AccountType.ONLINE ? "● ONLINE" : "○ OFFLINE");
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

            Button logoutBtn = new Button("🚪  Log Out");
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

    // ==================== SKINS TAB ====================

    /** LEFT: skin profile list + capes. RIGHT: 3D preview + Import/Remove. Matches the spec's layout sketch. */
    private HBox buildSkinsTab(Dialog<Void> dialog) {
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

        ScrollPane leftScroll = new ScrollPane(new VBox(20,
                sectionLabel("SKIN PROFILES"), profilesBox,
                sectionLabel("CAPES"), capesBox));
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

        Button importBtn = new Button("📁  Import Skin");
        importBtn.getStyleClass().add("pill-button");
        Button removeBtn = new Button("🗑  Remove / Restore");
        removeBtn.getStyleClass().add("pill-button");
        Label capeLimitNote = new Label();
        capeLimitNote.getStyleClass().add("notice-label");
        capeLimitNote.setWrapText(true);
        capeLimitNote.setManaged(false);
        capeLimitNote.setVisible(false);

        HBox actionRow = new HBox(10, importBtn, removeBtn);
        actionRow.setAlignment(Pos.CENTER);
        Button applyCapeBtn = new Button("✔  APPLY CAPE");
        applyCapeBtn.getStyleClass().add("settings-apply-button");
        updateCapeApplyButton(applyCapeBtn);
        right.getChildren().addAll(previewHost, modelRow, actionRow, applyCapeBtn, capeLimitNote);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> refreshSkinsTab(profilesBox, capesBox, dialog, refresh, classicBtn, slimBtn, applyCapeBtn);
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

        importBtn.setOnAction(e -> onImportSkinProfile(dialog, refresh));
        removeBtn.setOnAction(e -> onRemoveSkin(dialog, refresh));

        HBox root = new HBox(left, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        return root;
    }

    /** Rebuilds skin profile rows, cape rows, and the 3D preview from current disk/account state. */
    private void refreshSkinsTab(TilePane profilesBox, TilePane capesBox, Dialog<Void> dialog, Runnable[] refresh,
                                  RadioButton classicBtn, RadioButton slimBtn, Button applyCapeBtn) {
        profilesBox.getChildren().clear();
        capesBox.getChildren().clear();
        PlayerIdentity active = identityStore.getActive();

        if (active == null) {
            Label none = new Label("No account set up yet -- use the Account tab first.");
            none.getStyleClass().add("notice-label");
            none.setWrapText(true);
            profilesBox.getChildren().add(none);
            skinPreview.update(defaultSteveImage(), SkinModel.CLASSIC, null);
            capeDirty = false;
            updateCapeApplyButton(applyCapeBtn);
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

        // ---- 3D preview ----
        java.nio.file.Path skinPath = identityStore.skinFile(active.uuid);
        Image skinImage = (active.skinSource != SkinSource.DEFAULT && java.nio.file.Files.exists(skinPath))
                ? new Image(skinPath.toUri().toString()) : defaultSteveImage();
        skinPreview.update(skinImage, active.skinModel, selectedCapeImage);
    }

    private Image skinImage(java.nio.file.Path path) {
        return java.nio.file.Files.exists(path) ? new Image(path.toUri().toString(), false) : defaultSteveImage();
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
            button.setText("✔  APPLY CAPE CHANGE");
        } else {
            button.setDisable(true);
            button.setText("✔  CAPE APPLIED");
        }
    }

    /** Commits the staged cape selection with one request, avoiding rate-limit spam. */
    private void onApplyCape(Button applyCapeBtn, Runnable[] refresh) {
        if (!capeDirty) return;
        String targetCapeId = selectedCapeId;
        String tokenSnapshot = liveOnlineAccessToken;
        Task<MinecraftSkinService.SkinChangeResult> task = new Task<>() {
            @Override protected MinecraftSkinService.SkinChangeResult call() {
                return targetCapeId == null
                        ? new MinecraftSkinService().unequipCape(tokenSnapshot)
                        : new MinecraftSkinService().equipCape(tokenSnapshot, targetCapeId);
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

    private void onImportSkinProfile(Dialog<Void> dialog, Runnable[] refresh) {
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

    private void onRemoveSkin(Dialog<Void> dialog, Runnable[] refresh) {
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

    private void onSignInWithMicrosoft(Button signInBtn, VBox deviceCodeBox, Label statusLabel, Dialog<Void> dialog, Runnable[] refreshHolder) {
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

        Button copyBtn = new Button("📋  Copy Code");
        copyBtn.getStyleClass().add("pill-button");
        copyBtn.setOnAction(e -> {
            var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            var contentToCopy = new javafx.scene.input.ClipboardContent();
            contentToCopy.putString(userCode);
            clipboard.setContent(contentToCopy);
            copyBtn.setText("✔  Copied!");
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
     * already on whichever tab makes sense for how it was invoked. */
    private void openPreferencesDialog(String initialTabTitle) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Settings");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        dialog.getDialogPane().getStylesheets().add(
                DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
        dialog.getDialogPane().getStyleClass().addAll("root-pane", darkMode ? "theme-dark" : "theme-light");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.setResizable(true);

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
        refreshAccountHolder[0] = () -> accountScroll.setContent(buildAccountTab(dialog, refreshAccountHolder));
        refreshAccountHolder[0].run();

        // ---- Skins ----
        Tab skinsTab = new Tab("Skins", buildSkinsTab(dialog));

        // ---- Game ----
        GameLauncher.LaunchSettings[] pending = { settings };
        SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        Runnable markDirty = () -> dirty.set(true);

        ScrollPane gameScroll = new ScrollPane(buildGameSettingsPane(pending, markDirty));
        gameScroll.setFitToWidth(true);
        gameScroll.getStyleClass().add("settings-scroll");
        Tab gameTab = new Tab("Game", gameScroll);

        // ---- Launcher ----
        ScrollPane launcherScroll = new ScrollPane(buildLauncherSettingsPane(markDirty, dialog.getDialogPane()));
        launcherScroll.setFitToWidth(true);
        launcherScroll.getStyleClass().add("settings-scroll");
        Tab launcherTab = new Tab("Launcher", launcherScroll);

        tabs.getTabs().addAll(accountTab, skinsTab, gameTab, launcherTab);
        for (Tab t : tabs.getTabs()) {
            if (t.getText().equals(initialTabTitle)) {
                tabs.getSelectionModel().select(t);
                break;
            }
        }

        Label savedLabel = new Label();
        savedLabel.getStyleClass().add("notice-label");

        Button applyBtn = new Button("✔  APPLY");
        applyBtn.getStyleClass().add("settings-apply-button");
        dirty.addListener((obs, old, changed) -> {
            if (changed) {
                if (!applyBtn.getStyleClass().contains("settings-apply-button-ready"))
                    applyBtn.getStyleClass().add("settings-apply-button-ready");
                applyBtn.setText("✔  APPLY CHANGES");
            } else {
                applyBtn.getStyleClass().remove("settings-apply-button-ready");
                applyBtn.setText("✔  APPLY");
            }
        });
        applyBtn.setOnAction(e -> {
            settings = pending[0];
            prefs.ramMinMb = settings.ramMinMb();
            prefs.ramMaxMb = settings.ramMaxMb();
            prefs.gameWidth = settings.width();
            prefs.gameHeight = settings.height();
            prefs.fullscreen = settings.fullscreen();
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

        dialog.getDialogPane().setContent(root);
        // Configurable from Launcher > Settings Window Size -- defaults big enough for the
        // Skins tab's profile/cape list + large 3D preview to actually have room to breathe.
        dialog.getDialogPane().setPrefSize(prefs.settingsWindowWidth, prefs.settingsWindowHeight);
        dialog.getDialogPane().setMinWidth(760);
        dialog.getDialogPane().setMinHeight(520);
        dialog.setOnHidden(e -> { if (skinPreview != null) skinPreview.stop(); });
        dialog.showAndWait();
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
                    pending[0].width(), pending[0].height(), pending[0].fullscreen());
        });

        TextField widthField = new TextField(String.valueOf(settings.width()));
        TextField heightField = new TextField(String.valueOf(settings.height()));
        widthField.setPrefWidth(90);
        heightField.setPrefWidth(90);
        Runnable applyRes = () -> pending[0] = new GameLauncher.LaunchSettings(pending[0].ramMinMb(), pending[0].ramMaxMb(),
                parseIntOr(widthField.getText(), pending[0].width()),
                parseIntOr(heightField.getText(), pending[0].height()), pending[0].fullscreen());
        widthField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });
        heightField.textProperty().addListener((o, a, b) -> { applyRes.run(); markDirty.run(); });

        CheckBox fullscreenBox = new CheckBox("Launch fullscreen");
        fullscreenBox.setSelected(settings.fullscreen());
        fullscreenBox.selectedProperty().addListener((o, a, b) -> pending[0] = new GameLauncher.LaunchSettings(
                pending[0].ramMinMb(), pending[0].ramMaxMb(), pending[0].width(), pending[0].height(), b));
        fullscreenBox.selectedProperty().addListener((o, a, b) -> markDirty.run());

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
        return grid;
    }

    private GridPane buildLauncherSettingsPane(Runnable markDirty, DialogPane dialogPane) {
        ToggleButton themeToggle = new ToggleButton(darkMode ? "🌙  Dark" : "☀  Light");
        themeToggle.getStyleClass().add("pill-button");
        themeToggle.setSelected(darkMode);
        themeToggle.setOnAction(e -> {
            darkMode = themeToggle.isSelected();
            themeToggle.setText(darkMode ? "🌙  Dark" : "☀  Light");
            prefs.darkMode = darkMode;
            prefs.save();
            applyTheme();
            // applyTheme() only re-themes the main window's scene -- this dialog is its own
            // top-level Stage under the hood, so it needs its own style classes swapped too,
            // right now, instead of waiting for the user to close and reopen Settings.
            dialogPane.getStyleClass().removeAll("theme-dark", "theme-light");
            dialogPane.getStyleClass().add(darkMode ? "theme-dark" : "theme-light");
        });

        Slider uiScaleSlider = new Slider(0.75, 1.75, prefs.uiScale);
        uiScaleSlider.setShowTickMarks(true);
        uiScaleSlider.setMajorTickUnit(0.25);
        uiScaleSlider.setPrefWidth(300);
        Label uiScaleLabel = new Label(Math.round(prefs.uiScale * 100) + "%");
        uiScaleLabel.getStyleClass().add("settings-value-label");

        Slider textScaleSlider = new Slider(0.75, 1.75, prefs.textScale);
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
            // applyDynamicStyle() only touches the main window's scene -- this dialog carries
            // its own separate copy of the same data: URI stylesheet, added once at open time,
            // so it needs to be swapped out here too or the scale only visibly changes on the
            // window behind the dialog until you close and reopen Settings.
            dialogPane.getStylesheets().removeIf(s -> s.startsWith("data:text/css"));
            dialogPane.getStylesheets().add(DynamicStyle.dataUri(prefs.uiScale, prefs.textScale, prefs.fontFamily));
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
        TextField serverAddressField = new TextField(prefs.myServerAddress);
        serverAddressField.setPromptText("e.g. mc.example.com:25565");
        serverAddressField.getStyleClass().add("input-field");
        serverAddressField.setDisable(!prefs.shareServerAddress);
        Label addressNote = new Label("Enter the address yourself when you're hosting or playing on "
                + "one -- DeyLauncher can't detect this automatically. Ignored entirely while "
                + "invisible mode (Account tab) is on.");
        addressNote.getStyleClass().add("notice-label");
        addressNote.setWrapText(true);
        shareAddressBox.selectedProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.shareServerAddress = b;
            serverAddressField.setDisable(!b);
            prefs.save();
        });
        serverAddressField.textProperty().addListener((o, a, b) -> {
            markDirty.run();
            prefs.myServerAddress = b;
            prefs.save();
        });
        grid.add(shareAddressBox, 0, row++, 2, 1);
        grid.add(serverAddressField, 0, row++, 2, 1);
        grid.add(addressNote, 0, row++, 2, 1);

        return grid;
    }


    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
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

                if ((modLoader.equals("Fabric") || modLoader.equals("Forge")) && deyMode) {
                    updateMessage("Making sure " + (modLoader.equals("Forge") ? "Embeddium" : "Sodium") + " is installed...");
                    try {
                        String installed = new SodiumInstaller(modLoader).ensureInstalled(entry.id(), gameDir.resolve("mods"));
                        if (installed != null) {
                            String finalName = installed;
                            Platform.runLater(() -> log("Installed " + finalName));
                        }
                    } catch (Exception sodiumEx) {
                        String msg = sodiumEx.getMessage();
                        Platform.runLater(() -> log("Couldn't auto-install the performance mod (continuing without it): " + msg));
                    }
                }
                if (modLoader.equals("Fabric") && deyMode) {
                    // Fabric API only -- there's no Forge build and no Forge equivalent to swap
                    // in, so this never runs for a DEY Forge instance.
                    updateMessage("Making sure Fabric API is installed...");
                    try {
                        String installed = new FabricApiInstaller().ensureInstalled(entry.id(), gameDir.resolve("mods"));
                        if (installed != null) {
                            String finalName = installed;
                            Platform.runLater(() -> log("Installed " + finalName));
                        }
                    } catch (Exception apiEx) {
                        String msg = apiEx.getMessage();
                        Platform.runLater(() -> log("Couldn't auto-install Fabric API (continuing without it): " + msg));
                    }
                }

                updateMessage(quickPlayTarget != null ? "Launching straight into " + quickPlayTarget + "..." : "Launching...");
                Process process = new GameLauncher().launch(prepared, session, gameDir, settings, javaBinary.toString(), quickPlayTarget);

                // Stream the game's own output into our log area instead of inheriting the console.
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> log(finalLine));
                    }
                }
                int exit = process.waitFor();
                Platform.runLater(() -> log("Game exited with code " + exit));
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
}
