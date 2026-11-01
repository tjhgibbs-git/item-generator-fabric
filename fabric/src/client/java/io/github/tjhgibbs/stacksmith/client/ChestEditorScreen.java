package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.*;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.container.ChestSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.mod.McItems;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Chest → slot → item, with a second container editor for a shulker's contents. */
public final class ChestEditorScreen extends StacksmithScreen {
    private ChestSpec chest;
    private final Consumer<ChestSpec> done;
    private int selected;
    private boolean clearArmed, saveArmed;
    private Chip save;
    private final List<Chip> slots = new ArrayList<>();

    public ChestEditorScreen(Screen parent) {
        this(parent, Session.chest, null);
    }

    private ChestEditorScreen(Screen parent, ChestSpec chest, Consumer<ChestSpec> done) {
        super(Component.literal(done == null ? "Chests" : "Shulker contents"), parent);
        this.chest = done == null ? chest : chest.copy();
        this.done = done;
    }

    private int left() {
        return (width - Math.min(360, width - 24)) / 2;
    }

    private int panelWidth() {
        return Math.min(360, width - 24);
    }

    private void changed() {
        if (done == null) Session.chest = chest;
        saveArmed = false;
        rebuildWidgets();
    }

    private List<String> types() {
        List<String> out =
                new ArrayList<>(List.of("chest", "trapped_chest", "barrel", "shulker_box"));
        for (String color :
                List.of(
                        "white",
                        "orange",
                        "magenta",
                        "light_blue",
                        "yellow",
                        "lime",
                        "pink",
                        "gray",
                        "light_gray",
                        "cyan",
                        "purple",
                        "blue",
                        "brown",
                        "green",
                        "red",
                        "black")) out.add(color + "_shulker_box");
        return out;
    }

    @Override
    protected void init() {
        slots.clear();
        int x = left(), w = panelWidth();
        savedLabel = "★ Saved";
        savedTip = "Reusable container templates, without world locations";
        header(
                done == null ? Tab.CHEST : null,
                null,
                null,
                done == null
                        ? () -> minecraft.setScreenAndShow(new ChestLibraryScreen(this, true))
                        : null,
                "Start an empty chest",
                done == null
                        ? () -> {
                            chest = new ChestSpec();
                            Session.chestLink = null;
                            changed();
                        }
                        : null);
        SuggestBox type =
                addRenderableWidget(
                        new SuggestBox(
                                font,
                                w - 120,
                                16,
                                Component.literal("Container type"),
                                this::types));
        type.setX(x + 120);
        type.setY(46);
        type.setValueQuietly(Ids.brief(chest.block()));
        type.onAccept(
                id -> {
                    if (!types().contains(Ids.brief(id))) return;
                    try {
                        chest.block(id);
                        if (done == null) Session.chest = chest;
                        saveArmed = false;
                    } catch (IllegalArgumentException e) {
                        toast(Component.literal(e.getMessage()), false);
                        type.setValueQuietly(Ids.brief(chest.block()));
                    }
                });
        if (done == null) {
            addRenderableWidget(
                    Chip.of(
                                    "Find in world…",
                                    96,
                                    16,
                                    () ->
                                            minecraft.setScreenAndShow(
                                                    new ChestLibraryScreen(this, false)))
                            .at(x, 25));
            Chip load =
                    addRenderableWidget(
                            Chip.of(
                                            "Load looked-at",
                                            96,
                                            16,
                                            () -> {
                                                var be = WorldSpawner.lookedAtBlockEntity();
                                                if (ChestIndex.supported(be))
                                                    ChestIndex.load(
                                                            ChestIndex.entry(
                                                                    be,
                                                                    io.github.tjhgibbs.stacksmith
                                                                            .core.nbt.Nbt
                                                                            .compound()),
                                                            e -> {
                                                                Session.chestLink = e;
                                                                chest = e.chest().copy();
                                                                changed();
                                                            });
                                            })
                                    .at(x + 100, 25)
                                    .tip(
                                            "Read this container's full contents; needs /data"
                                                + " permission"));
            load.active =
                    ChestIndex.supported(WorldSpawner.lookedAtBlockEntity())
                            && Delivery.canUse("data")
                            && !ChestIndex.busy
                            && !SpawnerBatch.searching();
            if (Session.chestLink != null)
                addRenderableWidget(
                        Chip.of(
                                        "Unlink",
                                        48,
                                        16,
                                        () -> {
                                            Session.chestLink = null;
                                            changed();
                                        })
                                .at(x + w - 48, 25)
                                .tip("Keep the contents as a new reusable container"));
        }
        int cell = Math.min(32, w / 9), gx = x + (w - cell * 9) / 2;
        for (int i = 0; i < 27; i++) {
            int slot = i;
            Chip chip =
                    addRenderableWidget(
                            Chip.of(
                                            "",
                                            cell - 2,
                                            25,
                                            () -> {
                                                selected = slot;
                                                clearArmed = false;
                                                rebuildWidgets();
                                            })
                                    .selected(() -> selected == slot)
                                    .at(gx + (i % 9) * cell, 78 + (i / 9) * 27)
                                    .tip(slotTip(i)));
            slots.add(chip);
        }
        addRenderableWidget(
                Chip.of(
                                chest.slot(selected) == null ? "Add item…" : "Edit item…",
                                86,
                                16,
                                this::editItem)
                        .at(x, 176));
        Chip clear =
                addRenderableWidget(
                        Chip.of(
                                        clearArmed ? "Clear slot?" : "Clear slot",
                                        74,
                                        16,
                                        () -> {
                                            if (clearArmed) {
                                                chest.slot(selected, null);
                                                clearArmed = false;
                                                changed();
                                            } else {
                                                clearArmed = true;
                                                rebuildWidgets();
                                            }
                                        })
                                .at(x + 90, 176));
        clear.active = chest.slot(selected) != null;
        ItemSpec item = chest.slot(selected);
        if (item != null && ChestSpec.shulker(item.id()))
            addRenderableWidget(
                    Chip.of(
                                    "Open shulker…",
                                    100,
                                    16,
                                    () -> {
                                        minecraft.setScreenAndShow(
                                                new ChestEditorScreen(
                                                        this,
                                                        ChestSpec.fromItem(item),
                                                        edited -> {
                                                            try {
                                                                chest.slot(
                                                                        selected,
                                                                        edited.updateItem(item));
                                                                changed();
                                                                minecraft.setScreenAndShow(this);
                                                            } catch (IllegalArgumentException e) {
                                                                toast(
                                                                        Component.literal(
                                                                                e.getMessage()),
                                                                        false);
                                                            }
                                                        }));
                                    })
                            .at(x + 168, 176));
        int y = height - 25;
        if (done != null) {
            addRenderableWidget(
                    Chip.of(
                                    "Keep contents",
                                    100,
                                    18,
                                    () -> {
                                        done.accept(chest.copy());
                                    })
                            .at(x, y)
                            .primary(0xFF2F7D46));
            addRenderableWidget(Chip.of("Back", 50, 18, this::onClose).at(x + w - 50, y));
        } else {
            save =
                    addRenderableWidget(
                            Chip.of(
                                            saveArmed
                                                    ? "Confirm write"
                                                    : Session.chestLink == null
                                                            ? "Place here"
                                                            : "Save to chest",
                                            100,
                                            18,
                                            this::write)
                                    .at(x, y)
                                    .primary(0xFF2F7D46));
            addRenderableWidget(
                                    Chip.of(
                                                    "Take item",
                                                    76,
                                                    18,
                                                    () -> {
                                                        var result =
                                                                McItems.toStack(
                                                                        chest.item(),
                                                                        Delivery.registries());
                                                        toast(
                                                                result.ok()
                                                                        ? Delivery.giveCreative(
                                                                                result.stack())
                                                                        : Component.literal(
                                                                                result.error()),
                                                                result.ok());
                                                    })
                                            .at(x + 104, y))
                            .active =
                    Delivery.isCreative();
            addRenderableWidget(
                    Chip.of(
                                    "Copy /setblock",
                                    98,
                                    18,
                                    () -> toast(Delivery.copy(chest.command()), true))
                            .at(x + 184, y));
            addRenderableWidget(Chip.of("Back", 50, 18, this::onClose).at(x + w - 50, y));
            updateSave();
        }
    }

    private String slotTip(int i) {
        ItemSpec item = chest.slot(i);
        return "Slot "
                + (i + 1)
                + ": "
                + (item == null ? "Empty" : Ids.pretty(item.id()) + " ×" + item.count())
                + "\nSelect to edit";
    }

    private void editItem() {
        if (chest.lootTable()) {
            toast(
                    Component.literal(
                            "Open this loot chest in the world first, then reload it to edit its"
                                + " generated contents."),
                    false);
            return;
        }
        ItemSpec item = chest.slot(selected);
        minecraft.setScreenAndShow(
                new ItemEditorScreen(
                        this,
                        item == null ? new ItemSpec("stone") : item,
                        edited -> {
                            try {
                                chest.slot(selected, edited);
                                changed();
                                minecraft.setScreenAndShow(this);
                            } catch (IllegalArgumentException e) {
                                minecraft.setScreenAndShow(this);
                                toast(Component.literal(e.getMessage()), false);
                            }
                        }));
    }

    private void write() {
        if (!saveArmed) {
            saveArmed = true;
            rebuildWidgets();
            return;
        }
        var link = Session.chestLink;
        if (link != null
                && (!ChestIndex.dimension().equals(link.dimension())
                        || !ChestIndex.supported(minecraft.level.getBlockEntity(link.pos())))) {
            toast(
                    Component.literal(
                            "The linked container is not loaded here. Find it in the world and"
                                + " reload before saving."),
                    false);
            return;
        }
        if (link == null
                && !minecraft
                        .level
                        .getBlockState(minecraft.player.blockPosition())
                        .canBeReplaced()) {
            toast(Component.literal("Stand in an empty space to place a new container."), false);
            return;
        }
        if (link != null
                && !ChestIndex.block(minecraft.level.getBlockEntity(link.pos()))
                        .equals(link.chest().block())) {
            toast(
                    Component.literal("This container changed type. Reload it before saving."),
                    false);
            return;
        }
        String pos =
                link == null
                        ? minecraft.player.blockPosition().getX()
                                + " "
                                + minecraft.player.blockPosition().getY()
                                + " "
                                + minecraft.player.blockPosition().getZ()
                        : link.where();
        String state = chest.block();
        if (link != null && chest.block().equals(link.chest().block())) state = link.state();
        List<String> commands = chest.write(pos, state, link != null, Commands.CHAT_LIMIT);
        if (commands == null) {
            toast(
                    Component.literal(
                            "An item value is too long for chat. Use Take item or Copy /setblock."),
                    false);
            return;
        }
        CommandQueue.add(
                commands,
                () -> {
                    saveArmed = false;
                    rebuildWidgets();
                    toast(
                            Component.literal(
                                    "Write commands sent. Reload the container to verify the server"
                                        + " result."),
                            true);
                });
        updateSave();
    }

    private void updateSave() {
        if (save != null)
            save.active =
                    Delivery.inWorld()
                            && Delivery.canUse("data")
                            && Delivery.canUse("setblock")
                            && !CommandQueue.busy()
                            && !chest.lootTable();
    }

    @Override
    public void tick() {
        super.tick();
        updateSave();
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = left(), w = panelWidth();
        panel(g, x - 5, 43, w + 10, height - 73);
        label(g, "Container type", x, 50);
        String context =
                done != null
                        ? "Shulker contents · Back discards changes"
                        : Session.chestLink == null
                                ? "New container · " + chest.occupied() + " / 27 slots"
                                : "Linked: "
                                        + Session.chestLink.where()
                                        + " · "
                                        + Session.chestLink.dimension();
        g.text(font, font.plainSubstrByWidth(context, w), x, 66, Theme.TEXT_DIM, false);
        ItemSpec item = chest.slot(selected);
        String description =
                "Slot "
                        + (selected + 1)
                        + " · "
                        + (item == null ? "Empty" : Ids.pretty(item.id()) + " ×" + item.count());
        g.text(font, font.plainSubstrByWidth(description, w), x, 162, Theme.TEXT, false);
        if (height > 245)
            g.text(
                    font,
                    font.plainSubstrByWidth(
                            chest.lootTable()
                                    ? "Loot chest: open in-world, then reload before editing."
                                    : saveArmed
                                            ? "Writes replace this container's contents. Click"
                                                  + " Confirm write."
                                            : "Select a slot. Edit its item or open a shulker's"
                                                  + " contents.",
                            w),
                    x,
                    201,
                    Theme.TEXT_DIM,
                    false);
    }

    @Override
    protected void extractOverlays(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (Delivery.registries() == null) return;
        for (int i = 0; i < slots.size(); i++) {
            ItemSpec item = chest.slot(i);
            if (item == null) continue;
            var result = McItems.toStack(item, Delivery.registries());
            Chip chip = slots.get(i);
            if (result.ok()) {
                g.item(result.stack(), chip.getX() + 4, chip.getY() + 2);
                g.itemDecorations(font, result.stack(), chip.getX() + 4, chip.getY() + 2);
            }
        }
    }
}
