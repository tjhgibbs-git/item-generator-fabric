package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import io.github.tjhgibbs.stacksmith.core.tags.TagMemory;
import io.github.tjhgibbs.stacksmith.core.tags.TagScan;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every entity tag known in this world, ticked when the mob has it: the ones used here (newest
 * first) and, unless hidden, the ones the world's datapacks give out or test for. Click a row to
 * put the tag on the mob or take it off; type a new one and press Enter to add it.
 */
public final class TagsScreen extends StacksmithScreen {
    private final EntitySpec mob;
    private final Runnable onChange;
    private final ScrollPane list = pane(new ScrollPane());
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private EditBox search;
    private Chip scan;
    private String query = "";
    private String scanStatus;

    public TagsScreen(Screen parent, EntitySpec mob, Runnable onChange) {
        super(Component.literal("Tags · " + SpawnerLog.mobName(mob)), parent);
        this.mob = mob;
        this.onChange = onChange;
    }

    private int panelW() {
        return Math.min(400, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    @Override
    protected void init() {
        int x = left(), w = panelW(), y = 26;
        boolean shown = EntityTags.memory().showDatapack();
        String toggleLabel = (shown ? "☑" : "☐") + " Datapack tags";
        int tw = font.width(toggleLabel) + 14;
        search = addRenderableWidget(new EditBox(font, x, y - 1, w - tw - 4, 16, Component.literal("Search or add")));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(t -> {
            query = t.strip();
            list.scroll(0);
            fillList();
        });
        addRenderableWidget(Chip.of(toggleLabel, tw, 14, () -> {
            EntityTags.memory().showDatapack(!EntityTags.memory().showDatapack());
            rebuildWidgets();
        }).selected(() -> EntityTags.memory().showDatapack()).at(x + w - tw, y)
                .tip("Show the tags found in this world's datapacks here and in the tag box's suggestions"));
        scan = addRenderableWidget(Chip.of("Scan datapacks", 90, 14, this::scan).at(x, y + 20)
                .tip(EntityTags.canScan() ? "Read this world's datapacks (functions, predicates, advancements, loot tables) for the tags they use"
                        : "Only in a world you're running (singleplayer or LAN host): a server's datapacks can't be read from here"));
        scan.active = EntityTags.canScan();
        list.bounds(x, y + 40, w, height - y - 40 - 34);
        fillList();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + w - 70, height - 28, 70, 20).build());
        setInitialFocus(search);
    }

    private void scan() {
        scan.active = false;
        scanStatus = "Reading datapacks…";
        EntityTags.scan(line -> {
            scanStatus = line;
            if (minecraft.gui.screen() == this) {
                rebuildWidgets();
            }
        });
    }

    /** "Found 12 tags · scanned 5 min ago", or why not. */
    private String scanLine() {
        if (scanStatus != null) {
            return scanStatus;
        }
        TagMemory.World here = EntityTags.here();
        if (here.scannedAt() == 0) {
            return EntityTags.canScan() ? "Not scanned yet" : "Datapacks: singleplayer only";
        }
        return here.datapack().size() + " datapack tags · scanned " + SpawnerLog.ago(here.scannedAt(), System.currentTimeMillis());
    }

    private void fillList() {
        rowWidgets.forEach(this::removeWidget);
        rowWidgets.clear();
        list.clear();
        TagMemory.World here = EntityTags.here();
        TagMemory memory = EntityTags.memory();
        String world = SpawnerLibrary.world();
        List<String> recent = memory.recentlyUsed(world);
        List<String> on = mob.tags();
        String q = query.toLowerCase(Locale.ROOT);
        boolean exact = false;
        List<String> seen = new ArrayList<>();
        List<Row> used = new ArrayList<>(), packs = new ArrayList<>(), mine = new ArrayList<>();
        // The mob's own tags first, so they're never hidden by a filter you forgot about.
        for (String t : on) {
            if (t.toLowerCase(Locale.ROOT).contains(q)) {
                mine.add(new Row(t, sources(here, t), recent.contains(t)));
            }
            seen.add(t);
        }
        // Used before (here, or a datapack tag used anywhere), most recent first.
        for (String t : recent) {
            if (!seen.contains(t) && t.toLowerCase(Locale.ROOT).contains(q)) {
                used.add(new Row(t, sources(here, t), true));
            }
            seen.add(t);
        }
        for (String t : memory.unusedDatapack(world)) {
            if (!seen.contains(t) && t.toLowerCase(Locale.ROOT).contains(q)) {
                packs.add(new Row(t, sources(here, t), false));
            }
        }
        for (String t : seen) {
            exact |= t.equals(query);
        }
        exact |= here.datapack().containsKey(query) && EntityTags.memory().showDatapack();
        if (!query.isEmpty() && !exact) {
            boolean valid = TagScan.NAME.matcher(query).matches();
            Chip addNew = Chip.of(valid ? "+ Add \"" + query + "\"" : "Tags are one word: letters, digits and _ - . +", 200, 16, () -> addNew()).left();
            addNew.active = valid;
            list.add(new ItemEditorScreen.WidgetRow(addNew, 18));
        }
        section("On this mob", mine);
        section("Recently used", used);
        section("From datapacks", packs);
        if (list.rows().isEmpty()) {
            list.add(new ItemEditorScreen.HeaderRow(query.isEmpty() ? "No tags yet: type one above and press Enter" : "No tag matches"));
        }
        for (ScrollPane.Row r : list.rows()) {
            rowWidgets.addAll(r.widgets());
        }
        rowWidgets.forEach(this::addWidget);
        list.layout();
    }

    private void section(String title, List<Row> rows) {
        if (!rows.isEmpty()) {
            list.add(new ItemEditorScreen.HeaderRow(title));
            rows.forEach(list::add);
        }
    }

    private static List<String> sources(TagMemory.World here, String tag) {
        return here.datapack().getOrDefault(tag, List.of());
    }

    private void toggle(String tag) {
        List<String> now = mob.tags();
        if (now.contains(tag)) {
            now.remove(tag);
        } else {
            now.add(tag);
            EntityTags.use(List.of(tag));
        }
        mob.tags(now);
        onChange.run();
        fillList();
    }

    private void addNew() {
        if (TagScan.NAME.matcher(query).matches()) {
            if (!mob.tags().contains(query)) {
                toggle(query);
            }
            search.setValue("");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if ((e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) && getFocused() == search && !query.isEmpty()) {
            // Enter: add what was typed, or tick the one tag it matches exactly.
            List<String> all = new ArrayList<>(EntityTags.here().used());
            all.addAll(EntityTags.here().datapack().keySet());
            if (all.contains(query) || mob.tags().contains(query)) {
                if (!mob.tags().contains(query)) {
                    toggle(query);
                }
                search.setValue("");
            } else {
                addNew();
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = left(), w = panelW();
        panel(g, x - 6, 8, w + 12, height - 16);
        g.centeredText(font, font.plainSubstrByWidth(title.getString(), w), width / 2, 13, Theme.TEXT);
        g.text(font, font.plainSubstrByWidth(scanLine(), w - 96), x + 96, 50, Theme.TEXT_DIM, false);
        g.text(font, font.plainSubstrByWidth("Click a tag to put it on the mob or take it off", w - 80), x, height - 22, Theme.TEXT_FAINT, false);
    }

    @Override
    protected void extractOverlays(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (search.getValue().isEmpty()) {
            g.text(font, font.plainSubstrByWidth("Search, or type a new tag and press Enter", search.getWidth() - 8),
                    search.getX() + 4, search.getY() + 4, Theme.TEXT_FAINT, false);
        }
    }

    /** One tag: ☑/☐ and its name (click to toggle), where it was seen, and ✕ to forget a used one. */
    private final class Row implements ScrollPane.Row {
        private final String tag;
        private final List<String> sources;
        private final Chip check, forget;

        Row(String tag, List<String> sources, boolean used) {
            this.tag = tag;
            this.sources = sources;
            boolean has = mob.tags().contains(tag);
            check = Chip.of((has ? "☑ " : "☐ ") + tag, font.width("☐ " + tag) + 14, 16, () -> toggle(tag)).left().selected(() -> has)
                    .tip(has ? "On this mob: click to take it off" : "Click to put it on this mob");
            if (used) {
                forget = Chip.of("✕", 16, 16, () -> {
                    EntityTags.memory().forget(SpawnerLibrary.world(), tag);
                    fillList();
                }).color(Theme.TEXT_DIM).tip("Forget you used it: it leaves Recently used (and stays on any mob that has it)");
            } else {
                forget = null;
            }
        }

        @Override
        public int layout(int x, int y, int width) {
            check.setX(x);
            check.setY(y + 1);
            check.setWidth(Math.min(check.getWidth(), width / 2));
            if (forget != null) {
                forget.setX(x + width - 16);
                forget.setY(y + 1);
            }
            return 18;
        }

        @Override
        public void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
            if (sources.isEmpty()) {
                return;
            }
            int sx = check.getX() + check.getWidth() + 6, room = x + width - (forget != null ? 20 : 0) - sx;
            g.text(font, font.plainSubstrByWidth(sources.getFirst() + (sources.size() > 1 ? " +" + (sources.size() - 1) : ""), room),
                    sx, y + 5, Theme.TEXT_FAINT, false);
            if (mouseX >= sx && mouseX < sx + room && mouseY >= y && mouseY < y + 18 && list.contains(mouseX, mouseY)) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal("Seen in:"));
                sources.forEach(s -> lines.add(Component.literal(s).withColor(Theme.TEXT_DIM)));
                g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
        }

        @Override
        public List<AbstractWidget> widgets() {
            return forget == null ? List.of(check) : List.of(check, forget);
        }
    }
}
