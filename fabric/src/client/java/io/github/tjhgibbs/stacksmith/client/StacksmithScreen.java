package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for Stacksmith's screens: scroll panes (clipped, with guarded clicks), the
 * id dropdown overlay, toasts, and panel drawing helpers.
 */
public abstract class StacksmithScreen extends Screen {
    protected static final int PAD = 6;

    protected final Screen parent;
    private final List<ScrollPane> panes = new ArrayList<>();
    private Component toast = Component.empty();
    private int toastColor = Theme.TEXT;
    private long toastUntil;

    protected StacksmithScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Registers a pane; its rows' widgets must be added with {@link #addPaneWidgets}. */
    protected ScrollPane pane(ScrollPane p) {
        panes.add(p);
        return p;
    }

    protected void addPaneWidgets(ScrollPane p) {
        for (ScrollPane.Row r : p.rows()) {
            for (AbstractWidget w : r.widgets()) {
                addWidget(w);
            }
        }
    }

    public void toast(Component message, boolean good) {
        toast = message;
        toastColor = good ? Theme.GOOD : Theme.BAD;
        toastUntil = System.currentTimeMillis() + 4000;
    }

    /** Panels behind everything; subclasses draw their frames here. */
    protected abstract void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick);

    /** Drawn above widgets and panes (previews that must not be covered). */
    protected void extractOverlays(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractPanels(g, mouseX, mouseY, partialTick);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        for (ScrollPane p : panes) {
            p.extract(g, mouseX, mouseY, partialTick);
        }
        extractOverlays(g, mouseX, mouseY, partialTick);
        SuggestBox.extractOverlay(g, mouseX, mouseY);
        if (System.currentTimeMillis() < toastUntil) {
            g.nextStratum();
            int tw = font.width(toast) + 12;
            int tx = (width - tw) / 2, ty = height - 68;
            g.fill(tx, ty, tx + tw, ty + 16, 0xE0101418);
            g.outline(tx, ty, tw, 16, toastColor);
            g.centeredText(font, toast, width / 2, ty + 4, Theme.TEXT);
        }
    }

    protected static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Theme.PANEL);
        g.outline(x - 1, y - 1, w + 2, h + 2, 0xFF000000);
    }

    protected void label(GuiGraphicsExtractor g, String text, int x, int y) {
        g.text(font, text, x, y, Theme.ACCENT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (SuggestBox.clickOverlay(event.x(), event.y())) {
            return true;
        }
        for (ScrollPane p : panes) {
            if (p.mouseClickedBar(event.x(), event.y())) {
                return true;
            }
        }
        // Each pane hides its widgets for clicks outside it (nested guards cover every pane).
        java.util.function.BooleanSupplier click = () -> super.mouseClicked(event, doubleClick);
        for (ScrollPane p : panes) {
            java.util.function.BooleanSupplier inner = click;
            click = () -> p.guardClick(event.x(), event.y(), inner);
        }
        boolean handled = click.getAsBoolean();
        if (!handled && getFocused() != null) {
            // Clicking empty space drops focus, so a text box stops eating keys.
            setFocused(null);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        for (ScrollPane p : panes) {
            if (p.mouseDraggedBar(event.y())) {
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panes.forEach(ScrollPane::mouseReleased);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (SuggestBox.scrollOverlay(x, y, scrollY)) {
            return true;
        }
        // A code area under the pointer scrolls itself first.
        if (super.mouseScrolled(x, y, scrollX, scrollY)) {
            return true;
        }
        for (ScrollPane p : panes) {
            if (p.mouseScrolled(x, y, scrollY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SuggestBox.closeAll();
        minecraft.setScreenAndShow(parent);
    }
}
