# DeyLauncher GitHub backend (Friends, capes, option kits)

DeyLauncher uses GitHub for the shared friends roster, the Dey capes, and the shared option-kit sets. That used to mean a one-time manual `Setup GitHub properties` step for every machine. It no longer does: a freshly downloaded DeyLauncher has the shared backend wired up for everyone, online and offline accounts alike. Open the Friends tab and it just works.

## How it works

On startup, `GitHubConfig.load()` resolves the backend credentials in priority order:

1. The current user can override on a single machine with a local file at `~/.deylauncher/github.properties`. This is only for pointing a build at a different repo, or for the person operating the shared backend.
2. If there is no local override, the launcher uses the backend embedded in the jar itself. The build XOR+Base64 obfuscates the credentials (key `DeyLauncher-backend-v1`) into the resource `/deylauncher-backend.dat`. That is a speed bump against casually reading the token out of the jar, not real encryption. The real containment is that the token is a fine-grained credential scoped to a single repo with Contents read and write and nothing else.
3. If neither is present, features that need GitHub report `not set up` and degrade gracefully.

Because of step 2, every distributed DeyLauncher build ships with working Friends, capes, and option kits out of the box -- no key, no file, and no setup dialog on the user side.

## The shared backend token

The embedded token belongs to the public DeyLauncher bot account `onpishi` / `DeyLauncher-Friends`. It is the same fine-grained PAT the project already keeps in its GitHub secrets and uses to publish releases, so it is safe to embed (Contents read and write to that one repo, no other scope). The token is never logged and never copied into a Minecraft instance.

## Running your own backend (optional, for group organizers)

If your group wants its own private friends repo instead of the shared public one:

1. Create a fine-grained PAT on the bot account: `Settings` > `Developer settings` > `Personal access tokens` > `Fine-grained tokens` > `Generate new token`. Repository access: only your private repo. Permissions: Contents -> Read and write. No other permission. Set a reasonable expiration.
2. Add that token to the deyrownnt/DeyLauncher repo as the secret named `DEYLAUNCHER_GITHUB_TOKEN`. The value may be just the token itself, or a full `key=value` properties block (`token`, `owner`, `repo`, `friendsPath`, and so on).
3. CI reads that secret at build time and bakes it into `deylauncher-backend.dat` via the `embedGithubCredentials` Gradle task. The token never lives in the repository: it comes only from the GitHub secret at build time.

A single user can also point just their own install at a different backend by writing `~/.deylauncher/github.properties`:

```properties
token=github_pat_...
owner=your-bot-account
repo=your-private-repo
friendsPath=friends.json
```

Restart DeyLauncher (or open Friends) and it picks the local override up automatically.

## What this protects

The Microsoft OAuth client ID is intentionally public: it identifies the desktop app and is required by the Microsoft device-code flow. It is not an account credential. The GitHub token is a private credential and must never be committed or hard-coded in source -- its containment is the fine-grained, repo-scoped permission and the build-time embedding from a CI secret, never a value checked into the repository.

## What DeyLauncher actually does with this

- One `GET` to read the whole friends graph (friend lists, pending requests, presence/status, shared server addresses).
- One `PUT` to write changes, with automatic retry if two people save at the same moment (GitHub rejects the second write with a conflict; DeyLauncher re-fetches, reapplies the change, and retries).
- No polling loop, no heartbeat spam. Presence publishes only on app start, on invisible-mode toggle, and when the Friends page opens, so a single shared token stays well under GitHub rate limits.