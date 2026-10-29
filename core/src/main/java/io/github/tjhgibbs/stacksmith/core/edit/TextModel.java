package io.github.tjhgibbs.stacksmith.core.edit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The text behind the outline editor: lines, a caret, a selection, undo/redo, and the edits a
 * code editor needs (auto-indent, indent/outdent blocks, toggle comments). No Minecraft here;
 * the widget only maps keys and mouse positions onto these calls and draws the result.
 *
 * <p>Positions are (line, column) with columns in UTF-16 chars.
 */
public final class TextModel {
    public static final int INDENT = 2;
    private static final int UNDO_LIMIT = 200;

    public record Pos(int line, int col) implements Comparable<Pos> {
        @Override
        public int compareTo(Pos o) {
            return line != o.line ? Integer.compare(line, o.line) : Integer.compare(col, o.col);
        }
    }

    private record Snapshot(String text, Pos caret, Pos anchor) {
    }

    private final List<String> lines = new ArrayList<>();
    private Pos caret = new Pos(0, 0);
    /** Selection start; equal to the caret when nothing is selected. */
    private Pos anchor = caret;
    /** Column the caret tries to keep when moving up and down. */
    private int wantCol = -1;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    /** Typing in a row merges into one undo step. */
    private boolean merging;
    private int version;

    public TextModel() {
        lines.add("");
    }

    public TextModel(String text) {
        this();
        setText(text);
    }

    // ---- reading ---------------------------------------------------------------------------------

    public String text() {
        return String.join("\n", lines);
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int i) {
        return lines.get(i);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public Pos caret() {
        return caret;
    }

    public Pos anchor() {
        return anchor;
    }

    public boolean hasSelection() {
        return !caret.equals(anchor);
    }

    public Pos selStart() {
        return caret.compareTo(anchor) <= 0 ? caret : anchor;
    }

    public Pos selEnd() {
        return caret.compareTo(anchor) <= 0 ? anchor : caret;
    }

    public String selectedText() {
        return hasSelection() ? textBetween(selStart(), selEnd()) : "";
    }

    /** Increases on every change; widgets compare it to know when to re-validate. */
    public int version() {
        return version;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    // ---- whole-text ---------------------------------------------------------------------------

    /** Replaces everything (not undoable; for loading). Caret goes to the start. */
    public void setText(String text) {
        lines.clear();
        for (String l : text.replace("\r\n", "\n").replace('\t', ' ').split("\n", -1)) {
            lines.add(l);
        }
        caret = anchor = new Pos(0, 0);
        undo.clear();
        redo.clear();
        merging = false;
        version++;
    }

    /** Replaces everything as one undoable edit, keeping the caret near where it was. */
    public void replaceAll(String text) {
        if (text.equals(text())) {
            return;
        }
        checkpoint(false);
        Pos keep = caret;
        lines.clear();
        for (String l : text.split("\n", -1)) {
            lines.add(l);
        }
        caret = anchor = clamp(keep);
        version++;
    }

    // ---- caret -------------------------------------------------------------------------------

    public void moveTo(Pos p, boolean select) {
        caret = clamp(p);
        if (!select) {
            anchor = caret;
        }
        wantCol = -1;
        merging = false;
    }

    public void selectAll() {
        anchor = new Pos(0, 0);
        caret = new Pos(lines.size() - 1, lines.getLast().length());
        merging = false;
    }

    public void left(boolean select, boolean word) {
        if (hasSelection() && !select) {
            moveTo(selStart(), false);
            return;
        }
        Pos p = caret;
        if (p.col > 0) {
            p = new Pos(p.line, word ? wordStart(lines.get(p.line), p.col) : p.col - 1);
        } else if (p.line > 0) {
            p = new Pos(p.line - 1, lines.get(p.line - 1).length());
        }
        moveTo(p, select);
    }

    public void right(boolean select, boolean word) {
        if (hasSelection() && !select) {
            moveTo(selEnd(), false);
            return;
        }
        Pos p = caret;
        String l = lines.get(p.line);
        if (p.col < l.length()) {
            p = new Pos(p.line, word ? wordEnd(l, p.col) : p.col + 1);
        } else if (p.line < lines.size() - 1) {
            p = new Pos(p.line + 1, 0);
        }
        moveTo(p, select);
    }

    public void up(boolean select) {
        vertical(-1, select);
    }

    public void down(boolean select) {
        vertical(1, select);
    }

    public void vertical(int by, boolean select) {
        int want = wantCol >= 0 ? wantCol : caret.col;
        int line = Math.max(0, Math.min(lines.size() - 1, caret.line + by));
        Pos p = line == caret.line && by != 0 ? (by < 0 ? new Pos(0, 0) : new Pos(line, lines.get(line).length()))
                : new Pos(line, Math.min(want, lines.get(line).length()));
        caret = p;
        if (!select) {
            anchor = caret;
        }
        wantCol = want;
        merging = false;
    }

    /** Home goes to the first non-space character, then to column 0. */
    public void home(boolean select) {
        String l = lines.get(caret.line);
        int indent = indentOf(l);
        moveTo(new Pos(caret.line, caret.col == indent ? 0 : indent), select);
    }

    public void end(boolean select) {
        moveTo(new Pos(caret.line, lines.get(caret.line).length()), select);
    }

    public void docStart(boolean select) {
        moveTo(new Pos(0, 0), select);
    }

    public void docEnd(boolean select) {
        moveTo(new Pos(lines.size() - 1, lines.getLast().length()), select);
    }

    /** Selects the word (or run of punctuation) around a position, for double-click. */
    public void selectWord(Pos p) {
        Pos at = clamp(p);
        String l = lines.get(at.line);
        anchor = new Pos(at.line, wordStart(l, Math.min(l.length(), at.col + 1)));
        caret = new Pos(at.line, wordEnd(l, at.col));
        merging = false;
    }

    public void selectLine(int line) {
        int l = Math.max(0, Math.min(lines.size() - 1, line));
        anchor = new Pos(l, 0);
        caret = l + 1 < lines.size() ? new Pos(l + 1, 0) : new Pos(l, lines.get(l).length());
        merging = false;
    }

    // ---- editing -----------------------------------------------------------------------------

    /** Types text at the caret (replacing the selection). Consecutive typing is one undo step. */
    public void type(String s) {
        checkpoint(!hasSelection() && !s.contains("\n") && !s.equals(" "));
        insertRaw(s.replace("\r\n", "\n").replace('\t', ' '));
    }

    /** Pastes text as one undo step (tabs become spaces). */
    public void paste(String s) {
        checkpoint(false);
        String text = s.replace("\r\n", "\n").replace("\t", "  ");
        insertRaw(text);
    }

    /**
     * Enter: a new line indented like this one, one level deeper after a line ending in
     * {@code :} (a block opens) or after a list dash.
     */
    public void newline() {
        checkpoint(false);
        String l = lines.get(caret.line);
        String before = l.substring(0, Math.min(caret.col, l.length())).stripTrailing();
        int indent = indentOf(l);
        if (before.endsWith(":") && !before.startsWith("#")) {
            indent += INDENT;
        } else if (before.strip().equals("-")) {
            indent += INDENT;
        }
        // Continue a list: "- a" + Enter gives "- " on the next line at the same indent.
        String prefix = " ".repeat(indent);
        if (before.stripLeading().startsWith("- ") && !before.endsWith(":") && caret.col >= l.length()) {
            prefix = " ".repeat(indentOf(l)) + "- ";
        }
        insertRaw("\n" + prefix);
    }

    public void backspace(boolean word) {
        if (hasSelection()) {
            checkpoint(false);
            deleteSelection();
            return;
        }
        if (caret.col == 0 && caret.line == 0) {
            return;
        }
        checkpoint(true);
        String l = lines.get(caret.line);
        int col = caret.col;
        Pos from;
        if (col == 0) {
            from = new Pos(caret.line - 1, lines.get(caret.line - 1).length());
        } else if (word) {
            from = new Pos(caret.line, wordStart(l, col));
        } else if (col <= indentOf(l) && col % INDENT == 0 && col >= INDENT) {
            // In leading spaces, delete back to the previous indent stop.
            from = new Pos(caret.line, col - INDENT);
        } else {
            from = new Pos(caret.line, col - 1);
        }
        anchor = from;
        deleteSelection();
    }

    public void delete(boolean word) {
        if (hasSelection()) {
            checkpoint(false);
            deleteSelection();
            return;
        }
        String l = lines.get(caret.line);
        if (caret.col >= l.length() && caret.line == lines.size() - 1) {
            return;
        }
        checkpoint(true);
        anchor = caret.col < l.length() ? new Pos(caret.line, word ? wordEnd(l, caret.col) : caret.col + 1) : new Pos(caret.line + 1, 0);
        deleteSelection();
    }

    /** Tab: indent the selected lines, or insert spaces to the next indent stop. */
    public void indent() {
        checkpoint(false);
        if (hasSelection() && selStart().line != selEnd().line) {
            shiftLines(INDENT);
            return;
        }
        int col = caret.col;
        insertRaw(" ".repeat(INDENT - col % INDENT));
    }

    /** Shift+Tab: outdent the current or selected lines. */
    public void outdent() {
        checkpoint(false);
        shiftLines(-INDENT);
    }

    /** Ctrl+/: comment or uncomment the current or selected lines. */
    public void toggleComment() {
        checkpoint(false);
        int a = selStart().line, b = selEnd().line;
        if (hasSelection() && selEnd().col == 0 && b > a) {
            b--;
        }
        boolean allCommented = true;
        for (int i = a; i <= b; i++) {
            String t = lines.get(i).stripLeading();
            if (!t.isEmpty() && !t.startsWith("#")) {
                allCommented = false;
            }
        }
        for (int i = a; i <= b; i++) {
            String l = lines.get(i);
            int ind = indentOf(l);
            if (allCommented) {
                String rest = l.substring(ind);
                rest = rest.startsWith("# ") ? rest.substring(2) : rest.startsWith("#") ? rest.substring(1) : rest;
                lines.set(i, l.substring(0, ind) + rest);
            } else if (!l.isBlank()) {
                lines.set(i, l.substring(0, ind) + "# " + l.substring(ind));
            }
        }
        caret = clamp(caret);
        anchor = clamp(anchor);
        version++;
    }

    /** Ctrl+D: duplicate the current line (or selection) below. */
    public void duplicate() {
        checkpoint(false);
        if (hasSelection()) {
            String s = selectedText();
            Pos end = selEnd();
            caret = anchor = end;
            insertRaw(s);
            return;
        }
        lines.add(caret.line + 1, lines.get(caret.line));
        caret = anchor = new Pos(caret.line + 1, caret.col);
        version++;
    }

    /** Alt+Up/Down: move the current line (or selected lines) by one. */
    public void moveLines(int by) {
        int a = selStart().line, b = selEnd().line;
        if (a + by < 0 || b + by >= lines.size()) {
            return;
        }
        checkpoint(false);
        if (by < 0) {
            String moved = lines.remove(a - 1);
            lines.add(b, moved);
        } else {
            String moved = lines.remove(b + 1);
            lines.add(a, moved);
        }
        caret = new Pos(caret.line + by, caret.col);
        anchor = new Pos(anchor.line + by, anchor.col);
        version++;
    }

    public String cut() {
        String s = selectedText();
        if (!s.isEmpty()) {
            checkpoint(false);
            deleteSelection();
        }
        return s;
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(snapshot());
        restore(undo.pop());
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(snapshot());
        restore(redo.pop());
    }

    // ---- internals --------------------------------------------------------------------------

    private void checkpoint(boolean typing) {
        if (!(typing && merging)) {
            undo.push(snapshot());
            if (undo.size() > UNDO_LIMIT) {
                undo.removeLast();
            }
        }
        redo.clear();
        merging = typing;
    }

    private Snapshot snapshot() {
        return new Snapshot(text(), caret, anchor);
    }

    private void restore(Snapshot s) {
        lines.clear();
        for (String l : s.text().split("\n", -1)) {
            lines.add(l);
        }
        caret = clamp(s.caret());
        anchor = clamp(s.anchor());
        merging = false;
        version++;
    }

    private void insertRaw(String s) {
        if (hasSelection()) {
            deleteSelection();
        }
        String l = lines.get(caret.line);
        String head = l.substring(0, caret.col), tail = l.substring(caret.col);
        String[] parts = s.split("\n", -1);
        if (parts.length == 1) {
            lines.set(caret.line, head + s + tail);
            caret = new Pos(caret.line, caret.col + s.length());
        } else {
            lines.set(caret.line, head + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                lines.add(caret.line + i, parts[i]);
            }
            int last = caret.line + parts.length - 1;
            lines.add(last, parts[parts.length - 1] + tail);
            caret = new Pos(last, parts[parts.length - 1].length());
        }
        anchor = caret;
        wantCol = -1;
        version++;
    }

    private void deleteSelection() {
        Pos a = selStart(), b = selEnd();
        String head = lines.get(a.line).substring(0, a.col), tail = lines.get(b.line).substring(b.col);
        for (int i = b.line; i > a.line; i--) {
            lines.remove(i);
        }
        lines.set(a.line, head + tail);
        caret = anchor = a;
        wantCol = -1;
        version++;
    }

    private void shiftLines(int by) {
        int a = selStart().line, b = selEnd().line;
        if (hasSelection() && selEnd().col == 0 && b > a) {
            b--;
        }
        int caretShift = 0, anchorShift = 0;
        for (int i = a; i <= b; i++) {
            String l = lines.get(i);
            int delta;
            if (by > 0) {
                lines.set(i, " ".repeat(by) + l);
                delta = by;
            } else {
                int remove = Math.min(-by, indentOf(l));
                lines.set(i, l.substring(remove));
                delta = -remove;
            }
            if (i == caret.line) {
                caretShift = delta;
            }
            if (i == anchor.line) {
                anchorShift = delta;
            }
        }
        caret = clamp(new Pos(caret.line, Math.max(0, caret.col + caretShift)));
        anchor = clamp(new Pos(anchor.line, Math.max(0, anchor.col + anchorShift)));
        version++;
    }

    private String textBetween(Pos a, Pos b) {
        if (a.line == b.line) {
            return lines.get(a.line).substring(a.col, b.col);
        }
        StringBuilder sb = new StringBuilder(lines.get(a.line).substring(a.col));
        for (int i = a.line + 1; i < b.line; i++) {
            sb.append('\n').append(lines.get(i));
        }
        return sb.append('\n').append(lines.get(b.line), 0, b.col).toString();
    }

    private Pos clamp(Pos p) {
        int line = Math.max(0, Math.min(lines.size() - 1, p.line));
        return new Pos(line, Math.max(0, Math.min(lines.get(line).length(), p.col)));
    }

    public static int indentOf(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static int wordStart(String l, int col) {
        int i = Math.min(col, l.length());
        while (i > 0 && Character.isWhitespace(l.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && isWord(l.charAt(i - 1))) {
            while (i > 0 && isWord(l.charAt(i - 1))) {
                i--;
            }
        } else if (i > 0) {
            i--;
        }
        return i;
    }

    static int wordEnd(String l, int col) {
        int i = col;
        if (i < l.length() && isWord(l.charAt(i))) {
            while (i < l.length() && isWord(l.charAt(i))) {
                i++;
            }
        } else if (i < l.length()) {
            i++;
        }
        return i;
    }
}
