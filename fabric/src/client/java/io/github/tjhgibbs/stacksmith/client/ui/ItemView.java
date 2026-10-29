package io.github.tjhgibbs.stacksmith.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Drawing items: scaled icons, and the real in-game tooltip laid out as a panel. */
public final class ItemView {
    private ItemView() {
    }

    /** The item icon at any size ({@code size} in GUI pixels; 16 is normal). */
    public static void icon(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (size == 16) {
            g.item(stack, x, y);
            return;
        }
        float s = size / 16f;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
    }

    /** The tooltip exactly as the game builds it, wrapped to {@code width}. */
    public static List<FormattedCharSequence> tooltip(ItemStack stack, int width) {
        Minecraft mc = Minecraft.getInstance();
        List<FormattedCharSequence> out = new ArrayList<>();
        if (stack == null || stack.isEmpty() || mc.player == null) {
            return out;
        }
        for (Component line : Screen.getTooltipFromItem(mc, stack)) {
            List<FormattedCharSequence> split = mc.font.split(line, width);
            out.addAll(split.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : split);
        }
        return out;
    }

    /** Draws tooltip lines in a vanilla-like purple-edged box; returns the height used. */
    public static int tooltipPanel(GuiGraphicsExtractor g, List<FormattedCharSequence> lines, int x, int y, int width, int maxHeight) {
        if (lines.isEmpty()) {
            return 0;
        }
        Font font = Minecraft.getInstance().font;
        int h = Math.min(maxHeight, lines.size() * 10 + 6 + (lines.size() > 1 ? 2 : 0));
        g.fill(x, y, x + width, y + h, 0xF0100010);
        g.outline(x, y, width, h, 0xFF2D0A63);
        g.outline(x + 1, y + 1, width - 2, h - 2, 0x505000FF);
        int ty = y + 4;
        for (int i = 0; i < lines.size(); i++) {
            if (ty + 9 > y + h) {
                break;
            }
            g.text(font, lines.get(i), x + 4, ty, 0xFFFFFFFF, true);
            ty += i == 0 ? 12 : 10;
        }
        return h;
    }
}
