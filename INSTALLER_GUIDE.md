# Building an installer to give to friends

DeyLauncher uses `jpackage` (bundled with the JDK since Java 14) to
produce a self-contained package -- your friends won't need Java
installed at all, since a JRE is bundled inside it.

**Important limitation:** `jpackage` bundles a platform-specific JRE, so
you must build the Windows package *on Windows* and the Linux package
*on Linux* -- there's no reliable cross-building from CachyOS to
Windows. If you don't have a Windows machine, see the fallback option
at the bottom.

## Before you build: embed your GitHub token (optional but recommended)

If you want Friends to work out of the box for everyone you give this
to -- no setup on their end -- create `secrets/embedded-github.properties`
in the project root first. See `GITHUB_SETUP.md` (Option B) for the
exact steps. Gradle bakes it into the jar automatically on the next
build; skip this and Friends just stays unconfigured until each person
sets up their own `~/.deylauncher/github.properties`.

## Why updating is always safe

DeyLauncher's own data (`~/.deylauncher/` -- accounts, skins, downloaded
Minecraft versions, mods, settings, the friends cache) lives in the
user's **home directory**, completely separate from wherever the app
itself is installed. Reinstalling or overwriting the app folder with a
newer build **never touches `~/.deylauncher`** -- nothing extra to do,
no migration step, no "preserve my data" flag needed. This is already
true today; you don't need to change anything for updates to be safe.
The same applies to `~/.deylauncher/github.properties` if someone set up
their own override -- unaffected by any update.

## Building on Linux (your machine)

```bash
./gradlew clean shadowJar
```

This produces `build/libs/DeyLauncher-0.1.0.jar` -- the full fat jar,
with your embedded token inside if you did the step above (check the
build log for the confirmation line).

Then build the app-image (a folder containing a bundled JRE + native
launcher, no system-wide install needed):

```bash
jpackage \
  --type app-image \
  --input build/libs \
  --main-jar DeyLauncher-0.1.0.jar \
  --main-class com.deylauncher.ui.LauncherApp \
  --name DeyLauncher \
  --icon src/main/resources/app-icon.png \
  --dest dist
```

This creates `dist/DeyLauncher/` -- a folder with everything needed to
run. Zip that folder up:

```bash
cd dist && zip -r DeyLauncher-linux.zip DeyLauncher
```

**To share with Linux friends:** send them `DeyLauncher-linux.zip`. They
unzip it anywhere and run `./DeyLauncher/bin/DeyLauncher`. No install,
no package manager involved, no GitHub setup needed if you embedded a
token -- works the same on CachyOS, Ubuntu, Fedora, etc., since the JRE
is bundled too.

**To update:** just repeat the build, and have them replace the old
`DeyLauncher/` folder with the new one. `~/.deylauncher/` isn't inside
that folder, so nothing is lost.

## Building on Windows (needs an actual Windows machine or VM)

Same idea, run on Windows with a JDK 17+ installed:

```powershell
gradlew.bat clean shadowJar

jpackage `
  --type app-image `
  --input build\libs `
  --main-jar DeyLauncher-0.1.0.jar `
  --main-class com.deylauncher.ui.LauncherApp `
  --name DeyLauncher `
  --icon src\main\resources\app-icon.ico `
  --dest dist
```

Windows `jpackage` wants a real `.ico` file, not a `.png` -- convert
`app-icon.png` to `.ico` first (any online converter, or ImageMagick:
`magick app-icon.png -define icon:auto-resize=256,128,64,48,32,16 app-icon.ico`).

Zip `dist\DeyLauncher\` the same way and share it. Your Windows friends
unzip and run `DeyLauncher\DeyLauncher.exe` -- no installer wizard, no
admin rights needed, nothing written outside that folder except (as
always) `%USERPROFILE%\.deylauncher\` for their actual data.

## If you don't have a Windows machine at all

Simplest fallback: have Windows friends install a JDK 17+ themselves
(Adoptium/Temurin is a good free option) and just run the shadow jar
directly:

```powershell
java -jar DeyLauncher-0.1.0.jar
```

Less polished (they need Java installed, no native `.exe`), but zero
extra work on your end, and identical `~/.deylauncher` update behavior
and embedded-token behavior (the jar is the jar either way).

## A note on the two `run` tasks vs. the real jar

`./gradlew run` (what you've been testing with) launches from source via
Gradle and isn't what you distribute. `shadowJar` is the actual
standalone artifact -- always build and test *that* jar directly
(`java -jar build/libs/DeyLauncher-0.1.0.jar`) before packaging an
installer, since Gradle's `run` task can occasionally behave slightly
differently (classpath ordering, working directory) than the real
packaged app. This is also the easiest way to confirm your embedded
token actually made it into the jar -- open Friends in that direct-jar
run and confirm it doesn't show "not set up yet."
