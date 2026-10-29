package io.github.tjhgibbs.stacksmith.mod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/** Text components (as core trees) to the game's {@link Component} for previews. */
public final class McText {
    private McText() {
    }

    /** The component, or null if the game can't read it. */
    public static Component toGame(Nbt component, HolderLookup.Provider registries) {
        try {
            return ComponentSerialization.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), McNbt.toGame(component))
                    .result().orElse(null);
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }
}
