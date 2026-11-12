package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** World inventory, with an explicit source snapshot only when entered through Find copies. */
public final class SpawnerBatchScreen extends StacksmithScreen {
    private enum Stage { FIND, OPTIONS, REVIEW, SENT }
    private Stage stage = Stage.FIND;
    private final SpawnerSpec reference;
    private final SpawnerSpec edited;
    private final String sourceDimension;
    private final BlockPos sourcePos;
    private static Object browseWorld;
    private static String browseQuery = "";
    private static int browsePage;
    private EditBox radius, search;
    private boolean wholeWorld = SpawnerIndex.available(), scanned, expanded, allSpawners;
    private boolean started;
    boolean dismissed;
    private int page;
    private String query = "";
    private List<SpawnerIndex.Entry> visibleEntries() {
        if (!SpawnerIndex.current()) return List.of();
        var source = allSpawners || reference == null ? new ArrayList<>(SpawnerIndex.entries.values()) : worldMatches();
        return source.stream().filter(e -> (e.dimension() + " " + e.pos().getX() + " " + e.pos().getY() + " " + e.pos().getZ() + " " + SpawnerLog.title(e.spec())).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))).toList();
    }
    private Chip primary, options, stopRemaining;
    private boolean radiusValid = true;
    private String error = "";
    private int submitted;
    private boolean wasSearching;

    /** The World tab always browses the world independently of the design being edited. */
    public SpawnerBatchScreen(Screen parent) {
        this(parent, false);
    }

    /** Find copies explicitly supplies a source; designing a spawner alone never filters World. */
    public SpawnerBatchScreen(Screen parent, boolean findCopies) {
        super(Component.literal("World spawners"), parent);
        reference = findCopies ? (Session.spawnerSaved == null ? Session.spawner.copy() : Session.spawnerSaved.copy()) : null;
        edited = findCopies ? Session.spawner.copy() : null;
        sourceDimension = findCopies ? Session.spawnerDimension : null;
        sourcePos = findCopies ? Session.spawnerPos : null;
        allSpawners = !findCopies;
        expanded = !findCopies;
        if (!findCopies) {
            Object current = Minecraft.getInstance().getSingleplayerServer();
            if (browseWorld != current) { browseWorld = current; browseQuery = ""; browsePage = 0; }
            query = browseQuery;
            page = browsePage;
        }
    }

    boolean filtersCopies() { return reference != null; }

    private List<SpawnerIndex.Entry> worldMatches() {
        return SpawnerIndex.matches(reference, sourceDimension, sourcePos);
    }

    @Override public void removed() {
        if (reference == null) { browseQuery = query; browsePage = page; }
        super.removed();
    }

    private int left() { return (width - panelWidth()) / 2; }
    private int panelWidth() { return Math.min(440, width - 28); }
    private boolean busy() { return SpawnerBatch.searching() || SpawnerBatch.writing() || SpawnerIndex.busy || SpawnerWorldBatch.busy(); }
    private boolean allowed() { return reference != null && (!wholeWorld || SpawnerWorldBatch.available()) && Delivery.canUse("data") && Delivery.canUse("setblock"); }
    private int count() { return wholeWorld ? worldMatches().size() : SpawnerBatch.matches(reference).size(); }
    private Chip chip(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Chip.of(text, w, 18, action).at(x, y));
    }
    private void show(Stage next) { stage = next; error = ""; rebuildWidgets(); }

    @Override
    protected void init() {
        header(Tab.WORLD, null, null, null, null, null);
        int x = left(), w = panelWidth(), bottom = height - 32;
        chip(stage == Stage.FIND || stage == Stage.SENT ? reference == null ? "Done" : "Back to editor" : "Back", x, bottom, 100,
                () -> { if (stage == Stage.FIND || stage == Stage.SENT) onClose(); else show(Stage.FIND); });
        if (stage == Stage.FIND) {
            chip("Whole world", x, 58, 110, () -> { wholeWorld = true; useSavedIndex(); rebuildWidgets(); })
                    .selected(() -> wholeWorld).active = !busy() && SpawnerIndex.available();
            if (reference != null) chip("Nearby…", x + 114, 58, 72, () -> { wholeWorld = false; scanned = SpawnerBatch.searched(); rebuildWidgets(); })
                    .selected(() -> !wholeWorld).active = !busy();
            chip(SpawnerIndex.busy || SpawnerBatch.searching() ? "Cancel" : "Update index", x + w - 100, 58, 100, () -> {
                if (SpawnerIndex.busy) SpawnerIndex.cancel(); else if (SpawnerBatch.searching()) SpawnerBatch.cancel(); else startSearch();
                rebuildWidgets();
            }).tip(wholeWorld && !SpawnerIndex.available() ? ServerHooks.REQUIRED : "Refresh the index")
                    .active = !SpawnerWorldBatch.busy() && (!wholeWorld || SpawnerIndex.available());
            search = addRenderableWidget(new EditBox(font, x, 82, w - (reference == null ? 0 : 120), 18, Component.literal("Search mob, dimension or coordinates")));
            search.setHint(Component.literal("Search mob, dimension, coordinates"));
            search.setValue(query);
            search.setResponder(t -> { query = t; page = 0; rebuildWidgets(); if (search != null) { setFocused(search); search.setCursorPosition(t.length()); } });
            if (reference != null) chip(allSpawners ? "All spawners" : "Matching copies", x + w - 116, 82, 116, () -> { allSpawners = !allSpawners; page = 0; rebuildWidgets(); });
            int total = wholeWorld ? visibleEntries().size() : count();
            chip((expanded ? "v " : "> ") + (allSpawners ? "Spawners" : "Copies") + " (" + total + ")", x, 106, w - (reference == null ? 0 : 110), () -> { expanded = !expanded; rebuildWidgets(); });
            if (reference != null) {
                options = chip("Options…", x + w - 104, 106, 104, () -> show(Stage.OPTIONS));
                options.active = !busy();
            }
            if (expanded && wholeWorld) {
                var results = visibleEntries();
                int perPage = Math.max(1, (height - 195) / 22);
                page = Math.min(page, Math.max(0, (results.size() - 1) / perPage));
                for (int i = page * perPage; i < Math.min(results.size(), (page + 1) * perPage); i++) {
                    var entry = results.get(i);
                    int rowY = 130 + (i % perPage) * 22;
                    chip((allSpawners ? SpawnerLog.title(entry.spec()) + " · " : "") + entry.dimension().replace("minecraft:", "") + " · " + entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ(), x, rowY, w - 76,
                            () -> openEntry(entry))
                            .tip(entry.dimension() + " · click to travel here and edit fresh data");
                    chip("Teleport", x + w - 72, rowY, 72, () -> { String why = SpawnerIndex.teleport(entry); if (why != null) error = why; }).active = Delivery.canUse("tp") && !busy();
                }
                if (results.size() > perPage) {
                    chip("‹", x, bottom - 24, 24, () -> { page--; rebuildWidgets(); }).active = page > 0;
                    chip("›", x + 28, bottom - 24, 24, () -> { page++; rebuildWidgets(); }).active = (page + 1) * perPage < results.size();
                }
            }
            if (reference != null) {
                primary = chip("Review " + copies(count()) + " →", x + w - 140, bottom, 140, () -> show(Stage.REVIEW));
                primary.active = allowed() && scanned && !busy() && count() > 0;
            }
            if (!started) { started = true; useSavedIndex(); }
        } else if (stage == Stage.OPTIONS) {
            chip("Exact copies", x, 77, w / 2 - 3, () -> { SpawnerBatch.match = SpawnerBatch.Match.IDENTICAL; rebuildWidgets(); })
                    .selected(() -> SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL)
                    .tip("The saved mob pool and all settings must match. The live countdown is ignored.");
            chip("Same mobs", x + w / 2 + 3, 77, w / 2 - 3, () -> { SpawnerBatch.match = SpawnerBatch.Match.SAME_MOBS; rebuildWidgets(); })
                    .selected(() -> SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS)
                    .tip("Matches the same mobs and gear in the same order. Ignores weights, light rules and settings; replaces the full pool.");
            if (SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS) {
                chip(SpawnerBatch.overwriteSettings ? "✓ Replace timing and ranges too" : "Keep each copy's timing and ranges", x, 127, w, () -> {
                    SpawnerBatch.overwriteSettings = !SpawnerBatch.overwriteSettings;
                    rebuildWidgets();
                }).selected(() -> SpawnerBatch.overwriteSettings);
            }
            if (!wholeWorld) {
                radius = addRenderableWidget(new EditBox(font, x + w - 54, 178, 54, 18, Component.literal("Nearby radius")));
                radius.setValue(Integer.toString(SpawnerBatch.radius));
                radius.setResponder(t -> { try { int r = Integer.parseInt(t); radiusValid = r > 0 && r <= 128; if (radiusValid) { SpawnerBatch.radius = r; scanned = false; } } catch (NumberFormatException e) { radiusValid = false; } });
            }
            chip("Use these options", x + w - 130, bottom, 130, () -> show(Stage.FIND));
        } else if (stage == Stage.REVIEW) {
            chip("Locations v", x + w - 110, 56, 110, () -> { expanded = true; show(Stage.FIND); });
            primary = chip("Apply to " + copies(count()), x + w - 140, bottom, 140, this::apply);
        } else {
            if (wholeWorld && SpawnerWorldBatch.busy()) stopRemaining = chip("Stop remaining", x, 135, 120, SpawnerWorldBatch::cancel);
            primary = chip("Back to results", x + w - 140, bottom, 140, () -> { show(Stage.FIND); useSavedIndex(); });
        }
    }

    private void openEntry(SpawnerIndex.Entry entry) {
        if (busy()) return;
        String why = SpawnerIndex.teleport(entry);
        if (why != null) { error = why; return; }
        error = "Travelling to spawner…";
        awaitEntry(entry, 0);
    }

    private void awaitEntry(SpawnerIndex.Entry entry, int attempt) {
        if (dismissed) return;
        var shown = minecraft.gui.screen();
        if (shown != null && shown != this && !(shown instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(shown instanceof net.minecraft.client.gui.screens.GenericMessageScreen)) return;
        if (minecraft.level != null && minecraft.level.dimension().identifier().toString().equals(entry.dimension())
                && minecraft.level.getBlockEntity(entry.pos()) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
            WorldSpawner.load(entry.pos(), () -> minecraft.setScreenAndShow(new SpawnerEditorScreen(parent)));
            return;
        }
        if (attempt >= 100) { error = "Spawner unavailable after travel. Rescan to refresh this location."; return; }
        java.util.concurrent.CompletableFuture.runAsync(() -> minecraft.execute(() -> awaitEntry(entry, attempt + 1)),
                java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    private void useSavedIndex() {
        if (!wholeWorld) { scanned = SpawnerBatch.searched(); return; }
        if (!SpawnerIndex.available()) {
            error = "World browsing requires compatible Stacksmith server hooks.";
            return;
        }
        scanned = SpawnerIndex.current() && SpawnerIndex.indexed;
        SpawnerIndex.ensure(() -> {
            scanned = SpawnerIndex.current() && SpawnerIndex.indexed;
            if (minecraft.gui.screen() == this) rebuildWidgets();
        });
    }

    private void startSearch() {
        if (busy()) return;
        scanned = false;
        Runnable done = () -> { scanned = wholeWorld ? SpawnerIndex.current() && SpawnerIndex.indexed : SpawnerBatch.searched(); if (minecraft.gui.screen() == this) rebuildWidgets(); };
        if (wholeWorld) {
            if (!SpawnerIndex.available()) { error = "Whole-world discovery requires compatible Stacksmith server hooks. Choose Nearby."; return; }
            SpawnerIndex.start(done);
        } else SpawnerBatch.search(done);
    }

    @Override public void onClose() {
        dismissed = true;
        if (SpawnerBatch.searching()) SpawnerBatch.cancel();
        super.onClose();
    }

    @Override public void tick() {
        super.tick();
        if (stopRemaining != null) stopRemaining.active = SpawnerWorldBatch.busy();
        if (stage == Stage.SENT && primary != null) primary.active = !busy();
        boolean searching = SpawnerIndex.busy || SpawnerBatch.searching();
        if (wasSearching && !searching && stage == Stage.FIND) { scanned = wholeWorld ? SpawnerIndex.current() && SpawnerIndex.indexed : SpawnerBatch.searched(); rebuildWidgets(); }
        wasSearching = searching;
        if (primary != null && stage == Stage.FIND) primary.active = allowed() && scanned && !busy() && count() > 0;
    }

    private void apply() {
        if (reference == null || busy() || count() == 0) return;
        submitted = count();
        String why = wholeWorld ? SpawnerWorldBatch.start(worldMatches(), edited,
                SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL || SpawnerBatch.overwriteSettings)
                : SpawnerBatch.apply(reference, edited, new Commands.Style(Session.briefCommands, Session.target));
        if (why != null) { error = why; return; }
        show(Stage.SENT);
    }

    private void line(GuiGraphicsExtractor g, String text, int y, int color) {
        g.text(font, font.plainSubstrByWidth(text, panelWidth()), left(), y, color, false);
    }
    private int wrap(GuiGraphicsExtractor g, String text, int y, int color) {
        for (var line : font.split(Component.literal(text), panelWidth())) {
            g.text(font, line, left(), y, color, false);
            y += 10;
        }
        return y;
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = left(), w = panelWidth();
        panel(g, x - 9, 26, w + 18, height - 36);
        line(g, stage == Stage.REVIEW ? "Review changes" : stage == Stage.SENT ? "Update results"
                : reference == null ? "World spawners" : "Find copies · " + SpawnerLog.title(reference), 34, Theme.ACCENT);
        if (stage == Stage.FIND) {
            if (!expanded) {
                String status = wholeWorld ? SpawnerIndex.status : SpawnerBatch.status();
                wrap(g, status.isBlank() ? "Discover spawners, then expand Copies to inspect or teleport." : status, 137, Theme.TEXT_DIM);
                wrap(g, wholeWorld ? reference == null ? "Saved results across all dimensions. Update index checks changed regions." : "Source: " + SpawnerLog.title(reference) + (sourcePos == null ? " · design template" : " · source block excluded")
                        : "Loaded chunks only · " + SpawnerBatch.radius + " blocks around source.", 169, Theme.TEXT_DIM);
            } else if (wholeWorld && !visibleEntries().isEmpty()) {
                String coverage = SpawnerIndex.status;
                int offset = visibleEntries().size() > Math.max(1, (height - 195) / 22) ? 62 : 0;
                g.text(font, font.plainSubstrByWidth(coverage, w - offset), x + offset, height - 49, SpawnerIndex.skipped > 0 ? Theme.WARN : Theme.TEXT_DIM, false);
                if (mouseX >= x + offset && mouseX < x + w && mouseY >= height - 51 && mouseY < height - 38)
                    g.setTooltipForNextFrame(font, Component.literal(coverage + (SpawnerIndex.savedAt > 0 ? "\nSaved regions checked: " + java.time.Instant.ofEpochMilli(SpawnerIndex.savedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm")) : "")), mouseX, mouseY);
            } else if (wholeWorld && visibleEntries().isEmpty()) {
                wrap(g, SpawnerIndex.busy ? SpawnerIndex.status : reference == null ? "No spawners match. Try another search or Update index." : "No matching copies. Try All spawners or Update index.", 136, Theme.TEXT_DIM);
            }
        } else if (stage == Stage.OPTIONS) {
            line(g, "Which saved spawners should match?", 60, Theme.TEXT_DIM);
            wrap(g, SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL
                    ? "Recommended: rebalance copies together. Matches all mobs, gear, light rules and settings."
                    : "Matches the same mobs and gear in order, with any weights, light rules or settings. The full pool will be replaced.", 104, Theme.TEXT_DIM);
            wrap(g, "Every copy keeps its own live spawn countdown.", 159, Theme.TEXT_DIM);
            if (!wholeWorld) line(g, "Nearby radius (1–128 blocks)", 182, Theme.TEXT_DIM);
        } else if (stage == Stage.REVIEW) {
            line(g, "Matching: " + copies(count()), 59, Theme.GOOD);
            List<String> changes = new ArrayList<>();
            if (SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS || !reference.withPoolOf(edited).sameAs(reference)) changes.add("Mob pool, gear or light rules → edited version");
            if (SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL || SpawnerBatch.overwriteSettings) {
                for (SpawnerSpec.Setting s : SpawnerSpec.Setting.values()) {
                    if (s != SpawnerSpec.Setting.DELAY && (reference.get(s) != edited.get(s) || SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS && SpawnerBatch.overwriteSettings)) {
                        changes.add(settingName(s) + ": " + (SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS ? "set to " : settingValue(s, reference.get(s)) + " → ") + settingValue(s, edited.get(s)));
                    }
                }
            }
            if (changes.isEmpty()) changes.add("No changes to the saved definition.");
            int y = 78;
            for (String change : changes) { line(g, "• " + change, y, Theme.TEXT); y += 11; }
            wrap(g, wholeWorld ? "Keeps live countdowns; skips changed targets and unloaded chunks near unexplored edges. Source saved separately."
                    : "Keeps scan countdowns. Source saved separately in the editor.", Math.max(y + 6, 161), Theme.TEXT_DIM);
            primary.active = !busy() && count() > 0 && edited.problems().isEmpty() && !changes.getFirst().startsWith("No changes");
        } else {
            if (wholeWorld) {
                wrap(g, SpawnerWorldBatch.status(), 65, Theme.TEXT);
            } else {
            wrap(g, copies(submitted) + " queued for update. " + (SpawnerBatch.writing() ? "Sending commands…" : "Commands have been sent."), 65, Theme.GOOD);
            wrap(g, "These are command submissions; server acceptance has not been verified. Search again to inspect the result.", 100, Theme.TEXT_DIM);
            }
        }
        if (!error.isEmpty()) line(g, error, height - 47, Theme.BAD);
    }

    private static String copies(int count) {
        return count + (count == 1 ? " copy" : " copies");
    }

    private static String settingValue(SpawnerSpec.Setting setting, int value) {
        return setting == SpawnerSpec.Setting.MIN_DELAY || setting == SpawnerSpec.Setting.MAX_DELAY
                ? (value / 20.0) + " s" : Integer.toString(value);
    }

    private static String settingName(SpawnerSpec.Setting setting) {
        return switch (setting) {
            case MIN_DELAY -> "Minimum delay";
            case MAX_DELAY -> "Maximum delay";
            case COUNT -> "Mobs per spawn";
            case MAX_NEARBY -> "Nearby mob limit";
            case PLAYER_RANGE -> "Activation range";
            case SPAWN_RANGE -> "Spawn range";
            case DELAY -> "Countdown";
        };
    }
}
