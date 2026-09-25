import com.deylauncher.modpack.*;
import com.deylauncher.ui.LauncherApp;
import com.deylauncher.ui.LauncherPrefs;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.*;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Drives the REAL LauncherApp (JavaFX, software pipeline, Xvfb) through the modpack/version/loader flow. */
public class UiFlowHarness {
    static LauncherApp app;
    static Stage stage;
    static Path home, root;
    static int pass = 0, fail = 0;
    static final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());

    // ---- reflection helpers (LauncherApp state is private) ----
    static Object field(String n) throws Exception { Field f = LauncherApp.class.getDeclaredField(n); f.setAccessible(true); return f.get(app); }
    static Object call(String n, Object... args) throws Exception {
        for (Method m : LauncherApp.class.getDeclaredMethods()) {
            if (m.getName().equals(n) && m.getParameterCount() == args.length) { m.setAccessible(true);
                try { return m.invoke(app, args); } catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); } }
        }
        throw new NoSuchMethodException(n);
    }
    static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> { try { f.complete(c.call()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get(60, TimeUnit.SECONDS);
    }
    static void fxv(Callable<?> c) throws Exception { fx(() -> { c.call(); return null; }); }
    static void settle() throws Exception { Thread.sleep(250); fxv(() -> null); Thread.sleep(150); }
    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "   -> " + detail));
    }
    static void snap(String name) throws Exception {
        fxv(() -> {
            WritableImage img = stage.getScene().snapshot(null);
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader pr = img.getPixelReader();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
            ImageIO.write(bi, "png", Path.of("/tmp/shots", name + ".png").toFile());
            return null;
        });
    }

    /** A user picks an entry from the dropdown: only listed items are selectable. */
    static boolean userPicks(ComboBox<String> box, String item) throws Exception {
        return fx(() -> { if (box.isDisabled() || !box.getItems().contains(item)) return false; box.getSelectionModel().select(item); return true; });
    }
    static void clickTile(int index) throws Exception {
        fxv(() -> { VBox tiles = (VBox) field("versionTileList"); int i = 0;
            for (Node n : tiles.getChildren()) if (n instanceof Button bt) { if (i++ == index) { bt.fire(); break; } } return null; });
    }
    static Button packTile(String name) throws Exception {
        return fx(() -> { VBox box = (VBox) field("modpackTileBox");
            for (Node n : box.getChildren()) if (n instanceof Button b && name.equals(b.getText())) return b; return null; });
    }
    static void clickPackTile(String name) throws Exception {
        Button b = packTile(name); if (b == null) throw new IllegalStateException("no sidebar tile for " + name);
        fxv(() -> { b.fire(); return null; }); settle();
    }
    static boolean tileActive(Button b) throws Exception { return fx(() -> b.getStyleClass().contains("version-tile-active")); }
    /** "ICON(WxH)" of the artwork actually laid out inside a pack tile, or "GLYPH". */
    static String tileArt(String name) throws Exception {
        Button b = packTile(name);
        return fx(() -> { Node g = b.getGraphic();
            if (g instanceof StackPane sp && sp.getChildren().get(0) instanceof ImageView iv && iv.getImage() != null)
                return String.format("ICON(src %.0fx%.0f, shown %.1fx%.1f, box %.0fx%.0f)", iv.getImage().getWidth(), iv.getImage().getHeight(),
                        iv.getBoundsInParent().getWidth(), iv.getBoundsInParent().getHeight(), sp.getPrefWidth(), sp.getPrefHeight());
            return "GLYPH"; });
    }
    static ImageView backdrop() throws Exception { return (ImageView) field("packBackdrop"); }
    static double contentRightPad() throws Exception { return fx(() -> ((VBox) field("playContent")).getPadding().getRight()); }
    static String heading() throws Exception { return fx(() -> ((Label) field("mainHeadingLabel")).getText()); }
    // ---- state readers ----
    @SuppressWarnings("unchecked") static ComboBox<String> versionBox() throws Exception { return (ComboBox<String>) field("versionBox"); }
    @SuppressWarnings("unchecked") static ComboBox<String> loaderBox() throws Exception { return (ComboBox<String>) field("modLoaderBox"); }
    static Button modpackBtn() throws Exception { return (Button) field("modpackBtn"); }
    static ModpackSelection sel() throws Exception { return (ModpackSelection) field("modpackSelection"); }
    static ModpackSelection.Target target() throws Exception { return (ModpackSelection.Target) fx(() -> call("currentLaunchTarget")); }
    static String attachedName() throws Exception { return fx(() -> sel().attached() == null ? null : sel().attached().name); }
    static String dirName() throws Exception { return target().instanceDir().getFileName().toString(); }
    static String state() throws Exception {
        return fx(() -> "[" + versionBox().getValue() + " / " + loaderBox().getValue() + " / dey=" + field("deyMode")
                + " / attached=" + (sel().attached() == null ? null : sel().attached().name) + " / dir=" + target().instanceDir().getFileName() + "]");
    }
    static boolean selectorsEditable() throws Exception {
        return fx(() -> !versionBox().isDisabled() && !loaderBox().isDisabled());
    }
    /** What the modpack button currently shows: "ICON(w x h ratio)" for a real pack icon, "GLYPH" for the generic one. */
    static String buttonGraphic() throws Exception {
        return fx(() -> {
            Node g = modpackBtn().getGraphic();
            if (g instanceof StackPane sp && !sp.getChildren().isEmpty() && sp.getChildren().get(0) instanceof ImageView iv) {
                Image im = iv.getImage();
                double b = iv.getBoundsInParent().getWidth(), bh = iv.getBoundsInParent().getHeight();
                return String.format("ICON(src %.0fx%.0f, shown %.1fx%.1f, box %.0fx%.0f)", im.getWidth(), im.getHeight(), b, bh, sp.getPrefWidth(), sp.getPrefHeight());
            }
            return g == null ? "NONE" : "GLYPH(" + g.getClass().getSimpleName() + ")";
        });
    }
    static boolean ring() throws Exception { return fx(() -> modpackBtn().getStyleClass().contains("modpack-attached")); }

    /** Selects a pack exactly as a user does: open the modpack menu, click the pack's row. */
    static void pickPackFromMenu(String packName) throws Exception {
        fxv(() -> { modpackBtn().fire(); return null; });
        settle();
        Boolean clicked = fx(() -> {
            for (Window w : new ArrayList<>(Window.getWindows())) {
                if (!(w instanceof PopupWindow) || !w.isShowing()) continue;
                Deque<Node> q = new ArrayDeque<>(); q.add(w.getScene().getRoot());
                while (!q.isEmpty()) { Node n = q.poll();
                    if (n instanceof Button b && b.getText() != null && b.getText().startsWith(packName + "  ")) { b.fire(); return true; }
                    if (n instanceof Parent p) q.addAll(p.getChildrenUnmodifiable()); }
            }
            return false;
        });
        if (!clicked) throw new IllegalStateException("menu row not found for " + packName);
        settle();
    }

    // ---- seeding ----
    static void png(Path p, int w, int h, Color c) throws Exception {
        Files.createDirectories(p.getParent());
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics(); g.setColor(c); g.fillRect(0, 0, w, h); g.setColor(Color.WHITE); g.fillOval(w / 4, h / 4, w / 2, h / 2); g.dispose();
        ImageIO.write(bi, "png", p.toFile());
    }
    static ModpackMeta seed(String name, String mc, String loader, Path dir) {
        ModpackMeta m = new ModpackMeta(); m.name = name; m.version = "2.0"; m.mcVersion = mc; m.loader = loader; m.loaderVersion = "0.16.0";
        m.write(dir); return m;
    }

    public static void main(String[] args) throws Exception {
        home = Files.createTempDirectory("uihome");
        System.setProperty("user.home", home.toString());
        root = home.resolve(".deylauncher");
        Files.createDirectories(Path.of("/tmp/shots"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { uncaught.add(e); e.printStackTrace(); });

        // Seeded packs: wide icon / tall icon / no icon / corrupt icon; Alpha & Gamma share 1.20.1+Fabric; Legacy = seeded in the plain <mc>-<loader> folder like an older build did (startup moves it out).
        Path inst = root.resolve("instances");
        Path alpha = inst.resolve("alpha-pack");  ModpackMeta a = seed("Alpha Pack", "1.20.1", "Fabric", alpha);
        png(root.resolve("modpacks/icons/alpha-pack.png"), 128, 48, new Color(0x2277cc)); a.iconPath = root.resolve("modpacks/icons/alpha-pack.png").toString(); a.write(alpha);
        Path beta = inst.resolve("beta-pack");    ModpackMeta b = seed("Beta Pack", "1.21.1", "Forge", beta);
        png(beta.resolve("icon.png"), 40, 160, new Color(0x22aa55));   // tall; only the copy inside the instance (cache missing -> fallback)
        b.iconPath = root.resolve("modpacks/icons/gone.png").toString(); b.write(beta);
        Path gamma = inst.resolve("gamma-pack");  ModpackMeta g = seed("Gamma Pack", "1.20.1", "Fabric", gamma);
        Files.writeString(gamma.resolve("icon.png"), "this is not a png"); g.iconPath = gamma.resolve("icon.png").toString(); g.write(gamma);
        Path delta = inst.resolve("delta-pack");  seed("Delta Pack", "1.21.4", "NeoForge", delta);
        Path legacy = inst.resolve("1.19.4-fabric"); ModpackMeta l = seed("Legacy Pack", "1.19.4", "Fabric", legacy);
        png(legacy.resolve("icon.png"), 64, 64, new Color(0xcc7722));
        Files.createDirectories(legacy.resolve("mods")); Files.writeString(legacy.resolve("mods/legacy-only-mod.jar"), "the pack's own mod");
        for (int i = 1; i <= 4; i++) seed("Extra Pack " + i, "1.20.4", "Forge", inst.resolve("extra-" + i)); // enough packs to overflow the sidebar
        new LauncherPrefs().save(); // marks "not first launch" so no welcome dialog blocks the run

        Platform.startup(() -> {});
        Platform.runLater(() -> Thread.currentThread().setUncaughtExceptionHandler((t, e) -> { uncaught.add(e); e.printStackTrace(); }));
        fxv(() -> { app = new LauncherApp(); stage = new Stage(); app.start(stage); return null; });
        Thread.sleep(1500);

        // Feed the version list the way loadVersionsAsync.onSucceeded does (the network is unavailable here).
        fxv(() -> {
            @SuppressWarnings("unchecked") List<Object> all = (List<Object>) field("allVersions");
            Class<?> ve = Class.forName("com.deylauncher.version.VersionManifest$VersionEntry");
            Constructor<?> k = ve.getConstructors()[0];
            for (String v : new String[]{"1.21.4", "1.21.1", "1.20.4", "1.20.1", "1.19.4"}) /* newest first, like Mojang's manifest */ all.add(k.newInstance(v, "release", null, null));
            call("applyVersionFilter", field("activePreset"));
            call("restoreLastPlayedVersion");
            return null;
        });
        settle();
        snap("00-start");

        System.out.println("\n== 0. initial state + sidebar");
        check("selectors editable at startup", selectorsEditable(), state());
        check("no pack profile selected at startup", attachedName() == null, state());
        for (String n : new String[]{"Alpha Pack", "Beta Pack", "Gamma Pack", "Delta Pack", "Legacy Pack"})
            check("sidebar has a profile tile for " + n, packTile(n) != null, "missing");
        check("Alpha tile: artwork 128x48 fitted 56x21 inside its 56x32 box (ratio kept, not squared)", tileArt("Alpha Pack").contains("shown 56.0x21.0"), tileArt("Alpha Pack"));
        check("Beta tile: tall 40x160 icon (from the pack folder copy) fitted 8x32", tileArt("Beta Pack").contains("shown 8.0x32.0"), tileArt("Beta Pack"));
        check("Gamma tile: CORRUPT icon -> safe glyph, no crash", tileArt("Gamma Pack").equals("GLYPH"), tileArt("Gamma Pack"));
        check("Delta tile: no icon -> glyph", tileArt("Delta Pack").equals("GLYPH"), tileArt("Delta Pack"));
        snap("00-start");

        System.out.println("\n== 1. select Alpha by clicking its sidebar profile tile");
        clickPackTile("Alpha Pack");
        check("Alpha is the selected profile, launching its own folder", "Alpha Pack".equals(attachedName()) && "alpha-pack".equals(dirName()), state());
        check("version + loader come from the pack", "1.20.1".equals(fx(() -> versionBox().getValue())) && "Fabric".equals(fx(() -> loaderBox().getValue())), state());
        check("Version AND Loader selectors are locked", !fx(() -> !versionBox().isDisabled()) && !fx(() -> !loaderBox().isDisabled()), "version disabled=" + fx(() -> versionBox().isDisabled()) + " loader disabled=" + fx(() -> loaderBox().isDisabled()));
        check("a user cannot change the loader or version", !userPicks(loaderBox(), "Forge") && !userPicks(versionBox(), "1.20.4") && "Fabric".equals(fx(() -> loaderBox().getValue())), state());
        check("card is titled with the pack's name", "Alpha Pack".equals(heading()), heading());
        check("Alpha's tile is the highlighted one and no Vanilla tile is", tileActive(packTile("Alpha Pack"))
                && fx(() -> ((VBox) field("versionTileList")).getChildren().stream().filter(n -> n instanceof Button).map(n -> (Button) n).noneMatch(vt -> vt.getStyleClass().contains("version-tile-active"))), "highlight wrong");
        String desc = fx(() -> ((Label) field("mainDescriptionLabel")).getText());
        check("description states version + Minecraft + loader build; the locked captions say FIXED BY MODPACK", desc.contains("Minecraft 1.20.1") && desc.contains("Fabric 0.16.0") && fx(() -> ((Label) field("modLoaderLabel")).getText() + " " + ((Label) field("versionLabel")).getText()).contains("FIXED BY MODPACK"), desc);
        String bg = buttonGraphic();
        check("top button shows Alpha's real icon, 24x24 box, ratio kept", bg.startsWith("ICON") && bg.contains("shown 24.0x9.0") && ring(), bg + " ring=" + ring());
        check("faded backdrop artwork is showing on the right", fx(() -> backdrop().isVisible()) && fx(() -> backdrop().getImage() != null), "backdrop hidden");
        ImageView bd = backdrop(); Image bi = fx(() -> bd.getImage());
        double ratioSrc = 128.0 / 48.0, ratioBd = bi.getWidth() / bi.getHeight();
        check("backdrop keeps the artwork's aspect ratio (" + String.format("%.3f", ratioSrc) + ")", Math.abs(ratioSrc - ratioBd) < 0.02 * ratioSrc && bd.isPreserveRatio(), "ratio " + ratioBd);
        PixelReader pr = bi.getPixelReader(); int w = (int) bi.getWidth(), h = (int) bi.getHeight();
        int leftA = 0, topA = 0, botA = 0, rightA = 0; for (int i = 0; i < 12; i++) { leftA = Math.max(leftA, (int) (pr.getArgb(0, h * i / 12) >>> 24)); topA = Math.max(topA, (int) (pr.getArgb(w * i / 12, 0) >>> 24));
            botA = Math.max(botA, (int) (pr.getArgb(w * i / 12, h - 1) >>> 24)); rightA = Math.max(rightA, (int) (pr.getArgb(w - 1, h * i / 12) >>> 24)); }
        int midA = (int) (pr.getArgb((int) (w * 0.8), h / 2) >>> 24);
        check("no visible rectangular edge: all four edges fade to transparent, the body is visible", leftA <= 2 && topA <= 2 && botA <= 2 && rightA <= 2 && midA > 100, "L" + leftA + " T" + topA + " B" + botA + " R" + rightA + " mid" + midA);
        check("content column stops short of the artwork (text stays left)", contentRightPad() > 100, "right padding " + contentRightPad());
        snap("01-alpha-profile");

        System.out.println("\n== 2. the way OUT: click a Vanilla tile -> unlocked, plain profile");
        fxv(() -> { VBox tiles = (VBox) field("versionTileList"); for (Node n : tiles.getChildren()) if (n instanceof Button bt && bt.getUserData() != null && !(bt.getUserData() instanceof String)) { bt.fire(); break; } return null; }); settle();
        check("selectors editable again, nothing selected, backdrop gone", selectorsEditable() && attachedName() == null && !fx(() -> backdrop().isVisible()) && contentRightPad() < 40, state());
        check("card retitled 'Minecraft ...' and loader list is the full Vanilla list again", heading().startsWith("Minecraft") && fx(() -> loaderBox().getItems().size()) >= 4, heading() + " " + fx(() -> loaderBox().getItems()));
        check("version and loader can be changed freely again", userPicks(loaderBox(), "Forge") && userPicks(versionBox(), fx(() -> versionBox().getItems().get(0))), state());
        settle(); check("launches the plain folder for whatever was chosen", target().pack() == null && !dirName().contains("pack"), state());
        snap("02-back-to-vanilla");

        System.out.println("\n== 3. Gamma shares Alpha's Minecraft 1.20.1 + Fabric but is a different profile with its own folder");
        clickPackTile("Gamma Pack");
        check("Gamma (not Alpha) selected and launched from gamma-pack", "Gamma Pack".equals(attachedName()) && "gamma-pack".equals(dirName()) && tileActive(packTile("Gamma Pack")) && !tileActive(packTile("Alpha Pack")), state());
        check("corrupt icon: safe glyph on the top button (not blank), backdrop hidden, ring shown", buttonGraphic().startsWith("GLYPH") && ring() && !fx(() -> backdrop().isVisible()), buttonGraphic());
        check("Mods window would open Gamma's mods folder", fx(() -> target().instanceDir().resolve("mods")).equals(gamma.resolve("mods")), "" + target().instanceDir());
        clickPackTile("Alpha Pack");
        check("switching to Alpha: its own folder again, never Gamma's", "Alpha Pack".equals(attachedName()) && fx(() -> target().instanceDir().resolve("mods")).equals(alpha.resolve("mods")), state());

        System.out.println("\n== 4. Beta (Forge 1.21.1) then Delta (no icon, NeoForge 1.21.4) - each fixes its own loader");
        clickPackTile("Beta Pack");
        check("Beta: 1.21.1 / Forge, locked, tall artwork kept in ratio", "Forge".equals(fx(() -> loaderBox().getValue())) && "1.21.1".equals(fx(() -> versionBox().getValue())) && !selectorsEditable()
                && Math.abs(fx(() -> backdrop().getImage().getWidth() / backdrop().getImage().getHeight()) - 0.25) < 0.01, state());
        clickPackTile("Delta Pack");
        check("Delta: 1.21.4 / NeoForge, locked", "NeoForge".equals(fx(() -> loaderBox().getValue())) && "1.21.4".equals(fx(() -> versionBox().getValue())) && !selectorsEditable(), state());
        check("no artwork -> no backdrop and no reserved gap, still ringed with the generic glyph", !fx(() -> backdrop().isVisible()) && contentRightPad() < 40 && buttonGraphic().startsWith("GLYPH") && ring(), buttonGraphic());
        snap("04-delta-noart");

        System.out.println("\n== 5. selecting a pack from the top modpack menu is the same profile selection");
        pickPackFromMenu("Alpha Pack");
        check("menu -> Alpha profile, locked", "Alpha Pack".equals(attachedName()) && !selectorsEditable(), state());

        System.out.println("\n== 6. DEY / VANILLA mode toggle is a way out too");
        fxv(() -> { ((ToggleButton) field("deyModeBtn")).fire(); return null; }); settle();
        check("DEY: unlocked, Fabric only, no pack, no Alpha highlight", selectorsEditable() && attachedName() == null && List.of("Fabric").equals(fx(() -> new ArrayList<>(loaderBox().getItems()))) && !tileActive(packTile("Alpha Pack")), state());
        clickPackTile("Beta Pack");
        check("picking a pack while in DEY switches to VANILLA and selects it", Boolean.FALSE.equals(fx(() -> field("deyMode"))) && "Beta Pack".equals(attachedName()) && !selectorsEditable(), state());
        fxv(() -> { ((ToggleButton) field("deyModeBtn")).fire(); return null; }); settle();
        fxv(() -> { ((ToggleButton) field("vanillaModeBtn")).fire(); return null; }); settle();
        check("DEY then VANILLA again: unlocked, full loader list, nothing stale", selectorsEditable() && attachedName() == null && fx(() -> loaderBox().getItems().size()) >= 4, state());

        System.out.println("\n== 7. pack an older build installed into the plain <mc>-<loader> folder is given its own folder at startup");
        Path legacyOwn = inst.resolve("Legacy-Pack");
        check("at startup the pack was moved out of the plain 1.19.4-fabric folder into its own (mods travel with it)",
                !Files.exists(legacy) && Files.exists(legacyOwn.resolve("mods/legacy-only-mod.jar")), "plain exists=" + Files.exists(legacy) + " own=" + Files.exists(legacyOwn));
        check("so plain 1.19.4 + Fabric (Vanilla or DEY) holds none of the pack's mods", !Files.exists(legacy.resolve("mods/legacy-only-mod.jar")), "leaked");
        clickPackTile("Legacy Pack");
        check("selecting it as a profile launches its OWN folder and locks 1.19.4 / Fabric", "Legacy Pack".equals(attachedName()) && "Legacy-Pack".equals(dirName()) && !selectorsEditable(), state());

        System.out.println("\n== 8. delete the selected pack");
        clickPackTile("Alpha Pack");
        ModpackMeta toDelete = ModpackMeta.read(alpha);
        fxv(() -> { call("deleteInstalledModpack", toDelete); return null; }); settle();
        check("deleted: unlocked, nothing selected, folder gone, tile gone, backdrop gone",
                selectorsEditable() && attachedName() == null && !Files.exists(alpha) && packTile("Alpha Pack") == null && !fx(() -> backdrop().isVisible()), state());
        check("other packs keep their tiles", packTile("Gamma Pack") != null && packTile("Beta Pack") != null, "tiles lost");

        System.out.println("\n== 9. stress: 80 random user-style actions (pack tiles, menu, Vanilla tiles, dropdowns, mode toggle)");
        Random rnd = new Random(7); String[] packs = {"Beta Pack", "Gamma Pack", "Delta Pack", "Legacy Pack"};
        boolean coherent = true; String bad = "";
        for (int i = 0; i < 80 && coherent; i++) {
            switch (rnd.nextInt(6)) {
                case 0 -> clickPackTile(packs[rnd.nextInt(packs.length)]);
                case 1 -> pickPackFromMenu(packs[rnd.nextInt(packs.length)]);
                case 2 -> { List<String> items = fx(() -> new ArrayList<>(versionBox().getItems())); if (!items.isEmpty()) userPicks(versionBox(), items.get(rnd.nextInt(items.size()))); }
                case 3 -> { List<String> items = fx(() -> new ArrayList<>(loaderBox().getItems())); if (!items.isEmpty()) userPicks(loaderBox(), items.get(rnd.nextInt(items.size()))); }
                case 4 -> clickTile(rnd.nextInt(4));
                default -> fxv(() -> { ((ToggleButton) field(rnd.nextBoolean() ? "deyModeBtn" : "vanillaModeBtn")).fire(); return null; });
            }
            settle();
            ModpackMeta at = fx(() -> sel().attached());
            boolean lockedNow = fx(() -> versionBox().isDisabled() && loaderBox().isDisabled());
            boolean unlockedNow = selectorsEditable();
            boolean ok = (at == null ? unlockedNow : lockedNow)   // locked iff a pack profile is selected -- never stuck either way
                    && (at == null || (at.targets(fx(() -> versionBox().getValue()), fx(() -> loaderBox().getValue())) && Boolean.FALSE.equals(fx(() -> field("deyMode")))
                        && target().instanceDir().equals(at.instanceDir(root)) && tileActive(packTile(at.name))
                        && fx(() -> ((Label) field("mainHeadingLabel")).getText()).equals(at.name)))
                    && (at != null || fx(() -> ((Label) field("mainHeadingLabel")).getText()).startsWith("Minecraft"));
            if (!ok) { coherent = false; bad = "step " + i + " " + state(); }
        }
        check("80 actions: selectors locked exactly while a pack profile is selected; title, tile, folder and selectors always agree", coherent, bad);
        clickTile(0); settle();
        check("and after all that, one Vanilla-tile click always gets you back to a free selector", selectorsEditable() && attachedName() == null, state());

        Thread.sleep(300);
        check("no exception reached the JavaFX thread during the whole run", uncaught.isEmpty(), uncaught.toString());
        System.out.println("\nUI RESULT: " + pass + " passed, " + fail + " failed");
        Platform.exit();
        System.exit(fail == 0 ? 0 : 1);
    }
}
