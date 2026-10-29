package io.github.tjhgibbs.stacksmith.core.entity;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;

/**
 * A mob spawner's block entity data: timing, ranges, and what it spawns. One entry spawns that
 * mob every time; several are a weighted pool ({@code SpawnPotentials}), with the first used as
 * the initial {@code SpawnData} (what the cage shows before the first spawn).
 *
 * <p>Unknown fields read from an imported spawner are kept in {@link #extra()} and written back.
 */
public final class SpawnerSpec {
    /** The block entity type id written into {@code block_entity_data}. */
    public static final String BLOCK_ENTITY_ID = "minecraft:mob_spawner";
    public static final String BLOCK_ID = "minecraft:spawner";

    /** Timing and range fields with vanilla's defaults (all saved as shorts). */
    public enum Setting {
        DELAY("Delay", 20, 0, 32767),
        MIN_DELAY("MinSpawnDelay", 200, 0, 32767),
        MAX_DELAY("MaxSpawnDelay", 800, 1, 32767),
        COUNT("SpawnCount", 4, 0, 1000),
        MAX_NEARBY("MaxNearbyEntities", 6, 0, 1000),
        PLAYER_RANGE("RequiredPlayerRange", 16, 0, 1000),
        SPAWN_RANGE("SpawnRange", 4, 0, 1000);

        public final String key;
        public final int defaultValue, min, max;

        Setting(String key, int defaultValue, int min, int max) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }
    }

    /** One spawnable mob with its weight and optional light limits. */
    public static final class Entry {
        public EntitySpec entity;
        public int weight;
        /** Light limits 0..15 inclusive, or null for the mob's normal rules. */
        public int[] blockLight, skyLight;

        public Entry(EntitySpec entity, int weight) {
            this.entity = entity;
            this.weight = Math.max(1, weight);
        }

        Nbt.Compound spawnData() {
            Nbt.Compound c = new Nbt.Compound().put("entity", entity.toTag());
            if (blockLight != null || skyLight != null) {
                Nbt.Compound rules = c.child("custom_spawn_rules");
                if (blockLight != null) {
                    rules.put("block_light_limit", range(blockLight));
                }
                if (skyLight != null) {
                    rules.put("sky_light_limit", range(skyLight));
                }
            }
            return c;
        }

        private static Nbt range(int[] r) {
            return r[0] == r[1] ? Nbt.i(r[0]) : Nbt.list(Nbt.i(r[0]), Nbt.i(r[1]));
        }

        static int[] readRange(Nbt n) {
            if (n instanceof Nbt.Num v) {
                return new int[]{(int) v.asLong(), (int) v.asLong()};
            }
            if (n instanceof Nbt.List l && l.size() == 2 && l.get(0) instanceof Nbt.Num a && l.get(1) instanceof Nbt.Num b) {
                return new int[]{(int) a.asLong(), (int) b.asLong()};
            }
            if (n instanceof Nbt.Compound c) {
                return new int[]{(int) c.getLong("min_inclusive", 0), (int) c.getLong("max_inclusive", 15)};
            }
            return null;
        }

        static Entry fromSpawnData(Nbt.Compound data, int weight) {
            Nbt.Compound e = data.getCompound("entity");
            Entry entry = new Entry(e != null && e.has("id") ? EntitySpec.fromTag(e) : new EntitySpec("pig"), weight);
            Nbt.Compound rules = data.getCompound("custom_spawn_rules");
            if (rules != null) {
                entry.blockLight = readRange(rules.get("block_light_limit"));
                entry.skyLight = readRange(rules.get("sky_light_limit"));
            }
            return entry;
        }
    }

    private final int[] settings = new int[Setting.values().length];
    private final List<Entry> entries = new ArrayList<>();
    private final Nbt.Compound extra = new Nbt.Compound();

    public SpawnerSpec() {
        for (Setting s : Setting.values()) {
            settings[s.ordinal()] = s.defaultValue;
        }
    }

    public SpawnerSpec(EntitySpec entity) {
        this();
        entries.add(new Entry(entity, 1));
    }

    public int get(Setting s) {
        return settings[s.ordinal()];
    }

    public SpawnerSpec set(Setting s, int value) {
        settings[s.ordinal()] = Math.max(s.min, Math.min(s.max, value));
        return this;
    }

    /** The spawn pool (mutable). Empty means an empty spawner. */
    public List<Entry> entries() {
        return entries;
    }

    /** Fields this editor does not model, kept as they were. */
    public Nbt.Compound extra() {
        return extra;
    }

    /** The first mob in the pool, or null. */
    public EntitySpec primary() {
        return entries.isEmpty() ? null : entries.getFirst().entity;
    }

    /** Problems that would make the spawner misbehave, for the editor to show. Empty when fine. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (get(Setting.MIN_DELAY) > get(Setting.MAX_DELAY)) {
            out.add("Min delay is above max delay; the game will reject this spawner.");
        }
        if (entries.isEmpty()) {
            out.add("Nothing to spawn: add a mob.");
        }
        return out;
    }

    /** The block entity data, without {@code id} (as {@code /setblock spawner{...}} wants). */
    public Nbt.Compound toTag() {
        Nbt.Compound c = new Nbt.Compound();
        extra.entries().forEach(e -> c.put(e.getKey(), e.getValue().copy()));
        // Defaults are left out (the game uses the same ones when a key is missing), which keeps
        // simple spawners short enough for chat.
        for (Setting s : Setting.values()) {
            if (get(s) != s.defaultValue) {
                c.put(s.key, Nbt.s(get(s)));
            }
        }
        if (!entries.isEmpty()) {
            // The pool is written even for one mob: a spawner placed from an item otherwise
            // keeps an empty pool, and every route should leave the same data.
            c.put("SpawnData", entries.getFirst().spawnData());
            Nbt.List pool = new Nbt.List();
            for (Entry e : entries) {
                pool.add(new Nbt.Compound().put("weight", Nbt.i(e.weight)).put("data", e.spawnData()));
            }
            c.put("SpawnPotentials", pool);
        }
        return c;
    }

    /** Block entity data with its type id, as an item's {@code block_entity_data} needs. */
    public Nbt.Compound toBlockEntityData() {
        Nbt.Compound c = new Nbt.Compound().put("id", BLOCK_ENTITY_ID);
        toTag().entries().forEach(e -> c.put(e.getKey(), e.getValue()));
        return c;
    }

    public static SpawnerSpec fromTag(Nbt.Compound tag) {
        SpawnerSpec s = new SpawnerSpec();
        Nbt.Compound rest = tag.copy();
        rest.remove("id");
        for (Setting st : Setting.values()) {
            if (rest.remove(st.key) instanceof Nbt.Num n) {
                s.set(st, (int) n.asLong());
            }
        }
        Nbt.List pool = rest.getList("SpawnPotentials");
        Nbt data = rest.remove("SpawnData");
        rest.remove("SpawnPotentials");
        if (pool != null && !pool.isEmpty()) {
            for (Nbt n : pool.items()) {
                if (n instanceof Nbt.Compound p && p.getCompound("data") instanceof Nbt.Compound d) {
                    s.entries.add(Entry.fromSpawnData(d, (int) p.getLong("weight", 1)));
                }
            }
        } else if (data instanceof Nbt.Compound d && d.getCompound("entity") != null) {
            s.entries.add(Entry.fromSpawnData(d, 1));
        }
        // Runtime state the game re-creates; not worth keeping in an editor.
        rest.remove("x");
        rest.remove("y");
        rest.remove("z");
        rest.remove("components");
        rest.entries().forEach(e -> s.extra.put(e.getKey(), e.getValue()));
        return s;
    }
}
