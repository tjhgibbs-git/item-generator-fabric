package io.github.tjhgibbs.stacksmith.core.entity;

import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntitySpecTest {
    @Test
    void equipmentAndDropChancesStayTidy() {
        EntitySpec e = new EntitySpec("skeleton");
        e.equipment(EntitySpec.Slot.MAINHAND, new ItemSpec("bow"));
        e.dropChance(EntitySpec.Slot.MAINHAND, 1f);
        assertEquals("minecraft:bow", e.equipment(EntitySpec.Slot.MAINHAND).id());
        assertEquals(1f, e.dropChance(EntitySpec.Slot.MAINHAND));
        e.dropChance(EntitySpec.Slot.MAINHAND, EntitySpec.Slot.DEFAULT_DROP_CHANCE);
        e.equipment(EntitySpec.Slot.MAINHAND, null);
        assertTrue(e.data().isEmpty(), Snbt.write(e.data()));
    }

    @Test
    void attributesKeepOtherFields() {
        EntitySpec e = new EntitySpec("zombie");
        e.attribute("max_health", 40);
        e.attribute("minecraft:scale", 2);
        assertEquals(40, e.attribute("minecraft:max_health"));
        ((Nbt.Compound) e.data().getList("attributes").get(0)).put("modifiers", Nbt.list());
        e.attribute("max_health", Double.NaN);
        assertEquals(2, e.data().getList("attributes").size(), "kept because of its modifiers");
        e.attribute("scale", Double.NaN);
        assertTrue(Double.isNaN(e.attribute("scale")));
    }

    @Test
    void effectsAndFlagsRoundTrip() {
        EntitySpec e = new EntitySpec("creeper").flag(EntitySpec.Flag.NO_AI, true);
        e.effects(List.of(new EntitySpec.Effect("speed", 2, -1, false, false, true)));
        EntitySpec back = EntitySpec.fromTag(e.toTag());
        assertEquals(e, back);
        assertTrue(back.flag(EntitySpec.Flag.NO_AI));
        assertEquals("minecraft:speed", back.effects().getFirst().id());
        assertEquals(2, back.effects().getFirst().amplifier());
        assertFalse(back.effects().getFirst().particles());
        e.flag(EntitySpec.Flag.NO_AI, false);
        assertFalse(e.data().has("NoAI"));
    }

    @Test
    void passengers() {
        EntitySpec spider = new EntitySpec("spider");
        spider.passengers(List.of(new EntitySpec("skeleton")));
        assertEquals("minecraft:skeleton", spider.passengers().getFirst().id());
        assertTrue(Snbt.write(spider.toTag()).contains("Passengers:[{id:\"minecraft:skeleton\"}]"));
    }
}
