package io.github.tjhgibbs.stacksmith.core.outline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.tjhgibbs.stacksmith.core.outline.Highlighter.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class HighlighterTest {
    private static List<Highlighter.Kind> kinds(String line) {
        return Highlighter.line(line).stream().map(Highlighter.Span::kind).toList();
    }

    @Test
    void keysAndScalars() {
        assertEquals(List.of(KEY, PUNCT, TEXT), kinds("  text: Excalibur sword"));
        assertEquals(List.of(KEY, PUNCT, NUMBER), kinds("minecraft:sharpness: 5"));
        assertEquals(List.of(KEY, PUNCT, BOOL), kinds("italic: false"));
        assertEquals(List.of(DASH, KEY, PUNCT, ID), kinds("- type: minecraft:attack_damage"));
        assertEquals(List.of(COMMENT), kinds("  # hi: there"));
        assertEquals(List.of(KEY, PUNCT), kinds("lore:"));
    }

    @Test
    void inlineSnbt() {
        assertEquals(List.of(KEY, PUNCT, PUNCT, KEY, PUNCT, NUMBER, PUNCT, STRING, PUNCT), kinds("a: {x: 1, \"s\"}"));
        assertEquals(List.of(KEY, PUNCT, PUNCT, ID, PUNCT, TEXT, PUNCT), kinds("b: [minecraft:stone, dirt]"));
    }

    @Test
    void spansCoverTheirText() {
        String l = "  name: {text:\"hi\", color: gold}";
        for (Highlighter.Span s : Highlighter.line(l)) {
            assertTrue(s.start() >= 0 && s.end() <= l.length() && s.start() < s.end(), s.toString());
        }
    }
}
