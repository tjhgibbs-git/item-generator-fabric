package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Review, progress and results for editing a group of world spawners together (World tab's
 * Edit all). Only what was changed in the editor is written; each copy keeps the rest.
 */
public final class SpawnerApplyScreen extends StacksmithScreen {
    /** The copies being edited together, the setup the editor started from, and the list to go back to. */
    public record Targets(String name, SpawnerSpec base, List<SpawnerIndex.Entry> entries, int setups, Screen list) {
        /** The same copies with their data as now indexed (after an update), and {@code base} as the new starting point. */
        Targets refreshed(SpawnerSpec newBase) {
            List<SpawnerIndex.Entry> fresh = entries.stream().map(e -> SpawnerIndex.entries.get(e.key())).filter(Objects::nonNull).toList();
            return new Targets(name, newBase, fresh, setups, list);
        }
    }

    private enum Stage { REVIEW, RUNNING, DONE }

    private final SpawnerEditorScreen editor;
    private final Targets targets;
    private final SpawnerSpec edited;
    private final SpawnerWorldBatch.Changes changes;
    private final ScrollPane problems = pane(new ScrollPane());
    private Stage stage = Stage.REVIEW;
    private Chip apply, stop;
    private String error = "";
    private int shownProblems = -1;

    SpawnerApplyScreen(SpawnerEditorScreen editor, Targets targets, SpawnerSpec edited) {
        super(Component.literal("Apply to " + targets.entries().size() + " spawners"), editor);
        this.editor = editor;
        this.targets = targets;
        this.edited = edited.copy();
        this.changes = SpawnerWorldBatch.changes(targets.base(), edited);
    }

    private int panelW() { return Math.min(width - 2 * PAD, 460); }
    private int panelX() { return (width - panelW()) / 2; }
    private int x() { return panelX() + 8; }
    private int w() { return panelW() - 16; }
    private int n() { return targets.entries().size(); }

    @Override
    protected void init() {
        int x = x(), w = w(), bottom = height - 28;
        if (stage == Stage.REVIEW) {
            addRenderableWidget(Chip.of("‹ Back to editor", 100, 20, this::onClose).at(x, bottom));
            apply = addRenderableWidget(Chip.of("Apply to " + n() + " spawners", 140, 20, this::start)
                    .primary(0xFF2F7D46).at(x + w - 140, bottom));
            apply.active = SpawnerWorldBatch.available() && !changes.isEmpty() && edited.problems().isEmpty();
        } else if (stage == Stage.RUNNING) {
            stop = addRenderableWidget(Chip.of("Stop", 70, 20, SpawnerWorldBatch::cancel).at(x + w - 70, bottom)
                    .tip("Stops before the next spawner. Ones already updated stay updated."));
        } else {
            addRenderableWidget(Chip.of("Keep editing", 100, 20, this::keepEditing).at(x, bottom)
                    .tip("Back to the editor to change these spawners some more"));
            addRenderableWidget(Chip.of("Back to the list", 120, 20, () -> minecraft.setScreenAndShow(targets.list()))
                    .primary(0xFF2E5E8C).at(x + w - 120, bottom));
            buildProblems();
        }
    }

    private void start() {
        if (SpawnerIndex.busy) { error = "Still scanning the world; try again in a moment."; return; }
        String why = SpawnerWorldBatch.startChanges(targets.entries(), targets.base(), edited);
        if (why != null) { error = why; return; }
        error = "";
        stage = Stage.RUNNING;
        rebuildWidgets();
    }

    private void keepEditing() {
        Session.spawnerOriginal = edited.copy();
        minecraft.setScreenAndShow(new SpawnerEditorScreen(targets.list(), targets.refreshed(edited.copy())));
    }

    /** Changing dimension on a visit replaces this screen: show it again once the game is back in a world. */
    @Override
    public void removed() {
        super.removed();
        // Checked a moment later: while this runs, the next screen isn't in place yet.
        if (stage == Stage.RUNNING) java.util.concurrent.CompletableFuture.runAsync(() -> minecraft.execute(() -> comeBack(0)),
                java.util.concurrent.CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    private void comeBack(int attempt) {
        var shown = minecraft.gui.screen();
        if (shown == this || attempt > 600 || minecraft.level == null) return;
        if (shown == null) { minecraft.setScreenAndShow(this); return; }
        if (!(shown instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(shown instanceof net.minecraft.client.gui.screens.GenericMessageScreen)) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> minecraft.execute(() -> comeBack(attempt + 1)),
                java.util.concurrent.CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Override
    public void tick() {
        super.tick();
        if (stage == Stage.RUNNING && !SpawnerWorldBatch.busy()) {
            stage = Stage.DONE;
            rebuildWidgets();
        }
    }

    private void buildProblems() {
        List<SpawnerWorldBatch.Problem> list = List.copyOf(SpawnerWorldBatch.problems);
        shownProblems = list.size();
        for (ScrollPane.Row r : problems.rows()) r.widgets().forEach(this::removeWidget);
        problems.clear();
        int top = 96;
        problems.bounds(x(), top, w(), height - 36 - top);
        for (SpawnerWorldBatch.Problem p : list) problems.add(new ProblemRow(p));
        addPaneWidgets(problems);
        problems.layout();
    }

    /** A copy that wasn't updated: where, why, and a way to go and look. */
    private final class ProblemRow implements ScrollPane.Row {
        final Chip tp;
        final SpawnerWorldBatch.Problem problem;

        ProblemRow(SpawnerWorldBatch.Problem p) {
            problem = p;
            tp = Chip.of("TP", 26, 14, () -> {
                if (SpawnerIndex.teleport(p.entry(), false, true) == null) minecraft.setScreenAndShow(null);
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

    // ---- drawing --------------------------------------------------------------------------------

    private List<String> changeLines() {
        List<String> out = new ArrayList<>();
        SpawnerSpec base = targets.base();
        if (changes.pool()) {
            String before = SpawnerLog.title(base), after = SpawnerLog.title(edited);
            String detail = SpawnerWorldScreen.detail(edited);
            out.add("Mobs: " + (before.equals(after) ? after + " with your changes" : before + " → " + after)
                    + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
        for (SpawnerSpec.Setting s : changes.settings()) {
            java.util.Map<Integer, Long> now = targets.entries().stream().collect(java.util.stream.Collectors.groupingBy(
                    e -> e.spec().get(s), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
            String before = now.size() == 1 ? value(s, now.keySet().iterator().next())
                    : now.entrySet().stream().map(v -> value(s, v.getKey()) + " on " + v.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
            out.add(settingName(s) + ": " + (now.size() == 1 ? before + " → " + value(s, edited.get(s))
                    : value(s, edited.get(s)) + " (now " + before + ")"));
        }
        return out;
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int px = panelX(), pw = panelW(), x = x(), w = w();
        panel(g, px, 8, pw, height - 14);
        g.text(font, font.plainSubstrByWidth("Edit all · " + targets.name() + " · " + n() + " spawners", w), x, 16, Theme.ACCENT, false);
        int y = 34;
        if (stage == Stage.REVIEW) {
            if (changes.isEmpty()) {
                y = wrap(g, "You haven't changed anything yet. Go back to the editor, change what these spawners should have, then apply.", y, Theme.TEXT_DIM);
            } else {
                g.text(font, "These changes go to all " + n() + ":", x, y, Theme.TEXT, false);
                y += 14;
                for (String line : changeLines()) {
                    y = wrap(g, "•  " + line, y, Theme.GOOD) + 2;
                }
                long broken = targets.entries().stream()
                        .filter(e -> SpawnerWorldBatch.conflict(SpawnerWorldBatch.preview(e.spec(), edited, changes)) != null).count();
                if (broken > 0) {
                    y = wrap(g, "⚠ " + broken + " of them would end up waiting at least longer than they wait at most, "
                            + "which breaks a spawner: they will be skipped. Set Wait at most too to include them.", y + 4, Theme.WARN) + 2;
                }
                y += 8;
                List<String> notes = new ArrayList<>();
                notes.add("Everything else stays as each spawner has it.");
                if (changes.pool() && targets.setups() > 1) notes.add("The mob list is replaced as a whole, including weights and light rules.");
                notes.add(minecraft.getSingleplayerServer() != null
                        ? "Far-away spawners are loaded for a moment. Near unexplored terrain, you may be taken there briefly and brought back."
                        : "Far-away saved chunks are loaded briefly. Targets near unexplored terrain are skipped; teleport there and edit them locally.");
                notes.add("A spawner changed since the last scan is left alone and listed.");
                for (String note : notes) y = wrap(g, note, y, Theme.TEXT_DIM) + 4;
            }
        } else {
            boolean running = stage == Stage.RUNNING;
            int done = SpawnerWorldBatch.completed + SpawnerWorldBatch.skipped + SpawnerWorldBatch.failed;
            int total = Math.max(1, SpawnerWorldBatch.total);
            g.fill(x, y, x + w, y + 8, 0xFF1C232A);
            g.fill(x, y, x + w * done / total, y + 8, running ? 0xFF5C8DBF : 0xFF2F7D46);
            g.outline(x, y, w, 8, Theme.BORDER);
            y += 16;
            if (running) {
                g.text(font, SpawnerWorldBatch.status().startsWith("Visiting") ? SpawnerWorldBatch.status()
                        : "Updating " + done + " of " + total + "…", x, y, Theme.TEXT, false);
            } else {
                int ok = SpawnerWorldBatch.completed, skipped = SpawnerWorldBatch.skipped, failed = SpawnerWorldBatch.failed;
                g.text(font, "✔ " + ok + " updated", x, y, ok > 0 ? Theme.GOOD : Theme.TEXT_DIM, false);
                int cx = x + font.width("✔ " + ok + " updated") + 14;
                if (skipped > 0) { g.text(font, skipped + " skipped", cx, y, Theme.WARN, false); cx += font.width(skipped + " skipped") + 14; }
                if (failed > 0) g.text(font, failed + " failed", cx, y, Theme.BAD, false);
                y += 14;
                String failure = SpawnerWorldBatch.failure;
                if (failure != null) {
                    // The server refused or the connection ended: say why rather than "All done".
                    wrap(g, failure, y, Theme.BAD);
                } else if (shownProblems > 0) {
                    g.text(font, "Not updated:", x, y + 26, Theme.TEXT_DIM, false);
                    wrap(g, "Changes are saved with the world as usual.", y, Theme.TEXT_FAINT);
                } else if (SpawnerWorldBatch.status.startsWith("Stopped")) {
                    wrap(g, "Stopped: the rest were left as they were. Changes are saved with the world as usual.", y, Theme.TEXT_DIM);
                } else {
                    wrap(g, "All done. Changes are saved with the world as usual.", y, Theme.TEXT_DIM);
                }
            }
        }
        if (!error.isEmpty()) g.text(font, font.plainSubstrByWidth(error, w), x, height - 42, Theme.BAD, false);
    }

    private int wrap(GuiGraphicsExtractor g, String text, int y, int color) {
        for (var line : font.split(Component.literal(text), w())) {
            g.text(font, line, x(), y, color, false);
            y += 10;
        }
        return y;
    }

    private static String value(SpawnerSpec.Setting s, int v) {
        return s == SpawnerSpec.Setting.MIN_DELAY || s == SpawnerSpec.Setting.MAX_DELAY
                ? (v % 20 == 0 ? Integer.toString(v / 20) : Double.toString(v / 20.0)) + " s" : Integer.toString(v);
    }

    private static String settingName(SpawnerSpec.Setting s) {
        return switch (s) {
            case MIN_DELAY -> "Wait at least";
            case MAX_DELAY -> "Wait at most";
            case COUNT -> "Mobs per spawn";
            case MAX_NEARBY -> "Pause with nearby";
            case PLAYER_RANGE -> "Runs with a player within";
            case SPAWN_RANGE -> "Mobs appear within";
            case DELAY -> "First spawn after";
        };
    }
}
