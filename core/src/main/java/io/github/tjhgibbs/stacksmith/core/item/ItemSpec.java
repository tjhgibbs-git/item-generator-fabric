package io.github.tjhgibbs.stacksmith.core.item;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An item stack being edited: id, count, and the component <em>patch</em> (components set on
 * top of the item's defaults, plus defaults removed with {@code !name}). This is exactly what a
 * {@code /give} argument or the game's saved stack holds, so nothing is lost either way.
 *
 * <p>Component keys are always full ids ({@code minecraft:custom_name}).
 */
public final class ItemSpec {
    private String id;
    private int count;
    private final Nbt.Compound components = new Nbt.Compound();
    private final Set<String> removed = new LinkedHashSet<>();

    public ItemSpec(String id) {
        this(id, 1);
    }

    public ItemSpec(String id, int count) {
        this.id = Ids.full(id);
        this.count = count;
    }

    public String id() {
        return id;
    }

    public ItemSpec id(String id) {
        this.id = Ids.full(id);
        return this;
    }

    public int count() {
        return count;
    }

    public ItemSpec count(int count) {
        this.count = Math.max(1, count);
        return this;
    }

    /** The set components (mutable view). */
    public Nbt.Compound components() {
        return components;
    }

    /** Default components removed from this stack (mutable view). */
    public Set<String> removed() {
        return removed;
    }

    public Nbt get(String component) {
        return components.get(Ids.full(component));
    }

    /** Sets a component (un-removing it if it was removed). */
    public ItemSpec set(String component, Nbt value) {
        String k = Ids.full(component);
        removed.remove(k);
        components.put(k, value);
        return this;
    }

    /** Clears a component back to the item default. */
    public ItemSpec unset(String component) {
        String k = Ids.full(component);
        components.remove(k);
        removed.remove(k);
        return this;
    }

    /** Removes a default component ({@code !name}). */
    public ItemSpec remove(String component) {
        String k = Ids.full(component);
        components.remove(k);
        removed.add(k);
        return this;
    }

    public boolean isPlain() {
        return components.isEmpty() && removed.isEmpty();
    }

    public ItemSpec copy() {
        ItemSpec s = new ItemSpec(id, count);
        components.entries().forEach(e -> s.components.put(e.getKey(), e.getValue().copy()));
        s.removed.addAll(removed);
        return s;
    }

    // ---- saved stack form: {id:"...", count:N, components:{...,"!x":{}}} ----------------------

    /** The stack as the game saves it (and as items inside other NBT are written). */
    public Nbt.Compound toTag() {
        Nbt.Compound c = new Nbt.Compound().put("id", id);
        if (count != 1) {
            c.put("count", Nbt.i(count));
        }
        if (!isPlain()) {
            Nbt.Compound comps = components.copy();
            removed.forEach(r -> comps.put("!" + r, new Nbt.Compound()));
            c.put("components", comps);
        }
        return c;
    }

    /** Reads the saved stack form. Accepts short ids and a missing count (1). */
    public static ItemSpec fromTag(Nbt.Compound tag) {
        String id = tag.getString("id", null);
        if (id == null) {
            throw new IllegalArgumentException("item has no id");
        }
        ItemSpec s = new ItemSpec(id, (int) tag.getLong("count", tag.getLong("Count", 1)));
        if (tag.getCompound("components") instanceof Nbt.Compound comps) {
            for (var e : comps.entries()) {
                String k = e.getKey();
                if (k.startsWith("!")) {
                    s.removed.add(Ids.full(k.substring(1)));
                } else {
                    s.components.put(Ids.full(k), e.getValue().copy());
                }
            }
        }
        return s;
    }

    // ---- command argument form: id[comp=value,!comp] ------------------------------------------

    /**
     * The item as a command argument: {@code diamond_sword[enchantments={...},!tool]}. With
     * {@code brief}, the {@code minecraft:} namespace is dropped from the id and component names
     * (shorter commands; the game accepts both).
     */
    public String toArgument(boolean brief) {
        StringBuilder sb = new StringBuilder(brief ? Ids.brief(id) : id);
        if (!isPlain()) {
            sb.append('[');
            boolean first = true;
            for (var e : components.entries()) {
                sb.append(first ? "" : ",").append(brief ? Ids.brief(e.getKey()) : e.getKey()).append('=').append(Snbt.write(e.getValue()));
                first = false;
            }
            for (String r : removed) {
                sb.append(first ? "" : ",").append('!').append(brief ? Ids.brief(r) : r);
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** Parses {@code id[comp=snbt,!comp]} starting at {@code offset}; see {@link #parseArgument(String)}. */
    public static Parsed parseArgumentAt(String text, int offset) throws SnbtException {
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < text.length() && isIdChar(text.charAt(i))) {
            i++;
        }
        if (i == start) {
            throw new SnbtException("expected an item id", start);
        }
        ItemSpec spec = new ItemSpec(text.substring(start, i));
        if (i < text.length() && text.charAt(i) == '[') {
            i++;
            while (true) {
                i = skipWs(text, i);
                if (i < text.length() && text.charAt(i) == ']') {
                    i++;
                    break;
                }
                boolean remove = i < text.length() && text.charAt(i) == '!';
                if (remove) {
                    i++;
                }
                int ks = i;
                while (i < text.length() && isIdChar(text.charAt(i))) {
                    i++;
                }
                if (i == ks) {
                    throw new SnbtException("expected a component name", ks);
                }
                String key = Ids.full(text.substring(ks, i));
                i = skipWs(text, i);
                if (remove) {
                    spec.remove(key);
                } else {
                    if (i >= text.length() || text.charAt(i) != '=') {
                        throw new SnbtException("expected '=' after " + Ids.brief(key), i);
                    }
                    Snbt.Parsed v = Snbt.parseAt(text, i + 1);
                    spec.set(key, v.value());
                    i = v.end();
                }
                i = skipWs(text, i);
                if (i < text.length() && text.charAt(i) == ',') {
                    i++;
                } else if (i < text.length() && text.charAt(i) == ']') {
                    i++;
                    break;
                } else {
                    throw new SnbtException("expected ',' or ']'", i);
                }
            }
        }
        return new Parsed(spec, i);
    }

    /** Parses a whole item argument; trailing text (other than spaces) is an error. */
    public static ItemSpec parseArgument(String text) throws SnbtException {
        Parsed p = parseArgumentAt(text, 0);
        int end = skipWs(text, p.end());
        if (end < text.length()) {
            throw new SnbtException("unexpected text after the item", end);
        }
        return p.item();
    }

    public record Parsed(ItemSpec item, int end) {
    }

    static boolean isIdChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == ':' || c == '.' || c == '-' || c == '/';
    }

    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemSpec s && s.id.equals(id) && s.count == count && s.components.equals(components) && s.removed.equals(removed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, count, components, removed);
    }

    @Override
    public String toString() {
        return toArgument(true) + (count != 1 ? " x" + count : "");
    }
}
