# Spawner workspace: design proposal

Status: design proposal with an initial world-browser implementation now present. See SPAWNER_BULK_FLOW.md for implemented behavior and remaining limitations; advanced filters, annotations and individual selection below remain proposed.

## Goal

Give a survival map maker a dependable inventory of placed spawners: find every zombie spawner in a world, select matching copies and rebalance them together. A radius search remains useful when working on a room. Keep reusable spawner presets separate from placed instances. Bulk Spawner Editor and Spawner Tweaker are reference points to improve on, not formats that must be copied.

The current “Apply to nearby copies” starts from an edited spawner and silently skips unloaded chunks. Its exact-spec or exact-pool matching is not a search for every spawner containing a given mob. The existing library is preset/history storage, not an inventory of world blocks. A result must never imply complete coverage when the scan was partial.

## Proposed first release

Add **Find in world** to the Spawners area, opening a dedicated workspace. Preserve the direct look-at-and-edit shortcut and the preset library. The workspace has three stages, with only the relevant controls shown at each stage:

1. **Choose scope and scan.** Default to all saved dimensions in a singleplayer world, with a secondary nearby radius around the player. Show progress, cancellation and a clear coverage summary. No terrain generation or forced chunk loading.
2. **Find and select.** Search by mob ID/name, optional nickname, biome, dimension or coordinates. Offer sorting by mob, nickname, biome and distance, with a stable coordinate tie-breaker. Each compact row shows mob or mob pool, nickname if present, biome, dimension and coordinates. A mob filter matches *any* entry in a multi-mob pool; show the other entries rather than hiding them. Unknown values remain visible and sort consistently.
3. **Select and rebalance.** Select individual rows, all filtered results, or find copies identical to a chosen spawner. Show selection counts separately from filter counts. Review the selection, edit a representative spawner in the existing editor, then preview the affected count and intended changes before applying. Default the identical-copies workflow to matching the entire spawn definition and settings except the live countdown; explain precisely what is matched. Retain each target's countdown. Show conflicts and failures and let the user inspect or retry them.
4. **Act on one result.** A detail panel shows the complete mob pool and location, scan freshness, plus **Locate**, **Teleport**, and **Edit**. Locate temporarily highlights a loaded spawner; teleport is an explicit action, and Edit reloads current data before opening the existing editor. A missing or changed block is reported and can be removed or refreshed from the results.

Illustrative layout (adapt to available screen space):

```text
 Spawners / Find in world
 [All dimensions v] [Index world]  [Nearby…]    Indexed: 1,248 | 2 skipped
 [Search mob, name, biome or coordinates       ] [Sort: Mob v]
 Results                                Selected spawner
 Zombie + Skeleton   Forest  Overworld  Zombie + Skeleton
 Zombie              Plains  Overworld  Forest · Overworld · 120 64 -30
 Skeleton            Desert  Nether     Snapshot from 12:04
                                        [Locate] [Teleport] [Edit]
```

At narrow sizes, selection can open the detail view instead of squeezing both columns. Show a useful empty state before scanning, a distinct no-matches state, and a partial-scan warning with counts and reasons. Keep bulk application behind selection and a separate review step.

### Advanced search

Start with a simple search field and an expandable advanced panel. Add explicit filters for mob type, mob tags, armour and nickname, as well as biome and dimension. Tag means the spawned entity's `Tags` data, not a spawner annotation or registry tag. Match individual mobs within spawn potentials without implying that the whole pool matches. Make matching semantics visible: for example, an armour filter and a tag filter should both match the same mob entry. Offer a separate exact-copy action for comparing the full spawner, avoiding an ambiguous “same mob” shortcut. Sort by tags and nickname as well as mob, biome and distance; define a deterministic ordering for multiple tags and missing values. Unknown or unparseable data must not be presented as a definite match or non-match without an explanation.

Nicknames belong to the world and are attached to a dimension and position, stored with the world so they persist across sessions and travel with its files. They are independent of reusable presets and do not require modifying spawner NBT. Show when a location no longer holds a spawner, and allow names to be changed or removed. Replacing a spawner at the same location can retain its annotation; moving it does not automatically move the name.

## Index and reliability

Use the chest index as a starting point: inspect saved region files across dimensions in the integrated server's world, then overlay currently loaded server chunks so unsaved changes win. Scan existing chunks only, with progress and cancellation. Treat this as a session snapshot; show when it was made and provide rescan. Count unreadable/unsupported chunks and parsing failures; cancellation and errors must produce an explicitly partial result. Check concurrency and server-thread ownership before reusing this approach. The existing chest index documents compression and old-format limitations; surface equivalent limitations rather than silently claiming completeness.

Extract all mob IDs from both the current spawn data and spawn potentials where present; preserve an unknown state for absent or unrecognised data. Determine biome at the spawner position from chunk data, accounting for the world's stored biome format and vertical position; if unavailable, display Unknown and count it. Never infer a biome from the currently loaded client world for a distant result. Verify the exact version-specific NBT and APIs during implementation.

For nearby scope, a clearly labelled loaded-chunks-only search is acceptable. Full-world indexing and bulk editing must not depend on the player having visited or loaded the targets in the current session. Remote servers are outside the initial full-world promise: disable full indexing with a useful explanation, or explicitly offer loaded-chunk-only discovery if feasible.

An indexed location is dimension plus block coordinates within a particular world, not proof that the same spawner still occupies the position. Revalidate the block and the relevant matching data immediately before each edit; skip changed targets and report them rather than overwriting unexpected changes. Avoid assigning opaque numeric IDs unless a concrete workflow needs them.

### Executing changes across the world

Investigate integrated-server chunk loading and live block-entity editing first. An integrated server may be able to load existing chunks and update them on its server thread without moving the player or editing region files directly; this needs a version-specific feasibility and performance check. Never generate missing terrain as a side effect. Persist through normal world saving, and release any temporary chunk tickets. Throttle work across ticks and expose progress and cancellation.

If that approach is not viable, a teleport-based executor is acceptable: group targets by dimension and chunk proximity so each loaded area handles multiple spawners, wait for chunks and acknowledgements, and return the player to the starting dimension, exact position and orientation on completion, cancellation or recoverable failure. Explain travel before starting. Do not promise restoration after disconnect or crash; record enough recovery information to offer a return when possible. Avoid teleporting once per spawner. A region-file rewrite requiring a world reload is not an acceptable normal workflow.

Apply only after a preview and confirmation. Re-read each target, preserve its countdown, and report confirmed successes, skipped stale targets and failures separately. Commands merely queued or sent are not confirmed writes. Cancellation stops future work; already completed writes remain. Bulk changes are not assumed atomic, so make the result clear and investigate an undo strategy before promising one.

### Feasibility investigation (26.3)

Inspection of the locally installed Minecraft classes shows a plausible live-server route: `ServerChunkCache` exposes `addTicketAndLoadWithRadius`, `getChunkFuture`, and ticket removal; a `SpawnerBlockEntity` exposes its serialized data through `saveWithoutMetadata` and `loadWithComponents`/`loadCustomOnly`, followed by `setChanged`. `ServerLevel.sendBlockUpdated` can notify tracking clients. A dedicated 26.3 GameTest server verified an in-memory direct update of a loaded spawner: changing spawn count retained the rest of the serialized data, including a two-mob pool, tags, weights and countdown. This is not yet an end-to-end distant world-edit test.

The `getChunk(..., FULL, false)` flag alone is **not** an existing-chunk loader: bytecode shows that it does not add a ticket and returns an unloaded result when no suitable holder exists. A GameTest confirmed a distant unticketed chunk stays unloaded. With a suitable holder it schedules a chunk generation task. Another GameTest confirmed that a radius-zero loading ticket created a FULL chunk at a position with no saved chunk; therefore tickets cannot simply be used on arbitrary index locations. A region-file index entry alone does not establish that all relevant chunks are already fully generated. Do not ship this path under a no-generation guarantee until a controlled test checks target and neighbour region data, incomplete chunks, and ticket cleanup. In particular, avoid blocking the server thread on a future; use asynchronous completion, marshal mutations back to the server thread, and limit concurrent tickets.

The existing `SpawnerSpec.sameAs` compares serialized canonical specifications after removing `Delay`, and existing unit tests cover countdown and settings differences. However, parsing defaults and reserialization may normalize data; an exact-copy and stale-write comparison must be checked against raw, unfamiliar and modded NBT before promising lossless identity. Preserve unknown fields and components, and compare the entire relevant payload rather than only the visible editor fields. Loading block-entity data can also reset internal caches or omit components; verify a round trip and notification behaviour against real 26.3 spawners before implementing bulk writes. Save success in memory is distinct from durable disk persistence.

The existing chest index reads region files on a worker while the integrated server can save concurrently and then overlays server chunks enumerated by `forEachReadyToSendChunk`. That is a useful precedent, not proof of a consistent full-world snapshot: a save may race a region read, and ready-to-send may not cover every server-resident dirty chunk. Investigate server-managed chunk scanning and enumerate all relevant loaded chunks, or document and detect snapshot races. Report file and chunk failures individually; a mere completed scan is not proof of complete coverage.

The GameTests run in a temporary dedicated server world with no client. A mapmaking-oriented test created a distant chunk, placed two copies with different countdowns, removed its ticket, waited for it to unload, then loaded it again. Both copies matched as identical despite their countdowns, and a direct change to both retained their individual countdowns. This supports the basic approach without player travel. A test run initially timed out because the test server advances ticks faster than asynchronous chunk loading; a longer tick budget passed, so real execution must wait for completion and handle failure rather than assume a fixed delay.

Prioritise hand-built maps in already visited, fully generated areas, including superflat worlds; broad handling of incomplete or exotic chunks is secondary. A player may still have placed a spawner near the edge of explored terrain, so don't claim that all neighbours are guaranteed generated. Report load failures, and clearly state any possible terrain generation before a bulk operation. Future validation should focus on finding and rebalancing a realistic spread of placed copies, preserving their data, progress and cancellation, and persistence after saving and reopening. The current experiment does not test the index, a full bulk executor, or a client interface.

## Deliberately later

Bulk editing identical copies is in scope; more general field-by-field mass transformations can follow once the core workflow is reliable. Persistent automatic indexing, background tracking, cross-server full-world scans and arbitrary marker systems are outside the first release.

## Implementation sequence

First establish indexing and a tested model for full spawn data, biome, and world-local annotations. Then build the searchable browser and selection, followed by the integrated-server bulk executor and review flow. Validate the non-teleport executor before deciding whether a travel fallback is needed. The GUI must be visually reviewed with block-scraper when the owner permits in-game testing.

## Validation plan

During development, exercise saved and unsaved chunks, dimensions, multiple spawn potentials, missing biome data, partial/cancelled scans, stale and replaced blocks, filtering and sorting, and no-match states. Test bulk revalidation, cancellation, persistence and partial failure. Check performance on a large world and ensure scans don't generate terrain. When the workspace exists, inspect it with block-scraper at several GUI scales, verify real keyboard input, and test locating, travel and reloading live spawner data.

## References

- [DaBsu](https://modrinth.com/project/dabsu): separates quick spawner editing from selection and batch workflows.
- [Spawner Tweaker](https://www.mccreations.net/en-US/datapacks/spawner_tweaker): groups and highlights registered spawners; its registration model and spawn potentials illustrate why coverage and mob membership must be explicit.
- [Amulet FAQ](https://www.amuletmc.com/faq): external world editing requires a closed world, highlighting the distinction between saved data and live unsaved chunks.
- [Chest indexing notes](CHESTS.md): existing project behaviour and limitations.
