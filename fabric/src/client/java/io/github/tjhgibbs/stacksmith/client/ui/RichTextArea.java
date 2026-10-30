package io.github.tjhgibbs.stacksmith.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.core.edit.TextModel;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
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
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Text with {@code &}-codes, drawn the way the game will draw it: every line in its colours and
 * styles, with the codes hidden except on the line being edited (where they show, dimmed, so
 * they can be changed). One line per lore line; {@link #singleLine} for names.
 *
 * <p>Formatting is applied with {@link #applyCode}: it wraps the selection (or starts at the
 * caret), which is what the toolbar buttons and Ctrl+B/I/U do.
 */
public final class RichTextArea extends AbstractWidget {
    public static final int LINE_H = 11;
    private static final int PAD = 4;
    private static final String CODES = "0123456789abcdefklmnor";

    private final TextModel model;
    private final Font font = Minecraft.getInstance().font;
    private final boolean singleLine;
    private int minLines = 1, maxLines = 12;
    private int scrollLine, scrollX;
    private int seenVersion;
    private int baseColor = 0xFFFFFF;
    private String placeholder = "";
    private Consumer<List<String>> onChange = l -> {
    };
    private Runnable onFocus = () -> {
    };
    private long lastInput;

    public RichTextArea(String text, boolean singleLine) {
        super(0, 0, 100, 0, Component.literal("Text"));
        this.singleLine = singleLine;
        this.model = new TextModel(singleLine ? text.replace("\n", " ") : text);
        seenVersion = model.version();
        if (singleLine) {
            minLines = maxLines = 1;
        }
        fitHeight();
    }

    /** The colour of unformatted text (lore is dark purple in game, names white). */
    public RichTextArea baseColor(int rgb) {
        baseColor = rgb & 0xFFFFFF;
        return this;
    }

    public RichTextArea lines(int min, int max) {
        if (!singleLine) {
            minLines = min;
            maxLines = max;
            fitHeight();
        }
        return this;
    }

    public RichTextArea placeholder(String p) {
        placeholder = p;
        return this;
    }

    /** Called with every line after each edit. */
    public RichTextArea onChange(Consumer<List<String>> listener) {
        onChange = listener;
        return this;
    }

    /** Called when this area gains focus (so a shared toolbar knows where to type). */
    public RichTextArea onFocus(Runnable r) {
        onFocus = r;
        return this;
    }

    public List<String> lines() {
        return model.lines();
    }

    public String text() {
        return model.text();
    }

    private void fitHeight() {
        int lines = Math.max(minLines, Math.min(maxLines, model.lineCount() + (singleLine ? 0 : 1)));
        setHeight(lines * LINE_H + PAD * 2 - 2);
    }

    private int visibleLines() {
        return Math.max(1, (getHeight() - PAD * 2 + 2) / LINE_H);
    }

    private int textX() {
        return getX() + PAD;
    }

    private int textWidth() {
        return getWidth() - PAD * 2;
    }

    // ---- editing from outside ---------------------------------------------------------------

    /**
     * Applies a formatting code ({@code "&6"}, {@code "&l"}, {@code "&#ff8800"}): around the
     * selection, ending with {@code &r} and whatever style the text had before; or at the caret.
     */
    public void applyCode(String code) {
        if (model.hasSelection()) {
            TextModel.Pos end = model.selEnd();
            String before = styleCodesAt(model.line(end.line()), end.col());
            String sel = model.selectedText();
            String wrapped = code + sel.replace("\n", "\n" + code) + "&r" + before;
            model.type(wrapped);
        } else {
            model.type(code);
        }
        lastInput = System.currentTimeMillis();
        edited();
    }

    /** Codes that recreate the style in effect at {@code col} of {@code line}. */
    private static String styleCodesAt(String line, int col) {
        String color = null;
        StringBuilder flags = new StringBuilder();
        for (Token t : tokens(line)) {
            if (t.start >= col) {
                break;
            }
            if (t.code != null) {
                char c = t.code.charAt(1);
                if (c == '#' || "0123456789abcdef".indexOf(c) >= 0) {
                    color = t.code;
                    flags.setLength(0);
                } else if (c == 'r') {
                    color = null;
                    flags.setLength(0);
                } else if (flags.indexOf(t.code) < 0) {
                    flags.append(t.code);
                }
            }
        }
        return (color == null ? "" : color) + flags;
    }

    private void edited() {
        if (model.version() != seenVersion) {
            seenVersion = model.version();
            fitHeight();
            onChange.accept(model.lines());
        }
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
        int[] xs = layout(model.line(c.line()), true);
        int cx = xs[Math.min(c.col(), xs.length - 1)];
        int tw = textWidth();
        if (cx - scrollX > tw - 6) {
            scrollX = cx - tw + 20;
        } else if (cx - scrollX < 0) {
            scrollX = Math.max(0, cx - 20);
        }
    }

    // ---- parsing and layout --------------------------------------------------------------------

    /** A formatting code ({@code code} set) or one plain character. */
    private record Token(int start, int end, String code) {
    }

    private static List<Token> tokens(String line) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < line.length()) {
                char n = Character.toLowerCase(line.charAt(i + 1));
                if (n == '#' && i + 8 <= line.length() && line.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    out.add(new Token(i, i + 8, "&#" + line.substring(i + 2, i + 8).toLowerCase(Locale.ROOT)));
                    i += 8;
                    continue;
                }
                if (CODES.indexOf(n) >= 0) {
                    out.add(new Token(i, i + 2, "&" + n));
                    i += 2;
                    continue;
                }
                if (n == '&' && c == '&') {
                    out.add(new Token(i, i + 2, null)); // "&&" is a literal &
                    i += 2;
                    continue;
                }
            }
            out.add(new Token(i, i + 1, null));
            i++;
        }
        return out;
    }

    private Style apply(Style s, String code) {
        char c = code.charAt(1);
        if (c == '#') {
            return Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(code.substring(2), 16))).withItalic(false);
        }
        int ci = "0123456789abcdef".indexOf(c);
        if (ci >= 0) {
            return Style.EMPTY.withColor(TextColor.fromRgb(TextMarkup.COLOR_RGB[ci])).withItalic(false);
        }
        return switch (c) {
            case 'l' -> s.withBold(true);
            case 'o' -> s.withItalic(true);
            case 'n' -> s.withUnderlined(true);
            case 'm' -> s.withStrikethrough(true);
            case 'k' -> s.withObfuscated(true);
            default -> base();
        };
    }

    private Style base() {
        return Style.EMPTY.withColor(TextColor.fromRgb(baseColor)).withItalic(false);
    }

    /** X offset (from the text start, unscrolled) of every column 0..length. */
    private int[] layout(String line, boolean showCodes) {
        int[] xs = new int[line.length() + 1];
        int x = 0;
        Style style = base();
        for (Token t : tokens(line)) {
            for (int k = t.start; k < t.end; k++) {
                xs[k] = x;
            }
            if (t.code != null) {
                if (showCodes) {
                    // The codes' columns spread over their drawn width, so the caret can sit inside.
                    String raw = line.substring(t.start, t.end);
                    for (int k = t.start; k < t.end; k++) {
                        xs[k] = x + font.width(raw.substring(0, k - t.start));
                    }
                    x += font.width(raw);
                }
                style = apply(style, t.code);
            } else {
                String ch = t.end - t.start == 2 ? "&" : line.substring(t.start, t.end);
                x += font.width(Component.literal(ch).setStyle(style.withObfuscated(false)));
            }
        }
        xs[line.length()] = x;
        return xs;
    }

    private boolean showCodes(int line) {
        if (!isFocused()) {
            return false;
        }
        return line >= model.selStart().line() && line <= model.selEnd().line();
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x, y, x + w, y + h, 0xFF0D1114);
        g.outline(x, y, w, h, isFocused() ? Theme.BORDER_FOCUS : isHovered() ? 0xFF56636F : Theme.BORDER);
        int tx = textX();
        int vis = visibleLines();
        if (model.lineCount() == 1 && model.line(0).isEmpty() && !placeholder.isEmpty() && !isFocused()) {
            g.text(font, placeholder, tx, y + PAD, Theme.TEXT_FAINT, false);
        }
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        TextModel.Pos s = model.selStart(), e = model.selEnd();
        for (int i = 0; i < vis && scrollLine + i < model.lineCount(); i++) {
            int li = scrollLine + i;
            int ly = y + PAD + i * LINE_H;
            String line = model.line(li);
            boolean codes = showCodes(li);
            int[] xs = layout(line, codes);
            if (model.hasSelection() && li >= s.line() && li <= e.line()) {
                int a = li == s.line() ? s.col() : 0;
                int b = li == e.line() ? e.col() : line.length();
                g.fill(tx - scrollX + xs[a], ly - 1, tx - scrollX + xs[b] + (li != e.line() ? 3 : 0), ly + LINE_H - 1, Theme.SELECTION);
            }
            if (!singleLine && model.lineCount() > 1 && !isFocused()) {
                // A faint marker where each lore line starts, so empty lines are visible.
                g.fill(x + 1, ly, x + 2, ly + 8, 0x30FFFFFF);
            }
            Style style = base();
            for (Token t : tokens(line)) {
                int cx = tx - scrollX + xs[t.start];
                if (t.code != null) {
                    if (codes) {
                        g.text(font, line.substring(t.start, t.end), cx, ly, codeColor(t.code), false);
                    }
                    style = apply(style, t.code);
                } else {
                    String ch = t.end - t.start == 2 ? "&" : line.substring(t.start, t.end);
                    Style drawn = codes ? style.withObfuscated(false) : style;
                    g.text(font, Component.literal(ch).setStyle(drawn).getVisualOrderText(), cx, ly, 0xFFFFFFFF, true);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (isFocused() && (now - lastInput < 600 || now / 500 % 2 == 0)) {
            TextModel.Pos c = model.caret();
            if (c.line() >= scrollLine && c.line() < scrollLine + vis) {
                int[] xs = layout(model.line(c.line()), true);
                int cx = tx - scrollX + xs[Math.min(c.col(), xs.length - 1)];
                int cy = y + PAD + (c.line() - scrollLine) * LINE_H;
                g.fill(cx, cy - 1, cx + 1, cy + LINE_H - 2, Theme.CARET);
            }
        }
        g.disableScissor();
        if (model.lineCount() > vis) {
            int track = h - 2;
            int thumb = Math.max(8, track * vis / model.lineCount());
            int ty = y + 1 + (track - thumb) * scrollLine / Math.max(1, model.lineCount() - vis);
            g.fill(x + w - 3, ty, x + w - 1, ty + thumb, 0xFF6A7682);
        }
    }

    /** Codes are drawn dimmed, tinted with the colour they set. */
    private static int codeColor(String code) {
        char c = code.charAt(1);
        int rgb;
        if (c == '#') {
            rgb = Integer.parseInt(code.substring(2), 16);
        } else if ("0123456789abcdef".indexOf(c) >= 0) {
            rgb = TextMarkup.COLOR_RGB["0123456789abcdef".indexOf(c)];
        } else {
            return 0xFF8C9AA6;
        }
        // Mix toward grey so dark colours stay readable on the dark field.
        int r = ((rgb >> 16 & 255) + 0x80) / 2, gg = ((rgb >> 8 & 255) + 0x80) / 2, b = ((rgb & 255) + 0x80) / 2;
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    // ---- input ------------------------------------------------------------------------------------

    private TextModel.Pos posAt(double mx, double my) {
        int line = scrollLine + (int) Math.floor((my - getY() - PAD + 1) / LINE_H);
        line = Math.max(0, Math.min(model.lineCount() - 1, line));
        String l = model.line(line);
        int[] xs = layout(l, showCodes(line));
        int target = (int) Math.round(mx - textX() + scrollX);
        int col = 0;
        while (col < l.length() && target >= (xs[col] + xs[col + 1]) / 2) {
            col++;
        }
        return new TextModel.Pos(line, col);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean was = isFocused();
        super.setFocused(focused);
        // 26.3's SDL input only delivers typed characters while text input is on for a focused owner.
        if (focused != was) {
            Minecraft.getInstance().onTextInputFocusChange(this, focused);
        }
        if (focused && !was) {
            onFocus.run();
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        TextModel.Pos p = posAt(event.x(), event.y());
        if (doubleClick) {
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
        scrollLine = Math.max(0, Math.min(model.lineCount() - visibleLines(), scrollLine - (int) Math.signum(sy)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (!isFocused()) {
            return false;
        }
        boolean shift = e.hasShiftDown(), ctrl = e.hasControlDown();
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
            String clip = kb.getClipboard();
            model.paste(singleLine ? clip.replace("\r", "").replace("\n", " ") : clip);
        } else {
            switch (e.key()) {
                case InputConstants.KEY_LEFT -> model.left(shift, ctrl);
                case InputConstants.KEY_RIGHT -> model.right(shift, ctrl);
                case InputConstants.KEY_UP -> {
                    if (singleLine || model.caret().line() == 0 && !shift) {
                        return false;
                    }
                    model.up(shift);
                }
                case InputConstants.KEY_DOWN -> {
                    if (singleLine || model.caret().line() == model.lineCount() - 1 && !shift) {
                        return false;
                    }
                    model.down(shift);
                }
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
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    if (singleLine) {
                        return false;
                    }
                    model.type("\n");
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
                case InputConstants.KEY_B, InputConstants.KEY_I, InputConstants.KEY_U -> {
                    if (!ctrl) {
                        return false;
                    }
                    applyCode(e.key() == InputConstants.KEY_B ? "&l" : e.key() == InputConstants.KEY_I ? "&o" : "&n");
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        lastInput = System.currentTimeMillis();
        edited();
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (!isFocused() || !e.isAllowedChatCharacter()) {
            return false;
        }
        model.type(e.codepointAsString());
        lastInput = System.currentTimeMillis();
        edited();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal("Text editor, line " + (model.caret().line() + 1)));
    }
}
