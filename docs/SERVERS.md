# Optional Fabric server support

Build with `./gradlew build`. Install `fabric/build/libs/stacksmith-0.1.0.jar` in the
client's `mods/` folder and, optionally, in the Fabric server's `mods/` folder.
Both need Minecraft 26.3, Java 25, Fabric Loader and Fabric API as declared by the mod.
There is one artifact for both sides; Paper/Bukkit plugins are outside this implementation.

## Installation modes

| Installed on | Behavior |
| --- | --- |
| Client only | Editors, previews, imports, local presets, creative item delivery and vanilla commands. As op, the World tab lists the spawners and containers in the chunks loaded around you (read with `/data`) and its bulk edits (Edit all, Prime all, Edit item, Also change the others, Undo) work on those by commands, each target read fresh first and read back after. |
| Client and Fabric server | The World spawner index across saved dimensions, far-away verified bulk edits, server-side undo and prime, and server datapack tag suggestions. Used automatically whenever the hooks answer. |
| Server only | Exposes hooks for compatible clients. Unmodified clients can join normally; they receive no custom screens or additional commands. |

The client waits for a versioned capability reply on `stacksmith:world_v1`. Merely advertising
a plugin channel is insufficient. Without the hooks the World tab falls back to the client path
(loaded chunks, commands; see [CLIENT_FIRST_HANDOFF.md](CLIENT_FIRST_HANDOFF.md)); datapack
scanning stays disabled. The handshake completes a few seconds after joining: a list read from
loaded chunks before then is replaced by the server's. Connection changes clear negotiated
support, pending requests and remote results. Integrated singleplayer/LAN-host features keep their
existing local implementation, and are preferred over both. Order: integrated server, hooks,
client commands; never a user-facing setting.

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

The hello reply also advertises `undo` (put back the last bulk update, per player, where each
spawner is still as that update left it) and `prime` (set the countdown of the selected spawners
to 0). A client talking to an older server build leaves those two buttons disabled.

Chest discovery retains its existing saved-world implementation in singleplayer and loaded-chunk
implementation on remote servers. There is no remote whole-world chest operation: on a server,
container bulk edits always use the client command path over loaded chunks.

## Without the server mod (client path)

Spawners and containers are read with `/data`-style block queries, one at a time (the game keeps
one query open; `BlockReads` queues every query of the mod). Writes are ordinary commands sent in
order through `CommandQueue` (they share the `stacksmith:tmp` command storage slot): spawners as
`/setblock` (in steps through storage when long), prime as `data modify block … Delay set value
0s`, containers by building the contents in storage and merging them in (`data remove block` first
for keys the new contents drop, such as `LootTable`), so the block is never broken. Each target is
re-read before writing and skipped if it no longer matches the list, and read back afterwards; the
results count only what reads back as wanted. Undo keeps the before-data in the client (one level,
lost on disconnect). Only loaded blocks in your current dimension can be reached.

To try it in a dev client without a server: run with `-Dstacksmith.clientOnly=true` (for example
`JAVA_TOOL_OPTIONS=-Dstacksmith.clientOnly=true` before `block-scraper start`). The integrated
server and hooks are then ignored, and everything goes through the queries and commands above.

## Verification

`./gradlew build` runs core tests (including malformed, oversized and fragmented protocol documents)
and dedicated-server GameTests for handshake/session reset, stale-target detection, countdown/pool
preservation, authorization loss, and an index-to-write RPC round trip.

Verified in game (2026-11-12, Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3) with a
real client joining a local dedicated server: capability negotiation, whole-world index across
saved/unloaded regions and the Nether, verified Edit all and copies-browser writes (countdown kept,
changed targets and min-above-max skipped, targets near unexplored terrain skipped, far saved targets
loaded and their tickets released), Stop, remote datapack scan and travel; on a Fabric server without
the mod and on a vanilla server the World tab, Whole world and Scan datapacks are disabled with a
tooltip while editors, nearby search, placing and creative delivery work.

For a manual multiplayer check, run a 26.3 Fabric server with Fabric API, install the built JAR,
join using a client with the same JAR, and wait briefly for negotiation. World should enable and
show saved spawners after indexing. Apply a setting to several copies, then rescan to verify.
Repeat without the server JAR (and on a vanilla or Paper server), as op: the World tab should say
**Loaded chunks only** and list what is around you; Edit all, Prime all, container Edit all / Edit
item and Undo should work on those; Scan datapacks stays disabled. Disconnect/rejoin between these
cases to check that results and support never carry over from another server.
