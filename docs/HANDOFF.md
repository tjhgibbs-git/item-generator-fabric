# Handoff notes

Written 2026-10-29 from a cloud session with JDK 21 and Maven Central, but **no access to
maven.fabricmc.net or Mojang's servers**, so no Minecraft client. Core was built and tested
locally. The Fabric module was compiled and GameTested by GitHub Actions only (all 6 server
GameTests pass on 26.3, so the bridges, save formats and schema defaults are right); nobody
has opened its screens yet.

The session had no Minecraft jars, so it read 26.3 signatures through `.github/api-probe.sh`:
list classes in `.github/api-probe.txt`, push, and the CI log prints their `javap`. Use the
same trick for any API question when offline.

## 1. Next step: run it in a client with block-scraper

This needs an environment that can reach maven.fabricmc.net and Mojang (a real machine, or a
cloud environment with those hosts allowed). Then, with block-scraper checked out next to
this repo:

```sh
B=../block-scraper/block-scraper
$B start --project-dir . --task :fabric:runBlockScraper
$B world --creative
$B open class:io.github.tjhgibbs.stacksmith.client.ItemEditorScreen
$B shot shots/item.png          # and READ it
$B open class:io.github.tjhgibbs.stacksmith.client.SpawnerEditorScreen
$B shot shots/spawner.png
```

Check, in this order:

1. **Both screens open** and fit at 854x480 (scale 2) and 1920x1080. The three columns are
   sized with `Math.clamp` in `leftW()/rightW()`; at the smallest sizes the middle column
   may be too narrow.
2. **Item tab**: type in the id box (dropdown appears, Tab accepts), add components from the
   left list (click; search box filters), edit each card kind: Custom Name (`&6&lHi`), Lore
   (add/remove lines), Enchantments (add, change id, level), Rarity, Dyed Color (swatches),
   Max Stack Size (−/+), Unbreakable, Tool (outline card). Watch the tooltip preview update.
3. **Outline view** (Form/Outline toggle): typing, Enter auto-indent, Tab, Ctrl+Z/Y,
   Ctrl+/, Alt+↑/↓, Ctrl+D, selection by drag and shift+arrows, copy/paste, the error line
   turning red on bad input, mouse wheel scrolling in long text.
4. **Get item** in creative (singleplayer, then a server where you're not op, if possible),
   **Copy /give**, **Run** (greyed out with a reason when the command is over 256 chars).
5. **Import…**: held item, pasted MCStacker `/give`, pasted spawner `/setblock`, a
   self-running command block's `/give`, and "Spawner I'm looking at".
6. **Spawner tab**: mob type, name, equipment (Pick from inventory, ✎ nested editor and
   **Use item** back), drop chances, switches, stats, effects (level, seconds or ∞, particles),
   All data, the pool (+ Add mob, weights,
   ✕), the **live mob preview** wearing its gear, **Get spawner**, **Get command block**
   (place it: it must become the spawner at once), **Place where I look**.

## 2. Things written blind that may need fixing

- `InventoryScreen.extractEntityInInventoryFollowsMouse` arguments (scale and y offset) in
  `SpawnerEditorScreen.extractPanels`: sized from the mob's bounding box; check big (ghast,
  ender dragon) and small (silverfish) mobs. Some entity types may fail to build client-side;
  they show "No preview".
- `ScrollPane` clips with `enableScissor` and hides widgets for clicks outside it
  (`guardClick`). Check that clicking a card half scrolled out of view does nothing outside
  the pane, and that the dropdown of a `SuggestBox` inside a pane draws above everything
  (`g.nextStratum()` in `SuggestBox.extractOverlay`).
- `CodeArea` key handling uses `KeyEvent.hasControlDown()` for Ctrl shortcuts; on macOS this
  should be Cmd. `isCopy/isPaste/isCut/isSelectAll` come from the game and should already
  be right.
- Text input: `CharacterEvent.isAllowedChatCharacter()` filters what the code area accepts;
  check that `§`, `#`, `{` and non-ASCII letters type.
- `Delivery.giveCreative` writes the local inventory and sends the creative slot packet
  (menu slot = 36 + hotbar index). Check the item appears and survives a relog on a server.
- Rebuilding a screen (adding a lore line, removing a component) re-creates widgets, so a
  focused text box loses focus. Fine for buttons; if it bothers when typing, rebuild only the
  changed card.

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

## 4. Backlog

- Dedicated forms for more components: attribute modifiers (attribute / amount / operation
  / slot rows), potion contents and effects, tool rules, food and consumable, equippable,
  written book pages, firework explosions (colour pickers), banner patterns (with preview),
  player head profile, container contents (a 27-slot grid that opens nested item editors).
- Effects on items: potion contents / consumable effects forms (the mob editor has one to reuse).
- Entity-specific fields in the spawner tab, generated from a second schema
  (`entities.outline`): baby, villager profession/level, slime size, creeper fuse/power,
  horse colours, armour stand poses (with a preview), etc. Passengers editor (stack mobs).
- Spawner custom spawn rules (light limits) and equipment loot tables in the UI; core
  already reads and writes light limits (`SpawnerSpec.Entry.blockLight/skyLight`).
- Trial spawners; other block entities (chests with loot, signs, banners, beehives).
- Saved presets ("My items"/"My mobs") under `config/stacksmith/`, with a library screen.
- `/stacksmith` client command (open, import from argument).
- Undo across rebuilds (snapshot `ItemSpec` per change).
- Text editor: autocomplete of component field names from codecs.
