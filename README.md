# DeyLauncher -- Phase 6.1 (Account/Auth fixes, AppID approved)

## AppID approved

Mojang approved DeyLauncher's app registration on 08.27.2026. The real
Microsoft/Xbox/Minecraft chain in `MicrosoftAuth.java` is unchanged --
it should now succeed end to end instead of stopping at the known 403.

## Fixes this round

1. **Offline username is now explicit, not implicit.** Account dialog
   has a new "OFFLINE ACCOUNT USERNAME" field + **Apply** button. This
   is now the only thing that creates/renames a saved offline account --
   `onPlay()` no longer silently registers whatever's typed in the Play
   card as "the" account. Root cause: offline UUIDs are derived from the
   name itself, so silently re-deriving it on every Play click minted a
   disconnected identity each time instead of updating one persistent
   account. Apply is the single source of truth now, exactly as
   requested.

2. **Offline skins:** storage format unchanged (`profiles/<uuid>/skin.png`
   + `profile.json`, as before) -- confirmed still correct, kept as is.
   Added `DEYLAUNCHER_SKIN_PROTOCOL.md` at the repo root documenting that
   format explicitly for the (not-yet-built) companion mod, and stating
   plainly what's NOT solved yet: a skin file on one player's disk isn't
   visible to another player's machine without a network sync layer,
   which doesn't exist. No fake sync was added -- the doc says so.

3. **Critical fix: online sessions actually reach the game now.**
   `onPlay()` previously called `AuthSession.offline(...)` unconditionally,
   regardless of Microsoft sign-in state -- a successful login had zero
   effect on what got launched. Fixed: `onPlay()` now checks the active
   account; if it's ONLINE and this run has a live token *for that exact
   account* (tokens are matched by UUID now, not just "any token"), it
   builds a real `AuthSession(accessToken, uuid, username, isOffline=false)`
   and that's what launches. Falls back to offline (with a clear log
   message) if the active account is online but wasn't signed in this
   run. Offline play is unaffected.

4. **Device-code dialog rebuilt:** clickable `Hyperlink` (opens via
   JavaFX's `HostServices`, underlined by default), a separate read-only
   `TextField` with the same URL underneath for actual text
   selection/copy, a large 40px monospace code display, and a Copy
   Code button using the system clipboard.

No unrelated systems were rewritten -- GameFiles, GameLauncher,
VersionResolver, the mod loader installers, and the mods UI are all
untouched.

## Please test

I can't run a Java GUI from this sandbox (no network, no display):
```bash
./gradlew clean run
```
- Offline: Account > type a name > Apply > close/reopen dialog > name
  persists > Play uses it without retyping or ever having played before.
- Online: Sign in with Microsoft (should now complete, not just reach
  a known 403) > Play > confirm the game actually connects online, not
  as a local/offline player.
- Device code: confirm the link opens your browser, the URL field is
  actually selectable, and Copy Code puts the exact code on your
  clipboard.

Paste back what you see either way, especially anything unexpected --
this round touches a genuinely load-bearing path (which session actually
reaches the game), so I'd rather verify against real output than assume.

## Files changed this round, and why

**`src/main/java/com/deylauncher/launch/GameFiles.java`**
Bug: the library-download loop had `if (!lib.has("downloads")) continue;`.
Fabric's meta API (and some Forge libraries) declare libraries in the
older Maven-coordinate format -- `{"name": "net.fabricmc:fabric-loader:X.Y.Z",
"url": "https://maven.fabricmc.net/"}` -- with no `"downloads"` block at
all. That line silently skipped every Fabric library, including
fabric-loader itself, which is exactly why `KnotClient` was never on the
classpath (`ClassNotFoundException`). Fix: the loop now branches on
whether a library uses the modern `"downloads"` format or the
Maven-coordinate `"name"`+`"url"` format; for the latter, a new
`mavenCoordinateToPath()` derives the standard Maven repo layout path
(`group/artifact/version/artifact-version.jar`) and downloads from
`url` (or `libraries.minecraft.net` if `url` is omitted, matching the
official launcher's own fallback). No Fabric jar is hardcoded anywhere --
this reads and resolves whatever `FabricInstaller`'s profile actually
declares, so it'll keep working as Fabric updates its dependency list.

**`src/main/java/com/deylauncher/launch/GameLauncher.java`**
Bug: `osRulesPass()` (now `argRulesPass()`) only ever checked a rule's
`"os"` key. Modern game-argument rules also gate on a `"features"` key
-- e.g. Minecraft 26.2's quick-play args each look like
`{"rules":[{"action":"allow","features":{"is_quick_play_singleplayer":true}}], ...}`.
A features-only rule has no `"os"` block, so `matches` stayed at its
default `true`, meaning every quick-play variant's rule spuriously
"matched" and all of them got added to the command at once -- hence
"Only one quick play option can be specified." Fix: `argRulesPass()` now
evaluates `"features"` conditions too, checked against a new
`currentFeatures()` map. DeyLauncher deliberately declares no
`is_quick_play_*` feature (since there's no Quick Play UI yet), so those
rules now correctly fail to match and none of their arguments get added
-- while every other required vanilla argument (auth, assets, version
info, etc.) is untouched, since those aren't gated on quick-play
features at all. `has_custom_resolution` is explicitly declared `true`,
since Settings > Game already always supplies width/height.

**`src/main/java/com/deylauncher/ui/LauncherApp.java`**
Removed a dead import, `import com.deylauncher.modloader.ModManager;`,
left over from an earlier version of the mods feature that was later
renamed to `ModsManager` and moved into the `ui` package (where
`LauncherApp` already lives, so it needs no import at all). This didn't
affect the two crashes above, but it was a real compile error -- thank
you for catching it.

No other files were touched -- the mods UI/backend, Fabric/Forge
installers, and version resolver logic are all unchanged, since neither
bug originated there.

## Please test

I can't run a Java GUI from this sandbox (no network, no display), so:
```bash
./gradlew clean run
```
1. Vanilla 26.2 -- should no longer crash with the quick-play exception.
2. Fabric 26.2 -- should get past `KnotClient` and actually boot.

Let me know exactly what happens on each, including any new log output --
if either still fails, paste the log and I'll keep tracing from there.


## What's new: drag-and-drop mods tab

When Fabric or Forge is selected, a **🧩 Mods** button appears next to
Settings in the top bar (hidden for Vanilla, since there's nothing to
manage). Opens a dialog for whichever version+loader is currently
selected:

- **Drag & drop** `.jar` files straight onto the drop zone to add mods
  (or use the "+ Add Mods" button for a normal file picker)
- Each mod shows as a card with its **display name** (cleaned-up
  filename) and the raw filename + size underneath
- A **checkbox turns green when a mod is active**; unchecking it disables
  the mod and the whole row **grays out** (45% opacity) instead of just
  the checkbox
- A **red trash button** on every row deletes that mod from the launcher
  entirely (with a hover highlight)

How disabling actually works: unchecking a mod moves its jar into a
sibling `mods-disabled/` folder rather than renaming its extension or
deleting it -- the mod loader only ever scans `mods/`, so a disabled jar
is simply invisible to it, and re-enabling just moves it back. This is
the same approach tools like MultiMC/Prism use, and it means nothing
loader-specific is needed for Fabric vs. Forge.

Mods are stored per version+loader instance (e.g. mods for
`1.21.1`+Fabric are separate from `1.21.1`+Forge), matching how the game
directories were already split in Phase 5.

What's working right now:

- **Microsoft sign-in** (`auth/MicrosoftAuth.java`) -- full chain: Microsoft
  device-code login -> Xbox Live -> XSTS -> Minecraft Services -> profile.
  Uses your registered Client ID (`bef65492-f622-4b08-a124-9535e7b0ffde`).
  No browser embedding, no local server, no card/cost involved.
- **Version list** (`version/VersionManifest.java`) -- pulls the live,
  official list of every Minecraft version straight from Mojang, the same
  source the real launcher uses. This means new versions show up
  automatically, nobody has to hand-maintain a list.
- **`Main.java`** ties both together as a console test so we can confirm
  login + version fetching actually work before building the GUI on top.

## Running it

Requires JDK 17+ and an internet connection (Gradle needs to download
dependencies the first time).

```bash
./gradlew run
```

You'll be given a URL and a short code -- open the URL on any device, type
the code, sign in with the Microsoft account that owns Minecraft. The
console will then print your username and confirm the version manifest
fetch worked.

## Before this will actually work: app approval

New Azure apps must apply for permission to use the Minecraft API via
**https://aka.ms/mce-reviewappid** (Client ID: `bef65492-f622-4b08-a124-9535e7b0ffde`).
Without approval, the final login step returns a 403 "Invalid app
registration" -- everything before that (Microsoft sign-in, Xbox Live,
XSTS) works fine even without approval, it's only the last handoff to
Minecraft's own servers that's gated. This is a one-time, free, manual
review by Microsoft -- submit the form and wait; there's no way to code
around it. We can keep building everything else in the meantime.

## Default versions

Confirmed: `1.21.1` and `26.2` (the current stable release, "Chaos Cubed",
June 16 2026) are both real, valid version ids in Mojang's manifest --
`Main.java` looks up both automatically. One thing this affects: **26.2
requires Java 25+ to run**, while 1.21.1 works on older Java. Phase 2's
launch code will read the required Java version out of each version's own
manifest entry and use the matching runtime per-version automatically, so
you won't have to manage multiple JDKs by hand.

## What's new in Phase 2

- **`launch/GameFiles.java`** -- downloads a version's client jar,
  OS-filtered libraries, extracted natives, and assets into
  `~/.deylauncher/`, reusing cached files on repeat runs.
- **`launch/GameLauncher.java`** -- builds the actual `java ...` command
  from the version JSON's argument templates and starts the game.
- **`auth/AuthSession.java`** -- a shared shape for both a real Microsoft
  session and an **offline test session**, so the rest of the launcher
  doesn't care which one it got.

## Offline test mode (active right now, while approval is pending)

`Main.java` currently has `USE_MICROSOFT_AUTH = false`. In this mode you
just type any username and it launches locally -- enough to test that
downloading and launching actually works for both 1.21.1 and 26.2. **This
cannot join real online-mode servers** -- that needs the approved
Microsoft token. Flip that flag to `true` once `aka.ms/mce-reviewappid`
comes back approved; nothing else changes, since `AuthSession` already
unifies both paths.

## Resolved: Java version per Minecraft version

(Previously noted as a limitation here -- fixed in Phase 4, see below.)

## What's new in Phase 3: the real window

- **`ui/LauncherApp.java`** -- the actual GUI: username field, version
  dropdown (defaults to 1.21.1/26.2 first, then every other Mojang
  version), a big **PLAY** button, a live log panel streaming the game's
  own output, and a settings dialog (RAM slider, resolution, fullscreen).
- **`theme.css`** -- a real dark/light theme (not default JavaFX gray) --
  toggle button top-right switches instantly.
- Download and launch now run on a background thread with an
  indeterminate progress bar, so the window never freezes while files are
  fetched or the game boots.

Run it:
```bash
./gradlew run
```
The console version still exists for quick debugging without a window:
```bash
./gradlew runConsole
```
(`echo "name" | ./gradlew runConsole` still works the same as before.)

Still offline test mode underneath (`Main.USE_MICROSOFT_AUTH = false` is
irrelevant to the GUI right now -- the GUI always uses
`AuthSession.offline(...)` directly; I'll wire the real Microsoft flow
into a proper "Sign in" button once your app is approved).

## What's new in Phase 4b: bigger, more modern, fully resizable UI

- Default window is now **1180x760** (was 720x480) -- opens like a real
  desktop app instead of a cramped dialog. Minimum size 860x560, no max --
  resize freely.
- The play area is now a centered, elevated **card** (rounded corners,
  soft shadow, subtle purple border) over a gradient background, instead
  of flat controls sitting directly on the window.
- Card and log panel sit in a **SplitPane** you can drag to rebalance --
  everything grows/shrinks with the window via HGrow/VGrow rather than
  fixed pixel sizes, so it scales cleanly at any size or DPI instead of
  clipping or leaving dead space.
- Pill-shaped top-bar buttons, a small logo mark + subtitle, glowing
  gradient Play button, styled progress bar and log panel to match.

## What's new in Phase 4: automatic per-version Java

- **`launch/JavaRuntimeManager.java`** -- reads each version's
  `javaVersion.component` (e.g. `java-runtime-delta` for 26.2's Java 25+
  requirement, `jre-legacy` for old versions), downloads Mojang's own
  bundled JRE build for your OS/arch straight from their public runtime
  manifest, and caches it under `~/.deylauncher/runtimes/`. The launcher
  now always uses that JRE's `java` binary instead of whatever's on your
  PATH -- **the earlier limitation (matching your system `java` to
  whichever version you pick) is gone.**
- Both `Main.java` (console) and `LauncherApp.java` (GUI) now call this
  before launching, and both print/log which runtime path it resolved to.
- Covers Windows (x64/x86/arm64), macOS (Intel/Apple Silicon), and Linux
  (x64/x86). Note: Mojang doesn't publish an ARM Linux build (e.g.
  Raspberry Pi) -- on that one platform it'll throw a clear error telling
  you to install a matching JDK yourself rather than silently failing.

## What's new: bigger, more modern, auto-scaling window

- Opens at **1280x820** (was a cramped 720x480), centered on screen,
  resizable with a sane 860x560 floor -- feels like a normal desktop app
  now instead of a small utility dialog.
- **Nothing is hardcoded to a fixed pixel layout anymore.** The window
  uses a draggable `SplitPane` between the play card and the log panel,
  and every panel grows/shrinks via HGrow/VGrow -- resize the window (or
  move it to a different-resolution monitor) and everything reflows
  instead of clipping or leaving dead space.
- **All text sizes bumped up** across both themes -- base font, field
  labels, notices, inputs, the Play button, and the log panel all read
  clearly now instead of the original small defaults.
- Restyled with a gradient background, a glowing logo glyph + subtitle,
  pill-shaped buttons, a frosted-glass play card with a soft shadow, and a
  glowing purple Play button -- aiming for "modern app," not "default
  JavaFX gray."

## What's new: Launcher settings tab

Settings is now tabbed: **Game** (unchanged: RAM, resolution, fullscreen)
and a new **Launcher** tab with:

- **Interface scale** (75%-175%) -- scales padding/spacing/control sizing
- **Text size** (75%-175%) -- scales font sizes independently of the above
- **Font** -- pick between Segoe UI, Inter, Helvetica Neue, Roboto, Arial,
  Consolas
- **Startup window size** -- exact width/height the app opens at next
  time, with a "Use current window size" button, or leave "Remember
  window size automatically on close" checked to have it just track
  whatever size you left it at
- All of it **live** -- every slider/dropdown re-renders the actual
  window (and the settings dialog itself) immediately, not just after
  closing the dialog -- and **persists** to
  `~/.deylauncher/launcher.properties`, so it's still set next time you
  open the app.

Implementation note: `DynamicStyle.java` regenerates a small override
stylesheet from your chosen scale/font each time something changes and
loads it as a `data:` URI after `theme.css` -- this is what makes the
sliders update the window live without a restart.

## What's new: Fabric and Forge

A **MOD LOADER** dropdown (Vanilla / Fabric / Forge) now sits under the
version picker in the GUI.

- **Fabric** (`modloader/FabricInstaller.java`) uses Fabric's own public
  meta API -- the officially documented way for third-party launchers to
  integrate it. Fast, no extra process spawned, just downloads a small
  JSON profile.
- **Forge** (`modloader/ForgeInstaller.java`) works completely
  differently under the hood: Forge doesn't publish a simple profile like
  Fabric, its installer is a real Java program that patches files via its
  own internal pipeline. Rather than reimplementing that (fragile, and
  changes across Forge versions), this downloads Forge's **actual
  official installer jar** and runs it in its documented headless mode
  (`--installClient <dir>`), then reads the version profile it produces.
  Less code, and it stays correct even if Forge changes its internals,
  since we're not guessing at them.
- **`version/VersionResolver.java`** -- both loaders produce a profile
  that "inherits" from the vanilla version (extra libraries + a different
  main class, same base game/assets). This merges that profile with the
  vanilla version JSON the same way the official launcher does, so
  `GameFiles`/`GameLauncher` never need special-case logic for modded
  vs. vanilla -- they just see one normal-looking version JSON either way.

Known limits: Forge's own installer needs to actually run and complete
successfully, which can take a bit longer than a plain download (watch
the log panel -- it'll say "Installing Forge..." during this). Very new
Minecraft versions may not have a Fabric/Forge build yet at all (both
installers surface a clear error rather than failing silently if so).
Fabric mods and Forge mods aren't interchangeable -- pick based on which
mods you actually want to run.

## What's new: real Account/Profile/Skin system

New `identity` package, entirely new -- MicrosoftAuth and AuthSession are
**untouched**:

- **`PlayerIdentity`** -- the persistent record (uuid, username,
  accountType, skinModel, skinSource) for one account, online or
  offline. Not the same thing as `AuthSession` (which is still the
  short-lived object `GameLauncher` actually uses to launch) -- this is
  the durable record that survives between runs.
- **`IdentityStore`** -- reads/writes `~/.deylauncher/accounts.json`
  (the list of known accounts + which one is active) and
  `~/.deylauncher/profiles/<uuid>/profile.json` + `skin.png`. That
  per-UUID folder is deliberately plain files, not a database, so a
  future DeyLauncher Minecraft mod (or a sync mechanism) can read a
  player's skin without touching this code at all.
- **`SkinValidator`** -- actually checks imported files are real PNGs at
  64x64 or legacy 64x32 before accepting them, with a clear rejection
  reason otherwise -- no arbitrary images get through.
- **`MinecraftSkinService`** -- the real Minecraft Services skin-upload
  API (`POST /minecraft/profile/skins`, multipart, officially documented,
  not reverse-engineered). Needs a real Microsoft-issued access token, so
  it's gated on the same pending app approval as the rest of
  `MicrosoftAuth` -- it fails with a clear, specific message ("still
  awaiting approval") rather than pretending to succeed.

**UI:** a new 👤 **Account** button opens Account/Profile/Skin management
-- shows the active account (ONLINE/OFFLINE badge, username, UUID, skin
preview), lets you import a skin PNG, pick Classic/Slim, remove/reset,
sign in with Microsoft (exercises the real login flow right now -- it'll
get all the way to the final Minecraft handoff and stop there until
approval lands), and switch between multiple saved accounts.

**Online vs. offline, explicitly:** offline skin imports are saved
locally only -- the dialog says so, plainly, every time. Online skin
changes call the real API; nothing is faked. Since DeyLauncher doesn't
persist Microsoft refresh tokens yet, an online skin change only works
within the same run you signed in during (there's a clear warning if you
try otherwise) -- that's an honest current limitation, not a bug.

**Offline skins visible to other players:** intentionally NOT implemented
as in-game rendering this phase -- see the design note in
`IdentityStore`'s Javadoc. The launcher-side storage is deliberately
plain files for exactly this reason: a small companion mod can read
`profiles/<uuid>/skin.png` later without any launcher changes.

**Cape-ready:** `PlayerIdentity` and the `profiles/<uuid>/` folder layout
intentionally leave room for a `cape.png` + `capeSource` field later
(for the planned separate DeyCape mod) without restructuring anything
built this phase.


