package io.github.tjhgibbs.stacksmith.mod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ids and tags of any registry, by registry name ({@code minecraft:enchantment}). */
public final class McRegistries {
    private McRegistries() {
    }

    /** Element ids, sorted; empty if the registry is unknown. */
    public static List<String> ids(String registry, HolderLookup.Provider registries) {
        List<String> out = new ArrayList<>();
        lookup(registry, registries).ifPresent(l -> l.listElements().forEach(h -> out.add(h.getRegisteredName())));
        out.sort(null);
        return out;
    }

    /** Tag ids with a leading '#', sorted. */
    public static List<String> tags(String registry, HolderLookup.Provider registries) {
        List<String> out = new ArrayList<>();
        lookup(registry, registries).ifPresent(l -> l.listTags().forEach(t -> out.add("#" + t.key().location())));
        out.sort(null);
        return out;
    }

    private static Optional<? extends HolderLookup.RegistryLookup<Object>> lookup(String registry, HolderLookup.Provider registries) {
        Identifier id = Identifier.tryParse(registry);
        if (id == null) {
            return Optional.empty();
        }
        ResourceKey<? extends Registry<Object>> key = ResourceKey.createRegistryKey(id);
        return registries.lookup(key);
    }

    /** "minecraft:sharpness" for a holder. */
    public static String name(Holder<?> holder) {
        return holder.getRegisteredName();
    }
}
