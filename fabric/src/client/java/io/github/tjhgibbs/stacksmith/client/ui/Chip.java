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
    /** Background colour of a main action button, or 0 for the usual flat look. */
    private int primary = 0;
    private boolean leftAlign;
    private boolean keepsFocus;
    private net.minecraft.world.item.ItemStack icon;

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

    /** A main action: filled with {@code c}, white text, brighter when hovered. */
    public Chip primary(int c) {
        primary = c;
        textColor = 0xFFFFFFFF;
        return this;
    }

    /** Clicking doesn't take focus (toolbar buttons acting on a text box keep it focused). */
    public Chip keepsFocus() {
        keepsFocus = true;
        return this;
    }

    public boolean isKeepingFocus() {
        return keepsFocus;
    }

    /** An item drawn at the left (16x16; make the chip at least 18 high). */
    public Chip icon(net.minecraft.world.item.ItemStack stack) {
        icon = stack;
        leftAlign = true;
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
        if (primary != 0 && active) {
            bg = isHoveredOrFocused() ? lighter(primary) : primary;
        }
        g.fill(x, y, x + w, y + h, bg);
        g.outline(x, y, w, h, primary != 0 && active ? lighter(lighter(primary))
                : sel ? 0xFF5C8DBF : isHoveredOrFocused() && active ? 0xFF56636F : Theme.BORDER);
        if (accent != 0) {
            g.fill(x + 1, y + 1, x + 3, y + h - 1, accent);
        }
        Font font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        int iconW = 0;
        if (icon != null) {
            iconW = 18;
            if (!icon.isEmpty()) {
                g.item(icon, x + 2 + (accent != 0 ? 3 : 0), y + (h - 16) / 2);
            }
        }
        int room = w - iconW - (leftAlign ? 8 + (accent != 0 ? 3 : 0) : 4);
        if (font.width(text) > room) {
            text = font.plainSubstrByWidth(text, room - font.width("…")) + "…";
        }
        int color = !active ? Theme.TEXT_FAINT : textColor;
        int ty = y + (h - 8) / 2;
        boolean shadow = primary != 0 && active;
        if (leftAlign) {
            g.text(font, text, x + 4 + iconW + (accent != 0 ? 3 : 0), ty, color, shadow);
        } else {
            g.text(font, text, x + (w - font.width(text)) / 2, ty, color, shadow);
        }
    }

    private static int lighter(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28), gr = Math.min(255, ((argb >> 8) & 0xFF) + 28), b = Math.min(255, (argb & 0xFF) + 28);
        return (argb & 0xFF000000) | r << 16 | gr << 8 | b;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
