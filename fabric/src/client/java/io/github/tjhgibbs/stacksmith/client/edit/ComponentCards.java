package io.github.tjhgibbs.stacksmith.client.edit;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.SuggestBox;
import io.github.tjhgibbs.stacksmith.client.ui.Swatch;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
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
    }

    static final String MARKUP_HELP = "&0-&f colours, &#rrggbb any colour, &l bold, &o italic, &n underline, &m strike, &k magic, &r reset";

    private ComponentCards() {
    }

    public static Card create(ComponentSchema.Info info, Host host) {
        Nbt value = host.item().get(info.id());
        Card card = switch (info.editor()) {
            case TEXT -> TextMarkup.fromComponent(value) != null ? new TextCard(info, host) : new OutlineCard(info, host);
            case TEXT_LIST -> value instanceof Nbt.List l && l.items().stream().allMatch(n -> TextMarkup.fromComponent(n) != null)
                    ? new TextListCard(info, host) : new OutlineCard(info, host);
            case INT, FLOAT -> value instanceof Nbt.Num ? new NumberCard(info, host) : new OutlineCard(info, host);
            case BOOL -> value instanceof Nbt.Num ? new BoolCard(info, host) : new OutlineCard(info, host);
            case UNIT -> new UnitCard(info, host);
            case ENUM -> value instanceof Nbt.Str ? new EnumCard(info, host) : new OutlineCard(info, host);
            case COLOR -> value instanceof Nbt.Num ? new ColorCard(info, host) : new OutlineCard(info, host);
            case ID -> value instanceof Nbt.Str ? new IdCard(info, host) : new OutlineCard(info, host);
            case ENCHANTMENTS -> value instanceof Nbt.Compound ? new EnchantmentsCard(info, host) : new OutlineCard(info, host);
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
            headerButton("✕", "Remove this component", () -> host.removeComponent(info.id())).color(Theme.BAD);
            if (info.defaultValue() != null) {
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

    static final class TextCard extends ComponentCard {
        private final EditBox box;
        private Component preview;

        TextCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            String markup = TextMarkup.fromComponent(value());
            box = body(markupBox(font, markup == null ? "" : markup, 100, s -> {
                Nbt c = TextMarkup.toComponent(s, info.upright());
                set(c);
                preview = McText.toGame(c, host.registries());
            }));
            preview = McText.toGame(value(), host.registries());
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            box.setX(x);
            box.setY(y);
            box.setWidth(w);
            return 30;
        }

        @Override
        protected void extractBody(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            if (preview != null) {
                var lines = font.split(preview, w);
                if (!lines.isEmpty()) {
                    g.text(font, lines.getFirst(), x + 1, y + 20, 0xFFFFFFFF, true);
                }
            }
        }
    }

    static final class TextListCard extends ComponentCard {
        private final List<EditBox> lines = new ArrayList<>();
        private final List<Chip> removers = new ArrayList<>();
        private final Chip add;

        TextListCard(ComponentSchema.Info info, Host host) {
            super(info, host);
            Nbt.List list = (Nbt.List) value();
            for (int i = 0; i < list.size(); i++) {
                int index = i;
                String markup = TextMarkup.fromComponent(list.get(i));
                lines.add(body(markupBox(font, markup == null ? "" : markup, 100, s -> {
                    Nbt.List l = (Nbt.List) value();
                    if (index < l.size()) {
                        l.set(index, TextMarkup.toComponent(s, info.upright()));
                        host.changed();
                    }
                })));
                removers.add(body(Chip.of("✕", 14, 16, () -> {
                    Nbt.List l = (Nbt.List) value();
                    if (index < l.size()) {
                        l.remove(index);
                        host.changed();
                        host.rebuild();
                    }
                }).color(Theme.BAD).tip("Remove this line")));
            }
            add = body(Chip.of("+ Add line", 70, 14, () -> {
                ((Nbt.List) value()).add(TextMarkup.toComponent("", info.upright()));
                host.changed();
                host.rebuild();
            }));
            status = list.size() == 1 ? "1 line" : list.size() + " lines";
        }

        @Override
        protected int layoutBody(int x, int y, int w) {
            int cy = y;
            for (int i = 0; i < lines.size(); i++) {
                lines.get(i).setX(x);
                lines.get(i).setY(cy);
                lines.get(i).setWidth(w - 16);
                removers.get(i).setX(x + w - 14);
                removers.get(i).setY(cy);
                cy += 18;
            }
            add.setX(x);
            add.setY(cy);
            return cy - y + 14;
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
            List<String> keys = new ArrayList<>(((Nbt.Compound) value()).keys());
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
                    ((Nbt.Compound) value()).remove(current[0]);
                    host.changed();
                    host.rebuild();
                }).color(Theme.BAD)));
            }
            add = body(Chip.of("+ Add enchantment", 100, 14, () -> {
                Nbt.Compound map = (Nbt.Compound) value();
                String next = all.stream().filter(e -> !map.has(e)).findFirst().orElse(null);
                if (next != null) {
                    map.put(next, Nbt.i(1));
                    host.changed();
                    host.rebuild();
                }
            }));
            status = keys.size() == 1 ? "1 enchantment" : keys.size() + " enchantments";
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
