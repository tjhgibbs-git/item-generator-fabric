package io.github.tjhgibbs.stacksmith.core.outline;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;

import java.util.ArrayList;
import java.util.List;

/**
 * Colours one line of outline text for the editor. Purely lexical (each line on its own), so it
 * is cheap enough to run every frame and never fails; errors are shown separately.
 */
public final class Highlighter {
    public enum Kind { KEY, PUNCT, STRING, TEXT, NUMBER, BOOL, ID, COMMENT, DASH }

    /** {@code [start, end)} of a line, in chars. */
    public record Span(int start, int end, Kind kind) {
    }

    private Highlighter() {
    }

    public static List<Span> line(String line) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        if (i >= line.length()) {
            return out;
        }
        if (line.charAt(i) == '#') {
            out.add(new Span(i, line.length(), Kind.COMMENT));
            return out;
        }
        if (line.startsWith("- ", i) || line.substring(i).equals("-")) {
            out.add(new Span(i, i + 1, Kind.DASH));
            i += 1;
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
        }
        String content = line.substring(i);
        String[] kv = Outline.splitKey(content);
        if (kv != null) {
            int keyEnd = i + kv[0].length();
            out.add(new Span(i, keyEnd, Kind.KEY));
            out.add(new Span(keyEnd, keyEnd + 1, Kind.PUNCT));
            int v = keyEnd + 1;
            while (v < line.length() && line.charAt(v) == ' ') {
                v++;
            }
            value(line, v, out);
        } else {
            value(line, i, out);
        }
        return out;
    }

    private static void value(String line, int from, List<Span> out) {
        if (from >= line.length()) {
            return;
        }
        String v = line.substring(from).stripTrailing();
        char c = v.charAt(0);
        if (c == '{' || c == '[' || c == '"' || c == '\'') {
            snbt(line, from, out);
            return;
        }
        out.add(new Span(from, from + v.length(), scalarKind(v)));
    }

    private static Kind scalarKind(String v) {
        Nbt n;
        try {
            n = Snbt.scalar(v);
        } catch (IllegalArgumentException e) {
            return Kind.NUMBER;
        }
        if (n instanceof Nbt.Num) {
            return v.equals("true") || v.equals("false") ? Kind.BOOL : Kind.NUMBER;
        }
        return Ids.valid(v) ? Kind.ID : Kind.TEXT;
    }

    /** Tokenises inline SNBT: punctuation, quoted strings, bare words (keys before ':'). */
    private static void snbt(String line, int from, List<Span> out) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < line.length() && line.charAt(j) != c) {
                    j += line.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(line.length(), j + 1);
                out.add(new Span(i, j, Kind.STRING));
                i = j;
            } else if ("{}[],:;".indexOf(c) >= 0) {
                out.add(new Span(i, i + 1, Kind.PUNCT));
                i++;
            } else if (c == ' ') {
                i++;
            } else {
                int j = i;
                while (j < line.length() && "{}[],:;\"' ".indexOf(line.charAt(j)) < 0) {
                    j++;
                }
                // A bare word followed by ':' is a key, except inside a namespaced id (minecraft:x).
                int k = j;
                while (k < line.length() && line.charAt(k) == ' ') {
                    k++;
                }
                boolean key = k < line.length() && line.charAt(k) == ':' && (k + 1 >= line.length() || !Character.isLetter(line.charAt(k + 1)));
                if (!key && j < line.length() && line.charAt(j) == ':' && j + 1 < line.length() && Character.isLetter(line.charAt(j + 1))) {
                    // namespaced id: swallow "ns:path"
                    int e = j + 1;
                    while (e < line.length() && "{}[],:;\"' ".indexOf(line.charAt(e)) < 0) {
                        e++;
                    }
                    out.add(new Span(i, e, Kind.ID));
                    i = e;
                    continue;
                }
                out.add(new Span(i, j, key ? Kind.KEY : scalarKind(line.substring(i, j))));
                i = j;
            }
        }
    }
}
