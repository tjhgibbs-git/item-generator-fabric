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
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
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
    private Button getSpawner, getCommandBlock, copySetblock;
    /** The main action: save to the linked spawner, or place one where you look. */
    private Chip placeHere;
    /** Back to the linked spawner as it was loaded. */
    private Chip reset;
    /** Stars (or unstars) what's being edited. */
    private Chip star;
    /** When starred: its nickname, edited in place of the Preview label (Enter or clicking away keeps it, Esc cancels). */
    private EditBox nickname;
    /** The name it is starred under (as last read), or null. */
    private String starName;
    /** Unstarring takes a second click before this time (it drops the nickname). */
    private long unstarArmedUntil;
    /** Left edge of the looked-at chip; the title is left out when it would run into it. */
    private int loadChipX;
    private final Map<EntitySpec.Slot, ItemStack> slotIcons = new EnumMap<>(EntitySpec.Slot.class);

    public SpawnerEditorScreen(Screen parent) {
        super(Component.translatable("stacksmith.screen.spawner"), parent);
    }

    /** The selected pool entry, or null while the spawner is empty. */
    private SpawnerSpec.Entry entry() {
        if (spawner.entries().isEmpty()) {
            return null;
        }
        Session.spawnerEntry = Math.max(0, Math.min(Session.spawnerEntry, spawner.entries().size() - 1));
        return spawner.entries().get(Session.spawnerEntry);
    }

    /** The selected mob, or null while the spawner is empty. */
    private EntitySpec mob() {
        SpawnerSpec.Entry e = entry();
        return e == null ? null : e.entity;
    }

    /** "Skeleton" (the first mob, which spawns first), or "Empty". */
    private String spawnerLabel() {
        return spawner.primary() == null ? "Empty" : Ids.pretty(spawner.primary().id());
    }

    private void addMob() {
        minecraft.setScreenAndShow(new MobEditorScreen(this, new EntitySpec("zombie"), result -> {
            spawner.entries().add(new SpawnerSpec.Entry(result, 1).classicLight());
            Session.spawnerEntry = spawner.entries().size() - 1;
            changed();
        }));
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

    private static final int SAVE_H = 24, SAVE_STATUS = 13;

    /** Top of the right column's buttons: the big save button, its status line, then three more. */
    private int buttonsTop() {
        return bodyBottom() - 3 * 22 - SAVE_H - SAVE_STATUS + 2;
    }

    @Override
    protected void init() {
        SuggestBox.closeAll();
        savedLabel = "Recent…";
        savedTip = "Spawners you've saved, placed or taken out, and the ones you starred. Type to search, Enter to load.";
        header(Tab.SPAWNER, "Look at a spawner, or paste a /setblock, /summon or spawner /give",
                () -> minecraft.setScreenAndShow(new ImportScreen(this, null)),
                () -> minecraft.setScreenAndShow(new SpawnerLibraryScreen(this, spawner, data -> {
                    // Stays tied to the block it was editing, so Overwrite puts the loaded one there.
                    SpawnerSpec loaded = SpawnerSpec.fromTag(data);
                    WorldSpawner.link(loaded, Session.spawnerPos);
                    SpawnerEditorScreen fresh = new SpawnerEditorScreen(parent);
                    minecraft.setScreenAndShow(fresh);
                    fresh.toast(Component.literal("Loaded " + SpawnerLog.title(loaded)
                            + (Session.spawnerPos != null ? ": Overwrite puts it in the spawner" : "")), true);
                })),
                "Start a blank spawner, not tied to any block",
                () -> {
                    WorldSpawner.link(new SpawnerSpec(), null);
                    minecraft.setScreenAndShow(new SpawnerEditorScreen(parent));
                });
        BlockPos looked = WorldSpawner.lookedAtSpawner();
        // The short label when the full one would run into the title.
        String loadLabel = I18n.get("stacksmith.button.load_looked_at");
        if (width - PAD - HEADER_RIGHT - 4 - font.width(loadLabel) - 16 < (width + font.width(title)) / 2 + 4) {
            loadLabel = I18n.get("stacksmith.button.load_looked_at_short");
        }
        int lw = font.width(loadLabel) + 16;
        Chip load = addRenderableWidget(Chip.of(loadLabel, lw, 14,
                () -> WorldSpawner.load(looked, () -> minecraft.setScreenAndShow(new SpawnerEditorScreen(parent))))
                .at(width - PAD - HEADER_RIGHT - 4 - lw, 5)
                .tip(I18n.get(looked != null ? "stacksmith.tooltip.load_looked_at" : "stacksmith.tooltip.load_looked_at_none")));
        load.active = looked != null && !looked.equals(Session.spawnerPos);
        loadChipX = load.getX();
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
            left.add(new PairRow(pick, remove));
        }
        left.add(new ItemEditorScreen.WidgetRow(Chip.of("+ Add mob", 80, 16, this::addMob).tip("Pick and set up another mob. With several mobs the spawner picks one each time, by weight."), 18));
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
        if (entry() == null) {
            mid.add(new EmptyCard());
        } else {
            mid.add(new SelectedMobCard());
            mid.add(new LightCard(entry()));
        }
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
        mid.add(new BatchCard());
        addPaneWidgets(mid);
        mid.layout();
        mid.scroll(midScroll);
    }

    private void buildRight() {
        int x = rightX(), w = rightW();
        int y = buttonsTop();
        String save = I18n.get(Session.spawnerPos != null ? "stacksmith.button.save_spawner" : "stacksmith.button.place");
        placeHere = addRenderableWidget(new Chip(Component.literal(save), w, SAVE_H, in -> placeHere())
                .primary(Session.spawnerPos != null ? 0xFF2F7D46 : 0xFF2E5E8C).at(x, y));
        y += SAVE_H + SAVE_STATUS;
        getSpawner = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_spawner"), b -> giveSpawner())
                .bounds(x, y, w, 20).build());
        getCommandBlock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get_command_block"), b -> giveCommandBlock())
                .bounds(x, y + 22, w, 20).build());
        copySetblock = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_setblock"), b -> {
            SpawnerLibrary.record(spawner, "Copied /setblock", null);
            toast(Delivery.copy("/" + command), true);
        }).bounds(x, y + 44, w, 20).build());
        // Reset sits at the top right of the middle panel, beside "Spawner at x y z".
        String resetLabel = I18n.get("stacksmith.button.reset_spawner");
        int rw = font.width(resetLabel) + 12;
        reset = addRenderableWidget(Chip.of(resetLabel, rw, 12, this::resetSpawner).at(midX() + midW() - rw, TOP));
        reset.setTooltip(Tooltip.create(Component.translatable(Session.spawnerOriginal != null
                ? "stacksmith.tooltip.reset_spawner" : "stacksmith.tooltip.reset_spawner_none")));
        reset.active = Session.spawnerOriginal != null && !Session.spawnerOriginal.toTag().equals(spawner.toTag());
        // In the preview's header, which has room at every width.
        star = addRenderableWidget(Chip.of("☆ Star", 64, 12, this::toggleStar).at(rightX() + rightW() - 64, TOP));
        nickname = addRenderableWidget(new EditBox(font, rightX() + 1, TOP + 2, rightW() - 64 - 6, 10, Component.literal("Nickname")));
        nickname.setBordered(false);
        nickname.setMaxLength(80);
        nickname.setTextColor(Theme.ACCENT);
        nickname.setTooltip(Tooltip.create(Component.literal("Its nickname: click to rename, Enter to keep")));
        starName = null;
        updateStar();
    }

    /** Stars the spawner being edited (then its nickname is ready to type), or, on a second click, unstars it. */
    private void toggleStar() {
        String name = SpawnerLibrary.starredName(spawner);
        if (name != null) {
            if (System.currentTimeMillis() >= unstarArmedUntil) {
                unstarArmedUntil = System.currentTimeMillis() + 3000;
                starChip("Unstar?", 48, Theme.BAD);
                return;
            }
            unstarArmedUntil = 0;
            SpawnerLibrary.unstar(name, spawner.toTag());
            toast(Component.literal("Unstarred \"" + name + "\" (still in Recent…)"), true);
            updateStar();
        } else {
            name = SpawnerLibrary.star(spawner, null);
            updateStar();
            if (name == null) {
                toast(Component.literal("Couldn't star it"), false);
                return;
            }
            toast(Component.literal("Starred: type a nickname, Enter to keep it"), true);
            focusSoon(nickname);
        }
    }

    private void updateStar() {
        String name = SpawnerLibrary.starredName(spawner);
        // Starred, the nickname beside it says so: the chip shrinks to ★ to give the nickname room.
        starChip(name != null ? "★" : "☆ Star", name != null ? 16 : 64, name != null ? Theme.ACCENT : Theme.TEXT);
        star.active = !spawner.entries().isEmpty();
        star.tip(name != null ? "In Recent… → ★ Starred. Click twice to unstar." : "Keep this spawner in Recent… → ★ Starred, with a nickname, to load it again any time");
        nickname.visible = name != null;
        if (!java.util.Objects.equals(name, starName) && !nickname.isFocused()) {
            nickname.setValue(name == null ? "" : name);
            nickname.moveCursorToStart(false);
        }
        starName = name;
    }

    /** Sets the star chip's label, width and colour, right-aligned in the preview header, with the nickname filling the rest. */
    private void starChip(String label, int w, int color) {
        star.setMessage(Component.literal(label));
        star.color(color);
        star.setWidth(w);
        star.setX(rightX() + rightW() - w);
        nickname.setWidth(star.getX() - rightX() - 6);
    }

    /** Keeps the typed nickname (renaming the starred file), or puts the old one back if it can't. */
    private void commitNickname() {
        if (starName == null || nickname.getValue().strip().equals(starName)) {
            nickname.setValue(starName == null ? "" : starName);
            return;
        }
        String to = SpawnerLibrary.rename(starName, nickname.getValue(), why -> toast(Component.literal(why), false));
        if (to != null) {
            toast(Component.literal("Nicknamed \"" + to + "\""), true);
            starName = to;
        }
        nickname.setValue(starName);
        nickname.moveCursorToStart(false);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        if (nickname != null && nickname.isFocused()) {
            int k = e.key();
            if (k == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || k == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
                commitNickname();
                setFocused(null);
                return true;
            }
            if (k == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
                nickname.setValue(starName == null ? "" : starName);
                setFocused(null);
                return true;
            }
        }
        return super.keyPressed(e);
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
        return TextMarkup.toComponent("&e" + spawnerLabel() + " spawner", true);
    }

    private void giveSpawner() {
        McItems.Result r = McItems.toStack(Commands.spawnerItem(spawner, spawnerName()), Delivery.registries());
        if (r.ok() && Delivery.isCreative()) {
            SpawnerLibrary.record(spawner, "Spawner item", null);
        }
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok());
    }

    private void giveCommandBlock() {
        String setblock = Commands.setSpawner(spawner, "~ ~ ~", style());
        ItemSpec cb = Commands.commandBlockItem(setblock, TextMarkup.toComponent("&d" + spawnerLabel() + " spawner (place to build)", true));
        McItems.Result r = McItems.toStack(cb, Delivery.registries());
        if (r.ok() && Delivery.isCreative()) {
            SpawnerLibrary.record(spawner, "Command block", null);
        }
        toast(r.ok() ? Delivery.giveCreative(r.stack()) : Component.literal(r.error()), r.ok());
    }

    /** Back to the linked spawner as it was loaded from the world (Save then writes that back). */
    private void resetSpawner() {
        if (Session.spawnerOriginal == null) {
            return;
        }
        SpawnerSpec original = Session.spawnerOriginal.copy();
        int entry = Session.spawnerEntry;
        WorldSpawner.link(original, Session.spawnerPos); // same block: keeps the original
        Session.spawnerEntry = entry;
        SpawnerEditorScreen fresh = new SpawnerEditorScreen(parent);
        minecraft.setScreenAndShow(fresh);
        fresh.toast(Component.translatable("stacksmith.msg.reset_spawner"), true);
    }

    /** Whether the editor differs from what the linked block holds (as loaded or last saved). */
    private boolean unsaved() {
        return Session.spawnerPos != null && (Session.spawnerSaved == null || !Session.spawnerSaved.toTag().equals(spawner.toTag()));
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
        if (!canPlace(steps)) {
            toast(Component.translatable("stacksmith.tooltip.place_no_permission"), false);
            return;
        }
        // Queued behind any spawners still being written: they share the storage the steps use.
        CommandQueue.add(steps, null);
        boolean saved = Session.spawnerPos != null;
        Session.spawnerPos = p.immutable();
        Session.spawnerSaved = spawner.copy();
        SpawnerLibrary.record(spawner, saved ? "Saved" : "Placed", p);
        // The button now saves to this block, and nearby copies are looked for as saved now.
        rebuild();
        toast(Component.translatable(saved ? "stacksmith.msg.saved_spawner" : "stacksmith.msg.placed_spawner", at(p)), true);
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
        if (!nickname.isFocused() && starName != null && !nickname.getValue().equals(starName)) {
            commitNickname(); // clicked away: keep what was typed
        }
        if (unstarArmedUntil != 0 && System.currentTimeMillis() >= unstarArmedUntil) {
            unstarArmedUntil = 0;
            updateStar();
        }
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
        preview = minecraft.level != null && mob() != null ? McEntities.create(mob(), minecraft.level) : null;
        slotIcons.clear();
        for (EntitySpec.Slot s : mob() == null ? new EntitySpec.Slot[0] : EntitySpec.Slot.values()) {
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
        reset.active = Session.spawnerOriginal != null && !Session.spawnerOriginal.toTag().equals(spawner.toTag());
        updateStar();
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
        headerTitle(g, loadChipX);
        int top = TOP - 2, bottom = bodyBottom() + 2;
        panel(g, PAD - 2, top, leftW() + 4, bottom - top);
        panel(g, midX() - 2, top, midW() + 4, bottom - top);
        panel(g, rightX() - 2, top, rightW() + 4, bottom - top);
        label(g, spawner.entries().size() > 1 ? "Spawns (picks one)" : "Spawns", PAD + 1, TOP + 2);
        label(g, font.plainSubstrByWidth(Session.spawnerPos != null ? "Spawner at " + at(Session.spawnerPos) : "New spawner",
                reset.getX() - midX() - 4), midX() + 1, TOP + 2);
        int rx = rightX(), rw = rightW();
        if (!nickname.visible) {
            label(g, "Preview", rx + 1, TOP + 2);
        } else if (nickname.isFocused() || nickname.isHovered()) {
            // Shows the nickname can be edited.
            g.fill(rx, TOP + 11, star.getX() - 4, TOP + 12, nickname.isFocused() ? Theme.ACCENT : Theme.BORDER);
        }
        int py = TOP + 14, ph = buttonsTop() - py - 26;
        MobPreview.draw(g, font, preview, rx, py, rw, ph, mouseX, mouseY);
        int y = py + ph + 4;
        if (problems.isEmpty()) {
            g.text(font, "✔ Ready", rx + 2, y, Theme.GOOD, false);
        }
        for (String p : problems) {
            for (FormattedCharSequence l : font.split(Component.literal(p), rw - 4)) {
                if (y > buttonsTop() - 12) {
                    break;
                }
                g.text(font, l, rx + 2, y, Theme.WARN, false);
                y += 10;
            }
        }
        int sy = placeHere.getY() + SAVE_H + 3;
        if (Session.spawnerPos == null) {
            g.text(font, font.plainSubstrByWidth("Places a new spawner where you look", rw - 4), rx + 2, sy, Theme.TEXT_DIM, false);
        } else if (unsaved()) {
            g.text(font, font.plainSubstrByWidth("● Unsaved changes", rw - 4), rx + 2, sy, Theme.WARN, false);
        } else {
            g.text(font, font.plainSubstrByWidth("✔ No unsaved changes", rw - 4), rx + 2, sy, Theme.GOOD, false);
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
        /** A time field: stored in ticks, shown in seconds or ticks. */
        boolean seconds() {
            return unit.equals("s");
        }

        String shown() {
            return seconds() ? Time.unit() : unit;
        }
    }

    /** A titled group of settings, each on its own line. */
    private final class SettingsCard extends Card {
        private final List<Field> fields;
        private final List<EditBox> boxes = new ArrayList<>();
        /** Box width: seconds like 23.65 need more room than block counts. */
        private final int boxW;
        /** Per field: a toggle that draws its range in the world, or null. */
        private final List<Chip> views = new ArrayList<>();

        SettingsCard(String title, int accent, String about, Field... fields) {
            super(title, accent, about);
            this.fields = List.of(fields);
            boxW = this.fields.stream().anyMatch(Field::seconds) ? 40 : 32;
            if (this.fields.stream().anyMatch(Field::seconds)) {
                headerButton(Time.buttonLabel(), 50, Time.buttonTip(), () -> {
                    Time.toggle();
                    rebuild();
                });
            }
            for (Field f : fields) {
                EditBox box = body(new EditBox(font, 0, 0, boxW, 14, Component.literal(f.label())));
                int v = spawner.get(f.setting());
                box.setValue(f.seconds() ? Time.format(v) : Integer.toString(v));
                box.setTooltip(Tooltip.create(Component.literal(f.help())));
                box.setResponder(t -> {
                    try {
                        int ticks = f.seconds() ? Time.parse(t) : Integer.parseInt(t.strip());
                        boolean ok = ticks >= f.setting().min && ticks <= f.setting().max;
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
                views.add(viewToggle(f.setting()));
            }
        }

        /** The show-in-world toggle for the two ranges: a sphere for the player range, a box for the spawn area. */
        private Chip viewToggle(SpawnerSpec.Setting s) {
            boolean player = s == SpawnerSpec.Setting.PLAYER_RANGE;
            if (!player && s != SpawnerSpec.Setting.SPAWN_RANGE) {
                return null;
            }
            Chip c = body(Chip.of(player ? "◯" : "▢", 14, 14, () -> {
                if (player) {
                    Session.showPlayerRange = !Session.showPlayerRange;
                } else {
                    Session.showSpawnRange = !Session.showSpawnRange;
                }
            }).selected(() -> player ? Session.showPlayerRange : Session.showSpawnRange));
            c.active = Session.spawnerPos != null;
            c.tip(Session.spawnerPos == null ? "Place or load a spawner to show its range in the world"
                    : player ? "Show this range as a sphere around the spawner, also after closing the editor. Click again to hide it."
                    : "Show the area mobs appear in (a box one block above and below the spawner), also after closing the editor. Click again to hide it.");
            return c;
        }

        private int viewW() {
            return views.stream().anyMatch(java.util.Objects::nonNull) ? 16 : 0;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            for (int i = 0; i < boxes.size(); i++) {
                EditBox b = boxes.get(i);
                b.setX(x + w - boxW - unitW());
                b.setY(y + i * 17);
                Chip v = views.get(i);
                if (v != null) {
                    v.setX(b.getX() - 16);
                    v.setY(b.getY());
                }
            }
            return boxes.size() * 17 - 3;
        }

        private int unitW() {
            int m = 0;
            for (Field f : fields) {
                m = Math.max(m, font.width(f.shown()));
            }
            return m == 0 ? 0 : m + 4;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                int ry = y + i * 17;
                int labelRoom = w - boxW - 4 - unitW() - viewW();
                g.text(font, font.plainSubstrByWidth(f.label(), labelRoom), x, ry + 3, Theme.TEXT, false);
                if (!f.shown().isEmpty()) {
                    g.text(font, f.shown(), x + w - unitW() + 4, ry + 3, Theme.TEXT_DIM, false);
                }
                boolean def = spawner.get(f.setting()) == f.setting().defaultValue;
                if (!def && mouseX >= x && mouseX < x + labelRoom && mouseY >= ry && mouseY < ry + 14) {
                    g.setTooltipForNextFrame(font, Component.literal(f.help()), mouseX, mouseY);
                }
            }
        }
    }

    /**
     * Applies this spawner to the copies of it nearby: finds spawners within a radius that match
     * what the linked block holds (as loaded, or as last saved: never the unsaved edits), then
     * overwrites them. So editing a pig spawner into a zombie one still finds the other pig
     * spawners, until the zombie is saved.
     */
    private final class BatchCard extends Card {
        private final SpawnerSpec reference = Session.spawnerSaved;
        private final boolean allowed = Delivery.canUse("data") && Delivery.canUse("setblock");
        private final EditBox radius;
        private final Chip find, identical, sameMobs, settings, apply;
        private final List<BlockPos> matches = SpawnerBatch.matches(reference);
        /** Overwrite settings too only applies to Same mob. (Not the chip's visible flag: the pane resets that when scrolling.) */
        private final boolean showSettings = SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS;

        BatchCard() {
            super("Apply to nearby copies", 0xFFB48CFF, "Placed several copies of this spawner? Find the ones nearby that match it "
                    + "as it is in the world (your unsaved edits aren't looked for), and give them all your changes. "
                    + "This spawner itself is written by Overwrite.");
            radius = body(new EditBox(font, 0, 0, 30, 14, Component.literal("Radius")));
            radius.setValue(Integer.toString(SpawnerBatch.radius));
            radius.setTooltip(Tooltip.create(Component.literal("How far from this spawner to look, up to " + SpawnerBatch.MAX_RADIUS
                    + " blocks. Only spawners in chunks your game has loaded are found.")));
            radius.setResponder(t -> {
                try {
                    int v = Integer.parseInt(t.strip());
                    boolean ok = v >= 1 && v <= SpawnerBatch.MAX_RADIUS;
                    radius.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                    if (ok && v != SpawnerBatch.radius) {
                        SpawnerBatch.radius = v;
                        SpawnerBatch.forget(); // found with the old radius
                    }
                } catch (NumberFormatException e) {
                    radius.setTextColor(Theme.BAD);
                }
            });
            find = body(Chip.of("Find", 40, 14, () -> SpawnerBatch.search(() -> {
                if (minecraft.gui.screen() == SpawnerEditorScreen.this) {
                    rebuild();
                }
            })).tip("Read the spawners within the radius and count the matching ones"));
            identical = body(Chip.of("Identical", 60, 14, () -> setMatch(SpawnerBatch.Match.IDENTICAL))
                    .selected(() -> SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL)
                    .tip("Only spawners exactly like this one as saved (mobs and every setting), which get everything"));
            sameMobs = body(Chip.of("Same mob", 60, 14, () -> setMatch(SpawnerBatch.Match.SAME_MOBS))
                    .selected(() -> SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS)
                    .tip("Spawners with the same mob(s) as this one as saved, whatever their timing and ranges"));
            settings = body(Chip.of((SpawnerBatch.overwriteSettings ? "☑" : "☐") + " Overwrite settings too", 100, 14, () -> {
                SpawnerBatch.overwriteSettings = !SpawnerBatch.overwriteSettings;
                rebuild();
            }).left().tip("Off: they get the new mob(s) and keep their own delays, counts and ranges (say, a trigger distance you "
                    + "tuned). On: they get this spawner's settings too."));
            int n = matches.size();
            apply = body(Chip.of(n == 0 ? "Apply to matches" : "Apply to " + n + (n == 1 ? " spawner" : " spawners"), 100, 16, () -> {
                String why = SpawnerBatch.apply(reference, spawner, style());
                if (why == null) {
                    SpawnerLibrary.record(spawner, "Applied to " + n + (n == 1 ? " copy" : " copies"), null);
                }
                toast(Component.literal(why == null ? "Overwriting " + n + (n == 1 ? " spawner" : " spawners") : why), why == null);
                rebuild();
            }).color(Theme.GOOD).tip("Overwrite the matching spawners with this one"
                    + (SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS && !SpawnerBatch.overwriteSettings ? "'s mobs (keeping their settings)" : "")));
            radius.setEditable(ready());
            identical.active = sameMobs.active = settings.active = ready();
            updateButtons();
            if (SpawnerBatch.searched()) {
                SpawnerBatch.highlight(matches);
            }
        }

        private boolean ready() {
            return reference != null && allowed;
        }

        /** Searching and writing run on after the card is built, so these follow along. */
        private void updateButtons() {
            boolean busy = SpawnerBatch.searching() || SpawnerBatch.writing();
            find.active = ready() && !busy;
            apply.active = ready() && !busy && SpawnerBatch.searched() && !matches.isEmpty() && spawner.problems().isEmpty();
        }

        private void setMatch(SpawnerBatch.Match m) {
            SpawnerBatch.match = m;
            rebuild();
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            radius.setX(x + font.width("Within") + 4);
            radius.setY(y);
            find.setX(x + w - find.getWidth());
            find.setY(y);
            int cw = (w - font.width("Match") - 6) / 2;
            identical.setX(x + font.width("Match") + 4);
            identical.setY(y + 17);
            identical.setWidth(cw);
            sameMobs.setWidth(cw);
            sameMobs.setX(identical.getX() + cw + 2);
            sameMobs.setY(y + 17);
            int ry = y + 34;
            settings.setX(x);
            settings.setWidth(w);
            if (showSettings) {
                settings.setY(ry);
                ry += 17;
            } else {
                settings.setY(-10_000); // off every pane, so it stays hidden
            }
            ry += 12; // status line
            apply.setX(x);
            apply.setY(ry);
            apply.setWidth(w);
            return ry + 16 - y;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            updateButtons();
            g.text(font, "Within", x, y + 3, Theme.TEXT, false);
            g.text(font, "blocks", radius.getX() + radius.getWidth() + 4, y + 3, Theme.TEXT_DIM, false);
            g.text(font, "Match", x, y + 20, Theme.TEXT, false);
            String line;
            int color = Theme.TEXT_DIM;
            if (reference == null) {
                line = "Load a spawner (look at it, press J) or place this one first.";
                color = Theme.WARN;
            } else if (!allowed) {
                line = "Needs operator (to read and set spawners).";
                color = Theme.WARN;
            } else if (!SpawnerBatch.status().isEmpty()) {
                line = SpawnerBatch.status();
            } else if (SpawnerBatch.searched()) {
                line = matches.size() + " of " + SpawnerBatch.foundCount() + " nearby match " + SpawnerLog.title(reference);
                color = matches.isEmpty() ? Theme.TEXT_DIM : Theme.GOOD;
            } else {
                line = "Finds " + SpawnerLog.title(reference) + (unsaved() ? ", as saved" : "");
            }
            g.text(font, font.plainSubstrByWidth(line, w), x, apply.getY() - 11, color, false);
            if (mouseX >= x && mouseX < x + w && mouseY >= apply.getY() - 12 && mouseY < apply.getY() - 1 && reference != null && unsaved()) {
                g.setTooltipForNextFrame(font, Component.literal("Looks for copies of the spawner as it is in the world. "
                        + "Overwrite first to look for what you're editing instead."), mouseX, mouseY);
            }
        }
    }

    /** Shown while the spawner has nothing in it. */
    private final class EmptyCard extends Card {
        private final Chip add;

        EmptyCard() {
            super("Nothing to spawn yet", 0xFFFF9EC7, "Pick the mob this spawner makes");
            add = body(Chip.of("+ Add mob", 80, 16, SpawnerEditorScreen.this::addMob).tip("Choose and set up the mob"));
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            add.setX(x);
            add.setY(y);
            return 16;
        }
    }

    /**
     * Light limits for the selected mob (custom_spawn_rules). Empty boxes keep the mob's usual
     * rule; setting either replaces it, e.g. block light 0 to 15 lets zombies spawn in daylight.
     */
    private final class LightCard extends Card {
        private final SpawnerSpec.Entry entry;
        private final EditBox[] boxes = new EditBox[4];
        private static final String[] LABELS = {"Block light", "Sky light"};

        LightCard(SpawnerSpec.Entry entry) {
            super("Light levels", 0xFFFFE066, "The light it spawns in, 0 (dark) to 15. When set, these replace the mob's own spawn rules. Sky light is checked as is, so it doesn't drop at night. Empty (—): the mob's own rules.");
            this.entry = entry;
            headerButton("Classic", 44, "The old spawner rule: light 7 or below (11 for blazes and silverfish). New mobs start with this.", () -> {
                entry.classicLight();
                changed();
                rebuild();
            });
            headerButton("Normal", 40, "Clear: the mob's own spawn rules (today's game: most hostile mobs need block light 0)", () -> {
                entry.blockLight = null;
                entry.skyLight = null;
                changed();
                rebuild();
            });
            headerButton("Any light", 52, "Spawn in any light, even daylight (0 to 15)", () -> {
                entry.blockLight = new int[]{0, 15};
                entry.skyLight = new int[]{0, 15};
                changed();
                rebuild();
            });
            for (int i = 0; i < 4; i++) {
                int row = i / 2, end = i % 2;
                int[] r = row == 0 ? entry.blockLight : entry.skyLight;
                EditBox b = body(new EditBox(font, 0, 0, 28, 14, Component.literal(LABELS[row] + (end == 0 ? " min" : " max"))));
                b.setValue(r == null ? "" : Integer.toString(r[end]));
                b.setHint(Component.literal("—"));
                b.setTooltip(Tooltip.create(Component.literal(LABELS[row] + (end == 0 ? ": lowest" : ": highest") + " level it spawns at (0-15)")));
                b.setResponder(t -> apply(row));
                boxes[i] = b;
            }
        }

        /** Reads both boxes of a row: both empty clears it; one empty means 0 or 15. */
        private void apply(int row) {
            EditBox lo = boxes[row * 2], hi = boxes[row * 2 + 1];
            Integer a = parse(lo, 0), b = parse(hi, 15);
            if (a == null || b == null) {
                return;
            }
            boolean empty = lo.getValue().isBlank() && hi.getValue().isBlank();
            int[] range = empty ? null : new int[]{a, b};
            boolean ok = empty || a <= b;
            lo.setTextColor(ok ? Theme.TEXT : Theme.BAD);
            hi.setTextColor(ok ? Theme.TEXT : Theme.BAD);
            if (!ok) {
                return;
            }
            if (row == 0) {
                entry.blockLight = range;
            } else {
                entry.skyLight = range;
            }
            changed();
        }

        /** The box's level, {@code empty} when blank, or null (and red) when not 0-15. */
        private static Integer parse(EditBox b, int empty) {
            String t = b.getValue().strip();
            if (t.isEmpty()) {
                return empty;
            }
            try {
                int v = Integer.parseInt(t);
                b.setTextColor(v >= 0 && v <= 15 ? Theme.TEXT : Theme.BAD);
                return v >= 0 && v <= 15 ? v : null;
            } catch (NumberFormatException e) {
                b.setTextColor(Theme.BAD);
                return null;
            }
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            for (int i = 0; i < 4; i++) {
                boxes[i].setX(x + w - (i % 2 == 0 ? 28 + 22 + 28 : 28));
                boxes[i].setY(y + i / 2 * 17);
            }
            return 2 * 17 - 3;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            for (int row = 0; row < 2; row++) {
                int ry = y + row * 17;
                g.text(font, LABELS[row], x, ry + 3, Theme.TEXT, false);
                g.text(font, "to", x + w - 28 - 17, ry + 3, Theme.TEXT_DIM, false);
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
                SpawnerSpec.Entry e = entry();
                // On the classic rule for the old type? Then follow it to the new type (7 or 11).
                int old = SpawnerSpec.Entry.classicMax(e.entity.id());
                boolean classic = java.util.Arrays.equals(e.blockLight, new int[]{0, old}) && java.util.Arrays.equals(e.skyLight, new int[]{0, old});
                e.entity = result;
                if (classic) {
                    e.classicLight();
                }
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
