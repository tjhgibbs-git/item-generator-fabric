package io.github.tjhgibbs.stacksmith.core.text;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Names and lore typed with formatting codes, the way players already know them:
 * {@code &6&lGolden &rblade}, {@code &#ff8800orange}. Converts to and from text components (as
 * SNBT trees), so the editor can offer one line of text instead of a component builder.
 *
 * <table>
 *   <tr><td>{@code &0-&9 &a-&f}</td><td>colours (black ... white)</td></tr>
 *   <tr><td>{@code &#rrggbb}</td><td>any colour</td></tr>
 *   <tr><td>{@code &l &o &n &m &k}</td><td>bold, italic, underlined, strikethrough, obfuscated</td></tr>
 *   <tr><td>{@code &r}</td><td>back to plain</td></tr>
 *   <tr><td>{@code &&}</td><td>a literal {@code &}</td></tr>
 * </table>
 * {@code §} works in place of {@code &}.
 */
public final class TextMarkup {
    public static final List<String> COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    /** RGB of each named colour, for previews and the colour picker. */
    public static final int[] COLOR_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String CODES = "0123456789abcdef";
    private static final Map<Character, String> FLAGS = Map.of('l', "bold", 'o', "italic", 'n', "underlined", 'm', "strikethrough", 'k', "obfuscated");
    private static final List<String> FLAG_ORDER = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

    private TextMarkup() {
    }

    /** A run of text with one style. {@code color} is a name, {@code #rrggbb}, or null. */
    public record Segment(String text, String color, boolean bold, boolean italic, boolean underlined, boolean strikethrough,
                          boolean obfuscated) {
        boolean flag(String name) {
            return switch (name) {
                case "bold" -> bold;
                case "italic" -> italic;
                case "underlined" -> underlined;
                case "strikethrough" -> strikethrough;
                default -> obfuscated;
            };
        }

        boolean sameStyle(Segment o) {
            return Objects.equals(color, o.color) && bold == o.bold && italic == o.italic && underlined == o.underlined
                    && strikethrough == o.strikethrough && obfuscated == o.obfuscated;
        }
    }

    /** Splits markup into styled runs. Unknown codes are kept as text. */
    public static List<Segment> segments(String markup) {
        List<Segment> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String color = null;
        boolean[] f = new boolean[5];
        for (int i = 0; i < markup.length(); i++) {
            char c = markup.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < markup.length()) {
                char n = Character.toLowerCase(markup.charAt(i + 1));
                String newColor = null;
                int skip = 1;
                boolean code = true;
                if (n == '&' && c == '&') {
                    text.append('&');
                    i++;
                    continue;
                } else if (CODES.indexOf(n) >= 0) {
                    newColor = COLORS.get(CODES.indexOf(n));
                } else if (n == '#' && i + 8 <= markup.length() && markup.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    newColor = "#" + markup.substring(i + 2, i + 8).toUpperCase(Locale.ROOT);
                    skip = 7;
                } else if (FLAGS.containsKey(n) || n == 'r') {
                    // handled below
                } else {
                    code = false;
                }
                if (code) {
                    flush(out, text, color, f);
                    if (newColor != null) {
                        // A colour code resets formatting, as in vanilla's legacy codes.
                        color = newColor;
                        f = new boolean[5];
                    } else if (n == 'r') {
                        color = null;
                        f = new boolean[5];
                    } else {
                        f[FLAG_ORDER.indexOf(FLAGS.get(n))] = true;
                    }
                    i += skip;
                    continue;
                }
            }
            text.append(c);
        }
        flush(out, text, color, f);
        return out;
    }

    private static void flush(List<Segment> out, StringBuilder text, String color, boolean[] f) {
        if (text.isEmpty()) {
            return;
        }
        Segment s = new Segment(text.toString(), color, f[0], f[1], f[2], f[3], f[4]);
        if (!out.isEmpty() && out.getLast().sameStyle(s)) {
            Segment last = out.removeLast();
            s = new Segment(last.text() + s.text(), color, f[0], f[1], f[2], f[3], f[4]);
        }
        out.add(s);
        text.setLength(0);
    }

    /**
     * Markup to a text component. With {@code upright}, text is explicitly not italic unless
     * {@code &o} says so: item names and lore are italic by default and nobody wants that.
     */
    public static Nbt toComponent(String markup, boolean upright) {
        List<Segment> segs = segments(markup);
        if (segs.isEmpty()) {
            return upright ? new Nbt.Compound().put("text", "").put("italic", Nbt.bool(false)) : new Nbt.Str("");
        }
        if (segs.size() == 1) {
            Nbt.Compound c = style(segs.getFirst(), upright);
            return c.size() == 1 ? new Nbt.Str(segs.getFirst().text()) : c;
        }
        // Children inherit only from the parent, so one upright parent covers every run.
        Nbt.Compound root = new Nbt.Compound().put("text", "");
        if (upright) {
            root.put("italic", Nbt.bool(false));
        }
        Nbt.List extra = new Nbt.List();
        for (Segment s : segs) {
            Nbt.Compound c = style(s, false);
            extra.add(c.size() == 1 ? new Nbt.Str(s.text()) : c);
        }
        return root.put("extra", extra);
    }

    private static Nbt.Compound style(Segment s, boolean upright) {
        Nbt.Compound c = new Nbt.Compound().put("text", s.text());
        if (s.color() != null) {
            c.put("color", s.color().toLowerCase(Locale.ROOT));
        }
        for (String flag : FLAG_ORDER) {
            if (s.flag(flag)) {
                c.put(flag, Nbt.bool(true));
            }
        }
        if (upright && !s.italic()) {
            c.put("italic", Nbt.bool(false));
        }
        return c;
    }

    private static final java.util.Set<String> UNDERSTOOD = java.util.Set.of("text", "type", "color", "extra", "bold", "italic",
            "underlined", "strikethrough", "obfuscated");

    /**
     * A text component back to markup, or null when it uses things markup can't say (translated
     * text, click events, fonts...); the editor then falls back to the outline view.
     */
    public static String fromComponent(Nbt component) {
        List<Segment> out = new ArrayList<>();
        if (!collect(component, new Segment("", null, false, false, false, false, false), out)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Segment prev = new Segment("", null, false, false, false, false, false);
        for (Segment s : out) {
            if (s.text().isEmpty()) {
                continue;
            }
            if (!s.sameStyle(prev)) {
                boolean needReset = prev.color() != null && s.color() == null || lostFlag(prev, s);
                if (s.color() != null && (!Objects.equals(s.color(), prev.color()) || needReset)) {
                    sb.append(colorCode(s.color()));
                } else if (needReset) {
                    sb.append("&r");
                }
                for (String flag : FLAG_ORDER) {
                    if (s.flag(flag) && !flag.equals("italic")) {
                        sb.append('&').append(flagCode(flag));
                    } else if (flag.equals("italic") && s.italic()) {
                        sb.append("&o");
                    }
                }
            }
            sb.append(s.text().replace("&", "&&"));
            prev = s;
        }
        return sb.toString();
    }

    private static boolean lostFlag(Segment prev, Segment s) {
        for (String f : FLAG_ORDER) {
            if (prev.flag(f) && !s.flag(f)) {
                return true;
            }
        }
        return false;
    }

    private static String colorCode(String color) {
        int i = COLORS.indexOf(color);
        return i >= 0 ? "&" + CODES.charAt(i) : "&" + color.toLowerCase(Locale.ROOT);
    }

    private static char flagCode(String flag) {
        for (var e : FLAGS.entrySet()) {
            if (e.getValue().equals(flag)) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException(flag);
    }

    private static boolean collect(Nbt n, Segment inherited, List<Segment> out) {
        switch (n) {
            case Nbt.Str s -> {
                out.add(new Segment(s.value(), inherited.color(), inherited.bold(), inherited.italic(), inherited.underlined(),
                        inherited.strikethrough(), inherited.obfuscated()));
                return true;
            }
            case Nbt.List l -> {
                // A list is its first element with the rest as its children.
                if (l.isEmpty()) {
                    return true;
                }
                Nbt.Compound root = l.get(0) instanceof Nbt.Compound c ? c.copy() : new Nbt.Compound().put("text", textOf(l.get(0)));
                if (root.get("text") == null && !(l.get(0) instanceof Nbt.Compound)) {
                    return false;
                }
                Nbt.List extra = root.childList("extra");
                for (int i = 1; i < l.size(); i++) {
                    extra.add(l.get(i));
                }
                return collect(root, inherited, out);
            }
            case Nbt.Compound c -> {
                for (String k : c.keys()) {
                    if (!UNDERSTOOD.contains(k)) {
                        return false;
                    }
                }
                if (c.has("type") && !c.getString("type", "").equals("text")) {
                    return false;
                }
                String color = c.getString("color", inherited.color());
                if (color != null && !COLORS.contains(color) && !color.matches("#[0-9a-fA-F]{6}")) {
                    return false;
                }
                Segment self = new Segment(c.getString("text", ""), color,
                        c.getBool("bold", inherited.bold()), c.getBool("italic", inherited.italic()),
                        c.getBool("underlined", inherited.underlined()), c.getBool("strikethrough", inherited.strikethrough()),
                        c.getBool("obfuscated", inherited.obfuscated()));
                out.add(self);
                if (c.get("extra") instanceof Nbt.List extra) {
                    for (Nbt child : extra.items()) {
                        if (!collect(child, self, out)) {
                            return false;
                        }
                    }
                }
                return true;
            }
            case Nbt.Num num -> {
                return collect(new Nbt.Str(num.kind().integral() ? Long.toString(num.whole()) : Double.toString(num.real())), inherited, out);
            }
            default -> {
                return false;
            }
        }
    }

    private static String textOf(Nbt n) {
        return n instanceof Nbt.Str s ? s.value() : null;
    }

    /** True when every run of the component is explicitly upright (or it is empty). */
    public static boolean isUpright(Nbt component) {
        List<Segment> out = new ArrayList<>();
        if (!collect(component, new Segment("", null, false, true, false, false, false), out)) {
            return false;
        }
        return out.stream().filter(s -> !s.text().isEmpty()).noneMatch(Segment::italic);
    }

    /** The text with every code removed. */
    public static String plain(String markup) {
        StringBuilder sb = new StringBuilder();
        segments(markup).forEach(s -> sb.append(s.text()));
        return sb.toString();
    }
}
