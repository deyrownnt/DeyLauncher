# DeyLauncher Skin/Identity Protocol (for the future companion mod)

This is the stable, documented contract between DeyLauncher (this
project) and any future Minecraft-side companion mod that wants to read
or synchronize player skins/identities. **No mod exists yet** -- this
document exists so one can be built against a fixed format without
reverse-engineering the launcher.

## What DeyLauncher guarantees

For every account DeyLauncher knows about (online or offline), it
maintains:

```
~/.deylauncher/accounts.json
~/.deylauncher/profiles/<uuid>/profile.json
~/.deylauncher/profiles/<uuid>/skin.png      (present only if a custom skin is set)
```

### `accounts.json`
```json
{
  "activeUuid": "<uuid of the currently active account, or null>",
  "accounts": [ { ...same shape as profile.json... }, ... ]
}
```

### `profiles/<uuid>/profile.json`
```json
{
  "uuid": "<player uuid, no dashes, same form Mojang uses>",
  "username": "<current username>",
  "accountType": "ONLINE" | "OFFLINE",
  "skinModel": "CLASSIC" | "SLIM",
  "skinSource": "DEFAULT" | "OFFLINE_CUSTOM" | "MOJANG_ONLINE",
  "lastUpdated": <epoch millis>
}
```

- `skinSource: DEFAULT` -- no custom skin; `skin.png` will not exist.
- `skinSource: OFFLINE_CUSTOM` -- `skin.png` next to this file is a
  DeyLauncher-local custom skin. **Never uploaded to Mojang.** This is
  the case a companion mod needs to render for other DeyLauncher players
  to see it, since Mojang's own skin service has no knowledge of it.
- `skinSource: MOJANG_ONLINE` -- the account's real Mojang skin is
  already what Minecraft itself will render normally; a companion mod
  has nothing extra to do for this case.

### `skin.png`
Standard Minecraft skin PNG, always 64x64 or legacy 64x32 (DeyLauncher
validates this on import, see `SkinValidator`).

## What is explicitly NOT solved yet

**File presence on one player's disk does not make it visible to another
player.** `profiles/<uuid>/skin.png` on Player A's machine is not
reachable by Player B's machine on its own -- there is no network sync
layer in DeyLauncher today. That depends on the still-unbuilt GitHub
server-registry phase (see the launcher's own README), which is the
planned discovery/sync backbone for the whole self-hosted-server system,
skins included. A companion mod's realistic integration points, in
order of how much additional infrastructure they need:

1. **Local-only (buildable today, no new infra):** a mod running
   alongside DeyLauncher on the *same machine* can read its own
   `profiles/<active-uuid>/skin.png` directly and use it as this
   player's local render override. This does not require anything new
   from DeyLauncher.
2. **LAN/direct sync (needs a small new protocol):** two DeyLauncher
   instances on the same LAN, or connected to the same
   DeyLauncher-hosted server, would need some way to exchange
   `(uuid, skinModel, png bytes)` -- not designed yet. If this is the
   direction the companion mod takes, it should define and own that
   wire protocol; DeyLauncher's job is just keeping the local files
   above accurate and available.
3. **GitHub-backed sync (matches the server-registry design):** once
   that phase exists, skin metadata could piggyback on the same
   per-server GitHub repo already planned for server discovery/friends.
   Not built.

DeyLauncher intentionally does not fake any of the above -- the local
storage format is complete and stable; the multiplayer visibility layer
is real future work, not a shortcut.

## Cape (future)

Reserved, not implemented: `profiles/<uuid>/cape.png` alongside
`skin.png`, and a `capeSource` field alongside `skinSource` in
`profile.json`, once the separate DeyCape mod exists. Nothing in this
format needs to change shape to add that later -- just new optional
fields/files.
