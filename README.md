# DeyLauncher

A lightweight, cross-platform Minecraft launcher built with **Java, JavaFX, and Gradle**.

DeyLauncher is designed around a simple goal: provide a modern launcher experience while keeping the actual Minecraft installation and launch process close to how the official launcher works.

## Features

- 🎮 **Minecraft version management**
  - Uses Mojang's version manifest
  - Automatically discovers available Minecraft versions
  - Downloads and caches game files locally
  - Supports vanilla Minecraft

- 🔧 **Mod loader support**
  - Fabric
  - Forge
  - Per-version and per-loader mod instances
  - Automatic loader installation and profile resolution

- 🧩 **Mod Manager**
  - Drag-and-drop `.jar` support
  - Enable/disable mods without deleting them
  - Per-version + loader mod directories
  - Mod file size and filename information

- 👤 **Account & profile management**
  - Microsoft accounts
  - Offline accounts
  - Multiple saved accounts
  - Persistent account/profile data
  - Minecraft skin import and management
  - Classic and Slim skin models

- 🔐 **Microsoft authentication**
  - Microsoft device-code authentication
  - Xbox Live authentication
  - XSTS authentication
  - Minecraft Services authentication
  - Minecraft profile retrieval

- 🎨 **Modern JavaFX UI**
  - Resizable interface
  - Dark/light themes
  - Configurable interface scale
  - Configurable text size and font
  - Persistent launcher settings
  - Integrated game log output

- ☕ **Automatic Java runtime management**
  - Uses the Java runtime required by each Minecraft version
  - Runtime downloads are handled automatically
  - No manual JDK switching required

- 📦 **Self-contained builds**
  - Linux build includes its Java runtime
  - Windows build includes its Java runtime
  - Users do not need to install Java separately

## Supported platforms

| Platform | Status |
|---|---|
| Linux x64 | ✅ |
| Windows x64 | ✅ |
| macOS | ❌ |

The launcher itself is built with Java/JavaFX, while the release packaging is handled through `jpackage`.

## Architecture

The project is split into several relatively independent systems:

```text
src/main/java/com/deylauncher/
├── auth/          Microsoft authentication and sessions
├── identity/      Accounts, profiles and skins
├── launch/        Game files, Java runtimes and launching
├── modloader/     Fabric and Forge integration
├── version/       Minecraft version manifests and resolution
├── ui/            JavaFX launcher interface
└── Main.java      Console/debug entry point
```

The launcher intentionally keeps authentication, identity storage, game launching and UI logic separated. This makes it possible to change one part without having to rewrite the rest of the launcher.

## Minecraft launching

DeyLauncher resolves a Minecraft version using Mojang's version manifest and builds the required runtime environment from the version metadata.

For a selected version it can handle:

1. Version metadata
2. Client JAR
3. Libraries
4. Native libraries
5. Assets
6. Version-specific Java runtime
7. Authentication arguments
8. Loader-specific libraries and profiles
9. Game launch arguments

Downloaded files are cached under:

```text
~/.deylauncher/
```

Repeated launches therefore reuse files that have already been downloaded.

## Automatic Java runtimes

Different Minecraft versions require different Java versions.

Instead of relying on the Java installation on the user's system, DeyLauncher resolves the Java runtime required by the selected Minecraft version and downloads the corresponding Mojang runtime when necessary.

The runtime is cached locally under:

```text
~/.deylauncher/runtimes/
```

This is especially useful for versions with newer Java requirements, while allowing older Minecraft versions to continue using their expected runtime.

## Fabric and Forge

### Fabric

Fabric integration uses Fabric's metadata API to obtain the loader profile and its dependencies.

The launcher then resolves the Fabric profile together with the selected vanilla Minecraft version.

### Forge

Forge uses a different installation model. Instead of reimplementing Forge's installer logic, DeyLauncher runs the official Forge installer in headless client-installation mode and uses the generated profile.

This keeps the launcher implementation smaller and avoids depending on Forge's internal installation logic.

### Version resolution

Both loaders ultimately produce a version profile that can be combined with the vanilla version metadata.

This allows the game-launching code to operate on a resolved version rather than having separate launch implementations for Vanilla, Fabric and Forge.

## Mod Manager

When Fabric or Forge is selected, the launcher exposes a Mods interface for the current version/loader instance.

Mods can be added by:

- Dragging `.jar` files into the launcher
- Using the file picker

Disabled mods are moved from:

```text
mods/
```

to:

```text
mods-disabled/
```

Re-enabling a mod simply moves it back.

This keeps the original JAR intact and works without loader-specific enable/disable logic.

Mod directories are isolated per Minecraft version and loader, so for example:

```text
Minecraft 1.21.1 + Fabric
```

and

```text
Minecraft 1.21.1 + Forge
```

do not share their installed mods.

## Accounts and profiles

DeyLauncher stores persistent account information separately from the short-lived authentication session used to launch the game.

The main account store is:

```text
~/.deylauncher/accounts.json
```

Individual profiles are stored under:

```text
~/.deylauncher/profiles/<uuid>/
├── profile.json
└── skin.png
```

This keeps account data simple and filesystem-based instead of introducing a database.

### Offline accounts

Offline usernames are explicitly managed through the Account interface.

The launcher does not silently create a new offline identity every time a username is entered on the Play screen. The account must be explicitly created or updated.

This is important because offline UUIDs are derived from the username.

### Skins

Imported skins are validated before being stored.

Supported skin formats include:

- 64×64 PNG
- Legacy 64×32 PNG

Offline skins are currently stored locally. They are not automatically synchronized to other players.

Online skin changes use the Minecraft Services API and require a valid Microsoft authentication session.

## Microsoft authentication

DeyLauncher uses the standard Microsoft/Xbox/Minecraft authentication chain:

```text
Microsoft
   ↓
Xbox Live
   ↓
XSTS
   ↓
Minecraft Services
   ↓
Minecraft profile
```

Authentication uses Microsoft's device-code flow, so the launcher does not need to embed a browser or run a local authentication server.

The Minecraft API application registration used by DeyLauncher has been approved, allowing the complete authentication flow to reach Minecraft Services.

## UI

The launcher interface is built with JavaFX.

Current UI functionality includes:

- Version selection
- Loader selection
- Play
- Account management
- Skin management
- Mod management
- Game settings
- Launcher settings
- Live game logs
- Theme switching
- Interface scaling
- Text scaling
- Font selection
- Startup window configuration

The layout is designed to scale with the window rather than relying on a fixed-size interface.

## Building from source

### Requirements

For development, you need:

- JDK 17+
- Git
- Internet connection

Clone the repository:

```bash
git clone https://github.com/deyrownnt/DeyLauncher.git
cd DeyLauncher
```

Run the launcher:

```bash
./gradlew run
```

On Windows:

```powershell
.\gradlew.bat run
```

The console/debug entry point can be started with:

```bash
./gradlew runConsole
```

## Building a standalone application

DeyLauncher uses Gradle together with `jpackage`.

The build process creates a self-contained application image containing:

- DeyLauncher
- JavaFX
- Required Java libraries
- A bundled Java runtime

The resulting application does not depend on the user's system Java installation.

### Linux

```bash
./gradlew clean prepareJpackage

rm -rf dist
mkdir -p dist

jpackage \
  --type app-image \
  --input build/jpackage-input \
  --main-jar DeyLauncher-0.1.0.jar \
  --main-class com.deylauncher.ui.LauncherApp \
  --name DeyLauncher \
  --icon src/main/resources/app-icon.png \
  --dest dist \
  --java-options "--module-path \$APPDIR --add-modules javafx.controls,javafx.graphics" \
  --java-options "--enable-native-access=javafx.graphics"
```

The resulting application is located in:

```text
dist/DeyLauncher/
```

### Windows

The Windows build uses the same Gradle packaging pipeline and `jpackage`, with the Windows icon and launcher-specific packaging options.

## Automated builds

GitHub Actions builds both supported platforms automatically whenever changes are pushed to `main`.

```text
GitHub
  │
  ├── Build Linux
  │     └── DeyLauncher-Linux.tar.gz
  │
  └── Build Windows
        └── DeyLauncher-Windows.zip
```

The workflow uses:

- `actions/checkout`
- `actions/setup-java`
- Gradle
- `jpackage`
- `actions/upload-artifact`

Build artifacts are available from the corresponding GitHub Actions run.

## Project status

DeyLauncher is actively being developed.

The current build already includes the core launcher pipeline:

- Minecraft version resolution
- Game file downloading
- Automatic Java runtime selection
- Vanilla launching
- Fabric support
- Forge support
- Microsoft authentication
- Account/profile management
- Skin management
- Mod management
- Cross-platform packaging

Some systems are still evolving, particularly online account persistence, skin synchronization for offline accounts, and additional platform support.

## Roadmap

Planned improvements include:

- [ ] macOS packaging
- [ ] More complete account/session persistence
- [ ] Offline skin synchronization through a companion system
- [ ] Cape support
- [ ] Improved release/update distribution
- [ ] Additional launcher customization
- [ ] Further UI/UX improvements

## Development philosophy

DeyLauncher tries to avoid unnecessarily reimplementing Minecraft's existing infrastructure.

Where possible, it consumes official metadata and APIs instead of hardcoding version-specific information.

Examples:

- Minecraft versions come from Mojang's version manifest
- Java runtime requirements come from version metadata
- Fabric profiles come from Fabric's metadata API
- Forge installation uses the official Forge installer
- Microsoft authentication follows the normal Microsoft/Xbox/Minecraft authentication chain

This should make the launcher easier to maintain as Minecraft and its mod loaders evolve.

## License

See [`LICENSE`](LICENSE).
