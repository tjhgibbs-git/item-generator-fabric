# Optional Fabric server support

Build with `./gradlew build`. Install `fabric/build/libs/stacksmith-0.1.0.jar` in the
client's `mods/` folder and, optionally, in the Fabric server's `mods/` folder.
Both need Minecraft 26.3, Java 25, Fabric Loader and Fabric API as declared by the mod.
There is one artifact for both sides; Paper/Bukkit plugins are outside this implementation.

## Installation modes

| Installed on | Behavior |
| --- | --- |
| Client only | Editors, previews, imports, local presets, creative item delivery, vanilla commands and nearby tools work as before. |
| Client and Fabric server | Also enables the World spawner index across saved dimensions, verified remote bulk edits and server datapack tag suggestions. |
| Server only | Exposes hooks for compatible clients. Unmodified clients can join normally; they receive no custom screens or additional commands. |

The client waits for a versioned capability reply on `stacksmith:world_v1`. Merely advertising
a plugin channel is insufficient. World navigation, whole-world discovery/apply and datapack
scanning are disabled when the relevant hook is absent. The ordinary editors and nearby search
remain available. Connection changes clear negotiated support, pending requests and remote results.
Integrated singleplayer/LAN-host features keep their existing local implementation.

## Discovery and writes

The server saves before reading existing region files off its main thread, then overlays loaded
chunks on the server thread. Discovery never loads or generates chunks. Results are a snapshot:
use Rescan after another builder changes the world. Unchanged regions are reused during the session.
Unread/unsupported chunks are reported as partial results; stale region entries are not retained.

Each player has a private, revisioned server snapshot. Apply rejects superseded revisions, accepts
only locations in that snapshot and
re-reads each block before writing. Targets changed since discovery are skipped. Only selected
settings and/or the mob pool change; other data and the live countdown remain intact. Each write
is read back before it is counted as successful. Different clients cannot overwrite one another's
temporary command storage because remote world edits do not use command storage.

Far-away targets are loaded temporarily only when a conservative neighbourhood of saved FULL
chunks exists. Targets near unexplored terrain are skipped and listed: teleport there and edit
them locally. Remote bulk edits never teleport the player or generate terrain. Stop, disconnect,
server shutdown and authorization loss stop future writes and release chunk tickets. Completed
writes remain. One world operation runs at a time across clients; other users receive a retry message.

Server hooks require the server's normal game-master command permission and check it again during
writes. Requests are bounded to 8 MiB of UTF-8 SNBT, 24,000-byte frame bodies, 64 nesting levels and
100,000 selected targets. Oversized results produce an explicit error. Rescans are limited to one
per five seconds per player. An inactive operation times out on the client after five minutes;
progress replies extend a running write's deadline.

Chest discovery retains its existing saved-world implementation in singleplayer and loaded-chunk
implementation on remote servers. This change adds no remote whole-world chest operation.

## Verification

`./gradlew build` runs core tests (including malformed, oversized and fragmented protocol documents)
and dedicated-server GameTests for handshake/session reset, stale-target detection, countdown/pool
preservation, authorization loss, and an index-to-write RPC round trip.

The full build and all 19 server GameTests pass in the development environment. A block-scraper
client launch was attempted twice; the graphical check was blocked by OpenGL reporting
`Couldn’t find matching GLX visual` and Vulkan lacking `VK_KHR_surface`. A live multiplayer UI
check remains to be done.

For a manual multiplayer check, run a 26.3 Fabric server with Fabric API, install the built JAR,
join using a client with the same JAR, and wait briefly for negotiation. World should enable and
show saved spawners after indexing. Apply a setting to several copies, then rescan to verify.
Repeat without the server JAR: World and Scan datapacks should be disabled while Item, Mob,
Spawner and nearby operations remain accessible. Disconnect/rejoin between these cases to check
that results and support never carry over from another server.
