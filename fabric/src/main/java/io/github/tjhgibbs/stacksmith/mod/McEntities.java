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
            return EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), EntityProcessor.NOP);
        } catch (Exception | LinkageError e) {
            StacksmithMod.LOG.debug("No preview for {}: {}", spec.id(), e.toString());
            return null;
        }
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
