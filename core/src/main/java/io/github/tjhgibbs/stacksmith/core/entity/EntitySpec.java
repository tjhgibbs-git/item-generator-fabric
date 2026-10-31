package io.github.tjhgibbs.stacksmith.core.entity;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An entity being edited: its type id and its saved data, with typed helpers for what the mob
 * editor exposes (equipment, drop chances, attributes, effects, flags, passengers). Anything
 * else stays in {@link #data()} and is edited as an outline, so no field is ever lost.
 *
 * <p>Field names are the game's save format ({@link Keys}); a version that renames one only
 * needs that constant changed. The mod's GameTests load these into real entities to check.
 */
public final class EntitySpec {
    /** Save-format key names, as of 26.3. */
    public static final class Keys {
        public static final String ID = "id";
        public static final String EQUIPMENT = "equipment";
        public static final String DROP_CHANCES = "drop_chances";
        public static final String ATTRIBUTES = "attributes";
        public static final String ATTRIBUTE_ID = "id";
        public static final String ATTRIBUTE_BASE = "base";
        public static final String EFFECTS = "active_effects";
        public static final String CUSTOM_NAME = "CustomName";
        public static final String PASSENGERS = "Passengers";
        public static final String HEALTH = "Health";
        /** Scoreboard tags, what {@code @e[tag=...]} selects on. */
        public static final String TAGS = "Tags";

        private Keys() {
        }
    }

    /** Equipment slots by their save-format name. */
    public enum Slot {
        MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, BODY, SADDLE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The chance a mob drops what it was given unless told otherwise (vanilla's default). */
        public static final float DEFAULT_DROP_CHANCE = 0.085f;
    }

    /** On/off fields shown as toggles in the mob editor. */
    public enum Flag {
        NO_AI("NoAI"),
        SILENT("Silent"),
        GLOWING("Glowing"),
        INVULNERABLE("Invulnerable"),
        PERSISTENT("PersistenceRequired"),
        NAME_VISIBLE("CustomNameVisible"),
        NO_GRAVITY("NoGravity"),
        PICKS_UP_LOOT("CanPickUpLoot"),
        LEFT_HANDED("LeftHanded");

        public final String key;

        Flag(String key) {
            this.key = key;
        }
    }

    private String id;
    private final Nbt.Compound data;

    public EntitySpec(String id) {
        this(id, new Nbt.Compound());
    }

    public EntitySpec(String id, Nbt.Compound data) {
        this.id = Ids.full(id);
        this.data = data;
        data.remove(Keys.ID);
    }

    public String id() {
        return id;
    }

    public EntitySpec id(String id) {
        this.id = Ids.full(id);
        return this;
    }

    /** Everything but the id (mutable). */
    public Nbt.Compound data() {
        return data;
    }

    public EntitySpec copy() {
        return new EntitySpec(id, data.copy());
    }

    /** {@code {id:"minecraft:zombie", ...}}, the form spawners, summon and entity_data use. */
    public Nbt.Compound toTag() {
        Nbt.Compound c = new Nbt.Compound().put(Keys.ID, id);
        data.entries().forEach(e -> c.put(e.getKey(), e.getValue().copy()));
        return c;
    }

    public static EntitySpec fromTag(Nbt.Compound tag) {
        String id = tag.getString(Keys.ID, null);
        if (id == null) {
            throw new IllegalArgumentException("entity has no id");
        }
        return new EntitySpec(id, tag.copy());
    }

    // ---- equipment ---------------------------------------------------------------------------

    /** What the entity wears or holds in {@code slot}, or null. */
    public ItemSpec equipment(Slot slot) {
        Nbt.Compound eq = data.getCompound(Keys.EQUIPMENT);
        if (eq != null && eq.getCompound(slot.key()) instanceof Nbt.Compound item && item.has("id")) {
            return ItemSpec.fromTag(item);
        }
        return null;
    }

    /** Puts an item in a slot; null empties it. */
    public EntitySpec equipment(Slot slot, ItemSpec item) {
        if (item == null) {
            Nbt.Compound eq = data.getCompound(Keys.EQUIPMENT);
            if (eq != null) {
                eq.remove(slot.key());
                if (eq.isEmpty()) {
                    data.remove(Keys.EQUIPMENT);
                }
            }
            return this;
        }
        data.child(Keys.EQUIPMENT).put(slot.key(), item.toTag());
        return this;
    }

    public float dropChance(Slot slot) {
        Nbt.Compound dc = data.getCompound(Keys.DROP_CHANCES);
        return dc != null && dc.has(slot.key()) ? (float) dc.getDouble(slot.key(), 0) : Slot.DEFAULT_DROP_CHANCE;
    }

    /** Sets a drop chance; the default ({@link Slot#DEFAULT_DROP_CHANCE}) is left out of the data. */
    public EntitySpec dropChance(Slot slot, float chance) {
        if (Math.abs(chance - Slot.DEFAULT_DROP_CHANCE) < 1e-6) {
            Nbt.Compound dc = data.getCompound(Keys.DROP_CHANCES);
            if (dc != null) {
                dc.remove(slot.key());
                if (dc.isEmpty()) {
                    data.remove(Keys.DROP_CHANCES);
                }
            }
        } else {
            data.child(Keys.DROP_CHANCES).put(slot.key(), Nbt.f(chance));
        }
        return this;
    }

    // ---- flags, health ----------------------------------------------------------------------

    public boolean flag(Flag f) {
        return data.getBool(f.key, false);
    }

    /** Sets a flag; false removes it (every flag defaults to false). */
    public EntitySpec flag(Flag f, boolean on) {
        data.putOrRemove(f.key, on ? Nbt.bool(true) : null);
        return this;
    }

    /** Health, or NaN when not set (the mob spawns with its max health). */
    public float health() {
        return data.has(Keys.HEALTH) ? (float) data.getDouble(Keys.HEALTH, 0) : Float.NaN;
    }

    public EntitySpec health(float h) {
        data.putOrRemove(Keys.HEALTH, Float.isNaN(h) ? null : Nbt.f(h));
        return this;
    }

    // ---- attributes ---------------------------------------------------------------------------

    /** Base value set for an attribute, or NaN if the entity's default is used. */
    public double attribute(String attributeId) {
        Nbt.Compound a = findAttribute(Ids.full(attributeId));
        return a == null ? Double.NaN : a.getDouble(Keys.ATTRIBUTE_BASE, Double.NaN);
    }

    /** Sets an attribute's base value; NaN removes the override. Keeps any modifiers listed. */
    public EntitySpec attribute(String attributeId, double base) {
        String id = Ids.full(attributeId);
        Nbt.Compound a = findAttribute(id);
        if (Double.isNaN(base)) {
            if (a != null) {
                a.remove(Keys.ATTRIBUTE_BASE);
                if (a.size() == 1) {
                    data.getList(Keys.ATTRIBUTES).items().remove(a);
                }
                if (data.getList(Keys.ATTRIBUTES).isEmpty()) {
                    data.remove(Keys.ATTRIBUTES);
                }
            }
            return this;
        }
        if (a == null) {
            a = new Nbt.Compound().put(Keys.ATTRIBUTE_ID, id);
            data.childList(Keys.ATTRIBUTES).add(a);
        }
        a.put(Keys.ATTRIBUTE_BASE, Nbt.d(base));
        return this;
    }

    private Nbt.Compound findAttribute(String id) {
        Nbt.List list = data.getList(Keys.ATTRIBUTES);
        if (list == null) {
            return null;
        }
        for (Nbt n : list.items()) {
            if (n instanceof Nbt.Compound c && id.equals(Ids.full(c.getString(Keys.ATTRIBUTE_ID, "")))) {
                return c;
            }
        }
        return null;
    }

    // ---- effects -----------------------------------------------------------------------------

    /** A status effect. {@code duration} is in ticks; -1 is infinite. */
    public record Effect(String id, int amplifier, int duration, boolean ambient, boolean particles, boolean icon) {
        public Nbt.Compound toTag() {
            Nbt.Compound c = new Nbt.Compound().put("id", Ids.full(id));
            if (amplifier != 0) {
                c.put("amplifier", Nbt.b(Math.max(-128, Math.min(127, amplifier))));
            }
            c.put("duration", Nbt.i(duration));
            if (ambient) {
                c.put("ambient", Nbt.bool(true));
            }
            if (!particles) {
                c.put("show_particles", Nbt.bool(false));
            }
            if (!icon) {
                c.put("show_icon", Nbt.bool(false));
            }
            return c;
        }

        public static Effect fromTag(Nbt.Compound c) {
            return new Effect(c.getString("id", "minecraft:speed"), (int) c.getLong("amplifier", 0), (int) c.getLong("duration", -1),
                    c.getBool("ambient", false), c.getBool("show_particles", true), c.getBool("show_icon", true));
        }
    }

    public List<Effect> effects() {
        List<Effect> out = new ArrayList<>();
        Nbt.List list = data.getList(Keys.EFFECTS);
        if (list != null) {
            for (Nbt n : list.items()) {
                if (n instanceof Nbt.Compound c) {
                    out.add(Effect.fromTag(c));
                }
            }
        }
        return out;
    }

    public EntitySpec effects(List<Effect> effects) {
        if (effects.isEmpty()) {
            data.remove(Keys.EFFECTS);
        } else {
            Nbt.List l = new Nbt.List();
            effects.forEach(e -> l.add(e.toTag()));
            data.put(Keys.EFFECTS, l);
        }
        return this;
    }

    // ---- tags ----------------------------------------------------------------------------------

    /** The mob's tags (for {@code @e[tag=...]}), in order. */
    public List<String> tags() {
        List<String> out = new ArrayList<>();
        Nbt.List list = data.getList(Keys.TAGS);
        if (list != null) {
            for (Nbt n : list.items()) {
                if (n instanceof Nbt.Str s && !out.contains(s.value())) {
                    out.add(s.value());
                }
            }
        }
        return out;
    }

    public EntitySpec tags(List<String> tags) {
        if (tags.isEmpty()) {
            data.remove(Keys.TAGS);
        } else {
            Nbt.List l = new Nbt.List();
            tags.stream().distinct().forEach(t -> l.add(Nbt.str(t)));
            data.put(Keys.TAGS, l);
        }
        return this;
    }

    // ---- passengers ----------------------------------------------------------------------------

    public List<EntitySpec> passengers() {
        List<EntitySpec> out = new ArrayList<>();
        Nbt.List list = data.getList(Keys.PASSENGERS);
        if (list != null) {
            for (Nbt n : list.items()) {
                if (n instanceof Nbt.Compound c && c.has(Keys.ID)) {
                    out.add(fromTag(c));
                }
            }
        }
        return out;
    }

    public EntitySpec passengers(List<EntitySpec> riders) {
        if (riders.isEmpty()) {
            data.remove(Keys.PASSENGERS);
        } else {
            Nbt.List l = new Nbt.List();
            riders.forEach(r -> l.add(r.toTag()));
            data.put(Keys.PASSENGERS, l);
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntitySpec e && e.id.equals(id) && e.data.equals(data);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + data.hashCode();
    }

    @Override
    public String toString() {
        return Ids.brief(id) + data;
    }
}
