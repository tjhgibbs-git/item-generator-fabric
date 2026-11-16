# World spawner workflow

## Client path (2026-11-16, later)

Without an integrated server or hooks, `SpawnerIndex` builds its list from the client's loaded
chunks (one `/data` query per spawner through `BlockReads`), for the current dimension only, and
`SpawnerWorldBatch` writes by commands through `CommandRun` (read fresh, skip if changed, send,
read back). Undo and prime work the same way; undo is kept client-side until disconnect. The
backend is chosen at each refresh (`Backend`): hooks arriving after join replace a client-built
list. `SpawnerBatch` (the old 128-block nearby search and unchecked writer) is gone: "Also change
the others" now always goes through the index. The World lists have a distance filter (*Within
32 / 64 / 128 / 256* of where you stand, from your live position, like the distance column).

## One review flow, scope bar, undo, prime (2026-11-16)

`SpawnerBatchScreen` (Find copies…) is gone. There is one bulk flow, `SpawnerApplyScreen`, reached
from the World tab's **Edit all**, the editor's **Also change the other N…** (finds the same kind via
the index, and includes the linked spawner), and **Prime all…** (sets `Delay` to 0, nothing else). It always writes the difference
between a base and the edit to an explicit, tickable list of targets; the apply button counts
targets that will really be written (ticked and not broken by the change).

- `SpawnerApplyScreen.Targets` carries the entries, unticked keys, and `unify`: settings typed into a
  "mixed" field with the usual value, which must still be written to all (`diffBase` makes them
  count as changed, locally and in the base sent to a server).
- `SpawnerWorldBatch` / `ServerBatch` keep the last run's before-data: `undo()` restores each
  target that is still as the run left it (one level; an undo can't be undone). Undo of a prime
  restores the countdowns; otherwise countdowns stay the spawner's own. Server ops `undo` and
  `apply{prime:1}` are advertised in the hello reply (`undo`, `prime`); older servers lack them and
  the client greys those buttons.
- (Since replaced: see "Client path" above.) `SpawnerBatch` was the loaded-chunk reader and
  unchecked command writer for servers without hooks.
- The editor's scope bar (`SpawnerEditorScreen.Scope`) is the single source for the mode's wording
  and colours: NEW, COPY (`Session.spawnerCopyOf`), ONE, ALL.

The sections below predate this; where they mention Find copies or matching options, the above
replaces them.

## World tab (grouped browser, 2026-11-03)

The World tab (`SpawnerWorldScreen`) groups the index by kind: the ordered list of spawned
entities' full data (`SpawnerSpec.sameMobs` semantics). Within a kind, copies are grouped by setup
(everything except `Delay`); the most common setup is the kind's base, and other copies show how
they differ. Locations sort by distance from where the player stood when the list was first
built (re-anchored on Rescan), so the order doesn't shift while touring.

Teleport uses `tp … facing` onto the block so the crosshair is on it and J edits it. **Edit**
travels and opens the editor with the list as its parent. **Edit all** opens the editor in group
mode (`SpawnerApplyScreen.Targets`); `SpawnerApplyScreen` reviews and runs
`SpawnerWorldBatch.startChanges`, which writes only the difference between the base and the
edit (pool if changed, each changed setting; never `Delay`). Each target is revalidated as
before, and results that would have `MinSpawnDelay > MaxSpawnDelay` are skipped. Targets failing
the conservative `safeToLoad` check are visited after the rest: the player is teleported
server-side, the write happens once the chunk is loaded by the player, and the player is put
back at the original dimension, position and rotation (also on cancel). Skips and failures are
listed per target with a reason.

The older **Find copies…** flow below (`SpawnerBatchScreen`, from the editor) is unchanged.


**Find in world…** opens a world-wide browser by default and starts discovery. It first flushes a saved world snapshot, then reads region files across dimensions and overlays current server chunks. Singleplayer is required for the world scope; Nearby is a secondary loaded-chunk scope for servers.

The browser searches mob labels, dimensions and coordinates. Matching copies uses the loaded source as saved, excluding the source position and dimension. All spawners switches to the full discovered inventory. Expand the Copies/Spawners row to inspect locations; each has an explicit Teleport action. Opening a location travels there and loads fresh block data into the editor. Pages keep long lists compact. The browser returns after cross-dimension travel so the list can be traversed continuously.

Matching options default to exact copies, ignoring live countdown. Same mobs matches entity data in order, ignoring pool weights, light rules and spawner settings; applying replaces the whole pool. Timing/range replacement is optional in this broader mode.

Review shows intended changes before applying. Singleplayer bulk execution runs sequentially on the server, reloads persisted targets asynchronously, revalidates raw NBT, preserves live countdowns and unknown fields, and checks serialized readback after writing. Changed/missing targets are skipped. Cancellation stops future writes; completed writes remain. Counts distinguish updated, skipped and failed. Confirmation is in-memory, with normal world saving responsible for persistence.

Discovery is a session snapshot, not a transaction with concurrent saving. Unsupported/unreadable chunks produce partial coverage counts. Discovery does not load chunks. The executor conservatively requires a fully generated saved neighbourhood before loading an unloaded target and skips explored edges instead of intentionally generating terrain. Per-target skip reasons, undo and individual selection remain future improvements.
