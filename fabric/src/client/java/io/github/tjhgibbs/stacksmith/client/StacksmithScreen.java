package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
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

    /** The three editors, as tabs along the top. */
    public enum Tab {
        SPAWNER("Spawner", "Mob spawners: what they spawn, how often, and how many"),
        MOB("Mob", "Mobs: type, name, gear, behaviour, stats and effects; summon them or put them in a spawner"),
        ITEM("Item", "Items: name, lore, enchantments and every other component");

        final String label, tip;

        Tab(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }
    }

    /** The tab the key reopens. */
    static Tab lastTab = Tab.SPAWNER;

    static Screen open(Tab tab, Screen parent) {
        return switch (tab) {
            case ITEM -> new ItemEditorScreen(parent);
            case MOB -> new MobEditorScreen(parent);
            case SPAWNER -> new SpawnerEditorScreen(parent);
        };
    }

    /** Width of the header's right-hand buttons (New, Saved… and Import…), for screens adding more. */
    protected static final int HEADER_RIGHT = 60 + 4 + 56 + 4 + 40;

    /**
     * The tab row (top level), New, Saved… and Import…; nested editors pass {@code null} for no tabs.
     * New asks for a second click before it throws away what's being edited.
     */
    protected void header(Tab current, String importTip, Runnable importAction, Runnable savedAction,
                          String newTip, Runnable newAction) {
        if (current != null) {
            lastTab = current;
            int x = PAD;
            for (Tab t : Tab.values()) {
                int w = font.width(t.label) + 20;
                addRenderableWidget(Chip.of(t.label, w, 14, () -> {
                    if (t != current) {
                        minecraft.setScreenAndShow(open(t, parent));
                    }
                }).selected(() -> t == current).tip(t.tip).at(x, 5));
                x += w + 2;
            }
            tabsRight = x;
        }
        if (importAction != null) {
            addRenderableWidget(Chip.of("Import…", 60, 14, importAction).at(width - PAD - 60, 5).tip(importTip));
        }
        if (savedAction != null) {
            addRenderableWidget(Chip.of("Saved…", 56, 14, savedAction).at(width - PAD - 60 - 4 - 56, 5)
                    .tip("Save this under a name, or load one you saved"));
        }
        if (newAction != null) {
            newArmedUntil = 0;
            newChip = addRenderableWidget(Chip.of("New", 40, 14, () -> {
                if (System.currentTimeMillis() < newArmedUntil) {
                    newAction.run();
                } else {
                    newArmedUntil = System.currentTimeMillis() + 3000;
                    newChip.setMessage(Component.literal("Sure?"));
                    newChip.color(Theme.BAD);
                    toast(Component.literal("Click New again to start over: unsaved changes are lost"), false);
                }
            }).at(width - PAD - HEADER_RIGHT, 5).tip(newTip + " (click twice)"));
        }
    }

    protected final Screen parent;
    private Chip newChip;
    private int tabsRight = PAD;
    private long newArmedUntil;
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

    /** Draws the centred title if it clears the tabs and the header's right-hand buttons. */
    protected void headerTitle(GuiGraphicsExtractor g, int rightEdge) {
        int half = font.width(title) / 2;
        if (width / 2 - half - 4 > tabsRight && width / 2 + half + 4 < rightEdge) {
            g.centeredText(font, title, width / 2, 8, Theme.TEXT);
        }
    }

    protected void headerTitle(GuiGraphicsExtractor g) {
        headerTitle(g, width - PAD - HEADER_RIGHT);
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
    public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener listener) {
        if (listener instanceof io.github.tjhgibbs.stacksmith.client.ui.Chip c && c.isKeepingFocus()
                || listener instanceof io.github.tjhgibbs.stacksmith.client.ui.Swatch s && s.isKeepingFocus()) {
            return;
        }
        super.setFocused(listener);
    }

    @Override
    public void tick() {
        super.tick();
        if (newChip != null && newArmedUntil != 0 && System.currentTimeMillis() >= newArmedUntil) {
            newArmedUntil = 0;
            newChip.setMessage(Component.literal("New"));
            newChip.color(Theme.TEXT);
        }
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
