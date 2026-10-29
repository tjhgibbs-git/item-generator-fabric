package io.github.tjhgibbs.stacksmith.core.command;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

/**
 * Turns pasted text into something editable: a {@code /give}, {@code /setblock},
 * {@code /summon} or {@code /item replace} command (from MCStacker, a wiki, a datapack...), a
 * bare item argument ({@code diamond_sword[...]}), a saved stack ({@code {id:...,components:...}})
 * or a block state with data ({@code spawner{...}}). Self-running command-block items are
 * unwrapped to what they place.
 */
public final class Importer {
    private Importer() {
    }

    /** What the text turned out to be. */
    public sealed interface Result permits ItemResult, SpawnerResult, EntityResult, BlockResult {
    }

    public record ItemResult(ItemSpec item) implements Result {
    }

    /** A spawner, and the item it came wrapped in, if any (e.g. a named spawner item). */
    public record SpawnerResult(SpawnerSpec spawner, ItemSpec item) implements Result {
    }

    public record EntityResult(EntitySpec entity) implements Result {
    }

    /** Any other block: state string (with properties) and its data (possibly empty). */
    public record BlockResult(String state, Nbt.Compound data) implements Result {
    }

    public static Result parse(String text) throws SnbtException {
        String s = Commands.clean(text);
        if (s.isEmpty()) {
            throw new SnbtException("nothing to import", 0);
        }
        if (s.startsWith("{")) {
            Nbt.Compound c = Snbt.parseCompound(s);
            if (c.has("components") || c.has("count") || c.has("Count")) {
                return unwrap(ItemSpec.fromTag(c));
            }
            if (c.has("SpawnData") || c.has("SpawnPotentials")) {
                return new SpawnerResult(SpawnerSpec.fromTag(c), null);
            }
            if (c.has("id")) {
                return new EntityResult(EntitySpec.fromTag(c));
            }
            throw new SnbtException("can't tell what this data is (no id)", 0);
        }
        String[] words = s.split("\\s+", 2);
        String verb = words[0].toLowerCase();
        String rest = words.length > 1 ? words[1] : "";
        int restAt = s.length() - rest.length();
        return switch (verb) {
            case "give" -> {
                // give <targets> <item> [count]
                int itemAt = skipWord(s, restAt);
                ItemSpec.Parsed p = ItemSpec.parseArgumentAt(s, itemAt);
                String tail = s.substring(p.end()).strip();
                if (!tail.isEmpty()) {
                    try {
                        p.item().count(Integer.parseInt(tail.split("\\s+")[0]));
                    } catch (NumberFormatException e) {
                        throw new SnbtException("expected a count after the item", p.end());
                    }
                }
                yield unwrap(p.item());
            }
            case "item" -> {
                // item replace (entity <t>|block <x y z>) <slot> with <item> [count]
                int with = s.indexOf(" with ");
                if (with < 0) {
                    throw new SnbtException("expected '... with <item>'", restAt);
                }
                ItemSpec.Parsed p = ItemSpec.parseArgumentAt(s, with + 6);
                String tail = s.substring(p.end()).strip();
                if (!tail.isEmpty()) {
                    try {
                        p.item().count(Integer.parseInt(tail.split("\\s+")[0]));
                    } catch (NumberFormatException ignored) {
                        // keep count 1
                    }
                }
                yield unwrap(p.item());
            }
            case "setblock" -> {
                int at = restAt;
                for (int i = 0; i < 3; i++) {
                    at = skipWord(s, at);
                }
                yield block(s, at);
            }
            case "summon" -> {
                int at = ItemSpecs.skipWs(s, restAt);
                int idEnd = at;
                while (idEnd < s.length() && !Character.isWhitespace(s.charAt(idEnd))) {
                    idEnd++;
                }
                EntitySpec e = new EntitySpec(s.substring(at, idEnd));
                int brace = s.indexOf('{', idEnd);
                if (brace >= 0) {
                    Nbt n = Snbt.parseAt(s, brace).value();
                    if (n instanceof Nbt.Compound c) {
                        c.entries().forEach(en -> e.data().put(en.getKey(), en.getValue()));
                        e.data().remove("id");
                    }
                }
                yield new EntityResult(e);
            }
            default -> {
                // A bare item argument or block state.
                if (s.indexOf('{') >= 0 && (s.indexOf('[') < 0 || s.indexOf('{') < s.indexOf('['))
                        || s.matches("[a-z0-9_:./-]+\\[[^=\\]]*=[^\\]{]*\\]\\{.*")) {
                    yield block(s, 0);
                }
                yield unwrap(ItemSpec.parseArgument(s));
            }
        };
    }

    /** Items that are really spawners or self-running command blocks become what they hold. */
    private static Result unwrap(ItemSpec item) throws SnbtException {
        String cmd = Commands.commandOf(item);
        if (cmd != null && !cmd.isBlank()) {
            String inner = Commands.clean(cmd);
            if (inner.startsWith("setblock ") || inner.startsWith("give ") || inner.startsWith("summon ")) {
                Result r = parse(inner);
                if (r instanceof SpawnerResult sr) {
                    return new SpawnerResult(sr.spawner(), null);
                }
                return r;
            }
        }
        if (item.id().equals(SpawnerSpec.BLOCK_ID) && item.get("block_entity_data") instanceof Nbt.Compound be) {
            return new SpawnerResult(SpawnerSpec.fromTag(be), item);
        }
        return new ItemResult(item);
    }

    private static Result block(String s, int at) throws SnbtException {
        at = ItemSpecs.skipWs(s, at);
        int i = at;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "_:./-".indexOf(s.charAt(i)) >= 0)) {
            i++;
        }
        String id = Ids.full(s.substring(at, i));
        String state = id;
        if (i < s.length() && s.charAt(i) == '[') {
            int close = s.indexOf(']', i);
            if (close < 0) {
                throw new SnbtException("unclosed block properties", i);
            }
            state = id + s.substring(i, close + 1);
            i = close + 1;
        }
        Nbt.Compound data = new Nbt.Compound();
        if (i < s.length() && s.charAt(i) == '{') {
            if (Snbt.parseAt(s, i).value() instanceof Nbt.Compound c) {
                data = c;
            }
        }
        if (id.equals(SpawnerSpec.BLOCK_ID)) {
            return new SpawnerResult(SpawnerSpec.fromTag(data), null);
        }
        return new BlockResult(state, data);
    }

    private static int skipWord(String s, int i) {
        i = ItemSpecs.skipWs(s, i);
        // Selectors may contain brackets with spaces: @e[type=zombie, limit=1]
        int depth = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (Character.isWhitespace(c) && depth <= 0) {
                break;
            }
            i++;
        }
        return ItemSpecs.skipWs(s, i);
    }

    /** Small helpers shared with {@link ItemSpec}'s parser. */
    private static final class ItemSpecs {
        static int skipWs(String s, int i) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i;
        }
    }
}
