# World spawner workflow

**Find in world…** opens a world-wide browser by default and starts discovery. It first flushes a saved world snapshot, then reads region files across dimensions and overlays current server chunks. Singleplayer is required for the world scope; Nearby is a secondary loaded-chunk scope for servers.

The browser searches mob labels, dimensions and coordinates. Matching copies uses the loaded source as saved, excluding the source position and dimension. All spawners switches to the full discovered inventory. Expand the Copies/Spawners row to inspect locations; each has an explicit Teleport action. Opening a location travels there and loads fresh block data into the editor. Pages keep long lists compact. The browser returns after cross-dimension travel so the list can be traversed continuously.

Matching options default to exact copies, ignoring live countdown. Same mobs matches entity data in order, ignoring pool weights, light rules and spawner settings; applying replaces the whole pool. Timing/range replacement is optional in this broader mode.

Review shows intended changes before applying. Singleplayer bulk execution runs sequentially on the server, reloads persisted targets asynchronously, revalidates raw NBT, preserves live countdowns and unknown fields, and checks serialized readback after writing. Changed/missing targets are skipped. Cancellation stops future writes; completed writes remain. Counts distinguish updated, skipped and failed. Confirmation is in-memory, with normal world saving responsible for persistence.

Discovery is a session snapshot, not a transaction with concurrent saving. Unsupported/unreadable chunks produce partial coverage counts. Discovery does not load chunks. The executor conservatively requires a fully generated saved neighbourhood before loading an unloaded target and skips explored edges instead of intentionally generating terrain. Per-target skip reasons, undo and individual selection remain future improvements.
