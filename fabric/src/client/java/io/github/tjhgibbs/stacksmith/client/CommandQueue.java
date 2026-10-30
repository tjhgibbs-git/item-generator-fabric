package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Sends the commands that write spawners, in order, a few per tick. Everything that writes a
 * spawner goes through here: spawners too long for one command are built in the one shared
 * command storage slot ({@link Commands#STORAGE}), so two writes running side by side would
 * overwrite each other's half-built data.
 *
 * <p>While it sends, the routine "Modified storage" / "Changed the block" replies are kept out
 * of chat (a big spawner takes dozens of commands). Errors still show.
 */
public final class CommandQueue {
    private CommandQueue() {
    }

    private static final int PER_TICK = 4;
    /** How long after the last command its replies are still expected. */
    private static final long QUIET_MS = 3000;
    private static final Set<String> ROUTINE = Set.of("commands.data.storage.modified", "commands.data.block.modified",
            "commands.setblock.success", "commands.data.merge.failed");

    private static final Deque<String> queue = new ArrayDeque<>();
    private static final Deque<Runnable> afterDone = new ArrayDeque<>();
    private static long quietUntil;

    static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !(System.currentTimeMillis() < quietUntil
                && message.getContents() instanceof TranslatableContents t && ROUTINE.contains(t.getKey())));
    }

    /** Queues {@code commands}; {@code done} (may be null) runs once the last of them is sent. */
    public static void add(java.util.List<String> commands, Runnable done) {
        queue.addAll(commands);
        quietUntil = System.currentTimeMillis() + QUIET_MS;
        if (done != null) {
            // Marks the end of this batch in the queue.
            queue.add("");
            afterDone.add(done);
        }
    }

    public static boolean busy() {
        return !queue.isEmpty();
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (queue.isEmpty()) {
            return;
        }
        if (mc.getConnection() == null) {
            queue.clear();
            afterDone.clear();
            return;
        }
        for (int sent = 0; sent < PER_TICK && !queue.isEmpty(); ) {
            String c = queue.poll();
            if (c.isEmpty()) {
                afterDone.poll().run();
            } else {
                mc.getConnection().sendCommand(Commands.clean(c));
                quietUntil = System.currentTimeMillis() + QUIET_MS;
                sent++;
            }
        }
        // A batch whose last command went out this tick is done too.
        while (!queue.isEmpty() && queue.peek().isEmpty()) {
            queue.poll();
            afterDone.poll().run();
        }
    }
}
