package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Getting results into the game. Everything here works on any server the player can use:
 * <ul>
 *   <li>{@link #giveCreative}: puts the stack in the inventory the way the creative menu does
 *       (no commands, no op; needs creative mode). Item data of any size arrives intact.</li>
 *   <li>{@link #run}: sends a command, only offered when the server lets this player use it
 *       and it fits the chat limit.</li>
 *   <li>{@link #copy}: the clipboard, always.</li>
 * </ul>
 * Spawners and command blocks still need op where they are placed: the server drops the data
 * of those blocks for non-ops. {@link #canUse} reports what the server allows.
 */
public final class Delivery {
    private Delivery() {
    }

    public static HolderLookup.Provider registries() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null ? mc.getConnection().registryAccess() : null;
    }

    public static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null;
    }

    public static boolean isCreative() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.isCreative();
    }

    /**
     * True if the server sent this player the command (servers only send the commands a player
     * may run), e.g. {@code canUse("give")}.
     */
    public static boolean canUse(String command) {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild(command) != null;
    }

    /** Puts the stack in the selected hotbar slot if empty, else the first empty slot. */
    public static Component giveCreative(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return Component.translatable("stacksmith.msg.no_world");
        }
        if (!mc.player.isCreative()) {
            return Component.translatable("stacksmith.msg.need_creative");
        }
        Inventory inv = mc.player.getInventory();
        int slot = inv.getItem(inv.getSelectedSlot()).isEmpty() ? inv.getSelectedSlot() : inv.getFreeSlot();
        if (slot < 0 || slot > 35) {
            return Component.translatable("stacksmith.msg.inventory_full");
        }
        ItemStack copy = stack.copy();
        inv.setItem(slot, copy);
        // Inventory index -> player menu slot: hotbar 0-8 is 36-44, main 9-35 is the same.
        mc.gameMode.handleCreativeModeItemAdd(copy, slot < 9 ? 36 + slot : slot);
        return Component.translatable("stacksmith.msg.given", copy.getHoverName());
    }

    /** Sends a command if allowed; returns a message saying why not otherwise. */
    public static Component run(String command) {
        Minecraft mc = Minecraft.getInstance();
        String cmd = Commands.clean(command);
        if (mc.getConnection() == null) {
            return Component.translatable("stacksmith.msg.no_world");
        }
        if (cmd.length() > Commands.CHAT_LIMIT) {
            return Component.translatable("stacksmith.msg.too_long", cmd.length(), Commands.CHAT_LIMIT);
        }
        String root = cmd.split(" ", 2)[0];
        if (!canUse(root)) {
            return Component.translatable("stacksmith.msg.no_permission", root);
        }
        mc.getConnection().sendCommand(cmd);
        return Component.translatable("stacksmith.msg.ran", root);
    }

    /** Sends several commands in order, stopping at the first that can't be sent. */
    public static Component runAll(java.util.List<String> commands) {
        Component last = Component.empty();
        for (String c : commands) {
            last = run(c);
            if (!isSent(last)) {
                return last;
            }
        }
        return last;
    }

    private static boolean isSent(Component msg) {
        return msg.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("stacksmith.msg.ran");
    }

    public static Component copy(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        return Component.translatable("stacksmith.msg.copied", text.length());
    }
}
