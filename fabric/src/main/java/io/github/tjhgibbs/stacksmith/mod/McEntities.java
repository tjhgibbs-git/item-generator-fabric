package io.github.tjhgibbs.stacksmith.mod;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Entities from {@link EntitySpec}s (for previews and tests), and the list of summonable types. */
public final class McEntities {
    private McEntities() {
    }

    /**
     * Builds the entity (with passengers) in {@code level} without adding it to the world.
     * Returns null if the data can't be loaded; some entity types can't exist client-side.
     */
    public static Entity create(EntitySpec spec, Level level) {
        try {
            CompoundTag tag = McNbt.toGame(spec.toTag());
            Entity e = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), EntityProcessor.NOP);
            if (e != null) {
                // Renderers read the id (item models, animations); a preview never joins the world.
                assignIds(e);
            }
            return e;
        } catch (Exception | LinkageError e) {
            StacksmithMod.LOG.debug("No preview for {}: {}", spec.id(), e.toString());
            return null;
        }
    }

    private static int nextPreviewId = -1_000_000;

    private static void assignIds(Entity e) {
        e.setId(nextPreviewId--);
        e.getPassengers().forEach(McEntities::assignIds);
    }

    /** Ids of every entity type a command can summon, sorted. */
    public static List<String> summonableIds() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            if (t.canSummon()) {
                out.add(EntityType.getKey(t).toString());
            }
        }
        out.sort(null);
        return out;
    }

    /** Groups for the mob list, in display order. */
    public static final java.util.List<String> GROUPS = java.util.List.of("Hostile", "Animals", "Water", "Other mobs", "Objects");

    /** Which {@link #GROUPS} entry an entity type belongs to. */
    public static String group(String id) {
        Identifier key = Identifier.tryParse(id);
        EntityType<?> t = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        if (t == null) {
            return "Objects";
        }
        return switch (t.getCategory()) {
            case MONSTER -> "Hostile";
            case CREATURE, AMBIENT, AXOLOTLS -> "Animals";
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> "Water";
            default -> spawnEggId(id) != null ? "Other mobs" : "Objects";
        };
    }

    public static boolean isEntity(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key);
    }

    /** The spawn egg for an entity type, by the vanilla naming convention, or null. */
    public static String spawnEggId(String entityId) {
        String egg = entityId + "_spawn_egg";
        return McItems.isItem(egg) ? egg : null;
    }
}
