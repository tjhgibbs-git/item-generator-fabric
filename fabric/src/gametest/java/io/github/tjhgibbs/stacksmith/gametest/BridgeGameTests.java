package io.github.tjhgibbs.stacksmith.gametest;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.schema.ComponentSchema;
import io.github.tjhgibbs.stacksmith.core.schema.EntitySchema;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import io.github.tjhgibbs.stacksmith.mod.McEntities;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks core's model against the real game: SNBT both ways, items through the game's codec
 * and command parser, every schema default, mob data loading into a real mob, and a spawner
 * placed by the generated /setblock reading back the same.
 */
public class BridgeGameTests {
    private static final String EMPTY_64 = "stacksmith-gametest:empty64";

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void chestSlotsAndNestedShulkersSurviveCommandsAndItemCodecs(GameTestHelper h) {
        var reg = h.getLevel().registryAccess();
        var nested = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec();
        nested.block("blue_shulker_box");
        nested.slot(26, new ItemSpec("emerald", 12));
        var chest = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec();
        chest.slot(0, richItem());
        chest.slot(13, nested.item().set("custom_name", TextMarkup.toComponent("Supplies", true)));
        chest.slot(26, new ItemSpec("diamond", 64));
        var asItem = McItems.toStack(chest.item(), reg);
        if (!asItem.ok()) { h.fail("container item rejected: " + asItem.error()); return; }
        var backItem = io.github.tjhgibbs.stacksmith.core.container.ChestSpec.fromItem(McItems.fromStack(asItem.stack(), reg));
        if (!chest.slot(13).equals(backItem.slot(13)) || !chest.slot(26).equals(backItem.slot(26))) {
            h.fail("item codec changed container contents"); return;
        }
        BlockPos pos = h.absolutePos(new BlockPos(5, 2, 5));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var server = h.getLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "setblock " + at + " chest{Items:[{Slot:1b,id:'minecraft:dirt',count:64}]}");
        for (String command : chest.write(at, "trapped_chest[facing=east]", true, Commands.CHAT_LIMIT)) {
            server.getCommands().performPrefixedCommand(source, command);
        }
        var saved = McNbt.fromGame(h.getLevel().getBlockEntity(pos).saveWithoutMetadata(reg));
        var back = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec("trapped_chest", saved);
        if (back.slot(1) != null || !chest.slot(0).equals(back.slot(0)) || !chest.slot(13).equals(back.slot(13)) || !chest.slot(26).equals(back.slot(26))) {
            h.fail("commands changed chest slots: " + saved); return;
        }
        server.getCommands().performPrefixedCommand(source, "data remove block " + at + " Items");
        var box = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec();
        box.block("red_shulker_box"); box.slot(26, richItem());
        for (String command : box.write(at, "red_shulker_box", true, Commands.CHAT_LIMIT)) server.getCommands().performPrefixedCommand(source, command);
        var boxBack = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec("red_shulker_box", McNbt.fromGame(h.getLevel().getBlockEntity(pos).saveWithoutMetadata(reg)));
        if (!richItem().equals(boxBack.slot(26))) { h.fail("shulker conversion lost slot 27"); return; }
        h.succeed();
    }

    private static ItemSpec richItem() {
        return new ItemSpec("diamond_sword")
                .set("custom_name", TextMarkup.toComponent("&6&lExcalibur", true))
                .set("lore", Nbt.list(TextMarkup.toComponent("&7Forged in the deep", true), TextMarkup.toComponent("second", true)))
                .set("enchantments", Nbt.compound().put("minecraft:sharpness", Nbt.i(5)).put("minecraft:unbreaking", Nbt.i(3)))
                .set("attribute_modifiers", Nbt.list(Nbt.compound().put("type", "minecraft:attack_damage").put("id", "stacksmith:test")
                        .put("amount", Nbt.d(12)).put("operation", "add_value").put("slot", "mainhand")))
                .set("custom_data", Nbt.compound().put("tags", Nbt.list(Nbt.str("a"))).put("n", Nbt.b(5)))
                .remove("minecraft:tool");
    }

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void itemsRoundTripThroughTheGame(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        ItemSpec spec = richItem();
        McItems.Result r = McItems.toStack(spec, reg);
        if (!r.ok()) {
            h.fail("codec rejected the item: " + r.error());
            return;
        }
        ItemSpec back = McItems.fromStack(r.stack(), reg);
        if (!back.equals(spec)) {
            h.fail("round trip changed the item:\n  " + spec.toTag() + "\n  " + back.toTag());
            return;
        }
        McItems.Problem p = McItems.checkArgument(spec.toArgument(true), reg);
        if (p != null) {
            h.fail("ItemParser rejected " + spec.toArgument(true) + ": " + p.message());
            return;
        }
        p = McItems.checkArgument(spec.toArgument(false), reg);
        if (p != null) {
            h.fail("ItemParser rejected the long form: " + p.message());
            return;
        }
        h.succeed();
    }

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void gameSnbtReadsInCore(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        ItemStack stack = McItems.toStack(richItem(), reg).stack();
        var tag = ItemStack.CODEC.encodeStart(reg.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack).getOrThrow();
        Nbt mine = McNbt.fromGame(tag);
        try {
            if (!McNbt.toGame(mine).equals(tag)) {
                h.fail("SNBT round trip differs:\n  game " + tag + "\n  core " + Snbt.write(mine));
                return;
            }
        } catch (Exception e) {
            h.fail("game could not read core's SNBT: " + e.getMessage() + "\n  " + Snbt.write(mine));
            return;
        }
        h.succeed();
    }

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void everySchemaDefaultIsValid(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        List<String> known = McItems.componentIds();
        List<String> bad = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (ComponentSchema.Info info : ComponentSchema.bundled().all()) {
            if (!known.contains(info.id())) {
                unknown.add(info.id());
                continue;
            }
            String firstError = null;
            boolean ok = false;
            for (String base : List.of("minecraft:stick", "minecraft:diamond_sword", "minecraft:player_head", "minecraft:written_book")) {
                McItems.Result r = McItems.toStack(new ItemSpec(base).set(info.id(), info.defaultValue().copy()), reg);
                if (r.ok()) {
                    ok = true;
                    break;
                }
                if (firstError == null) {
                    firstError = r.error();
                }
            }
            if (!ok) {
                bad.add(info.id() + " = " + Snbt.write(info.defaultValue()) + ": " + firstError);
            }
        }
        if (!bad.isEmpty() || !unknown.isEmpty()) {
            h.fail("schema problems:\n  invalid defaults:\n    " + String.join("\n    ", bad) + "\n  not in this game:\n    " + String.join("\n    ", unknown));
            return;
        }
        h.succeed();
    }

    private static EntitySpec zombie() {
        EntitySpec z = new EntitySpec("zombie");
        z.equipment(EntitySpec.Slot.MAINHAND, new ItemSpec("diamond_sword"));
        z.equipment(EntitySpec.Slot.HEAD, new ItemSpec("golden_helmet"));
        z.dropChance(EntitySpec.Slot.MAINHAND, 0f);
        z.flag(EntitySpec.Flag.NO_AI, true);
        z.flag(EntitySpec.Flag.PERSISTENT, true);
        z.attribute("max_health", 40);
        z.health(40);
        z.data().put(EntitySpec.Keys.CUSTOM_NAME, TextMarkup.toComponent("&cBob", false));
        z.effects(List.of(new EntitySpec.Effect("minecraft:speed", 1, -1, false, false, true)));
        return z;
    }

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void mobDataLoadsIntoARealMob(GameTestHelper h) {
        EntitySpec spec = zombie();
        Entity e = McEntities.create(spec, h.getLevel());
        if (!(e instanceof Mob mob)) {
            h.fail("no mob from " + Snbt.write(spec.toTag()));
            return;
        }
        List<String> wrong = new ArrayList<>();
        if (!mob.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD)) {
            wrong.add("mainhand " + mob.getItemBySlot(EquipmentSlot.MAINHAND));
        }
        if (!mob.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET)) {
            wrong.add("head " + mob.getItemBySlot(EquipmentSlot.HEAD));
        }
        if (!mob.isNoAi()) {
            wrong.add("NoAI not applied");
        }
        if (!mob.isPersistenceRequired()) {
            wrong.add("PersistenceRequired not applied");
        }
        if (mob.getAttributeBaseValue(Attributes.MAX_HEALTH) != 40) {
            wrong.add("max_health " + mob.getAttributeBaseValue(Attributes.MAX_HEALTH));
        }
        if (mob.getCustomName() == null || !mob.getCustomName().getString().equals("Bob")) {
            wrong.add("name " + mob.getCustomName());
        }
        if (mob.getActiveEffects().isEmpty()) {
            wrong.add("no effects");
        }
        if (!wrong.isEmpty()) {
            h.fail("mob data not applied: " + wrong + "\n  sent " + Snbt.write(spec.toTag()));
            return;
        }
        h.succeed();
    }

    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void spawnerFromSetblockReadsBack(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        SpawnerSpec spawner = new SpawnerSpec(zombie());
        spawner.set(SpawnerSpec.Setting.COUNT, 2).set(SpawnerSpec.Setting.MIN_DELAY, 100);
        spawner.entries().add(new SpawnerSpec.Entry(new EntitySpec("skeleton"), 3));
        BlockPos pos = h.absolutePos(new BlockPos(5, 2, 5));
        String cmd = Commands.setSpawner(spawner, pos.getX() + " " + pos.getY() + " " + pos.getZ(), Commands.Style.DEFAULT);
        var server = h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        BlockEntity be = h.getLevel().getBlockEntity(pos);
        if (be == null) {
            h.fail("no spawner placed by: " + cmd);
            return;
        }
        Nbt.Compound saved = McNbt.fromGame(be.saveWithoutMetadata(reg));
        SpawnerSpec back = SpawnerSpec.fromTag(saved);
        if (back.entries().size() != 2 || back.get(SpawnerSpec.Setting.COUNT) != 2 || back.get(SpawnerSpec.Setting.MIN_DELAY) != 100
                || back.entries().get(1).weight != 3
                || back.primary().equipment(EntitySpec.Slot.MAINHAND) == null) {
            h.fail("spawner read back differently:\n  sent  " + cmd + "\n  saved " + Snbt.write(saved));
            return;
        }
        // The self-running command block item must be a valid stack too.
        McItems.Result cb = McItems.toStack(Commands.commandBlockItem(cmd, null), reg);
        if (!cb.ok()) {
            h.fail("command block item rejected: " + cb.error());
            return;
        }
        McItems.Result si = McItems.toStack(Commands.spawnerItem(spawner, TextMarkup.toComponent("Spawner", true)), reg);
        if (!si.ok()) {
            h.fail("spawner item rejected: " + si.error());
            return;
        }
        h.succeed();
    }

    /** A spawner too long for one chat command, saved in steps over an existing one, reads back whole. */
    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void spawnerSavedInStepsReplacesTheOldOne(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        BlockPos pos = h.absolutePos(new BlockPos(5, 2, 5));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var server = h.getLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        // The old spawner has a named, glowing skeleton that must not survive.
        server.getCommands().performPrefixedCommand(source,
                "setblock " + at + " spawner{SpawnData:{entity:{id:\"minecraft:skeleton\",CustomName:\"Old\",Glowing:1b}},SpawnRange:9s}");
        SpawnerSpec spawner = new SpawnerSpec(zombie());
        for (String id : new String[]{"creeper", "spider", "witch"}) {
            EntitySpec e = new EntitySpec(id);
            e.data().put("CustomName", TextMarkup.toComponent("&cA rather long name for the " + id, true));
            spawner.entries().add(new SpawnerSpec.Entry(e, 2));
        }
        List<String> steps = Commands.setSpawnerInSteps(spawner, at, Commands.Style.DEFAULT, Commands.CHAT_LIMIT);
        if (steps == null || steps.size() < 2) {
            h.fail("expected several steps, got " + steps);
            return;
        }
        for (String c : steps) {
            if (c.length() + 1 > Commands.CHAT_LIMIT) {
                h.fail("step too long for chat: " + c);
                return;
            }
            server.getCommands().performPrefixedCommand(source, c);
        }
        Nbt.Compound saved = McNbt.fromGame(h.getLevel().getBlockEntity(pos).saveWithoutMetadata(reg));
        SpawnerSpec back = SpawnerSpec.fromTag(saved);
        String old = Snbt.write(saved);
        if (back.entries().size() != 4 || back.get(SpawnerSpec.Setting.SPAWN_RANGE) != spawner.get(SpawnerSpec.Setting.SPAWN_RANGE)
                || old.contains("Glowing") || old.contains("\"Old\"") || !back.primary().id().equals("minecraft:zombie")) {
            h.fail("spawner saved in steps read back differently:\n  " + old);
            return;
        }
        h.succeed();
    }

    /**
     * Apply to nearby copies compares the spawner as the editor last saved it with spawners read
     * back from the world. Every shape the editor makes must still match itself after the game
     * has stored it (one command or in steps), and keep matching when saved again from what was read.
     */
    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void savedSpawnersMatchTheirCopiesInTheWorld(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        var server = h.getLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        java.util.Map<String, SpawnerSpec> shapes = new java.util.LinkedHashMap<>();
        shapes.put("plain pig, classic light", spawnerOf(new SpawnerSpec.Entry(new EntitySpec("pig"), 1).classicLight()));
        SpawnerSpec rich = spawnerOf(new SpawnerSpec.Entry(zombie(), 1).classicLight());
        rich.set(SpawnerSpec.Setting.DELAY, 5).set(SpawnerSpec.Setting.COUNT, 7).set(SpawnerSpec.Setting.PLAYER_RANGE, 40)
                .set(SpawnerSpec.Setting.MIN_DELAY, 1).set(SpawnerSpec.Setting.MAX_DELAY, 2);
        shapes.put("geared zombie, settings changed", rich);
        SpawnerSpec.Entry any = new SpawnerSpec.Entry(new EntitySpec("skeleton"), 1);
        any.blockLight = new int[]{0, 15};
        any.skyLight = new int[]{0, 15};
        shapes.put("any light", spawnerOf(any));
        SpawnerSpec.Entry dark = new SpawnerSpec.Entry(new EntitySpec("husk"), 1);
        dark.blockLight = new int[]{0, 0};
        shapes.put("block light only, single level", spawnerOf(dark));
        shapes.put("blaze, classic 11", spawnerOf(new SpawnerSpec.Entry(new EntitySpec("blaze"), 1).classicLight()));
        SpawnerSpec pool = spawnerOf(new SpawnerSpec.Entry(zombie(), 5).classicLight());
        for (String id : new String[]{"creeper", "spider", "witch"}) {
            EntitySpec e = new EntitySpec(id);
            e.data().put("CustomName", TextMarkup.toComponent("&cA rather long name for the " + id, true));
            pool.entries().add(new SpawnerSpec.Entry(e, 2));
        }
        pool.entries().get(2).blockLight = new int[]{3, 9};
        shapes.put("pool of four, saved in steps", pool);
        EntitySpec jockey = new EntitySpec("spider").passengers(List.of(new EntitySpec("skeleton")));
        shapes.put("spider jockey", spawnerOf(new SpawnerSpec.Entry(jockey, 1)));

        List<String> wrong = new ArrayList<>();
        int x = 2;
        for (var shape : shapes.entrySet()) {
            SpawnerSpec spec = shape.getValue();
            Nbt.Compound back = place(h, source, reg, spec, x);
            Nbt.Compound again = back == null ? null : place(h, source, reg, SpawnerSpec.fromTag(back), x + 1);
            x += 3;
            if (back == null || again == null) {
                wrong.add(shape.getKey() + ": not placed");
                continue;
            }
            SpawnerSpec read = SpawnerSpec.fromTag(back);
            if (!read.sameAs(spec) || !read.sameMobs(spec)) {
                wrong.add(shape.getKey() + ":\n    saved " + Snbt.write(spec.toTag()) + "\n    read  " + Snbt.write(read.toTag()));
            } else if (!SpawnerSpec.fromTag(again).sameAs(read)) {
                wrong.add(shape.getKey() + ": changed when saved again from what was read");
            }
            // Same mobs, other settings: found by Same mob, not by Identical.
            SpawnerSpec tuned = spec.copy().set(SpawnerSpec.Setting.SPAWN_RANGE, 9);
            if (tuned.sameAs(read) || !tuned.sameMobs(read)) {
                wrong.add(shape.getKey() + ": a copy with another spawn range matched wrongly");
            }
        }
        if (!wrong.isEmpty()) {
            h.fail("saved spawners don't match their copies:\n  " + String.join("\n  ", wrong));
            return;
        }
        h.succeed();
    }

    private static SpawnerSpec spawnerOf(SpawnerSpec.Entry entry) {
        SpawnerSpec s = new SpawnerSpec();
        s.entries().add(entry);
        return s;
    }

    /** Writes {@code spec} at column {@code x} the way the editor saves, and reads the block back. */
    private static Nbt.Compound place(GameTestHelper h, net.minecraft.commands.CommandSourceStack source, HolderLookup.Provider reg,
                                      SpawnerSpec spec, int x) {
        BlockPos pos = h.absolutePos(new BlockPos(x, 2, 5));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        List<String> steps = Commands.setSpawnerInSteps(spec, at, Commands.Style.DEFAULT, Commands.CHAT_LIMIT);
        if (steps == null) {
            return null;
        }
        for (String c : steps) {
            h.getLevel().getServer().getCommands().performPrefixedCommand(source, c);
        }
        BlockEntity be = h.getLevel().getBlockEntity(pos);
        return be == null ? null : McNbt.fromGame(be.saveWithoutMetadata(reg));
    }

    /**
     * Every field in entities.outline, on every mob it lists: load it into the real mob, save the
     * mob, and check the value is still there. Catches wrong keys, types and value ranges.
     */
    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void everyMobFieldLoadsIntoTheGame(GameTestHelper h) {
        HolderLookup.Provider reg = h.getLevel().registryAccess();
        EntitySchema schema = EntitySchema.bundled();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (EntitySchema.Field f : schema.all()) {
            Nbt value = sample(f, reg);
            if (value == null) {
                wrong.add(f.name() + ": no sample value (registry " + f.registry() + " empty?)");
                continue;
            }
            for (String id : f.mobs()) {
                if (!McEntities.isEntity(id)) {
                    wrong.add(f.name() + ": no entity " + id);
                    continue;
                }
                EntitySpec spec = new EntitySpec(id);
                f.set(spec, value);
                Entity e = McEntities.create(spec, h.getLevel());
                if (e == null) {
                    wrong.add(f.name() + " on " + id + ": didn't load");
                    continue;
                }
                var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, reg);
                e.saveWithoutId(out);
                EntitySpec back = new EntitySpec(id, McNbt.fromGame(out.buildResult()));
                Nbt got = f.get(back);
                boolean same = got != null && (got.equals(value)
                        || got instanceof Nbt.Num a && value instanceof Nbt.Num b && a.asDouble() == b.asDouble()
                        || got instanceof Nbt.Str a && value instanceof Nbt.Str b && io.github.tjhgibbs.stacksmith.core.Ids.full(a.value()).equals(io.github.tjhgibbs.stacksmith.core.Ids.full(b.value())));
                if (!same) {
                    wrong.add(f.name() + " on " + id + ": sent " + Snbt.write(value) + ", saved " + (got == null ? "nothing" : Snbt.write(got)));
                }
                e.discard();
                checked++;
            }
        }
        if (!wrong.isEmpty()) {
            h.fail(wrong.size() + " of " + checked + " mob fields wrong:\n  " + String.join("\n  ", wrong));
            return;
        }
        h.succeed();
    }

    /** A value that isn't the mob's default, so a field the game ignores shows up as missing. */
    private static Nbt sample(EntitySchema.Field f, HolderLookup.Provider reg) {
        return switch (f.editor()) {
            case BOOL -> f.on();
            case NUMBER -> f.number(Math.min(f.max(), Math.max(f.min(), 2)));
            case CHOICE, DYE -> f.values().values().stream().reduce((a, b) -> b).orElse(null);
            case ID -> {
                List<String> ids = io.github.tjhgibbs.stacksmith.mod.McRegistries.ids(f.registry(), reg);
                yield ids.isEmpty() ? null : Nbt.str(ids.get(ids.size() / 2));
            }
        };
    }

    /** A weapon's attack_range (with mob_factor) reaches further for the mob holding it: the editor's Reach. */
    @GameTest(structure = EMPTY_64, maxTicks = 20)
    public void attackRangeGivesMobsReach(GameTestHelper h) {
        EntitySpec far = new EntitySpec("zombie");
        far.equipment(EntitySpec.Slot.MAINHAND, new ItemSpec("iron_sword").set("attack_range",
                Nbt.compound().put("max_reach", Nbt.f(6)).put("mob_factor", Nbt.f(1))));
        EntitySpec plain = new EntitySpec("zombie");
        plain.equipment(EntitySpec.Slot.MAINHAND, new ItemSpec("iron_sword"));
        Mob reach = (Mob) McEntities.create(far, h.getLevel());
        Mob normal = (Mob) McEntities.create(plain, h.getLevel());
        Mob target = (Mob) McEntities.create(new EntitySpec("pig"), h.getLevel());
        if (reach == null || normal == null || target == null) {
            h.fail("couldn't build the mobs");
            return;
        }
        BlockPos p = h.absolutePos(new BlockPos(5, 2, 5));
        reach.setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        normal.setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        target.setPos(p.getX() + 5.0, p.getY(), p.getZ() + 0.5);
        boolean a = reach.isWithinMeleeAttackRange(target), b = normal.isWithinMeleeAttackRange(target);
        if (!a || b) {
            h.fail("4.5 blocks away: with attack_range " + a + " (want true), without " + b + " (want false)");
            return;
        }
        h.succeed();
    }
}
