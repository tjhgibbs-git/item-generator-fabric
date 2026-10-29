package io.github.tjhgibbs.stacksmith.core.schema;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntitySchemaTest {
    private final EntitySchema schema = EntitySchema.bundled();

    private EntitySchema.Field field(String name) {
        return schema.all().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void bundledFileLoadsAndSetsExpand() {
        assertTrue(schema.all().size() > 30);
        List<String> zombie = schema.fieldsFor("zombie").stream().map(EntitySchema.Field::name).toList();
        assertEquals(List.of("is_baby", "can_break_doors"), zombie);
        List<String> cow = schema.fieldsFor("minecraft:cow").stream().map(EntitySchema.Field::name).toList();
        assertTrue(cow.containsAll(List.of("age", "age_locked", "cow_variant")), cow.toString());
        assertTrue(schema.fieldsFor("stone").isEmpty());
    }

    @Test
    void boolWritesItsOnValueAndRemoves() {
        EntitySpec cow = new EntitySpec("cow");
        EntitySchema.Field age = field("age");
        assertFalse(age.isOn(cow));
        age.set(cow, age.on());
        assertEquals(Nbt.i(-24000), cow.data().get("Age"));
        assertTrue(age.isOn(cow));
        age.set(cow, null);
        assertFalse(cow.data().has("Age"));
    }

    @Test
    void nestedKeysMakeAndPruneCompounds() {
        EntitySpec v = new EntitySpec("villager");
        EntitySchema.Field level = field("villager_level"), prof = field("profession");
        level.set(v, level.number(3));
        prof.set(v, Nbt.str("minecraft:farmer"));
        assertEquals(Nbt.compound().put("level", Nbt.i(3)).put("profession", "minecraft:farmer"), v.data().get("VillagerData"));
        level.set(v, null);
        assertEquals(Nbt.str("minecraft:farmer"), prof.get(v));
        prof.set(v, null);
        assertFalse(v.data().has("VillagerData"), v.data().toString());
        prof.set(v, null); // removing what isn't there doesn't create anything
        assertFalse(v.data().has("VillagerData"));
    }

    @Test
    void choicesMapLabelsToSavedValues() {
        EntitySpec r = new EntitySpec("rabbit");
        EntitySchema.Field type = field("rabbit_type");
        type.set(r, type.values().get("killer_bunny"));
        assertEquals(Nbt.i(99), r.data().get("RabbitType"));
        assertEquals("killer_bunny", type.choiceLabel(r));
        EntitySchema.Field wool = field("sheep_color");
        assertEquals(16, wool.values().size());
        assertEquals(Nbt.b(14), wool.values().get("red"));
        EntitySchema.Field fuse = field("fuse");
        assertEquals(Nbt.s(40), fuse.number(40));
    }
}
