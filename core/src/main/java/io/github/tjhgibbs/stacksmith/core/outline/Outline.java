package io.github.tjhgibbs.stacksmith.core.outline;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The editor's readable NBT text: YAML-like indentation instead of braces and commas, strings
 * without quotes, {@code true}/{@code false} for flags. Converts losslessly to and from
 * {@link Nbt} (types survive: {@code 5b}, {@code 2.5f}, {@code 3L} keep their suffix).
 *
 * <pre>
 * custom_name:
 *   text: Excalibur
 *   color: gold
 *   italic: false
 * enchantments:
 *   minecraft:sharpness: 5
 *   minecraft:unbreaking: 3
 * lore:
 *   - text: Forged in the deep
 *     color: gray
 *   - A plain line
 * attribute_modifiers:
 *   - type: attack_damage
 *     amount: 12.0
 *     operation: add_value
 * tags: [legendary, bound]
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code key: value} on one line, or {@code key:} with an indented block under it.</li>
 *   <li>A list is lines starting with {@code - }; an item can be a scalar or a compound whose
 *       first key follows the dash.</li>
 *   <li>A value may also be written as inline SNBT: {@code {a: 1}}, {@code [1, 2]}, {@code [I; 1, 2]}.</li>
 *   <li>Unquoted text is a string unless it reads as a number or {@code true}/{@code false};
 *       quote it ({@code "5"}) to force a string.</li>
 *   <li>Lines starting with {@code #} are comments. Indent with spaces (a tab counts as two).</li>
 * </ul>
 */
public final class Outline {
    private static final int INDENT = 2;
    /** Short scalar lists are written inline ({@code [1, 2, 3]}) when they fit this width. */
    private static final int INLINE_LIST_WIDTH = 60;
    private static final Pattern OPERATION = Pattern.compile("[a-z_]+\\(.*\\)");

    private Outline() {
    }

    // ---- writing -------------------------------------------------------------------------------

    /** Writes any tag. Compounds and lists become indented blocks; a scalar is one line. */
    public static String write(Nbt n) {
        StringBuilder sb = new StringBuilder();
        switch (n) {
            case Nbt.Compound c -> writeCompound(sb, c, 0);
            case Nbt.List l when !l.isEmpty() && !inlineable(l) -> writeList(sb, l, 0);
            default -> sb.append(inline(n, true)).append('\n');
        }
        return sb.toString();
    }

    private static void writeCompound(StringBuilder sb, Nbt.Compound c, int indent) {
        for (var e : c.entries()) {
            pad(sb, indent).append(key(e.getKey())).append(':');
            writeValue(sb, e.getValue(), indent);
        }
    }

    /** Writes what follows "key:" or "-": either " scalar\n" or "\n" and an indented block. */
    private static void writeValue(StringBuilder sb, Nbt v, int indent) {
        if (v instanceof Nbt.Compound c && !c.isEmpty()) {
            sb.append('\n');
            writeCompound(sb, c, indent + INDENT);
        } else if (v instanceof Nbt.List l && !l.isEmpty() && !inlineable(l)) {
            sb.append('\n');
            writeList(sb, l, indent + INDENT);
        } else {
            sb.append(' ').append(inline(v, false)).append('\n');
        }
    }

    private static void writeList(StringBuilder sb, Nbt.List l, int indent) {
        for (Nbt item : l.items()) {
            pad(sb, indent).append('-');
            if (item instanceof Nbt.Compound c && !c.isEmpty()) {
                // "- first: value" then the other keys aligned under "first".
                boolean first = true;
                for (var e : c.entries()) {
                    if (first) {
                        sb.append(' ');
                        first = false;
                    } else {
                        pad(sb, indent + INDENT);
                    }
                    sb.append(key(e.getKey())).append(':');
                    writeValue(sb, e.getValue(), indent + INDENT);
                }
            } else if (item instanceof Nbt.List inner && !inner.isEmpty() && !inlineable(inner)) {
                sb.append('\n');
                writeList(sb, inner, indent + INDENT);
            } else {
                sb.append(' ').append(inline(item, true)).append('\n');
            }
        }
    }

    private static boolean inlineable(Nbt.List l) {
        int width = 2;
        for (Nbt n : l.items()) {
            if (n instanceof Nbt.Compound || n instanceof Nbt.List) {
                return false;
            }
            width += inline(n, true).length() + 2;
        }
        return width <= INLINE_LIST_WIDTH;
    }

    private static StringBuilder pad(StringBuilder sb, int indent) {
        return sb.append(" ".repeat(indent));
    }

    /** One value on one line. Containers fall back to SNBT ({@code {}}, {@code []}, short lists). */
    static String inline(Nbt n, boolean inList) {
        return switch (n) {
            case Nbt.Str s -> scalarString(s.value(), inList);
            case Nbt.Num num -> num.kind() == Nbt.Num.Kind.BYTE && (num.whole() == 0 || num.whole() == 1)
                    ? (num.whole() == 1 ? "true" : "false") : Snbt.number(num);
            case Nbt.List l -> {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(i > 0 ? ", " : "");
                    Nbt item = l.get(i);
                    sb.append(item instanceof Nbt.Str s ? listString(s.value()) : item instanceof Nbt.Num || item instanceof Nbt.Arr
                            ? inline(item, true) : Snbt.write(item));
                }
                yield sb.append(']').toString();
            }
            case Nbt.Arr a -> Snbt.write(a).replace(",", ", ").replace(";", "; ");
            case Nbt.Compound c -> Snbt.write(c);
        };
    }

    /** Inside an inline list, a string goes bare when the loose list reader reads it back. */
    private static String listString(String s) {
        boolean bare = !needsQuotes(s, true) && s.chars().noneMatch(ch -> ch == ',' || ch == '[' || ch == ']' || ch == '{' || ch == '}');
        return bare ? s : Snbt.quote(s);
    }

    /** A string bare when it reads back as itself, otherwise quoted. */
    static String scalarString(String s, boolean inList) {
        return needsQuotes(s, inList) ? Snbt.quote(s) : s;
    }

    private static boolean needsQuotes(String s, boolean inList) {
        if (s.isEmpty() || !s.equals(s.strip()) || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return true;
        }
        char c = s.charAt(0);
        if (c == '{' || c == '[' || c == '"' || c == '\'' || c == '#') {
            return true;
        }
        if (inList && (s.contains(": ") || s.endsWith(":") || c == '-')) {
            return true;
        }
        Nbt bare = Snbt.scalar(s);
        if (bare != null && !(bare instanceof Nbt.Str) || looksLikeBadNumber(s)) {
            return true;
        }
        return OPERATION.matcher(s).matches();
    }

    /** A key bare when the parser would read it back, otherwise quoted. */
    static String key(String k) {
        if (k.isEmpty() || !k.equals(k.strip()) || k.contains(": ") || k.endsWith(":") || k.contains("\n")) {
            return Snbt.quote(k);
        }
        char c = k.charAt(0);
        return c == '"' || c == '\'' || c == '#' || c == '-' || c == '{' || c == '[' ? Snbt.quote(k) : k;
    }

    // ---- reading -------------------------------------------------------------------------------

    /** Parses outline text. Empty text is an empty compound. */
    public static Nbt read(String text) throws SnbtException {
        List<Line> lines = lines(text);
        if (lines.isEmpty()) {
            return new Nbt.Compound();
        }
        Parser p = new Parser(lines);
        int indent = lines.getFirst().indent;
        for (Line l : lines) {
            if (l.indent < indent) {
                throw new SnbtException("this line is indented less than the first line", -1, l.number);
            }
        }
        Nbt result;
        Line first = lines.getFirst();
        if (lines.size() == 1 && !first.isListItem() && splitKey(first.text) == null) {
            result = value(first.text, first.number);
            p.pos = 1;
        } else {
            result = p.block(indent);
        }
        if (p.pos < lines.size()) {
            Line l = lines.get(p.pos);
            throw new SnbtException("unexpected indentation", -1, l.number);
        }
        return result;
    }

    /** Parses outline text that must be a compound. */
    public static Nbt.Compound readCompound(String text) throws SnbtException {
        Nbt n = read(text);
        if (n instanceof Nbt.Compound c) {
            return c;
        }
        throw new SnbtException("expected keys and values (key: value)", -1, 1);
    }

    private static final class Line {
        int indent;
        String text;
        final int number;

        Line(int indent, String text, int number) {
            this.indent = indent;
            this.text = text;
            this.number = number;
        }

        boolean isListItem() {
            return text.equals("-") || text.startsWith("- ");
        }
    }

    private static List<Line> lines(String text) {
        List<Line> out = new ArrayList<>();
        String[] raw = text.split("\r?\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].replace("\t", "  ");
            String stripped = s.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (s.charAt(indent) == ' ') {
                indent++;
            }
            out.add(new Line(indent, stripped, i + 1));
        }
        return out;
    }

    private static final class Parser {
        final List<Line> lines;
        int pos;

        Parser(List<Line> lines) {
            this.lines = lines;
        }

        Nbt block(int indent) throws SnbtException {
            return lines.get(pos).isListItem() ? list(indent) : compound(indent);
        }

        Nbt.Compound compound(int indent) throws SnbtException {
            Nbt.Compound c = new Nbt.Compound();
            while (pos < lines.size()) {
                Line l = lines.get(pos);
                if (l.indent < indent) {
                    break;
                }
                if (l.indent > indent) {
                    throw new SnbtException("unexpected indentation", -1, l.number);
                }
                if (l.isListItem()) {
                    throw new SnbtException("a list item ('-') among keys; indent it under a key", -1, l.number);
                }
                String[] kv = splitKey(l.text);
                if (kv == null) {
                    throw new SnbtException("expected 'key: value'", -1, l.number);
                }
                String key = unquoteKey(kv[0], l.number);
                if (c.has(key)) {
                    throw new SnbtException("duplicate key " + key, -1, l.number);
                }
                pos++;
                c.put(key, rest(kv[1], indent, l.number));
            }
            return c;
        }

        Nbt.List list(int indent) throws SnbtException {
            Nbt.List list = new Nbt.List();
            while (pos < lines.size()) {
                Line l = lines.get(pos);
                if (l.indent < indent) {
                    break;
                }
                if (l.indent > indent) {
                    throw new SnbtException("unexpected indentation", -1, l.number);
                }
                if (!l.isListItem()) {
                    throw new SnbtException("expected a list item ('- ...')", -1, l.number);
                }
                String content = l.text.equals("-") ? "" : l.text.substring(2).stripLeading();
                if (content.isEmpty()) {
                    pos++;
                    list.add(nested(indent, l.number, true));
                } else if (splitKey(content) != null) {
                    // "- key: value": the item is a compound whose keys line up after the dash.
                    l.indent = indent + (l.text.length() - content.length());
                    l.text = content;
                    list.add(compound(l.indent));
                } else {
                    pos++;
                    list.add(value(content, l.number));
                }
            }
            return list;
        }

        /** What follows "key:": an inline value, or the indented block below. */
        private Nbt rest(String after, int indent, int lineNo) throws SnbtException {
            if (!after.isEmpty()) {
                return value(after, lineNo);
            }
            return nested(indent, lineNo, false);
        }

        private Nbt nested(int indent, int lineNo, boolean listItem) throws SnbtException {
            if (pos < lines.size() && lines.get(pos).indent > indent) {
                return block(lines.get(pos).indent);
            }
            // "key:" (or "-") with nothing under it is an empty compound, like a unit component.
            return new Nbt.Compound();
        }
    }

    /** Parses one inline value: SNBT for containers and quoted strings, else a scalar or raw text. */
    static Nbt value(String text, int lineNo) throws SnbtException {
        String v = text.strip();
        char c = v.isEmpty() ? ' ' : v.charAt(0);
        if (c == '{' || c == '[' || c == '"' || c == '\'' || OPERATION.matcher(v).matches()) {
            try {
                return Snbt.parse(v);
            } catch (SnbtException e) {
                Nbt.List loose = c == '[' ? looseList(v, lineNo) : null;
                if (loose != null) {
                    return loose;
                }
                throw new SnbtException(e.reason, e.offset, lineNo);
            }
        }
        try {
            Nbt bare = Snbt.scalar(v);
            if (bare != null) {
                return bare;
            }
        } catch (IllegalArgumentException e) {
            throw new SnbtException(e.getMessage(), -1, lineNo);
        }
        if (looksLikeBadNumber(v)) {
            throw new SnbtException("'" + v + "' is not a valid number (quote it for text)", -1, lineNo);
        }
        return new Nbt.Str(v);
    }

    /**
     * {@code [minecraft:stone, Lore line]}: a flat list of outline scalars, which SNBT would
     * reject (colons, spaces). Null if the text has nesting or quotes and is simply wrong SNBT.
     */
    private static Nbt.List looseList(String v, int lineNo) throws SnbtException {
        if (!v.endsWith("]") || v.length() < 2) {
            return null;
        }
        String body = v.substring(1, v.length() - 1);
        if (body.matches("\\s*[BILbil]\\s*;.*") || body.matches(".*[\\[\\]{}\"'].*")) {
            return null;
        }
        Nbt.List l = new Nbt.List();
        if (body.isBlank()) {
            return l;
        }
        for (String part : body.split(",", -1)) {
            if (part.isBlank()) {
                return null;
            }
            l.add(value(part, lineNo));
        }
        return l;
    }

    static boolean looksLikeBadNumber(String v) {
        return v.matches("[+-]?[0-9][0-9_.]*[A-Za-z]{0,2}") && !v.matches(".*\\s.*");
    }

    /** Splits "key: value" / "key:" at the first ": " (or a trailing ':'); null if neither. */
    static String[] splitKey(String text) {
        if (text.startsWith("\"") || text.startsWith("'")) {
            char q = text.charAt(0);
            for (int i = 1; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\\') {
                    i++;
                } else if (ch == q) {
                    String after = text.substring(i + 1);
                    if (after.equals(":")) {
                        return new String[]{text.substring(0, i + 1), ""};
                    }
                    if (after.startsWith(": ")) {
                        return new String[]{text.substring(0, i + 1), after.substring(2).strip()};
                    }
                    return null;
                }
            }
            return null;
        }
        char c = text.isEmpty() ? ' ' : text.charAt(0);
        if (c == '{' || c == '[' || c == '-' || c == '#') {
            return null;
        }
        int i = text.indexOf(": ");
        if (i > 0) {
            return new String[]{text.substring(0, i), text.substring(i + 2).strip()};
        }
        if (text.endsWith(":") && text.length() > 1) {
            return new String[]{text.substring(0, text.length() - 1), ""};
        }
        return null;
    }

    private static String unquoteKey(String k, int lineNo) throws SnbtException {
        if (k.startsWith("\"") || k.startsWith("'")) {
            try {
                return ((Nbt.Str) Snbt.parse(k)).value();
            } catch (SnbtException | ClassCastException e) {
                throw new SnbtException("bad quoted key", -1, lineNo);
            }
        }
        return k.strip();
    }
}
