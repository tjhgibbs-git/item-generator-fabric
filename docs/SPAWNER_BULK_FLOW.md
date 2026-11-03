# World spawner workflow

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
