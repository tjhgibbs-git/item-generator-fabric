package io.github.tjhgibbs.stacksmith.client.edit;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A titled box in the editor's middle column: a coloured stripe for its group, a title (hover
 * for help), optional ↺/✕ buttons, and a body laid out by the subclass.
 */
public abstract class Card implements ScrollPane.Row {
    public static final int HEADER = 15, PAD = 4;

    protected final Font font = Minecraft.getInstance().font;
    private final String title;
    private final int accent;
    private final String about;
    private final List<Chip> headerButtons = new ArrayList<>();
    private final List<AbstractWidget> body = new ArrayList<>();
    private int titleX, titleY, titleW;
    /** A short status shown at the right of the title (e.g. "3 lines", an error). */
    protected String status = "";
    protected int statusColor = Theme.TEXT_DIM;

    protected Card(String title, int accent, String about) {
        this.title = title;
        this.accent = accent;
        this.about = about;
    }

    /** Adds a small button to the header's right end (added right to left). */
    protected Chip headerButton(String label, String tip, Runnable action) {
        Chip c = Chip.of(label, 13, 11, action).tip(tip);
        headerButtons.add(c);
        return c;
    }

    protected <W extends AbstractWidget> W body(W widget) {
        body.add(widget);
        return widget;
    }

    /** Positions body widgets inside (x, y, w); returns the body height (0 for none). */
    protected abstract int layoutBody(int x, int y, int w);

    /** Extra drawing for the body (labels, previews). */
    protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
    }

    private int bodyX, bodyY, bodyW;

    @Override
    public final int layout(int x, int y, int width) {
        int bx = x + width - 2;
        for (Chip c : headerButtons) {
            bx -= c.getWidth();
            c.setX(bx);
            c.setY(y + 2);
            bx -= 2;
        }
        titleX = x + 7;
        titleY = y + 4;
        titleW = bx - titleX - 4;
        bodyX = x + PAD + 3;
        bodyY = y + HEADER + 2;
        bodyW = width - PAD * 2 - 3;
        int bh = layoutBody(bodyX, bodyY, bodyW);
        return HEADER + (bh > 0 ? bh + PAD + 2 : 0) + 3;
    }

    @Override
    public final void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
        int h = height - 3;
        g.fill(x, y, x + width, y + h, Theme.CARD);
        g.fill(x, y, x + width, y + HEADER, Theme.CARD_HEADER);
        g.fill(x, y, x + 3, y + h, accent);
        g.outline(x, y, width, h, Theme.BORDER);
        String t = title;
        int sw = status.isEmpty() ? 0 : font.width(status) + 6;
        int room = titleW - sw;
        if (font.width(t) > room) {
            t = font.plainSubstrByWidth(t, Math.max(0, room - font.width("…"))) + "…";
        }
        g.text(font, t, titleX, titleY, Theme.TEXT, false);
        if (!status.isEmpty()) {
            g.text(font, status, titleX + titleW - font.width(status), titleY, statusColor, false);
        }
        if (!about.isEmpty() && mouseX >= titleX && mouseX < titleX + font.width(t) && mouseY >= y && mouseY < y + HEADER) {
            g.setTooltipForNextFrame(font, Component.literal(about), mouseX, mouseY);
        }
        extractBody(g, bodyX, bodyY, bodyW, mouseX, mouseY);
    }

    @Override
    public List<AbstractWidget> widgets() {
        List<AbstractWidget> all = new ArrayList<>(headerButtons);
        all.addAll(body);
        return all;
    }
}
