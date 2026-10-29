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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The spawner editor: the spawner's timing on the left with its pool of mobs, the selected
 * mob's editor in the middle (type, name, equipment, behaviour, stats, effects, all data),
 * and a live preview of the mob, wearing and holding what it was given, on the right.
 *
 * <p>Outputs: a spawner item, a command block that turns itself into the spawner when placed
 * (always active, no redstone), a /setblock where you look, or the command to copy.
 */
public final class SpawnerEditorScreen extends StacksmithScreen {
    private static final int TOP = 24, FOOTER = 46;
    private static final long DEBOUNCE_MS = 150;
    /** Attributes offered as fields; anything else goes in "All data". */
    private static final String[][] ATTRIBUTES = {
            {"minecraft:max_health", "Max health"}, {"minecraft:movement_speed", "Speed"}, {"minecraft:attack_damage", "Attack damage"},
            {"minecraft:armor", "Armour"}, {"minecraft:knockback_resistance", "Knockback resist."}, {"minecraft:follow_range", "Follow range"},
            {"minecraft:scale", "Scale"}};
    private static double leftScroll, midScroll;

    private final SpawnerSpec spawner = Session.spawner;
    private final ScrollPane left = pane(new ScrollPane());
    private final ScrollPane mid = pane(new ScrollPane());
    private List<String> entityIds;

    private Entity preview;
    private String command = "";
    private List<String> problems = new ArrayList<>();
    private boolean dirty = true;
    private long changedAt;
    private EditBox commandBox;
    private Button getSpawner, getCommandBlock, placeHere, copySetblock;
    private CodeArea allData;
    private int dataVersion, shownDataVersion;
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
        return Math.clamp(width * 24 / 100, 130, 170);
    }

    private int rightW() {
        return Math.clamp(width * 28 / 100, 130, 200);
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
        StacksmithClient.spawnerLast = true;
        if (entityIds == null) {
            entityIds = McEntities.summonableIds();
        }
        addRenderableWidget(Chip.of("Item", 46, 14, () -> minecraft.setScreenAndShow(new ItemEditorScreen(parent))).at(PAD, 5));
        addRenderableWidget(Chip.of("Spawner", 54, 14, () -> {
        }).selected(() -> true).at(PAD + 48, 5));
        addRenderableWidget(Chip.of("Import…", 60, 14, () -> minecraft.setScreenAndShow(new ImportScreen(this, null)))
                .at(width - PAD - 60, 5).tip("Look at a spawner, or paste a /setblock, /summon or spawner /give"));
        buildLeft();
        buildMiddle();
        buildRight();
        buildFooter();
        changed();
    }

    private void buildLeft() {
        left.clear();
        left.bounds(PAD, TOP + 2, leftW(), bodyBottom() - TOP - 2);
        left.add(new ItemEditorScreen.HeaderRow("Timing (ticks; 20 = 1 second)"));
        for (SpawnerSpec.Setting s : SpawnerSpec.Setting.values()) {
            left.add(new NumberRow(settingLabel(s), spawner.get(s), s.min, s.max, v -> {
                spawner.set(s, v);
                changed();
            }, settingHelp(s)));
        }
        left.add(new ItemEditorScreen.HeaderRow("Spawns"));
        for (int i = 0; i < spawner.entries().size(); i++) {
            int index = i;
            SpawnerSpec.Entry e = spawner.entries().get(i);
            String name = Ids.pretty(e.entity.id()) + (spawner.entries().size() > 1 ? "  ×" + e.weight : "");
            Chip pick = new Chip(Component.literal(name), 100, 14, in -> {
                Session.spawnerEntry = index;
                midScroll = 0;
                rebuild();
            }).left().selected(() -> Session.spawnerEntry == index).accent(0xFFFF9EC7);
            Chip remove = Chip.of("✕", 14, 14, () -> {
                spawner.entries().remove(index);
                Session.spawnerEntry = Math.max(0, Session.spawnerEntry - (Session.spawnerEntry >= index ? 1 : 0));
                changed();
                rebuild();
            }).color(Theme.BAD).tip("Remove from the pool");
            remove.active = spawner.entries().size() > 1;
            left.add(new PairRow(pick, remove));
        }
        left.add(new ItemEditorScreen.WidgetRow(Chip.of("+ Add mob", 80, 14, () -> {
            spawner.entries().add(new SpawnerSpec.Entry(mob().copy(), 1));
            Session.spawnerEntry = spawner.entries().size() - 1;
            changed();
            rebuild();
        }).tip("Several mobs make a weighted pool; the spawner picks one each time"), 16));
        addPaneWidgets(left);
        left.layout();
        left.scroll(leftScroll);
    }

    private static String settingLabel(SpawnerSpec.Setting s) {
        return switch (s) {
            case DELAY -> "First delay";
            case MIN_DELAY -> "Min delay";
            case MAX_DELAY -> "Max delay";
            case COUNT -> "Spawn count";
            case MAX_NEARBY -> "Max nearby";
            case PLAYER_RANGE -> "Player range";
            case SPAWN_RANGE -> "Spawn range";
        };
    }

    private static String settingHelp(SpawnerSpec.Setting s) {
        return switch (s) {
            case DELAY -> "Ticks until the first spawn after placing. Default 20.";
            case MIN_DELAY -> "Shortest wait between spawns, in ticks. Default 200 (10 s).";
            case MAX_DELAY -> "Longest wait between spawns, in ticks. Default 800 (40 s).";
            case COUNT -> "Mobs tried per spawn. Default 4.";
            case MAX_NEARBY -> "Stops spawning while this many of the mob are nearby. Default 6.";
            case PLAYER_RANGE -> "Only spawns with a player this close (blocks). Default 16.";
            case SPAWN_RANGE -> "How far from the spawner mobs appear (blocks). Default 4.";
        };
    }

    private void buildMiddle() {
        mid.clear();
        mid.bounds(midX(), TOP + 2, midW(), bodyBottom() - TOP - 2);
        EntitySpec mob = mob();
        mid.add(new MobCard(mob));
        mid.add(new EquipmentCard(mob));
        mid.add(new FlagsCard(mob));
        mid.add(new StatsCard(mob));
        mid.add(new DataCard(mob));
        addPaneWidgets(mid);
        mid.layout();
        mid.scroll(midScroll);
    }

    private void buildRight() {
        int x = rightX(), w = rightW();
        int y = bodyBottom() - 4 * 22 + 2;
        getSpawner = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_spawner"), b -> giveSpawner())
                .bounds(x, y, w, 20).build());
        getCommandBlock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_command_block"), b -> giveCommandBlock())
                .bounds(x, y + 22, w, 20).build());
        placeHere = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.place"), b -> placeHere())
                .bounds(x, y + 44, w, 20).build());
        copySetblock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_setblock"), b ->
                toast(Delivery.copy("/" + command), true)).bounds(x, y + 66, w, 20).build());
    }

    private void buildFooter() {
        int y = height - FOOTER;
        commandBox = addRenderableWidget(new EditBox(font, PAD, y, width - PAD * 2 - 58, 16, Component.literal("Command")));
        commandBox.setMaxLength(Commands.COMMAND_BLOCK_LIMIT);
        commandBox.setEditable(false);
        addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_summon"), b ->
                        toast(Delivery.copy("/" + Commands.summon(mob(), "~ ~1 ~", style())), true))
                .bounds(PAD, y + 20, 100, 20).tooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.copy_summon"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - PAD - 60, y + 20, 60, 20).build());
    }

    private Commands.Style style() {
        return new Commands.Style(Session.briefCommands, Session.target);
    }

    void changed() {
        dirty = true;
        dataVersion++;
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

    private void placeHere() {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            toast(Component.translatable("stacksmith.msg.look_at_block"), false);
            return;
        }
        BlockPos p = hit.getBlockPos().relative(hit.getDirection());
        String cmd = Commands.setSpawner(spawner, p.getX() + " " + p.getY() + " " + p.getZ(), style());
        Component msg = Delivery.run(cmd);
        toast(msg, cmd.length() + 1 <= Commands.CHAT_LIMIT && Delivery.canUse("setblock"));
    }

    // ---- update ----------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (dirty && System.currentTimeMillis() - changedAt >= DEBOUNCE_MS) {
            dirty = false;
            recompute();
        }
        if (allData != null && shownDataVersion != dataVersion && !allData.isFocused()) {
            allData.setText(Outline.write(mob().data()).stripTrailing(), true);
            shownDataVersion = dataVersion;
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
        placeHere.active = valid && op && command.length() + 20 <= Commands.CHAT_LIMIT;
        copySetblock.active = valid;
        getSpawner.setTooltip(Tooltip.create(Component.translatable(creative ? "stacksmith.tooltip.get_spawner" : "stacksmith.tooltip.get_creative")));
        getCommandBlock.setTooltip(Tooltip.create(Component.translatable(creative ? "stacksmith.tooltip.get_command_block" : "stacksmith.tooltip.get_creative")));
        placeHere.setTooltip(Tooltip.create(Component.translatable(!op ? "stacksmith.tooltip.place_no_permission"
                : command.length() + 20 > Commands.CHAT_LIMIT ? "stacksmith.tooltip.place_too_long" : "stacksmith.tooltip.place")));
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
        int rx = rightX(), rw = rightW();
        label(g, "Preview", rx + 1, TOP + 2);
        int py = TOP + 14, ph = bodyBottom() - 4 * 22 - py - 40;
        g.fillGradient(rx, py, rx + rw, py + ph, 0xFF20303C, 0xFF0E1418);
        if (preview instanceof LivingEntity living) {
            int size = (int) Math.max(12, Math.min(ph * 0.42, rw * 0.5) / Math.max(1, Math.max(living.getBbHeight(), living.getBbWidth() * 1.2)));
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, rx, py, rx + rw, py + ph, size, 0.0625f, mouseX, mouseY, living);
        } else {
            g.centeredText(font, Component.literal(preview == null ? "No preview" : "Not a mob"), rx + rw / 2, py + ph / 2 - 4, Theme.TEXT_DIM);
        }
        int y = py + ph + 4;
        List<String> lines = problems.isEmpty() ? List.of() : problems;
        for (String p : lines) {
            for (FormattedCharSequence l : font.split(Component.literal(p), rw - 4)) {
                if (y > bodyBottom() - 4 * 22 - 2) {
                    break;
                }
                g.text(font, l, rx + 2, y, Theme.WARN, false);
                y += 10;
            }
        }
        if (lines.isEmpty()) {
            g.text(font, "✔ Ready", rx + 2, y, Theme.GOOD, false);
        }
        int len = command.length() + 1;
        String count = len + " chars";
        g.text(font, count, width - PAD - font.width(count), height - FOOTER + 4,
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

    /** "Label [ 200 ]" with validation. */
    private final class NumberRow implements ScrollPane.Row {
        private final String label;
        private final EditBox box;

        NumberRow(String label, int value, int min, int max, java.util.function.IntConsumer onChange, String help) {
            this.label = label;
            box = new EditBox(font, 0, 0, 44, 14, Component.literal(label));
            box.setValue(Integer.toString(value));
            box.setTooltip(Tooltip.create(Component.literal(help + " (" + min + " to " + max + ")")));
            box.setResponder(s -> {
                try {
                    int v = Integer.parseInt(s.strip());
                    boolean ok = v >= min && v <= max;
                    box.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                    if (ok) {
                        onChange.accept(v);
                    }
                } catch (NumberFormatException e) {
                    box.setTextColor(Theme.BAD);
                }
            });
        }

        @Override
        public int layout(int x, int y, int width) {
            box.setX(x + width - 46);
            box.setY(y);
            return 16;
        }

        @Override
        public void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
            g.text(font, label, x + 2, y + 3, Theme.TEXT, false);
        }

        @Override
        public List<net.minecraft.client.gui.components.AbstractWidget> widgets() {
            return List.of(box);
        }
    }

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

    /** Type, weight and name. */
    private final class MobCard extends Card {
        private final SuggestBox type;
        private final EditBox weight, name;
        private final Chip nameVisible;

        MobCard(EntitySpec mob) {
            super("Mob", 0xFFFF9EC7, "What this spawner makes");
            type = body(new SuggestBox(font, 100, 16, Component.literal("Mob type"), () -> entityIds));
            type.setValueQuietly(Ids.brief(mob.id()));
            type.onAccept(s -> {
                String id = Ids.full(s);
                boolean ok = McEntities.isEntity(id);
                type.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                if (ok && !id.equals(mob.id())) {
                    mob.id(id);
                    changed();
                }
            });
            type.setTooltip(Tooltip.create(Component.literal("Mob type: type to search")));
            weight = body(new EditBox(font, 0, 0, 30, 16, Component.literal("Weight")));
            weight.setValue(Integer.toString(entry().weight));
            weight.setTooltip(Tooltip.create(Component.literal("Weight in the pool: how often this one is picked compared with the others")));
            weight.setResponder(s -> {
                try {
                    int v = Integer.parseInt(s.strip());
                    weight.setTextColor(v >= 1 ? Theme.TEXT : Theme.BAD);
                    if (v >= 1) {
                        entry().weight = v;
                        changed();
                    }
                } catch (NumberFormatException e) {
                    weight.setTextColor(Theme.BAD);
                }
            });
            weight.visible = spawner.entries().size() > 1;
            Nbt n = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
            String markup = n == null ? "" : TextMarkup.fromComponent(n);
            name = body(new EditBox(font, 0, 0, 100, 16, Component.literal("Name")));
            name.setMaxLength(1024);
            name.setHint(Component.literal("Name (optional, &-codes work)"));
            name.setValue(markup == null ? "" : markup);
            name.setEditable(markup != null);
            name.setTooltip(Tooltip.create(Component.literal(markup == null ? "This name uses features the box can't show; edit it in All data."
                    : "Custom name. &6 gold, &l bold, &#ff8800 any colour, &r reset")));
            name.setResponder(s -> {
                mob.data().putOrRemove(EntitySpec.Keys.CUSTOM_NAME, s.isEmpty() ? null : TextMarkup.toComponent(s, false));
                changed();
            });
            nameVisible = body(Chip.of("Always show name", 100, 14, () -> {
                mob.flag(EntitySpec.Flag.NAME_VISIBLE, !mob.flag(EntitySpec.Flag.NAME_VISIBLE));
                changed();
            }).selected(() -> mob.flag(EntitySpec.Flag.NAME_VISIBLE)));
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            boolean pool = spawner.entries().size() > 1;
            type.setX(x);
            type.setY(y);
            type.setWidth(pool ? w - 34 : w);
            weight.setX(x + w - 30);
            weight.setY(y);
            name.setX(x);
            name.setY(y + 20);
            name.setWidth(w);
            nameVisible.setX(x);
            nameVisible.setY(y + 40);
            return 54;
        }
    }

    /** Seven slots: icon (click to choose), edit, clear, drop chance. */
    private final class EquipmentCard extends Card {
        private final List<EntitySpec.Slot> slots = List.of(EntitySpec.Slot.HEAD, EntitySpec.Slot.CHEST, EntitySpec.Slot.LEGS,
                EntitySpec.Slot.FEET, EntitySpec.Slot.MAINHAND, EntitySpec.Slot.OFFHAND, EntitySpec.Slot.BODY);
        private final List<Chip> pick = new ArrayList<>(), edit = new ArrayList<>(), clear = new ArrayList<>();
        private final List<EditBox> chance = new ArrayList<>();
        private int bodyX, bodyY;

        EquipmentCard(EntitySpec mob) {
            super("Equipment", 0xFF7FDBCA, "What the mob wears and holds, and the chance each drops on death");
            for (EntitySpec.Slot s : slots) {
                ItemSpec item = mob.equipment(s);
                pick.add(body(Chip.of(item == null ? "choose…" : Ids.pretty(item.id()), 80, 18, () ->
                        minecraft.setScreenAndShow(new ItemPickerScreen(SpawnerEditorScreen.this, picked -> {
                            mob.equipment(s, picked);
                            changed();
                        }))).left().tip("Pick an item, or one from your inventory")));
                Chip e = body(Chip.of("✎", 16, 18, () -> {
                    ItemSpec current = mob.equipment(s);
                    minecraft.setScreenAndShow(new ItemEditorScreen(SpawnerEditorScreen.this, current != null ? current : new ItemSpec("stone"), result -> {
                        mob.equipment(s, result);
                        changed();
                    }));
                }).tip("Edit this item in full (enchantments, name, anything)"));
                edit.add(e);
                Chip c = body(Chip.of("✕", 14, 18, () -> {
                    mob.equipment(s, null);
                    changed();
                    rebuild();
                }).color(Theme.BAD).tip("Empty this slot"));
                c.active = item != null;
                clear.add(c);
                EditBox box = body(new EditBox(font, 0, 0, 34, 18, Component.literal("Drop chance")));
                box.setValue(String.format(Locale.ROOT, "%.1f", mob.dropChance(s) * 100).replace(".0", ""));
                box.setTooltip(Tooltip.create(Component.literal("Drop chance in % (0 never, 100 always, default 8.5)")));
                box.setResponder(t -> {
                    try {
                        float v = Float.parseFloat(t.strip().replace("%", ""));
                        boolean ok = v >= 0 && v <= 100;
                        box.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                        if (ok) {
                            mob.dropChance(s, v / 100f);
                            changed();
                        }
                    } catch (NumberFormatException ex) {
                        box.setTextColor(Theme.BAD);
                    }
                });
                chance.add(box);
            }
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            bodyX = x;
            bodyY = y;
            int labelW = 44, iconW = 20;
            for (int i = 0; i < slots.size(); i++) {
                int ry = y + i * 20;
                int px = x + labelW + iconW;
                int pw = w - labelW - iconW - 16 - 14 - 34 - 6;
                pick.get(i).setX(px);
                pick.get(i).setY(ry);
                pick.get(i).setWidth(Math.max(30, pw));
                edit.get(i).setX(px + Math.max(30, pw) + 2);
                edit.get(i).setY(ry);
                clear.get(i).setX(px + Math.max(30, pw) + 20);
                clear.get(i).setY(ry);
                chance.get(i).setX(x + w - 34);
                chance.get(i).setY(ry);
            }
            return slots.size() * 20;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            for (int i = 0; i < slots.size(); i++) {
                int ry = y + i * 20;
                g.text(font, slotName(slots.get(i)), x, ry + 5, Theme.TEXT_DIM, false);
                g.fill(x + 44, ry, x + 62, ry + 18, 0xFF0D1114);
                g.outline(x + 44, ry, 18, 18, Theme.BORDER);
                ItemStack icon = slotIcons.get(slots.get(i));
                if (icon != null && !icon.isEmpty()) {
                    g.item(icon, x + 45, ry + 1);
                    if (mouseX >= x + 44 && mouseX < x + 62 && mouseY >= ry && mouseY < ry + 18) {
                        g.setTooltipForNextFrame(font, icon, mouseX, mouseY);
                    }
                }
            }
        }

        private String slotName(EntitySpec.Slot s) {
            return switch (s) {
                case HEAD -> "Head";
                case CHEST -> "Chest";
                case LEGS -> "Legs";
                case FEET -> "Feet";
                case MAINHAND -> "Main hand";
                case OFFHAND -> "Off hand";
                case BODY -> "Body";
                case SADDLE -> "Saddle";
            };
        }
    }

    /** On/off behaviour switches. */
    private final class FlagsCard extends Card {
        private final List<Chip> chips = new ArrayList<>();

        FlagsCard(EntitySpec mob) {
            super("Behaviour", 0xFF8CC8FF, "Switches saved on the mob");
            add(mob, EntitySpec.Flag.PERSISTENT, "Never despawn");
            add(mob, EntitySpec.Flag.NO_AI, "No AI (statue)");
            add(mob, EntitySpec.Flag.SILENT, "Silent");
            add(mob, EntitySpec.Flag.GLOWING, "Glowing");
            add(mob, EntitySpec.Flag.INVULNERABLE, "Invulnerable");
            add(mob, EntitySpec.Flag.NO_GRAVITY, "No gravity");
            add(mob, EntitySpec.Flag.PICKS_UP_LOOT, "Picks up items");
            add(mob, EntitySpec.Flag.LEFT_HANDED, "Left-handed");
        }

        private void add(EntitySpec mob, EntitySpec.Flag f, String label) {
            chips.add(body(Chip.of(label, 90, 14, () -> {
                mob.flag(f, !mob.flag(f));
                changed();
            }).selected(() -> mob.flag(f)).tip(f.key)));
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cols = w >= 200 ? 2 : 1, cw = (w - (cols - 1) * 2) / cols;
            for (int i = 0; i < chips.size(); i++) {
                chips.get(i).setWidth(cw);
                chips.get(i).setX(x + i % cols * (cw + 2));
                chips.get(i).setY(y + i / cols * 16);
            }
            return (chips.size() + cols - 1) / cols * 16 - 2;
        }
    }

    /** Health and common attributes; blank means the mob's default. */
    private final class StatsCard extends Card {
        private final List<EditBox> boxes = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();

        StatsCard(EntitySpec mob) {
            super("Stats", 0xFFFF6B6B, "Leave a box empty to keep the mob's normal value");
            labels.add("Health");
            boxes.add(body(numberBox(Float.isNaN(mob.health()) ? "" : trim(mob.health()), "Current health (set Max health too, to go above the default)", v -> {
                mob.health(v == null ? Float.NaN : v.floatValue());
                changed();
            })));
            for (String[] a : ATTRIBUTES) {
                labels.add(a[1]);
                double base = mob.attribute(a[0]);
                boxes.add(body(numberBox(Double.isNaN(base) ? "" : trim(base), a[0], v -> {
                    mob.attribute(a[0], v == null ? Double.NaN : v);
                    changed();
                })));
            }
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }

        private EditBox numberBox(String value, String tip, java.util.function.Consumer<Double> onChange) {
            EditBox box = new EditBox(font, 0, 0, 50, 14, Component.literal(tip));
            box.setValue(value);
            box.setHint(Component.literal("default"));
            box.setTooltip(Tooltip.create(Component.literal(tip)));
            box.setResponder(s -> {
                if (s.isBlank()) {
                    box.setTextColor(Theme.TEXT);
                    onChange.accept(null);
                    return;
                }
                try {
                    double v = Double.parseDouble(s.strip());
                    box.setTextColor(Theme.TEXT);
                    onChange.accept(v);
                } catch (NumberFormatException e) {
                    box.setTextColor(Theme.BAD);
                }
            });
            return box;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cols = w >= 220 ? 2 : 1, cw = (w - (cols - 1) * 6) / cols;
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setX(x + i % cols * (cw + 6) + cw - 50);
                boxes.get(i).setY(y + i / cols * 16);
            }
            return (boxes.size() + cols - 1) / cols * 16 - 2;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            int cols = w >= 220 ? 2 : 1, cw = (w - (cols - 1) * 6) / cols;
            for (int i = 0; i < labels.size(); i++) {
                g.text(font, labels.get(i), x + i % cols * (cw + 6), y + i / cols * 16 + 3, Theme.TEXT_DIM, false);
            }
        }
    }

    /** Every field of the mob as an outline: effects, passengers, anything not covered above. */
    private final class DataCard extends Card {
        private final CodeArea area;
        private String error = "";

        DataCard(EntitySpec mob) {
            super("All data", 0xFF9AA5B1, "The mob's full data as an outline. Effects, passengers, mob-specific fields "
                    + "(e.g. IsBaby, powered, Size): add them here.");
            area = body(new CodeArea(Outline.write(mob.data()).stripTrailing(), 100).lines(3, 24)
                    .placeholder("e.g. IsBaby: true"));
            allData = area;
            shownDataVersion = dataVersion;
            area.onChange(text -> {
                try {
                    Nbt.Compound c = text.isBlank() ? new Nbt.Compound() : Outline.readCompound(text);
                    mob.data().entries().clear();
                    c.entries().forEach(e -> mob.data().put(e.getKey(), e.getValue()));
                    error = "";
                    area.errorLine(0);
                    changed();
                    shownDataVersion = dataVersion;
                } catch (SnbtException e) {
                    error = e.getMessage();
                    area.errorLine(e.line);
                }
            });
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            area.setX(x);
            area.setY(y);
            area.setWidth(w);
            return area.getHeight() + (error.isEmpty() ? 0 : 11);
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            if (!error.isEmpty()) {
                g.text(font, font.plainSubstrByWidth(error, w), x, y + area.getHeight() + 2, Theme.BAD, false);
            }
        }
    }
}
