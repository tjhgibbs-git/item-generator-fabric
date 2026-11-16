package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.container.ChestSpec;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The review for giving several containers the same contents (the World tab's Edit all on
 * containers): what they hold now and will hold, which ones (untick any), then progress,
 * results and Undo. The counterpart of {@link SpawnerApplyScreen}.
 */
public final class ChestApplyScreen extends StacksmithScreen {
    private enum Stage { REVIEW, RUNNING, DONE }

    /**
     * What is about to happen: the containers, what each will hold afterwards, and how to say
     * it. {@code keepEditing} reopens the editor on them afterwards ({@code undone}: as they
     * were before), or is null when there is nothing to go back into.
     */
    record Plan(String name, String now, String after, List<ChestIndex.Entry> entries,
                java.util.Map<String, ChestSpec> wanted, boolean changed, Screen list,
                java.util.function.Consumer<Boolean> keepEditing) {}

    private final Plan group;
    private final Set<String> excluded = new HashSet<>();
    private final ScrollPane rows = pane(new ScrollPane());
    private Stage stage = Stage.REVIEW;
    private Chip apply;
    private String error = "";
    private boolean undoing, undone;
    private static final int LIST_TOP = 78;

    /** Reviews giving every container of {@code group} the contents of {@code edited}. */
    ChestApplyScreen(ChestEditorScreen editor, ChestEditorScreen.Group group, ChestSpec edited) {
        this(editor, plan(group, edited.copy()));
    }

    ChestApplyScreen(Screen back, Plan plan) {
        super(Component.literal("Review changes"), back);
        this.group = plan;
    }

    private static Plan plan(ChestEditorScreen.Group group, ChestSpec edited) {
        java.util.Map<String, ChestSpec> wanted = new java.util.HashMap<>();
        group.entries().forEach(e -> wanted.put(e.key(), edited));
        int slots = edited.occupied();
        var mc = net.minecraft.client.Minecraft.getInstance();
        return new Plan(group.name(), ContainerWorldScreen.summary(group.base()),
                ContainerWorldScreen.summary(edited) + (edited.lootTable() || slots == 0 ? "" : "  (" + slots + (slots == 1 ? " slot)" : " slots)")),
                group.entries(), wanted, !edited.data().equals(group.base().data()), group.list(), undone -> {
            ChestSpec now = undone ? group.base().copy() : edited.copy();
            List<ChestIndex.Entry> fresh = group.entries().stream().map(e -> ChestIndex.entries.get(e.key())).filter(Objects::nonNull).toList();
            mc.setScreenAndShow(new ChestEditorScreen(group.list(),
                    new ChestEditorScreen.Group(ContainerWorldScreen.summary(now), now, fresh, group.list()), now.copy()));
        });
    }

    private int panelW() { return Math.min(width - 2 * PAD, 460); }
    private int panelX() { return (width - panelW()) / 2; }
    private int x() { return panelX() + 8; }
    private int w() { return panelW() - 16; }

    private List<ChestIndex.Entry> selected() {
        return group.entries().stream().filter(e -> !excluded.contains(e.key())).toList();
    }

    private static String containers(int n) {
        return n + (n == 1 ? " container" : " containers");
    }

    @Override
    protected void init() {
        int x = x(), w = w(), bottom = height - 28;
        if (stage == Stage.REVIEW) {
            addRenderableWidget(Chip.of(group.keepEditing() != null ? "‹ Back to editor" : "‹ Back to the list", 100, 20, this::onClose).at(x, bottom));
            apply = addRenderableWidget(Chip.of("", 170, 20, this::start).primary(0xFF2F7D46).at(x + w - 170, bottom));
            apply.tip("Writes this to each ticked container. Each stays the block it is, facing the same way. "
                    + "Far-away ones are loaded for a moment; ones next to unexplored terrain are skipped and listed, "
                    + "as is one changed since the last scan. You can undo afterwards.");
            addRenderableWidget(Chip.of("None", 36, 14, () -> { group.entries().forEach(e -> excluded.add(e.key())); updateApply(); })
                    .at(x + w - 36, LIST_TOP - 16).tip("Untick every container"));
            addRenderableWidget(Chip.of("All", 26, 14, () -> { excluded.clear(); updateApply(); })
                    .at(x + w - 36 - 28, LIST_TOP - 16).tip("Tick every container"));
            updateApply();
        } else if (stage == Stage.RUNNING) {
            addRenderableWidget(Chip.of("Stop", 70, 20, ChestWorldBatch::cancel).at(x + w - 70, bottom)
                    .tip("Stops before the next container. Ones already updated stay updated."));
        } else {
            addRenderableWidget(Chip.of("Back to the list", 124, 20, () -> minecraft.setScreenAndShow(group.list()))
                    .primary(0xFF2E5E8C).at(x + w - 124, bottom));
            int bx = x;
            if (group.keepEditing() != null) {
                addRenderableWidget(Chip.of("Keep editing", 84, 20, () -> group.keepEditing().accept(undone)).at(x, bottom)
                        .tip("Back to the editor to change these containers some more"));
                bx += 88;
            }
            if (!undone) {
                Chip undo = addRenderableWidget(Chip.of("↺ Undo", 60, 20, this::undo).at(bx, bottom));
                undo.active = ChestWorldBatch.canUndo();
                undo.tip(undo.active ? "Put every container this changed back as it was. One changed again since then is left alone."
                        : "Nothing was changed");
            }
        }
        buildRows();
    }

    private void updateApply() {
        if (apply == null) return;
        int n = selected().size();
        boolean same = !group.changed();
        apply.setMessage(Component.literal(same ? "Nothing to apply" : n == 0 ? "None selected" : "Apply to " + containers(n)));
        apply.active = n > 0 && !same && ChestWorldBatch.available() && Delivery.canUse("setblock");
    }

    private void start() {
        String why = ChestWorldBatch.start(selected(), group.wanted());
        if (why != null) { error = why; return; }
        error = "";
        stage = Stage.RUNNING;
        rebuildWidgets();
    }

    private void undo() {
        String why = ChestWorldBatch.undo();
        if (why != null) { error = why; return; }
        error = "";
        undoing = true;
        stage = Stage.RUNNING;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (stage == Stage.REVIEW) updateApply();
        if (stage == Stage.RUNNING && !ChestWorldBatch.busy()) {
            stage = Stage.DONE;
            if (undoing) { undoing = false; undone = true; }
            rebuildWidgets();
        }
    }

    private void buildRows() {
        for (ScrollPane.Row r : rows.rows()) r.widgets().forEach(this::removeWidget);
        rows.clear();
        int top = stage == Stage.REVIEW ? LIST_TOP : 96;
        rows.bounds(x(), top, w(), Math.max(20, height - 36 - top));
        if (stage == Stage.REVIEW) {
            for (ChestIndex.Entry e : group.entries()) rows.add(new TargetRow(e));
        } else if (stage == Stage.DONE) {
            for (ChestWorldBatch.Problem p : List.copyOf(ChestWorldBatch.problems)) rows.add(new ProblemRow(p));
        }
        addPaneWidgets(rows);
        rows.layout();
    }

    private String place(ChestIndex.Entry e) {
        String here = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        if (!here.equals(e.dimension())) return SpawnerWorldScreen.dimensionLabel(e.dimension());
        double d = minecraft.player == null ? 0 : Math.sqrt(minecraft.player.blockPosition().distSqr(e.pos()));
        return d < 6 ? "here" : Math.round(d) + " m";
    }

    private final class TargetRow implements ScrollPane.Row {
        final ChestIndex.Entry entry;
        final Chip tick;

        TargetRow(ChestIndex.Entry e) {
            entry = e;
            tick = Chip.of("", 14, 14, () -> {
                if (!excluded.remove(e.key())) excluded.add(e.key());
                updateApply();
            });
            tick.setTooltip(Tooltip.create(Component.literal("Untick to leave this one alone.")));
        }

        @Override public int layout(int x, int y, int w) {
            tick.setX(x + 1); tick.setY(y + 1);
            return 16;
        }

        @Override public void extract(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
            boolean on = !excluded.contains(entry.key());
            tick.setMessage(Component.literal(on ? "✔" : ""));
            tick.color(Theme.GOOD);
            g.text(font, entry.where(), x + 20, y + 4, on ? Theme.TEXT : Theme.TEXT_FAINT, false);
            int cx = x + 20 + Math.max(font.width(entry.where()) + 8, 84);
            String place = place(entry);
            g.text(font, place, cx, y + 4, on ? Theme.TEXT_DIM : Theme.TEXT_FAINT, false);
            cx += Math.max(font.width(place) + 10, 50);
            g.text(font, font.plainSubstrByWidth(Ids.pretty(entry.chest().block()), Math.max(0, x + w - 4 - cx)), cx, y + 4, Theme.TEXT_FAINT, false);
        }

        @Override public List<AbstractWidget> widgets() { return List.of(tick); }
    }

    private final class ProblemRow implements ScrollPane.Row {
        final Chip tp;
        final ChestWorldBatch.Problem problem;

        ProblemRow(ChestWorldBatch.Problem p) {
            problem = p;
            tp = Chip.of("TP", 26, 14, () -> {
                if (ContainerWorldScreen.travel(p.entry()) == null) minecraft.setScreenAndShow(null);
            });
            tp.setTooltip(Tooltip.create(Component.literal("Teleport onto it (J then edits it)")));
        }

        @Override public int layout(int x, int y, int w) {
            tp.setX(x + w - 26); tp.setY(y + 1);
            return 16;
        }

        @Override public void extract(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
            String where = problem.entry().where() + "  " + SpawnerWorldScreen.dimensionLabel(problem.entry().dimension());
            g.text(font, where, x + 2, y + 4, Theme.TEXT, false);
            int rx = x + 2 + Math.max(font.width(where) + 10, 130);
            g.text(font, font.plainSubstrByWidth(problem.reason(), x + w - 32 - rx), rx, y + 4, Theme.WARN, false);
        }

        @Override public List<AbstractWidget> widgets() { return List.of(tp); }
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int px = panelX(), pw = panelW(), x = x(), w = w();
        panel(g, px, 8, pw, height - 14);
        g.fill(px, 8, px + pw, 26, 0xFF4A3A14);
        g.fill(px, 8, px + 3, 26, Theme.ACCENT);
        g.text(font, font.plainSubstrByWidth("All " + group.entries().size() + " · " + group.name(), w), x, 13, 0xFFFFFFFF, false);
        int y = 34;
        if (stage == Stage.REVIEW) {
            g.text(font, font.plainSubstrByWidth("Now:  " + group.now(), w), x, y, Theme.TEXT_DIM, false);
            g.text(font, font.plainSubstrByWidth("After:  " + group.after(), w), x, y + 12, Theme.GOOD, false);
            int go = selected().size();
            g.text(font, "Goes to " + go + " of " + group.entries().size(), x, LIST_TOP - 13, Theme.TEXT, false);
            g.fill(x, LIST_TOP - 2, x + w, LIST_TOP - 1, Theme.BORDER);
        } else {
            boolean running = stage == Stage.RUNNING;
            int done = ChestWorldBatch.completed + ChestWorldBatch.skipped + ChestWorldBatch.failed;
            int total = Math.max(1, ChestWorldBatch.total);
            g.fill(x, y, x + w, y + 8, 0xFF1C232A);
            g.fill(x, y, x + w * done / total, y + 8, running ? 0xFF5C8DBF : 0xFF2F7D46);
            g.outline(x, y, w, 8, Theme.BORDER);
            y += 16;
            if (running) {
                g.text(font, (undoing ? "Putting back " : "Updating ") + done + " of " + total + "…", x, y, Theme.TEXT, false);
            } else {
                int ok = ChestWorldBatch.completed, skipped = ChestWorldBatch.skipped, failed = ChestWorldBatch.failed;
                String okText = undone ? "↺ " + ok + " put back as " + (ok == 1 ? "it was" : "they were") : "✔ " + ok + " updated";
                g.text(font, okText, x, y, ok > 0 ? Theme.GOOD : Theme.TEXT_DIM, false);
                int cx = x + font.width(okText) + 14;
                if (skipped > 0) { g.text(font, skipped + " skipped", cx, y, Theme.WARN, false); cx += font.width(skipped + " skipped") + 14; }
                if (failed > 0) g.text(font, failed + " failed", cx, y, Theme.BAD, false);
                y += 14;
                String note = ChestWorldBatch.stopped ? "Stopped: the rest were left as they were. " : ChestWorldBatch.problems.isEmpty() ? "All done. " : "";
                g.text(font, font.plainSubstrByWidth(note + "Changes are saved with the world as usual.", w), x, y, Theme.TEXT_DIM, false);
                if (!ChestWorldBatch.problems.isEmpty()) g.text(font, undone ? "Not put back:" : "Not updated:", x, y + 26, Theme.TEXT_DIM, false);
            }
        }
        if (!error.isEmpty()) g.text(font, font.plainSubstrByWidth(error, w), x, height - 42, Theme.BAD, false);
    }
}
