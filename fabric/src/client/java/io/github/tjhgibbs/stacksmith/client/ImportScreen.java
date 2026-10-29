package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.CodeArea;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import io.github.tjhgibbs.stacksmith.core.command.Importer;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.mod.McItems;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.function.Consumer;

/**
 * Bring something in to edit: the held item, the spawner you're looking at, or pasted text
 * (a /give, /setblock, /summon or /item command from anywhere, a bare item like
 * {@code diamond_sword[...]}, or saved item data). Items go to the item editor; spawners and
 * mobs to the spawner editor.
 */
public final class ImportScreen extends StacksmithScreen {
    private final Consumer<ItemSpec> onItem;
    private CodeArea paste;
    private String error = "";

    /** {@code onItem} receives imported items; spawners and mobs open the spawner editor instead. */
    public ImportScreen(Screen parent, Consumer<ItemSpec> onItem) {
        super(Component.translatable("stacksmith.screen.import"), parent);
        this.onItem = onItem;
    }

    private int panelW() {
        return Math.min(420, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    @Override
    protected void init() {
        int x = left(), w = panelW(), y = 30;
        Button held = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.import_held"), b -> importHeld())
                .bounds(x, y, (w - 4) / 2, 20).build());
        ItemStack hand = minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        held.active = !hand.isEmpty();
        held.setTooltip(Tooltip.create(hand.isEmpty() ? Component.translatable("stacksmith.tooltip.import_held_empty")
                : Component.translatable("stacksmith.tooltip.import_held", hand.getHoverName())));
        Button block = addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.import_block"), b -> importLookedAt())
                .bounds(x + w - (w - 4) / 2, y, (w - 4) / 2, 20).build());
        block.active = lookedAtBlockEntity() != null;
        block.setTooltip(Tooltip.create(Component.translatable("stacksmith.tooltip.import_block")));

        y += 40;
        String clip = minecraft.keyboardHandler.getClipboard();
        boolean looksUseful = clip != null && clip.length() < 100_000 && clip.strip().matches("(?s)/?(give|setblock|summon|item|[a-z0-9_:.-]+[\\[{]|\\{).*");
        paste = addRenderableWidget(new CodeArea(looksUseful ? clip.strip() : "", w).lines(6, 6)
                .placeholder("Paste a /give, /setblock, /summon or item here (Ctrl+V)"));
        paste.setX(x);
        paste.setY(y);
        paste.onChange(s -> error = "");
        y += paste.getHeight() + 6;
        addRenderableWidget(Button.builder(Component.translatable("stacksmith.button.import_text"), b -> importText())
                .bounds(x + w - 100, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x, y, 70, 20).build());
        setInitialFocus(paste);
    }

    private void importHeld() {
        ItemStack hand = minecraft.player.getMainHandItem();
        if (Delivery.registries() == null || hand.isEmpty()) {
            return;
        }
        route(new Importer.ItemResult(McItems.fromStack(hand, Delivery.registries())));
    }

    private BlockEntity lookedAtBlockEntity() {
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return minecraft.level.getBlockEntity(hit.getBlockPos());
    }

    private void importLookedAt() {
        BlockEntity be = lookedAtBlockEntity();
        if (be == null || Delivery.registries() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        // The client's copy has what the server shares for rendering; ops can ask for the rest.
        Nbt.Compound local = McNbt.fromGame(be.saveWithoutMetadata(Delivery.registries()));
        if (Delivery.canUse("data") && minecraft.getConnection() != null) {
            minecraft.getConnection().getDebugQueryHandler().queryBlockEntityTag(pos, tag -> minecraft.execute(() ->
                    routeBlock(be, tag != null ? McNbt.fromGame(tag) : local)));
        } else {
            routeBlock(be, local);
        }
    }

    private void routeBlock(BlockEntity be, Nbt.Compound data) {
        if (data.has("SpawnData") || data.has("SpawnPotentials")) {
            route(new Importer.SpawnerResult(SpawnerSpec.fromTag(data), null));
        } else {
            error = "Only spawners can be imported from blocks so far.";
        }
    }

    private void importText() {
        try {
            route(Importer.parse(paste.text()));
        } catch (SnbtException e) {
            error = e.getMessage();
            paste.errorLine(1);
        }
    }

    private void route(Importer.Result result) {
        switch (result) {
            case Importer.ItemResult r -> {
                if (onItem != null) {
                    onItem.accept(r.item());
                    onClose();
                } else {
                    Session.item = r.item();
                    minecraft.setScreenAndShow(new ItemEditorScreen(rootParent()));
                }
            }
            case Importer.SpawnerResult r -> {
                Session.spawner = r.spawner();
                Session.spawnerEntry = 0;
                minecraft.setScreenAndShow(new SpawnerEditorScreen(rootParent()));
            }
            case Importer.EntityResult r -> {
                Session.mob = r.entity();
                minecraft.setScreenAndShow(new MobEditorScreen(rootParent()));
            }
            case Importer.BlockResult r -> {
                String id = r.state().contains("[") ? r.state().substring(0, r.state().indexOf('[')) : r.state();
                if (McItems.isItem(id) && r.data().isEmpty()) {
                    route(new Importer.ItemResult(new ItemSpec(id)));
                } else {
                    error = "That block can't be edited here yet (only spawners).";
                }
            }
        }
    }

    /** The screen below the editor that opened this one (so switching editors doesn't stack). */
    private Screen rootParent() {
        return parent instanceof StacksmithScreen s ? s.parent : parent;
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = left(), w = panelW();
        panel(g, x - 6, 10, w + 12, height - 20);
        g.centeredText(font, title, width / 2, 16, Theme.TEXT);
        label(g, "Or paste", x, 60);
        if (!error.isEmpty()) {
            int y = paste.getY() + paste.getHeight() + 30;
            for (FormattedCharSequence line : font.split(Component.literal(error), w)) {
                g.text(font, line, x, y, Theme.BAD, false);
                y += 10;
            }
        }
    }
}
