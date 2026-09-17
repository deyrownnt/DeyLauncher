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

## 4. Configure DeyLauncher locally

### Local setup only

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

Never put this file in the project, source control, a CI build secret that is copied into an artifact, or
a distributed launcher. DeyLauncher intentionally reads it only from the current user's home directory and
never copies it into a Minecraft instance. Each person who needs write access must use their own least-
privileged, repo-scoped credential; a public launcher needs a server-side service or per-user OAuth instead.

## 5. What this protects

Microsoft’s OAuth client ID is intentionally public: it identifies the desktop app and is required by
Microsoft’s device-code flow. It is not an account credential. Microsoft refresh tokens, Minecraft access
tokens, and GitHub tokens are private credentials; they must never be committed or shipped in a jar/app image.

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
