# Handoff notes

Last updated 2026-10-29, from a local WSL machine with a 26.3 dev client (block-scraper) and
JetBrains Runtime 25 (hot reload of new methods/fields; new static fields still need a client
restart).

## 1. State

Everything below has been used in a live client, with results checked on the server
(`/data get`, inventory contents), at 1920x1080 and at 854x480 (GUI scale 2):

- **Spawner-first flow**: J while looking at a spawner opens it in the Spawner tab, tied to
  that block (`Session.spawnerPos`); J again on the same spawner keeps unsaved edits. **Save to
  this spawner** overwrites it. Unlinked, **Place where I look** replaces a looked-at spawner or
  places against the face, then ties the tab to it. The header's "Edit spawner I'm looking at"
  and Import's "Spawner I'm looking at" link too. **+ Add mob** opens the mob editor first.
- **Stepwise save** (`Commands.setSpawnerInSteps`): over 256 characters, the spawner is built
  in `storage stacksmith:tmp` piece by piece, the block is set to air and a fresh spawner, and
  the data merged on in one command (so nothing of the old spawner survives). Each step
  prints a feedback line in chat (a two-mob pool is ~11 lines).
- Outputs: Get item, Get spawn egg, Summon here, Get spawner, Get command block (spawner tab:
  becomes the spawner; mob tab: summons the mob on top and stays), all checked in a world.
- **Mob-specific fields** (`entities.outline`, `EntitySchema`): keys read from the 26.3 class
  constants. The `everyMobFieldLoadsIntoTheGame` GameTest checks all 121 field/mob pairs.
  Changing mob type drops the old type's fields.
- **Saved presets** (`Presets`, `PresetsScreen`): `config/stacksmith/{items,mobs,spawners}/*.snbt`.
  Save/Replace/Load/delete (click ✕ twice). Loading a spawner keeps the tab's block link, so
  "Saved… → Load → Save to this spawner" puts a saved spawner into the block you looked at.
- Tests: core JUnit (all green) and 8 server GameTests (`./gradlew :fabric:runGameTest`).

- Later the same day: spawners start empty and default to the classic light rule (Light
  levels card); potion contents form and skeleton tipped arrows; "Only name & lore";
  Simple item view; Seconds/Ticks toggle; more mob stats and Reach (weapon `attack_range`,
  GameTest-checked); Riders stack with ▲ ▼ focus (the editor keeps the root and writes the
  focused rider's copy back on every change); horse Tame/Temper.
- **Open issue from the owner:** in their Prism instance (with Axiom, 14erEdit, Lattice, Sylvan)
  they couldn't type into Name/Lore. Not reproducible in the dev client, no errors in their
  log; waiting on whether the box takes focus and whether it works without Axiom.

Not checked yet: a dedicated server where you're not op (the non-op warnings), macOS (Cmd vs
Ctrl in the code area), and very long single strings (a name over ~200 characters can't be
sent from chat at all; the button says to use Get command block).

## 2. Known rough edges

- The stepwise save floods chat with "Modified storage" lines. Could set
  `sendCommandFeedback` off around it, but that is a world setting; left alone.
- Preview sizing is a heuristic (`MobPreview`): the elder guardian has a hand-tuned offset;
  the ender dragon is small but recognisable.
- Card titles truncate oddly at the very smallest width (e.g. "nat set").
- Switching mob type with a field shared by both types (e.g. `Age`) keeps it, on purpose.

## 3. Design notes

- **Everything version-specific is data or one small class.** See
  [UPDATING.md](UPDATING.md). The component schema
  (`core/src/main/resources/stacksmith/schema/components.outline`) only drives forms; the
  list of components comes from the game's registry, so new components work as outlines.
- **NBT crosses into the game as SNBT text** (`McNbt`), so no tag class API is used.
- **Item validity comes from the game**: `ItemStack.CODEC` (data) and `ItemParser` (the
  `/give` argument), both run on every edit (debounced) and shown in the preview column.
- **Delivery without op**: the creative slot packet, like pick-block. Commands are only
  offered when the server's command tree includes them for this player.
- **Self-running command block**: `command_block[block_entity_data={id:"minecraft:command_block",
  Command:"setblock ~ ~ ~ spawner{...}",auto:true}]`. When placed by an op it runs once
  immediately and replaces itself with the spawner.

## 4. Decisions from the owner (2026-10-29)

- "Item tags" means item data: components, including `custom_data`. Not datapack tags.
- The owner is usually an operator on the servers they play, so spawners, command blocks and
  `/setblock` are the main outputs. Don't spend effort on non-op workarounds.
- Keep the name Stacksmith, the J key and the dark flat panel style.
- After the in-game check, build in this order: **more item forms**, then **mob-specific
  fields**, then **saved presets**. Other blocks (trial spawners, chests...) come later.

## 5. Scope

The owner has called the feature set done (2026-10-29): items, mobs and spawners as they are
now. Further ideas (villager trade forms, armour stand poses, other block entities, more item
forms) were considered and dropped as bloat; anything not covered by a form is still editable
as an outline (All data on mobs, the outline card or Outline view on items).
