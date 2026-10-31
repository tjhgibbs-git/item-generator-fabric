package io.github.tjhgibbs.stacksmith.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.core.Ids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A text box for ids with a dropdown of matches (registry ids, tags...). Typing filters;
 * Up/Down choose, Tab or Enter accept, a click picks. {@code minecraft:} may be left out. The
 * dropdown is drawn by the screen on top of everything ({@link #extractOverlay}) and gets the
 * first look at clicks ({@link #clickOverlay}).
 */
public final class SuggestBox extends EditBox {
    private static final int ROWS = 8, ROW_H = 11;
    /** The box whose dropdown is open; only one at a time. */
    private static SuggestBox open;

    private final Supplier<List<String>> source;
    private final List<String> matches = new ArrayList<>();
    private int selected;
    private int scroll;
    private Consumer<String> onAccept = s -> {
    };
    private boolean suppress;
    /** Free-text mode (see {@link #onSubmit}): called with what was added; null otherwise. */
    private Consumer<String> onSubmit;
    /** Whether ↑ ↓ moved the selection since the last typing (free-text Enter then takes it). */
    private boolean navigated;

    public SuggestBox(Font font, int width, int height, Component label, Supplier<List<String>> source) {
        super(font, 0, 0, width, height, label);
        this.source = source;
        setMaxLength(512);
        setResponder(s -> refresh());
    }

    /** Called when the value changes by typing or by picking (with the full id when picked). */
    public SuggestBox onAccept(Consumer<String> listener) {
        this.onAccept = listener;
        setResponder(s -> {
            refresh();
            listener.accept(s);
        });
        return this;
    }

    /**
     * Free text with suggestions, e.g. tags: Enter submits what was typed (unless ↑ ↓ picked a
     * suggestion), Tab or a click submits the suggestion. The box is cleared after each.
     */
    public SuggestBox onSubmit(Consumer<String> listener) {
        this.onSubmit = listener;
        return this;
    }

    private void submit(String value) {
        if (!value.isBlank()) {
            onSubmit.accept(value.strip());
        }
        setValueQuietly("");
        refresh();
    }

    public void setValueQuietly(String v) {
        suppress = true;
        setValue(v);
        suppress = false;
        matches.clear();
    }

    private void refresh() {
        if (suppress) {
            return;
        }
        matches.clear();
        navigated = false;
        String q = getValue().strip().toLowerCase(Locale.ROOT);
        List<String> all = source.get();
        if (q.isEmpty() && isFocused()) {
            matches.addAll(all); // an empty box lists everything
        } else if (!q.isEmpty()) {
            String brief = Ids.brief(q);
            List<String> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (String id : all) {
                // Lower-cased: tags may have capitals, typed or not.
                String lid = id.toLowerCase(Locale.ROOT), b = Ids.brief(lid);
                if (lid.equals(q) || b.equals(q)) {
                    continue;
                }
                if (b.startsWith(brief) || lid.startsWith(q)) {
                    starts.add(id);
                } else if (lid.contains(brief)) {
                    contains.add(id);
                }
            }
            matches.addAll(starts);
            matches.addAll(contains);
        }
        selected = 0;
        scroll = 0;
        if (isFocused() && !matches.isEmpty()) {
            open = this;
        } else if (open == this) {
            open = null;
        }
        String first = matches.isEmpty() ? "" : Ids.brief(matches.getFirst());
        setSuggestion(!first.isEmpty() && first.toLowerCase(Locale.ROOT).startsWith(Ids.brief(q)) && !q.isEmpty()
                ? first.substring(Ids.brief(q).length()) : "");
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused && open == this) {
            open = null;
        } else if (focused && getValue().isBlank()) {
            refresh();
        }
    }

    private void accept(String id) {
        suppress = true;
        setValue(Ids.brief(id));
        suppress = false;
        matches.clear();
        setSuggestion("");
        if (open == this) {
            open = null;
        }
        onAccept.accept(getValue());
        if (onSubmit != null) {
            submit(getValue());
        }
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (open == this && !matches.isEmpty()) {
            switch (e.key()) {
                case InputConstants.KEY_DOWN -> {
                    navigated = true;
                    selected = Math.min(matches.size() - 1, selected + 1);
                    if (selected >= scroll + ROWS) {
                        scroll = selected - ROWS + 1;
                    }
                    return true;
                }
                case InputConstants.KEY_UP -> {
                    navigated = true;
                    selected = Math.max(0, selected - 1);
                    if (selected < scroll) {
                        scroll = selected;
                    }
                    return true;
                }
                case InputConstants.KEY_TAB, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    if (onSubmit != null && e.key() != InputConstants.KEY_TAB && !navigated) {
                        submit(getValue());
                    } else {
                        accept(matches.get(selected));
                    }
                    return true;
                }
                default -> {
                }
            }
        }
        if (onSubmit != null && (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER)) {
            submit(getValue());
            return true;
        }
        return super.keyPressed(e);
    }

    private int[] overlayRect() {
        int n = Math.min(ROWS, matches.size());
        Font font = Minecraft.getInstance().font;
        int w = getWidth();
        for (int i = scroll; i < scroll + n; i++) {
            w = Math.max(w, font.width(Ids.brief(matches.get(i))) + 8);
        }
        int h = n * ROW_H + 2;
        int y = getY() + getHeight() + 1;
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (y + h > screenH - 2) {
            y = getY() - h - 1;
        }
        return new int[]{getX(), y, w, h};
    }

    /** Draws the open dropdown, if any. Call last in the screen's render. */
    public static void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SuggestBox b = open;
        if (b == null || !b.isFocused() || !b.visible || b.matches.isEmpty()) {
            return;
        }
        g.nextStratum();
        Font font = Minecraft.getInstance().font;
        int[] r = b.overlayRect();
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xF0101418);
        g.outline(r[0], r[1], r[2], r[3], Theme.BORDER);
        int n = Math.min(ROWS, b.matches.size());
        for (int i = 0; i < n; i++) {
            int idx = b.scroll + i;
            int ry = r[1] + 1 + i * ROW_H;
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= ry && mouseY < ry + ROW_H;
            if (idx == b.selected || hover) {
                g.fill(r[0] + 1, ry, r[0] + r[2] - 1, ry + ROW_H, idx == b.selected ? 0xFF2E4A66 : 0x30FFFFFF);
            }
            String id = b.matches.get(idx);
            g.text(font, Ids.brief(id), r[0] + 4, ry + 2, id.startsWith("#") ? Theme.ACCENT : Theme.TEXT, false);
        }
        if (b.matches.size() > ROWS) {
            String more = (b.matches.size() - ROWS) + " more";
            g.text(font, more, r[0] + r[2] - font.width(more) - 3, r[1] + r[3] - 10, Theme.TEXT_FAINT, false);
        }
    }

    /** Handles a click on the open dropdown; true if it was consumed. */
    public static boolean clickOverlay(double mx, double my) {
        SuggestBox b = open;
        if (b == null || !b.isFocused() || b.matches.isEmpty()) {
            return false;
        }
        int[] r = b.overlayRect();
        if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            int i = b.scroll + (int) ((my - r[1] - 1) / ROW_H);
            if (i >= 0 && i < b.matches.size()) {
                b.accept(b.matches.get(i));
            }
            return true;
        }
        return false;
    }

    /** Scrolls the open dropdown under the pointer; true if consumed. */
    public static boolean scrollOverlay(double mx, double my, double amount) {
        SuggestBox b = open;
        if (b == null || !b.isFocused() || b.matches.size() <= ROWS) {
            return false;
        }
        int[] r = b.overlayRect();
        if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            b.scroll = Math.max(0, Math.min(b.matches.size() - ROWS, b.scroll - (int) Math.signum(amount)));
            return true;
        }
        return false;
    }

    public static void closeAll() {
        open = null;
    }
}
