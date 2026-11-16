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

    /** Containers with identical contents being edited together (World tab's Edit all), and the list to go back to. */
    public record Group(String name, ChestSpec base, List<ChestIndex.Entry> entries, Screen list) {}

    /** When editing several world containers together, or null. */
    private final Group group;

    public ChestEditorScreen(Screen parent) {
        this(parent, Session.chest, null, null);
    }

    /** Edits {@code group} together: the main button gives every one of them these contents. */
    public ChestEditorScreen(Screen parent, Group group, ChestSpec contents) {
        this(parent, contents, null, group);
    }

    private ChestEditorScreen(Screen parent, ChestSpec chest, Consumer<ChestSpec> done) {
        this(parent, chest, done, null);
    }

    private ChestEditorScreen(Screen parent, ChestSpec chest, Consumer<ChestSpec> done, Group group) {
        super(Component.literal(done == null ? "Chests" : "Shulker contents"), parent);
        this.chest = done == null ? chest : chest.copy();
        this.done = done;
        this.group = group;
    }

    /** Editing the session's own container: a new one, or the one linked in the world. */
    private boolean own() {
        return done == null && group == null;
    }

    private int left() {
        return (width - Math.min(360, width - 24)) / 2;
    }

    private int panelWidth() {
        return Math.min(360, width - 24);
    }

    private void changed() {
        if (own()) Session.chest = chest;
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
                own()
                        ? () -> minecraft.setScreenAndShow(new ChestLibraryScreen(this, true))
                        : null,
                "Start an empty chest",
                own()
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
        // Editing all: each keeps the block it is (a barrel stays a barrel); only contents change.
        type.active = group == null;
        type.setEditable(group == null);
        type.onAccept(
                id -> {
                    if (!types().contains(Ids.brief(id))) return;
                    try {
                        chest.block(id);
                        if (own()) Session.chest = chest;
                        saveArmed = false;
                    } catch (IllegalArgumentException e) {
                        toast(Component.literal(e.getMessage()), false);
                        type.setValueQuietly(Ids.brief(chest.block()));
                    }
                });
        if (own()) {
            addRenderableWidget(
                    Chip.of(
                                    "Find in world…",
                                    96,
                                    16,
                                    () -> {
                                        SpawnerWorldScreen.containers = true;
                                        minecraft.setScreenAndShow(new ContainerWorldScreen(parent));
                                    })
                            .at(x, 25)
                            .tip("Every container in this world, grouped by what's in them: teleport to one, edit one, or edit all alike"));
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
                            && !ChestIndex.busy;
            if (Session.chestLink != null)
                addRenderableWidget(
                        Chip.of(
                                        "Use as a new copy",
                                        108,
                                        16,
                                        () -> {
                                            Session.chestLink = null;
                                            changed();
                                        })
                                .at(x + w - 108, 25)
                                .tip("Stop editing this container and keep the contents as a new one to place somewhere else. This container stays as it is in the world."));
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
        if (chest.lootTable())
            addRenderableWidget(
                    Chip.of(
                                    "Drop loot table",
                                    100,
                                    16,
                                    () -> {
                                        chest.unpackLootTable();
                                        changed();
                                    })
                            .at(x + 168, 176)
                            .tip("Stop this container generating loot when first opened, so you can set its contents yourself"));
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
                                            group != null
                                                    ? "Apply to all " + group.entries().size() + "…"
                                                    : saveArmed
                                                            ? "Confirm write"
                                                            : Session.chestLink == null
                                                                    ? "Place here"
                                                                    : "Save this one",
                                            100,
                                            18,
                                            group != null
                                                    ? () -> minecraft.setScreenAndShow(new ChestApplyScreen(this, group, chest))
                                                    : this::write)
                                    .at(x, y)
                                    .primary(group != null ? 0xFF8A6A1E : Session.chestLink == null ? 0xFF2E5E8C : 0xFF2F7D46));
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
                            "This is a loot chest: Drop loot table to set its contents yourself."),
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
        if (save != null && group != null) {
            save.active = ChestWorldBatch.available() && Delivery.canUse("setblock")
                    && !chest.data().equals(group.base().data());
            return;
        }
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
        // The same scope strip as the spawner editor: what this editor is pointed at, in the main button's colour.
        String tag, context;
        int bar, stripe;
        if (done != null) {
            tag = "INSIDE A SHULKER";
            context = "Back discards changes";
            bar = 0xFF2A333C;
            stripe = Theme.TEXT_DIM;
        } else if (group != null) {
            tag = "ALL " + group.entries().size();
            context = group.name() + " · every one gets these contents";
            bar = 0xFF4A3A14;
            stripe = 0xFFFFC14D;
        } else if (Session.chestLink != null) {
            tag = "THIS CONTAINER";
            context = Ids.pretty(Session.chestLink.chest().block()) + " at " + Session.chestLink.where()
                    + (ChestIndex.dimension().equals(Session.chestLink.dimension()) ? ""
                    : " · " + SpawnerWorldScreen.dimensionLabel(Session.chestLink.dimension()));
            bar = 0xFF1F4A2C;
            stripe = 0xFF7BD88F;
        } else {
            tag = "NEW CONTAINER";
            context = "not in the world yet · " + chest.occupied() + " / 27 slots";
            bar = 0xFF1E3550;
            stripe = 0xFF5C8DBF;
        }
        g.fill(x - 5, 63, x + w + 5, 76, bar);
        g.fill(x - 5, 63, x - 2, 76, stripe);
        g.text(font, tag, x + 2, 66, stripe, false);
        g.text(font, font.plainSubstrByWidth(context, w - font.width(tag) - 10), x + 8 + font.width(tag), 66, 0xFFFFFFFF, false);
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
                                    ? "Loot chest: fills itself when first opened. Drop the loot table to set contents."
                                    : group != null
                                    ? "Change the contents, then Apply: a review lists every container first."
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
