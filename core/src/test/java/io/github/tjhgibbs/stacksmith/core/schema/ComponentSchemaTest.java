package io.github.tjhgibbs.stacksmith.core.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComponentSchemaTest {
    @Test
    void bundledSchemaLoadsWithDefaults() {
        ComponentSchema s = ComponentSchema.bundled();
        assertTrue(s.all().size() > 50, "components: " + s.all().size());
        for (ComponentSchema.Info i : s.all()) {
            assertNotNull(i.defaultValue(), i.id() + " has no default");
            assertTrue(s.groups().containsKey(i.group()), i.id() + " group " + i.group());
        }
        assertEquals(ComponentSchema.Editor.TEXT_LIST, s.info("lore").editor());
        assertEquals(List.of("common", "uncommon", "rare", "epic"), s.info("minecraft:rarity").values());
        assertEquals(99, s.info("max_stack_size").max());
        assertFalse(s.info("item_name").upright());
    }

    @Test
    void unknownComponentsAreOutlines() {
        ComponentSchema s = ComponentSchema.bundled();
        assertEquals(ComponentSchema.Editor.OUTLINE, s.info("mod:thing").editor());
        List<ComponentSchema.Info> arranged = s.arrange(List.of("mod:thing", "minecraft:lore", "minecraft:custom_name", "minecraft:custom_data"));
        assertEquals(List.of("minecraft:custom_name", "minecraft:lore", "minecraft:custom_data", "mod:thing"),
                arranged.stream().map(ComponentSchema.Info::id).toList());
    }
}
