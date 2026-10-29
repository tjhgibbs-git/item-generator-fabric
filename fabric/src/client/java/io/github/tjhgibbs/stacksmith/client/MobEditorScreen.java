package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.edit.Card;
import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.FormatBar;
import io.github.tjhgibbs.stacksmith.client.ui.RichTextArea;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Swatch;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;
import io.github.tjhgibbs.stacksmith.core.schema.EntitySchema;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import io.github.tjhgibbs.stacksmith.mod.McEntities;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import io.github.tjhgibbs.stacksmith.mod.McRegistries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The mob editor.
 *
 * <pre>
 * +-[Item][Mob][Spawner]------------- Stacksmith ---------------------------[Import...]-+
 * | [search mobs...]  | +- Name -----------------------------+ | Preview             |
 * |  Hostile          | | [swatches] [B I U S]               | |   (the mob, live)   |
 * |   (egg) Zombie    | | &cBoss                             | |                     |
 * |   (egg) Skeleton  | +- Equipment ------------------------+ | [Get spawn egg]     |
 * |  Animals          | | Head  [icon] diamond_helmet ✎ ✕ 0 | | [Summon here]       |
 * |   ...             | +- Behaviour / Stats / Effects / ... | | [Put in spawner]    |
 * +-------------------------------------------------------------------------------------+
 * | summon zombie ~ ~ ~ {...}                                      [Copy /summon] [Done] |
 * +-------------------------------------------------------------------------------------+
 * </pre>
 *
 * Opened as a tab (on the session's mob) or nested from the spawner to edit one of its mobs,
 * in which case the footer offers Use/Cancel and the result goes to a callback.
 */
public final class MobEditorScreen extends StacksmithScreen {
    private static final int TOP = 24, FOOTER = 26;
    private static final long DEBOUNCE_MS = 150;
    /** Attributes offered as fields; anything else goes in "All data". */
    private static final String[][] ATTRIBUTES = {
            {"minecraft:max_health", "Max health"}, {"minecraft:movement_speed", "Speed"}, {"minecraft:attack_damage", "Attack damage"},
            {"minecraft:armor", "Armour"}, {"minecraft:knockback_resistance", "Knockback resist."}, {"minecraft:follow_range", "Follow range"},
            {"minecraft:scale", "Size (scale)"}, {"minecraft:attack_knockback", "Attack knockback"}, {"minecraft:armor_toughness", "Armour toughness"},
            {"minecraft:step_height", "Step height"}, {"minecraft:jump_strength", "Jump strength"}, {"minecraft:gravity", "Gravity"},
            {"minecraft:spawn_reinforcements", "Reinforcements"}};
    /** Hints for the attribute boxes (default and range). */
    private static final Map<String, String> ATTRIBUTE_HELP = Map.ofEntries(
            Map.entry("minecraft:scale", "Size: 1 is normal, 0.0625 to 16. Hitbox and reach scale with it."),
            Map.entry("minecraft:movement_speed", "Zombie 0.23, player walking 0.1"),
            Map.entry("minecraft:attack_damage", "Damage per hit, in half hearts"),
            Map.entry("minecraft:follow_range", "How far away it notices and chases you, in blocks"),
            Map.entry("minecraft:knockback_resistance", "0 to 1; 1 means no knockback"),
            Map.entry("minecraft:attack_knockback", "Extra knockback on hit (0 to 5)"),
            Map.entry("minecraft:step_height", "Blocks it walks up without jumping (0.6 normal)"),
            Map.entry("minecraft:gravity", "0.08 normal; lower floats, 0 hovers"),
            Map.entry("minecraft:jump_strength", "Jump height (0.42 normal)"),
            Map.entry("minecraft:spawn_reinforcements", "Zombies: chance (0 to 1) to call more zombies when hit"));
    private static double listScroll, midScroll;
    private static String search = "";
    private static final Map<String, ItemStack> EGGS = new HashMap<>();
    private static final EntitySchema SCHEMA = EntitySchema.bundled();
    /** Mobs that shoot the arrow held in their off hand. */
    private static final List<String> ARCHERS = List.of("minecraft:skeleton", "minecraft:stray", "minecraft:bogged");

    private final EntitySpec mob;
    private final Consumer<EntitySpec> onDone;
    private final ScrollPane list = pane(new ScrollPane());
    private final ScrollPane mid = pane(new ScrollPane());
    private List<String> entityIds;

    private Entity preview;
    private String command = "";
    private final List<String> problems = new ArrayList<>();
    private boolean dirty = true;
    private long changedAt;
    private EditBox commandBox;
    private Button getEgg, summonHere, getCommandBlock, toSpawner;
    private CodeArea allData;
    private int dataVersion, shownDataVersion;
    private final Map<EntitySpec.Slot, ItemStack> slotIcons = new EnumMap<>(EntitySpec.Slot.class);

    /** The Mob tab, on the session's mob. */
    public MobEditorScreen(Screen parent) {
        this(parent, Session.mob, null);
    }

    /** A nested editor: edits a copy, and {@code onDone} gets the result if the player keeps it. */
    public MobEditorScreen(Screen parent, EntitySpec mob, Consumer<EntitySpec> onDone) {
        super(Component.translatable("stacksmith.screen.mob"), parent);
        this.mob = onDone == null ? mob : mob.copy();
        this.onDone = onDone;
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
        if (entityIds == null) {
            entityIds = McEntities.summonableIds();
        }
        header(onDone == null ? Tab.MOB : null, "Paste a /summon, or a spawn egg's /give",
                () -> minecraft.setScreenAndShow(new ImportScreen(this, null)),
                () -> minecraft.setScreenAndShow(new PresetsScreen(this, Presets.Kind.MOB, mob.toTag(), data -> {
                    EntitySpec loaded = EntitySpec.fromTag(data);
                    if (onDone == null) {
                        Session.mob = loaded;
                        minecraft.setScreenAndShow(new MobEditorScreen(parent));
                    } else {
                        minecraft.setScreenAndShow(new MobEditorScreen(parent, loaded, onDone));
                    }
                })));
        buildList();
        buildMiddle();
        buildRight();
        buildFooter();
        changed();
    }

    static ItemStack egg(String entityId) {
        return EGGS.computeIfAbsent(entityId, id -> {
            String egg = McEntities.spawnEggId(id);
            if (egg == null || Delivery.registries() == null) {
                return ItemStack.EMPTY;
            }
            McItems.Result r = McItems.toStack(new ItemSpec(egg), Delivery.registries());
            return r.ok() ? r.stack() : ItemStack.EMPTY;
        });
    }

    /** Every mob type, grouped, with its spawn egg; the search box filters. */
    private void buildList() {
        int x = PAD, w = leftW();
        EditBox searchBox = addRenderableWidget(new EditBox(font, x, TOP + 2, w, 14, Component.literal("Search mobs")));
        searchBox.setHint(Component.literal("search mobs…"));
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            if (!s.equals(search)) {
                search = s;
                listScroll = 0;
                rebuild();
                setFocused(searchBox);
                searchBox.setFocused(true);
            }
        });
        list.clear();
        list.bounds(x, TOP + 20, w, bodyBottom() - TOP - 20);
        String q = search.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        Map<String, List<String>> groups = new HashMap<>();
        for (String id : entityIds) {
            if (q.isEmpty() || id.contains(q) || Ids.pretty(id).toLowerCase(Locale.ROOT).contains(search.strip().toLowerCase(Locale.ROOT))) {
                groups.computeIfAbsent(McEntities.group(id), g -> new ArrayList<>()).add(id);
            }
        }
        for (String group : McEntities.GROUPS) {
            List<String> ids = groups.get(group);
            if (ids == null) {
                continue;
            }
            list.add(new ItemEditorScreen.HeaderRow(group));
            for (String id : ids) {
                Chip c = Chip.of(Ids.pretty(id), 100, 18, () -> {
                    if (!id.equals(mob.id())) {
                        // Drop the old type's own fields that the new type doesn't have.
                        for (EntitySchema.Field f : SCHEMA.fieldsFor(mob.id())) {
                            if (!f.appliesTo(id) && SCHEMA.fieldsFor(id).stream().noneMatch(g -> g.path().equals(f.path()))) {
                                f.set(mob, null);
                            }
                        }
                        mob.id(id);
                        changed();
                        rebuild();
                    }
                }).icon(egg(id)).selected(() -> mob.id().equals(id));
                list.add(new ItemEditorScreen.WidgetRow(c, 19));
            }
        }
        if (groups.isEmpty()) {
            list.add(new ItemEditorScreen.HeaderRow("No mob matches"));
        }
        addPaneWidgets(list);
        list.layout();
        list.scroll(listScroll);
    }

    private void buildMiddle() {
        mid.clear();
        mid.bounds(midX(), TOP + 14, midW(), bodyBottom() - TOP - 14);
        mid.add(new NameCard(mob));
        if (!SCHEMA.fieldsFor(mob.id()).isEmpty()) {
            mid.add(new TypeCard(mob));
        }
        mid.add(new EquipmentCard(mob));
        mid.add(new FlagsCard(mob));
        mid.add(new StatsCard(mob));
        mid.add(new EffectsCard(mob));
        mid.add(new DataCard(mob));
        addPaneWidgets(mid);
        mid.layout();
        mid.scroll(midScroll);
    }

    private void buildRight() {
        int x = rightX(), w = rightW();
        int y = bodyBottom() - (onDone == null ? 4 : 0) * 22 - 22 + 2;
        if (onDone == null) {
            getEgg = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_egg"), b -> giveEgg())
                    .bounds(x, y, w, 20).build());
            summonHere = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.summon"), b ->
                    toast(Delivery.run(Commands.summon(mob, "~ ~ ~", style())), true)).bounds(x, y + 22, w, 20).build());
            getCommandBlock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_command_block"), b -> giveCommandBlock())
                    .bounds(x, y + 44, w, 20).build());
            y += 22;
            toSpawner = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.to_spawner"), b -> {
                SpawnerSpec fresh = new SpawnerSpec();
                fresh.entries().add(new SpawnerSpec.Entry(mob.copy(), 1).classicLight());
                WorldSpawner.link(fresh, null);
                minecraft.setScreenAndShow(new SpawnerEditorScreen(parent));
            }).bounds(x, y + 44, w, 20).tooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.to_spawner"))).build());
            y += 66;
        }
        addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_summon"), b ->
                        toast(Delivery.copy("/" + command), true))
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.copy_summon"))).build());
    }

    private void buildFooter() {
        int y = height - FOOTER + 3;
        int right = onDone == null ? 64 : 128;
        commandBox = addRenderableWidget(new EditBox(font, PAD, y + 2, width - PAD * 2 - right - 50, 16, Component.literal("Command")));
        commandBox.setMaxLength(Commands.COMMAND_BLOCK_LIMIT);
        commandBox.setEditable(false);
        if (onDone == null) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - PAD - 60, y, 60, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width - PAD - 124, y, 60, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.use_mob"), b -> {
                onDone.accept(mob.copy());
                onClose();
            }).bounds(width - PAD - 62, y, 62, 20).build());
        }
    }

    private Commands.Style style() {
        return new Commands.Style(Session.briefCommands, Session.target);
    }

    /** A command block that summons this mob on top of itself once, when placed: no chat limit. */
    private void giveCommandBlock() {
        String summon = Commands.summon(mob, "~ ~1 ~", style());
        Nbt name = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
        String label = name != null && TextMarkup.fromComponent(name) != null ? TextMarkup.plain(TextMarkup.fromComponent(name)) : Ids.pretty(mob.id());
        ItemSpec cb = Commands.commandBlockItem(summon, TextMarkup.toComponent("&d" + label + " (place to summon)", true));
        McItems.Result r = McItems.toStack(cb, Delivery.registries());
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok() && Delivery.isCreative());
    }

    private void giveEgg() {
        String egg = McEntities.spawnEggId(mob.id());
        if (egg == null) {
            return;
        }
        ItemSpec item = new ItemSpec(egg);
        item.set("entity_data", mob.toTag());
        Nbt name = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
        if (name != null) {
            item.set("custom_name", name.copy());
        }
        McItems.Result r = McItems.toStack(item, Delivery.registries());
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok() && Delivery.isCreative());
    }

    void changed() {
        dirty = true;
        dataVersion++;
        changedAt = System.currentTimeMillis();
    }

    void rebuild() {
        listScroll = list.scroll();
        midScroll = mid.scroll();
        rebuildWidgets();
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
            allData.setText(Outline.write(mob.data()).stripTrailing(), true);
            shownDataVersion = dataVersion;
        }
    }

    private void recompute() {
        command = Commands.summon(mob, "~ ~ ~", style());
        commandBox.setValue(command);
        commandBox.moveCursorToStart(false);
        if (preview != null) {
            preview.discard();
        }
        preview = minecraft.level != null ? McEntities.create(mob, minecraft.level) : null;
        problems.clear();
        if (minecraft.level != null && preview == null) {
            problems.add("The game can't load this mob's data: check All data.");
        }
        slotIcons.clear();
        for (EntitySpec.Slot s : EntitySpec.Slot.values()) {
            ItemSpec item = mob.equipment(s);
            if (item != null && Delivery.registries() != null) {
                McItems.Result r = McItems.toStack(item, Delivery.registries());
                slotIcons.put(s, r.ok() ? r.stack() : ItemStack.EMPTY);
            }
        }
        if (onDone == null) {
            Session.mob = mob;
            boolean creative = Delivery.isCreative();
            boolean hasEgg = McEntities.spawnEggId(mob.id()) != null;
            getEgg.active = creative && hasEgg && preview != null;
            getEgg.setTooltip(Tooltip.create(Component.translatable(!hasEgg ? "stacksmith.tooltip.get_egg_none"
                    : creative ? "stacksmith.tooltip.get_egg" : "stacksmith.tooltip.get_creative")));
            boolean fits = command.length() + 1 <= Commands.CHAT_LIMIT, can = Delivery.canUse("summon");
            summonHere.active = fits && can && preview != null;
            getCommandBlock.active = creative && command.length() <= Commands.COMMAND_BLOCK_LIMIT;
            getCommandBlock.setTooltip(Tooltip.create(Component.translatable(creative ? "stacksmith.tooltip.get_command_block_mob" : "stacksmith.tooltip.get_creative")));
            summonHere.setTooltip(Tooltip.create(Component.translatable(!can ? "stacksmith.tooltip.summon_no_permission"
                    : !fits ? "stacksmith.tooltip.summon_too_long" : "stacksmith.tooltip.summon")));
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, title, width / 2, 8, Theme.TEXT);
        int top = TOP - 2, bottom = bodyBottom() + 2;
        panel(g, PAD - 2, top, leftW() + 4, bottom - top);
        panel(g, midX() - 2, top, midW() + 4, bottom - top);
        panel(g, rightX() - 2, top, rightW() + 4, bottom - top);
        label(g, Ids.pretty(mob.id()), midX() + 1, TOP + 2);
        int rx = rightX(), rw = rightW();
        label(g, "Preview", rx + 1, TOP + 2);
        int py = TOP + 14;
        int buttons = onDone == null ? 5 : 1;
        int ph = bodyBottom() - buttons * 22 - py - 16;
        MobPreview.draw(g, font, preview, rx, py, rw, ph, mouseX, mouseY);
        int y = py + ph + 4;
        if (problems.isEmpty()) {
            g.text(font, "✔ Ready", rx + 2, y, Theme.GOOD, false);
        }
        for (String p : problems) {
            for (FormattedCharSequence l : font.split(Component.literal(p), rw - 4)) {
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
        listScroll = list.scroll();
        midScroll = mid.scroll();
        if (preview != null) {
            preview.discard();
            preview = null;
        }
        super.removed();
    }

    // ---- cards ------------------------------------------------------------------------------------

    /** The name, styled like item names, and whether it always shows. */
    private final class NameCard extends Card {
        private final RichTextArea area;
        private final FormatBar bar;
        private final Chip visible;

        NameCard(EntitySpec mob) {
            super("Name", 0xFFFFC14D, "Shown over the mob's head (when looked at, or always), and in death messages");
            Nbt n = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
            String markup = n == null ? "" : TextMarkup.fromComponent(n);
            area = body(new RichTextArea(markup == null ? "" : markup, true)
                    .placeholder("No name: type one, e.g. &c&lThe Butcher")
                    .onChange(lines -> {
                        String s = lines.getFirst();
                        mob.data().putOrRemove(EntitySpec.Keys.CUSTOM_NAME, s.isEmpty() ? null : TextMarkup.toComponent(s, false));
                        changed();
                    }));
            area.active = markup != null;
            if (markup == null) {
                status = "edit in All data";
                statusColor = Theme.WARN;
            }
            bar = new FormatBar(() -> area);
            bar.widgets().forEach(this::body);
            visible = body(Chip.of("Always show name", 100, 14, () -> {
                mob.flag(EntitySpec.Flag.NAME_VISIBLE, !mob.flag(EntitySpec.Flag.NAME_VISIBLE));
                changed();
            }).selected(() -> mob.flag(EntitySpec.Flag.NAME_VISIBLE)).tip("Show the name above the mob all the time, not just when looked at"));
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y + bar.layout(x, y, w) + 3;
            area.setX(x);
            area.setY(cy);
            area.setWidth(w);
            cy += area.getHeight() + 3;
            visible.setX(x);
            visible.setY(cy);
            return cy + 14 - y;
        }
    }

    /** Seven slots: icon (click to choose), edit, clear, drop chance. */
    private final class EquipmentCard extends Card {
        private final List<EntitySpec.Slot> slots = List.of(EntitySpec.Slot.HEAD, EntitySpec.Slot.CHEST, EntitySpec.Slot.LEGS,
                EntitySpec.Slot.FEET, EntitySpec.Slot.MAINHAND, EntitySpec.Slot.OFFHAND, EntitySpec.Slot.BODY);
        private final List<Chip> pick = new ArrayList<>(), edit = new ArrayList<>(), clear = new ArrayList<>();
        private final List<EditBox> chance = new ArrayList<>();

        EquipmentCard(EntitySpec mob) {
            super("Equipment", 0xFF7FDBCA, "What the mob wears and holds, and the chance each drops on death");
            for (EntitySpec.Slot s : slots) {
                ItemSpec item = mob.equipment(s);
                ItemStack icon = ItemStack.EMPTY;
                if (item != null && Delivery.registries() != null) {
                    McItems.Result r = McItems.toStack(item, Delivery.registries());
                    icon = r.ok() ? r.stack() : ItemStack.EMPTY;
                }
                // Skeletons shoot the tipped arrow in their off hand, with its effects.
                boolean arrows = s == EntitySpec.Slot.OFFHAND && ARCHERS.contains(mob.id());
                // A mob with custom data doesn't get its natural gear, so an archer needs its bow given.
                boolean bow = s == EntitySpec.Slot.MAINHAND && ARCHERS.contains(mob.id());
                String label = slotName(s) + ": " + (item == null ? (arrows ? "empty (✎ for a tipped arrow)" : bow ? "empty (needs a bow to shoot)" : "empty")
                        : icon.isEmpty() ? Ids.pretty(item.id()) : icon.getHoverName().getString());
                Chip pickChip = Chip.of(label, 80, 18, () -> minecraft.setScreenAndShow(new ItemPickerScreen(MobEditorScreen.this, picked -> {
                    mob.equipment(s, picked);
                    changed();
                }))).icon(icon).tip(item == null ? "Click to pick an item (or one from your inventory)" : "Click to pick a different item");
                if (item == null) {
                    pickChip.color(Theme.TEXT_DIM);
                }
                pick.add(body(pickChip));
                Chip e = body(Chip.of("✎", 16, 18, () -> {
                    ItemSpec current = mob.equipment(s);
                    ItemSpec start = current != null ? current : arrows
                            ? new ItemSpec("tipped_arrow").set("potion_contents", new Nbt.Compound().put("potion", "minecraft:poison"))
                            : bow ? new ItemSpec("bow") : new ItemSpec("stone");
                    minecraft.setScreenAndShow(new ItemEditorScreen(MobEditorScreen.this, start, result -> {
                        mob.equipment(s, result);
                        changed();
                    }));
                }).tip(item == null && arrows ? "Give it a tipped arrow: it shoots arrows with these effects"
                        : item == null && bow ? "Give it a bow. Mobs with any custom data don't get their usual gear, so without one it can't shoot."
                        : "Edit this item in full (enchantments, name, anything)"));
                edit.add(e);
                Chip c = body(Chip.of("✕", 14, 18, () -> {
                    mob.equipment(s, null);
                    changed();
                    rebuild();
                }).color(Theme.BAD).tip("Empty this slot"));
                c.active = item != null;
                clear.add(c);
                EditBox box = body(new EditBox(font, 0, 0, 28, 18, Component.literal("Drop chance")));
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
            int pw = w - 16 - 14 - 28 - 6;
            for (int i = 0; i < slots.size(); i++) {
                int ry = y + 10 + i * 20;
                pick.get(i).setX(x);
                pick.get(i).setY(ry);
                pick.get(i).setWidth(pw);
                edit.get(i).setX(x + pw + 2);
                edit.get(i).setY(ry);
                clear.get(i).setX(x + pw + 20);
                clear.get(i).setY(ry);
                chance.get(i).setX(x + w - 28);
                chance.get(i).setY(ry);
            }
            return 10 + slots.size() * 20 - 2;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            g.text(font, "Drop %", x + w - font.width("Drop %"), y, Theme.TEXT_FAINT, false);
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
    /** Fields only this kind of mob has (entities.outline): switches, then a row per value. */
    private final class TypeCard extends Card {
        private final List<Chip> switches = new ArrayList<>();
        private final List<EntitySchema.Field> rowFields = new ArrayList<>();
        private final List<List<AbstractWidget>> rows = new ArrayList<>();

        TypeCard(EntitySpec mob) {
            super(Ids.pretty(mob.id()) + " options", 0xFFFFC857, "Settings only this kind of mob has");
            for (EntitySchema.Field f : SCHEMA.fieldsFor(mob.id())) {
                if (f.editor() == EntitySchema.Editor.BOOL) {
                    Chip c = body(Chip.of(f.label(), 90, 14, () -> {
                        f.set(mob, f.isOn(mob) ? null : f.on());
                        changed();
                    }).selected(() -> f.isOn(mob)));
                    if (!f.about().isEmpty()) {
                        c.tip(f.about());
                    }
                    switches.add(c);
                    continue;
                }
                rowFields.add(f);
                rows.add(switch (f.editor()) {
                    case NUMBER -> List.of(numberBox(mob, f));
                    case CHOICE -> List.of(choiceChip(mob, f));
                    case DYE -> dyeSwatches(mob, f);
                    case ID -> List.of(idBox(mob, f));
                    case BOOL -> List.of();
                });
            }
        }

        private EditBox numberBox(EntitySpec mob, EntitySchema.Field f) {
            EditBox box = body(new EditBox(font, 0, 0, 50, 14, Component.literal(f.label())));
            Nbt v = f.get(mob);
            box.setValue(v instanceof Nbt.Num n ? (f.integral() ? Long.toString(n.asLong()) : Double.toString(n.asDouble())) : "");
            box.setHint(Component.literal("default"));
            String range = (f.min() > Double.NEGATIVE_INFINITY ? (long) f.min() + " to " + (long) f.max() + ". " : "");
            box.setTooltip(Tooltip.create(Component.literal(range + f.about())));
            box.setResponder(t -> {
                if (t.isBlank()) {
                    box.setTextColor(Theme.TEXT);
                    f.set(mob, null);
                    changed();
                    return;
                }
                try {
                    double d = Double.parseDouble(t.strip());
                    boolean ok = d >= f.min() && d <= f.max() && (!f.integral() || d == Math.rint(d));
                    box.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                    if (ok) {
                        f.set(mob, f.number(d));
                        changed();
                    }
                } catch (NumberFormatException e) {
                    box.setTextColor(Theme.BAD);
                }
            });
            return box;
        }

        /** Click for the next choice, shift-click for the previous; past the last is "default". */
        private Chip choiceChip(EntitySpec mob, EntitySchema.Field f) {
            List<String> labels = new ArrayList<>(f.values().keySet());
            Chip[] chip = new Chip[1];
            chip[0] = body(new Chip(Component.literal(choiceText(mob, f)), 90, 14, in -> {
                String now = f.choiceLabel(mob);
                int i = now == null ? labels.size() : labels.indexOf(now);
                int n = labels.size() + 1;
                int next = ((i + (in.hasShiftDown() ? -1 : 1)) % n + n) % n;
                f.set(mob, next == labels.size() ? null : f.values().get(labels.get(next)));
                chip[0].setMessage(Component.literal(choiceText(mob, f)));
                changed();
            }));
            chip[0].tip((f.about().isEmpty() ? "" : f.about() + "\n") + "Click for the next, shift-click for the previous: "
                    + labels.stream().map(Ids::pretty).collect(java.util.stream.Collectors.joining(", ")));
            return chip[0];
        }

        private String choiceText(EntitySpec mob, EntitySchema.Field f) {
            String l = f.choiceLabel(mob);
            return l == null ? "default" : Ids.pretty(l);
        }

        /** The 16 dye colours; clicking the chosen one again goes back to the default. */
        private List<AbstractWidget> dyeSwatches(EntitySpec mob, EntitySchema.Field f) {
            List<AbstractWidget> out = new ArrayList<>();
            for (int i = 0; i < EntitySchema.DYES.size(); i++) {
                String name = EntitySchema.DYES.get(i);
                Nbt value = f.values().get(name);
                int rgb = net.minecraft.world.item.DyeColor.byId(i).getTextureDiffuseColor() & 0xFFFFFF;
                out.add(body(new Swatch(rgb, Ids.pretty(name), 9, () -> value.equals(f.get(mob)), () -> {
                    f.set(mob, value.equals(f.get(mob)) ? null : value);
                    changed();
                })));
            }
            return out;
        }

        private SuggestBox idBox(EntitySpec mob, EntitySchema.Field f) {
            List<String> ids = McRegistries.ids(f.registry(), Delivery.registries());
            SuggestBox box = body(new SuggestBox(font, 100, 14, Component.literal(f.label()), () -> ids));
            box.setValueQuietly(f.get(mob) instanceof Nbt.Str s ? Ids.brief(s.value()) : "");
            box.setHint(Component.literal("default"));
            box.setTooltip(Tooltip.create(Component.literal((f.about().isEmpty() ? "" : f.about() + " ") + "Click for the list, or type to search.")));
            box.onAccept(t -> {
                String id = Ids.full(t.strip());
                boolean known = ids.contains(id);
                box.setTextColor(t.isBlank() || known ? Theme.TEXT : Theme.BAD);
                if (t.isBlank() || known) {
                    f.set(mob, t.isBlank() ? null : Nbt.str(id));
                    changed();
                }
            });
            return box;
        }

        private int labelW() {
            int m = 0;
            for (EntitySchema.Field f : rowFields) {
                m = Math.max(m, font.width(f.label()));
            }
            return m + 8;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cols = w >= 200 ? 2 : 1, cw = (w - (cols - 1) * 2) / cols;
            for (int i = 0; i < switches.size(); i++) {
                switches.get(i).setWidth(cw);
                switches.get(i).setX(x + i % cols * (cw + 2));
                switches.get(i).setY(y + i / cols * 16);
            }
            int ry = y + (switches.size() + cols - 1) / cols * 16;
            int cx = x + labelW(), room = x + w - cx;
            for (List<AbstractWidget> row : rows) {
                if (row.size() == 1) {
                    AbstractWidget wd = row.getFirst();
                    if (!(wd instanceof EditBox) || wd instanceof SuggestBox) {
                        wd.setWidth(Math.min(room, 140));
                    }
                    wd.setX(cx);
                    wd.setY(ry);
                    ry += 16;
                } else {
                    // Swatches: as many per line as fit.
                    int per = Math.max(1, room / 10), i = 0;
                    for (AbstractWidget sw : row) {
                        sw.setX(cx + i % per * 10);
                        sw.setY(ry + 2 + i / per * 10);
                        i++;
                    }
                    ry += (row.size() + per - 1) / per * 10 + 6;
                }
            }
            return ry - y - 2;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            for (int i = 0; i < rowFields.size(); i++) {
                AbstractWidget first = rows.get(i).getFirst();
                g.text(font, rowFields.get(i).label(), x, first.getY() + (rows.get(i).size() > 1 ? 1 : 3), Theme.TEXT_DIM, false);
            }
        }
    }

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
                boxes.add(body(numberBox(Double.isNaN(base) ? "" : trim(base), ATTRIBUTE_HELP.getOrDefault(a[0], a[0]), v -> {
                    mob.attribute(a[0], v == null ? Double.NaN : v);
                    changed();
                })));
            }
            // Melee reach comes from the main-hand weapon's attack_range component.
            labels.add("Reach");
            ItemSpec weapon = mob.equipment(EntitySpec.Slot.MAINHAND);
            double reach = weapon != null && weapon.get("attack_range") instanceof Nbt.Compound r ? r.getDouble("max_reach", Double.NaN) : Double.NaN;
            EditBox reachBox = body(numberBox(Double.isNaN(reach) ? "" : trim(reach),
                    weapon == null ? "Give it a main-hand weapon first: reach is set on the weapon (attack_range)"
                            : "Melee reach in blocks (about 2 to 3 normally). Sets attack_range on its main-hand weapon.", v -> {
                ItemSpec w = mob.equipment(EntitySpec.Slot.MAINHAND);
                if (w == null) {
                    return;
                }
                if (v == null) {
                    w.unset("attack_range");
                } else {
                    w.set("attack_range", new Nbt.Compound().put("max_reach", Nbt.f(v)).put("mob_factor", Nbt.f(1)));
                }
                mob.equipment(EntitySpec.Slot.MAINHAND, w);
                changed();
            }));
            reachBox.active = weapon != null;
            reachBox.setEditable(weapon != null);
            boxes.add(reachBox);
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

    /** Status effects: effect, level, duration (blank = forever), particles. */
    private final class EffectsCard extends Card {
        private final List<SuggestBox> ids = new ArrayList<>();
        private final List<EditBox> levels = new ArrayList<>(), durations = new ArrayList<>();
        private final List<Chip> particles = new ArrayList<>(), removers = new ArrayList<>();
        private final Chip add;

        EffectsCard(EntitySpec mob) {
            super("Effects", 0xFFC79BF2, "Status effects the mob spawns with. Leave the duration empty for forever.");
            List<String> all = McRegistries.ids("minecraft:mob_effect", Delivery.registries());
            List<EntitySpec.Effect> effects = new ArrayList<>(mob.effects());
            for (int i = 0; i < effects.size(); i++) {
                int index = i;
                EntitySpec.Effect e = effects.get(i);
                SuggestBox id = body(new SuggestBox(font, 100, 16, Component.literal("Effect"), () -> all));
                id.setValueQuietly(Ids.brief(e.id()));
                id.onAccept(s -> {
                    String full = Ids.full(s);
                    boolean ok = all.isEmpty() || all.contains(full);
                    id.setTextColor(ok ? Theme.TEXT : Theme.WARN);
                    if (ok) {
                        update(mob, effects, index, new EntitySpec.Effect(full, effects.get(index).amplifier(), effects.get(index).duration(),
                                effects.get(index).ambient(), effects.get(index).particles(), effects.get(index).icon()));
                    }
                });
                ids.add(id);
                EditBox level = body(new EditBox(font, 0, 0, 26, 16, Component.literal("Level")));
                level.setValue(Integer.toString(e.amplifier() + 1));
                level.setTooltip(Tooltip.create(Component.literal("Level (1 is the normal effect)")));
                level.setResponder(t -> {
                    try {
                        int v = Integer.parseInt(t.strip());
                        boolean ok = v >= 1 && v <= 128;
                        level.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                        if (ok) {
                            EntitySpec.Effect c = effects.get(index);
                            update(mob, effects, index, new EntitySpec.Effect(c.id(), v - 1, c.duration(), c.ambient(), c.particles(), c.icon()));
                        }
                    } catch (NumberFormatException ex) {
                        level.setTextColor(Theme.BAD);
                    }
                });
                levels.add(level);
                EditBox duration = body(new EditBox(font, 0, 0, 34, 16, Component.literal("Seconds")));
                duration.setValue(e.duration() < 0 ? "" : NumberText.trim(e.duration() / 20.0));
                duration.setHint(Component.literal("∞"));
                duration.setTooltip(Tooltip.create(Component.literal("Duration in seconds; empty for forever")));
                duration.setResponder(t -> {
                    try {
                        int ticks = t.isBlank() ? -1 : (int) Math.round(Double.parseDouble(t.strip()) * 20);
                        duration.setTextColor(Theme.TEXT);
                        EntitySpec.Effect c = effects.get(index);
                        update(mob, effects, index, new EntitySpec.Effect(c.id(), c.amplifier(), ticks, c.ambient(), c.particles(), c.icon()));
                    } catch (NumberFormatException ex) {
                        duration.setTextColor(Theme.BAD);
                    }
                });
                durations.add(duration);
                particles.add(body(Chip.of("✦", 16, 16, () -> {
                    EntitySpec.Effect c = effects.get(index);
                    update(mob, effects, index, new EntitySpec.Effect(c.id(), c.amplifier(), c.duration(), c.ambient(), !c.particles(), c.icon()));
                }).selected(() -> effects.get(index).particles()).tip("Show particles")));
                removers.add(body(Chip.of("✕", 14, 16, () -> {
                    effects.remove(index);
                    mob.effects(effects);
                    changed();
                    rebuild();
                }).color(Theme.BAD)));
            }
            add = body(Chip.of("+ Add effect", 80, 14, () -> {
                effects.add(new EntitySpec.Effect("minecraft:speed", 0, -1, false, true, true));
                mob.effects(effects);
                changed();
                rebuild();
            }));
        }

        private void update(EntitySpec mob, List<EntitySpec.Effect> effects, int index, EntitySpec.Effect e) {
            effects.set(index, e);
            mob.effects(effects);
            changed();
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y;
            for (int i = 0; i < ids.size(); i++) {
                int idW = w - 26 - 34 - 16 - 14 - 8;
                ids.get(i).setX(x);
                ids.get(i).setY(cy);
                ids.get(i).setWidth(Math.max(40, idW));
                int rx = x + Math.max(40, idW) + 2;
                levels.get(i).setX(rx);
                levels.get(i).setY(cy);
                durations.get(i).setX(rx + 28);
                durations.get(i).setY(cy);
                particles.get(i).setX(rx + 64);
                particles.get(i).setY(cy);
                removers.get(i).setX(x + w - 14);
                removers.get(i).setY(cy);
                cy += 18;
            }
            add.setX(x);
            add.setY(cy);
            return cy - y + 14;
        }
    }

    /** Number formatting shared by the cards. */
    private static final class NumberText {
        static String trim(double d) {
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
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
