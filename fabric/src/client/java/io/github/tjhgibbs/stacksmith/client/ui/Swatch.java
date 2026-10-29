package io.github.tjhgibbs.stacksmith.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** A clickable colour square (dye colours, text colours). */
public final class Swatch extends AbstractButton {
    private final int rgb;
    private final Runnable onPress;
    private final BooleanSupplier selected;
    private boolean keepsFocus;

    public Swatch(int rgb, String name, int size, BooleanSupplier selected, Runnable onPress) {
        super(0, 0, size, size, Component.literal(name));
        this.rgb = rgb;
        this.onPress = onPress;
        this.selected = selected;
        setTooltip(Tooltip.create(Component.literal(name)));
    }

    /** Clicking doesn't take focus (see {@link Chip#keepsFocus()}). */
    public Swatch keepsFocus() {
        keepsFocus = true;
        return this;
    }

    public boolean isKeepingFocus() {
        return keepsFocus;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), s = getWidth();
        g.fill(x, y, x + s, y + getHeight(), 0xFF000000 | rgb);
        boolean sel = selected.getAsBoolean();
        g.outline(x, y, s, getHeight(), sel ? 0xFFFFFFFF : isHoveredOrFocused() ? 0xFFB0B0B0 : 0xFF000000);
        if (sel) {
            g.outline(x + 1, y + 1, s - 2, getHeight() - 2, 0xFF000000);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
