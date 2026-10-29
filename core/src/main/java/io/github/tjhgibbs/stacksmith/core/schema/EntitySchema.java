package io.github.tjhgibbs.stacksmith.core.schema;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fields only some mobs have (baby, slime size, villager profession...), loaded from
 * {@code stacksmith/schema/entities.outline} (see that file). Reads and writes them on an
 * {@link EntitySpec}; every save key lives in the file, not in code.
 */
public final class EntitySchema {
    public static final String RESOURCE = "/stacksmith/schema/entities.outline";
    /** The 16 dye colours in save order (0 white ... 15 black). */
    public static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    public enum Editor {
        BOOL, NUMBER, CHOICE, DYE, ID
    }

    /**
     * One field. {@code path} is the key split at dots; {@code on} is what a bool writes;
     * {@code numberType} is byte/short/int/float; {@code values} maps choice labels to saved values.
     */
    public record Field(String name, String label, List<String> path, Editor editor, String about, Nbt on, String numberType,
                        double min, double max, Map<String, Nbt> values, String registry, Set<String> mobs) {
        public boolean appliesTo(String entityId) {
            return mobs.contains(Ids.full(entityId));
        }

        /** The saved value, or null when the mob doesn't have it set. */
        public Nbt get(EntitySpec mob) {
            Nbt n = mob.data();
            for (String k : path) {
                if (!(n instanceof Nbt.Compound c) || (n = c.get(k)) == null) {
                    return null;
                }
            }
            return n;
        }

        /** Sets the value, or removes it (and any compounds left empty) for null. */
        public void set(EntitySpec mob, Nbt value) {
            Nbt.Compound c = mob.data();
            List<Nbt.Compound> parents = new ArrayList<>();
            for (String k : path.subList(0, path.size() - 1)) {
                parents.add(c);
                if (value == null && c.getCompound(k) == null) {
                    return;
                }
                c = c.child(k);
            }
            String last = path.getLast();
            if (value != null) {
                c.put(last, value);
                return;
            }
            c.remove(last);
            for (int i = parents.size() - 1; i >= 0 && c.isEmpty(); i--) {
                parents.get(i).remove(path.get(i));
                c = parents.get(i);
            }
        }

        /** For a bool: whether the mob has the "on" value. */
        public boolean isOn(EntitySpec mob) {
            Nbt v = get(mob);
            return v != null && (v.equals(on) || v instanceof Nbt.Num a && on instanceof Nbt.Num b && a.asDouble() == b.asDouble());
        }

        /** For a number: the value typed in, as this field's NBT type. */
        public Nbt.Num number(double v) {
            return switch (numberType) {
                case "byte" -> Nbt.b((long) v);
                case "short" -> Nbt.s((long) v);
                case "float" -> Nbt.f(v);
                case "double" -> Nbt.d(v);
                default -> Nbt.i((long) v);
            };
        }

        public boolean integral() {
            return !numberType.equals("float") && !numberType.equals("double");
        }

        /** For a choice: the label of the saved value, or null. */
        public String choiceLabel(EntitySpec mob) {
            Nbt v = get(mob);
            if (v == null) {
                return null;
            }
            for (var e : values.entrySet()) {
                if (e.getValue().equals(v) || e.getValue() instanceof Nbt.Num a && v instanceof Nbt.Num b && a.asLong() == b.asLong()) {
                    return e.getKey();
                }
            }
            return v instanceof Nbt.Str s ? s.value() : v.toString();
        }
    }

    private final List<Field> fields = new ArrayList<>();

    private EntitySchema() {
    }

    /** The schema shipped in the jar. */
    public static EntitySchema bundled() {
        try (InputStream in = EntitySchema.class.getResourceAsStream(RESOURCE)) {
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

    public static EntitySchema parse(String text) throws SnbtException {
        Nbt.Compound root = Outline.readCompound(text);
        Map<String, List<String>> sets = new LinkedHashMap<>();
        if (root.getCompound("sets") instanceof Nbt.Compound s) {
            s.entries().forEach(e -> sets.put(e.getKey(), strings(e.getValue())));
        }
        EntitySchema schema = new EntitySchema();
        Nbt.Compound fields = root.getCompound("fields");
        if (fields == null) {
            return schema;
        }
        for (var e : fields.entries()) {
            if (!(e.getValue() instanceof Nbt.Compound c)) {
                continue;
            }
            Set<String> mobs = new LinkedHashSet<>();
            for (String m : strings(c.get("for"))) {
                if (m.startsWith("$")) {
                    List<String> set = sets.get(m.substring(1));
                    if (set == null) {
                        throw new SnbtException("field " + e.getKey() + ": no set " + m, 0);
                    }
                    set.forEach(id -> mobs.add(Ids.full(id)));
                } else {
                    mobs.add(Ids.full(m));
                }
            }
            Map<String, Nbt> values = new LinkedHashMap<>();
            Nbt v = c.get("values");
            if (v instanceof Nbt.Compound vc) {
                vc.entries().forEach(x -> values.put(x.getKey(), x.getValue()));
            } else {
                strings(v).forEach(x -> values.put(x, Nbt.str(x)));
            }
            Editor editor = Editor.valueOf(c.getString("editor", "bool").toUpperCase(java.util.Locale.ROOT));
            if (editor == Editor.DYE) {
                for (int i = 0; i < DYES.size(); i++) {
                    values.put(DYES.get(i), Nbt.b(i));
                }
            }
            String key = c.getString("key", e.getKey());
            schema.fields.add(new Field(e.getKey(), c.getString("label", Ids.pretty(e.getKey())), List.of(key.split("\\.")), editor,
                    c.getString("about", ""), c.has("on") ? c.get("on") : Nbt.b(1), c.getString("type", "int"),
                    c.getDouble("min", Double.NEGATIVE_INFINITY), c.getDouble("max", Double.POSITIVE_INFINITY),
                    values, c.getString("registry", null), mobs));
        }
        return schema;
    }

    private static List<String> strings(Nbt n) {
        List<String> out = new ArrayList<>();
        if (n instanceof Nbt.List l) {
            l.items().forEach(x -> out.add(x instanceof Nbt.Str s ? s.value() : x.toString()));
        } else if (n instanceof Nbt.Str s) {
            out.addAll(Arrays.asList(s.value().split("\\s*,\\s*")));
        }
        return out;
    }

    public List<Field> all() {
        return fields;
    }

    /** The fields this mob type has, in file order. */
    public List<Field> fieldsFor(String entityId) {
        return fields.stream().filter(f -> f.appliesTo(entityId)).toList();
    }
}
