package io.github.tjhgibbs.stacksmith.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.core.edit.TextModel;
import io.github.tjhgibbs.stacksmith.core.outline.Highlighter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A small code editor for outline text: line numbers, syntax colours, the error line marked,
 * selection, undo/redo, auto-indent, Tab/Shift+Tab, Ctrl+/ comments, Alt+Up/Down to move lines,
 * Ctrl+D to duplicate. Grows with its content between {@code minLines} and {@code maxLines},
 * then scrolls. Editing logic is core's {@link TextModel}; this class maps input and draws.
 */
public final class CodeArea extends AbstractWidget {
    public static final int LINE_H = 10;
    private static final int PAD = 3;

    private final TextModel model;
    private final Font font = Minecraft.getInstance().font;
    private int minLines = 3, maxLines = 14;
    private int scrollLine;
    private int scrollX;
    private int seenVersion = -1;
    private Consumer<String> onChange = s -> {
    };
    private int errorLine = -1;
    private String placeholder = "";
    private long lastInput;

    public CodeArea(String text, int width) {
        super(0, 0, width, 0, Component.literal("Outline editor"));
        this.model = new TextModel(text);
        this.seenVersion = model.version();
        fitHeight();
    }

    public TextModel model() {
        return model;
    }

    public String text() {
        return model.text();
    }

    /** Replaces the text from outside (not undoable when {@code load}, else one undo step). */
    public void setText(String text, boolean load) {
        if (load) {
            model.setText(text);
        } else {
            model.replaceAll(text);
        }
        seenVersion = model.version();
        fitHeight();
    }

    public CodeArea lines(int min, int max) {
        minLines = min;
        maxLines = max;
        fitHeight();
        return this;
    }

    public CodeArea onChange(Consumer<String> listener) {
        onChange = listener;
        return this;
    }

    public CodeArea placeholder(String p) {
        placeholder = p;
        return this;
    }

    /** 1-based line to mark as wrong, or 0 for none. */
    public void errorLine(int line) {
        errorLine = line - 1;
    }

    private void fitHeight() {
        int lines = Math.max(minLines, Math.min(maxLines, model.lineCount()));
        setHeight(lines * LINE_H + PAD * 2);
    }

    private int visibleLines() {
        return (getHeight() - PAD * 2) / LINE_H;
    }

    private int gutterWidth() {
        return font.width(Integer.toString(Math.max(10, model.lineCount()))) + 6;
    }

    private int textX() {
        return getX() + gutterWidth() + PAD;
    }

    private int textWidth() {
        return getWidth() - gutterWidth() - PAD * 2;
    }

    /** Called after every edit: notify, resize, keep the caret in view. */
    private void edited() {
        if (model.version() != seenVersion) {
            seenVersion = model.version();
            fitHeight();
            onChange.accept(model.text());
        }
        lastInput = System.currentTimeMillis();
        reveal();
    }

    private void reveal() {
        TextModel.Pos c = model.caret();
        int vis = visibleLines();
        if (c.line() < scrollLine) {
            scrollLine = c.line();
        } else if (c.line() >= scrollLine + vis) {
            scrollLine = c.line() - vis + 1;
        }
        scrollLine = Math.max(0, Math.min(scrollLine, Math.max(0, model.lineCount() - vis)));
        String line = model.line(c.line());
        int cx = font.width(line.substring(0, Math.min(c.col(), line.length())));
        int tw = textWidth();
        if (cx - scrollX > tw - 8) {
            scrollX = cx - tw + 24;
        } else if (cx - scrollX < 0) {
            scrollX = Math.max(0, cx - 24);
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x, y, x + w, y + h, 0xFF0D1114);
        int gw = gutterWidth();
        g.fill(x, y, x + gw, y + h, Theme.GUTTER);
        g.outline(x, y, w, h, isFocused() ? Theme.BORDER_FOCUS : Theme.BORDER);
        int tx = textX(), tw = textWidth();
        int vis = visibleLines();
        TextModel.Pos s = model.selStart(), e = model.selEnd();
        boolean sel = model.hasSelection();
        if (model.lineCount() == 1 && model.line(0).isEmpty() && !placeholder.isEmpty() && !isFocused()) {
            g.text(font, placeholder, tx, y + PAD + 1, Theme.TEXT_FAINT, false);
        }
        g.enableScissor(x + gw + 1, y + 1, x + w - 1, y + h - 1);
        for (int i = 0; i < vis && scrollLine + i < model.lineCount(); i++) {
            int li = scrollLine + i;
            int ly = y + PAD + i * LINE_H;
            String line = model.line(li);
            if (li == errorLine) {
                g.fill(x + gw + 1, ly - 1, x + w - 1, ly + LINE_H - 1, Theme.ERROR_LINE);
            }
            if (sel && li >= s.line() && li <= e.line()) {
                int a = li == s.line() ? s.col() : 0;
                int b = li == e.line() ? e.col() : line.length();
                int ax = tx - scrollX + font.width(line.substring(0, Math.min(a, line.length())));
                int bx = tx - scrollX + font.width(line.substring(0, Math.min(b, line.length()))) + (li != e.line() ? 3 : 0);
                g.fill(ax, ly - 1, bx, ly + LINE_H - 1, Theme.SELECTION);
            }
            drawLine(g, line, tx - scrollX, ly);
        }
        long now = System.currentTimeMillis();
        if (isFocused() && (now - lastInput < 600 || now / 500 % 2 == 0)) {
            TextModel.Pos c = model.caret();
            if (c.line() >= scrollLine && c.line() < scrollLine + vis) {
                String line = model.line(c.line());
                int cx = tx - scrollX + font.width(line.substring(0, Math.min(c.col(), line.length())));
                int cy = y + PAD + (c.line() - scrollLine) * LINE_H;
                g.fill(cx, cy - 1, cx + 1, cy + LINE_H - 1, Theme.CARET);
            }
        }
        g.disableScissor();
        for (int i = 0; i < vis && scrollLine + i < model.lineCount(); i++) {
            int li = scrollLine + i;
            String n = Integer.toString(li + 1);
            g.text(font, n, x + gw - 3 - font.width(n), y + PAD + i * LINE_H, li == errorLine ? Theme.BAD : Theme.TEXT_FAINT, false);
        }
        if (model.lineCount() > vis) {
            int track = h - 2;
            int thumb = Math.max(8, track * vis / model.lineCount());
            int ty = y + 1 + (track - thumb) * scrollLine / Math.max(1, model.lineCount() - vis);
            g.fill(x + w - 3, ty, x + w - 1, ty + thumb, 0xFF6A7682);
        }
    }

    private void drawLine(GuiGraphicsExtractor g, String line, int x, int y) {
        int at = 0;
        int cx = x;
        for (Highlighter.Span span : Highlighter.line(line)) {
            if (span.start() > at) {
                String gap = line.substring(at, span.start());
                g.text(font, gap, cx, y, Theme.TEXT, false);
                cx += font.width(gap);
            }
            String part = line.substring(span.start(), span.end());
            g.text(font, part, cx, y, Theme.syntax(span.kind()), false);
            cx += font.width(part);
            at = span.end();
        }
        if (at < line.length()) {
            g.text(font, line.substring(at), cx, y, Theme.TEXT, false);
        }
    }

    // ---- input ---------------------------------------------------------------------------------

    private TextModel.Pos posAt(double mx, double my) {
        int line = scrollLine + (int) Math.floor((my - getY() - PAD) / LINE_H);
        line = Math.max(0, Math.min(model.lineCount() - 1, line));
        String l = model.line(line);
        int target = (int) Math.round(mx - textX() + scrollX);
        int col = 0;
        while (col < l.length()) {
            int a = font.width(l.substring(0, col)), b = font.width(l.substring(0, col + 1));
            if (target < (a + b) / 2) {
                break;
            }
            col++;
        }
        return new TextModel.Pos(line, col);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        TextModel.Pos p = posAt(event.x(), event.y());
        if (event.x() < textX() - PAD) {
            model.selectLine(p.line());
        } else if (doubleClick) {
            model.selectWord(p);
        } else {
            model.moveTo(p, event.hasShiftDown());
        }
        lastInput = System.currentTimeMillis();
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        model.moveTo(posAt(event.x(), event.y()), true);
        reveal();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my) || model.lineCount() <= visibleLines()) {
            return false;
        }
        scrollLine = Math.max(0, Math.min(model.lineCount() - visibleLines(), scrollLine - (int) Math.signum(sy) * 2));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (!isFocused()) {
            return false;
        }
        boolean shift = e.hasShiftDown(), ctrl = e.hasControlDown(), alt = e.hasAltDown();
        var kb = Minecraft.getInstance().keyboardHandler;
        if (e.isSelectAll()) {
            model.selectAll();
        } else if (e.isCopy()) {
            if (model.hasSelection()) {
                kb.setClipboard(model.selectedText());
            }
            return true;
        } else if (e.isCut()) {
            kb.setClipboard(model.cut());
        } else if (e.isPaste()) {
            model.paste(kb.getClipboard());
        } else {
            switch (e.key()) {
                case InputConstants.KEY_LEFT -> model.left(shift, ctrl);
                case InputConstants.KEY_RIGHT -> model.right(shift, ctrl);
                case InputConstants.KEY_UP -> {
                    if (alt) {
                        model.moveLines(-1);
                    } else {
                        model.up(shift);
                    }
                }
                case InputConstants.KEY_DOWN -> {
                    if (alt) {
                        model.moveLines(1);
                    } else {
                        model.down(shift);
                    }
                }
                case InputConstants.KEY_PAGEUP -> model.vertical(-visibleLines(), shift);
                case InputConstants.KEY_PAGEDOWN -> model.vertical(visibleLines(), shift);
                case InputConstants.KEY_HOME -> {
                    if (ctrl) {
                        model.docStart(shift);
                    } else {
                        model.home(shift);
                    }
                }
                case InputConstants.KEY_END -> {
                    if (ctrl) {
                        model.docEnd(shift);
                    } else {
                        model.end(shift);
                    }
                }
                case InputConstants.KEY_BACKSPACE -> model.backspace(ctrl);
                case InputConstants.KEY_DELETE -> model.delete(ctrl);
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> model.newline();
                case InputConstants.KEY_TAB -> {
                    if (shift) {
                        model.outdent();
                    } else {
                        model.indent();
                    }
                }
                case InputConstants.KEY_Z -> {
                    if (!ctrl) {
                        return false;
                    }
                    if (shift) {
                        model.redo();
                    } else {
                        model.undo();
                    }
                }
                case InputConstants.KEY_Y -> {
                    if (!ctrl) {
                        return false;
                    }
                    model.redo();
                }
                case InputConstants.KEY_D -> {
                    if (!ctrl) {
                        return false;
                    }
                    model.duplicate();
                }
                case InputConstants.KEY_SLASH -> {
                    if (!ctrl) {
                        return false;
                    }
                    model.toggleComment();
                }
                default -> {
                    return false;
                }
            }
        }
        edited();
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (!isFocused() || !e.isAllowedChatCharacter()) {
            return false;
        }
        model.type(e.codepointAsString());
        edited();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal("Outline editor, line " + (model.caret().line() + 1)));
    }
}
