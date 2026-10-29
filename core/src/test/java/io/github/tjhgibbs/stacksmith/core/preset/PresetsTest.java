package io.github.tjhgibbs.stacksmith.core.preset;

import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class PresetsTest {
    @TempDir
    Path root;

    @Test
    void saveListLoadDelete() throws Exception {
        Presets p = new Presets(root);
        assertTrue(p.list(Presets.Kind.ITEM).isEmpty());
        ItemSpec sword = new ItemSpec("diamond_sword").set("custom_name", TextMarkup.toComponent("&6Excalibur", true));
        String name = p.save(Presets.Kind.ITEM, "Excalibur", sword.toTag());
        assertEquals("Excalibur", name);
        p.save(Presets.Kind.MOB, "Bob", new Nbt.Compound().put("id", "minecraft:zombie"));
        var items = p.list(Presets.Kind.ITEM);
        assertEquals(1, items.size());
        assertEquals(sword, ItemSpec.fromTag(items.getFirst().data()));
        assertEquals(1, p.list(Presets.Kind.MOB).size());
        assertTrue(p.exists(Presets.Kind.ITEM, "Excalibur"));
        p.delete(Presets.Kind.ITEM, "Excalibur");
        assertTrue(p.list(Presets.Kind.ITEM).isEmpty());
    }

    @Test
    void newestFirstAndBrokenFilesSkipped() throws Exception {
        Presets p = new Presets(root);
        p.save(Presets.Kind.ITEM, "old", new Nbt.Compound().put("id", "minecraft:stick"));
        p.save(Presets.Kind.ITEM, "new", new Nbt.Compound().put("id", "minecraft:apple"));
        Files.setLastModifiedTime(root.resolve("items/old.snbt"), FileTime.fromMillis(1000));
        Files.writeString(root.resolve("items/broken.snbt"), "{not snbt");
        var list = p.list(Presets.Kind.ITEM);
        assertEquals(2, list.size());
        assertEquals("new", list.get(0).name());
        assertEquals("old", list.get(1).name());
    }

    @Test
    void namesBecomeSafeFileNames() {
        assertEquals("My sword (v2).snbt", Presets.fileName("My sword (v2)"));
        assertEquals("a_b_c.snbt", Presets.fileName("a/b\\c"));
        assertEquals("__x.snbt", Presets.fileName("../x"));
        assertEquals("unnamed.snbt", Presets.fileName("  "));
        assertEquals("Épée.snbt", Presets.fileName("Épée"));
    }
}
