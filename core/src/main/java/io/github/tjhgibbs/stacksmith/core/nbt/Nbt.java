package io.github.tjhgibbs.stacksmith.core.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A plain-Java NBT tree. Everything Stacksmith edits (item components, entity data, block
 * entity data) is held as one of these, so the editor, the text formats and the command
 * builders never touch Minecraft classes. The mod converts to and from the game's tags at the
 * edges ({@code McNbt} in the fabric module).
 *
 * <p>Compounds keep insertion order, so what the player typed comes back in the same order.
 */
public sealed interface Nbt permits Nbt.Compound, Nbt.List, Nbt.Str, Nbt.Num, Nbt.Arr {

    /** A deep copy. Scalars are immutable and return themselves. */
    Nbt copy();

    /** Short type name for messages and the type picker ("compound", "int", ...). */
    String typeName();

    // ---- factories -----------------------------------------------------------------------------

    static Str str(String s) {
        return new Str(s);
    }

    static Num b(long v) {
        return Num.of(Num.Kind.BYTE, v);
    }

    static Num bool(boolean v) {
        return Num.of(Num.Kind.BYTE, v ? 1 : 0);
    }

    static Num s(long v) {
        return Num.of(Num.Kind.SHORT, v);
    }

    static Num i(long v) {
        return Num.of(Num.Kind.INT, v);
    }

    static Num l(long v) {
        return Num.of(Num.Kind.LONG, v);
    }

    static Num f(double v) {
        return Num.of(Num.Kind.FLOAT, v);
    }

    static Num d(double v) {
        return Num.of(Num.Kind.DOUBLE, v);
    }

    static Compound compound() {
        return new Compound();
    }

    static List list(Nbt... items) {
        List l = new List();
        for (Nbt n : items) {
            l.add(n);
        }
        return l;
    }

    // ---- compound ------------------------------------------------------------------------------

    /** Ordered, mutable map of named tags. */
    final class Compound implements Nbt {
        private final LinkedHashMap<String, Nbt> map = new LinkedHashMap<>();

        public Nbt get(String key) {
            return map.get(key);
        }

        public Compound put(String key, Nbt value) {
            map.put(Objects.requireNonNull(key), Objects.requireNonNull(value, key));
            return this;
        }

        public Compound put(String key, String value) {
            return put(key, new Str(value));
        }

        public Nbt remove(String key) {
            return map.remove(key);
        }

        public boolean has(String key) {
            return map.containsKey(key);
        }

        public Set<String> keys() {
            return map.keySet();
        }

        public Set<Map.Entry<String, Nbt>> entries() {
            return map.entrySet();
        }

        public Collection<Nbt> values() {
            return map.values();
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        /** The compound at {@code key}, created (and stored) if absent or not a compound. */
        public Compound child(String key) {
            if (map.get(key) instanceof Compound c) {
                return c;
            }
            Compound c = new Compound();
            map.put(key, c);
            return c;
        }

        /** The list at {@code key}, created (and stored) if absent or not a list. */
        public List childList(String key) {
            if (map.get(key) instanceof List l) {
                return l;
            }
            List l = new List();
            map.put(key, l);
            return l;
        }

        public Compound getCompound(String key) {
            return map.get(key) instanceof Compound c ? c : null;
        }

        public List getList(String key) {
            return map.get(key) instanceof List l ? l : null;
        }

        public String getString(String key, String fallback) {
            return map.get(key) instanceof Str s ? s.value() : fallback;
        }

        public long getLong(String key, long fallback) {
            return map.get(key) instanceof Num n ? n.asLong() : fallback;
        }

        public double getDouble(String key, double fallback) {
            return map.get(key) instanceof Num n ? n.asDouble() : fallback;
        }

        public boolean getBool(String key, boolean fallback) {
            return map.get(key) instanceof Num n ? n.asLong() != 0 : fallback;
        }

        /** Removes {@code key} when {@code value} is null, so optional fields stay out of the output. */
        public Compound putOrRemove(String key, Nbt value) {
            if (value == null) {
                map.remove(key);
            } else {
                map.put(key, value);
            }
            return this;
        }

        @Override
        public Compound copy() {
            Compound c = new Compound();
            map.forEach((k, v) -> c.map.put(k, v.copy()));
            return c;
        }

        @Override
        public String typeName() {
            return "compound";
        }

        @Override
        public boolean equals(Object o) {
            // Order does not matter for equality, as in the game.
            return o instanceof Compound c && c.map.equals(map);
        }

        @Override
        public int hashCode() {
            return map.hashCode();
        }

        @Override
        public String toString() {
            return Snbt.write(this);
        }
    }

    // ---- list ----------------------------------------------------------------------------------

    /** Ordered, mutable list. Since 1.21.5 lists may mix element types; nothing here forbids it. */
    final class List implements Nbt {
        private final ArrayList<Nbt> items = new ArrayList<>();

        public List add(Nbt n) {
            items.add(Objects.requireNonNull(n));
            return this;
        }

        public void add(int index, Nbt n) {
            items.add(index, Objects.requireNonNull(n));
        }

        public Nbt get(int i) {
            return items.get(i);
        }

        public void set(int i, Nbt n) {
            items.set(i, Objects.requireNonNull(n));
        }

        public Nbt remove(int i) {
            return items.remove(i);
        }

        public int size() {
            return items.size();
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public java.util.List<Nbt> items() {
            return items;
        }

        @Override
        public List copy() {
            List l = new List();
            items.forEach(n -> l.items.add(n.copy()));
            return l;
        }

        @Override
        public String typeName() {
            return "list";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof List l && l.items.equals(items);
        }

        @Override
        public int hashCode() {
            return items.hashCode();
        }

        @Override
        public String toString() {
            return Snbt.write(this);
        }
    }

    // ---- scalars -------------------------------------------------------------------------------

    record Str(String value) implements Nbt {
        public Str {
            Objects.requireNonNull(value);
        }

        @Override
        public Str copy() {
            return this;
        }

        @Override
        public String typeName() {
            return "string";
        }

        @Override
        public String toString() {
            return Snbt.write(this);
        }
    }

    /**
     * A number with its NBT type. Integer kinds keep the value in {@code whole}, FLOAT and
     * DOUBLE in {@code real}; use {@link #asLong()} / {@link #asDouble()} to read either.
     */
    record Num(Kind kind, long whole, double real) implements Nbt {
        public enum Kind {
            BYTE("b", Byte.MIN_VALUE, Byte.MAX_VALUE),
            SHORT("s", Short.MIN_VALUE, Short.MAX_VALUE),
            INT("", Integer.MIN_VALUE, Integer.MAX_VALUE),
            LONG("L", Long.MIN_VALUE, Long.MAX_VALUE),
            FLOAT("f", 0, 0),
            DOUBLE("d", 0, 0);

            public final String suffix;
            public final long min, max;

            Kind(String suffix, long min, long max) {
                this.suffix = suffix;
                this.min = min;
                this.max = max;
            }

            public boolean integral() {
                return this != FLOAT && this != DOUBLE;
            }
        }

        public static Num of(Kind kind, long v) {
            if (!kind.integral()) {
                return of(kind, (double) v);
            }
            if (v < kind.min || v > kind.max) {
                throw new IllegalArgumentException(v + " is out of range for " + kind.name().toLowerCase());
            }
            return new Num(kind, v, v);
        }

        public static Num of(Kind kind, double v) {
            if (kind.integral()) {
                return of(kind, (long) v);
            }
            double stored = kind == Kind.FLOAT ? (float) v : v;
            return new Num(kind, (long) stored, stored);
        }

        public long asLong() {
            return kind.integral() ? whole : (long) real;
        }

        public double asDouble() {
            return kind.integral() ? whole : real;
        }

        /** The same value as another kind (clamped for integers), e.g. the editor retyping 5 to 5b. */
        public Num as(Kind k) {
            if (k.integral()) {
                return of(k, Math.max(k.min, Math.min(k.max, kind.integral() ? whole : Math.round(real))));
            }
            return of(k, asDouble());
        }

        @Override
        public Num copy() {
            return this;
        }

        @Override
        public String typeName() {
            return kind.name().toLowerCase();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Num n && n.kind == kind && (kind.integral() ? n.whole == whole : Double.compare(n.real, real) == 0);
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + (kind.integral() ? Long.hashCode(whole) : Double.hashCode(real));
        }

        @Override
        public String toString() {
            return Snbt.write(this);
        }
    }

    /** {@code [B;...]}, {@code [I;...]} or {@code [L;...]}. */
    record Arr(Num.Kind kind, long[] values) implements Nbt {
        public Arr {
            if (kind != Num.Kind.BYTE && kind != Num.Kind.INT && kind != Num.Kind.LONG) {
                throw new IllegalArgumentException("arrays hold bytes, ints or longs, not " + kind);
            }
            for (long v : values) {
                if (v < kind.min || v > kind.max) {
                    throw new IllegalArgumentException(v + " is out of range for a " + kind.name().toLowerCase() + " array");
                }
            }
            values = values.clone();
        }

        @Override
        public long[] values() {
            return values.clone();
        }

        public int length() {
            return values.length;
        }

        public long get(int i) {
            return values[i];
        }

        @Override
        public Arr copy() {
            return this;
        }

        @Override
        public String typeName() {
            return kind.name().toLowerCase() + "_array";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Arr a && a.kind == kind && Arrays.equals(a.values, values);
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Snbt.write(this);
        }
    }
}
