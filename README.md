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
- `/pb bots pearlid <id> <pearlId>` — set the pearl id for a bot (default: your username).
- `/pb pull <id>` — pull your pearl from that bot (`pearlplus load <you> <pearlId>`).
- `/pb bots cmd <id> <command…>` — run any command on that bot.

Requires the target bot to run a proxy that exposes the ZenithProxy-style HTTP command API (this is what the
shared token authorizes). AquariusProxy does not yet ship that server — see the repo notes.

Config: `config/proxybridge.json` (render toggles, `useXaero`, max HUD entries, swap command, the bot list).

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

v0.1 — first cut. Waypoints (PearlDrop empties) + swap/pull control. The renderer is intentionally simple
(line boxes + HUD list); Xaero's/XaeroPlus minimap integration and click-to-pull are future work.

[AquariusProxy]: https://github.com/aquariusnetwork9/AquariusProxy
