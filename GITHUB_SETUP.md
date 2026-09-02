# Setting up Friends (GitHub backend)

Friends needs one shared, private GitHub repo that every DeyLauncher
install talks to. This is a one-time setup, done by whoever runs the
"backend" for your DeyLauncher group (probably you). Nobody pastes a
token into chat with me -- you do all of this yourself, then just tell
the launcher (or the build) where to find it.

## 1. Create a dedicated bot GitHub account

Not your personal account. A brand new, free GitHub account that owns
nothing except what we're about to create. If it's ever compromised,
nothing of yours is at risk.

## 2. Create one private repo

Name it whatever you like, e.g. `deylauncher-data`. Keep it **Private**.
That's it for now -- DeyLauncher creates `friends.json` inside it
automatically on first use.

## 3. Generate a fine-grained Personal Access Token

On the bot account: **Settings > Developer settings > Personal access
tokens > Fine-grained tokens > Generate new token**.

- **Repository access**: "Only select repositories" -> pick the one repo
  you just made. Not "All repositories."
- **Permissions**: under "Repository permissions," set **Contents** to
  **Read and write**. Leave everything else at "No access."
- Set an expiration (90 days is reasonable -- you'll need to regenerate
  and update it when it expires).
- Generate it, and copy the token now -- GitHub only shows it once.

## 4. Two ways to configure DeyLauncher with this

### Option A -- for you, running from source (`./gradlew run`)

Create this file on your own machine:

```
~/.deylauncher/github.properties
```

```properties
token=github_pat_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
owner=your-bot-account-username
repo=deylauncher-data
friendsPath=friends.json
```

Save it. Restart DeyLauncher (or just open Friends) and it picks this up
automatically.

### Option B -- embed it in the installer you build for friends

This is what makes it so **friends who install the built jar/app-image
don't have to do any of this themselves.** Before building (see
`INSTALLER_GUIDE.md`), create:

```
secrets/embedded-github.properties
```

(inside the project root, right next to `build.gradle.kts`) with the
**exact same content** as the file in Option A. This folder is already
listed in `.gitignore`, so it never gets committed if you put the
project under git.

When you run `./gradlew shadowJar` (or build an installer), Gradle
automatically bakes this file into the jar as a bundled resource --
you'll see a log line confirming it (`"embedding GitHub token from
secrets/embedded-github.properties into this build"`). Every install of
that build already has Friends working, with zero setup from the person
who receives it.

**DeyLauncher checks both locations, in this order:** a person's own
`~/.deylauncher/github.properties` (if they've set one up themselves)
always wins over the embedded one -- so you can still hand someone a
build with your token embedded, and they can override it with their own
config later if they ever want to point at a different backend.

## 5. The tradeoff, stated plainly

Anyone who has the token -- whether they read it out of
`github.properties` or decompile it out of a distributed jar -- can
read/write the whole shared `friends.json`, including everyone's
presence status and friend graph, not just their own. That's inherent
to "no real server holding the secret." The mitigations that make this
an acceptable tradeoff for a small friend group:

- The token belongs to a **dedicated bot account**, not your personal
  one -- worst case, you lose control of two throwaway repos, not your
  real GitHub identity.
- The token is **fine-grained and repo-scoped** -- it can't touch
  anything outside the one repo it was issued for.
- You can **rotate it** at any time (regenerate on the bot account,
  update `secrets/embedded-github.properties`, rebuild and redistribute)
  if you're ever worried it's leaked further than intended.

If this ever needs to scale past a small trusted group, the real fix is
moving to per-user GitHub OAuth or a small serverless proxy that holds
the token server-side instead -- both were discussed earlier in this
project's design and remain the path forward if/when it's warranted.

## What DeyLauncher actually does with this

- One `GET` to read the whole friends graph (friend lists, pending
  requests, presence/status, shared server addresses).
- One `PUT` to write changes, with automatic retry if two people save
  at the same moment (GitHub rejects the second write with a conflict;
  DeyLauncher re-fetches, reapplies the change, and retries).
- No polling loop, no heartbeat spam -- presence publishes only on app
  start, on invisible-mode toggle, and when the Friends page opens.
  This matters because every install currently shares this one token's
  rate limit (5,000 requests/hour).
