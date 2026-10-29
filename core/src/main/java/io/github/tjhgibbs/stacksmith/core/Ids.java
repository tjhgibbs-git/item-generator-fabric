package io.github.tjhgibbs.stacksmith.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** Namespaced ids ({@code minecraft:stone}): normalising, shortening, checking. */
public final class Ids {
    public static final String MINECRAFT = "minecraft:";
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_.\\-/]+");

    private Ids() {
    }

    /** {@code stone} → {@code minecraft:stone}; ids with a namespace are kept. Lower-cases. */
    public static String full(String id) {
        String s = id.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) {
            return "#" + full(s.substring(1));
        }
        return s.indexOf(':') >= 0 ? s : MINECRAFT + s;
    }

    /** {@code minecraft:stone} → {@code stone}; other namespaces are kept. */
    public static String brief(String id) {
        if (id.startsWith("#")) {
            return "#" + brief(id.substring(1));
        }
        return id.startsWith(MINECRAFT) ? id.substring(MINECRAFT.length()) : id;
    }

    public static boolean valid(String id) {
        return VALID.matcher(id.startsWith("#") ? id.substring(1) : id).matches();
    }

    /** "diamond_sword" → "Diamond Sword", for lists when no translation is at hand. */
    public static String pretty(String id) {
        String s = brief(id);
        int slash = s.lastIndexOf('/');
        s = (slash >= 0 ? s.substring(slash + 1) : s).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean up = true;
        for (char c : s.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        return sb.toString();
    }
}
