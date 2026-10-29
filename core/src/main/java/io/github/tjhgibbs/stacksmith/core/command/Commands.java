package io.github.tjhgibbs.stacksmith.core.command;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;

/**
 * Builds the commands and helper items Stacksmith hands out. Pure strings: the mod checks each
 * one with the game's own parser before it is used.
 */
public final class Commands {
    /** Longest command a player can type or send from the client (chat packet limit). */
    public static final int CHAT_LIMIT = 256;
    /** Longest command a command block stores. */
    public static final int COMMAND_BLOCK_LIMIT = 32500;

    private Commands() {
    }

    /** Options shared by every builder. */
    public record Style(boolean brief, String target) {
        public static final Style DEFAULT = new Style(true, "@s");
    }

    /** {@code give @s diamond_sword[...] 3} */
    public static String give(ItemSpec item, Style style) {
        String cmd = "give " + style.target() + " " + item.toArgument(style.brief());
        return item.count() != 1 ? cmd + " " + item.count() : cmd;
    }

    /** {@code setblock <pos> <block>[{data}] <mode>}; {@code pos} like {@code "~ ~ ~"}. */
    public static String setblock(String pos, String blockState, Nbt.Compound data, String mode, Style style) {
        StringBuilder sb = new StringBuilder("setblock ").append(pos).append(' ').append(style.brief() ? Ids.brief(blockState) : blockState);
        if (data != null && !data.isEmpty()) {
            sb.append(Snbt.write(data));
        }
        if (mode != null && !mode.equals("replace")) {
            sb.append(' ').append(mode);
        }
        return sb.toString();
    }

    /** {@code setblock ~ ~ ~ spawner{...}}: places this spawner where the command runs. */
    public static String setSpawner(SpawnerSpec spawner, String pos, Style style) {
        return setblock(pos, SpawnerSpec.BLOCK_ID, spawner.toTag(), "replace", style);
    }

    /** {@code summon zombie ~ ~ ~ {...}} */
    public static String summon(EntitySpec entity, String pos, Style style) {
        StringBuilder sb = new StringBuilder("summon ").append(style.brief() ? Ids.brief(entity.id()) : entity.id()).append(' ').append(pos);
        if (!entity.data().isEmpty()) {
            sb.append(' ').append(Snbt.write(entity.data()));
        }
        return sb.toString();
    }

    /** A spawner item that places this spawner as it is (needs op rights where it is placed). */
    public static ItemSpec spawnerItem(SpawnerSpec spawner, Nbt customName) {
        ItemSpec item = new ItemSpec(SpawnerSpec.BLOCK_ID);
        item.set("block_entity_data", spawner.toBlockEntityData());
        if (customName != null) {
            item.set("custom_name", customName);
        }
        return item;
    }

    /**
     * A command block item that runs {@code command} by itself the moment it is placed
     * ({@code auto}: always active, no redstone). With a command that {@code setblock}s at
     * {@code ~ ~ ~}, the block replaces itself with the result.
     */
    public static ItemSpec commandBlockItem(String command, Nbt customName) {
        ItemSpec item = new ItemSpec("command_block");
        item.set("block_entity_data", new Nbt.Compound()
                .put("id", "minecraft:command_block")
                .put("Command", command)
                .put("auto", Nbt.bool(true))
                .put("TrackOutput", Nbt.bool(false)));
        if (customName != null) {
            item.set("custom_name", customName);
        }
        return item;
    }

    /** The command a command-block item would run, or null if it is not one. */
    public static String commandOf(ItemSpec item) {
        if (!item.id().equals("minecraft:command_block") && !item.id().endsWith("command_block")) {
            return null;
        }
        return item.get("block_entity_data") instanceof Nbt.Compound be ? be.getString("Command", null) : null;
    }

    /** A command with any leading slash removed and spaces trimmed. */
    public static String clean(String command) {
        String s = command.strip();
        return s.startsWith("/") ? s.substring(1).stripLeading() : s;
    }
}
