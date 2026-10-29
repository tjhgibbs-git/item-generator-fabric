package io.github.tjhgibbs.stacksmith.client.ui;

import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Formatting buttons for a {@link RichTextArea}: the 16 text colours, any colour by hex, bold,
 * italic, underline, strikethrough, obfuscated and reset. They wrap the selection, or start the
 * style at the caret. The buttons never take focus, so typing carries on where it was.
 */
public final class FormatBar {
    private static final String[] STYLE_CODES = {"&l", "&o", "&n", "&m", "&k", "&r"};
    private static final String[] STYLE_LABELS = {"B", "I", "U", "S", "?", "R"};
    private static final String[] STYLE_TIPS = {"Bold (Ctrl+B)", "Italic (Ctrl+I)", "Underline (Ctrl+U)", "Strikethrough",
            "Obfuscated (scrambling letters)", "Reset to plain"};
    /** Colour codes in the order they look best as a palette (light to dark, by hue). */
    private static final String ORDER = "f7801234569abcde";

    private final Supplier<RichTextArea> target;
    private final List<Swatch> swatches = new ArrayList<>();
    private final List<Chip> styles = new ArrayList<>();
    private final EditBox hex;
    private final Chip hexApply;

    public FormatBar(Supplier<RichTextArea> target) {
        this.target = target;
        Font font = Minecraft.getInstance().font;
        for (char c : ORDER.toCharArray()) {
            int i = "0123456789abcdef".indexOf(c);
            String name = TextMarkup.COLORS.get(i).replace('_', ' ');
            Swatch s = new Swatch(TextMarkup.COLOR_RGB[i], name + "  &" + c, 8, () -> false, () -> apply("&" + c));
            s.keepsFocus();
            swatches.add(s);
        }
        for (int i = 0; i < STYLE_CODES.length; i++) {
            String code = STYLE_CODES[i];
            styles.add(Chip.of(STYLE_LABELS[i], 11, 11, () -> apply(code)).tip(STYLE_TIPS[i] + "  " + code).keepsFocus());
        }
        hex = new EditBox(font, 0, 0, 40, 11, Component.literal("Hex colour"));
        hex.setMaxLength(7);
        hex.setHint(Component.literal("#hex"));
        hex.setTooltip(Tooltip.create(Component.literal("Any colour: type 6 hex digits (ff8800), then ■ or Enter")));
        hex.setResponder(s -> hex.setTextColor(hexCode() != null || s.isBlank() ? Theme.TEXT : Theme.BAD));
        hexApply = Chip.of("■", 11, 11, () -> {
            String code = hexCode();
            if (code != null) {
                apply(code);
            }
        }).tip("Apply the hex colour").keepsFocus();
    }

    private String hexCode() {
        String t = hex.getValue().strip();
        t = t.startsWith("#") ? t.substring(1) : t;
        return t.matches("[0-9a-fA-F]{6}") ? "&#" + t.toLowerCase(Locale.ROOT) : null;
    }

    private void apply(String code) {
        RichTextArea area = target.get();
        if (area == null) {
            return;
        }
        area.applyCode(code);
        var screen = Minecraft.getInstance().gui.screen();
        if (screen != null && !area.isFocused()) {
            screen.setFocused(area);
        }
    }

    /** Enter in the hex box applies it. Call from the screen's key handling, or rely on the ■ button. */
    public boolean hexFocusedEnter() {
        if (hex.isFocused() && hexCode() != null) {
            apply(hexCode());
            return true;
        }
        return false;
    }

    public List<AbstractWidget> widgets() {
        List<AbstractWidget> all = new ArrayList<>(swatches);
        all.addAll(styles);
        all.add(hex);
        all.add(hexApply);
        return all;
    }

    /** Lays the bar out from (x, y) within width {@code w}; returns its height. */
    public int layout(int x, int y, int w) {
        int full = 16 * 9 + 5 + styles.size() * 12 + 5 + 34 + 12;
        int cx = x, cy = y;
        for (Swatch s : swatches) {
            s.setX(cx);
            s.setY(cy + 2);
            cx += 9;
        }
        cx += 5;
        if (full > w) {
            cx = x;
            cy += 13;
        }
        for (Chip c : styles) {
            c.setX(cx);
            c.setY(cy);
            cx += 12;
        }
        cx += 5;
        hex.setX(cx);
        hex.setY(cy);
        hex.setWidth(Math.max(34, Math.min(48, x + w - cx - 12)));
        hexApply.setX(hex.getX() + hex.getWidth() + 1);
        hexApply.setY(cy);
        return cy - y + 11;
    }
}
