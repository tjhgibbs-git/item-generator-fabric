package io.github.tjhgibbs.stacksmith.core.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextModelTest {
    @Test
    void typingAndUndoMerge() {
        TextModel m = new TextModel();
        for (char c : "hello".toCharArray()) {
            m.type(String.valueOf(c));
        }
        m.type(" ");
        m.type("w");
        assertEquals("hello w", m.text());
        m.undo();
        assertEquals("hello ", m.text());
        m.undo();
        assertEquals("hello", m.text());
        m.undo();
        assertEquals("", m.text());
        m.redo();
        assertEquals("hello", m.text());
    }

    @Test
    void newlineIndentsAfterColonAndContinuesLists() {
        TextModel m = new TextModel("lore:");
        m.end(false);
        m.newline();
        m.type("- one");
        m.newline();
        m.type("two");
        assertEquals("lore:\n  - one\n  - two", m.text());
    }

    @Test
    void tabIndentsSelectionAndShiftTabOutdents() {
        TextModel m = new TextModel("a:\nb: 1\nc: 2");
        m.moveTo(new TextModel.Pos(1, 0), false);
        m.moveTo(new TextModel.Pos(2, 1), true);
        m.indent();
        assertEquals("a:\n  b: 1\n  c: 2", m.text());
        m.outdent();
        assertEquals("a:\nb: 1\nc: 2", m.text());
    }

    @Test
    void backspaceRemovesWholeIndentStep() {
        TextModel m = new TextModel("    x");
        m.moveTo(new TextModel.Pos(0, 4), false);
        m.backspace(false);
        assertEquals("  x", m.text());
    }

    @Test
    void multiLinePasteAndSelectionDelete() {
        TextModel m = new TextModel("ab");
        m.moveTo(new TextModel.Pos(0, 1), false);
        m.paste("1\n2\n3");
        assertEquals("a1\n2\n3b", m.text());
        assertEquals(new TextModel.Pos(2, 1), m.caret());
        m.moveTo(new TextModel.Pos(0, 1), true);
        assertEquals("1\n2\n3", m.selectedText());
        m.backspace(false);
        assertEquals("ab", m.text());
    }

    @Test
    void commentToggleAndMoveLines() {
        TextModel m = new TextModel("a: 1\n  b: 2");
        m.selectAll();
        m.toggleComment();
        assertEquals("# a: 1\n  # b: 2", m.text());
        m.toggleComment();
        assertEquals("a: 1\n  b: 2", m.text());
        m.moveTo(new TextModel.Pos(1, 0), false);
        m.moveLines(-1);
        assertEquals("  b: 2\na: 1", m.text());
    }

    @Test
    void verticalMovementKeepsColumn() {
        TextModel m = new TextModel("long line\nx\nanother line");
        m.moveTo(new TextModel.Pos(0, 7), false);
        m.down(false);
        assertEquals(new TextModel.Pos(1, 1), m.caret());
        m.down(false);
        assertEquals(new TextModel.Pos(2, 7), m.caret());
    }

    @Test
    void wordMotion() {
        TextModel m = new TextModel("foo_bar: baz");
        m.right(false, true);
        assertEquals(7, m.caret().col());
        m.end(false);
        m.backspace(true);
        assertEquals("foo_bar: ", m.text());
    }
}
