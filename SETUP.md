# ProxyBridge — Setup (from zero to "channel ready")

If `/pb status` shows **`proxy: false, channel: not ready`**, read this. Those two flags do **not**
depend on anything you type into the mod — they depend on your actual Minecraft connection. The most
common mistake is thinking the **IP + token** entry connects the bridge. **It does not.**

## What the two flags actually mean

| Flag in `/pb status` | What it checks | How to make it `true` / `ready` |
|---|---|---|
| `proxy: true/false` | Is your Minecraft client connected **through** AquariusProxy? (it reads the server *brand*, which the proxy stamps with `(AquariusProxy)`) | Point your Minecraft **Multiplayer** connection at the **proxy's address**, not directly at the server. |
| `channel: ready/not ready` | Has the proxy announced the `proxybridge:main` channel back to you? | Enable the **Bridge module** on the proxy: `bridge on`. It is **off by default**. |

The bridge is **not something you dial into.** It piggybacks on your normal "Minecraft → AquariusProxy →
server" connection. There is no IP/token to make the bridge connect — you just have to actually be
playing *through* the proxy, with the Bridge module on.

> **The IP + token (`/pb bots add <id> <url> <token>`) is a different feature.** It registers a bot's
> **HTTP API** so you can pull *your* pearl from a bot you are **not** connected through (e.g. you're out
> in the world on 2b2t directly). It has zero effect on `proxy:`/`channel:`/waypoints. If all you want is
> live waypoints + swap/pull over the channel, you can ignore the bot list entirely.

## Step by step

### 1. Run AquariusProxy and get its bot in-world
Start the proxy (locally or on your VPS) and let its bot connect to the server and get **past the queue,
spawned in**. You can only join *through* the proxy once its bot is actually in the world — that's how
ZenithProxy/AquariusProxy works (your client shares the bot's session).

### 2. Enable the Bridge module on the proxy
It's **off by default**. Turn it on with any of these (they're the same command, different entry points):

- **Proxy console / terminal:** `bridge on`
- **In-game** (whatever account is controlling the proxy): `!bridge on` (default in-game prefix is `!`,
  and `/bridge on` also works since slash-commands are enabled)
- **Discord** (if you wired it up): `.bridge on`

Verify on the proxy with `bridge status` — it should show `Enabled: ✓`. This setting persists in the
proxy config, so you only do it once.

### 3. Connect Minecraft **through** the proxy
In Minecraft (1.21.4 Fabric, with **Fabric API** + the **ProxyBridge** jar installed):

- Multiplayer → Add Server → **Server Address = the proxy's listen address**:
  - Proxy on the **same PC**: `localhost` (or `127.0.0.1`) — port `25565` is the default, so no port needed.
  - Proxy on a **VPS**: `<vps-ip>:25565` (default bind is `0.0.0.0:25565`; make sure that port is open in
    the VPS firewall and you're allowed to connect — you, the owner, are).
- Join **that** server entry. Do **not** add `2b2t.org` (or whatever the real server is) directly — if you
  join the real server directly, you're bypassing the proxy and `proxy:` will stay `false`.

### 4. Confirm
Once you've spawned in *through* the proxy, run `/pb status` (alias of `/proxybridge status`). It should now
read:

```
ProxyBridge — proxy: true, channel: ready, waypoints: 0
```

Then `/bridge test` on the proxy pushes a debug waypoint at the bot — you should see a box in-world and the
waypoint count tick up. `/bridge test clear` removes it.

## Troubleshooting

- **`proxy: false`** — You are not connected through the proxy. You joined the real server directly, or the
  address you joined isn't the proxy. Re-check step 3. (Entering an IP/token in the bot list does **not** fix
  this — that's the HTTP-pull feature, not the connection.)
- **`proxy: true` but `channel: not ready`** — You're through the proxy, but the **Bridge module is off**.
  Run `bridge on` on the proxy (step 2), then reconnect or wait a couple seconds — the proxy re-announces
  the channel to every active connection each tick, so it should flip to `ready` without a reconnect.
- **Both correct but no waypoints** — That's normal until something publishes a group. PearlDrop publishes
  its empty chambers automatically when PearlDrop is enabled and `bridge pearldrop on` (default on). Use
  `/bridge test` to prove rendering works independent of any feature.
- **Pearl pull / swap does nothing** — `swap`/`cmd` over the channel only work when `channel: ready`. The
  command must also be on the proxy's invoke allow-list (`bridge allow list` to see it, `bridge allow add
  <cmd>` to permit one). The **HTTP pull** (`/pb pull`) is the path that works when you're *not* connected
  through the proxy — that one needs the bot registered with `/pb bots add` and a token scoped for
  `pearl.pull`.

## TL;DR
1. `bridge on` on the proxy.
2. Connect Minecraft to the **proxy's** address (not the real server).
3. `/pb status` → `proxy: true, channel: ready`.
4. The IP + token field is only for remote HTTP pearl-pull — unrelated to the channel.
