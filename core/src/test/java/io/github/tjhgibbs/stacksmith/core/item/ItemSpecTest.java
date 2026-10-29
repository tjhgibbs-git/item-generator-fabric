package io.github.tjhgibbs.stacksmith.core.item;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemSpecTest {
    @Test
    void argumentRoundTrip() throws SnbtException {
        String arg = "minecraft:diamond_sword[minecraft:custom_name={text:\"Hi\",italic:false},minecraft:enchantments={\"minecraft:sharpness\":5},!minecraft:tool]";
        ItemSpec s = ItemSpec.parseArgument(arg);
        assertEquals("minecraft:diamond_sword", s.id());
        assertTrue(s.removed().contains("minecraft:tool"));
        assertEquals(Nbt.i(5), ((Nbt.Compound) s.get("enchantments")).get("minecraft:sharpness"));
        assertEquals(s, ItemSpec.parseArgument(s.toArgument(true)));
        assertEquals(s, ItemSpec.parseArgument(s.toArgument(false)));
        assertTrue(s.toArgument(true).startsWith("diamond_sword[custom_name="), s.toArgument(true));
    }

    @Test
    void briefIdsExpand() throws SnbtException {
        ItemSpec s = ItemSpec.parseArgument("stick[ unbreakable = {} , !food ]");
        assertEquals("minecraft:stick", s.id());
        assertEquals(new Nbt.Compound(), s.get("minecraft:unbreakable"));
        assertTrue(s.removed().contains("minecraft:food"));
    }

    @Test
    void tagRoundTrip() throws SnbtException {
        ItemSpec s = new ItemSpec("bow", 3).set("damage", Nbt.i(5)).remove("enchantable");
        Nbt.Compound tag = s.toTag();
        assertEquals("{id:\"minecraft:bow\",count:3,components:{\"minecraft:damage\":5,\"!minecraft:enchantable\":{}}}", Snbt.write(tag));
        assertEquals(s, ItemSpec.fromTag(tag));
    }

    @Test
    void setUnremoves() {
        ItemSpec s = new ItemSpec("bread").remove("food").set("food", Nbt.compound());
        assertTrue(s.removed().isEmpty());
        assertFalse(s.isPlain());
        s.unset("food");
        assertTrue(s.isPlain());
    }

    @Test
    void badArgumentsPointAtTheProblem() {
        SnbtException e = assertThrows(SnbtException.class, () -> ItemSpec.parseArgument("stick[damage 3]"));
        assertEquals(13, e.offset);
        assertThrows(SnbtException.class, () -> ItemSpec.parseArgument("stick[damage=]"));
        assertThrows(SnbtException.class, () -> ItemSpec.parseArgument("stick extra"));
    }
}
