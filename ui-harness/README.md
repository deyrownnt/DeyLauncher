# UI flow harness (verification only -- NOT part of the project build)

`UiFlowHarness.java` starts the REAL `LauncherApp` (JavaFX, software pipeline, Xvfb) against a throwaway
`user.home`, seeds nine modpacks (wide / tall / missing / corrupt icon; two on the same Minecraft version
+ loader; one installed by an older launcher build; four extras that overflow the sidebar) and drives it
like a user: the sidebar profile tiles, the modpack menu, the dropdowns (only while enabled), the Vanilla
tiles, the VANILLA/DEY switch, delete -- then 80 random actions.

44 checks, among them:
- a selected modpack profile locks Loader + Version; the card title, highlighted tile, launch folder and
  Mods folder are all that pack's own (Alpha and Gamma share 1.20.1 + Fabric and never mix);
- any Vanilla tile or the mode switch always gets you out: unlocked, plain folder, nothing stale;
- artwork keeps its aspect ratio in the tiles and in the backdrop; missing/corrupt icons fall back safely;
  the backdrop fades to zero alpha on all four edges;
- after every random action: locked exactly while a pack profile is selected, never stuck either way.

Needs a display, so on headless Linux use Xvfb:

    xvfb-run -a -s "-screen 0 1400x900x24" java -Dprism.order=sw \
      --module-path <javafx-jars-dir> --add-modules javafx.controls,javafx.graphics \
      -cp <compiled-DeyLauncher-classes>:<harness-classes>:<gson/jna/etc jars> UiFlowHarness

Limits: it does not launch Minecraft (that downloads the game first); it checks `currentLaunchTarget()`,
which `onPlay` snapshots. `shim/` is a tiny stand-in for the JUnit API + a reflection runner that I used only
because the sandbox had no Gradle -- ignore it and run `./gradlew test` on your machine.
