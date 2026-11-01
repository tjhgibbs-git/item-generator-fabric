package io.github.tjhgibbs.stacksmith.core.container;

import static org.junit.jupiter.api.Assertions.*;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;

import org.junit.jupiter.api.Test;

class ChestSpecTest {
    @Test
    void savedChestKeepsEverySlotAndUnknownDataButNoCoordinates() {
        ChestSpec c =
                new ChestSpec(
                        "trapped_chest", Nbt.compound().put("x", Nbt.i(400)).put("Lock", "secret"));
        ItemSpec sword = new ItemSpec("diamond_sword").set("custom_name", Nbt.str("Treasure"));
        c.slot(0, sword);
        c.slot(26, new ItemSpec("diamond", 64));
        ChestSpec loaded = ChestSpec.fromTag(c.toTag());
        assertEquals(sword, loaded.slot(0));
        assertEquals(64, loaded.slot(26).count());
        assertNull(loaded.slot(1));
        assertEquals("secret", loaded.data().getString("Lock", ""));
        assertFalse(loaded.data().has("x"));
        assertEquals(c.toTag(), ChestSpec.fromTag(BlockTemplate.save(c.command())).toTag());
    }

    @Test
    void conversionAndSlotWritesRejectShulkerNesting() {
        ChestSpec c = new ChestSpec();
        c.slot(3, new ItemSpec("red_shulker_box"));
        assertThrows(IllegalArgumentException.class, () -> c.block("shulker_box"));
        assertEquals("minecraft:chest", c.block());
        c.slot(3, null);
        c.block("blue_shulker_box");
        assertThrows(IllegalArgumentException.class, () -> c.slot(0, new ItemSpec("shulker_box")));
        c.slot(0, new ItemSpec("diamond", 5));
        assertEquals(5, c.slot(0).count());
    }

    @Test
    void nestedEditKeepsItemIdentityAndOtherComponents() {
        ChestSpec inside = new ChestSpec();
        inside.block("red_shulker_box");
        inside.slot(26, new ItemSpec("emerald", 12));
        ItemSpec box =
                inside.item()
                        .set("custom_name", Nbt.str("Supplies"))
                        .set("lore", Nbt.list(Nbt.str("Keep me")));
        ChestSpec edit = ChestSpec.fromItem(box);
        edit.slot(26, new ItemSpec("gold_ingot", 7));
        ItemSpec result = edit.updateItem(box);
        assertEquals(box.get("custom_name"), result.get("custom_name"));
        assertEquals(box.get("lore"), result.get("lore"));
        assertEquals(new ItemSpec("gold_ingot", 7), ChestSpec.fromItem(result).slot(26));
        assertEquals(new ItemSpec("emerald", 12), ChestSpec.fromItem(box).slot(26));
    }

    @Test
    void oversizedPayloadIsBuiltBeforeClearingExistingContents() {
        ChestSpec c = new ChestSpec();
        for (int i = 0; i < 27; i++) c.slot(i, new ItemSpec("diamond", 64));
        var commands =
                c.write(
                        "100 64 100",
                        "minecraft:chest[facing=east,type=single,waterlogged=false]",
                        true,
                        256);
        assertNotNull(commands);
        assertTrue(commands.stream().allMatch(s -> s.length() < 256));
        int clear = commands.indexOf("data remove block 100 64 100 Items");
        assertTrue(clear > 0);
        assertTrue(commands.get(clear + 1).startsWith("setblock"));
        c.slot(0, new ItemSpec("stone").set("custom_name", Nbt.str("x".repeat(300))));
        assertNull(c.write("~ ~ ~", "chest", false, 256));
    }

    @Test
    void spawnerStarsAreRelativeAndReadableByExistingModel() {
        Nbt.Compound raw =
                Nbt.compound().put("x", Nbt.i(987)).put("y", Nbt.i(60)).put("z", Nbt.i(-100));
        SpawnerSpec s = SpawnerSpec.fromTag(raw);
        String command = Commands.setSpawner(s, "~ ~ ~", Commands.Style.DEFAULT);
        assertTrue(command.startsWith("setblock ~ ~ ~ spawner"));
        assertFalse(command.contains("987"));
        assertEquals(s.toTag(), SpawnerSpec.fromTag(BlockTemplate.save(command)).toTag());
        assertThrows(
                IllegalArgumentException.class,
                () -> BlockTemplate.data(BlockTemplate.save("setblock 1 2 3 chest{}")));
    }
}
