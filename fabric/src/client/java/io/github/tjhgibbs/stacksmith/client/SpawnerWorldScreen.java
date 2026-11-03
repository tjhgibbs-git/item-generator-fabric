package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The World tab: every spawner in this singleplayer world, one row per kind (same mobs, gear
 * and data). A kind opens into its locations: click one to teleport onto it, Edit to travel
 * there and edit it, or Edit all to change every copy at once without moving.
 */
public final class SpawnerWorldScreen extends StacksmithScreen {
    /** One kind of spawner, as listed: the copies that pass the search and dimension filter. */
    record Group(String key, String title, String detail, String nickname, SpawnerSpec base, String baseSetup,
                 List<SpawnerIndex.Entry> entries, int total, int setups, boolean matchedByLocation) {}

    // Remembered while the game runs, for the world they were made in.
    private static Object stateWorld;
    private static String query = "";
    /** Dimension shown, or null for all. */
    private static String dimension;
    private static final Set<String> open = new HashSet<>();
    private static double scroll;
    /** The location teleported to last, marked in the list. */
    private static String lastVisited;
    /** Locations teleported to or edited this session. */
    private static final Set<String> visited = new HashSet<>();
    /**
     * Where the list is sorted from: where you stood when it was first shown (or rescanned).
     * Fixed so the order doesn't jump around while you teleport from one spawner to the next.
     */
    private static net.minecraft.core.BlockPos anchor;
    private static String anchorDimension;

    private static final int TOP = 24;
    private final ScrollPane list = pane(new ScrollPane());
    private EditBox search;
    private Chip rescan, dimChip, expandAll;
    private List<Group> groups = List.of();
    private Map<String, String> setupOf = new HashMap<>();
    private int total, kinds;
    private Map<String, Integer> perDimension = new TreeMap<>();
    private int shownVersion = -1;
    private boolean shownBusy, started;
    boolean dismissed;
    private String message = "";
    private int messageColor = Theme.TEXT_DIM;

    public SpawnerWorldScreen(Screen parent) {
        super(Component.literal("World spawners"), parent);
        Object world = Minecraft.getInstance().getSingleplayerServer();
        if (world != stateWorld) {
            stateWorld = world;
            query = "";
            dimension = null;
            open.clear();
            scroll = 0;
            lastVisited = null;
            visited.clear();
            anchor = null;
        }
    }

    // ---- layout -------------------------------------------------------------------------------

    private int panelW() { return Math.min(width - 2 * PAD, 640); }
    private int panelX() { return (width - panelW()) / 2; }
    private int innerX() { return panelX() + 6; }
    private int innerW() { return panelW() - 12; }
    private int listTop() { return TOP + 36; }
    private int listBottom() { return height - 30; }

    @Override
    protected void init() {
        header(Tab.WORLD, null, null, null, null, null);
        int x = innerX(), w = innerW();
        int rescanW = 62, dimW = 92;
        search = addRenderableWidget(new EditBox(font, x, TOP + 4, w - rescanW - dimW - 8, 16, Component.literal("Search")));
        search.setHint(Component.literal("Search mobs, names, gear or coordinates"));
        search.setMaxLength(100);
        search.setValue(query);
        search.setResponder(t -> {
            if (t.equals(query)) return;
            query = t;
            list.scroll(0);
            refilter();
        });
        dimChip = addRenderableWidget(Chip.of(dimensionLabel(dimension), dimW, 16, this::cycleDimension)
                .at(x + w - rescanW - 4 - dimW, TOP + 4)
                .tip("Which dimension to list. Click to switch."));
        rescan = addRenderableWidget(Chip.of("Rescan", rescanW, 16, this::rescan).at(x + w - rescanW, TOP + 4));
        rescan.tip("Save the world and look for spawners again (only regions that changed are re-read). "
                + "Spawners near you are always up to date when this tab opens.");
        expandAll = addRenderableWidget(Chip.of("Open all", 70, 18, this::toggleAll).at(x, height - 25));
        addRenderableWidget(Chip.of("Done", 60, 18, this::onClose).at(x + w - 60, height - 25));
        list.bounds(x, listTop(), w, listBottom() - listTop());
        if (!started) {
            started = true;
            if (SpawnerIndex.available()) {
                SpawnerIndex.ensure(() -> {});
            } else {
                message("Listing a world's spawners needs a singleplayer world (or one you host on LAN).", Theme.WARN);
            }
        }
        regroup();
        updateChips();
        if (!query.isEmpty() || groups.isEmpty()) focusSoon(search);
    }

    // ---- data -----------------------------------------------------------------------------------

    /** What makes two spawners the same kind: the same mobs (with gear and data) in the same order. */
    static String mobsKey(SpawnerSpec spec) {
        return spec.entries().stream().map(e -> Snbt.write(e.entity.toTag())).collect(Collectors.joining("|"));
    }

    /** Everything that makes two copies identical, except the running countdown. */
    static String setupKey(SpawnerSpec spec) {
        Nbt.Compound tag = spec.toTag();
        tag.remove(SpawnerSpec.Setting.DELAY.key);
        return Snbt.write(tag);
    }

    /** Groups the index, then applies the search and dimension filter. */
    private void regroup() {
        shownVersion = SpawnerIndex.version;
        shownBusy = SpawnerIndex.busy;
        refilter();
    }

    private void refilter() {
        Map<String, List<SpawnerIndex.Entry>> byKind = new LinkedHashMap<>();
        perDimension = new TreeMap<>(Comparator.comparingInt(SpawnerWorldScreen::dimensionOrder).thenComparing(d -> d));
        setupOf = new HashMap<>();
        total = 0;
        if (SpawnerIndex.current()) {
            for (SpawnerIndex.Entry e : SpawnerIndex.entries.values()) {
                byKind.computeIfAbsent(mobsKey(e.spec()), k -> new ArrayList<>()).add(e);
                perDimension.merge(e.dimension(), 1, Integer::sum);
                total++;
            }
        }
        if (dimension != null && !perDimension.containsKey(dimension)) dimension = null;
        kinds = byKind.size();
        String q = query.strip();
        List<Group> out = new ArrayList<>();
        for (var kind : byKind.entrySet()) {
            List<SpawnerIndex.Entry> all = kind.getValue();
            Map<String, Integer> setups = new LinkedHashMap<>();
            Map<String, SpawnerSpec> first = new HashMap<>();
            for (SpawnerIndex.Entry e : all) {
                String s = setupKey(e.spec());
                setupOf.put(e.key(), s);
                setups.merge(s, 1, Integer::sum);
                first.putIfAbsent(s, e.spec());
            }
            String main = setups.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            // The live countdown is whichever copy came first's: not part of the kind, so back to the default.
            SpawnerSpec base = first.get(main).copy().set(SpawnerSpec.Setting.DELAY, SpawnerSpec.Setting.DELAY.defaultValue);
            String title = SpawnerLog.title(base), detail = detail(base);
            String nickname = SpawnerLibrary.starredName(base);
            String kindText = SpawnerLog.searchText(base, nickname, detail);
            boolean kindMatches = SpawnerLog.matches(kindText, q);
            List<SpawnerIndex.Entry> shown = all.stream()
                    .filter(e -> dimension == null || e.dimension().equals(dimension))
                    .filter(e -> kindMatches || SpawnerLog.matches(locationText(e), q))
                    .sorted(nearestFirst())
                    .toList();
            if (shown.isEmpty()) continue;
            out.add(new Group(kind.getKey(), title, detail, nickname, base, main, shown, all.size(), setups.size(),
                    !q.isEmpty() && !kindMatches));
        }
        out.sort(Comparator.comparingInt((Group g) -> -g.entries().size())
                .thenComparing(g -> g.title().toLowerCase(Locale.ROOT)).thenComparing(Group::key));
        groups = out;
        buildList();
    }

    private static String locationText(SpawnerIndex.Entry e) {
        return (dimensionLabel(e.dimension()) + " " + e.dimension() + " " + e.where()).toLowerCase(Locale.ROOT);
    }

    /** The anchor's dimension first, nearest the anchor first; other dimensions after, nearest the origin first. */
    private Comparator<SpawnerIndex.Entry> nearestFirst() {
        if (anchor == null && minecraft.player != null) {
            anchor = minecraft.player.blockPosition();
            anchorDimension = here();
        }
        String from = anchorDimension == null ? "" : anchorDimension;
        var at = anchor == null ? net.minecraft.core.BlockPos.ZERO : anchor;
        return Comparator.comparing((SpawnerIndex.Entry e) -> !e.dimension().equals(from))
                .thenComparingInt(e -> dimensionOrder(e.dimension()))
                .thenComparing(SpawnerIndex.Entry::dimension)
                .thenComparingDouble(e -> e.pos().distSqr(e.dimension().equals(from) ? at : net.minecraft.core.BlockPos.ZERO))
                .thenComparing(SpawnerIndex.Entry::key);
    }

    private String here() {
        return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    }

    // ---- the list -------------------------------------------------------------------------------

    private boolean isOpen(Group g) {
        return open.contains(g.key()) || g.matchedByLocation();
    }

    private void buildList() {
        double keep = list.rows().isEmpty() ? scroll : list.scroll();
        for (ScrollPane.Row r : list.rows()) r.widgets().forEach(this::removeWidget);
        list.clear();
        for (Group g : groups) {
            list.add(new GroupRow(g));
            if (isOpen(g)) {
                int coordsW = g.entries().stream().mapToInt(e -> font.width(e.where())).max().orElse(60) + 10;
                for (int i = 0; i < g.entries().size(); i++) list.add(new LocationRow(g, g.entries().get(i), i, coordsW));
            }
        }
        addPaneWidgets(list);
        list.layout();
        list.scroll(keep);
        updateChips();
    }

    private void updateChips() {
        if (rescan == null) return;
        rescan.setMessage(Component.literal(SpawnerIndex.busy ? "Cancel" : "Rescan"));
        rescan.active = SpawnerIndex.available() && !SpawnerWorldBatch.busy();
        dimChip.setMessage(Component.literal(dimensionLabel(dimension)));
        dimChip.active = perDimension.size() > 1 || dimension != null;
        boolean anyClosed = groups.stream().anyMatch(g -> !isOpen(g));
        expandAll.setMessage(Component.literal(anyClosed ? "Open all" : "Close all"));
        expandAll.active = !groups.isEmpty();
    }

    private void toggle(Group g) {
        if (!open.remove(g.key())) open.add(g.key());
        buildList();
    }

    private void toggleAll() {
        boolean anyClosed = groups.stream().anyMatch(g -> !isOpen(g));
        if (anyClosed) groups.forEach(g -> open.add(g.key()));
        else groups.forEach(g -> open.remove(g.key()));
        buildList();
    }

    private void cycleDimension() {
        List<String> dims = new ArrayList<>(perDimension.keySet());
        int i = dimension == null ? -1 : dims.indexOf(dimension);
        dimension = i + 1 >= dims.size() ? null : dims.get(i + 1);
        list.scroll(0);
        refilter();
    }

    private void rescan() {
        if (SpawnerIndex.busy) {
            SpawnerIndex.cancel();
        } else if (SpawnerIndex.available()) {
            message("", Theme.TEXT_DIM);
            anchor = null; // re-sort from where you are now
            SpawnerIndex.start(() -> {});
        }
        updateChips();
    }

    // ---- actions --------------------------------------------------------------------------------

    /** Puts you on top of the spawner looking at it, and closes the list (J then edits it). */
    private void teleport(Group g, SpawnerIndex.Entry e, int index) {
        String why = SpawnerIndex.teleport(e, false, true);
        if (why != null) { message(why, Theme.BAD); return; }
        lastVisited = e.key();
        visited.add(e.key());
        scroll = list.scroll();
        String name = g.nickname() != null ? g.nickname() : g.title();
        String which = g.entries().size() > 1 ? " (" + (index + 1) + " of " + g.entries().size() + ")" : "";
        minecraft.setScreenAndShow(null);
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(name + " spawner" + which + " · press J to edit it"));
        }
    }

    /** Travels to the spawner and opens it in the editor with fresh data; Done comes back here. */
    private void edit(SpawnerIndex.Entry e) {
        if (SpawnerWorldBatch.busy()) { message("Wait for the update to finish.", Theme.WARN); return; }
        boolean there = here().equals(e.dimension()) && WorldSpawner.exists(e.pos())
                && minecraft.player != null && minecraft.player.blockPosition().distSqr(e.pos()) < 48 * 48;
        if (!there) {
            String why = SpawnerIndex.teleport(e, false, true);
            if (why != null) { message(why, Theme.BAD); return; }
            message("Travelling to the spawner…", Theme.TEXT_DIM);
        }
        lastVisited = e.key();
        visited.add(e.key());
        awaitSpawner(e, 0);
    }

    private void awaitSpawner(SpawnerIndex.Entry e, int attempt) {
        if (dismissed) return;
        var shown = minecraft.gui.screen();
        if (shown != null && shown != this && !(shown instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(shown instanceof net.minecraft.client.gui.screens.GenericMessageScreen)) return;
        if (here().equals(e.dimension()) && WorldSpawner.exists(e.pos())) {
            WorldSpawner.load(e.pos(), () -> minecraft.setScreenAndShow(new SpawnerEditorScreen(this)));
            return;
        }
        if (attempt >= 100) {
            if (shown == null) minecraft.setScreenAndShow(this);
            message("There's no spawner at " + e.where() + " any more. Rescan to update the list.", Theme.BAD);
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> minecraft.execute(() -> awaitSpawner(e, attempt + 1)),
                java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    /** Opens the editor on this kind's most common setup; applying writes your changes to every listed copy. */
    private void editAll(Group g) {
        if (SpawnerWorldBatch.busy()) { message("Wait for the update to finish.", Theme.WARN); return; }
        scroll = list.scroll();
        WorldSpawner.link(g.base().copy(), null);
        Session.spawnerOriginal = g.base().copy();
        String name = g.nickname() != null ? g.nickname() : g.title();
        minecraft.setScreenAndShow(new SpawnerEditorScreen(this,
                new SpawnerApplyScreen.Targets(name, g.base().copy(), g.entries(), g.setups(), this)));
    }

    private void message(String text, int color) {
        message = text;
        messageColor = color;
    }

    // ---- update and drawing ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (SpawnerIndex.version != shownVersion || SpawnerIndex.busy != shownBusy) regroup();
    }

    @Override
    public void removed() {
        scroll = list.scroll();
        super.removed();
    }

    @Override
    public void onClose() {
        dismissed = true;
        super.onClose();
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int px = panelX(), pw = panelW(), x = innerX(), w = innerW();
        panel(g, px, TOP - 2, pw, height - TOP - 2);
        // Summary: how many, of how many kinds, where; and how fresh the scan is.
        int y = TOP + 25;
        String summary = total == 0 ? "" : total + (total == 1 ? " spawner" : " spawners") + " · "
                + kinds + (kinds == 1 ? " kind" : " kinds")
                + (perDimension.size() > 1 ? " · " + perDimension.entrySet().stream()
                .map(d -> dimensionLabel(d.getKey()) + " " + d.getValue()).collect(Collectors.joining(", ")) : "");
        String state;
        int stateColor = Theme.TEXT_DIM;
        if (SpawnerIndex.busy) {
            state = SpawnerIndex.status;
            stateColor = Theme.ACCENT;
        } else if (SpawnerIndex.skipped > 0) {
            state = SpawnerIndex.skipped + " chunks unread: list may be incomplete";
            stateColor = Theme.WARN;
        } else if (SpawnerIndex.savedAt > 0 && SpawnerIndex.current()) {
            state = "Scanned " + SpawnerLog.ago(SpawnerIndex.savedAt, System.currentTimeMillis());
        } else {
            state = "";
        }
        int stateW = font.width(state);
        g.text(font, font.plainSubstrByWidth(summary, Math.max(0, w - stateW - 10)), x, y, Theme.TEXT_DIM, false);
        g.text(font, state, x + w - stateW, y, stateColor, false);
        if (!state.isEmpty() && mouseX >= x + w - stateW && mouseX < x + w && mouseY >= y - 2 && mouseY < y + 10) {
            g.setTooltipForNextFrame(font, font.split(Component.literal(SpawnerIndex.status), 220), mouseX, mouseY);
        }
        g.fill(x, listTop() - 3, x + w, listTop() - 2, Theme.BORDER);
        if (groups.isEmpty()) {
            String empty = !SpawnerIndex.available() ? "Open a singleplayer world to list its spawners."
                    : SpawnerIndex.busy && !SpawnerIndex.indexed ? "Looking for spawners in every dimension…"
                    : total == 0 ? "No spawners found in this world yet. Place some, then Rescan."
                    : "No spawners match" + (query.isBlank() ? "" : " “" + query.strip() + "”")
                    + (dimension == null ? "." : " in " + dimensionLabel(dimension) + ".");
            int ty = listTop() + 14;
            for (var line : font.split(Component.literal(empty), w - 20)) {
                g.centeredText(font, line, x + w / 2, ty, Theme.TEXT_DIM);
                ty += 11;
            }
        }
        if (!message.isEmpty()) {
            int mx = x + 76, mw = w - 76 - 66;
            g.text(font, font.plainSubstrByWidth(message, mw), mx, height - 20, messageColor, false);
        } else if (!groups.isEmpty()) {
            String hint = "Click a kind to see where they are; click a location to teleport onto it.";
            g.text(font, font.plainSubstrByWidth(hint, w - 76 - 66), x + 76, height - 20, Theme.TEXT_FAINT, false);
        }
    }

    // ---- rows -----------------------------------------------------------------------------------

    /** A kind: chevron, spawn egg, name and what's special about it, how many; Edit all. */
    private final class GroupRow implements ScrollPane.Row {
        final RowButton head;
        final Chip edit;

        GroupRow(Group g) {
            int n = g.entries().size();
            boolean opened = isOpen(g);
            ItemStack icon = g.base().primary() == null ? new ItemStack(Items.SPAWNER) : MobEditorScreen.egg(g.base().primary().id());
            if (icon.isEmpty()) icon = new ItemStack(Items.SPAWNER);
            ItemStack shownIcon = icon;
            head = new RowButton(Component.literal(g.title() + ", " + n), 20, () -> toggle(g), (gr, x, y, w, h, hovered) -> {
                chevron(gr, x + 5, y + 7, opened, hovered ? Theme.TEXT : Theme.TEXT_DIM);
                gr.item(shownIcon, x + 14, y + 2);
                String right = (g.setups() > 1 ? g.setups() + " setups   " : "") + "×" + n
                        + (n < g.total() ? " of " + g.total() : "");
                int rightW = font.width(right);
                int tx = x + 34, room = w - 34 - rightW - 12;
                String title = g.nickname() != null ? "★ " + g.nickname() : g.title();
                String rest = g.nickname() != null ? g.title() + (g.detail().isEmpty() ? "" : " · " + g.detail()) : g.detail();
                String t = font.plainSubstrByWidth(title, room);
                gr.text(font, t, tx, y + 6, g.nickname() != null ? Theme.ACCENT : Theme.TEXT, false);
                int used = font.width(t);
                if (!rest.isEmpty() && room - used > 30) {
                    String r = rest;
                    if (font.width("  " + r) > room - used) r = font.plainSubstrByWidth(r, room - used - font.width("  …")) + "…";
                    gr.text(font, "  " + r, tx + used, y + 6, Theme.TEXT_DIM, false);
                }
                int rx = x + w - 6 - rightW;
                if (g.setups() > 1) {
                    String s = g.setups() + " setups   ";
                    gr.text(font, s, rx, y + 6, Theme.WARN, false);
                    rx += font.width(s);
                }
                gr.text(font, "×" + n + (n < g.total() ? " of " + g.total() : ""), rx, y + 6, Theme.TEXT, false);
            });
            head.setTooltip(Tooltip.create(Component.literal(groupTip(g))));
            edit = Chip.of(n == 1 ? "Edit" : "Edit all " + n, 66, 20, () -> {
                if (n == 1) edit(g.entries().getFirst()); else editAll(g);
            });
            edit.setTooltip(Tooltip.create(Component.literal(n == 1
                    ? "Go to this spawner and open it in the editor. Done brings you back here."
                    : "Edit all " + n + " together, without travelling: what you change in the editor is "
                    + "written to every one of them. Anything you leave alone stays as each copy has it"
                    + (g.setups() > 1 ? " (they don't all have the same settings)." : ".")
                    + (n < g.total() ? "\nOnly the " + n + " listed: " + (g.total() - n) + " more are hidden by the search or dimension." : ""))));
            edit.active = Delivery.canUse("setblock");
        }

        @Override public int layout(int x, int y, int w) {
            head.setX(x); head.setY(y + 1); head.setWidth(w - 70);
            edit.setX(x + w - 66); edit.setY(y + 1);
            return 22;
        }

        @Override public List<AbstractWidget> widgets() { return List.of(head, edit); }
    }

    /** A location: coordinates, dimension, distance, how its settings differ; click to teleport; Edit. */
    private final class LocationRow implements ScrollPane.Row {
        final RowButton row;
        final Chip edit;

        LocationRow(Group g, SpawnerIndex.Entry e, int index, int coordsW) {
            boolean variant = !g.baseSetup().equals(setupOf.get(e.key()));
            String differs = variant ? differences(g.base(), e.spec()) : "";
            row = new RowButton(Component.literal(e.where()), 16, () -> teleport(g, e, index), (gr, x, y, w, h, hovered) -> {
                int ty = y + 4;
                boolean last = e.key().equals(lastVisited);
                if (last) gr.fill(x + 1, y + 1, x + 3, y + h - 1, Theme.ACCENT);
                gr.text(font, e.where(), x + 7, ty, Theme.TEXT, false);
                int cx = x + 7 + coordsW;
                // Distance here; another dimension's name instead (you can't walk there).
                String distance = distance(e);
                boolean near = distance.equals("here");
                if (here().equals(e.dimension())) {
                    gr.text(font, near ? "● you're here" : distance, cx, ty, near ? Theme.GOOD : Theme.TEXT_DIM, false);
                } else {
                    gr.text(font, dimensionLabel(e.dimension()), cx, ty, dimensionColor(e.dimension()), false);
                }
                cx += Math.max(font.width("● you're here"), font.width(dimensionLabel(e.dimension()))) + 10;
                String tail = hovered ? "teleport ›" : visited.contains(e.key()) ? "✓ visited" : "";
                int tailW = font.width(tail);
                if (!differs.isEmpty() && cx < x + w - tailW - 30) {
                    gr.text(font, font.plainSubstrByWidth(differs, x + w - tailW - 12 - cx), cx, ty, Theme.WARN, false);
                }
                if (!tail.isEmpty()) gr.text(font, tail, x + w - 5 - tailW, ty, hovered ? Theme.ACCENT : Theme.TEXT_FAINT, false);
            });
            if (variant) {
                row.setTooltip(Tooltip.create(Component.literal("Unlike most of these: " + differs + ".\n\n" + settings(e.spec()))));
            }
            row.active = Delivery.canUse("tp");
            edit = Chip.of("Edit", 34, 16, () -> edit(e));
            edit.setTooltip(Tooltip.create(Component.literal("Go to this spawner and open it in the editor. Done brings you back here.")));
            edit.active = Delivery.canUse("tp");
        }

        @Override public int layout(int x, int y, int w) {
            row.setX(x + 14); row.setY(y + 1); row.setWidth(w - 14 - 38);
            edit.setX(x + w - 34); edit.setY(y + 1);
            return 18;
        }

        @Override public List<AbstractWidget> widgets() { return List.of(row, edit); }
    }

    /** A small triangle: pointing right when closed, down when open. */
    private static void chevron(GuiGraphicsExtractor g, int x, int y, boolean down, int color) {
        for (int i = 0; i < 4; i++) {
            if (down) g.fill(x + i, y + i, x + 7 - i, y + i + 1, color);
            else g.fill(x + 1 + i, y - 1 + i, x + 2 + i, y + 6 - i, color);
        }
    }

    private String distance(SpawnerIndex.Entry e) {
        if (minecraft.player == null || !here().equals(e.dimension())) return "";
        double d = Math.sqrt(minecraft.player.blockPosition().distSqr(e.pos()));
        return d < 6 ? "here" : Math.round(d) + " m";
    }

    // ---- describing spawners --------------------------------------------------------------------

    /** What sets a kind apart beyond its mobs' names: gear, data, the type behind a custom name, pool weights. */
    static String detail(SpawnerSpec s) {
        if (s.entries().isEmpty()) return "";
        if (s.entries().size() > 1) {
            int sum = Math.max(1, s.entries().stream().mapToInt(e -> e.weight).sum());
            boolean even = s.entries().stream().allMatch(e -> e.weight == s.entries().getFirst().weight);
            String parts = s.entries().stream().map(e -> (even ? "" : Math.round(100.0 * e.weight / sum) + "% ")
                    + SpawnerLog.mobName(e.entity)).collect(Collectors.joining(", "));
            boolean custom = s.entries().stream().anyMatch(e -> !mobExtras(e.entity).isEmpty());
            return parts + (custom ? " · custom data" : "");
        }
        EntitySpec mob = s.entries().getFirst().entity;
        List<String> out = new ArrayList<>();
        if (!SpawnerLog.mobName(mob).equals(Ids.pretty(mob.id()))) out.add(Ids.pretty(mob.id()));
        out.addAll(mobExtras(mob));
        return String.join(", ", out);
    }

    private static List<String> mobExtras(EntitySpec mob) {
        List<String> out = new ArrayList<>();
        for (EntitySpec.Slot slot : List.of(EntitySpec.Slot.MAINHAND, EntitySpec.Slot.OFFHAND, EntitySpec.Slot.HEAD,
                EntitySpec.Slot.CHEST, EntitySpec.Slot.LEGS, EntitySpec.Slot.FEET, EntitySpec.Slot.BODY, EntitySpec.Slot.SADDLE)) {
            var item = mob.equipment(slot);
            if (item != null) out.add(Ids.pretty(item.id()));
        }
        for (String key : mob.data().keys()) {
            switch (key) {
                case "id", EntitySpec.Keys.EQUIPMENT, EntitySpec.Keys.CUSTOM_NAME, EntitySpec.Keys.DROP_CHANCES -> {}
                case EntitySpec.Keys.PASSENGERS -> {
                    if (mob.data().getList(key) instanceof Nbt.List riders && !riders.isEmpty()
                            && riders.items().getFirst() instanceof Nbt.Compound rider) {
                        out.add("ridden by " + Ids.pretty(rider.getString("id", "?")));
                    }
                }
                case EntitySpec.Keys.ATTRIBUTES -> out.add("stats");
                case EntitySpec.Keys.EFFECTS -> out.add("effects");
                case EntitySpec.Keys.TAGS -> out.add("tags");
                default -> out.add(words(key));
            }
        }
        return out;
    }

    /** "IsBaby" → "is baby", "powered" → "powered", "CanPickUpLoot" → "can pick up loot". */
    private static String words(String key) {
        return key.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    private static String seconds(int ticks) {
        double s = ticks / 20.0;
        return s == Math.floor(s) ? Long.toString((long) s) : String.valueOf(s);
    }

    /** "Every 10–40 s · 4 per spawn · stops at 6 nearby · runs within 16 blocks · spreads 4". */
    static String settings(SpawnerSpec s) {
        return "Every " + seconds(s.get(SpawnerSpec.Setting.MIN_DELAY)) + "–" + seconds(s.get(SpawnerSpec.Setting.MAX_DELAY)) + " s"
                + " · " + s.get(SpawnerSpec.Setting.COUNT) + " per spawn"
                + " · stops at " + s.get(SpawnerSpec.Setting.MAX_NEARBY) + " nearby"
                + " · runs within " + s.get(SpawnerSpec.Setting.PLAYER_RANGE) + " blocks"
                + " · spreads " + s.get(SpawnerSpec.Setting.SPAWN_RANGE);
    }

    /** How {@code s} differs from its kind's usual setup, briefly. */
    static String differences(SpawnerSpec base, SpawnerSpec s) {
        List<String> out = new ArrayList<>();
        if (s.get(SpawnerSpec.Setting.MIN_DELAY) != base.get(SpawnerSpec.Setting.MIN_DELAY)
                || s.get(SpawnerSpec.Setting.MAX_DELAY) != base.get(SpawnerSpec.Setting.MAX_DELAY)) {
            out.add("every " + seconds(s.get(SpawnerSpec.Setting.MIN_DELAY)) + "–" + seconds(s.get(SpawnerSpec.Setting.MAX_DELAY)) + " s");
        }
        if (s.get(SpawnerSpec.Setting.COUNT) != base.get(SpawnerSpec.Setting.COUNT)) out.add(s.get(SpawnerSpec.Setting.COUNT) + " per spawn");
        if (s.get(SpawnerSpec.Setting.MAX_NEARBY) != base.get(SpawnerSpec.Setting.MAX_NEARBY)) out.add("stops at " + s.get(SpawnerSpec.Setting.MAX_NEARBY));
        if (s.get(SpawnerSpec.Setting.PLAYER_RANGE) != base.get(SpawnerSpec.Setting.PLAYER_RANGE)) out.add("runs within " + s.get(SpawnerSpec.Setting.PLAYER_RANGE));
        if (s.get(SpawnerSpec.Setting.SPAWN_RANGE) != base.get(SpawnerSpec.Setting.SPAWN_RANGE)) out.add("spreads " + s.get(SpawnerSpec.Setting.SPAWN_RANGE));
        if (!base.withPoolOf(s).sameAs(base)) out.add("other weights or light");
        if (out.isEmpty()) out.add("other data");
        return String.join(", ", out);
    }

    private static String groupTip(Group g) {
        StringBuilder b = new StringBuilder();
        SpawnerSpec s = g.base();
        if (s.entries().isEmpty()) {
            b.append("Empty spawners: nothing set to spawn.");
        } else {
            int sum = Math.max(1, s.entries().stream().mapToInt(e -> e.weight).sum());
            for (SpawnerSpec.Entry e : s.entries()) {
                String extra = String.join(", ", mobExtras(e.entity));
                b.append(s.entries().size() > 1 ? Math.round(100.0 * e.weight / sum) + "%  " : "")
                        .append(SpawnerLog.mobName(e.entity))
                        .append(SpawnerLog.mobName(e.entity).equals(Ids.pretty(e.entity.id())) ? "" : " (" + Ids.pretty(e.entity.id()) + ")")
                        .append(extra.isEmpty() ? "" : ": " + extra).append('\n');
            }
        }
        b.append('\n').append(settings(s));
        if (g.setups() > 1) b.append("\n\n").append(g.setups()).append(" different setups: the odd ones out say how they differ.");
        b.append("\n\nClick to ").append(isOpenStatic(g) ? "hide" : "show").append(" where they are.");
        return b.toString();
    }

    private static boolean isOpenStatic(Group g) {
        return open.contains(g.key()) || g.matchedByLocation();
    }

    static String dimensionLabel(String id) {
        if (id == null) return "All dimensions";
        return switch (id) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> Ids.pretty(id);
        };
    }

    private static int dimensionOrder(String id) {
        return switch (id) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 3;
        };
    }

    private static int dimensionColor(String id) {
        return switch (id) {
            case "minecraft:overworld" -> 0xFF8FD18F;
            case "minecraft:the_nether" -> 0xFFFF8A65;
            case "minecraft:the_end" -> 0xFFC79BF2;
            default -> Theme.TEXT_DIM;
        };
    }

    /** A flat list row that draws its own contents. */
    static final class RowButton extends AbstractButton {
        interface Painter {
            void paint(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered);
        }

        private final Runnable action;
        private final Painter painter;

        RowButton(Component narration, int height, Runnable action, Painter painter) {
            super(0, 0, 100, height, narration);
            this.action = action;
            this.painter = painter;
        }

        @Override public void onPress(InputWithModifiers input) { action.run(); }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean hot = isHoveredOrFocused() && active;
            g.fill(x, y, x + w, y + h, hot ? 0xFF2A333C : 0xFF1C232A);
            g.outline(x, y, w, h, hot ? 0xFF56636F : Theme.BORDER);
            painter.paint(g, x, y, w, h, hot);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }
}
