package io.github.tjhgibbs.stacksmith.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The spawners you've used (Recent) and the ones you starred (★ Starred), searchable. Type to
 * filter, ↑ ↓ to pick, Enter to load; ★ on any row stars or unstars it. Loading hands the data
 * to the spawner editor, which stays tied to its block, so Overwrite then puts it there.
 */
public final class SpawnerLibraryScreen extends StacksmithScreen {
    private static boolean starredTab;

    private final SpawnerSpec current;
    private final Consumer<Nbt.Compound> onLoad;
    private final ScrollPane list = pane(new ScrollPane());
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    /** Unstarred during this visit: still listed (☆) so a misclick is one click to undo. */
    private final Map<String, Nbt.Compound> unstarred = new LinkedHashMap<>();
    private EditBox search, name;
    private Chip starCurrent;
    private String query = "";
    private int selected;
    /** The log entry whose ✕ was clicked once (a second click removes it). */
    private SpawnerLog.Entry confirmRemove;
    /** The starred spawner being nicknamed (its current name), and the box for it; null when none. */
    private String renaming;
    private EditBox renameBox;

    public SpawnerLibraryScreen(Screen parent, SpawnerSpec current, Consumer<Nbt.Compound> onLoad) {
        super(Component.literal("Spawners"), parent);
        this.current = current;
        this.onLoad = onLoad;
        SpawnerLibrary.reload();
    }

    private int panelW() {
        return Math.min(400, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int listTop() {
        return starredTab ? 66 : 46;
    }

    @Override
    protected void init() {
        int x = left(), w = panelW(), y = 26;
        int tx = x;
        for (boolean starTab : new boolean[]{false, true}) {
            String label = starTab ? "★ Starred" : "Recent";
            int tw = font.width(label) + 16;
            addRenderableWidget(Chip.of(label, tw, 14, () -> {
                if (starredTab != starTab) {
                    starredTab = starTab;
                    selected = 0;
                    list.scroll(0);
                    rebuildWidgets();
                }
            }).selected(() -> starredTab == starTab).at(tx, y)
                    .tip(starTab ? "Spawners you starred: kept for good, as files in config/stacksmith/spawners"
                            : "Every spawner you saved, placed or took out, newest first"));
            tx += tw + 2;
        }
        search = addRenderableWidget(new EditBox(font, tx + 6, y - 1, x + w - tx - 6, 16, Component.literal("Search")));
        search.setHint(Component.literal("Search mobs, names, places…"));
        search.setValue(query);
        search.setResponder(t -> {
            query = t;
            selected = 0;
            list.scroll(0);
            fillList();
        });
        search.setTooltip(Tooltip.create(Component.literal("Type to filter; ↑ ↓ pick, Enter loads")));

        if (starredTab) {
            String already = SpawnerLibrary.starredName(current);
            name = addRenderableWidget(new EditBox(font, x, y + 21, w - 104, 16, Component.literal("Name")));
            name.setMaxLength(80);
            name.setHint(Component.literal("Nickname for the spawner you're editing"));
            name.setValue(already != null ? already
                    : SpawnerLibrary.PRESETS.freeName(Presets.Kind.SPAWNER, SpawnerLog.title(current) + " spawner"));
            starCurrent = addRenderableWidget(Chip.of("★ Star this one", 100, 16, this::starCurrent).at(x + w - 100, y + 21));
            name.setResponder(t -> updateStarCurrent());
        } else {
            name = null;
            starCurrent = null;
        }

        list.bounds(x, listTop(), w, height - listTop() - 34);
        fillList();

        if (starredTab) {
            addRenderableWidget(Button.builder(Component.literal("Copy folder path"), b -> toast(Delivery.copy(
                            PresetsScreen.ROOT.resolve(Presets.Kind.SPAWNER.folder).toAbsolutePath().toString()), true))
                    .bounds(x, height - 28, 110, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + w - 70, height - 28, 70, 20).build());
        setInitialFocus(search);
    }

    /** Rebuilds the rows for the tab and the search, keeping the screen's other widgets (and the search box's focus). */
    private void fillList() {
        rowWidgets.forEach(this::removeWidget);
        rowWidgets.clear();
        renameBox = null;
        shown.clear();
        list.clear();
        if (starredTab) {
            List<Row> rows = new ArrayList<>();
            for (Presets.Preset p : SpawnerLibrary.starred()) {
                rows.add(Row.starred(this, p.name(), p.data(), p.saved().toMillis(), true));
            }
            unstarred.forEach((n, d) -> rows.add(Row.starred(this, n, d, 0, false)));
            rows.forEach(this::addIfShown);
        } else {
            for (SpawnerLog.Entry e : SpawnerLibrary.log().entries()) {
                addIfShown(Row.recent(this, e));
            }
        }
        selected = Math.clamp(selected, 0, Math.max(0, shown.size() - 1));
        for (Row r : shown) {
            list.add(r);
            rowWidgets.addAll(r.widgets());
        }
        rowWidgets.forEach(this::addWidget);
        list.layout();
        updateStarCurrent();
    }

    /** Star this one: for the spawner being edited, unless it is empty or starred already. */
    private void updateStarCurrent() {
        if (starCurrent == null) {
            return;
        }
        String already = SpawnerLibrary.starredName(current);
        starCurrent.active = !current.entries().isEmpty() && already == null && !name.getValue().isBlank();
        starCurrent.tip(current.entries().isEmpty() ? "The spawner you're editing is empty"
                : already != null ? "The spawner you're editing is starred as \"" + already + "\""
                : "Star the spawner you're editing, with this nickname");
    }

    private void addIfShown(Row r) {
        if (r != null && SpawnerLog.matches(r.searchText, query)) {
            shown.add(r);
        }
    }

    private void load(Nbt.Compound data) {
        onLoad.accept(data.copy());
    }

    private void starCurrent() {
        String n = SpawnerLibrary.star(current, name.getValue().isBlank() ? null : name.getValue());
        toast(Component.literal(n == null ? "Couldn't star it" : "Starred \"" + n + "\""), n != null);
        rebuildWidgets();
    }

    private void toggleStar(Row r) {
        if (r.starName != null) {
            if (SpawnerLibrary.unstar(r.starName, r.data)) {
                unstarred.put(r.starName, r.data);
                toast(Component.literal("Unstarred \"" + r.starName + "\" (still in Recent)"), true);
            }
        } else {
            String wanted = unstarred.entrySet().stream().filter(e -> e.getValue() == r.data).map(Map.Entry::getKey).findFirst().orElse(null);
            String n = SpawnerLibrary.star(r.spawner, wanted);
            if (n != null) {
                unstarred.remove(n);
                toast(Component.literal("Starred \"" + n + "\""), true);
            }
        }
        fillList();
    }

    /** ✎: edit the nickname in place of the row's title (Enter keeps it, Esc cancels). */
    private void startRename(String name) {
        if (name.equals(renaming)) {
            commitRename();
            return;
        }
        renaming = name;
        fillList();
        if (renameBox != null) {
            focusSoon(renameBox);
        }
    }

    private void commitRename() {
        if (renaming == null || renameBox == null) {
            return;
        }
        String from = renaming;
        String to = SpawnerLibrary.rename(from, renameBox.getValue(), why -> toast(Component.literal(why), false));
        if (to == null) {
            return; // stays open to fix
        }
        if (!to.equals(from)) {
            toast(Component.literal("Nicknamed \"" + to + "\""), true);
        }
        endRename();
    }

    private void endRename() {
        renaming = null;
        fillList();
        setFocused(search);
    }

    private void remove(SpawnerLog.Entry e) {
        if (e == confirmRemove) {
            SpawnerLibrary.log().remove(e);
            confirmRemove = null;
        } else {
            confirmRemove = e;
        }
        fillList();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (renameBox != null && getFocused() == renameBox) {
            switch (e.key()) {
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    commitRename();
                    return true;
                }
                case InputConstants.KEY_ESCAPE -> {
                    endRename();
                    return true;
                }
                default -> {
                    return super.keyPressed(e);
                }
            }
        }
        switch (e.key()) {
            case InputConstants.KEY_DOWN, InputConstants.KEY_UP -> {
                if (!shown.isEmpty()) {
                    selected = Math.clamp(selected + (e.key() == InputConstants.KEY_DOWN ? 1 : -1), 0, shown.size() - 1);
                    list.reveal(shown.get(selected));
                }
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (getFocused() == search && !shown.isEmpty()) {
                    Row r = shown.get(selected);
                    if (!r.editing) {
                        load(r.data);
                    }
                    return true;
                }
            }
            default -> {
            }
        }
        return super.keyPressed(e);
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = left(), w = panelW();
        panel(g, x - 6, 8, w + 12, height - 16);
        g.centeredText(font, title, width / 2, 13, Theme.TEXT);
        if (shown.isEmpty()) {
            String empty = !query.isBlank() ? "Nothing matches \"" + query.strip() + "\""
                    : starredTab ? "Nothing starred yet. Star the one you're editing above, or ★ one in Recent."
                    : "Spawners you save, place or take out show up here.";
            g.text(font, font.plainSubstrByWidth(empty, w - 8), x + 4, listTop() + 6, Theme.TEXT_DIM, false);
        }
    }

    @Override
    protected void extractOverlays(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The box hides its own hint while focused, which it is from the start.
        if (search.getValue().isEmpty()) {
            g.text(font, font.plainSubstrByWidth("Search mobs, names, places…  ↑ ↓ Enter", search.getWidth() - 8),
                    search.getX() + 4, search.getY() + 4, Theme.TEXT_FAINT, false);
        }
    }

    /** "Every 10–40 s · 4 per spawn · stops at 6 nearby · player within 16 · mobs within 4". */
    private static String settingsLine(SpawnerSpec s) {
        String u = Time.unit();
        return "Every " + Time.format(s.get(SpawnerSpec.Setting.MIN_DELAY)) + "–" + Time.format(s.get(SpawnerSpec.Setting.MAX_DELAY)) + " " + u
                + " · " + s.get(SpawnerSpec.Setting.COUNT) + " per spawn · stops at " + s.get(SpawnerSpec.Setting.MAX_NEARBY) + " nearby"
                + " · player within " + s.get(SpawnerSpec.Setting.PLAYER_RANGE) + " · mobs within " + s.get(SpawnerSpec.Setting.SPAWN_RANGE);
    }

    private static String light(SpawnerSpec.Entry e) {
        int[] b = e.blockLight, s = e.skyLight;
        if (b == null && s == null) {
            return "its own light rules";
        }
        if (b != null && s != null && b[0] == 0 && s[0] == 0 && b[1] == s[1]) {
            return b[1] == 15 ? "any light" : "light ≤ " + b[1];
        }
        return "block light " + (b == null ? "0–15" : b[0] + "–" + b[1]) + ", sky " + (s == null ? "0–15" : s[0] + "–" + s[1]);
    }

    /** One spawner: icon, title and a line about it; Load, ✎ (starred), ★ and (in Recent) ✕. Hover for details. */
    private static final class Row implements ScrollPane.Row {
        private final SpawnerLibraryScreen screen;
        final Nbt.Compound data;
        final SpawnerSpec spawner;
        /** The name it is starred under, or null. */
        final String starName;
        final boolean editing;
        final String title, subtitle, searchText;
        /** Dimmed after the title: the mobs, when the title is a nickname. */
        private String note = "";
        private final List<Component> details = new ArrayList<>();
        private final ItemStack icon;
        private final Chip load, star, remove, nick;
        /** The nickname box while this row is being renamed, else null. */
        private final EditBox box;

        private Row(SpawnerLibraryScreen screen, Nbt.Compound data, SpawnerSpec spawner, String starName, String title, String subtitle,
                    String searchText, SpawnerLog.Entry entry) {
            this.screen = screen;
            this.data = data;
            this.spawner = spawner;
            this.starName = starName;
            this.title = title;
            this.subtitle = subtitle;
            this.searchText = searchText;
            this.editing = spawner.sameAs(screen.current);
            this.icon = spawner.primary() == null ? ItemStack.EMPTY : MobEditorScreen.egg(spawner.primary().id());
            int total = spawner.entries().stream().mapToInt(e -> e.weight).sum();
            for (SpawnerSpec.Entry e : spawner.entries()) {
                String mob = SpawnerLog.mobName(e.entity);
                String type = io.github.tjhgibbs.stacksmith.core.Ids.pretty(e.entity.id());
                details.add(Component.literal((mob.equals(type) ? mob : mob + " (" + type + ")")
                        + (spawner.entries().size() > 1 ? " · " + Math.round(100.0 * e.weight / Math.max(1, total)) + "%" : "")
                        + " · " + light(e)).withColor(Theme.TEXT));
            }
            details.add(Component.literal(settingsLine(spawner)).withColor(Theme.TEXT_DIM));
            if (starName != null) {
                details.add(Component.literal("★ Starred as \"" + starName + "\"").withColor(Theme.ACCENT));
            }
            load = Chip.of(editing ? "Editing" : "Load", 44, 16, () -> screen.load(data))
                    .tip("Edit this one (replaces what you're editing; Overwrite then puts it in the block)");
            load.active = !editing;
            star = Chip.of(starName != null ? "★" : "☆", 16, 16, () -> screen.toggleStar(this))
                    .tip(starName != null ? "Unstar" : "Star: keep it for good in the Starred tab");
            if (starName != null) {
                star.color(Theme.ACCENT);
            }
            boolean renaming = starName != null && starName.equals(screen.renaming);
            if (starName != null) {
                nick = Chip.of(renaming ? "✔" : "✎", 16, 16, () -> screen.startRename(starName))
                        .tip(renaming ? "Keep this nickname (Enter); Esc cancels" : "Nickname: call it what you think of it as");
            } else {
                nick = null;
            }
            if (renaming) {
                box = new EditBox(screen.font, 0, 0, 100, 14, Component.literal("Nickname"));
                box.setMaxLength(80);
                box.setValue(starName);
                screen.renameBox = box;
            } else {
                box = null;
            }
            if (entry != null) {
                boolean sure = entry == screen.confirmRemove;
                remove = Chip.of(sure ? "Sure?" : "✕", sure ? 40 : 16, 16, () -> screen.remove(entry)).color(Theme.BAD)
                        .tip("Remove from the log (click twice)");
            } else {
                remove = null;
            }
        }

        static Row recent(SpawnerLibraryScreen screen, SpawnerLog.Entry e) {
            SpawnerSpec s;
            try {
                s = e.spawner();
            } catch (RuntimeException ex) {
                return null;
            }
            String world = SpawnerLibrary.world();
            boolean elsewhere = e.world() != null && !e.world().equals(world);
            String where = e.pos() == null ? null : e.pos() + (elsewhere ? " (" + e.world() + ")" : "");
            List<String> bits = new ArrayList<>();
            bits.add(e.how());
            if (where != null) {
                bits.add(where);
            }
            bits.add(SpawnerLog.ago(e.time(), System.currentTimeMillis()));
            if (e.uses() > 1) {
                bits.add("used " + e.uses() + "×");
            }
            String starName = SpawnerLibrary.starredName(s);
            Row r = new Row(screen, e.data(), s, starName, starName != null ? starName : SpawnerLog.title(s), String.join(" · ", bits),
                    SpawnerLog.searchText(s, e.pos(), e.how(), e.world(), starName), e);
            if (starName != null) {
                r.note = SpawnerLog.title(s);
            }
            r.details.add(Component.literal(e.how() + (where != null ? " at " + where : "") + ", "
                    + SpawnerLog.ago(e.time(), System.currentTimeMillis())).withColor(Theme.TEXT_DIM));
            return r;
        }

        static Row starred(SpawnerLibraryScreen screen, String name, Nbt.Compound data, long saved, boolean isStarred) {
            SpawnerSpec s;
            try {
                s = SpawnerSpec.fromTag(data);
            } catch (RuntimeException ex) {
                return null;
            }
            String sub = SpawnerLog.title(s) + (isStarred ? " · starred " + SpawnerLog.ago(saved, System.currentTimeMillis()) : " · unstarred");
            return new Row(screen, data, s, isStarred ? name : null, name, sub, SpawnerLog.searchText(s, name), null);
        }

        @Override
        public int layout(int x, int y, int width) {
            int rx = x + width;
            if (remove != null) {
                rx -= remove.getWidth();
                remove.setX(rx);
                remove.setY(y + 3);
                rx -= 2;
            }
            rx -= 16;
            star.setX(rx);
            star.setY(y + 3);
            if (nick != null) {
                rx -= 18;
                nick.setX(rx);
                nick.setY(y + 3);
            }
            rx -= 46;
            load.setX(rx);
            load.setY(y + 3);
            if (box != null) {
                box.setX(x + 24);
                box.setY(y + 3);
                box.setWidth(load.getX() - x - 24 - 4);
            }
            return 22;
        }

        @Override
        public void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
            var font = screen.font;
            boolean sel = screen.shown.indexOf(this) == screen.selected;
            if (sel) {
                g.fill(x, y, x + width, y + 21, 0x402E4A66);
            }
            if (editing) {
                g.fill(x, y + 1, x + 2, y + 20, Theme.GOOD);
            }
            if (!icon.isEmpty()) {
                g.item(icon, x + 4, y + 2);
            }
            if (box != null) {
                return; // the nickname box covers the title
            }
            int tx = x + 24, room = load.getX() - tx - 4;
            String t = font.plainSubstrByWidth(title, room);
            g.text(font, t, tx, y + 2, starName == null && screen.starredTab ? Theme.TEXT_DIM : Theme.TEXT, false);
            if (!note.isEmpty() && t.equals(title)) {
                int nx = tx + font.width(t) + 6;
                g.text(font, font.plainSubstrByWidth(note, tx + room - nx), nx, y + 2, Theme.TEXT_FAINT, false);
            }
            g.text(font, font.plainSubstrByWidth(subtitle, room), tx, y + 12, Theme.TEXT_DIM, false);
            if (mouseX >= x && mouseX < load.getX() - 2 && mouseY >= y && mouseY < y + 21 && screen.list.contains(mouseX, mouseY)) {
                g.setComponentTooltipForNextFrame(font, details, mouseX, mouseY);
            }
        }

        @Override
        public List<AbstractWidget> widgets() {
            List<AbstractWidget> out = new ArrayList<>(List.of(load, star));
            if (nick != null) {
                out.add(nick);
            }
            if (remove != null) {
                out.add(remove);
            }
            if (box != null) {
                out.add(box);
            }
            return out;
        }
    }
}
