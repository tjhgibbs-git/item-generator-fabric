package io.github.tjhgibbs.stacksmith.mod;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * Converts between core's {@link Nbt} and the game's tags by way of SNBT text. Slower than
 * walking the trees, but it touches no tag class API (which has changed in most recent
 * releases), only the parser and {@code toString()}, so it survives updates.
 */
public final class McNbt {
    private McNbt() {
    }

    public static Tag toGame(Nbt n) throws CommandSyntaxException {
        return TagParser.create(NbtOps.INSTANCE).parseFully(Snbt.write(n));
    }

    public static CompoundTag toGame(Nbt.Compound c) throws CommandSyntaxException {
        return TagParser.parseCompoundFully(Snbt.write(c));
    }

    public static Nbt fromGame(Tag t) {
        try {
            return Snbt.parse(t.toString());
        } catch (SnbtException e) {
            // The game printed SNBT that core can't read: a core parser bug worth hearing about.
            throw new IllegalStateException("Could not read the game's SNBT: " + e.getMessage() + "\n" + t, e);
        }
    }

    public static Nbt.Compound fromGame(CompoundTag t) {
        return (Nbt.Compound) fromGame((Tag) t);
    }
}
