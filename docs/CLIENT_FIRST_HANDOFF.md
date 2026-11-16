# Handoff: client-side baseline for the world and bulk tools

Written 2026-11-16. Read this with [SPAWNER_BULK_FLOW.md](SPAWNER_BULK_FLOW.md) (top section),
[CHESTS.md](CHESTS.md) (top section) and [SERVERS.md](SERVERS.md).

## Status (later on 2026-11-16): done, except a real server

Items 1 to 5 below are implemented on branch `world-bulk-flow` and merged into `26.3`:

- `Backend` picks host → hooks → commands at each use; `-Dstacksmith.clientOnly=true` forces the
  command path (also hides the hooks) for testing in singleplayer.
- `BlockReads` is the one queue for every block query of the mod (scans, loads, read-backs).
- `CommandRun` is the client executor shared by spawners and containers: read fresh, skip changed,
  send through `CommandQueue`, wait, read back. It reads back automatically, so there is no separate
  **Check** button: the result counts already say what the server really holds.
- `SpawnerIndex` has the client backend (loaded chunks, current dimension, rebuilt when the
  dimension or backend changes); `SpawnerBatch` is deleted.
- `SpawnerWorldBatch` / `ChestWorldBatch` have command branches for apply, prime and undo.
- Distance filter on both World lists, from your live position, capped at the loaded area on the
  client path; remembered per world.
- Failed routine command replies (red "Nothing changed") are now kept out of chat too.

Tested in a dev client with the switch on (spawners: Edit all with the filter, Undo, Prime with a
target changed behind its back, Prime's Undo, Also change the others; containers: Edit item across
chest and barrel, Undo, Drop loot table on two loot chests), each result checked with `/data get`;
and without the switch (Edit all and Undo, both lists). **Not tested:** a real vanilla/Paper server,
and a client against a dedicated server with hooks for Undo/Prime.

## Where things stood before (kept for context)

The 2026-11-16 session reworked the world/bulk flow (scope bar, one review screen, undo, mixed
fields, containers in the World tab, By item, Prime all). `./gradlew build` passes with 22 game
tests and every flow was driven in a block-scraper client in singleplayer. **That work is in the
working tree, not committed** (branch `26.3`): check `git status` first and ask the owner before
committing. The jar has not been copied to `download/` or the Prism instance.

## What the owner wants

1. Stacksmith must be **mostly usable on servers where the mod can't be installed**. Design the
   client-only path first; server/integrated-server code is for what can't work without it.
2. **Keep the full-power version.** Whole-world index, verified writes, far-chunk loading and
   server-side undo stay, and stay the **default whenever they are available**. The client path is
   the fallback when they aren't, not a replacement, and not a user-facing choice of backend.
3. **Being op is assumed.** Group map-making is always done as op, so `/data`, `/setblock`,
   `/execute`, `/tp` are fair game. Don't build non-op workarounds or treat op as a limitation
   worth explaining at length.
4. **Searching within a radius is wanted in its own right**, not only as a fallback: on a map with
   many areas, the owner wants to filter the lists to "the bit I'm standing in". So radius is a
   filter on the World lists for every backend, and on the client backend it is also the scan's reach.
5. The project exists for custom map development: finding and rebalancing spawners, and buffing or
   nerfing specific items wherever they sit in chests. Spawner `Delay` is normally 0 during
   development and at release (hence Prime all).

## The gap today (server without the mod)

| Feature | Today | Target |
|---|---|---|
| Editors, scope bar, Save this spawner, Use as a new copy | Works | — |
| Also change the other N… | Works: loaded chunks within 128 blocks, commands, no undo | Add client undo |
| World tab → Spawners (so Edit all, Prime all) | Tab greyed out | Works on loaded chunks |
| Prime all | Unreachable | Works by commands |
| World tab → Containers, By item, teleport, single Edit | Works, but only via Chests → Find in world… (the World tab chip is disabled) | Reachable from the World tab too |
| Container Edit all / Edit item everywhere | Disabled unless you host the world | Works by commands on loaded containers |
| Undo | Host, or a server with an updated jar | Client-side fallback |

## Work to do

### 1. A client backend for the spawner index

Everything spawner-side hangs off `SpawnerIndex`, which has two backends: the integrated server
(region files + loaded chunks) and `ServerHooks` (`remoteRefresh`). Add a third, used only when
neither is available: the spawners in the client's loaded chunks, read with `/data` queries.

- `SpawnerBatch` already does this read (`search`, `readNext`, one block query at a time, timeout,
  world-change handling) and `entries()` already returns `SpawnerIndex.Entry` rows. It currently
  centres on `Session.spawnerPos` and **excludes** that block; the index needs a variant centred on
  the player that excludes nothing.
- `SpawnerIndex.available()` and `worldIdentity()` return false/null without a host or hooks. Both
  need the client case (`mc.getConnection()` as the identity). Note the hooks handshake only
  completes a second or so after joining (`ServerHooks.tick`, every 100 ticks): don't latch onto
  the client backend and then ignore hooks arriving. Simplest rule: choose the backend at each
  `refresh`, and when `ServerHooks.index` turns true, drop a client-built index and rescan.
- Entries are per dimension and go stale when you travel: clear and rescan on dimension change,
  and label the list "Loaded chunks only" the way `ContainerWorldScreen` already does for
  containers (`Theme.WARN` state text).
- `safeToLoad`/`fullChunks` have no meaning on this backend; nothing is loaded from afar.
- `StacksmithScreen.open`, the tab chips and `worldSwitch` gate on `SpawnerIndex.available()` and
  show `ServerHooks.REQUIRED`. Once the client backend exists the World tab is always available in
  a world; `ServerHooks.REQUIRED` should then only appear on things that truly need hooks.

### 2. Command-based writes wherever the verified writer is missing

The review screens already have the right shape; only the executor differs.

- **Spawners.** `SpawnerApplyScreen.Targets.nearby` already routes to
  `SpawnerBatch.applyChanges` (commands via `CommandQueue`, each target keeps its countdown and
  other settings via `SpawnerWorldBatch.preview`). `SpawnerWorldScreen.editAll`/`primeAll` build
  `Targets` with `nearby = false` and their chips require `SpawnerWorldBatch.available()`: on the
  client backend build them with `nearby = true`. `applyChanges` checks `SpawnerBatch`'s own
  `searchWorld`/`searchDimension`, and `WorldSpawner.link` calls `SpawnerBatch.forget()`; make
  sure the validation still passes when the entries came through `SpawnerIndex`.
- **Prime.** `SpawnerWorldBatch.canPrime()`/`startPrime` are host/hooks only. Client version: one
  `data modify block x y z Delay set value 0s` per ticked spawner through `CommandQueue`.
- **Containers.** `ChestWorldBatch` is integrated-server only, and `ContainerWorldScreen`'s
  Edit all / Edit item chips require `ChestWorldBatch.available()`. Client version: for each ticked
  entry, `ChestSpec.write(pos, state, true, Commands.CHAT_LIMIT)` (what `ChestEditorScreen.write`
  uses for a single container) queued through `CommandQueue`. `write` returns null when a value is
  too long for chat: report those targets as skipped with that reason, don't abort the lot.
  `ChestApplyScreen.Plan.wanted` already holds the per-container result, so only `start()` and the
  progress/results section need a command branch.
- Command writes share one storage slot (`Commands.STORAGE`), which is why everything goes through
  `CommandQueue` in order. Keep it that way.
- Before queuing, re-read each target (`WorldSpawner.read` / `ChestIndex.load`) and skip any that
  no longer matches what the list showed, so the client path keeps the "changed since the scan is
  left alone" promise. Reads are one at a time; for large selections show progress.
- Results on this path: say "commands sent", not "updated". Offer a **Check** that re-reads the
  targets and reports which now match (that is the client-side stand-in for readback).

### 3. Undo: server first, client fallback

Order of preference, picked automatically: integrated server → `ServerHooks.undo` → client.
The client fallback keeps the before-data it read in step 2 (per target) and writes it back by
commands, only where a fresh read still equals what it wrote. One level, like the others. It is
lost on disconnect; say so in the Undo tooltip rather than hiding the button. Same for containers.
Prime's undo must restore `Delay` (see `restoreDelay` in `SpawnerWorldBatch`/`ServerBatch`).

### 4. Radius as a filter on the World lists

Add a "Near me" control beside the dimension chip on both `SpawnerWorldScreen` and
`ContainerWorldScreen`: Everywhere / within 32 / 64 / 128 / 256 blocks of where you stand (the
lists already keep a sort `anchor`; decide whether the radius uses the anchor or the live position
and keep it consistent with the distance column). It filters like the dimension chip does, so
Edit all, Prime all and Edit item act on what is listed, and their tooltips already say "only the
N listed". On the client backend the largest useful radius is the loaded area; show that rather
than offering values that can't be met. Remember the choice per world like `query`/`dimension`.

### 5. Wording and docs

- Remove or narrow "Requires compatible Stacksmith world hooks" where a client path now exists.
- Update README (World tab, Containers, Servers), SERVERS.md's installation table, and the top
  sections of SPAWNER_BULK_FLOW.md and CHESTS.md.

## Testing

- block-scraper only drives singleplayer, so the client backend can't be reached there as is. Add
  a dev-only switch (e.g. `-Dstacksmith.clientOnly=true`, read once) that makes
  `SpawnerIndex`, `SpawnerWorldBatch.available()`, `ChestWorldBatch.available()` and the undo
  choice behave as if there were no integrated server and no hooks. Commands and `/data` queries
  work the same against the integrated server, so this exercises the real client path.
- The real check is the owner joining a server without the mod (vanilla or Paper) as op. List for
  them exactly what to try; this has not been done for any of the command paths, including the
  existing "Also change the others" nearby path.
- Dedicated-server client paths for Undo and Prime (hooks) were only tested server-side by game
  tests (`ServerHooksGameTests`); a real client against a dev server is still owed.

## Practical notes from this session

- Mute the dev client: `fabric/run/block-scraper/options.txt` must have
  `soundCategory_master:0.0` (the owner hears it). Stop the client when done.
- `block-scraper reload` fails as soon as a lambda is added or removed; expect to `restart`.
- Widgets inside scroll panes aren't in `widgets`: click by GUI coordinates (screenshot px / 3).
- `block-scraper command` prints nothing back. Read results from
  `fabric/run/block-scraper/logs/latest.log` (`grep "has the following block data"`).
- `block-scraper key j` didn't open the editor; open screens with `open class:…` or through the
  World list's Edit.
- `ItemEditorScreen`'s nested **Use item** calls `onDone` and then closes to its parent, so a
  callback can't show another screen directly (`ContainerWorldScreen.next` defers it a tick).
- A tick mark drawn in a row's `extract` is hidden by the chip on top of it: set the chip's
  message instead.
- `ChestLibraryScreen` still carries a `starred` flag that is always true; safe to tidy.

## The pull request

This work is on branch `world-bulk-flow`, as a draft PR against `26.3`. Keep the PR description
current as you go: it is the owner's record of what is done.

- After each piece of work, update the "Done" / "Still to do" lists in the PR description
  (`gh pr edit --body-file …`).
- Add screenshots of every screen you change. Capture them with block-scraper into
  `docs/screenshots/` (hover the cursor somewhere neutral first so no tooltip covers the screen),
  commit them, and embed them in the description with
  `![what it shows](https://raw.githubusercontent.com/tjhgibbs-git/item-generator-fabric/world-bulk-flow/docs/screenshots/<name>.png)`.
  Look at each PNG before embedding it. Replace a screenshot when its screen changes; don't leave
  stale ones (the 2026-11-16 set was captured unattended at the end of the session and not
  reviewed: check them and retake any that are wrong).
- Say plainly in the description what was tested and how (singleplayer, forced client-only switch,
  real server), and what was not.
