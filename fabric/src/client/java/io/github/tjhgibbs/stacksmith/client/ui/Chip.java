package io.github.tjhgibbs.stacksmith.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A flat, compact button: list rows, tabs, the small ✕ and ↺ on cards. Draws like the rest of
 * Stacksmith's panels instead of vanilla's stone buttons, and can show a selected state.
 */
public final class Chip extends AbstractButton {
    private final Consumer<InputWithModifiers> onPress;
    private BooleanSupplier selected = () -> false;
    private int textColor = Theme.TEXT;
    private int accent = 0;
    private boolean leftAlign;

    public Chip(Component label, int width, int height, Consumer<InputWithModifiers> onPress) {
        super(0, 0, width, height, label);
        this.onPress = onPress;
    }

    public static Chip of(String label, int width, int height, Runnable onPress) {
        return new Chip(Component.literal(label), width, height, in -> onPress.run());
    }

    public Chip selected(BooleanSupplier s) {
        selected = s;
        return this;
    }

    public Chip color(int c) {
        textColor = c;
        return this;
    }

    /** A coloured stripe on the left (used for component groups). */
    public Chip accent(int c) {
        accent = c;
        return this;
    }

    public Chip left() {
        leftAlign = true;
        return this;
    }

    public Chip tip(String text) {
        setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    public Chip at(int x, int y) {
        setX(x);
        setY(y);
        return this;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.accept(input);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean sel = selected.getAsBoolean();
        int bg = !active ? 0x30000000 : sel ? 0xFF2E4A66 : isHoveredOrFocused() ? 0xFF2A333C : 0xFF1C232A;
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, sel ? 0xFF5C8DBF : isHoveredOrFocused() && active ? 0xFF56636F : Theme.BORDER);
        if (accent != 0) {
            g.fill(x + 1, y + 1, x + 3, y + h - 1, accent);
        }
        Font font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        int room = w - (leftAlign ? 8 + (accent != 0 ? 3 : 0) : 4);
        if (font.width(text) > room) {
            text = font.plainSubstrByWidth(text, room - font.width("…")) + "…";
        }
        int color = !active ? Theme.TEXT_FAINT : textColor;
        int ty = y + (h - 8) / 2;
        if (leftAlign) {
            g.text(font, text, x + 4 + (accent != 0 ? 3 : 0), ty, color, false);
        } else {
            g.text(font, text, x + (w - font.width(text)) / 2, ty, color, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
