package io.github.tjhgibbs.stacksmith.core.preset;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpawnerLogTest {
    @TempDir
    Path root;

    private static SpawnerSpec spawner(String id) {
        return new SpawnerSpec(new EntitySpec(id));
    }

    @Test
    void newestFirstAndKeptAcrossRestarts() {
        Path file = root.resolve("log.snbt");
        SpawnerLog log = new SpawnerLog(file);
        log.record(spawner("pig"), "Saved", "1 2 3", "World", 1000);
        log.record(spawner("zombie"), "Spawner item", null, null, 2000);
        var back = new SpawnerLog(file).entries();
        assertEquals(2, back.size());
        assertEquals("minecraft:zombie", back.get(0).spawner().primary().id());
        assertNull(back.get(0).pos());
        assertEquals("1 2 3", back.get(1).pos());
        assertEquals("World", back.get(1).world());
        assertEquals(1000, back.get(1).time());
    }

    @Test
    void usingTheSameSpawnerAgainMovesItUp() {
        SpawnerLog log = new SpawnerLog(root.resolve("log.snbt"));
        log.record(spawner("pig"), "Saved", "1 2 3", null, 1000);
        log.record(spawner("zombie"), "Saved", null, null, 2000);
        // Only the spawn countdown differs: still the same spawner.
        log.record(spawner("pig").set(SpawnerSpec.Setting.DELAY, 99), "Applied to 3", "4 5 6", null, 3000);
        var e = log.entries();
        assertEquals(2, e.size());
        assertEquals("minecraft:pig", e.get(0).spawner().primary().id());
        assertEquals(2, e.get(0).uses());
        assertEquals("4 5 6", e.get(0).pos());
        // Other settings: a different spawner.
        log.record(spawner("pig").set(SpawnerSpec.Setting.COUNT, 9), "Saved", null, null, 4000);
        assertEquals(3, log.entries().size());
    }

    @Test
    void oldestDroppedPastTheLimit() {
        SpawnerLog log = new SpawnerLog(root.resolve("log.snbt"));
        for (int i = 0; i < SpawnerLog.MAX + 5; i++) {
            log.record(spawner("pig").set(SpawnerSpec.Setting.COUNT, i), "Saved", null, null, i);
        }
        assertEquals(SpawnerLog.MAX, log.entries().size());
        assertEquals(SpawnerLog.MAX + 4, log.entries().getFirst().spawner().get(SpawnerSpec.Setting.COUNT));
    }

    @Test
    void brokenFileStartsEmpty() throws Exception {
        Path file = root.resolve("log.snbt");
        Files.writeString(file, "{spawners:[{data:");
        assertTrue(new SpawnerLog(file).entries().isEmpty());
    }

    @Test
    void titlesAndSearch() {
        EntitySpec bob = new EntitySpec("zombie");
        bob.data().put(EntitySpec.Keys.CUSTOM_NAME, TextMarkup.toComponent("&cBob", true));
        SpawnerSpec s = new SpawnerSpec(bob);
        s.entries().add(new SpawnerSpec.Entry(new EntitySpec("cave_spider").passengers(java.util.List.of(new EntitySpec("skeleton"))), 2));
        assertEquals("Bob, Cave Spider", SpawnerLog.title(s));
        s.entries().add(new SpawnerSpec.Entry(new EntitySpec("witch"), 1));
        s.entries().add(new SpawnerSpec.Entry(new EntitySpec("creeper"), 1));
        assertEquals("Bob, Cave Spider +2", SpawnerLog.title(s));
        assertEquals("Empty", SpawnerLog.title(new SpawnerSpec()));

        String text = SpawnerLog.searchText(s, "12 64 -30");
        assertTrue(SpawnerLog.matches(text, "bob"));
        assertTrue(SpawnerLog.matches(text, "zombie"));
        assertTrue(SpawnerLog.matches(text, "cave_spider"));
        assertTrue(SpawnerLog.matches(text, "SKEL witch"), "riders, any case, every word");
        assertTrue(SpawnerLog.matches(text, "64"));
        assertTrue(SpawnerLog.matches(text, "  "));
        assertFalse(SpawnerLog.matches(text, "bob pig"));
    }

    @Test
    void agoReadsNaturally() {
        long now = 100L * 86400_000;
        assertEquals("just now", SpawnerLog.ago(now - 5_000, now));
        assertEquals("5 min ago", SpawnerLog.ago(now - 300_000, now));
        assertEquals("3 h ago", SpawnerLog.ago(now - 3 * 3600_000, now));
        assertEquals("yesterday", SpawnerLog.ago(now - 30 * 3600_000L, now));
        assertEquals("4 days ago", SpawnerLog.ago(now - 4 * 86400_000L, now));
        assertEquals("3 weeks ago", SpawnerLog.ago(now - 21 * 86400_000L, now));
    }

    @Test
    void freePresetNames() throws Exception {
        Presets p = new Presets(root);
        assertEquals("Zombie spawner", p.freeName(Presets.Kind.SPAWNER, "Zombie spawner"));
        p.save(Presets.Kind.SPAWNER, "Zombie spawner", spawner("zombie").toTag());
        p.save(Presets.Kind.SPAWNER, "Zombie spawner (2)", spawner("zombie").toTag());
        assertEquals("Zombie spawner (3)", p.freeName(Presets.Kind.SPAWNER, "Zombie spawner"));
    }

    @Test
    void nicknamesRenameStarredSpawners() throws Exception {
        Presets p = new Presets(root);
        p.save(Presets.Kind.SPAWNER, "Zombie spawner", spawner("zombie").toTag());
        p.save(Presets.Kind.SPAWNER, "Pig spawner", spawner("pig").toTag());
        var before = p.list(Presets.Kind.SPAWNER).stream().filter(x -> x.name().equals("Zombie spawner")).findFirst().orElseThrow();
        assertEquals("XP farm_ west", p.rename(Presets.Kind.SPAWNER, "Zombie spawner", "XP farm: west"), "unsafe characters made safe");
        assertFalse(p.exists(Presets.Kind.SPAWNER, "Zombie spawner"));
        var after = p.list(Presets.Kind.SPAWNER).stream().filter(x -> x.name().equals("XP farm_ west")).findFirst().orElseThrow();
        assertEquals("minecraft:zombie", io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec.fromTag(after.data()).primary().id());
        assertEquals(before.saved(), after.saved(), "keeps its place in the list");
        // Taken by another: refused, nothing lost.
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> p.rename(Presets.Kind.SPAWNER, "XP farm_ west", "Pig spawner"));
        assertEquals(2, p.list(Presets.Kind.SPAWNER).size());
        // Case only, and unchanged.
        assertEquals("pig SPAWNER", p.rename(Presets.Kind.SPAWNER, "Pig spawner", "pig SPAWNER"));
        assertEquals("pig SPAWNER", p.rename(Presets.Kind.SPAWNER, "pig SPAWNER", "pig SPAWNER"));
        assertEquals(java.util.Set.of("XP farm_ west", "pig SPAWNER"),
                p.list(Presets.Kind.SPAWNER).stream().map(Presets.Preset::name).collect(java.util.stream.Collectors.toSet()));
    }
}
