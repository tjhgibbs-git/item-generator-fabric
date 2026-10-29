package io.github.tjhgibbs.stacksmith.core.command;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    private static SpawnerSpec zombieSpawner() {
        EntitySpec z = new EntitySpec("zombie");
        z.equipment(EntitySpec.Slot.MAINHAND, new ItemSpec("diamond_sword").set("enchantments", Nbt.compound().put("minecraft:sharpness", Nbt.i(5))));
        z.dropChance(EntitySpec.Slot.MAINHAND, 0f);
        z.flag(EntitySpec.Flag.PERSISTENT, true);
        z.data().put("CustomName", Nbt.compound().put("text", "Bob").put("color", "gold"));
        SpawnerSpec s = new SpawnerSpec(z);
        s.set(SpawnerSpec.Setting.COUNT, 2);
        return s;
    }

    @Test
    void give() {
        ItemSpec i = new ItemSpec("stick", 5).set("custom_name", new Nbt.Str("Wand"));
        assertEquals("give @s stick[custom_name=\"Wand\"] 5", Commands.give(i, Commands.Style.DEFAULT));
        assertEquals("give @p minecraft:stick[minecraft:custom_name=\"Wand\"] 5", Commands.give(i, new Commands.Style(false, "@p")));
    }

    @Test
    void spawnerSetblockRoundTripsThroughImport() throws SnbtException {
        SpawnerSpec s = zombieSpawner();
        String cmd = Commands.setSpawner(s, "~ ~ ~", Commands.Style.DEFAULT);
        assertTrue(cmd.startsWith("setblock ~ ~ ~ spawner{"), cmd);
        Importer.Result r = Importer.parse("/" + cmd);
        SpawnerSpec back = assertInstanceOf(Importer.SpawnerResult.class, r).spawner();
        assertEquals(s.toTag(), back.toTag());
        assertEquals("minecraft:diamond_sword", back.primary().equipment(EntitySpec.Slot.MAINHAND).id());
        assertEquals(0f, back.primary().dropChance(EntitySpec.Slot.MAINHAND));
    }

    @Test
    void selfRunningCommandBlockUnwraps() throws SnbtException {
        SpawnerSpec s = zombieSpawner();
        ItemSpec cb = Commands.commandBlockItem(Commands.setSpawner(s, "~ ~ ~", Commands.Style.DEFAULT), Nbt.str("Zombie spawner"));
        String give = Commands.give(cb, Commands.Style.DEFAULT);
        assertTrue(give.contains("auto:true") || give.contains("auto:1b"), give);
        SpawnerSpec back = assertInstanceOf(Importer.SpawnerResult.class, Importer.parse(give)).spawner();
        assertEquals(s.toTag(), back.toTag());
    }

    @Test
    void spawnerItemImports() throws SnbtException {
        SpawnerSpec s = zombieSpawner();
        String give = Commands.give(Commands.spawnerItem(s, null), Commands.Style.DEFAULT);
        Importer.SpawnerResult r = assertInstanceOf(Importer.SpawnerResult.class, Importer.parse(give));
        assertEquals(s.toTag(), r.spawner().toTag());
    }

    @Test
    void importsVariousForms() throws SnbtException {
        assertInstanceOf(Importer.ItemResult.class, Importer.parse("give @a[team=red, limit=2] diamond 64"));
        assertEquals(64, ((Importer.ItemResult) Importer.parse("give @a[team=red, limit=2] diamond 64")).item().count());
        assertInstanceOf(Importer.ItemResult.class, Importer.parse("diamond_sword[damage=3]"));
        assertInstanceOf(Importer.ItemResult.class, Importer.parse("{id:\"minecraft:apple\",count:2}"));
        assertInstanceOf(Importer.ItemResult.class, Importer.parse("item replace entity @s weapon.mainhand with stick[damage=1] 1"));
        Importer.EntityResult e = assertInstanceOf(Importer.EntityResult.class, Importer.parse("summon creeper ~ ~1 ~ {powered:1b}"));
        assertEquals("minecraft:creeper", e.entity().id());
        assertEquals(Nbt.b(1), e.entity().data().get("powered"));
        Importer.BlockResult b = assertInstanceOf(Importer.BlockResult.class, Importer.parse("setblock 1 2 3 chest[facing=east]{Lock:{}}"));
        assertEquals("minecraft:chest[facing=east]", b.state());
        assertThrows(SnbtException.class, () -> Importer.parse("give @s"));
    }

    @Test
    void spawnerPoolsKeepWeightsAndExtras() throws SnbtException {
        SpawnerSpec s = new SpawnerSpec(new EntitySpec("skeleton"));
        s.entries().add(new SpawnerSpec.Entry(new EntitySpec("spider"), 3));
        s.entries().getFirst().blockLight = new int[]{0, 7};
        s.extra().put("custom_field", Nbt.i(1));
        SpawnerSpec back = SpawnerSpec.fromTag(s.toBlockEntityData());
        assertEquals(2, back.entries().size());
        assertEquals(3, back.entries().get(1).weight);
        assertArrayEquals(new int[]{0, 7}, back.entries().getFirst().blockLight);
        assertEquals(s.toTag(), back.toTag());
    }
}
