package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.edit.Card;
import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.ItemView;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import io.github.tjhgibbs.stacksmith.mod.McEntities;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import io.github.tjhgibbs.stacksmith.mod.McRegistries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The spawner editor: the pool of mobs on the left, the selected mob (summarised, with
 * Edit mob… opening the full mob editor) and the spawner's settings in plain units in the
 * middle, and a live preview with the outputs on the right.
 *
 * <p>Usually tied to a spawner in the world (J while looking at one, or after placing one):
 * then the main button saves back to that block. Otherwise it places where you look,
 * replacing a spawner there. Data too long for one chat command is sent in steps (see
 * {@link Commands#setSpawnerInSteps}). Other outputs: a spawner item, a command block that
 * turns itself into the spawner when placed (always active, no redstone), or the command to copy.
 */
public final class SpawnerEditorScreen extends StacksmithScreen {
    private static final int TOP = 24, FOOTER = 26;
    private static final long DEBOUNCE_MS = 150;
    private static double leftScroll, midScroll;

    private final SpawnerSpec spawner = Session.spawner;
    private final ScrollPane left = pane(new ScrollPane());
    private final ScrollPane mid = pane(new ScrollPane());

    private Entity preview;
    private String command = "";
    private List<String> problems = new ArrayList<>();
    private boolean dirty = true;
    private long changedAt;
    private EditBox commandBox;
    private Button getSpawner, getCommandBlock, placeHere, copySetblock;
    private final Map<EntitySpec.Slot, ItemStack> slotIcons = new EnumMap<>(EntitySpec.Slot.class);

    public SpawnerEditorScreen(Screen parent) {
        super(Component.translatable("stacksmith.screen.spawner"), parent);
    }

    private EntitySpec mob() {
        if (spawner.entries().isEmpty()) {
            spawner.entries().add(new SpawnerSpec.Entry(new EntitySpec("zombie"), 1));
        }
        Session.spawnerEntry = Math.max(0, Math.min(Session.spawnerEntry, spawner.entries().size() - 1));
        return spawner.entries().get(Session.spawnerEntry).entity;
    }

    private SpawnerSpec.Entry entry() {
        mob();
        return spawner.entries().get(Session.spawnerEntry);
    }

    // ---- layout ------------------------------------------------------------------------------

    private int leftW() {
        return Math.clamp(width * 22 / 100, 110, 150);
    }

    private int rightW() {
        return Math.clamp(width * 26 / 100, 130, 190);
    }

    private int midX() {
        return PAD * 2 + leftW();
    }

    private int midW() {
        return width - leftW() - rightW() - PAD * 4;
    }

    private int rightX() {
        return width - PAD - rightW();
    }

    private int bodyBottom() {
        return height - FOOTER - PAD;
    }

    @Override
    protected void init() {
        SuggestBox.closeAll();
        header(Tab.SPAWNER, "Look at a spawner, or paste a /setblock, /summon or spawner /give",
                () -> minecraft.setScreenAndShow(new ImportScreen(this, null)));
        BlockPos looked = WorldSpawner.lookedAtSpawner();
        // The short label when the full one would run into the title.
        String loadLabel = I18n.get("stacksmith.button.load_looked_at");
        if (width - PAD - 64 - font.width(loadLabel) - 16 < (width + font.width(title)) / 2 + 4) {
            loadLabel = I18n.get("stacksmith.button.load_looked_at_short");
        }
        int lw = font.width(loadLabel) + 16;
        Chip load = addRenderableWidget(Chip.of(loadLabel, lw, 14,
                () -> WorldSpawner.load(looked, () -> minecraft.setScreenAndShow(new SpawnerEditorScreen(parent))))
                .at(width - PAD - 60 - 4 - lw, 5)
                .tip(I18n.get(looked != null ? "stacksmith.tooltip.load_looked_at" : "stacksmith.tooltip.load_looked_at_none")));
        load.active = looked != null && !looked.equals(Session.spawnerPos);
        if (Session.spawnerPos != null && !WorldSpawner.exists(Session.spawnerPos)) {
            Session.spawnerPos = null; // broken or out of reach: placing makes a new one
        }
        buildLeft();
        buildMiddle();
        buildRight();
        buildFooter();
        changed();
    }

    private static String mobLabel(EntitySpec e) {
        Nbt n = e.data().get(EntitySpec.Keys.CUSTOM_NAME);
        String markup = n == null ? null : TextMarkup.fromComponent(n);
        return markup != null && !markup.isBlank() ? TextMarkup.plain(markup) : Ids.pretty(e.id());
    }

    /** The pool: one row per mob, selected one highlighted. */
    private void buildLeft() {
        left.clear();
        left.bounds(PAD, TOP + 14, leftW(), bodyBottom() - TOP - 14);
        boolean pool = spawner.entries().size() > 1;
        for (int i = 0; i < spawner.entries().size(); i++) {
            int index = i;
            SpawnerSpec.Entry e = spawner.entries().get(i);
            Chip pick = Chip.of(mobLabel(e.entity), 100, 18, () -> {
                Session.spawnerEntry = index;
                midScroll = 0;
                rebuild();
            }).icon(MobEditorScreen.egg(e.entity.id())).selected(() -> Session.spawnerEntry == index);
            if (pool) {
                pick.setTooltip(Tooltip.create(Component.literal("Weight " + e.weight + ": picked " + percent(e.weight) + " of the time")));
            }
            Chip remove = Chip.of("✕", 14, 18, () -> {
                spawner.entries().remove(index);
                Session.spawnerEntry = Math.max(0, Session.spawnerEntry - (Session.spawnerEntry >= index ? 1 : 0));
                changed();
                rebuild();
            }).color(Theme.BAD).tip("Remove from the spawner");
            remove.active = pool;
            left.add(new PairRow(pick, remove));
        }
        left.add(new ItemEditorScreen.WidgetRow(Chip.of("+ Add mob", 80, 16, () ->
                minecraft.setScreenAndShow(new MobEditorScreen(this, new EntitySpec("zombie"), result -> {
                    spawner.entries().add(new SpawnerSpec.Entry(result, 1));
                    Session.spawnerEntry = spawner.entries().size() - 1;
                    changed();
                }))).tip("Pick and set up another mob. With several mobs the spawner picks one each time, by weight."), 18));
        addPaneWidgets(left);
        left.layout();
        left.scroll(leftScroll);
    }

    private String percent(int weight) {
        int total = spawner.entries().stream().mapToInt(x -> x.weight).sum();
        return Math.round(100.0 * weight / Math.max(1, total)) + "%";
    }

    private void buildMiddle() {
        mid.clear();
        mid.bounds(midX(), TOP + 14, midW(), bodyBottom() - TOP - 14);
        mid.add(new SelectedMobCard());
        mid.add(new SettingsCard("How often", 0xFF8CC8FF, "When the spawner fires. It waits a random time between the two delays after each spawn.",
                new Field(SpawnerSpec.Setting.DELAY, "First spawn after", "s", "Time from placing until the first spawn. Default 1 s."),
                new Field(SpawnerSpec.Setting.MIN_DELAY, "Wait at least", "s", "Shortest wait between spawns. Default 10 s."),
                new Field(SpawnerSpec.Setting.MAX_DELAY, "Wait at most", "s", "Longest wait between spawns. Default 40 s.")));
        mid.add(new SettingsCard("How many", 0xFFFF6B6B, "How many mobs each spawn makes, and when it holds back.",
                new Field(SpawnerSpec.Setting.COUNT, "Mobs per spawn", "", "How many it tries to spawn each time. Default 4."),
                new Field(SpawnerSpec.Setting.MAX_NEARBY, "Pause with", "nearby", "Stops while this many of its mobs are close by. Default 6.")));
        mid.add(new SettingsCard("Where", 0xFF7BD88F, "When it is active, and how far out mobs appear.",
                new Field(SpawnerSpec.Setting.PLAYER_RANGE, "Runs with a player within", "blocks", "Only spawns while a player is this close. Default 16."),
                new Field(SpawnerSpec.Setting.SPAWN_RANGE, "Mobs appear within", "blocks", "How far from the spawner mobs are placed. Default 4.")));
        addPaneWidgets(mid);
        mid.layout();
        mid.scroll(midScroll);
    }

    private void buildRight() {
        int x = rightX(), w = rightW();
        int y = bodyBottom() - 4 * 22 + 2;
        placeHere = addRenderableWidget(Button.builder(Component.translatable(Session.spawnerPos != null
                ? "stacksmith.button.save_spawner" : "stacksmith.button.place"), b -> placeHere()).bounds(x, y, w, 20).build());
        getSpawner = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_spawner"), b -> giveSpawner())
                .bounds(x, y + 22, w, 20).build());
        getCommandBlock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_command_block"), b -> giveCommandBlock())
                .bounds(x, y + 44, w, 20).build());
        copySetblock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_setblock"), b ->
                toast(Delivery.copy("/" + command), true)).bounds(x, y + 66, w, 20).build());
    }

    private void buildFooter() {
        int y = height - FOOTER + 3;
        commandBox = addRenderableWidget(new EditBox(font, PAD, y + 2, width - PAD * 2 - 64 - 50, 16, Component.literal("Command")));
        commandBox.setMaxLength(Commands.COMMAND_BLOCK_LIMIT);
        commandBox.setEditable(false);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - PAD - 60, y, 60, 20).build());
    }

    private Commands.Style style() {
        return new Commands.Style(Session.briefCommands, Session.target);
    }

    void changed() {
        dirty = true;
        changedAt = System.currentTimeMillis();
    }

    void rebuild() {
        leftScroll = left.scroll();
        midScroll = mid.scroll();
        rebuildWidgets();
    }

    // ---- outputs ------------------------------------------------------------------------------

    private Nbt spawnerName() {
        return TextMarkup.toComponent("&e" + Ids.pretty(mob().id()) + " spawner", true);
    }

    private void giveSpawner() {
        McItems.Result r = McItems.toStack(Commands.spawnerItem(spawner, spawnerName()), Delivery.registries());
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok());
    }

    private void giveCommandBlock() {
        String setblock = Commands.setSpawner(spawner, "~ ~ ~", style());
        ItemSpec cb = Commands.commandBlockItem(setblock, TextMarkup.toComponent("&d" + Ids.pretty(mob().id()) + " spawner (place to build)", true));
        McItems.Result r = McItems.toStack(cb, Delivery.registries());
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok());
    }

    /** Saves to the linked spawner, or places one where you look (replacing a spawner there) and links it. */
    private void placeHere() {
        BlockPos p = Session.spawnerPos;
        if (p == null) {
            BlockHitResult hit = WorldSpawner.lookedAt();
            if (hit == null) {
                toast(Component.translatable("stacksmith.msg.look_at_block"), false);
                return;
            }
            p = WorldSpawner.lookedAtSpawner() != null ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        }
        List<String> steps = Commands.setSpawnerInSteps(spawner, at(p), style(), Commands.CHAT_LIMIT);
        if (steps == null) {
            toast(Component.translatable("stacksmith.tooltip.place_too_long"), false);
            return;
        }
        boolean ok = canPlace(steps);
        Component msg = Delivery.runAll(steps);
        if (ok) {
            boolean saved = Session.spawnerPos != null;
            Session.spawnerPos = p.immutable();
            msg = saved ? Component.translatable("stacksmith.msg.saved_spawner", at(p)) : msg;
            if (!saved) {
                rebuild(); // the button now saves to this block
            }
        }
        toast(msg, ok);
    }

    /** Allowed to run these: setblock, and data when it is built in steps. */
    private static boolean canPlace(List<String> steps) {
        return steps != null && Delivery.canUse("setblock") && (steps.size() == 1 || Delivery.canUse("data"));
    }

    private static String at(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ---- update ----------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (dirty && System.currentTimeMillis() - changedAt >= DEBOUNCE_MS) {
            dirty = false;
            recompute();
        }
    }

    private void recompute() {
        command = Commands.setSpawner(spawner, "~ ~ ~", style());
        commandBox.setValue(command);
        commandBox.moveCursorToStart(false);
        problems = new ArrayList<>(spawner.problems());
        boolean op = Delivery.canUse("setblock");
        if (!op && Delivery.inWorld()) {
            problems.add("You aren't an operator here: the server strips the data from spawners and command blocks you place.");
        }
        if (preview != null) {
            preview.discard();
        }
        preview = minecraft.level != null ? McEntities.create(mob(), minecraft.level) : null;
        slotIcons.clear();
        for (EntitySpec.Slot s : EntitySpec.Slot.values()) {
            ItemSpec item = mob().equipment(s);
            if (item != null && Delivery.registries() != null) {
                McItems.Result r = McItems.toStack(item, Delivery.registries());
                slotIcons.put(s, r.ok() ? r.stack() : ItemStack.EMPTY);
            }
        }
        boolean creative = Delivery.isCreative();
        boolean valid = spawner.problems().isEmpty();
        getSpawner.active = creative && valid;
        getCommandBlock.active = creative && valid && command.length() <= Commands.COMMAND_BLOCK_LIMIT;
        // A sample position as long as real ones get, to see whether it needs steps or can't be sent.
        List<String> steps = Commands.setSpawnerInSteps(spawner, "-30000000 -64 -30000000", style(), Commands.CHAT_LIMIT);
        placeHere.active = valid && canPlace(steps);
        copySetblock.active = valid;
        getSpawner.setTooltip(Tooltip.create(Component.translatable(creative ? "stacksmith.tooltip.get_spawner" : "stacksmith.tooltip.get_creative")));
        getCommandBlock.setTooltip(Tooltip.create(Component.translatable(creative ? "stacksmith.tooltip.get_command_block" : "stacksmith.tooltip.get_creative")));
        placeHere.setTooltip(Tooltip.create(!op ? Component.translatable("stacksmith.tooltip.place_no_permission")
                : steps == null ? Component.translatable("stacksmith.tooltip.place_too_long")
                : Session.spawnerPos != null ? Component.translatable("stacksmith.tooltip.save_spawner", at(Session.spawnerPos))
                : Component.translatable("stacksmith.tooltip.place")));
        copySetblock.setTooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.copy_setblock")));
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, title, width / 2, 8, Theme.TEXT);
        int top = TOP - 2, bottom = bodyBottom() + 2;
        panel(g, PAD - 2, top, leftW() + 4, bottom - top);
        panel(g, midX() - 2, top, midW() + 4, bottom - top);
        panel(g, rightX() - 2, top, rightW() + 4, bottom - top);
        label(g, spawner.entries().size() > 1 ? "Spawns (picks one)" : "Spawns", PAD + 1, TOP + 2);
        label(g, Session.spawnerPos != null ? "Spawner at " + at(Session.spawnerPos) : "New spawner", midX() + 1, TOP + 2);
        int rx = rightX(), rw = rightW();
        label(g, "Preview", rx + 1, TOP + 2);
        int py = TOP + 14, ph = bodyBottom() - 4 * 22 - py - 26;
        MobPreview.draw(g, font, preview, rx, py, rw, ph, mouseX, mouseY);
        int y = py + ph + 4;
        if (problems.isEmpty()) {
            g.text(font, "✔ Ready", rx + 2, y, Theme.GOOD, false);
        }
        for (String p : problems) {
            for (FormattedCharSequence l : font.split(Component.literal(p), rw - 4)) {
                if (y > bodyBottom() - 4 * 22 - 2) {
                    break;
                }
                g.text(font, l, rx + 2, y, Theme.WARN, false);
                y += 10;
            }
        }
        int len = command.length() + 1;
        String count = len + "/" + Commands.CHAT_LIMIT;
        g.text(font, count, commandBox.getX() + commandBox.getWidth() + 4, commandBox.getY() + 4,
                len <= Commands.CHAT_LIMIT ? Theme.TEXT_DIM : Theme.WARN, false);
    }

    @Override
    public void removed() {
        leftScroll = left.scroll();
        midScroll = mid.scroll();
        if (preview != null) {
            preview.discard();
            preview = null;
        }
        super.removed();
    }

    // ---- rows and cards -----------------------------------------------------------------------

    /** A wide widget with a small one at its right. */
    private static final class PairRow implements ScrollPane.Row {
        private final net.minecraft.client.gui.components.AbstractWidget main, side;

        PairRow(net.minecraft.client.gui.components.AbstractWidget main, net.minecraft.client.gui.components.AbstractWidget side) {
            this.main = main;
            this.side = side;
        }

        @Override
        public int layout(int x, int y, int width) {
            main.setX(x);
            main.setY(y);
            main.setWidth(width - side.getWidth() - 2);
            side.setX(x + width - side.getWidth());
            side.setY(y);
            return main.getHeight() + 2;
        }

        @Override
        public List<net.minecraft.client.gui.components.AbstractWidget> widgets() {
            return List.of(main, side);
        }
    }

    /** One setting: a label, a box in friendly units (seconds for ticks), and the unit. */
    private record Field(SpawnerSpec.Setting setting, String label, String unit, String help) {
        boolean seconds() {
            return unit.equals("s");
        }
    }

    /** A titled group of settings, each on its own line. */
    private final class SettingsCard extends Card {
        private final List<Field> fields;
        private final List<EditBox> boxes = new ArrayList<>();

        SettingsCard(String title, int accent, String about, Field... fields) {
            super(title, accent, about);
            this.fields = List.of(fields);
            for (Field f : fields) {
                EditBox box = body(new EditBox(font, 0, 0, 40, 14, Component.literal(f.label())));
                int v = spawner.get(f.setting());
                box.setValue(f.seconds() ? trim(v / 20.0) : Integer.toString(v));
                box.setTooltip(Tooltip.create(Component.literal(f.help())));
                box.setResponder(t -> {
                    try {
                        double d = Double.parseDouble(t.strip());
                        int ticks = (int) Math.round(f.seconds() ? d * 20 : d);
                        boolean ok = ticks >= f.setting().min && ticks <= f.setting().max && (f.seconds() || d == Math.rint(d));
                        box.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                        if (ok) {
                            spawner.set(f.setting(), ticks);
                            changed();
                        }
                    } catch (NumberFormatException e) {
                        box.setTextColor(Theme.BAD);
                    }
                });
                boxes.add(box);
            }
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "");
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            for (int i = 0; i < boxes.size(); i++) {
                EditBox b = boxes.get(i);
                b.setX(x + w - 40 - unitW());
                b.setY(y + i * 17);
            }
            return boxes.size() * 17 - 3;
        }

        private int unitW() {
            int m = 0;
            for (Field f : fields) {
                m = Math.max(m, font.width(f.unit()));
            }
            return m == 0 ? 0 : m + 4;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                int ry = y + i * 17;
                int labelRoom = w - 44 - unitW();
                g.text(font, font.plainSubstrByWidth(f.label(), labelRoom), x, ry + 3, Theme.TEXT, false);
                if (!f.unit().isEmpty()) {
                    g.text(font, f.unit(), x + w - unitW() + 4, ry + 3, Theme.TEXT_DIM, false);
                }
                boolean def = spawner.get(f.setting()) == f.setting().defaultValue;
                if (!def && mouseX >= x && mouseX < x + labelRoom && mouseY >= ry && mouseY < ry + 14) {
                    g.setTooltipForNextFrame(font, Component.literal(f.help()), mouseX, mouseY);
                }
            }
        }
    }

    /** The selected mob at a glance, with Edit mob… and (in a pool) its weight. */
    private final class SelectedMobCard extends Card {
        private final Chip edit;
        private final EditBox weight;

        SelectedMobCard() {
            super("Mob", 0xFFFF9EC7, "What this spawner makes. Edit mob… opens the full mob editor.");
            EntitySpec mob = mob();
            edit = body(Chip.of("Edit mob…", 70, 16, () -> minecraft.setScreenAndShow(new MobEditorScreen(SpawnerEditorScreen.this, mob, result -> {
                entry().entity = result;
                changed();
            }))).tip("Type, name, gear, behaviour, stats, effects"));
            if (spawner.entries().size() > 1) {
                weight = body(new EditBox(font, 0, 0, 30, 14, Component.literal("Weight")));
                weight.setValue(Integer.toString(entry().weight));
                weight.setTooltip(Tooltip.create(Component.literal("How often this one is picked compared with the others")));
                weight.setResponder(t -> {
                    try {
                        int v = Integer.parseInt(t.strip());
                        weight.setTextColor(v >= 1 ? Theme.TEXT : Theme.BAD);
                        if (v >= 1) {
                            entry().weight = v;
                            changed();
                        }
                    } catch (NumberFormatException e) {
                        weight.setTextColor(Theme.BAD);
                    }
                });
            } else {
                weight = null;
            }
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            edit.setX(x);
            edit.setY(y + 40);
            if (weight != null) {
                weight.setX(x + w - 30);
                weight.setY(y + 41);
            }
            return 56;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            EntitySpec mob = mob();
            g.fill(x, y, x + 20, y + 20, 0xFF0D1114);
            g.outline(x, y, 20, 20, Theme.BORDER);
            ItemStack egg = MobEditorScreen.egg(mob.id());
            if (!egg.isEmpty()) {
                g.item(egg, x + 2, y + 2);
            }
            Nbt n = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
            Component name = n == null ? Component.literal(Ids.pretty(mob.id()))
                    : io.github.tjhgibbs.stacksmith.mod.McText.toGame(n, Delivery.registries());
            g.text(font, name.getVisualOrderText(), x + 24, y + 1, 0xFFFFFFFF, true);
            g.text(font, Ids.brief(mob.id()), x + 24, y + 11, Theme.TEXT_DIM, false);
            // Gear and a short summary line.
            int ix = x;
            for (ItemStack s : slotIcons.values()) {
                if (!s.isEmpty()) {
                    g.item(s, ix, y + 22);
                    ix += 17;
                }
            }
            List<String> bits = new ArrayList<>();
            if (!mob.effects().isEmpty()) {
                bits.add(mob.effects().size() + (mob.effects().size() == 1 ? " effect" : " effects"));
            }
            for (EntitySpec.Flag f : EntitySpec.Flag.values()) {
                if (mob.flag(f) && f != EntitySpec.Flag.NAME_VISIBLE) {
                    bits.add(f.key);
                }
            }
            String summary = bits.isEmpty() ? (ix == x ? "No gear or changes yet" : "") : String.join(", ", bits);
            g.text(font, font.plainSubstrByWidth(summary, x + w - ix - 4), ix + (ix == x ? 0 : 4), y + 26, Theme.TEXT_DIM, false);
            if (weight != null) {
                g.text(font, "Weight", weight.getX() - font.width("Weight") - 4, y + 44, Theme.TEXT_DIM, false);
            }
        }
    }
}
