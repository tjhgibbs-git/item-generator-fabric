package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one review for changing several spawners together, however you got there: the World
 * tab's Edit all, or the editor's "Also change the others". It lists exactly which spawners
 * get what, lets you untick any of them, runs the update, and can undo it. Only what was
 * changed in the editor is written; each spawner keeps the rest.
 */
public final class SpawnerApplyScreen extends StacksmithScreen {
    /** The spawners being changed together, the setup the edit started from, and where to go back to. */
    public static final class Targets {
        private final String name;
        private final SpawnerSpec base;
        private final List<SpawnerIndex.Entry> entries;
        private final Screen list;
        /** The spawner the editor is tied to, when this is "this one and the others like it"; else null. */
        final String sourceKey;
        /** Found in loaded chunks and written with commands (a server without world hooks). */
        final boolean nearby;
        /** Spawners unticked in the review: left alone. */
        final Set<String> excluded = new HashSet<>();
        /**
         * Settings to write to every spawner even though the typed value is the usual one: the
         * copies don't all have it (a "mixed" field filled in while editing all).
         */
        final EnumSet<SpawnerSpec.Setting> unify = EnumSet.noneOf(SpawnerSpec.Setting.class);

        public Targets(String name, SpawnerSpec base, List<SpawnerIndex.Entry> entries, Screen list) {
            this(name, base, entries, list, null, false);
        }

        Targets(String name, SpawnerSpec base, List<SpawnerIndex.Entry> entries, Screen list, String sourceKey, boolean nearby) {
            this.name = name;
            this.base = base;
            this.entries = List.copyOf(entries);
            this.list = list;
            this.sourceKey = sourceKey;
            this.nearby = nearby;
        }

        public String name() { return name; }
        public SpawnerSpec base() { return base; }
        public List<SpawnerIndex.Entry> entries() { return entries; }
        public Screen list() { return list; }

        /** The ticked ones. */
        List<SpawnerIndex.Entry> selected() {
            return entries.stream().filter(e -> !excluded.contains(e.key())).toList();
        }

        /** How many different setups (everything but the countdown) the spawners have between them. */
        int setups() {
            return (int) entries.stream().map(e -> SpawnerWorldScreen.setupKey(e.spec())).distinct().count();
        }

        /** The values {@code setting} has across the spawners, with how many have each. */
        java.util.TreeMap<Integer, Long> values(SpawnerSpec.Setting setting) {
            return entries.stream().collect(java.util.stream.Collectors.groupingBy(e -> e.spec().get(setting),
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        }

        /**
         * The setup to compare {@code edited} against to see what changed: {@link #base}, except
         * that a setting in {@link #unify} always counts as changed.
         */
        SpawnerSpec diffBase(SpawnerSpec edited) {
            SpawnerSpec out = base.copy();
            for (SpawnerSpec.Setting s : unify) {
                if (out.get(s) == edited.get(s)) out.set(s, edited.get(s) == s.min ? s.min + 1 : edited.get(s) - 1);
            }
            return out;
        }

        SpawnerWorldBatch.Changes changes(SpawnerSpec edited) {
            return SpawnerWorldBatch.changes(diffBase(edited), edited);
        }

        /** The same spawners with their data as now indexed (after an update), and {@code newBase} as the new starting point. */
        Targets refreshed(SpawnerSpec newBase) {
            List<SpawnerIndex.Entry> fresh = entries.stream().map(e -> SpawnerIndex.entries.get(e.key())).filter(Objects::nonNull).toList();
            Targets out = new Targets(name, newBase, fresh, list, sourceKey, nearby);
            out.excluded.addAll(excluded);
            return out;
        }
    }

    private enum Stage { FINDING, REVIEW, RUNNING, DONE }

    /** The editor to go back to. */
    private final SpawnerEditorScreen editor;
    private Targets targets;
    private final SpawnerSpec edited;
    private SpawnerWorldBatch.Changes changes;
    private final ScrollPane rows = pane(new ScrollPane());
    private Stage stage;
    private Chip apply;
    private String error = "";
    private boolean undoing, undone;
    /** Nearby: commands went out for this many (the server doesn't confirm them). */
    private int sent = -1;
    private int listTop;

    // Finding the others like the linked spawner (from the editor).
    /** What to compare the edit against, and whose kind to look for. */
    private final SpawnerSpec saved;
    /** What the linked block holds now, as far as the editor knows. */
    private final SpawnerSpec held;
    private final BlockPos sourcePos;
    private final String sourceDimension;
    private int findStep;
    private boolean nearbyFound;

    /** Priming: only sets each spawner's countdown to 0 (the World tab's Prime all). */
    private boolean prime;

    /** Reviews priming {@code targets}: countdown to 0, nothing else. */
    SpawnerApplyScreen(Screen list, Targets targets) {
        super(Component.literal("Prime spawners"), list);
        this.editor = null;
        this.targets = targets;
        this.edited = targets.base().copy();
        this.changes = new SpawnerWorldBatch.Changes(false, EnumSet.noneOf(SpawnerSpec.Setting.class));
        this.saved = null;
        this.held = null;
        this.sourcePos = null;
        this.sourceDimension = null;
        this.stage = Stage.REVIEW;
        this.prime = true;
    }

    private boolean nothing() {
        return changes.isEmpty() && !prime;
    }

    /** Reviews a change to {@code targets} (the World tab's Edit all). */
    SpawnerApplyScreen(SpawnerEditorScreen editor, Targets targets, SpawnerSpec edited) {
        super(Component.literal("Review changes"), editor);
        this.editor = editor;
        this.targets = targets;
        this.edited = edited.copy();
        this.changes = targets.changes(edited);
        this.saved = null;
        this.held = null;
        this.sourcePos = null;
        this.sourceDimension = null;
        this.stage = Stage.REVIEW;
    }

    /**
     * From the editor on one spawner: finds the others of its kind (the world index, or loaded
     * chunks nearby on a server without hooks), then reviews giving them, and this one, what
     * was changed from {@code saved}. {@code held} is what this spawner's block holds now.
     */
    SpawnerApplyScreen(SpawnerEditorScreen editor, SpawnerSpec saved, SpawnerSpec held, SpawnerSpec edited, BlockPos pos, String dimension) {
        super(Component.literal("Review changes"), editor);
        this.editor = editor;
        this.edited = edited.copy();
        this.saved = saved.copy();
        this.held = held.copy();
        this.sourcePos = pos.immutable();
        this.sourceDimension = dimension;
        this.stage = Stage.FINDING;
    }

    private int panelW() { return Math.min(width - 2 * PAD, 460); }
    private int panelX() { return (width - panelW()) / 2; }
    private int x() { return panelX() + 8; }
    private int w() { return panelW() - 16; }

    /** Ticked spawners the change can be written to, and those it would break (skipped). */
    private List<SpawnerIndex.Entry> writable() {
        return targets.selected().stream().filter(e -> conflict(e) == null).toList();
    }

    private String conflict(SpawnerIndex.Entry e) {
        if (prime) return null;
        return SpawnerWorldBatch.conflict(SpawnerWorldBatch.preview(e.spec(), edited, changes));
    }

    private boolean isSource(SpawnerIndex.Entry e) {
        return e.key().equals(targets.sourceKey);
    }

    private static String spawners(int n) {
        return n + (n == 1 ? " spawner" : " spawners");
    }

    @Override
    protected void init() {
        int x = x(), w = w(), bottom = height - 28;
        if (stage == Stage.FINDING) {
            addRenderableWidget(Chip.of("‹ Back to editor", 100, 20, this::onClose).at(x, bottom));
        } else if (stage == Stage.REVIEW) {
            addRenderableWidget(Chip.of(prime ? "‹ Back to the list" : "‹ Back to editor", 100, 20, this::onClose).at(x, bottom));
            apply = addRenderableWidget(Chip.of("", 150, 20, this::start).primary(0xFF2F7D46).at(x + w - 150, bottom));
            listTop = 34 + changeHeight() + 18;
            int cx = x + w;
            if (!nothing() && targets.entries().size() > 1) {
                cx -= 36;
                addRenderableWidget(Chip.of("None", 36, 14, () -> select(e -> false)).at(cx, listTop - 16)
                        .tip("Untick every spawner"));
                if (!prime && targets.setups() > 1) {
                    String usual = SpawnerWorldScreen.setupKey(targets.base());
                    cx -= 60;
                    addRenderableWidget(Chip.of("Identical", 58, 14, () -> select(e -> SpawnerWorldScreen.setupKey(e.spec()).equals(usual)))
                            .at(cx, listTop - 16).tip("Tick only the ones set up exactly like the one you edited (the countdown aside)"));
                }
                cx -= 28;
                addRenderableWidget(Chip.of("All", 26, 14, () -> select(e -> true)).at(cx, listTop - 16).tip("Tick every spawner"));
            }
            buildRows();
            updateApply();
        } else if (stage == Stage.RUNNING) {
            addRenderableWidget(Chip.of("Stop", 70, 20, SpawnerWorldBatch::cancel).at(x + w - 70, bottom)
                    .tip("Stops before the next spawner. Ones already updated stay updated.")).active = !targets.nearby;
        } else {
            boolean fromOne = targets.sourceKey != null;
            String back = fromOne ? "Back to this spawner" : "Back to the list";
            addRenderableWidget(Chip.of(back, 124, 20, this::finish).primary(0xFF2E5E8C).at(x + w - 124, bottom));
            int bx = x;
            if (!fromOne && !prime) {
                addRenderableWidget(Chip.of("Keep editing", 84, 20, this::keepEditing).at(bx, bottom)
                        .tip("Back to the editor to change these spawners some more"));
                bx += 88;
            }
            if (!targets.nearby && !undone) {
                Chip undo = addRenderableWidget(Chip.of("↺ Undo", 60, 20, this::undo).at(bx, bottom));
                undo.active = SpawnerWorldBatch.canUndo();
                undo.tip(undo.active ? "Put every spawner this changed back as it was. One changed again since then is left alone."
                        : SpawnerWorldBatch.completed == 0 ? "Nothing was changed"
                        : "This server's Stacksmith can't undo: update it, or change the setting back and apply again");
            }
            listTop = 96;
            buildRows();
        }
    }

    private void select(java.util.function.Predicate<SpawnerIndex.Entry> keep) {
        targets.excluded.clear();
        for (SpawnerIndex.Entry e : targets.entries()) if (!keep.test(e)) targets.excluded.add(e.key());
        rebuildWidgets();
    }

    private void updateApply() {
        if (apply == null) return;
        int n = writable().size();
        apply.setMessage(Component.literal(nothing() ? "Nothing to apply" : n == 0 ? "None selected" : (prime ? "Prime " : "Apply to ") + spawners(n)));
        apply.active = n > 0 && !nothing() && (prime ? SpawnerWorldBatch.canPrime() : edited.problems().isEmpty())
                && (targets.nearby ? Delivery.canUse("setblock") && !CommandQueue.busy() : SpawnerWorldBatch.available());
        if (prime) {
            apply.tip("Sets the countdown of each ticked spawner to 0 and changes nothing else. You can undo afterwards (their countdowns come back).");
            return;
        }
        apply.tip(targets.nearby
                ? "Sends the commands for each ticked spawner. Each keeps its other settings and its countdown. "
                + "This server has no Stacksmith hooks, so the result isn't checked and can't be undone here."
                : "Writes these changes to each ticked spawner; everything else stays as each one has it. "
                + (minecraft.getSingleplayerServer() != null
                ? "Far-away ones are loaded for a moment; next to unexplored terrain you may be taken there briefly and brought back. "
                : "Far-away ones are loaded briefly; ones next to unexplored terrain are skipped. ")
                + "One changed since the last scan is left alone and listed. You can undo afterwards.");
    }

    // ---- finding the others ----------------------------------------------------------------------

    private void find() {
        if (SpawnerIndex.available()) {
            if (SpawnerIndex.busy) return;
            if (findStep == 0) {
                // Refreshes the spawners in loaded chunks, this one included.
                findStep = 1;
                SpawnerIndex.ensure(() -> {});
                return;
            }
            SpawnerIndex.Entry source = SpawnerIndex.entries.get(SpawnerIndex.key(sourceDimension, sourcePos));
            boolean fresh = source != null && source.spec().sameAs(held);
            if (!fresh && findStep == 1) {
                // The index hasn't caught up with this spawner (just placed or saved): scan properly.
                findStep = 2;
                SpawnerIndex.start(() -> {});
                return;
            }
            if (!SpawnerIndex.current() || !SpawnerIndex.indexed) {
                error = "Couldn't scan the world: " + SpawnerIndex.status;
                findStep = -1;
                return;
            }
            List<SpawnerIndex.Entry> list = new ArrayList<>(SpawnerIndex.sameKind(saved));
            // Already saved with different mobs, it is no longer of that kind, but still belongs here.
            if (source != null && !list.contains(source)) list.add(source);
            found(list, false);
        } else {
            if (findStep == 0) {
                findStep = 1;
                SpawnerBatch.radius = SpawnerBatch.MAX_RADIUS;
                SpawnerBatch.search(() -> nearbyFound = true);
                return;
            }
            if (!nearbyFound) {
                if (!SpawnerBatch.searching()) { error = SpawnerBatch.status().isEmpty() ? "Couldn't read the spawners nearby." : SpawnerBatch.status(); findStep = -1; }
                return;
            }
            String kind = SpawnerWorldScreen.mobsKey(saved);
            List<SpawnerIndex.Entry> list = new ArrayList<>();
            list.add(new SpawnerIndex.Entry(sourceDimension, sourcePos, held, held.toTag()));
            SpawnerBatch.entries().stream().filter(e -> SpawnerWorldScreen.mobsKey(e.spec()).equals(kind)).forEach(list::add);
            found(list, true);
        }
    }

    private void found(List<SpawnerIndex.Entry> list, boolean nearby) {
        String sourceKey = SpawnerIndex.key(sourceDimension, sourcePos);
        list.sort(Comparator.comparing((SpawnerIndex.Entry e) -> !e.key().equals(sourceKey))
                .thenComparing(e -> !e.dimension().equals(sourceDimension))
                .thenComparing(SpawnerIndex.Entry::dimension)
                .thenComparingDouble(e -> e.pos().distSqr(sourcePos)));
        String nick = SpawnerLibrary.starredName(saved);
        targets = new Targets(nick != null ? nick : SpawnerLog.title(saved), saved, list, parent, sourceKey, nearby);
        changes = targets.changes(edited);
        stage = Stage.REVIEW;
        rebuildWidgets();
    }

    // ---- running ---------------------------------------------------------------------------------

    private void start() {
        List<SpawnerIndex.Entry> go = writable();
        if (targets.nearby) {
            int n = go.size();
            String why = SpawnerBatch.applyChanges(go, edited, changes, new Commands.Style(Session.briefCommands, Session.target), () -> sent = n);
            if (why != null) { error = why; return; }
            SpawnerWorldBatch.problems.clear();
        } else {
            if (SpawnerIndex.busy) { error = "Still scanning the world; try again in a moment."; return; }
            String why = prime ? SpawnerWorldBatch.startPrime(go) : SpawnerWorldBatch.startChanges(go, targets.diffBase(edited), edited);
            if (why != null) { error = why; return; }
        }
        if (!prime) SpawnerLibrary.record(edited, "Applied to " + spawners(go.size()), null);
        error = "";
        stage = Stage.RUNNING;
        rebuildWidgets();
    }

    private void undo() {
        String why = SpawnerWorldBatch.undo();
        if (why != null) { error = why; return; }
        error = "";
        undoing = true;
        stage = Stage.RUNNING;
        rebuildWidgets();
    }

    private void keepEditing() {
        SpawnerSpec now = undone ? targets.base().copy() : edited.copy();
        WorldSpawner.link(now.copy(), null);
        Session.spawnerOriginal = now.copy();
        minecraft.setScreenAndShow(new SpawnerEditorScreen(targets.list(), targets.refreshed(now)));
    }

    /** Back to where this started: the World list, or the editor on the spawner, read again from the world. */
    private void finish() {
        if (targets.sourceKey == null) { minecraft.setScreenAndShow(targets.list()); return; }
        Screen back = editor.parent;
        if (WorldSpawner.exists(sourcePos) && sourceDimension.equals(minecraft.level.dimension().identifier().toString())) {
            WorldSpawner.load(sourcePos, () -> minecraft.setScreenAndShow(new SpawnerEditorScreen(back)));
        } else {
            minecraft.setScreenAndShow(editor);
        }
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
        if (stage == Stage.FINDING && findStep >= 0) find();
        if (stage == Stage.REVIEW) updateApply();
        if (stage == Stage.RUNNING && (targets.nearby ? sent >= 0 : !SpawnerWorldBatch.busy())) {
            stage = Stage.DONE;
            if (undoing) { undoing = false; undone = true; }
            rebuildWidgets();
        }
    }

    // ---- rows ------------------------------------------------------------------------------------

    private void buildRows() {
        for (ScrollPane.Row r : rows.rows()) r.widgets().forEach(this::removeWidget);
        rows.clear();
        rows.bounds(x(), listTop, w(), Math.max(20, height - 36 - listTop));
        if (stage == Stage.REVIEW) {
            if (!nothing()) for (SpawnerIndex.Entry e : targets.entries()) rows.add(new TargetRow(e));
        } else {
            for (SpawnerWorldBatch.Problem p : List.copyOf(SpawnerWorldBatch.problems)) rows.add(new ProblemRow(p));
        }
        addPaneWidgets(rows);
        rows.layout();
    }

    private String place(SpawnerIndex.Entry e) {
        String here = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        if (!here.equals(e.dimension())) return SpawnerWorldScreen.dimensionLabel(e.dimension());
        double d = minecraft.player == null ? 0 : Math.sqrt(minecraft.player.blockPosition().distSqr(e.pos()));
        return d < 6 ? "here" : Math.round(d) + " m";
    }

    /** A spawner in the review: a tick box, where it is, and what's notable about it. */
    private final class TargetRow implements ScrollPane.Row {
        final SpawnerIndex.Entry entry;
        final Chip tick;
        final String note;
        final int noteColor;

        TargetRow(SpawnerIndex.Entry e) {
            entry = e;
            String broken = conflict(e);
            boolean variant = !SpawnerWorldScreen.setupKey(e.spec()).equals(SpawnerWorldScreen.setupKey(targets.base()));
            if (prime) {
                int delay = e.spec().get(SpawnerSpec.Setting.DELAY);
                note = delay == 0 ? "already primed" : "now waits " + value(SpawnerSpec.Setting.MIN_DELAY, delay) + " first";
                noteColor = delay == 0 ? Theme.TEXT_FAINT : Theme.TEXT_DIM;
            } else if (broken != null) {
                int most = SpawnerWorldBatch.preview(e.spec(), edited, changes).get(SpawnerSpec.Setting.MAX_DELAY);
                note = "skipped: its Wait at most is " + value(SpawnerSpec.Setting.MAX_DELAY, most);
                noteColor = Theme.BAD;
            } else if (isSource(e)) {
                note = "the one you're editing";
                noteColor = Theme.ACCENT;
            } else if (variant) {
                note = "has " + SpawnerWorldScreen.differences(targets.base(), e.spec());
                noteColor = Theme.WARN;
            } else {
                note = "";
                noteColor = Theme.TEXT_DIM;
            }
            tick = Chip.of("", 14, 14, () -> {
                if (!targets.excluded.remove(e.key())) targets.excluded.add(e.key());
                updateApply();
            });
            tick.active = broken == null;
            SpawnerSpec after = SpawnerWorldBatch.preview(e.spec(), edited, changes);
            tick.setTooltip(Tooltip.create(Component.literal(prime ? "Untick to leave this one's countdown alone." : broken != null ? broken + ": that breaks a spawner, so it is left alone."
                    : "Now: " + SpawnerWorldScreen.settings(e.spec()) + "\n\nAfter: " + SpawnerWorldScreen.settings(after)
                    + (changes.pool() ? "\nSpawns " + SpawnerLog.title(after) : "") + "\n\nUntick to leave this one alone.")));
        }

        @Override public int layout(int x, int y, int w) {
            tick.setX(x + 1); tick.setY(y + 1);
            return 16;
        }

        @Override public void extract(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
            boolean on = tick.active && !targets.excluded.contains(entry.key());
            tick.setMessage(Component.literal(on ? "✔" : ""));
            tick.color(Theme.GOOD);
            int color = on ? Theme.TEXT : Theme.TEXT_FAINT;
            g.text(font, entry.where(), x + 20, y + 4, color, false);
            int cx = x + 20 + Math.max(font.width(entry.where()) + 8, 84);
            String place = place(entry);
            g.text(font, place, cx, y + 4, on ? Theme.TEXT_DIM : Theme.TEXT_FAINT, false);
            cx += Math.max(font.width(place) + 10, 50);
            if (!note.isEmpty() && cx < x + w - 20) {
                g.text(font, font.plainSubstrByWidth(note, x + w - 4 - cx), cx, y + 4, noteColor, false);
            }
        }

        @Override public List<AbstractWidget> widgets() { return List.of(tick); }
    }

    /** A spawner that wasn't updated: where, why, and a way to go and look. */
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
        if (prime) {
            long already = targets.selected().stream().filter(e -> e.spec().get(SpawnerSpec.Setting.DELAY) == 0).count();
            out.add("Countdown to the next spawn → 0: each fires as soon as a player is in range"
                    + (already > 0 ? " (" + already + " already " + (already == 1 ? "is" : "are") + ")" : ""));
            out.add("Nothing else about them changes");
            return out;
        }
        SpawnerSpec base = targets.base();
        if (changes.pool()) {
            String before = SpawnerLog.title(base), after = SpawnerLog.title(edited);
            String detail = SpawnerWorldScreen.detail(edited);
            out.add("Mobs: " + (before.equals(after) ? after + " with your changes" : before + " → " + after)
                    + (detail.isEmpty() ? "" : " (" + detail + ")")
                    + (targets.setups() > 1 ? " · replaces the whole mob list, weights and light rules" : ""));
        }
        for (SpawnerSpec.Setting s : changes.settings()) {
            java.util.Map<Integer, Long> now = targets.selected().stream().collect(java.util.stream.Collectors.groupingBy(
                    e -> e.spec().get(s), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
            if (now.isEmpty()) now = java.util.Map.of(base.get(s), 0L);
            String before = now.size() == 1 ? value(s, now.keySet().iterator().next())
                    : now.entrySet().stream().map(v -> value(s, v.getKey()) + " on " + v.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
            out.add(settingName(s) + ": " + (now.size() == 1 ? before + " → " + value(s, edited.get(s))
                    : value(s, edited.get(s)) + " (now " + before + ")"));
        }
        return out;
    }

    private int changeHeight() {
        if (nothing()) return 20;
        int h = 0;
        for (String line : changeLines()) h += font.split(Component.literal("•  " + line), w()).size() * 10 + 2;
        return h;
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int px = panelX(), pw = panelW(), x = x(), w = w();
        panel(g, px, 8, pw, height - 14);
        // The same amber as the editor's "all of them" bar: this screen always writes to several.
        g.fill(px, 8, px + pw, 26, 0xFF4A3A14);
        g.fill(px, 8, px + 3, 26, Theme.ACCENT);
        String title = prime ? "Prime · " + targets.name() : targets == null ? "Finding the spawners like this one…"
                : targets.sourceKey != null ? "This spawner and the others like it · " + targets.name()
                : "All " + targets.entries().size() + " · " + targets.name();
        g.text(font, font.plainSubstrByWidth(title, w), x, 13, 0xFFFFFFFF, false);
        int y = 34;
        if (stage == Stage.FINDING) {
            wrap(g, error.isEmpty() ? (SpawnerIndex.available() ? SpawnerIndex.status
                    : "Reading the spawners in loaded chunks within " + SpawnerBatch.MAX_RADIUS + " blocks… " + SpawnerBatch.status()) : "", y, Theme.TEXT_DIM);
        } else if (stage == Stage.REVIEW) {
            if (nothing()) {
                wrap(g, "You haven't changed anything yet. Go back to the editor, change what these spawners should have, then come back.", y, Theme.TEXT_DIM);
            } else {
                for (String line : changeLines()) y = wrap(g, "•  " + line, y, Theme.GOOD) + 2;
                int ticked = targets.selected().size(), go = writable().size(), all = targets.entries().size();
                String head = "Goes to " + go + " of " + all + (ticked > go ? " · " + (ticked - go) + " skipped" : "")
                        + (targets.nearby ? " · loaded chunks nearby only" : "");
                g.text(font, font.plainSubstrByWidth(head, w - 130), x, listTop - 13, ticked > go ? Theme.WARN : Theme.TEXT, false);
                g.fill(x, listTop - 2, x + w, listTop - 1, Theme.BORDER);
            }
        } else {
            boolean running = stage == Stage.RUNNING;
            if (targets.nearby) {
                wrap(g, running ? "Sending commands…" : "Commands sent for " + spawners(Math.max(0, sent)) + ". This server has no Stacksmith hooks, "
                        + "so the result isn't checked: look at one to make sure.", y, running ? Theme.TEXT : Theme.GOOD);
            } else {
                int done = SpawnerWorldBatch.completed + SpawnerWorldBatch.skipped + SpawnerWorldBatch.failed;
                int total = Math.max(1, SpawnerWorldBatch.total);
                g.fill(x, y, x + w, y + 8, 0xFF1C232A);
                g.fill(x, y, x + w * done / total, y + 8, running ? 0xFF5C8DBF : 0xFF2F7D46);
                g.outline(x, y, w, 8, Theme.BORDER);
                y += 16;
                if (running) {
                    g.text(font, SpawnerWorldBatch.status().startsWith("Visiting") ? SpawnerWorldBatch.status()
                            : (undoing ? "Putting back " : "Updating ") + done + " of " + total + "…", x, y, Theme.TEXT, false);
                } else {
                    int ok = SpawnerWorldBatch.completed, skipped = SpawnerWorldBatch.skipped, failed = SpawnerWorldBatch.failed;
                    String okText = undone ? "↺ " + ok + " put back as " + (ok == 1 ? "it was" : "they were") : "✔ " + ok + (prime ? " primed" : " updated");
                    g.text(font, okText, x, y, ok > 0 ? Theme.GOOD : Theme.TEXT_DIM, false);
                    int cx = x + font.width(okText) + 14;
                    if (skipped > 0) { g.text(font, skipped + " skipped", cx, y, Theme.WARN, false); cx += font.width(skipped + " skipped") + 14; }
                    if (failed > 0) g.text(font, failed + " failed", cx, y, Theme.BAD, false);
                    y += 14;
                    String failure = SpawnerWorldBatch.failure;
                    if (failure != null) {
                        // The server refused or the connection ended: say why rather than "All done".
                        wrap(g, failure, y, Theme.BAD);
                    } else if (!SpawnerWorldBatch.problems.isEmpty()) {
                        g.text(font, undone ? "Not put back:" : "Not updated:", x, y + 26, Theme.TEXT_DIM, false);
                        wrap(g, "Changes are saved with the world as usual.", y, Theme.TEXT_FAINT);
                    } else if (SpawnerWorldBatch.status.startsWith("Stopped")) {
                        wrap(g, "Stopped: the rest were left as they were. Changes are saved with the world as usual.", y, Theme.TEXT_DIM);
                    } else {
                        wrap(g, "All done. Changes are saved with the world as usual.", y, Theme.TEXT_DIM);
                    }
                }
            }
        }
        if (!error.isEmpty()) {
            int ey = stage == Stage.FINDING ? 34 : height - 42;
            g.text(font, font.plainSubstrByWidth(error, w), x, ey, Theme.BAD, false);
        }
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

    static String settingName(SpawnerSpec.Setting s) {
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
