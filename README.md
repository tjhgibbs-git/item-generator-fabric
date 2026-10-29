# Stacksmith: an in-game item, mob and spawner editor for Fabric (Minecraft 26.3)

Everything [MCStacker](https://mcstacker.net) does for items and spawners, as a screen inside
the game: edit any data component with a form (or as one readable outline), import items you
already have, see the real tooltip and the mob as it will look, and get the result as an item
in your inventory, a `/give`, a `/setblock`, or a command block that builds the spawner by
itself the moment you place it.

Client-side only. It generates items and commands; the server needs nothing installed.

## Status

| Part | State |
|---|---|
| `core/`: SNBT, outline format, item/mob/spawner model, commands, import, text codes, text editor model, component schema | JUnit tests green (`./gradlew :core:test -PcoreOnly`) |
| `fabric/`: bridges, screens, delivery, GameTests | Written 2026-10-29 in a session without Minecraft; compiled by CI only. **Not yet run in a client.** See [docs/HANDOFF.md](docs/HANDOFF.md). |

## Using it

Press **J** (rebindable). Two tabs: **Item** and **Spawner**. **Import…** (top right) brings in
the item you're holding, the spawner you're looking at, or any pasted `/give`, `/setblock`,
`/summon`, `/item replace`, bare item (`diamond_sword[...]`) or saved item data.

### Item tab

- **Left:** the item (type to search, or **Pick…** to browse every item or take one from your
  inventory with all its data), the count, and every component you can add, grouped and
  searchable. Hover for what each does. Items' built-in components are marked `•`;
  shift-click one to *remove* it from the item (`!name`).
- **Middle:** one card per component. Forms where they help:
  - names and lore: one line of text with `&` codes (`&6&lGold &rplain`, `&#ff8800`), no
    JSON. Italic is turned off for you (use `&o` to want it);
  - enchantments: name + level rows with search; numbers, switches, choices, colours with
    dye swatches, registry ids with a dropdown;
  - everything else: the **outline** editor (below).
  - **Outline** (top right of the column) shows the whole item as one outline instead.
- **Right:** the item and its real tooltip, live, plus anything the game would reject.
- **Bottom:** the `/give` with its length (chat takes 256), **Get item**, **Copy /give**,
  **Run**, **Copy data**.

### The outline format

Instead of `{text:"Excalibur",color:"gold",italic:false}`:

```yaml
custom_name:
  text: Excalibur
  color: gold
  italic: false
lore:
  - text: Forged in the deep
    color: gray
  - A plain line
enchantments:
  minecraft:sharpness: 5
attribute_modifiers:
  - type: attack_damage
    amount: 12.0
    operation: add_value
tags: [legendary, bound]
```

- Indentation instead of braces, `- ` for list items, no quotes unless needed (`"5"` is
  text, `5` a number, `5b` a byte, `2.5f` a float, `true`/`false` flags).
- Inline SNBT still works anywhere: `pos: [I; 0, 64, 0]`, `a: {x: 1}`.
- `#` comments. The editor has line numbers, colours, the error line marked, undo/redo,
  auto-indent, Tab/Shift+Tab, Ctrl+/ to comment, Alt+↑/↓ to move lines, Ctrl+D to duplicate.

### Spawner tab

- **Left:** timing and ranges (with the defaults explained), and the spawn pool: one mob, or
  several with weights.
- **Middle:** the selected mob: type, name (with `&` codes), equipment (pick or fully edit
  the item in each slot, drop chance per slot), behaviour switches (never despawn, no AI,
  silent, glowing...), health and common attributes, and **All data** as an outline for
  everything else (effects, passengers, `IsBaby`, `powered`...).
- **Right:** the mob, rendered wearing and holding its gear (it follows the mouse), and:
  - **Get spawner**: a spawner item that places this spawner;
  - **Get command block**: a command block that turns itself into the spawner when placed
    (always active, no redstone);
  - **Place where I look**: runs the `/setblock` (if short enough for chat);
  - **Copy /setblock**, **Copy /summon**.

### Servers

- **Get item** puts the item in your inventory the way the creative menu does, so it works on
  any server where you're in creative, no op needed, any size of data.
- **Run** and **Place** are offered only when the server lets you use the command (servers
  only tell clients about the commands they may run).
- **Spawners and command blocks need op where they're placed**: vanilla servers throw away
  the data of those blocks when a non-op places them. The spawner tab says so when it applies.

## Building

```
./gradlew build                         # everything (needs maven.fabricmc.net + Mojang)
./gradlew :core:test -PcoreOnly         # core only, any JDK 21+, no Minecraft
```

## Layout

```
core/     pure Java, no Minecraft
  nbt/       Nbt tree, SNBT reader/writer (1.21.5+ syntax)
  outline/   the outline format, syntax highlighter
  item/      ItemSpec (id, count, component patch) <-> /give argument and saved form
  entity/    EntitySpec (equipment, attributes, effects, flags), SpawnerSpec
  command/   Commands (give, setblock, summon, spawner and command-block items), Importer
  text/      TextMarkup (&-codes <-> text components)
  edit/      TextModel (the code editor's text, caret, undo)
  schema/    ComponentSchema, loaded from resources/stacksmith/schema/components.outline
fabric/   the mod
  main/      Mc* bridges (the only code touching game classes outside the GUI)
  client/    screens, widgets, Delivery
  gametest/  checks core against the real game
```

Moving to a new Minecraft version: [docs/UPDATING.md](docs/UPDATING.md).

## Working on the GUI with block-scraper

With a [block-scraper](https://github.com/tjhgibbs-git/block-scraper) checkout next to this
repo, the build gains `:fabric:runBlockScraper`:

```sh
B=../block-scraper/block-scraper
$B start --project-dir . --task :fabric:runBlockScraper
$B world --creative
$B open class:io.github.tjhgibbs.stacksmith.client.ItemEditorScreen
$B shot shots/item.png
$B open class:io.github.tjhgibbs.stacksmith.client.SpawnerEditorScreen
$B shot shots/spawner.png
```
