# DeyLauncher -- Phase 1

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

## Open question: your "26.2" version

Minecraft: Java Edition doesn't have a version called `26.2` -- Mojang
names releases like `1.21.1` and snapshots like `26w02a` (year + week).
Before Phase 2 (the actual game-launching code), I need to know exactly
which version you meant so the launcher points at something real:

- The current latest snapshot (whatever it is when we build this)?
- A specific past version you misremembered the number of?
- Bedrock Edition instead of Java? (Note: this whole project as scoped --
  custom servers, Fabric/Forge mods -- only makes sense for Java Edition;
  Bedrock doesn't support any of that.)

## What's next (Phase 2)

- Download the actual game files (client jar, libraries, assets) for a
  chosen version and build the real `java -jar ...` launch command.
- Minimal JavaFX window: login button, version dropdown, Play button.
- Settings: RAM slider, resolution, fullscreen toggle, light/dark mode.

Everything after that (Fabric/Forge install, GitHub server registry,
friends, self-hosted servers) builds on top of this same foundation.
