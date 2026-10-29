package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.ScrollPane;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Saved items, mobs or spawners: save what is being edited under a name, or load one back.
 * Each is a file in {@code config/stacksmith/<kind>/}. Loading hands the data to the editor
 * that opened this screen (so a nested editor, like a spawner's mob, gets it too).
 */
public final class PresetsScreen extends StacksmithScreen {
    public static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("stacksmith");
    private static final Presets PRESETS = new Presets(ROOT);
    private static double listScroll;

    private final Presets.Kind kind;
    private final Nbt.Compound current;
    private final Consumer<Nbt.Compound> onLoad;
    private final ScrollPane list = pane(new ScrollPane());
    private EditBox name;
    private Button save;
    /** The preset whose ✕ was clicked once (a second click deletes). */
    private String confirmDelete;

    /** {@code current} is what Save stores; {@code onLoad} gets a loaded preset's data. */
    public PresetsScreen(Screen parent, Presets.Kind kind, Nbt.Compound current, Consumer<Nbt.Compound> onLoad) {
        super(Component.literal("Saved " + kind.folder), parent);
        this.kind = kind;
        this.current = current;
        this.onLoad = onLoad;
    }

    private int panelW() {
        return Math.min(380, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    @Override
    protected void init() {
        int x = left(), w = panelW(), y = 32;
        String suggested = name != null ? name.getValue() : suggestedName(kind, current);
        name = addRenderableWidget(new EditBox(font, x, y + 1, w - 84, 18, Component.literal("Name")));
        name.setMaxLength(80);
        name.setValue(suggested);
        name.setHint(Component.literal("Name to save this as"));
        name.setResponder(s -> updateSave());
        save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x + w - 80, y, 80, 20).build());
        updateSave();

        list.clear();
        list.bounds(x, y + 40, w, height - y - 40 - 34);
        List<Presets.Preset> presets = PRESETS.list(kind);
        for (Presets.Preset p : presets) {
            list.add(new PresetRow(p));
        }
        addPaneWidgets(list);
        list.layout();
        list.scroll(listScroll);

        addRenderableWidget(Button.builder(Component.literal("Copy folder path"), b -> toast(Delivery.copy(ROOT.resolve(kind.folder).toAbsolutePath().toString()), true))
                .bounds(x, height - 28, 110, 20).tooltip(Tooltip.create(Component.literal(ROOT.resolve(kind.folder).toString()))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + w - 70, height - 28, 70, 20).build());
        setInitialFocus(name);
    }

    private void updateSave() {
        boolean exists = PRESETS.exists(kind, name.getValue());
        save.setMessage(Component.literal(exists ? "Replace" : "Save"));
        save.active = !name.getValue().isBlank();
        save.setTooltip(Tooltip.create(Component.literal(exists ? "Replaces the saved one with this name" : "Save what you're editing under this name")));
    }

    private void save() {
        try {
            String saved = PRESETS.save(kind, name.getValue(), current);
            toast(Component.literal("Saved \"" + saved + "\""), true);
        } catch (IOException e) {
            toast(Component.literal("Couldn't save: " + e.getMessage()), false);
        }
        rebuild();
    }

    private void rebuild() {
        listScroll = list.scroll();
        rebuildWidgets();
    }

    @Override
    public void removed() {
        listScroll = list.scroll();
        super.removed();
    }

    /** A name for what's being saved: its custom name, else its type. */
    static String suggestedName(Presets.Kind kind, Nbt.Compound data) {
        try {
            return switch (kind) {
                case ITEM -> {
                    ItemSpec item = ItemSpec.fromTag(data);
                    Nbt n = item.get("custom_name");
                    String markup = n == null ? null : TextMarkup.fromComponent(n);
                    yield markup != null && !markup.isBlank() ? TextMarkup.plain(markup) : Ids.pretty(item.id());
                }
                case MOB -> mobName(EntitySpec.fromTag(data));
                case SPAWNER -> mobName(SpawnerSpec.fromTag(data).primary()) + " spawner";
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String mobName(EntitySpec mob) {
        Nbt n = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
        String markup = n == null ? null : TextMarkup.fromComponent(n);
        return markup != null && !markup.isBlank() ? TextMarkup.plain(markup) : Ids.pretty(mob.id());
    }

    private ItemStack icon(Nbt.Compound data) {
        try {
            return switch (kind) {
                case ITEM -> {
                    McItems.Result r = Delivery.registries() == null ? null : McItems.toStack(ItemSpec.fromTag(data), Delivery.registries());
                    yield r != null && r.ok() ? r.stack() : ItemStack.EMPTY;
                }
                case MOB -> MobEditorScreen.egg(EntitySpec.fromTag(data).id());
                case SPAWNER -> MobEditorScreen.egg(SpawnerSpec.fromTag(data).primary().id());
            };
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = left(), w = panelW();
        panel(g, x - 6, 8, w + 12, height - 16);
        g.centeredText(font, title, width / 2, 14, Theme.TEXT);
        label(g, "Save what you're editing", x, 24);
        label(g, list.rows().isEmpty() ? "Nothing saved yet" : "Load one", x, 62);
    }

    /** One saved preset: icon, name, Load and ✕. */
    private final class PresetRow implements ScrollPane.Row {
        private final Presets.Preset preset;
        private final ItemStack icon;
        private final String subtitle;
        private final Chip load, delete;

        PresetRow(Presets.Preset p) {
            this.preset = p;
            this.icon = icon(p.data());
            String id = p.data().getString("id", "");
            if (kind == Presets.Kind.SPAWNER) {
                try {
                    SpawnerSpec s = SpawnerSpec.fromTag(p.data());
                    id = s.primary().id() + (s.entries().size() > 1 ? " and " + (s.entries().size() - 1) + " more" : "");
                } catch (RuntimeException e) {
                    id = "";
                }
            }
            this.subtitle = Ids.brief(id);
            load = Chip.of("Load", 44, 16, () -> {
                onLoad.accept(p.data().copy());
            }).tip("Edit this one (what you're editing now is replaced)");
            delete = Chip.of(p.name().equals(confirmDelete) ? "Sure?" : "✕", p.name().equals(confirmDelete) ? 40 : 16, 16, () -> {
                if (p.name().equals(confirmDelete)) {
                    try {
                        PRESETS.delete(kind, p.name());
                    } catch (IOException e) {
                        toast(Component.literal("Couldn't delete: " + e.getMessage()), false);
                    }
                    confirmDelete = null;
                } else {
                    confirmDelete = p.name();
                }
                rebuild();
            }).color(Theme.BAD).tip("Delete (click twice)");
        }

        @Override
        public int layout(int x, int y, int width) {
            delete.setX(x + width - delete.getWidth());
            delete.setY(y + 1);
            load.setX(delete.getX() - 46);
            load.setY(y + 1);
            return 20;
        }

        @Override
        public void extract(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
            if (!icon.isEmpty()) {
                g.item(icon, x + 1, y + 1);
            }
            int room = load.getX() - x - 26;
            g.text(font, font.plainSubstrByWidth(preset.name(), room), x + 22, y + 2, Theme.TEXT, false);
            g.text(font, font.plainSubstrByWidth(subtitle, room), x + 22, y + 11, Theme.TEXT_DIM, false);
        }

        @Override
        public List<AbstractWidget> widgets() {
            return List.of(load, delete);
        }
    }
}
