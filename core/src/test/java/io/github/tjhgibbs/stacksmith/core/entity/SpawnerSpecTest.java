package io.github.tjhgibbs.stacksmith.core.entity;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnerSpecTest {
    private static SpawnerSpec zombies() {
        EntitySpec z = new EntitySpec("zombie");
        z.data().put(EntitySpec.Keys.HEALTH, Nbt.f(40));
        return new SpawnerSpec(z).set(SpawnerSpec.Setting.COUNT, 2);
    }

    @Test
    void identicalIgnoresTheSpawnCountdown() {
        SpawnerSpec a = zombies();
        SpawnerSpec b = zombies().set(SpawnerSpec.Setting.DELAY, 317);
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(zombies().set(SpawnerSpec.Setting.PLAYER_RANGE, 5)));
        assertFalse(a.sameAs(new SpawnerSpec(new EntitySpec("zombie")).set(SpawnerSpec.Setting.COUNT, 2)));
    }

    @Test
    void sameMobsIgnoresSettingsAndWeights() {
        SpawnerSpec a = zombies();
        SpawnerSpec b = zombies().set(SpawnerSpec.Setting.PLAYER_RANGE, 5);
        b.entries().getFirst().weight = 3;
        b.entries().getFirst().classicLight();
        assertTrue(a.sameMobs(b));
        assertFalse(a.sameMobs(new SpawnerSpec(new EntitySpec("zombie"))));
        assertFalse(a.sameMobs(new SpawnerSpec()));
    }

    @Test
    void withPoolOfKeepsSettings() {
        SpawnerSpec tuned = zombies().set(SpawnerSpec.Setting.PLAYER_RANGE, 5);
        SpawnerSpec edited = new SpawnerSpec(new EntitySpec("skeleton")).set(SpawnerSpec.Setting.PLAYER_RANGE, 20);
        SpawnerSpec out = tuned.withPoolOf(edited);
        assertEquals(5, out.get(SpawnerSpec.Setting.PLAYER_RANGE));
        assertEquals(2, out.get(SpawnerSpec.Setting.COUNT));
        assertEquals("minecraft:skeleton", out.primary().id());
        out.primary().data().put("Glowing", Nbt.b(1));
        assertFalse(edited.primary().data().has("Glowing"), "the copy shares no mobs with the source");
        assertEquals(1, tuned.entries().size());
        assertEquals("minecraft:zombie", tuned.primary().id());
    }

    @Test
    void emptySpawnRulesMeanAnyLight() {
        Nbt.Compound data = new Nbt.Compound().put("entity", new EntitySpec("zombie").toTag());
        data.child("custom_spawn_rules");
        SpawnerSpec s = SpawnerSpec.fromTag(new Nbt.Compound().put("SpawnData", data));
        assertArrayEquals(new int[]{0, 15}, s.entries().getFirst().blockLight);
        assertArrayEquals(new int[]{0, 15}, s.entries().getFirst().skyLight);
    }
}
