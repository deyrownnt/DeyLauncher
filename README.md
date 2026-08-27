# DeyLauncher

**DeyLauncher** is an independent, community-focused launcher for **Minecraft: Java Edition**.

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

DeyLauncher is currently under active development.

## Current Status

### Authentication & Version Information

- [x] Microsoft account sign-in
- [x] Microsoft Device Code authentication
- [x] Xbox Live authentication
- [x] XSTS authentication
- [ ] Minecraft Services authentication approval
- [x] Mojang/Minecraft version manifest retrieval
- [x] Graphical launcher
- [x] Java versions fix
- [ ] Mods manageing on forge and fabric

The Microsoft authentication system uses a registered Microsoft Entra application.

Minecraft Services access for the application is currently pending review.

## Planned Features

### Minecraft Versions

DeyLauncher will support multiple Minecraft: Java Edition versions.

The initial versions planned for testing are:

- `26.2`
- `1.21.11`

Users will eventually be able to:

- Select a Minecraft version
- Download versions through the launcher
- Add additional versions
- Update the version list
- Manage installed versions

Version information will be obtained from official Minecraft/Mojang services where appropriate.

### Modding

DeyLauncher is planned to support modded Minecraft installations.

Planned functionality includes:

- Fabric
- Forge
- Other supported mod loaders
- Installing mod loaders from the launcher
- Installing and removing mods
- Managing mods per Minecraft version
- Separate mod profiles

The launcher will not redistribute Minecraft itself or modified copies of Minecraft.

### Player-Hosted Servers

One of the main goals of DeyLauncher is to make player-hosted Minecraft servers easy to create and join.

A player will be able to create a server directly from the launcher.

The planned system works approximately like this:

```text
Player creates server
        ↓
Server configuration
        ↓
Server information published
        ↓
Server appears in DeyLauncher
        ↓
Player joins
        ↓
A permitted player hosts the server
        ↓
Other players connect to the current host
```

The goal is to avoid requiring the server owner to rent a traditional dedicated server.

Players who are authorized to host a server will be able to download the required server files and host the server themselves.

If another authorized player is currently hosting the server, new players can connect through that host.

Server information is planned to be stored through a GitHub-based registry.

### Server Addresses

DeyLauncher is intended to support normal Minecraft server addresses such as:

```text
mcppvp.club
```

The launcher will not require a central DeyLauncher server for the server registry.

Players will be able to use an external DNS/domain service to point a domain or subdomain toward their current host.

The exact networking and NAT traversal system is still under development.

### Friends

DeyLauncher is planned to include a lightweight friend system.

Players will be able to:

- Create a DeyLauncher profile
- Add other players as friends
- Accept friend requests
- See their friends inside the launcher
- Join supported player-hosted servers through friends

The initial design uses GitHub as part of the decentralized data infrastructure rather than requiring a dedicated central database.

### Launcher

The planned launcher interface will include:

- Microsoft account management
- Minecraft version selection
- Play button
- Server browser
- Server creation
- Friends
- Mod management
- Settings
- Skin management

### Settings

Planned settings include:

- RAM allocation
- Minecraft resolution
- Fullscreen/windowed mode
- Java configuration
- Dark mode
- Light mode

### Skins

DeyLauncher is planned to provide a way to view and manage the player's Minecraft skin from the launcher.

## Technology

DeyLauncher is written in **Java**.

Current technologies include:

- Java
- Gradle
- JavaFX
- Gson
- Microsoft OAuth / Device Code authentication
- Xbox Live authentication
- XSTS
- Minecraft Services APIs
- GitHub-based services

The launcher is intended to support:

- Linux
- Windows

## Development

### Requirements

- JDK 17 or newer
- Internet connection
- Gradle Wrapper

Run the application with:

```bash
./gradlew run
```

On Windows:

```powershell
gradlew.bat run
```

### Building

A runnable JAR can be built using:

```bash
./gradlew shadowJar
```

The resulting JAR will be located in:

```text
build/libs/
```

## Project Structure

The project is currently organized around the following components:

```text
src/
├── main/
│   └── java/
│       └── com/
│           └── deylauncher/
│               ├── auth/
│               ├── version/
│               └── Main.java
│
├── build.gradle.kts
└── settings.gradle.kts
```

The structure will evolve as additional launcher functionality is implemented.

## Design Goals

DeyLauncher is designed around several principles:

1. **Player ownership** — players should authenticate with their own Microsoft accounts.
2. **No central game server** — the planned server system should rely on player-hosted machines.
3. **Community driven** — server information and community features should minimize dependence on a central backend.
4. **Cross-platform** — Linux and Windows support.
5. **Mod friendly** — Fabric, Forge and other modded installations should be manageable from the launcher.
6. **Open development** — the project is developed transparently.

## Minecraft

Minecraft is a trademark of Mojang AB.

DeyLauncher is an independent third-party project and is not affiliated with, endorsed by, or sponsored by Mojang or Microsoft.

DeyLauncher does not distribute Minecraft game files. Users are responsible for obtaining Minecraft through official channels and complying with the applicable Minecraft EULA and usage guidelines.

## Project Background

DeyLauncher originally started as a school programming project.

What began as a small Java project to learn about application development, APIs, authentication, and software architecture gradually evolved into a larger personal project.

The current goal is to turn it into a functional, cross-platform Minecraft: Java Edition launcher with community-oriented features such as player-hosted servers, friends, version management, and mod-loader support.

## License

DeyLauncher source code is licensed under the MIT License.

This license applies only to the original source code of DeyLauncher.
Minecraft and all Minecraft-related trademarks, game files, assets, and other
intellectual property remain the property of Mojang AB and Microsoft and are
not covered by this license.
