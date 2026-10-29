package io.github.tjhgibbs.stacksmith.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrolling column of rows. Each row has a height (which may change while editing) and lays
 * out its own widgets. Widgets stay ordinary screen children (focus and keyboard work as
 * usual); the pane positions them, draws them clipped to its box, and hides those scrolled out.
 *
 * <p>The screen must add the widgets with {@code addWidget} (not as renderables), call
 * {@link #extract} where the pane should appear, and wrap clicks with {@link #guardClick}.
 */
public final class ScrollPane {
    /** A row: lays out at (x, y, width) and returns its height; draws its own background. */
    public interface Row {
        int layout(int x, int y, int width);

        default void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
        }

        default List<AbstractWidget> widgets() {
            return List.of();
        }
    }

    public static final int SCROLLBAR = 5;
    private final List<Row> rows = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();
    private int x, y, w, h;
    private double scroll;
    private int content;
    private boolean draggingBar;

    public void bounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void clear() {
        rows.clear();
    }

    public <R extends Row> R add(R row) {
        rows.add(row);
        return row;
    }

    public List<Row> rows() {
        return rows;
    }

    public int innerWidth() {
        return w - SCROLLBAR - 3;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public double scroll() {
        return scroll;
    }

    public void scroll(double s) {
        scroll = Math.max(0, Math.min(s, maxScroll()));
    }

    public int maxScroll() {
        return Math.max(0, content - h);
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Positions every row for the current scroll. Cheap; call every frame. */
    public void layout() {
        placed.clear();
        int cy = 0;
        int iw = innerWidth();
        for (Row r : rows) {
            int top = y + cy - (int) Math.round(scroll);
            int rh = r.layout(x, top, iw);
            placed.add(new int[]{top, rh});
            for (AbstractWidget wd : r.widgets()) {
                wd.visible = wd.getY() + wd.getHeight() > y && wd.getY() < y + h;
            }
            cy += rh;
        }
        content = cy;
        if (scroll > maxScroll()) {
            scroll = maxScroll();
        }
    }

    /** Scrolls so a row is fully visible (e.g. one just added). */
    public void reveal(Row row) {
        int cy = 0;
        for (Row r : rows) {
            int rh = placed.size() > rows.indexOf(r) ? placed.get(rows.indexOf(r))[1] : 20;
            if (r == row) {
                if (cy < scroll) {
                    scroll(cy);
                } else if (cy + rh > scroll + h) {
                    scroll(cy + rh - h);
                }
                return;
            }
            cy += rh;
        }
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!contains(mx, my) || maxScroll() == 0) {
            return false;
        }
        scroll(scroll - amount * 24);
        layout();
        return true;
    }

    public boolean mouseClickedBar(double mx, double my) {
        if (maxScroll() > 0 && mx >= x + w - SCROLLBAR - 1 && mx < x + w && my >= y && my < y + h) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        return false;
    }

    public boolean mouseDraggedBar(double my) {
        if (draggingBar) {
            dragBar(my);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        draggingBar = false;
    }

    private void dragBar(double my) {
        scroll((my - y) / h * content - h / 2.0);
        layout();
    }

    /**
     * Runs a click with pane widgets hidden when the pointer is outside the pane, so a widget
     * scrolled half out of view can't be clicked through the panels around it.
     */
    public boolean guardClick(double mx, double my, java.util.function.BooleanSupplier click) {
        if (contains(mx, my)) {
            return click.getAsBoolean();
        }
        List<AbstractWidget> hidden = new ArrayList<>();
        for (Row r : rows) {
            for (AbstractWidget wd : r.widgets()) {
                if (wd.visible) {
                    wd.visible = false;
                    hidden.add(wd);
                }
            }
        }
        try {
            return click.getAsBoolean();
        } finally {
            hidden.forEach(wd -> wd.visible = true);
        }
    }

    /** Draws rows, their widgets (clipped) and the scrollbar. */
    public void extract(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        layout();
        g.enableScissor(x, y, x + w, y + h);
        int iw = innerWidth();
        for (int i = 0; i < rows.size(); i++) {
            int[] p = placed.get(i);
            if (p[0] + p[1] < y || p[0] > y + h) {
                continue;
            }
            Row r = rows.get(i);
            r.extract(g, x, p[0], iw, p[1], mouseX, mouseY);
            for (AbstractWidget wd : r.widgets()) {
                if (wd.visible) {
                    wd.extractRenderState(g, mouseX, mouseY, partialTick);
                }
            }
        }
        g.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int thumb = Math.max(14, h * h / content);
            int ty = y + (int) ((h - thumb) * scroll / max);
            int sx = x + w - SCROLLBAR;
            g.fill(sx, y, sx + SCROLLBAR, y + h, 0x40000000);
            g.fill(sx, ty, sx + SCROLLBAR, ty + thumb, 0xFF6A7682);
            g.fill(sx, ty, sx + SCROLLBAR - 1, ty + thumb - 1, 0xFFA0AAB4);
        }
    }
}
