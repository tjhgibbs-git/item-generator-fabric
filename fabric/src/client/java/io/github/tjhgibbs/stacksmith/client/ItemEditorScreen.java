package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.edit.Card;
import io.github.tjhgibbs.stacksmith.client.edit.ComponentCards;
import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.ItemView;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;
import io.github.tjhgibbs.stacksmith.core.schema.ComponentSchema;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The item editor.
 *
 * <pre>
 * +-[Item][Spawner]------------------ Stacksmith --------------------------[Import...]-+
 * | [icon] diamond_sword  | Components (3)        [Form][Outline] | Preview            |
 * | Count [1]  [Pick...]  | +- Custom Name ------------------ ↺ ✕ |   (big icon)       |
 * | Add component         | | &6Excalibur                        |  +--tooltip-----+  |
 * | [search...........]   | +- Enchantments ------------------- ✕ |  | Excalibur    |  |
 * |  Display              | | sharpness  [5]  ✕                  |  | Sharpness V  |  |
 * |   Item Name           | | + Add enchantment                  |  +--------------+  |
 * |   Lore ...            |                                       |  problems, if any  |
 * +-------------------------------------------------------------------------------------+
 * | give @s diamond_sword[custom_name=...]                                    212/256   |
 * | [Get item] [Copy /give] [Run]                                             [Done]    |
 * +-------------------------------------------------------------------------------------+
 * </pre>
 *
 * Opened on its own (key J) or nested, to edit an item inside something else (a mob's
 * equipment), in which case the footer offers Use/Cancel and the result goes to a callback.
 */
public final class ItemEditorScreen extends StacksmithScreen implements ComponentCards.Host {
    static final ComponentSchema SCHEMA = ComponentSchema.bundled();
    /** Always on screen at the top of the form, set or not: what almost every item needs. */
    static final List<String> PINNED = List.of("minecraft:custom_name", "minecraft:lore", "minecraft:enchantments");
    private static final int TOP = 24, FOOTER = 46;
    private static final long DEBOUNCE_MS = 120;

    private static double cardScroll, paletteScroll;
    private static String search = "";

    private final ItemSpec spec;
    private final Consumer<ItemSpec> onDone;
    private final ScrollPane cards = pane(new ScrollPane());
    private final ScrollPane palette = pane(new ScrollPane());

    private SuggestBox idBox;
    private EditBox searchBox, commandBox;
    private CodeArea outlineArea;
    private String outlineError = "";
    private Button getButton, runButton;

    private ItemStack stack = ItemStack.EMPTY;
    private String problem;
    private String command = "";
    private List<FormattedCharSequence> tooltip = List.of();
    private boolean dirty = true;
    private boolean scrollToEnd;
    private long changedAt;
    private List<String> itemIds;

    /** The top-level editor, on the session's item. */
    public ItemEditorScreen(Screen parent) {
        this(parent, Session.item, null);
    }

    /** A nested editor: edits a copy, and {@code onDone} gets the result if the player keeps it. */
    public ItemEditorScreen(Screen parent, ItemSpec spec, Consumer<ItemSpec> onDone) {
        super(Component.translatable("stacksmith.screen.item"), parent);
        this.spec = onDone == null ? spec : spec.copy();
        this.onDone = onDone;
    }

    // ---- layout ------------------------------------------------------------------------------

    private int leftW() {
        return Math.clamp(width * 24 / 100, 100, 170);
    }

    private int rightW() {
        return Math.clamp(width * 26 / 100, 115, 200);
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

    // ---- widgets -------------------------------------------------------------------------------

    @Override
    protected void init() {
        SuggestBox.closeAll();
        if (itemIds == null) {
            itemIds = new ArrayList<>();
            BuiltInRegistries.ITEM.keySet().forEach(k -> itemIds.add(k.toString()));
            itemIds.sort(null);
        }
        buildHeader();
        buildLeft();
        buildMiddle();
        buildFooter();
        changed();
    }

    private void buildHeader() {
        header(onDone == null ? Tab.ITEM : null, "Held item, inventory, or a pasted /give, /setblock or /summon",
                () -> minecraft.setScreenAndShow(new ImportScreen(this, this::importItem)),
                () -> minecraft.setScreenAndShow(new PresetsScreen(this, Presets.Kind.ITEM, spec.toTag(), data -> {
                    ItemSpec loaded = ItemSpec.fromTag(data);
                    if (onDone == null) {
                        Session.item = loaded;
                        minecraft.setScreenAndShow(new ItemEditorScreen(parent));
                    } else {
                        minecraft.setScreenAndShow(new ItemEditorScreen(parent, loaded, onDone));
                    }
                })));
    }

    private void buildLeft() {
        int x = PAD, w = leftW(), y = TOP + 2;
        idBox = addRenderableWidget(new SuggestBox(font, w - 38, 16, Component.literal("Item id"), () -> itemIds));
        idBox.setX(x + 36);
        idBox.setY(y);
        idBox.setValueQuietly(Ids.brief(spec.id()));
        idBox.setTooltip(Tooltip.create(Component.literal("Item id: type to search, or use Pick…")));
        idBox.onAccept(s -> {
            String id = Ids.full(s);
            boolean ok = McItems.isItem(id);
            idBox.setTextColor(ok ? Theme.TEXT : Theme.BAD);
            if (ok && !id.equals(spec.id())) {
                spec.id(id);
                changed();
            }
        });
        EditBox count = addRenderableWidget(new EditBox(font, x + 36, y + 18, 30, 14, Component.literal("Count")));
        count.setValue(Integer.toString(spec.count()));
        count.setTooltip(Tooltip.create(Component.literal("Stack size (1 to 99; more than the item's max needs max_stack_size)")));
        count.setResponder(s -> {
            try {
                int n = Integer.parseInt(s.strip());
                count.setTextColor(n >= 1 && n <= 99 ? Theme.TEXT : Theme.BAD);
                if (n >= 1 && n <= 99) {
                    spec.count(n);
                    changed();
                }
            } catch (NumberFormatException e) {
                count.setTextColor(Theme.BAD);
            }
        });
        addRenderableWidget(Chip.of("Pick…", w - 72, 14, () -> minecraft.setScreenAndShow(new ItemPickerScreen(this, picked -> {
            spec.id(picked.id());
            if (!picked.isPlain()) {
                spec.components().entries().clear();
                spec.removed().clear();
                picked.components().entries().forEach(e -> spec.set(e.getKey(), e.getValue()));
                picked.removed().forEach(spec::remove);
                spec.count(picked.count());
            }
        }))).at(x + 70, y + 18).tip("Browse every item, or take one from your inventory"));

        int sy = y + 40;
        searchBox = addRenderableWidget(new EditBox(font, x, sy + 11, w, 14, Component.literal("Search components")));
        searchBox.setHint(Component.literal("search…"));
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            if (!s.equals(search)) {
                search = s;
                paletteScroll = 0;
                rememberScroll();
                rebuildWidgets();
                setFocused(searchBox);
                searchBox.setFocused(true);
            }
        });
        palette.clear();
        palette.bounds(x, sy + 28, w, bodyBottom() - sy - 28);
        buildPalette();
        addPaneWidgets(palette);
        palette.layout();
        palette.scroll(paletteScroll);
    }

    /** Components not on the item yet, grouped, filtered by the search box. */
    private void buildPalette() {
        Set<String> defaults = new HashSet<>(McItems.defaultComponentIds(spec.id()));
        String q = search.strip().toLowerCase(Locale.ROOT);
        List<ComponentSchema.Info> available = SCHEMA.arrange(McItems.componentIds());
        String group = null;
        int count = 0;
        for (ComponentSchema.Info info : available) {
            if (spec.components().has(info.id()) || spec.removed().contains(info.id()) || PINNED.contains(info.id())) {
                continue;
            }
            if (!q.isEmpty() && !info.id().contains(q) && !info.about().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (!info.group().equals(group)) {
                group = info.group();
                palette.add(new HeaderRow(SCHEMA.groups().getOrDefault(group, group)));
            }
            boolean isDefault = defaults.contains(info.id());
            Chip chip = new Chip(Component.literal(Ids.pretty(info.id()) + (isDefault ? " •" : "")), 100, 13, in -> {
                if (in.hasShiftDown() && isDefault) {
                    spec.remove(info.id());
                } else {
                    addComponent(info);
                }
                rememberScroll();
                if (scrollToEnd) {
                    cardScroll = Double.MAX_VALUE; // show the new card (it is last)
                    scrollToEnd = false;
                }
                rebuildWidgets();
            }).left().accent(Theme.group(info.group()));
            String tip = (info.about().isEmpty() ? "" : info.about() + "\n") + info.id()
                    + (isDefault ? "\n• This item has it by default. Click to change it, shift-click to remove it." : "");
            chip.setTooltip(Tooltip.create(Component.literal(tip)));
            palette.add(new WidgetRow(chip, 14));
            count++;
        }
        if (count == 0) {
            palette.add(new HeaderRow(q.isEmpty() ? "Everything is added" : "No match"));
        }
    }

    private void addComponent(ComponentSchema.Info info) {
        Nbt start = info.defaultValue() != null ? info.defaultValue().copy() : null;
        if (start == null) {
            start = McItems.defaultComponent(spec.id(), info.id(), registries());
        }
        spec.set(info.id(), start != null ? start : new Nbt.Compound());
        changed();
        scrollToEnd = true;
    }

    private void buildMiddle() {
        int x = midX(), w = midW();
        addRenderableWidget(Chip.of("Form", 40, 12, () -> setOutlineView(false)).selected(() -> !Session.outlineView)
                .at(x + w - 82, TOP + 1).tip("One card per component, with forms"));
        addRenderableWidget(Chip.of("Outline", 40, 12, () -> setOutlineView(true)).selected(() -> Session.outlineView)
                .at(x + w - 40, TOP + 1).tip("All components as one editable outline"));
        cards.clear();
        cards.bounds(x, TOP + 16, w, bodyBottom() - TOP - 16);
        if (Session.outlineView) {
            outlineArea = new CodeArea(outlineText(), cards.innerWidth()).lines(4, 400);
            outlineArea.onChange(this::applyOutline);
            cards.add(new ScrollPane.Row() {
                @Override
                public int layout(int rx, int ry, int rw) {
                    outlineArea.setX(rx);
                    outlineArea.setY(ry);
                    outlineArea.setWidth(rw);
                    outlineArea.lines(Math.max(4, (cards.height() - 4) / CodeArea.LINE_H), Math.max(4, (cards.height() - 4) / CodeArea.LINE_H));
                    return outlineArea.getHeight();
                }

                @Override
                public List<AbstractWidget> widgets() {
                    return List.of(outlineArea);
                }
            });
        } else {
            outlineArea = null;
            if (!spec.removed().isEmpty()) {
                cards.add(new RemovedCard());
            }
            for (String id : PINNED) {
                if (!spec.removed().contains(id)) {
                    cards.add(ComponentCards.create(SCHEMA.info(id), this));
                }
            }
            List<String> others = spec.components().keys().stream().filter(id -> !PINNED.contains(id)).toList();
            if (!others.isEmpty()) {
                cards.add(new HeaderRow("More (" + others.size() + ")"));
            }
            for (String id : others) {
                cards.add(ComponentCards.create(SCHEMA.info(id), this));
            }
            if (others.isEmpty()) {
                cards.add(new HeaderRow("More: pick from the list on the left"));
            }
        }
        addPaneWidgets(cards);
        cards.layout();
        cards.scroll(cardScroll);
        cardScroll = cards.scroll();
    }

    private void buildFooter() {
        int y = height - FOOTER;
        int w = width - PAD * 2;
        commandBox = addRenderableWidget(new EditBox(font, PAD, y, w - 58, 16, Component.literal("Command")));
        commandBox.setMaxLength(Commands.COMMAND_BLOCK_LIMIT);
        commandBox.setEditable(false);
        int by = y + 20;
        int x = PAD;
        if (onDone == null) {
            getButton = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.get"), b -> giveItem()).bounds(x, by, 70, 20).build());
            x += 72;
            addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_give"), b -> toast(Delivery.copy("/" + command), true))
                    .bounds(x, by, 80, 20).tooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.copy_give"))).build());
            x += 82;
            runButton = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.run"), b -> {
                Component msg = Delivery.run(command);
                toast(msg, true);
            }).bounds(x, by, 50, 20).build());
            x += 52;
            addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.copy_data"), b -> toast(Delivery.copy(spec.toTag().toString()), true))
                    .bounds(x, by, 70, 20).tooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.copy_data"))).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - PAD - 60, by, 60, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width - PAD - 124, by, 60, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.use"), b -> {
                onDone.accept(spec.copy());
                onClose();
            }).bounds(width - PAD - 62, by, 62, 20).build());
        }
    }

    private void giveItem() {
        if (problem == null && !stack.isEmpty()) {
            Component msg = Delivery.giveCreative(stack);
            toast(msg, Delivery.isCreative());
        }
    }

    private void setOutlineView(boolean on) {
        if (Session.outlineView == on) {
            return;
        }
        Session.outlineView = on;
        cardScroll = 0;
        rememberScroll();
        rebuildWidgets();
    }

    private void rememberScroll() {
        cardScroll = cards.scroll();
        paletteScroll = palette.scroll();
    }

    // ---- outline view ---------------------------------------------------------------------------

    private String outlineText() {
        Nbt.Compound c = new Nbt.Compound();
        spec.components().entries().forEach(e -> c.put(Ids.brief(e.getKey()), e.getValue()));
        spec.removed().forEach(r -> c.put("!" + Ids.brief(r), new Nbt.Compound()));
        return c.isEmpty() ? "" : Outline.write(c);
    }

    private void applyOutline(String text) {
        try {
            Nbt.Compound c = Outline.readCompound(text);
            spec.components().entries().clear();
            spec.removed().clear();
            for (var e : c.entries()) {
                if (e.getKey().startsWith("!")) {
                    spec.remove(e.getKey().substring(1));
                } else {
                    spec.set(e.getKey(), e.getValue());
                }
            }
            outlineError = "";
            outlineArea.errorLine(0);
            changed();
        } catch (SnbtException e) {
            outlineError = e.getMessage();
            outlineArea.errorLine(e.line);
        }
    }

    // ---- host -----------------------------------------------------------------------------------

    @Override
    public ItemSpec item() {
        return spec;
    }

    @Override
    public HolderLookup.Provider registries() {
        return Delivery.registries();
    }

    @Override
    public Font font() {
        return font;
    }

    @Override
    public void changed() {
        dirty = true;
        changedAt = System.currentTimeMillis();
    }

    @Override
    public void rebuild() {
        rememberScroll();
        rebuildWidgets();
    }

    @Override
    public boolean isPinned(String id) {
        return PINNED.contains(id);
    }

    @Override
    public void removeComponent(String id) {
        spec.unset(id);
        changed();
        rebuild();
    }

    @Override
    public void resetComponent(String id) {
        ComponentSchema.Info info = SCHEMA.info(id);
        if (info.defaultValue() != null) {
            spec.set(id, info.defaultValue().copy());
            changed();
            rebuild();
        }
    }

    private void importItem(ItemSpec imported) {
        spec.id(imported.id()).count(imported.count());
        spec.components().entries().clear();
        spec.removed().clear();
        imported.components().entries().forEach(e -> spec.set(e.getKey(), e.getValue().copy()));
        imported.removed().forEach(spec::remove);
        cardScroll = 0;
        changed();
    }

    // ---- validation and preview ---------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (dirty && System.currentTimeMillis() - changedAt >= DEBOUNCE_MS) {
            dirty = false;
            recompute();
        }
    }

    private void recompute() {
        HolderLookup.Provider reg = registries();
        command = Commands.give(spec, new Commands.Style(Session.briefCommands, Session.target));
        if (reg == null) {
            stack = ItemStack.EMPTY;
            problem = "Open a world to check and preview items.";
        } else {
            McItems.Result r = McItems.toStack(spec, reg);
            stack = r.ok() ? r.stack() : ItemStack.EMPTY;
            problem = r.error();
            if (problem == null) {
                McItems.Problem p = McItems.checkArgument(spec.toArgument(Session.briefCommands), reg);
                problem = p == null ? null : p.message();
            }
        }
        tooltip = ItemView.tooltip(stack, rightW() - 10);
        if (commandBox != null) {
            commandBox.setValue(command);
            commandBox.moveCursorToStart(false);
        }
        boolean valid = problem == null && !stack.isEmpty();
        if (getButton != null) {
            getButton.active = valid && Delivery.isCreative();
            getButton.setTooltip(Tooltip.create(Component.translatable(Delivery.isCreative()
                    ? "stacksmith.tooltip.get" : "stacksmith.tooltip.get_creative")));
        }
        if (runButton != null) {
            boolean fits = command.length() + 1 <= Commands.CHAT_LIMIT;
            runButton.active = valid && fits && Delivery.canUse("give");
            runButton.setTooltip(Tooltip.create(!fits ? Component.translatable("stacksmith.tooltip.run_too_long", command.length() + 1)
                    : !Delivery.canUse("give") ? Component.translatable("stacksmith.tooltip.run_no_permission")
                    : Component.translatable("stacksmith.tooltip.run")));
        }
        if (onDone == null) {
            Session.item = spec;
        }
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, title, width / 2, 8, Theme.TEXT);
        int top = TOP - 2, bottom = bodyBottom() + 2;
        panel(g, PAD - 2, top, leftW() + 4, bottom - top);
        panel(g, midX() - 2, top, midW() + 4, bottom - top);
        panel(g, rightX() - 2, top, rightW() + 4, bottom - top);
        // Left: item header.
        g.fill(PAD, TOP + 2, PAD + 32, TOP + 34, 0xFF0D1114);
        g.outline(PAD, TOP + 2, 32, 32, Theme.BORDER);
        ItemView.icon(g, stack.isEmpty() ? null : stack, PAD, TOP + 2, 32);
        label(g, "Add component", PAD + 1, TOP + 44);
        // Middle header.
        String head = Session.outlineView ? "Whole item as outline" : Ids.pretty(spec.id());
        label(g, font.plainSubstrByWidth(head, midW() - 86), midX() + 1, TOP + 3);
        // Right: preview.
        int rx = rightX(), rw = rightW();
        label(g, "Preview", rx + 1, TOP + 3);
        int iy = TOP + 16;
        g.fill(rx + (rw - 52) / 2, iy, rx + (rw + 52) / 2, iy + 52, 0x60000000);
        ItemView.icon(g, stack.isEmpty() ? null : stack, rx + (rw - 48) / 2, iy + 2, 48);
        if (!stack.isEmpty() && stack.getCount() > 1) {
            String n = Integer.toString(stack.getCount());
            g.text(font, n, rx + (rw + 48) / 2 - font.width(n), iy + 42, 0xFFFFFFFF, true);
        }
        int ty = iy + 58;
        int used = ItemView.tooltipPanel(g, tooltip, rx + 2, ty, rw - 4, bodyBottom() - ty - 30);
        int py = ty + used + 6;
        if (problem != null) {
            for (FormattedCharSequence line : font.split(Component.literal(problem), rw - 6)) {
                if (py > bodyBottom() - 10) {
                    break;
                }
                g.text(font, line, rx + 3, py, Theme.BAD, false);
                py += 10;
            }
        } else if (!stack.isEmpty()) {
            g.text(font, "✔ Valid item", rx + 3, py, Theme.GOOD, false);
        }
        if (!outlineError.isEmpty() && Session.outlineView) {
            g.text(font, font.plainSubstrByWidth(outlineError, midW()), midX(), bodyBottom() - 9, Theme.BAD, false);
        }
        // Footer: command length.
        int len = command.length() + 1;
        String count = len + "/" + Commands.CHAT_LIMIT;
        int fy = height - FOOTER + 4;
        g.text(font, count, width - PAD - font.width(count), fy, len <= Commands.CHAT_LIMIT ? Theme.TEXT_DIM : Theme.WARN, false);
        if (len > Commands.CHAT_LIMIT && commandBox.isMouseOver(mouseX, mouseY) || mouseX > width - PAD - 56 && mouseY >= fy - 2 && mouseY < fy + 10) {
            g.setTooltipForNextFrame(font, Component.translatable(len <= Commands.CHAT_LIMIT ? "stacksmith.tooltip.length_ok" : "stacksmith.tooltip.length_long"),
                    mouseX, mouseY);
        }
    }

    @Override
    public void removed() {
        rememberScroll();
        super.removed();
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** A heading in a list. */
    static final class HeaderRow implements ScrollPane.Row {
        private final String text;

        HeaderRow(String text) {
            this.text = text;
        }

        @Override
        public int layout(int x, int y, int width) {
            return 14;
        }

        @Override
        public void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            g.text(font, text, x + 1, y + 4, Theme.TEXT_DIM, false);
        }
    }

    /** One full-width widget. */
    static final class WidgetRow implements ScrollPane.Row {
        private final AbstractWidget widget;
        private final int h;

        WidgetRow(AbstractWidget widget, int h) {
            this.widget = widget;
            this.h = h;
        }

        @Override
        public int layout(int x, int y, int width) {
            widget.setX(x);
            widget.setY(y);
            widget.setWidth(width);
            return h;
        }

        @Override
        public List<AbstractWidget> widgets() {
            return List.of(widget);
        }
    }

    /** Lists default components removed from the item, each with a restore button. */
    private final class RemovedCard extends Card {
        private final List<Chip> chips = new ArrayList<>();

        RemovedCard() {
            super("Removed defaults", Theme.BAD, "Components this item normally has, removed with !name");
            for (String id : List.copyOf(spec.removed())) {
                chips.add(body(Chip.of(Ids.brief(id) + "  ↺", font.width(Ids.brief(id)) + 22, 14, () -> {
                    spec.unset(id);
                    changed();
                    rebuild();
                }).tip("Restore " + id)));
            }
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cx = x, cy = y;
            for (Chip c : chips) {
                if (cx + c.getWidth() > x + w && cx > x) {
                    cx = x;
                    cy += 16;
                }
                c.setX(cx);
                c.setY(cy);
                cx += c.getWidth() + 2;
            }
            return cy - y + 14;
        }
    }
}
