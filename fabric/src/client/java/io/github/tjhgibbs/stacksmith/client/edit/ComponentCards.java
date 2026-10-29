package io.github.tjhgibbs.stacksmith.client.edit;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.FormatBar;
import io.github.tjhgibbs.stacksmith.client.ui.RichTextArea;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Swatch;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.outline.Outline;
import io.github.tjhgibbs.stacksmith.core.schema.ComponentSchema;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import io.github.tjhgibbs.stacksmith.mod.McRegistries;
import io.github.tjhgibbs.stacksmith.mod.McText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the form card for each component from its schema entry: text with &amp;-codes, lists of
 * lines, numbers, switches, choices, colours, registry ids, enchantment levels, and the outline
 * editor for everything else. Cards write straight into the item and tell the host.
 */
public final class ComponentCards {
    /** What a card needs from the screen it lives in. */
    public interface Host {
        ItemSpec item();

        HolderLookup.Provider registries();

        Font font();

        /** A value changed: re-validate and refresh previews (debounced by the host). */
        void changed();

        /** The card's structure changed (rows added/removed): rebuild the widgets. */
        void rebuild();

        void removeComponent(String id);

        /** Puts the schema's starting value back. */
        void resetComponent(String id);

        /** Always shown, set or not (✕ clears instead of removing the card). */
        default boolean isPinned(String id) {
            return false;
        }
    }

    static final String MARKUP_HELP = "&0-&f colours, &#rrggbb any colour, &l bold, &o italic, &n underline, &m strike, &k magic, &r reset";

    private ComponentCards() {
    }

    public static Card create(ComponentSchema.Info info, Host host) {
        Nbt value = host.item().get(info.id());
        Card card = switch (info.editor()) {
            case TEXT -> new RichTextCard(info, host, false);
            case TEXT_LIST -> value == null || value instanceof Nbt.List ? new RichTextCard(info, host, true) : new OutlineCard(info, host);
            case INT, FLOAT -> value instanceof Nbt.Num ? new NumberCard(info, host) : new OutlineCard(info, host);
            case BOOL -> value instanceof Nbt.Num ? new BoolCard(info, host) : new OutlineCard(info, host);
            case UNIT -> new UnitCard(info, host);
            case ENUM -> value instanceof Nbt.Str ? new EnumCard(info, host) : new OutlineCard(info, host);
            case COLOR -> value instanceof Nbt.Num ? new ColorCard(info, host) : new OutlineCard(info, host);
            case ID -> value instanceof Nbt.Str ? new IdCard(info, host) : new OutlineCard(info, host);
            case ENCHANTMENTS -> value == null || value instanceof Nbt.Compound ? new EnchantmentsCard(info, host) : new OutlineCard(info, host);
            case MODIFIERS -> ModifiersCard.fits(value) ? new ModifiersCard(info, host) : new OutlineCard(info, host);
            case POTION -> value == null || value instanceof Nbt.Str || value instanceof Nbt.Compound ? new PotionCard(info, host) : new OutlineCard(info, host);
            default -> new OutlineCard(info, host);
        };
        return card;
    }

    /** Base: title, group stripe, help, ↺ and ✕. */
    abstract static class ComponentCard extends Card {
        final ComponentSchema.Info info;
        final Host host;

        ComponentCard(ComponentSchema.Info info, Host host) {
            super(Ids.pretty(info.id()), Theme.group(info.group()), info.about().isEmpty() ? info.id() : info.about() + "\n" + info.id());
            this.info = info;
            this.host = host;
            boolean pinned = host.isPinned(info.id());
            headerButton("✕", pinned ? "Clear" : "Remove this component", () -> host.removeComponent(info.id())).color(Theme.BAD);
            if (info.defaultValue() != null && !pinned) {
                headerButton("↺", "Back to the starting value", () -> host.resetComponent(info.id()));
            }
        }

        Nbt value() {
            return host.item().get(info.id());
        }

        void set(Nbt v) {
            host.item().set(info.id(), v);
            host.changed();
        }
    }

    // ---- text ----------------------------------------------------------------------------------

    static EditBox markupBox(Font font, String markup, int w, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(font, 0, 0, w, 16, Component.literal("Text"));
        box.setMaxLength(2048);
        box.setValue(markup);
        box.setResponder(onChange);
        box.setTooltip(Tooltip.create(Component.literal(MARKUP_HELP)));
        return box;
    }

    /** How name and lore cards show their text; one choice for all of them, remembered. */
    public enum TextView {
        STYLED("Styled", "Edit it as it looks on the item, with colour and style buttons"),
        JSON("JSON", "The text component as JSON, exactly as stored"),
        OUTLINE("Outline", "The text component as a readable outline");

        final String label, tip;

        TextView(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }
    }

    public static TextView textView = TextView.STYLED;

    /**
     * A name ({@code list = false}) or lore: styled text with a toolbar, or the same value as
     * JSON or as an outline. Works on an absent component too (typing creates it), so the
     * screen can always show Name and Lore.
     */
    static final class RichTextCard extends ComponentCard {
        private final boolean list;
        private final TextView view;
        private FormatBar bar;
        private RichTextArea area;
        private CodeArea code;
        private String error = "";
        private String note = "";

        RichTextCard(ComponentSchema.Info info, Host host, boolean list) {
            super(info, host);
            this.list = list;
            TextView want = textView;
            List<String> markup = markupLines();
            if (want == TextView.STYLED && markup == null) {
                want = TextView.JSON;
                note = "Uses features the styled view can't show (hover, click, translations...).";
            }
            view = want;
            for (TextView v : List.of(TextView.OUTLINE, TextView.JSON, TextView.STYLED)) {
                headerButton(v.label, font.width(v.label) + 8, v.tip, () -> {
                    textView = v;
                    host.rebuild();
                }).selected(() -> view == v);
            }
            switch (view) {
                case STYLED -> {
                    area = body(new RichTextArea(String.join("\n", markup), !list)
                            .baseColor(list ? 0xAA00AA : 0xFFFFFF)
                            .lines(list ? 3 : 1, 14)
                            .placeholder(list ? "Type lore here: one line per line" : "Type a name, e.g. &6&lExcalibur")
                            .onChange(this::applyStyled));
                    bar = new FormatBar(() -> area);
                    bar.widgets().forEach(this::body);
                }
                case JSON, OUTLINE -> {
                    Nbt v = value();
                    String text = v == null ? "" : view == TextView.JSON ? Snbt.json(v) : Outline.write(v).stripTrailing();
                    code = body(new CodeArea(text, 100).lines(list ? 3 : 1, 16)
                            .placeholder(view == TextView.JSON ? (list ? "[\"first line\", {\"text\": \"second\", \"color\": \"gold\"}]"
                                    : "{\"text\": \"Excalibur\", \"color\": \"gold\"}") : (list ? "- first line" : "text: Excalibur")));
                    code.onChange(this::applyCode);
                }
            }
            updateStatus();
        }

        private static String defaultName() {
            return "&6&lExcalibur";
        }

        /** The value as one markup string per line, or null if markup can't express it. */
        private List<String> markupLines() {
            Nbt v = value();
            List<String> out = new ArrayList<>();
            if (v == null) {
                out.add("");
                return out;
            }
            if (list) {
                if (!(v instanceof Nbt.List l)) {
                    return null;
                }
                for (Nbt n : l.items()) {
                    String m = TextMarkup.fromComponent(n);
                    if (m == null) {
                        return null;
                    }
                    out.add(m);
                }
                if (out.isEmpty()) {
                    out.add("");
                }
                return out;
            }
            String m = TextMarkup.fromComponent(v);
            if (m == null) {
                return null;
            }
            out.add(m);
            return out;
        }

        private void applyStyled(List<String> lines) {
            boolean empty = lines.stream().allMatch(String::isEmpty);
            if (empty) {
                host.item().unset(info.id());
                host.changed();
            } else if (list) {
                Nbt.List l = new Nbt.List();
                lines.forEach(s -> l.add(TextMarkup.toComponent(s, info.upright())));
                set(l);
            } else {
                set(TextMarkup.toComponent(lines.getFirst(), info.upright()));
            }
            updateStatus();
        }

        private void applyCode(String text) {
            try {
                if (text.isBlank()) {
                    host.item().unset(info.id());
                    host.changed();
                } else {
                    Nbt parsed = view == TextView.JSON ? Snbt.parse(text) : Outline.read(text);
                    set(parsed);
                }
                error = "";
                code.errorLine(0);
            } catch (SnbtException e) {
                error = e.getMessage();
                code.errorLine(e.line);
            }
            updateStatus();
        }

        private void updateStatus() {
            Nbt v = value();
            if (list && v instanceof Nbt.List l) {
                status = l.size() == 1 ? "1 line" : l.size() + " lines";
            } else {
                status = v == null ? "not set" : "";
            }
            statusColor = v == null ? Theme.TEXT_FAINT : Theme.TEXT_DIM;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y;
            if (!note.isEmpty()) {
                cy += 11;
            }
            if (bar != null) {
                cy += bar.layout(x, cy, w) + 3;
                area.setX(x);
                area.setY(cy);
                area.setWidth(w);
                cy += area.getHeight();
            } else {
                code.setX(x);
                code.setY(cy);
                code.setWidth(w);
                cy += code.getHeight() + (error.isEmpty() ? 0 : 11);
            }
            return cy - y;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            if (!note.isEmpty()) {
                g.text(font, font.plainSubstrByWidth(note, w), x, y + 1, Theme.WARN, false);
            }
            if (code != null && !error.isEmpty()) {
                g.text(font, font.plainSubstrByWidth(error, w), x, code.getY() + code.getHeight() + 2, Theme.BAD, false);
            }
        }
    }

    // ---- scalars --------------------------------------------------------------------------------

    static final class NumberCard extends ComponentCard {
        private final EditBox box;
        private final Chip minus, plus;
        private final boolean real;

        NumberCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            Nbt.Num v = (Nbt.Num) value();
            real = info.editor() == ComponentSchema.Editor.FLOAT || !v.kind().integral();
            box = body(new EditBox(font, 0, 0, 60, 16, Component.literal(info.id())));
            box.setValue(real ? trim(v.asDouble()) : Long.toString(v.asLong()));
            box.setResponder(s -> apply(s, false));
            minus = body(Chip.of("−", 16, 16, () -> step(-1)));
            plus = body(Chip.of("+", 16, 16, () -> step(1)));
            if (Double.isFinite(info.min()) || Double.isFinite(info.max())) {
                status = rangeText();
            }
        }

        private String rangeText() {
            String lo = Double.isFinite(info.min()) ? trim(info.min()) : "…";
            String hi = Double.isFinite(info.max()) && info.max() < Integer.MAX_VALUE ? trim(info.max()) : "…";
            return lo + " to " + hi;
        }

        static String trim(double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }

        private void step(int by) {
            Nbt.Num v = (Nbt.Num) value();
            double n = v.asDouble() + by;
            box.setValue(real ? trim(clamp(n)) : Long.toString((long) clamp(n)));
        }

        private double clamp(double n) {
            return Math.max(info.min(), Math.min(info.max(), n));
        }

        private void apply(String s, boolean quiet) {
            try {
                double n = Double.parseDouble(s.strip());
                boolean ok = n >= info.min() && n <= info.max() && (real || n == Math.rint(n));
                box.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                if (ok) {
                    Nbt.Num old = (Nbt.Num) value();
                    Nbt.Num.Kind kind = real ? (old.kind().integral() ? Nbt.Num.Kind.FLOAT : old.kind()) : old.kind();
                    set(kind.integral() ? Nbt.Num.of(kind, (long) n) : Nbt.Num.of(kind, n));
                }
            } catch (NumberFormatException e) {
                box.setTextColor(Theme.BAD);
            }
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            minus.setX(x);
            minus.setY(y);
            box.setX(x + 18);
            box.setY(y);
            box.setWidth(Math.min(120, w - 36));
            plus.setX(box.getX() + box.getWidth() + 2);
            plus.setY(y);
            return 16;
        }
    }

    static final class BoolCard extends ComponentCard {
        private final Chip yes, no;

        BoolCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            yes = body(Chip.of("true", 50, 14, () -> set(Nbt.bool(true))).selected(() -> ((Nbt.Num) value()).asLong() != 0));
            no = body(Chip.of("false", 50, 14, () -> set(Nbt.bool(false))).selected(() -> ((Nbt.Num) value()).asLong() == 0));
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            yes.setX(x);
            yes.setY(y);
            no.setX(x + 52);
            no.setY(y);
            return 14;
        }
    }

    static final class UnitCard extends ComponentCard {
        UnitCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            status = "on";
            statusColor = Theme.GOOD;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            return 0;
        }
    }

    static final class EnumCard extends ComponentCard {
        private final List<Chip> chips = new ArrayList<>();

        EnumCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            for (String v : info.values()) {
                chips.add(body(Chip.of(v.replace('_', ' '), font.width(v) + 10, 14, () -> set(Nbt.str(v)))
                        .selected(() -> value() instanceof Nbt.Str s && s.value().equals(v))));
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

    /** Dye colours, for colour cards. */
    static final int[] DYES = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C,
            0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};
    static final String[] DYE_NAMES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black"};

    static final class ColorCard extends ComponentCard {
        private final EditBox hex;
        private final List<Swatch> swatches = new ArrayList<>();

        ColorCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            hex = body(new EditBox(font, 0, 0, 64, 16, Component.literal("Colour")));
            hex.setMaxLength(7);
            hex.setValue(String.format(Locale.ROOT, "#%06X", rgb()));
            hex.setResponder(s -> {
                String t = s.strip().startsWith("#") ? s.strip().substring(1) : s.strip();
                if (t.matches("[0-9a-fA-F]{6}")) {
                    hex.setTextColor(Theme.TEXT);
                    set(Nbt.i(Integer.parseInt(t, 16)));
                } else {
                    hex.setTextColor(Theme.BAD);
                }
            });
            for (int i = 0; i < DYES.length; i++) {
                int c = DYES[i];
                swatches.add(body(new Swatch(c, DYE_NAMES[i].replace('_', ' '), 12, () -> rgb() == c,
                        () -> hex.setValue(String.format(Locale.ROOT, "#%06X", c)))));
            }
        }

        private int rgb() {
            return value() instanceof Nbt.Num n ? (int) n.asLong() & 0xFFFFFF : 0;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            hex.setX(x + 20);
            hex.setY(y);
            int cx = x, cy = y + 19;
            for (Swatch s : swatches) {
                if (cx + 12 > x + w) {
                    cx = x;
                    cy += 14;
                }
                s.setX(cx);
                s.setY(cy);
                cx += 14;
            }
            return cy - y + 12;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            g.fill(x, y, x + 16, y + 16, 0xFF000000 | rgb());
            g.outline(x, y, 16, 16, 0xFF000000);
        }
    }

    static final class IdCard extends ComponentCard {
        private final SuggestBox box;

        IdCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            String registry = info.registry();
            List<String> ids = registry == null ? List.of() : McRegistries.ids(registry, host.registries());
            box = body(new SuggestBox(font, 100, 16, Component.literal(info.id()), () -> ids));
            box.setValueQuietly(Ids.brief(((Nbt.Str) value()).value()));
            box.onAccept(s -> {
                boolean ok = Ids.valid(Ids.full(s)) && (ids.isEmpty() || ids.contains(Ids.full(s)));
                box.setTextColor(ok ? Theme.TEXT : ids.isEmpty() ? Theme.TEXT : Theme.WARN);
                if (Ids.valid(Ids.full(s))) {
                    set(Nbt.str(Ids.full(s)));
                }
            });
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            box.setX(x);
            box.setY(y);
            box.setWidth(w);
            return 16;
        }
    }

    // ---- enchantments -----------------------------------------------------------------------

    static final class EnchantmentsCard extends ComponentCard {
        private final List<SuggestBox> ids = new ArrayList<>();
        private final List<EditBox> levels = new ArrayList<>();
        private final List<Chip> removers = new ArrayList<>();
        private final Chip add;

        EnchantmentsCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            List<String> all = McRegistries.ids("minecraft:enchantment", host.registries());
            List<String> keys = value() instanceof Nbt.Compound c ? new ArrayList<>(c.keys()) : new ArrayList<>();
            for (String key : keys) {
                String[] current = {key};
                SuggestBox id = body(new SuggestBox(font, 100, 16, Component.literal("Enchantment"), () -> all));
                id.setValueQuietly(Ids.brief(key));
                EditBox level = body(new EditBox(font, 0, 0, 30, 16, Component.literal("Level")));
                level.setValue(Long.toString(((Nbt.Compound) value()).getLong(key, 1)));
                id.onAccept(s -> {
                    String full = Ids.full(s);
                    boolean known = all.contains(full);
                    id.setTextColor(known ? Theme.TEXT : Theme.WARN);
                    if (known && !full.equals(current[0])) {
                        Nbt.Compound map = (Nbt.Compound) value();
                        Nbt lvl = map.remove(current[0]);
                        map.put(full, lvl == null ? Nbt.i(1) : lvl);
                        current[0] = full;
                        host.changed();
                    }
                });
                level.setResponder(s -> {
                    try {
                        int n = Integer.parseInt(s.strip());
                        boolean ok = n >= 1 && n <= 255;
                        level.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                        if (ok) {
                            ((Nbt.Compound) value()).put(current[0], Nbt.i(n));
                            host.changed();
                        }
                    } catch (NumberFormatException e) {
                        level.setTextColor(Theme.BAD);
                    }
                });
                level.setTooltip(Tooltip.create(Component.literal("Level 1 to 255")));
                ids.add(id);
                levels.add(level);
                removers.add(body(Chip.of("✕", 14, 16, () -> {
                    Nbt.Compound map = (Nbt.Compound) value();
                    map.remove(current[0]);
                    if (map.isEmpty()) {
                        host.item().unset(info.id());
                    }
                    host.changed();
                    host.rebuild();
                }).color(Theme.BAD).tip("Remove this enchantment")));
            }
            add = body(Chip.of("+ Add enchantment", 100, 14, () -> {
                if (!(value() instanceof Nbt.Compound)) {
                    host.item().set(info.id(), new Nbt.Compound());
                }
                Nbt.Compound map = (Nbt.Compound) value();
                String next = all.stream().filter(e -> !map.has(e)).findFirst().orElse(null);
                if (next != null) {
                    map.put(next, Nbt.i(1));
                    host.changed();
                    host.rebuild();
                }
            }));
            status = keys.isEmpty() ? "none" : keys.size() == 1 ? "1 enchantment" : keys.size() + " enchantments";
            statusColor = keys.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT_DIM;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y;
            for (int i = 0; i < ids.size(); i++) {
                ids.get(i).setX(x);
                ids.get(i).setY(cy);
                ids.get(i).setWidth(w - 52);
                levels.get(i).setX(x + w - 50);
                levels.get(i).setY(cy);
                removers.get(i).setX(x + w - 14);
                removers.get(i).setY(cy);
                cy += 18;
            }
            add.setX(x);
            add.setY(cy);
            return cy - y + 14;
        }
    }

    // ---- potions ------------------------------------------------------------------------------

    /**
     * potion_contents: base potion, colour, and extra effects (one row each: effect, level,
     * seconds or ∞, particles). Works for potions and tipped arrows; arrows apply 1/8 of the
     * duration, as in the game.
     */
    static final class PotionCard extends ComponentCard {
        private final SuggestBox potion;
        private final EditBox color;
        private final List<SuggestBox> ids = new ArrayList<>();
        private final List<EditBox> levels = new ArrayList<>(), durations = new ArrayList<>();
        private final List<Chip> particles = new ArrayList<>(), removers = new ArrayList<>();
        private final Chip add;
        private final List<EntitySpec.Effect> effects = new ArrayList<>();

        PotionCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            headerButton(io.github.tjhgibbs.stacksmith.client.Time.buttonLabel(), 50, io.github.tjhgibbs.stacksmith.client.Time.buttonTip(), () -> {
                io.github.tjhgibbs.stacksmith.client.Time.toggle();
                host.rebuild();
            });
            Nbt.Compound c = contents();
            List<String> potions = McRegistries.ids("minecraft:potion", host.registries());
            potion = body(new SuggestBox(font, 100, 16, Component.literal("Potion"), () -> potions));
            potion.setValueQuietly(c.getString("potion", "").isEmpty() ? "" : Ids.brief(c.getString("potion", "")));
            potion.setHint(Component.literal("none"));
            potion.setTooltip(Tooltip.create(Component.literal("The base potion (its own effects and colour). Empty for none: only the effects below.")));
            potion.onAccept(s -> {
                String full = Ids.full(s.strip());
                boolean ok = s.isBlank() || potions.isEmpty() || potions.contains(full);
                potion.setTextColor(ok ? Theme.TEXT : Theme.WARN);
                if (ok) {
                    Nbt.Compound now = contents();
                    now.putOrRemove("potion", s.isBlank() ? null : Nbt.str(full));
                    write(now);
                }
            });
            color = body(new EditBox(font, 0, 0, 52, 16, Component.literal("Colour")));
            color.setMaxLength(7);
            color.setHint(Component.literal("#colour"));
            long rgb = c.getLong("custom_color", Long.MIN_VALUE);
            color.setValue(rgb == Long.MIN_VALUE ? "" : String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF));
            color.setTooltip(Tooltip.create(Component.literal("Liquid / arrow tip colour as hex (ff8800). Empty: mixed from the effects.")));
            color.setResponder(t -> {
                String h = t.strip().startsWith("#") ? t.strip().substring(1) : t.strip();
                boolean ok = h.isEmpty() || h.matches("[0-9a-fA-F]{6}");
                color.setTextColor(ok ? Theme.TEXT : Theme.BAD);
                if (ok) {
                    Nbt.Compound now = contents();
                    now.putOrRemove("custom_color", h.isEmpty() ? null : Nbt.i(Integer.parseInt(h, 16) | 0xFF000000));
                    write(now);
                }
            });
            if (c.getList("custom_effects") instanceof Nbt.List l) {
                l.items().forEach(n -> {
                    if (n instanceof Nbt.Compound e) {
                        effects.add(EntitySpec.Effect.fromTag(e));
                    }
                });
            }
            List<String> all = McRegistries.ids("minecraft:mob_effect", host.registries());
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
                        EntitySpec.Effect x = effects.get(index);
                        update(index, new EntitySpec.Effect(full, x.amplifier(), x.duration(), x.ambient(), x.particles(), x.icon()));
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
                            EntitySpec.Effect x = effects.get(index);
                            update(index, new EntitySpec.Effect(x.id(), v - 1, x.duration(), x.ambient(), x.particles(), x.icon()));
                        }
                    } catch (NumberFormatException ex) {
                        level.setTextColor(Theme.BAD);
                    }
                });
                levels.add(level);
                EditBox duration = body(new EditBox(font, 0, 0, 34, 16, Component.literal("Seconds")));
                duration.setValue(e.duration() < 0 ? "" : io.github.tjhgibbs.stacksmith.client.Time.format(e.duration()));
                duration.setHint(Component.literal("∞"));
                duration.setTooltip(Tooltip.create(Component.literal(io.github.tjhgibbs.stacksmith.client.Time.unit() + "; empty for forever. Tipped arrows give 1/8 of this.")));
                duration.setResponder(t -> {
                    try {
                        int ticks = t.isBlank() ? -1 : io.github.tjhgibbs.stacksmith.client.Time.parse(t);
                        duration.setTextColor(Theme.TEXT);
                        EntitySpec.Effect x = effects.get(index);
                        update(index, new EntitySpec.Effect(x.id(), x.amplifier(), ticks, x.ambient(), x.particles(), x.icon()));
                    } catch (NumberFormatException ex) {
                        duration.setTextColor(Theme.BAD);
                    }
                });
                durations.add(duration);
                particles.add(body(Chip.of("✦", 16, 16, () -> {
                    EntitySpec.Effect x = effects.get(index);
                    update(index, new EntitySpec.Effect(x.id(), x.amplifier(), x.duration(), x.ambient(), !x.particles(), x.icon()));
                }).selected(() -> effects.get(index).particles()).tip("Show particles")));
                removers.add(body(Chip.of("✕", 14, 16, () -> {
                    effects.remove(index);
                    writeEffects();
                    host.rebuild();
                }).color(Theme.BAD).tip("Remove this effect")));
            }
            add = body(Chip.of("+ Add effect", 80, 14, () -> {
                effects.add(new EntitySpec.Effect("minecraft:poison", 0, 30 * 20, false, true, true));
                writeEffects();
                host.rebuild();
            }));
            String base = c.getString("potion", "");
            status = (base.isEmpty() ? "" : Ids.pretty(base)) + (effects.isEmpty() ? "" : (base.isEmpty() ? "" : " + ") + effects.size() + (effects.size() == 1 ? " effect" : " effects"));
            statusColor = Theme.TEXT_DIM;
        }

        /** The value as a compound (the game also accepts a bare potion id). */
        private Nbt.Compound contents() {
            Nbt v = value();
            if (v instanceof Nbt.Compound c) {
                return c.copy();
            }
            Nbt.Compound c = new Nbt.Compound();
            if (v instanceof Nbt.Str s) {
                c.put("potion", s.value());
            }
            return c;
        }

        private void write(Nbt.Compound c) {
            if (c.isEmpty()) {
                host.item().unset(info.id());
                host.changed();
            } else {
                set(c);
            }
        }

        private void update(int index, EntitySpec.Effect e) {
            effects.set(index, e);
            writeEffects();
        }

        private void writeEffects() {
            Nbt.Compound c = contents();
            Nbt.List l = new Nbt.List();
            effects.forEach(e -> l.add(e.toTag()));
            c.putOrRemove("custom_effects", l.isEmpty() ? null : l);
            write(c);
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int lw = 44;
            potion.setX(x + lw);
            potion.setY(y);
            potion.setWidth(w - lw - 56);
            color.setX(x + w - 52);
            color.setY(y);
            int cy = y + 20;
            for (int i = 0; i < ids.size(); i++) {
                int idW = Math.max(40, w - 26 - 34 - 16 - 14 - 8);
                ids.get(i).setX(x);
                ids.get(i).setY(cy);
                ids.get(i).setWidth(idW);
                int rx = x + idW + 2;
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

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            g.text(font, "Potion", x, y + 4, Theme.TEXT_DIM, false);
        }
    }

    // ---- attribute modifiers ----------------------------------------------------------------

    /** Rows of attribute / amount / operation / slot. Modifiers with extra fields fall back to the outline. */
    static final class ModifiersCard extends ComponentCard {
        static final List<String> OPERATIONS = List.of("add_value", "add_multiplied_base", "add_multiplied_total");
        static final List<String> OPERATION_LABELS = List.of("+ amount", "+ % of base", "× total");
        static final List<String> SLOTS = List.of("any", "mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body", "saddle");
        private static final java.util.Set<String> FIELDS = java.util.Set.of("type", "id", "amount", "operation", "slot");

        private final List<SuggestBox> types = new ArrayList<>();
        private final List<EditBox> amounts = new ArrayList<>();
        private final List<Chip> ops = new ArrayList<>(), slots = new ArrayList<>(), removers = new ArrayList<>();
        private final Chip add;

        static boolean fits(Nbt v) {
            if (!(v instanceof Nbt.List l)) {
                return false;
            }
            for (Nbt n : l.items()) {
                if (!(n instanceof Nbt.Compound c) || !FIELDS.containsAll(c.keys())) {
                    return false;
                }
            }
            return true;
        }

        ModifiersCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            List<String> attributes = McRegistries.ids("minecraft:attribute", host.registries());
            Nbt.List list = (Nbt.List) value();
            for (int i = 0; i < list.size(); i++) {
                Nbt.Compound m = (Nbt.Compound) list.get(i);
                SuggestBox type = body(new SuggestBox(font, 100, 16, Component.literal("Attribute"), () -> attributes));
                type.setValueQuietly(Ids.brief(m.getString("type", "minecraft:attack_damage")));
                type.onAccept(s -> {
                    String full = Ids.full(s);
                    boolean ok = attributes.isEmpty() || attributes.contains(full);
                    type.setTextColor(ok ? Theme.TEXT : Theme.WARN);
                    if (ok) {
                        m.put("type", full);
                        host.changed();
                    }
                });
                types.add(type);
                EditBox amount = body(new EditBox(font, 0, 0, 40, 16, Component.literal("Amount")));
                amount.setValue(NumberCard.trim(m.getDouble("amount", 0)));
                amount.setTooltip(Tooltip.create(Component.literal("Amount. For the % operations, 0.5 means +50%.")));
                amount.setResponder(s -> {
                    try {
                        m.put("amount", Nbt.d(Double.parseDouble(s.strip())));
                        amount.setTextColor(Theme.TEXT);
                        host.changed();
                    } catch (NumberFormatException e) {
                        amount.setTextColor(Theme.BAD);
                    }
                });
                amounts.add(amount);
                ops.add(body(cycle(m, "operation", OPERATIONS, OPERATION_LABELS, "add_value",
                        "How the amount applies: added, as a share of the base value, or multiplying the total")));
                slots.add(body(cycle(m, "slot", SLOTS, SLOTS, "any", "Where the item must be for this to apply")));
                if (!m.has("id")) {
                    m.put("id", "stacksmith:" + Ids.brief(m.getString("type", "modifier")).replace('.', '_') + "_" + i);
                }
                int index = i;
                removers.add(body(Chip.of("✕", 14, 16, () -> {
                    Nbt.List l = (Nbt.List) value();
                    if (index < l.size()) {
                        l.remove(index);
                        host.changed();
                        host.rebuild();
                    }
                }).color(Theme.BAD)));
            }
            add = body(Chip.of("+ Add modifier", 90, 14, () -> {
                Nbt.List l = (Nbt.List) value();
                l.add(Nbt.compound().put("type", "minecraft:attack_damage").put("id", "stacksmith:modifier_" + l.size())
                        .put("amount", Nbt.d(1)).put("operation", "add_value").put("slot", "mainhand"));
                host.changed();
                host.rebuild();
            }));
            status = list.size() == 1 ? "1 modifier" : list.size() + " modifiers";
        }

        private Chip cycle(Nbt.Compound m, String key, List<String> values, List<String> labels, String fallback, String tip) {
            String[] current = {m.getString(key, fallback)};
            Chip[] self = new Chip[1];
            self[0] = new Chip(Component.literal(label(values, labels, current[0])), 60, 16, in -> {
                int i = values.indexOf(current[0]);
                int next = Math.floorMod(i + (in.hasShiftDown() ? -1 : 1), values.size());
                current[0] = values.get(next);
                m.put(key, current[0]);
                self[0].setMessage(Component.literal(labels.get(next)));
                host.changed();
            });
            self[0].setTooltip(Tooltip.create(Component.literal(tip + " (click to change, shift-click back)")));
            return self[0];
        }

        private static String label(List<String> values, List<String> labels, String v) {
            int i = values.indexOf(v);
            return i >= 0 ? labels.get(i) : v;
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y;
            boolean wide = w >= 260;
            for (int i = 0; i < types.size(); i++) {
                int rest = wide ? w - 16 - 42 - 76 - 62 : w - 16;
                types.get(i).setX(x);
                types.get(i).setY(cy);
                types.get(i).setWidth(Math.max(60, rest));
                removers.get(i).setX(x + w - 14);
                removers.get(i).setY(cy);
                int rx = wide ? x + rest + 2 : x;
                int ry = wide ? cy : cy + 18;
                amounts.get(i).setX(rx);
                amounts.get(i).setY(ry);
                ops.get(i).setX(rx + 42);
                ops.get(i).setY(ry);
                ops.get(i).setWidth(74);
                slots.get(i).setX(rx + 118);
                slots.get(i).setY(ry);
                slots.get(i).setWidth(60);
                cy += wide ? 18 : 38;
            }
            add.setX(x);
            add.setY(cy);
            return cy - y + 14;
        }
    }

    // ---- outline ------------------------------------------------------------------------------

    static final class OutlineCard extends ComponentCard {
        private final CodeArea area;
        private String error = "";

        OutlineCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            Nbt v = value();
            area = body(new CodeArea(v == null ? "" : Outline.write(v).stripTrailing(), 100).lines(1, 16));
            area.onChange(text -> {
                try {
                    Nbt parsed = text.isBlank() ? new Nbt.Compound() : Outline.read(text);
                    error = "";
                    area.errorLine(0);
                    set(parsed);
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
