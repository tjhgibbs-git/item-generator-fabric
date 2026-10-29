package io.github.tjhgibbs.stacksmith.core.schema;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Stacksmith knows about each data component: which form edits it, where it is listed,
 * a line of help and a starting value. Loaded from {@code stacksmith/schema/components.outline}
 * (see that file); everything version-specific about components lives there, not in code.
 */
public final class ComponentSchema {
    public static final String RESOURCE = "/stacksmith/schema/components.outline";

    /** The form used to edit a component. */
    public enum Editor {
        TEXT, TEXT_LIST, INT, FLOAT, BOOL, UNIT, ENUM, COLOR, ID, ENCHANTMENTS, MODIFIERS, EFFECTS, POTION, OUTLINE;

        static Editor of(String s) {
            try {
                return valueOf(s.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OUTLINE;
            }
        }
    }

    /** One component's entry. {@code id} is the full id; {@code defaultValue} may be null. */
    public record Info(String id, String group, Editor editor, String about, Nbt defaultValue, double min, double max,
                       List<String> values, String registry, boolean upright) {
        public String brief() {
            return Ids.brief(id);
        }
    }

    private final Map<String, Info> components = new LinkedHashMap<>();
    private final Map<String, String> groups = new LinkedHashMap<>();

    private ComponentSchema() {
    }

    /** The schema shipped in the jar. */
    public static ComponentSchema bundled() {
        try (InputStream in = ComponentSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SnbtException e) {
            throw new IllegalStateException(RESOURCE + ": " + e.getMessage(), e);
        }
    }

    public static ComponentSchema parse(String text) throws SnbtException {
        Nbt.Compound root = Outline.readCompound(text);
        ComponentSchema s = new ComponentSchema();
        Nbt.Compound groups = root.getCompound("groups");
        if (groups != null) {
            groups.entries().forEach(e -> s.groups.put(e.getKey(), e.getValue() instanceof Nbt.Str str ? str.value() : e.getKey()));
        }
        Nbt.Compound comps = root.getCompound("components");
        if (comps != null) {
            for (var e : comps.entries()) {
                if (!(e.getValue() instanceof Nbt.Compound c)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                if (c.getList("values") instanceof Nbt.List l) {
                    l.items().forEach(v -> values.add(v instanceof Nbt.Str str ? str.value() : v.toString()));
                }
                String id = Ids.full(e.getKey());
                String group = c.getString("group", "other");
                s.groups.putIfAbsent(group, Ids.pretty(group));
                s.components.put(id, new Info(id, group, Editor.of(c.getString("editor", "outline")), c.getString("about", ""),
                        c.get("default"), c.getDouble("min", Double.NEGATIVE_INFINITY), c.getDouble("max", Double.POSITIVE_INFINITY),
                        List.copyOf(values), c.getString("registry", null), c.getBool("upright", true)));
            }
        }
        s.groups.putIfAbsent("other", "Other");
        return s;
    }

    /** The entry for a component, or a plain outline entry for one this file doesn't list. */
    public Info info(String componentId) {
        String id = Ids.full(componentId);
        Info i = components.get(id);
        return i != null ? i : new Info(id, "other", Editor.OUTLINE, "", null, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                List.of(), null, true);
    }

    public boolean knows(String componentId) {
        return components.containsKey(Ids.full(componentId));
    }

    public Collection<Info> all() {
        return components.values();
    }

    /** Group ids in display order, mapped to their headings. */
    public Map<String, String> groups() {
        return groups;
    }

    /**
     * Orders the game's component ids for the "add component" list: by group, then in this
     * file's order, unknown ones last alphabetically.
     */
    public List<Info> arrange(Collection<String> available) {
        List<Info> out = new ArrayList<>();
        List<String> order = new ArrayList<>(components.keySet());
        for (String group : groups.keySet()) {
            available.stream().map(this::info).filter(i -> i.group().equals(group))
                    .sorted((a, b) -> {
                        int ia = order.indexOf(a.id()), ib = order.indexOf(b.id());
                        if (ia < 0 || ib < 0) {
                            return ia < 0 && ib < 0 ? a.id().compareTo(b.id()) : ia < 0 ? 1 : -1;
                        }
                        return Integer.compare(ia, ib);
                    })
                    .forEach(out::add);
        }
        return out;
    }
}
