# Temporary handoff: verify optional Fabric server hooks on the user's PC

> **DO NOT INCLUDE THIS FILE IN THE FINAL MERGE.** This is a working handoff for a fresh
> Codex session, committed at the user's request. Keep it while verifying the branch, then
> remove it with `git rm docs/SERVER_HOOKS_HANDOFF.md` and commit that removal before the
> PR is merged/squashed. Put lasting setup instructions in `docs/SERVERS.md` and validation
> results in the PR description. Do not merge the PR without the user's instruction.

## Context and requested outcome

Repository: https://github.com/tjhgibbs-git/item-generator-fabric

- Branch: `feat/optional-server-hooks`, based on `26.3`.
- PR: https://github.com/tjhgibbs-git/item-generator-fabric/pull/3
- Implementation commit before this handoff: `fe5620d`.
- The user initially discussed Paper support, then explicitly narrowed the scope to
  **Fabric servers only**. No Paper work is wanted in this PR.
- Use the same Fabric JAR on clients and dedicated servers. Server installation is optional.
- With compatible server hooks, enable whole-world spawner discovery, verified bulk edits
  and server datapack tag scanning.
- Without compatible hooks (vanilla, Fabric without Stacksmith, incompatible hooks),
  **grey out the controls that require them**. Keep ordinary editors, creative item
  delivery, vanilla-command actions and nearby tools usable.
- Capability support must come from the server handshake, not just a server brand or the
  presence of a channel. Disconnecting/changing servers must clear support and remote data.
- Use an operator account for the main test matrix; the user explicitly accepts that
  requirement and does not want repeated explanations about permissions.
- Server-only installation exposes hooks for modded clients. It adds no custom screens or
  player commands to an unmodified client.
- Remote chest discovery remains limited to the client's loaded chunks. Whole-world
  chest hooks were not added; do not silently expand this follow-up into that feature.

The next session should verify the implementation on a real local client/server, fix defects
found within this scope, update the PR and report the actual results. The automated tests below
are evidence, not proof that multiplayer UI/network behavior works in game.

## What has actually been verified

The implementation was built in a managed Linux environment with Java 25:

- `./gradlew build` passed on the implementation commit.
- 116 core tests passed, including five new protocol tests.
- All 19 dedicated-server GameTests passed, including four new server-hook tests.
- The new tests cover fragmented Unicode messages, malformed/oversized/deep/interleaved
  messages, handshake and session reset, stale targets, unrelated-data/countdown preservation,
  authorization loss, and an index-to-write RPC round trip.
- `git diff --check` passed; the implementation was committed and pushed to PR #3.

**Not verified:** joining a separate server with a real client, the actual Fabric payload
registration/handshake across that connection, visible disabled states/tooltips, remote travel,
multiple real clients, cancellation during real chunk loads, Prism/mod compatibility, or a
successful graphical block-scraper session.

`AGENTS.md` requires driving and looking at GUI changes with block-scraper. Two launches were
attempted, including software OpenGL/context overrides. Minecraft failed before a usable screen:

- OpenGL: `Failed to create window for OpenGL context: Couldn't find matching GLX visual`.
- Vulkan: `Installed Vulkan doesn't implement the VK_KHR_surface extension`.

No screenshots demonstrating this implementation were obtained. That is an environment blocker,
not a passed UI check. The local PC's less restrictive sandbox and working graphics should allow
this verification. There is no longer any need to unblock Paper Maven domains.

## Start here on the PC

1. Read the checked-out `AGENTS.md`, this file, `docs/SERVERS.md`, and relevant existing
   `docs/HANDOFF.md` notes. Check the branch and working tree before changing anything;
   preserve unrelated local work.
2. Fetch and check out `feat/optional-server-hooks`. Inspect changes relative to `origin/26.3`;
   do not start a replacement implementation on the default branch.
3. Use the locally installed JDK 25 and run `./gradlew build` (`gradlew.bat build` on Windows).
   Pure core checks can also run with `./gradlew :core:test -PcoreOnly` on JDK 21+.
   The cloud paths `/workspace/toolchains` and `/workspace/environment` are not PC prerequisites.
4. Build the JAR from this branch: `fabric/build/libs/stacksmith-0.1.0.jar`. Do not accidentally
   use the older tracked `download/` binary or an older JAR already in the launcher's mods folder.
   Remove duplicate Stacksmith versions and confirm the installed file hash matches the build.
5. Follow `AGENTS.md` for the user's Prism instance. Its documented WSL path is
   `/mnt/c/Users/tjhgi/AppData/Roaming/PrismLauncher/instances/26.3/minecraft/mods/`;
   locate/confirm the actual instance if paths differ. Preserve the existing other mods.
6. Prepare a disposable Minecraft 26.3 dedicated Fabric server with Fabric Loader and Fabric API
   matching the project. Use a separate test world, enable commands for the test operator and
   use a free local port. Record launch arguments, versions and installed mod lists.
   Keep server properties/authentication suitable for local testing; do not commit test credentials,
   account tokens, local launcher files or generated world data.
7. Keep a sibling `../block-scraper` checkout as described in `AGENTS.md`. Use
   `:fabric:runBlockScraper` for UI inspection and screenshots. Its stock `world` command creates
   singleplayer; that alone does not test a dedicated connection. Use the real multiplayer menu
   or suitable local automation for joining the dedicated server.

## In-game verification matrix

Use screenshots, client/server logs and independent reads of actual world data. Record each
result as pass/fail/not run. Tests of server absence require a restart with that server JAR
removed, not just hiding a UI button.

| Client | Server | Required result |
| --- | --- | --- |
| Stacksmith | Vanilla 26.3 | Joins normally; World/whole-world actions/Scan datapacks disabled; ordinary editors and nearby tools work. |
| Stacksmith | Fabric + API, no Stacksmith | Same disabled behavior; no payload errors or disconnects. |
| Stacksmith | Fabric + API + branch JAR | Capabilities enable after negotiation; remote index, edits and datapack scan work. |
| Unmodified compatible client | Fabric + API + branch JAR | Joins normally; no custom GUI or unsolicited feature packets required. |
| Stacksmith | Integrated singleplayer / LAN host | Existing local world tools still work. |

Also test the user's real Prism mod combination (including Axiom and other mods listed in
`AGENTS.md`) after a minimal clean client/server passes.

### 1. Greyed-out states and connection lifecycle — highest priority

- Join each unsupported server with the client mod. Press J; check both the full tab row and
  the compact tab picker at a smaller window/GUI scale. World must look disabled, be
  unclickable and explain the missing compatible hooks on hover.
- From the spawner editor, open the copies browser. Whole world must be disabled; Nearby
  must remain selectable and usable. Check review/apply controls cannot bypass support checks.
- Open the mob's Tags screen. Scan datapacks must be disabled, while remembered/manual tags
  remain usable. Recheck after resizing/rebuilding the screen.
- Join a supported server and immediately open an editor before negotiation completes. Controls
  should enable once capabilities arrive, without requiring a reconnect. Check the tag screen too.
- Switch supported -> unsupported -> supported without restarting Minecraft, including reusing
  the same server address after restarting with/without the JAR. No old world rows, progress,
  group expansion/selection state or enabled capability state should leak to the new connection.
- Disconnect during an index, datapack scan and bulk edit. Rejoin another server; delayed
  replies/timeouts must not populate the new world's results or cancel its new operation.
- If feasible, use a temporary dev fixture exposing only some capabilities, or a wrong protocol
  version. Merely advertising the channel must not enable features; absent capabilities must
  disable their corresponding actions. Do not ship the fixture or force-enable client flags.

### 2. Whole-world discovery and travel

- Place identifiable spawners near spawn, far away in already generated terrain, and in the
  Nether/End (and an existing custom dimension if available). Save, move away so far chunks
  unload, then index from the World tab.
- Verify positions, dimensions, kinds/groups, setup counts, filters and search match reality,
  including spawners outside the client's render distance.
- Remove/change a loaded spawner after saving; Rescan must overlay current live data rather
  than retaining the older disk entry. Change an unloaded saved target and rescan again.
- Reopen the World tab and explicitly Rescan. Check the snapshot wording, changed-region
  reuse, retry behavior when rescanning within five seconds, and errors remain understandable.
- Use teleport/Edit on results in the same and another dimension. The correct location should
  load, editor data should be fresh, and return navigation should remain usable.
- Compare saved chunk inventories before/after discovery. The scan itself must not generate
  or load new terrain. Do not infer this solely from an unchanged player position.

### 3. Verified bulk editing

Prepare copies sharing the same mob pool but different delays/counts/ranges, plus an unrelated
spawner. Include named/equipped mobs and unfamiliar extra block-entity data where valid.

- World -> Edit all -> change one setting -> review -> apply. Only that setting should change
  on selected copies; each copy keeps its other settings and mob pool. Check the result by
  querying the blocks independently, then rescan and reopen.
- Change the pool/weights/light rules/gear and verify the intended pool replacement. Exercise
  both World Edit all and the older copies browser's exact/same-mob modes and settings toggle.
- Countdown is live: compare with awareness that it naturally ticks when players are nearby.
  The operation must preserve the value at write time, not restore the value from discovery.
- After discovery, change one target from another client or command. Apply the reviewed edit:
  that target must be skipped with a useful reason while unchanged targets succeed.
- Remove a target, and test a setting combination that would make min delay exceed max delay.
  Verify skip/failure counts and location/reason rows; rejected targets must not be overwritten.
- Supersede the server snapshot and try an older revision via a temporary test harness if needed.
  It must request rescan/review rather than applying against a different snapshot.
- Exercise a safely loadable, unloaded target deep inside generated terrain. Confirm the write
  persists and its temporary chunk ticket is released afterward.
- Exercise an unloaded target near unexplored terrain. It should be skipped, listed and editable
  after travelling there. Remote bulk editing must not teleport the player or generate terrain.

### 4. Cancellation, competing clients and recovery

- Start enough writes that Stop can be clicked during progress. Verify completed writes remain,
  remaining writes stop, the final UI leaves its busy state and a fresh rescan/new edit works.
- Repeat with disconnect, server stop and loss of authorization while an operation is running.
  Verify no abandoned chunk tickets, lingering global busy lock or later writes after reconnect.
- Use two real clients. Start an index/apply/scan on one and a world operation on the other;
  the second should receive a useful retry response. Once the first ends/cancels/disconnects,
  the second must be able to proceed. Private snapshots must not be mixed between users.
- Check a cancelled/expired request's later callback cannot cancel a subsequent operation.
  Pay particular attention to cancellation while an unloaded chunk is being loaded.
- Exercise framed transfers larger than one 24,000-byte body with realistic mob data. Test
  an over-limit response in disposable fixtures if practical; it should fail explicitly and
  recover without disconnecting, corrupting an edit or leaving controls permanently busy.

### 5. Datapacks and regressions

- Add a small test datapack with `tag @s add test_tag` and a selector using `tag=test_tag` in
  function files. Scan from a remote client; verify names, source hints and file counts are
  returned and remembered for that server. Check zip and folder packs if practical.
- Switch servers and confirm tag history follows the intended existing per-world/server behavior.
- Smoke-test item creation, summon/spawn eggs, spawner item placement, direct overwrite,
  nearby copy editing, previews, saved presets, import/export and chest editing/indexing.
- Check real physical typing and keyboard navigation, not just block-scraper's `type` helper.
  Check readable disabled tooltips and both compact and full navigation layouts.

## Code map and review targets

- `fabric/.../server/FabricHooks.java`: payload registration, dedicated/integrated server lifetime,
  sender identity and disconnect cleanup. Confirm real client/server registration interoperates.
- `server/WorldService.java`: per-player snapshots/revisions, global operation serialization,
  request dispatch, cancellation, tag scans and progress/final replies.
- `server/ServerIndex.java`: background saved-region reads, server-thread live overlay,
  unchanged-region reuse, partial results and conservative FULL-chunk neighbourhood.
- `server/ServerBatch.java`: direct write/re-read, stale raw-data comparisons, selected field
  preservation, chunk loading, ticket release and interruption cleanup.
- `server/WorldPayload.java` and `core/.../network/Wire.java`: bounded payload framing and parser.
- `client/ServerHooks.java`: handshake retry, connection identity, pending callback cleanup,
  progress deadlines and cancellation. Review late replies, callbacks after screen rebuilds,
  permission-loss recovery and absence/incompatibility behavior.
- `client/SpawnerIndex.java` / `SpawnerWorldBatch.java`: remote/local routing and view/progress state.
- `client/StacksmithScreen.java`, `SpawnerWorldScreen.java`, `SpawnerBatchScreen.java`,
  `SpawnerApplyScreen.java`, `SpawnerEditorScreen.java`, `TagsScreen.java`, `EntityTags.java`:
  visible capability gating and existing local paths.
- `fabric/src/gametest/.../server/ServerHooksGameTests.java` and `core/src/test/.../network/WireTest.java`:
  existing automated coverage to extend when an in-game bug is found.

These are investigation targets, not claims that specific defects were reproduced. Review
main-thread save cost and large-world responsiveness too. The server region decoder deliberately
reports unsupported/corrupt chunks as partial results; test unusual region compression/external
chunk formats against real worlds before claiming exhaustive indexing. No region files should be
rewritten directly by these hooks. The original local and new remote executors share behavior but
are separate implementations; check for accidental divergence when fixing bugs.

## Deliverables before calling verification complete

- Fix reproduced in-scope bugs on this branch; add meaningful regression coverage and rerun
  the full build after code changes. Do not represent simulated RPC tests as real network testing.
- Capture representative screenshots of disabled controls on an unsupported server and enabled
  world discovery/apply on a supported server. Keep logs/artifacts somewhere reviewable without
  committing generated worlds or sensitive launcher data.
- Update PR #3 with a concise matrix of actual tested versions/mod combinations, passed cases,
  evidence locations and outstanding failures/not-run cases. Update lasting documentation if
  observed behavior differs from the implementation's current claims.
- Confirm the same built artifact works in both client and dedicated-server installations and
  an unmodified client still joins the server.
- Remove this temporary file in a final branch commit before merge; confirm it is absent from
  the net diff against `origin/26.3`. A squash merge then excludes it. If preserving every commit
  in merge history instead, tell the user this handoff remains in earlier branch history.
- Push verification fixes/results to the existing PR and report back. Leave merging to the user.
