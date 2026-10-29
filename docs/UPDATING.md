# Moving Stacksmith to a new Minecraft version

Stacksmith is split so that most of it never sees Minecraft, and the parts that do are small
and named. A version bump should touch the files below, in this order, and nothing else.

## 1. Versions (`gradle.properties`)

`minecraft_version`, `loader_version`, `loom_version`, `fabric_api_version`, and the
`"minecraft"` range in `fabric/src/main/resources/fabric.mod.json`. Take them from
<https://fabricmc.net/develop>. Copy whatever tjhgibbs-git/tree-generator-fabric uses if it
was moved first; both mods build the same way.

## 2. Compile and read the errors

```
./gradlew build
```

Compile errors can only come from these places, because nothing else imports `net.minecraft`:

| Where | What it does | Usually breaks when |
|---|---|---|
| `fabric/src/main/.../mod/Mc*.java` | the bridges: NBT, items, entities, text, registries | codecs or registries are renamed |
| `fabric/src/client/.../ui/*` | widgets drawn by hand (code editor, chips, scroll pane, dropdown) | GUI drawing or input events change (they did in 1.21.6 and 1.21.9) |
| `fabric/src/client/.../*Screen.java` | the screens | same, plus `Screen` hooks |
| `fabric/src/client/.../Delivery.java` | creative inventory, commands, clipboard | `MultiPlayerGameMode` / connection API |

No access to Mojang's or Fabric's servers where you are? Add class names to
`.github/api-probe.txt` and push: the CI job prints their signatures (`javap`) in its log.

## 3. Run the GameTests

`./gradlew build` runs them. They check what can't be checked at compile time:

- `itemsRoundTripThroughTheGame`: item ↔ game stack ↔ command argument.
- `gameSnbtReadsInCore`: core's SNBT parser reads what the game prints, and vice versa.
- `everySchemaDefaultIsValid`: every component in the schema exists and its starting value is
  accepted. **This is the one that lists what to fix in the schema after an update.**
- `mobDataLoadsIntoARealMob`: entity save keys (`equipment`, `drop_chances`, `attributes`,
  `active_effects`, `CustomName`, flags) still mean what `EntitySpec.Keys`/`Flag` say.
- `spawnerFromSetblockReadsBack`: spawner keys and the generated `/setblock` still work.

## 4. Fix data, not code

- **Components** (renamed, new, changed format): `core/src/main/resources/stacksmith/schema/components.outline`.
  New components appear in the editor without any change (edited as an outline); add an entry
  only to give them a form, group, help line or starting value.
- **Entity save keys**: the constants in `core/.../entity/EntitySpec.java` (`Keys`, `Flag`, `Slot`).
- **Spawner keys**: `core/.../entity/SpawnerSpec.java` (`Setting`, `BLOCK_ENTITY_ID`).
- **SNBT syntax** (if Mojang changes it again, as in 1.21.5): `core/.../nbt/Snbt.java`, with
  tests in `SnbtTest`.

`./gradlew :core:test -PcoreOnly` checks all of core without Minecraft.

## 5. Look at it

With block-scraper next to the repo: `:fabric:runBlockScraper`, open
`class:io.github.tjhgibbs.stacksmith.client.ItemEditorScreen` and
`class:io.github.tjhgibbs.stacksmith.client.SpawnerEditorScreen`, and screenshot them at
854x480 and 1920x1080 (see the README).
