package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Pick an item: every item in the game (searchable by name or id), or one from your
 * inventory with all its data, ready to edit.
 */
public final class ItemPickerScreen extends StacksmithScreen {
    private static final int CELL = 20;
    private static boolean inventoryTab;
    private static String query = "";

    private final Consumer<ItemSpec> onPick;
    private final List<ItemStack> all = new ArrayList<>();
    private final List<ItemStack> shown = new ArrayList<>();
    private int scrollRow;

    public ItemPickerScreen(Screen parent, Consumer<ItemSpec> onPick) {
        super(Component.translatable("stacksmith.screen.pick_item"), parent);
        this.onPick = onPick;
    }

    private int gridX() {
        return (width - cols() * CELL) / 2;
    }

    private int gridY() {
        return 46;
    }

    private int cols() {
        return Math.max(4, Math.min(18, (width - 40) / CELL));
    }

    private int rows() {
        return Math.max(1, (height - gridY() - 34) / CELL);
    }

    @Override
    protected void init() {
        if (all.isEmpty()) {
            var features = minecraft.level != null ? minecraft.level.enabledFeatures() : null;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR || features != null && !item.isEnabled(features)) {
                    continue;
                }
                all.add(new ItemStack(item));
            }
        }
        int w = cols() * CELL;
        int x = gridX();
        addRenderableWidget(Chip.of("All items", 70, 14, () -> setTab(false)).selected(() -> !inventoryTab).at(x, 8));
        addRenderableWidget(Chip.of("My inventory", 80, 14, () -> setTab(true)).selected(() -> inventoryTab).at(x + 72, 8)
                .tip("Items you carry, with all their data"));
        EditBox search = addRenderableWidget(new EditBox(font, x, 26, w, 14, Component.literal("Search")));
        search.setHint(Component.literal("search by name or id…"));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            filter();
        });
        setInitialFocus(search);
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 40, height - 26, 80, 20).build());
        filter();
    }

    private void setTab(boolean inventory) {
        inventoryTab = inventory;
        scrollRow = 0;
        filter();
    }

    private void filter() {
        shown.clear();
        List<ItemStack> source = all;
        if (inventoryTab) {
            source = new ArrayList<>();
            if (minecraft.player != null) {
                var inv = minecraft.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (!inv.getItem(i).isEmpty()) {
                        source.add(inv.getItem(i));
                    }
                }
            }
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        for (ItemStack s : source) {
            if (q.isEmpty() || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BuiltInRegistries.ITEM.getKey(s.getItem()).toString().contains(q)) {
                shown.add(s);
            }
        }
        scrollRow = Math.min(scrollRow, maxScroll());
    }

    private int maxScroll() {
        int totalRows = (shown.size() + cols() - 1) / cols();
        return Math.max(0, totalRows - rows());
    }

    private int indexAt(double mx, double my) {
        int c = (int) Math.floor((mx - gridX()) / CELL), r = (int) Math.floor((my - gridY()) / CELL);
        if (c < 0 || c >= cols() || r < 0 || r >= rows()) {
            return -1;
        }
        int i = (scrollRow + r) * cols() + c;
        return i < shown.size() ? i : -1;
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = gridX(), y = gridY(), w = cols() * CELL, h = rows() * CELL;
        panel(g, x - 2, y - 2, w + 4, h + 4);
        int hovered = indexAt(mouseX, mouseY);
        for (int r = 0; r < rows(); r++) {
            for (int c = 0; c < cols(); c++) {
                int i = (scrollRow + r) * cols() + c;
                if (i >= shown.size()) {
                    break;
                }
                int cx = x + c * CELL, cy = y + r * CELL;
                g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, i == hovered ? 0x60FFFFFF : 0x30000000);
                g.item(shown.get(i), cx + 2, cy + 2);
                g.itemDecorations(font, shown.get(i), cx + 2, cy + 2);
            }
        }
        if (shown.isEmpty()) {
            g.centeredText(font, Component.literal(inventoryTab ? "Nothing in your inventory matches" : "No item matches"), width / 2, y + 20, Theme.TEXT_DIM);
        }
        int max = maxScroll();
        if (max > 0) {
            int thumb = Math.max(10, h * rows() / (rows() + max));
            int ty = y + (h - thumb) * scrollRow / max;
            g.fill(x + w + 3, ty, x + w + 6, ty + thumb, 0xFFA0AAB4);
        }
        String count = shown.size() + " items";
        g.text(font, count, x + w - font.width(count), 11, Theme.TEXT_DIM, false);
        if (hovered >= 0) {
            g.setTooltipForNextFrame(font, shown.get(hovered), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int i = indexAt(event.x(), event.y());
        if (i >= 0) {
            ItemStack s = shown.get(i);
            ItemSpec picked = inventoryTab && Delivery.registries() != null
                    ? McItems.fromStack(s, Delivery.registries())
                    : new ItemSpec(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            onPick.accept(picked); // before closing, so the parent rebuilds with the new item
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY) * 2));
        return true;
    }
}
