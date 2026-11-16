package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.container.ChestSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The World tab's container list: every chest, barrel and shulker box, one row per set of
 * identical contents (or loot table). Works like the spawner list beside it: a row opens into
 * its locations; click one to teleport onto it, Edit to travel there and edit it, or Edit all
 * to give every one of them new contents at once.
 */
public final class ContainerWorldScreen extends StacksmithScreen implements StacksmithScreen.WorldList {
    /** {@code item}: in the By item view, the item this row is about; otherwise null (a row is one set of contents). */
    record Group(String key, String title, String detail, ItemStack icon, ChestSpec base, ItemSpec item,
                 List<ChestIndex.Entry> entries, int total, boolean matchedByLocation) {}

    /**
     * Rows are items rather than whole contents: every distinct item (type and data, whatever
     * the stack size) with the containers that hold it. For finding where an item is and
     * changing it everywhere.
     */
    private static boolean byItem;

    // Remembered while the game runs, for the world they were made in.
    private static Object stateWorld;
    private static String query = "";
    private static String dimension;
    /** Only what is within this many blocks of you (0: any distance). */
    private static int near;
    private static final Set<String> open = new HashSet<>();
    private static double scroll;
    private static String lastVisited;
    private static final Set<String> visited = new HashSet<>();
    private static BlockPos anchor;
    private static String anchorDimension;

    private static final int TOP = 24;
    private final ScrollPane list = pane(new ScrollPane());
    private EditBox search;
    private Chip rescan, dimChip, nearChip, expandAll, viewChip;
    private List<Group> groups = List.of();
    private int total, kinds;
    private Map<String, Integer> perDimension = new TreeMap<>();
    private int shownVersion = -1;
    private boolean shownBusy, started;
    boolean dismissed;
    private String message = "";
    private int messageColor = Theme.TEXT_DIM;
    /** A screen to show as soon as this one is back on top. */
    private Screen next;

    public ContainerWorldScreen(Screen parent) {
        super(Component.literal("World containers"), parent);
        Object world = Backend.world();
        if (world != stateWorld) {
            stateWorld = world;
            query = "";
            dimension = null;
            near = 0;
            open.clear();
            scroll = 0;
            lastVisited = null;
            visited.clear();
            anchor = null;
        }
    }

    private static net.minecraft.client.Minecraft minecraft() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    private int panelW() { return Math.min(width - 2 * PAD, 640); }
    private int panelX() { return (width - panelW()) / 2; }
    private int innerX() { return panelX() + 6; }
    private int innerW() { return panelW() - 12; }
    private int listTop() { return TOP + 36; }
    private int listBottom() { return height - 30; }

    @Override
    protected void init() {
        header(Tab.WORLD, null, null, null, null, null);
        worldSwitch(true);
        int x = innerX(), w = innerW();
        int rescanW = 62, dimW = 80, viewW = 70, nearW = 72;
        search = addRenderableWidget(new EditBox(font, x, TOP + 4, w - rescanW - dimW - viewW - nearW - 16, 16, Component.literal("Search")));
        search.setHint(Component.literal("Search items, loot, coordinates"));
        viewChip = addRenderableWidget(Chip.of("", viewW, 16, () -> {
            byItem = !byItem;
            open.clear();
            list.scroll(0);
            refilter();
        }).at(x + w - rescanW - dimW - nearW - viewW - 12, TOP + 4).tip("By contents: one row per set of identical contents, to restock containers alike. "
                + "By item: one row per item with every container that holds it, to find an item or change it everywhere."));
        search.setMaxLength(100);
        search.setValue(query);
        search.setResponder(t -> {
            if (t.equals(query)) return;
            query = t;
            list.scroll(0);
            refilter();
        });
        nearChip = addRenderableWidget(Chip.of("", nearW, 16, () -> {
            near = SpawnerWorldScreen.nextNear(near, ChestIndex.loadedOnly() && ChestIndex.reach > 0 ? ChestIndex.reach : Integer.MAX_VALUE);
            list.scroll(0);
            refilter();
        }).at(x + w - rescanW - dimW - nearW - 8, TOP + 4));
        dimChip = addRenderableWidget(Chip.of("", dimW, 16, this::cycleDimension)
                .at(x + w - rescanW - 4 - dimW, TOP + 4).tip("Which dimension to list. Click to switch."));
        rescan = addRenderableWidget(Chip.of("Rescan", rescanW, 16, () -> {
            anchor = null;
            ChestIndex.start();
            updateChips();
        }).at(x + w - rescanW, TOP + 4));
        rescan.tip(Backend.host() != null
                ? "Look for containers again: the saved world, plus the chunks loaded now."
                : "Read the containers in the chunks loaded around you again (a server only lets a client see those). Rescan after moving to another area.");
        expandAll = addRenderableWidget(Chip.of("Open all", 70, 18, this::toggleAll).at(x, height - 25));
        addRenderableWidget(Chip.of("Done", 60, 18, this::onClose).at(x + w - 60, height - 25));
        list.bounds(x, listTop(), w, listBottom() - listTop());
        if (!started) {
            started = true;
            ChestIndex.ensure();
            // The spawner index knows which chunks are fully generated: Edit all needs that to load far ones safely.
            if (Backend.host() != null && !SpawnerIndex.busy && !(SpawnerIndex.current() && SpawnerIndex.indexed)) SpawnerIndex.ensure(() -> {});
        }
        regroup();
        if (!query.isEmpty() || groups.isEmpty()) focusSoon(search);
    }

    // ---- data -----------------------------------------------------------------------------------

    /** What makes two containers alike: the same items in the same slots, and the same other data (loot table, name, lock). */
    static String contentsKey(ChestSpec chest) {
        return Snbt.write(chest.data());
    }

    /** "Loot: chests/simple_dungeon", "Empty", or "Diamond ×3, Bread ×8". */
    static String summary(ChestSpec chest) {
        if (chest.lootTable()) return "Loot: " + Ids.brief(chest.data().getString("LootTable", "?"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 27; i++) {
            ItemSpec item = chest.slot(i);
            if (item != null) counts.merge(item.id(), item.count(), Integer::sum);
        }
        if (counts.isEmpty()) return "Empty";
        return counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> Ids.pretty(e.getKey()) + " ×" + e.getValue()).collect(Collectors.joining(", "));
    }

    static ItemStack icon(ChestSpec chest) {
        if (Delivery.registries() != null) {
            for (int i = 0; i < 27; i++) {
                ItemSpec item = chest.slot(i);
                if (item == null) continue;
                McItems.Result r = McItems.toStack(item, Delivery.registries());
                if (r.ok()) return r.stack();
            }
        }
        return new ItemStack(Items.CHEST);
    }

    /** "Chest", or "2 Chests, 1 Barrel" when they aren't all the same block. */
    private static String blocks(List<ChestIndex.Entry> entries) {
        Map<String, Long> byBlock = entries.stream().collect(Collectors.groupingBy(e -> Ids.pretty(e.chest().block()), TreeMap::new, Collectors.counting()));
        if (byBlock.size() == 1) return byBlock.keySet().iterator().next();
        return byBlock.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }

    private void regroup() {
        shownVersion = ChestIndex.version;
        shownBusy = ChestIndex.busy;
        refilter();
    }

    /** An item whatever its stack size: the same type and data. */
    static String itemKey(ItemSpec item) {
        return Snbt.write(item.copy().count(1).toTag());
    }

    /** Its custom name when it has one, else the item's own name. */
    static String itemName(ItemSpec item) {
        var name = item.get("custom_name");
        String markup = name == null ? null : io.github.tjhgibbs.stacksmith.core.text.TextMarkup.fromComponent(name);
        return markup != null && !markup.isBlank() ? io.github.tjhgibbs.stacksmith.core.text.TextMarkup.plain(markup) : Ids.pretty(item.id());
    }

    private void refilterItems() {
        record Found(ItemSpec item, List<ChestIndex.Entry> in, int[] count) {}
        Map<String, Found> items = new LinkedHashMap<>();
        perDimension = new TreeMap<>();
        total = 0;
        for (ChestIndex.Entry e : ChestIndex.entries.values()) {
            perDimension.merge(e.dimension(), 1, Integer::sum);
            total++;
            Set<String> here = new HashSet<>();
            for (int i = 0; i < 27; i++) {
                ItemSpec item = e.chest().slot(i);
                if (item == null) continue;
                String key = itemKey(item);
                Found f = items.computeIfAbsent(key, k -> new Found(item.copy().count(1), new ArrayList<>(), new int[1]));
                f.count()[0] += item.count();
                if (here.add(key)) f.in().add(e);
            }
        }
        if (dimension != null && !perDimension.containsKey(dimension)) dimension = null;
        kinds = items.size();
        String q = query.strip();
        List<Group> out = new ArrayList<>();
        for (var found : items.entrySet()) {
            ItemSpec item = found.getValue().item();
            String title = itemName(item);
            boolean named = !title.equals(Ids.pretty(item.id()));
            int components = item.components().keys().size() - (named ? 1 : 0);
            String detail = (named ? Ids.pretty(item.id()) + " · " : "")
                    + (components > 0 ? "custom data · " : "") + found.getValue().count()[0] + " in all";
            boolean kindMatches = SpawnerLog.matches((title + " " + detail + " " + found.getKey()).toLowerCase(Locale.ROOT), q);
            List<ChestIndex.Entry> shown = found.getValue().in().stream()
                    .filter(e -> dimension == null || e.dimension().equals(dimension))
                    .filter(e -> SpawnerWorldScreen.within(e.dimension(), e.pos(), near))
                    .filter(e -> kindMatches || SpawnerLog.matches(locationText(e), q))
                    .sorted(nearestFirst())
                    .toList();
            if (shown.isEmpty()) continue;
            McItems.Result stack = Delivery.registries() == null ? null : McItems.toStack(item, Delivery.registries());
            out.add(new Group(found.getKey(), title, detail, stack != null && stack.ok() ? stack.stack() : new ItemStack(Items.CHEST),
                    null, item, shown, found.getValue().in().size(), !q.isEmpty() && !kindMatches));
        }
        out.sort(Comparator.comparing((Group g) -> g.title().toLowerCase(Locale.ROOT)).thenComparing(Group::key));
        groups = out;
        buildList();
    }

    private void refilter() {
        if (byItem) { refilterItems(); return; }
        Map<String, List<ChestIndex.Entry>> byKind = new LinkedHashMap<>();
        perDimension = new TreeMap<>();
        total = 0;
        for (ChestIndex.Entry e : ChestIndex.entries.values()) {
            byKind.computeIfAbsent(contentsKey(e.chest()), k -> new ArrayList<>()).add(e);
            perDimension.merge(e.dimension(), 1, Integer::sum);
            total++;
        }
        if (dimension != null && !perDimension.containsKey(dimension)) dimension = null;
        kinds = byKind.size();
        String q = query.strip();
        List<Group> out = new ArrayList<>();
        for (var kind : byKind.entrySet()) {
            List<ChestIndex.Entry> all = kind.getValue();
            ChestSpec base = all.getFirst().chest();
            String title = summary(base);
            int slots = base.occupied();
            String detail = blocks(all) + (base.lootTable() || slots == 0 ? "" : " · " + slots + (slots == 1 ? " slot" : " slots"));
            boolean kindMatches = SpawnerLog.matches((title + " " + detail + " " + kind.getKey()).toLowerCase(Locale.ROOT), q);
            List<ChestIndex.Entry> shown = all.stream()
                    .filter(e -> dimension == null || e.dimension().equals(dimension))
                    .filter(e -> SpawnerWorldScreen.within(e.dimension(), e.pos(), near))
                    .filter(e -> kindMatches || SpawnerLog.matches(locationText(e), q))
                    .sorted(nearestFirst())
                    .toList();
            if (shown.isEmpty()) continue;
            out.add(new Group(kind.getKey(), title, detail, icon(base), base, null, shown, all.size(), !q.isEmpty() && !kindMatches));
        }
        out.sort(Comparator.comparingInt((Group g) -> -g.entries().size())
                .thenComparing(g -> g.title().toLowerCase(Locale.ROOT)).thenComparing(Group::key));
        groups = out;
        buildList();
    }

    private static String locationText(ChestIndex.Entry e) {
        return (SpawnerWorldScreen.dimensionLabel(e.dimension()) + " " + e.dimension() + " " + e.where() + " " + e.state()).toLowerCase(Locale.ROOT);
    }

    private Comparator<ChestIndex.Entry> nearestFirst() {
        if (anchor == null && minecraft.player != null) {
            anchor = minecraft.player.blockPosition();
            anchorDimension = here();
        }
        String from = anchorDimension == null ? "" : anchorDimension;
        var at = anchor == null ? BlockPos.ZERO : anchor;
        return Comparator.comparing((ChestIndex.Entry e) -> !e.dimension().equals(from))
                .thenComparing(ChestIndex.Entry::dimension)
                .thenComparingDouble(e -> e.pos().distSqr(e.dimension().equals(from) ? at : BlockPos.ZERO))
                .thenComparing(ChestIndex.Entry::key);
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
        rescan.setMessage(Component.literal(ChestIndex.busy ? "Scanning…" : "Rescan"));
        viewChip.setMessage(Component.literal(byItem ? "By item" : "By contents"));
        rescan.active = !ChestIndex.busy && Delivery.inWorld() && !ChestWorldBatch.busy();
        dimChip.setMessage(Component.literal(SpawnerWorldScreen.dimensionLabel(dimension)));
        nearChip.setMessage(Component.literal(SpawnerWorldScreen.nearLabel(near)));
        nearChip.tip(SpawnerWorldScreen.nearTip(ChestIndex.loadedOnly() ? ChestIndex.reach : 0));
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

    // ---- actions --------------------------------------------------------------------------------

    /** Sends the teleport onto {@code e}, looking down at it. Returns why not, or null. */
    static String travel(ChestIndex.Entry e) {
        var mc = minecraft();
        if (mc.player == null || mc.getConnection() == null) return "Join a world first.";
        if (!Delivery.canUse("execute") || !Delivery.canUse("tp")) return "Teleport requires operator permission.";
        if (CommandQueue.busy()) return "Wait for the current update to finish.";
        if (!e.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "Unknown dimension.";
        BlockPos p = e.pos();
        mc.getConnection().sendCommand("execute in " + e.dimension() + " run tp @s "
                + (p.getX() + .5) + " " + (p.getY() + 1.0) + " " + (p.getZ() + .5)
                + " facing " + (p.getX() + .5) + " " + (p.getY() + .5) + " " + (p.getZ() + .5));
        return null;
    }

    /** Puts you on top of the container looking at it, and closes the list (J then edits it). */
    private void teleport(Group g, ChestIndex.Entry e, int index) {
        String why = travel(e);
        if (why != null) { message(why, Theme.BAD); return; }
        lastVisited = e.key();
        visited.add(e.key());
        scroll = list.scroll();
        String which = g.entries().size() > 1 ? " (" + (index + 1) + " of " + g.entries().size() + ")" : "";
        minecraft.setScreenAndShow(null);
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(Ids.pretty(e.chest().block()) + which + " · press J to edit it"));
        }
    }

    /** Travels to the container and opens it in the editor with fresh contents; Back comes back here. */
    private void edit(ChestIndex.Entry e) {
        if (ChestWorldBatch.busy()) { message("Wait for the update to finish.", Theme.WARN); return; }
        if (!Delivery.canUse("data")) { message("Reading a container needs /data permission.", Theme.BAD); return; }
        boolean there = here().equals(e.dimension()) && ChestIndex.supported(minecraft.level.getBlockEntity(e.pos()))
                && minecraft.player != null && minecraft.player.blockPosition().distSqr(e.pos()) < 48 * 48;
        if (!there) {
            String why = travel(e);
            if (why != null) { message(why, Theme.BAD); return; }
            message("Travelling to the container…", Theme.TEXT_DIM);
        }
        lastVisited = e.key();
        visited.add(e.key());
        scroll = list.scroll();
        awaitContainer(e, 0);
    }

    private void awaitContainer(ChestIndex.Entry e, int attempt) {
        if (dismissed) return;
        var shown = minecraft.gui.screen();
        if (shown != null && shown != this && !(shown instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(shown instanceof net.minecraft.client.gui.screens.GenericMessageScreen)) return;
        if (minecraft.level != null && here().equals(e.dimension()) && ChestIndex.supported(minecraft.level.getBlockEntity(e.pos()))
                && !ChestIndex.busy) {
            ChestIndex.load(e, fresh -> {
                Session.chest = fresh.chest().copy();
                Session.chestLink = fresh;
                minecraft.setScreenAndShow(new ChestEditorScreen(this));
            });
            return;
        }
        if (attempt >= 100) {
            if (shown == null) minecraft.setScreenAndShow(this);
            message("There's no container at " + e.where() + " any more. Rescan to update the list.", Theme.BAD);
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> minecraft.execute(() -> awaitContainer(e, attempt + 1)),
                java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    /** {@code chest} with every stack of the item {@code key} replaced by {@code to} (each stack keeps its size and slot), or null if it can't hold it. */
    private static ChestSpec withItem(ChestSpec chest, String key, ItemSpec to) {
        ChestSpec out = chest.copy();
        try {
            for (int i = 0; i < 27; i++) {
                ItemSpec item = out.slot(i);
                if (item != null && itemKey(item).equals(key)) out.slot(i, to.copy().count(item.count()));
            }
        } catch (IllegalArgumentException e) {
            return null; // a shulker box inside a shulker box
        }
        return out;
    }

    /** By item: opens the item in the item editor; Use then reviews putting the edited item in every listed container that holds it. */
    private void editItem(Group g) {
        if (ChestWorldBatch.busy()) { message("Wait for the update to finish.", Theme.WARN); return; }
        scroll = list.scroll();
        minecraft.setScreenAndShow(new ItemEditorScreen(this, g.item().copy(), edited -> {
            Map<String, ChestSpec> wanted = new HashMap<>();
            List<ChestIndex.Entry> can = new ArrayList<>();
            for (ChestIndex.Entry e : g.entries()) {
                ChestSpec after = withItem(e.chest(), g.key(), edited);
                if (after != null) { wanted.put(e.key(), after); can.add(e); }
            }
            String after = itemName(edited) + (edited.id().equals(g.item().id()) ? "" : " (" + Ids.pretty(edited.id()) + ")");
            // The item editor closes back to this list after handing over the item: the review opens from tick().
            next = (new ChestApplyScreen(this, new ChestApplyScreen.Plan(
                    g.title() + " · the item, in every container that holds it", g.title() + (g.detail().isEmpty() ? "" : "  (" + g.detail() + ")"),
                    after + " with your changes · each stack keeps its size and slot"
                            + (can.size() < g.entries().size() ? " · " + (g.entries().size() - can.size()) + " can't hold it" : ""),
                    can, wanted, !itemKey(edited).equals(g.key()), this, null)));
        }));
    }

    private void editAll(Group g) {
        if (g.item() != null) { editItem(g); return; }
        if (ChestWorldBatch.busy()) { message("Wait for the update to finish.", Theme.WARN); return; }
        scroll = list.scroll();
        minecraft.setScreenAndShow(new ChestEditorScreen(this,
                new ChestEditorScreen.Group(g.title(), g.base().copy(), g.entries(), this), g.base().copy()));
    }

    private void message(String text, int color) {
        message = text;
        messageColor = color;
    }

    // ---- update and drawing ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (next != null) {
            Screen go = next;
            next = null;
            minecraft.setScreenAndShow(go);
            return;
        }
        // Read from loaded chunks, the list is of one dimension: read again after travelling.
        if (ChestIndex.indexed && !ChestIndex.busy && !ChestWorldBatch.busy()) ChestIndex.ensure();
        if (ChestIndex.version != shownVersion || ChestIndex.busy != shownBusy) regroup();
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
        int y = TOP + 25;
        String summary = total == 0 ? "" : total + (total == 1 ? " container" : " containers") + " · "
                + kinds + (byItem ? (kinds == 1 ? " item" : " different items") : kinds == 1 ? " kind" : " kinds")
                + (perDimension.size() > 1 ? " · " + perDimension.entrySet().stream()
                .map(d -> SpawnerWorldScreen.dimensionLabel(d.getKey()) + " " + d.getValue()).collect(Collectors.joining(", ")) : "");
        boolean local = !ChestIndex.loadedOnly();
        String state = ChestIndex.busy ? ChestIndex.status
                : !ChestIndex.indexed ? ""
                : (local ? "Scanned " : "Loaded chunks only · ") + SpawnerLog.ago(ChestIndex.indexedAt, System.currentTimeMillis());
        int stateColor = ChestIndex.busy ? Theme.ACCENT : local ? Theme.TEXT_DIM : Theme.WARN;
        int stateW = font.width(state);
        g.text(font, font.plainSubstrByWidth(summary, Math.max(0, w - stateW - 10)), x, y, Theme.TEXT_DIM, false);
        g.text(font, state, x + w - stateW, y, stateColor, false);
        if (!state.isEmpty() && mouseX >= x + w - stateW && mouseX < x + w && mouseY >= y - 2 && mouseY < y + 10) {
            g.setTooltipForNextFrame(font, font.split(Component.literal(ChestIndex.status), 220), mouseX, mouseY);
        }
        g.fill(x, listTop() - 3, x + w, listTop() - 2, Theme.BORDER);
        if (groups.isEmpty()) {
            String empty = ChestIndex.busy ? "Looking for containers…"
                    : total == 0 ? (ChestIndex.indexed ? "No chests, barrels or shulker boxes found" + (local ? " in this world yet." : " in the chunks loaded around you.")
                    : ChestIndex.status)
                    : "No containers match" + (query.isBlank() ? "" : " “" + query.strip() + "”")
                    + (dimension == null ? "" : " in " + SpawnerWorldScreen.dimensionLabel(dimension))
                    + (near == 0 ? "." : " within " + near + " blocks of you.");
            int ty = listTop() + 14;
            for (var line : font.split(Component.literal(empty), w - 20)) {
                g.centeredText(font, line, x + w / 2, ty, Theme.TEXT_DIM);
                ty += 11;
            }
        }
        String foot = !message.isEmpty() ? message
                : groups.isEmpty() ? "" : "Click a row for locations; click one to teleport.";
        g.text(font, font.plainSubstrByWidth(foot, w - 76 - 66), x + 76, height - 20, message.isEmpty() ? Theme.TEXT_FAINT : messageColor, false);
    }

    // ---- rows -----------------------------------------------------------------------------------

    private final class GroupRow implements ScrollPane.Row {
        final SpawnerWorldScreen.RowButton head;
        final Chip edit;

        GroupRow(Group g) {
            int n = g.entries().size();
            boolean opened = isOpen(g);
            head = new SpawnerWorldScreen.RowButton(Component.literal(g.title() + ", " + n), 20, () -> toggle(g), (gr, x, y, w, h, hovered) -> {
                SpawnerWorldScreen.chevron(gr, x + 5, y + 7, opened, hovered ? Theme.TEXT : Theme.TEXT_DIM);
                gr.item(g.icon(), x + 14, y + 2);
                String right = "×" + n + (n < g.total() ? " of " + g.total() : "");
                int rightW = font.width(right);
                int tx = x + 34, room = w - 34 - rightW - 12;
                String t = font.plainSubstrByWidth(g.title(), room);
                if (!t.equals(g.title())) t = font.plainSubstrByWidth(g.title(), room - font.width("…")) + "…";
                gr.text(font, t, tx, y + 6, g.base() != null && g.base().lootTable() ? Theme.ACCENT : Theme.TEXT, false);
                int used = font.width(t);
                if (room - used > 40) {
                    String r = g.detail();
                    if (font.width("  " + r) > room - used) r = font.plainSubstrByWidth(r, room - used - font.width("  …")) + "…";
                    gr.text(font, "  " + r, tx + used, y + 6, Theme.TEXT_DIM, false);
                }
                gr.text(font, right, x + w - 6 - rightW, y + 6, Theme.TEXT, false);
            });
            head.setTooltip(Tooltip.create(Component.literal(g.title() + "\n" + g.detail()
                    + "\n\nClick to " + (opened ? "hide" : "show") + " where they are.")));
            boolean item = g.item() != null;
            edit = Chip.of(item ? "Edit item" : n == 1 ? "Edit" : "Edit all " + n, 66, 20, () -> {
                if (n == 1 && !item) edit(g.entries().getFirst()); else editAll(g);
            });
            boolean canAll = ChestWorldBatch.available() && Delivery.canUse("setblock") && Delivery.canUse("data");
            edit.active = n == 1 && !item ? Delivery.canUse("tp") && Delivery.canUse("data") : canAll;
            String hidden = n < g.total() ? "\nOnly the " + n + " listed: " + (g.total() - n) + " more are hidden by the search or filters." : "";
            String how = ChestWorldBatch.byCommands() ? "\nThey are changed with commands, then read back." : "";
            edit.setTooltip(Tooltip.create(Component.literal(item
                    ? "Open this item in the item editor (to buff or nerf it), then put the result in "
                    + (n == 1 ? "the container that holds it" : "all " + n + " containers that hold it")
                    + ", without travelling. Each stack keeps its size and slot; you review the list first." + hidden + how
                    : n == 1
                    ? "Go to this container and open it in the editor. Back brings you here."
                    : "Edit all " + n + " together, without travelling: set the contents once, review, and every one of them gets them." + hidden + how)));
        }

        @Override public int layout(int x, int y, int w) {
            head.setX(x); head.setY(y + 1); head.setWidth(w - 70);
            edit.setX(x + w - 66); edit.setY(y + 1);
            return 22;
        }

        @Override public List<AbstractWidget> widgets() { return List.of(head, edit); }
    }

    private final class LocationRow implements ScrollPane.Row {
        final SpawnerWorldScreen.RowButton row;
        final Chip edit;

        LocationRow(Group g, ChestIndex.Entry e, int index, int coordsW) {
            String block = Ids.pretty(e.chest().block());
            row = new SpawnerWorldScreen.RowButton(Component.literal(e.where()), 16, () -> teleport(g, e, index), (gr, x, y, w, h, hovered) -> {
                int ty = y + 4;
                if (e.key().equals(lastVisited)) gr.fill(x + 1, y + 1, x + 3, y + h - 1, Theme.ACCENT);
                gr.text(font, e.where(), x + 7, ty, Theme.TEXT, false);
                int cx = x + 7 + coordsW;
                if (here().equals(e.dimension())) {
                    double d = minecraft.player == null ? 0 : Math.sqrt(minecraft.player.blockPosition().distSqr(e.pos()));
                    gr.text(font, d < 6 ? "● you're here" : Math.round(d) + " m", cx, ty, d < 6 ? Theme.GOOD : Theme.TEXT_DIM, false);
                } else {
                    gr.text(font, SpawnerWorldScreen.dimensionLabel(e.dimension()), cx, ty, SpawnerWorldScreen.dimensionColor(e.dimension()), false);
                }
                cx += font.width("● you're here") + 10;
                String tail = hovered ? "teleport ›" : visited.contains(e.key()) ? "✓ visited" : "";
                int tailW = font.width(tail);
                if (cx < x + w - tailW - 30) gr.text(font, font.plainSubstrByWidth(block, x + w - tailW - 12 - cx), cx, ty, Theme.TEXT_DIM, false);
                if (!tail.isEmpty()) gr.text(font, tail, x + w - 5 - tailW, ty, hovered ? Theme.ACCENT : Theme.TEXT_FAINT, false);
            });
            row.active = Delivery.canUse("tp");
            edit = Chip.of("Edit", 34, 16, () -> edit(e));
            edit.setTooltip(Tooltip.create(Component.literal("Go to this container and open it in the editor. Back brings you here.")));
            edit.active = Delivery.canUse("tp") && Delivery.canUse("data");
        }

        @Override public int layout(int x, int y, int w) {
            row.setX(x + 14); row.setY(y + 1); row.setWidth(w - 14 - 38);
            edit.setX(x + w - 34); edit.setY(y + 1);
            return 18;
        }

        @Override public List<AbstractWidget> widgets() { return List.of(row, edit); }
    }
}
