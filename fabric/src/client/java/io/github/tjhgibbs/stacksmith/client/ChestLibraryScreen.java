package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.*;
import io.github.tjhgibbs.stacksmith.core.container.*;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.*;

/** World destinations and reusable stars are deliberately separate lists. */
public final class ChestLibraryScreen extends StacksmithScreen {
    private static final Presets PRESETS = new Presets(PresetsScreen.ROOT);
    private final ScrollPane list = pane(new ScrollPane());
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private boolean starred;
    private String query = "", nickname = "", confirmDelete;
    private boolean wasBusy;
    private int seenCount = -1;
    private int shown;

    public ChestLibraryScreen(Screen parent, boolean starred) {
        super(Component.literal("Container library"), parent);
        this.starred = starred;
    }

    private int left() {
        return 12;
    }

    @Override
    protected void init() {
        int x = left(), w = width - 24;
        addRenderableWidget(
                Chip.of(
                                "World",
                                56,
                                16,
                                () -> {
                                    starred = false;
                                    rebuildWidgets();
                                })
                        .selected(() -> !starred)
                        .at(x, 12));
        addRenderableWidget(
                Chip.of(
                                "★ Starred",
                                72,
                                16,
                                () -> {
                                    starred = true;
                                    rebuildWidgets();
                                })
                        .selected(() -> starred)
                        .at(x + 60, 12));
        EditBox search =
                addRenderableWidget(
                        new EditBox(
                                font,
                                x + 140,
                                12,
                                w - 140,
                                16,
                                Component.literal("Search containers")));
        search.setMaxLength(200);
        search.setHint(
                Component.literal(
                        starred ? "Search templates…" : "Item, type, dimension, coordinates…"));
        search.setValue(query);
        search.setResponder(
                s -> {
                    query = s;
                    fill();
                });
        if (starred) {
            EditBox name =
                    addRenderableWidget(
                            new EditBox(
                                    font,
                                    x,
                                    36,
                                    w - 108,
                                    16,
                                    Component.literal("Template nickname")));
            name.setMaxLength(80);
            name.setHint(Component.literal("Name this reusable container"));
            name.setValue(nickname);
            name.setResponder(s -> nickname = s);
            addRenderableWidget(
                    Chip.of(
                                    "★ Star current",
                                    104,
                                    16,
                                    () -> {
                                        try {
                                            String wanted =
                                                    nickname.isBlank()
                                                            ? io.github.tjhgibbs.stacksmith.core.Ids
                                                                    .pretty(Session.chest.block())
                                                            : nickname;
                                            String n =
                                                    PRESETS.save(
                                                            Presets.Kind.CHEST,
                                                            PRESETS.freeName(
                                                                    Presets.Kind.CHEST, wanted),
                                                            BlockTemplate.save(
                                                                    Session.chest.command()));
                                            toast(
                                                    Component.literal(
                                                            "Starred “"
                                                                    + n
                                                                    + "” · no world location"
                                                                    + " saved"),
                                                    true);
                                            fill();
                                        } catch (IOException e) {
                                            toast(
                                                    Component.literal(
                                                            "Couldn't save: " + e.getMessage()),
                                                    false);
                                        }
                                    })
                            .at(x + w - 104, 36));
        } else {
            Chip scan =
                    addRenderableWidget(
                            Chip.of(
                                            ChestIndex.busy ? "Indexing…" : "Index world",
                                            90,
                                            16,
                                            () -> {
                                                ChestIndex.start();
                                                rebuildWidgets();
                                            })
                                    .at(x, 36));
            scan.active = !ChestIndex.busy && Delivery.inWorld() && !SpawnerBatch.searching();
        }
        list.bounds(x, 76, w, height - 110);
        fill();
        addRenderableWidget(Chip.of("Back", 60, 18, this::onClose).at(width - 72, height - 26));
    }

    private boolean matches(String text) {
        text = text.toLowerCase(Locale.ROOT);
        for (String term : query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
            if (!text.contains(term)) return false;
        return true;
    }

    private void openTemplate(ChestSpec chest) {
        Session.chest = chest.copy();
        Session.chestLink = null;
        minecraft.setScreenAndShow(
                new ChestEditorScreen(parent instanceof ChestEditorScreen s ? s.parent : parent));
    }

    private void fill() {
        rowWidgets.forEach(this::removeWidget);
        rowWidgets.clear();
        list.clear();
        shown = 0;
        if (starred)
            for (Presets.Preset p : PRESETS.list(Presets.Kind.CHEST)) {
                try {
                    ChestSpec chest = ChestSpec.fromTag(p.data());
                    if (!matches(p.name() + " " + chest.data() + " " + chest.block())) continue;
                    addRow(
                            p.name() + " · " + chest.occupied() + " slots",
                            "Reusable · "
                                    + io.github.tjhgibbs.stacksmith.core.Ids.pretty(chest.block()),
                            List.of(
                                    Chip.of("Load", 42, 16, () -> openTemplate(chest)),
                                    Chip.of(
                                            "Copy",
                                            42,
                                            16,
                                            () -> toast(Delivery.copy(chest.command()), true)),
                                    Chip.of(
                                            p.name().equals(confirmDelete) ? "Sure?" : "Unstar",
                                            48,
                                            16,
                                            () -> {
                                                if (!p.name().equals(confirmDelete)) {
                                                    confirmDelete = p.name();
                                                    fill();
                                                    return;
                                                }
                                                try {
                                                    PRESETS.delete(Presets.Kind.CHEST, p.name());
                                                    fill();
                                                } catch (IOException e) {
                                                    toast(Component.literal(e.getMessage()), false);
                                                }
                                            })));
                } catch (RuntimeException e) {
                    /* Hand-edited invalid preset: leave on disk. */
                }
            }
        else
            for (ChestIndex.Entry e :
                    ChestIndex.entries.values().stream()
                            .sorted(
                                    Comparator.comparing(ChestIndex.Entry::dimension)
                                            .thenComparingDouble(
                                                    e ->
                                                            minecraft.player == null
                                                                    ? 0
                                                                    : e.pos()
                                                                            .distSqr(
                                                                                    minecraft.player
                                                                                            .blockPosition())))
                            .toList()) {
                if (!matches(e.searchText())) continue;
                Chip edit =
                        Chip.of(
                                        "Edit",
                                        42,
                                        16,
                                        () ->
                                                ChestIndex.load(
                                                        e,
                                                        fresh -> {
                                                            Session.chest = fresh.chest().copy();
                                                            Session.chestLink = fresh;
                                                            minecraft.setScreenAndShow(
                                                                    new ChestEditorScreen(
                                                                            parent
                                                                                            instanceof
                                                                                            ChestEditorScreen
                                                                                                    s
                                                                                    ? s.parent
                                                                                    : parent));
                                                        }))
                                .tip(
                                        "Reload live contents before editing. Teleport here first"
                                            + " if unloaded.");
                edit.active =
                        Delivery.inWorld()
                                && ChestIndex.dimension().equals(e.dimension())
                                && ChestIndex.supported(minecraft.level.getBlockEntity(e.pos()))
                                && Delivery.canUse("data")
                                && !ChestIndex.busy
                                && !SpawnerBatch.searching();
                Chip tp =
                        Chip.of(
                                        "Teleport",
                                        58,
                                        16,
                                        () -> {
                                            toast(
                                                    Delivery.run(
                                                            "execute in "
                                                                    + e.dimension()
                                                                    + " run tp @s "
                                                                    + (e.pos().getX() + 0.5)
                                                                    + " "
                                                                    + (e.pos().getY() + 1)
                                                                    + " "
                                                                    + (e.pos().getZ() + 0.5)),
                                                    true);
                                        })
                                .tip(
                                        "Teleport above this container; check the surroundings"
                                            + " before moving.");
                tp.active = Delivery.canUse("execute") && Delivery.canUse("tp");
                addRow(
                        io.github.tjhgibbs.stacksmith.core.Ids.pretty(e.chest().block())
                                + " · "
                                + e.chest().occupied()
                                + " slots",
                        e.where() + " · " + e.dimension(),
                        List.of(
                                edit,
                                tp,
                                Chip.of(
                                                "☆",
                                                20,
                                                16,
                                                () -> {
                                                    try {
                                                        PRESETS.save(
                                                                Presets.Kind.CHEST,
                                                                PRESETS.freeName(
                                                                        Presets.Kind.CHEST,
                                                                        io.github.tjhgibbs
                                                                                .stacksmith.core.Ids
                                                                                .pretty(
                                                                                        e.chest()
                                                                                                .block())),
                                                                BlockTemplate.save(
                                                                        e.chest().command()));
                                                        toast(
                                                                Component.literal(
                                                                        "Starred reusable"
                                                                            + " contents"),
                                                                true);
                                                    } catch (IOException ex) {
                                                        toast(
                                                                Component.literal(ex.getMessage()),
                                                                false);
                                                    }
                                                })
                                        .tip(
                                                "Star the indexed contents as a reusable relative"
                                                    + " command")));
            }
        addPaneWidgets(list);
        list.layout();
        seenCount = ChestIndex.entries.size();
        wasBusy = ChestIndex.busy;
    }

    private void addRow(String title, String subtitle, List<Chip> buttons) {
        shown++;
        list.add(
                new ScrollPane.Row() {
                    int room;

                    public int layout(int x, int y, int w) {
                        int right = x + w;
                        for (int i = buttons.size() - 1; i >= 0; i--) {
                            Chip b = buttons.get(i);
                            right -= b.getWidth();
                            b.setX(right);
                            b.setY(y + 4);
                            right -= 3;
                        }
                        room = right - x;
                        return 30;
                    }

                    public void extract(
                            GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
                        g.text(
                                font,
                                font.plainSubstrByWidth(title, room),
                                x,
                                y + 3,
                                Theme.TEXT,
                                false);
                        g.text(
                                font,
                                font.plainSubstrByWidth(subtitle, room),
                                x,
                                y + 15,
                                Theme.TEXT_DIM,
                                false);
                    }

                    public List<AbstractWidget> widgets() {
                        return new ArrayList<>(buttons);
                    }
                });
        rowWidgets.addAll(buttons);
    }

    @Override
    public void tick() {
        super.tick();
        if (!starred
                && (wasBusy != ChestIndex.busy
                        || !ChestIndex.busy && seenCount != ChestIndex.entries.size()))
            rebuildWidgets();
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mx, int my, float delta) {
        panel(g, 6, 6, width - 12, height - 12);
        g.text(
                font,
                font.plainSubstrByWidth(
                        starred
                                ? "Stars keep contents for reuse · Load starts a new container"
                                : ChestIndex.status,
                        width - 28),
                12,
                60,
                Theme.TEXT_DIM,
                false);
        if (shown == 0)
            g.text(
                    font,
                    starred
                            ? "Star a container to reuse its contents here."
                            : "No containers found. Index the world or change the search.",
                    12,
                    82,
                    Theme.TEXT_DIM,
                    false);
    }
}
