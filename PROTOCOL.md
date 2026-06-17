# ProxyBridge wire protocol

The single source of truth for the `proxybridge:main` plugin channel shared by **AquariusProxy**
(`com.aquarius.feature.bridge.BridgeProtocol`) and this mod
(`com.aquarius.proxybridge.net.BridgeProtocol`). Both implementations are pure-Java byte readers/writers so
the format is identical regardless of Minecraft mappings. Keep them in lockstep — if you change one, change both
and bump `PROTOCOL_VERSION`.

## Transport

- **Channel:** `proxybridge:main` (a Minecraft custom payload / plugin message).
- **Direction:** proxy → client carries display data; client → proxy carries control messages.
- The proxy **terminates** this channel — it is never forwarded to the real server, and the proxy strips
  `proxybridge:main` from the client's `minecraft:register` so the destination server never sees it.

## Primitives (match Minecraft's `FriendlyByteBuf`)

| Type      | Encoding |
|-----------|----------|
| `VarInt`  | 7 bits per byte, high bit = continuation |
| `String`  | `VarInt` UTF-8 byte length, then the UTF-8 bytes |
| `int`     | 4 bytes, big-endian |
| `boolean` | 1 byte (0/1) |

## Envelope (every message)

```
VarInt  protocolVersion   // currently 1
String  topic
bytes   body              // topic-specific, below
```

## Waypoint struct

```
String id          // stable id within a group, e.g. "pd_123_64_-200"
String name        // display label, e.g. "Pearl"
String dimension   // dimension registry id, e.g. "minecraft:the_nether"
int    x, y, z     // absolute block position
int    color       // 0xRRGGBB
int    ttlTicks    // 0 = no expiry (group sync controls lifetime)
```

## Topics

| Topic        | Direction      | Body |
|--------------|----------------|------|
| `hello`      | both           | `String side, String version, VarInt featureCount, String[] features` |
| `wp/sync`    | proxy → client | `String group, VarInt n, Waypoint[n]` — **replace** the whole group (primary op) |
| `wp/upsert`  | proxy → client | `String group, Waypoint` — add/update one |
| `wp/remove`  | proxy → client | `String group, String id` |
| `wp/clear`   | proxy → client | `String group` |
| `cmd/invoke` | client → proxy | `String name, String args` — run a proxy command (allow-listed proxy-side) |
| `toast`      | proxy → client | `String message` |

## Live behaviour

Feature modules on the proxy own a **group** (e.g. PearlDrop → `pearldrop.empties`) and re-`wp/sync` it on a
throttle. Because occupancy is recomputed live, a chamber that fills up just stops appearing in the next sync and
disappears on the client — no diffing or explicit removal needed.

## Control side

For commands the mod can also simply send an **unsigned chat command** (`/swap`, `/pearlplus pull`, …) which the
proxy parses normally — that's what `/proxybridge swap|pull|cmd` use. `cmd/invoke` over this channel is the
alternative in-band path (subject to the proxy's `invokeAllowList`).
