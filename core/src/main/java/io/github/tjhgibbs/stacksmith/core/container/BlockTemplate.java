package io.github.tjhgibbs.stacksmith.core.container;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

/** Stars store only a relative setblock command; location belongs to the world index. */
public final class BlockTemplate {
    private BlockTemplate() {}

    public static Nbt.Compound save(String command) {
        return Nbt.compound().put("command", command);
    }

    public static Nbt.Compound data(Nbt.Compound tag) {
        String command = tag.getString("command", null);
        if (command == null) return tag;
        if (!command.startsWith("setblock ~ ~ ~ "))
            throw new IllegalArgumentException("Expected a relative setblock template");
        int start = command.indexOf('{');
        try {
            return start < 0 ? Nbt.compound() : Snbt.parseCompound(command.substring(start));
        } catch (SnbtException e) {
            throw new IllegalArgumentException("Invalid template", e);
        }
    }

    public static String block(Nbt.Compound tag) {
        String command = tag.getString("command", "");
        if (!command.startsWith("setblock ~ ~ ~ "))
            throw new IllegalArgumentException("Expected a relative setblock template");
        return command.substring(15).split("[\\[{ ]", 2)[0];
    }
}
