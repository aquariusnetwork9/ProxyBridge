# ProxyBridge

A Fabric **client mod** (Minecraft 1.21.4) that pairs with [AquariusProxy] (a ZenithProxy fork). It is the client
half of a generic, bidirectional bridge:

- **Proxy → client:** the proxy pushes live data over the `proxybridge:main` plugin channel and this mod renders it.
  The first feature is **live waypoints** — e.g. PearlDrop scans a stasis room and the *currently empty* chambers
  appear as in-world boxes + an on-screen list, updating hands-free as chambers fill and empty.
- **Client → proxy:** the mod sends control commands back up (swap, pearl pull, or any command) so you can drive the
  proxy bot from the connected client.

Based on [rfresh2/ZenithProxyMod](https://github.com/rfresh2/ZenithProxyMod) (1.21.4 branch) — same build setup,
Mojang + Parchment mappings, and the proven `sendUnsignedCommand` control path.

## How it works

```
AquariusProxy  ──(proxybridge:main custom payload)──►  ProxyBridge mod  ──►  in-world boxes + HUD list
  Bridge module                                          WaypointStore / WaypointRenderer
       ▲
       └──────────────(unsigned /commands: swap, pearl pull, …)────────────  /proxybridge swap|pull|cmd
```

The proxy side lives in AquariusProxy: a `Bridge` module + `/bridge` command. Enable it with `/bridge on`. It
auto-publishes PearlDrop's empty chambers as the `pearldrop.empties` waypoint group. See `PROTOCOL.md` for the wire
format (kept byte-identical on both sides).

## Commands

- `/proxybridge status` (alias `/pb`) — proxy detected? channel ready? waypoint count.
- `/proxybridge swap` — send `/swap` to the connected proxy.
- `/proxybridge pull` — pull your pearl from the connected proxy (`pearlplus load <you> <you>`).
- `/proxybridge cmd <command…>` — send any command to the connected proxy.
- `/proxybridge hud <true|false>` / `boxes <true|false>` — toggle the built-in overlay.
- `/proxybridge xaero <true|false>` — mirror waypoints into Xaero's Minimap (a "ProxyBridge" set).

### Remote pearl bots (pull from a bot you're *not* connected to)

You can pull your pearl from any bot in a list you've been given access to. Each bot is reached over its HTTP
command API using a token its **owner** shares with you — no token, no access, and the bot simply isn't usable.
You pull from **one** bot at a time.

- `/pb bots add <id> <url> <token>` — register a bot (quote the url/token, e.g. `"1.2.3.4:7890"`).
- `/pb bots list` / `/pb bots del <id>`
- `/pb bots ign <id> <ign>` — set the bot's in-game name (used by the whisper intercept below).
- `/pb bots pearlid <id> <pearlId>` — set the pearl id for a bot (default: your username).
- `/pb pull <id>` — pull your pearl from that bot (`pearlplus load <you> <pearlId>`).
- `/pb bots cmd <id> <command…>` — run any command on that bot.

Requires the target bot to run a proxy that exposes the ZenithProxy-style HTTP command API authorized by the shared
token. AquariusProxy ships this as the RBAC **command API** (`perms api on`, localhost-bound by default — expose it
deliberately); the token is issued per user with `perms token issue <name>` and scoped by that user's role.

### Muted? Whisper your bot anyway (whisper intercept)

Normally you'd whisper a bot to pull your pearl — but if you're chat-muted, the whisper never lands. With the
intercept on, a whisper **to a registered bot** is rerouted over that bot's HTTP API instead of in-game chat, so the
mute doesn't matter (and the bot's RBAC role decides what your command is allowed to do).

- `/w <bot-ign> <command…>` (also `msg`/`tell`/`whisper`/`m`) — if `<bot-ign>` matches a registered bot's IGN
  (set with `/pb bots ign`), the command is sent to its API; a local line confirms the reroute. Whispers to real
  players are untouched.
- `/pb whisper <true|false>` — toggle the intercept (default on).

### In-game RBAC admin screen

`/pb admin [bot-id]` opens an in-game screen to administer a bot's access control (needs an **admin** token for that
bot — `perms` is admin-only). It reads the bot's state via `perms export` (structured JSON) and drives every change
back over the same API:

- toggle RBAC + the HTTP API; refresh
- a paginated **user table** (click to select)
- add a user (name + role), set a selected user's **role**, toggle **capability presets** (`group.*`) as checkboxes,
  **issue** a token (copied to your clipboard, shown once) / **revoke** one, flip **connect mode**, or **remove** them

With one registered bot, `/pb admin` (no id) opens it directly.

Config: `config/proxybridge.json` (render toggles, `useXaero`, max HUD entries, swap command, `interceptWhispers`,
`whisperVerbs`, the bot list — each bot has `id`, `ign`, `url`, `token`, `pearlId`).

## Xaero's Minimap

If **Xaero's Minimap** (or XaeroPlus) is installed, bridged waypoints are also mirrored into a dedicated
"ProxyBridge" waypoint set so they show as real minimap waypoints — not just the built-in in-world boxes/HUD.
This is a compile-only soft dependency (`modCompileOnly`, pinned to xaeros-minimap 26.1.0 for 1.21.4, the same
version XaeroPlus uses); it's fully guarded by `isModLoaded("xaerominimap")` and never breaks the mod if absent.

## Build

```
./gradlew build
```

Output jar: `build/libs/ProxyBridge-<version>+fabric-1.21.4.jar`. Drop it (plus Fabric API) into a 1.21.4 Fabric
client and connect **through** AquariusProxy with the `Bridge` module enabled.

## Status

v0.1 — first cut. Waypoints (PearlDrop empties) + swap/pull control + multi-bot remote pull + muted-safe whisper
intercept + in-game RBAC admin screen (`/pb admin`). The renderer is intentionally simple (line boxes + HUD list);
Xaero's/XaeroPlus minimap integration and click-to-pull are future work. None of it is live-tested yet.

[AquariusProxy]: https://github.com/aquariusnetwork9/AquariusProxy
