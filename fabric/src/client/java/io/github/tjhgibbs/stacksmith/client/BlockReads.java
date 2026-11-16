package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Asks the server for blocks' full data ({@code /data}-style queries), one at a time. The game
 * keeps only one query open: starting another drops the first one's answer, so every query of
 * ours goes through this queue. A read you're waiting on (loading one block) goes ahead of a
 * long scan.
 */
final class BlockReads {
    private BlockReads() {}

    private static final long TIMEOUT_MS = 10_000;

    private record Job(BlockPos pos, Object connection, BooleanSupplier wanted, Consumer<Nbt.Compound> done) {}

    private static final Deque<Job> queue = new ArrayDeque<>();
    private static Job active;
    private static long deadline;
    private static int serial;

    /**
     * Reads the block entity at {@code pos} from the server. {@code done} runs on the client
     * thread with its data, or with null if the server didn't answer (no block entity there, no
     * permission, timed out, disconnected).
     */
    static void read(BlockPos pos, Consumer<Nbt.Compound> done) {
        read(pos, () -> true, done);
    }

    /** Like {@link #read}, but skipped (answered with null, no query sent) once {@code wanted} says no: a scan that was cancelled. */
    static void read(BlockPos pos, BooleanSupplier wanted, Consumer<Nbt.Compound> done) {
        queue.addLast(new Job(pos.immutable(), Minecraft.getInstance().getConnection(), wanted, done));
    }

    /** Like {@link #read}, ahead of everything already waiting. */
    static void readFirst(BlockPos pos, Consumer<Nbt.Compound> done) {
        queue.addFirst(new Job(pos.immutable(), Minecraft.getInstance().getConnection(), () -> true, done));
    }

    static boolean busy() {
        return active != null || !queue.isEmpty();
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) {
            if (active.connection() != mc.getConnection() || System.currentTimeMillis() >= deadline) finish(active, null);
            else return;
        }
        while (!queue.isEmpty()) {
            Job job = queue.poll();
            if (job.connection() == null || job.connection() != mc.getConnection() || !job.wanted().getAsBoolean()) { job.done().accept(null); continue; }
            active = job;
            deadline = System.currentTimeMillis() + TIMEOUT_MS;
            int id = ++serial;
            mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(job.pos(), tag -> mc.execute(() -> {
                // The answer to a query we gave up on (timed out) must not be taken for a later one's.
                if (active == job && serial == id) finish(job, tag == null ? null : McNbt.fromGame(tag));
            }));
            return;
        }
    }

    private static void finish(Job job, Nbt.Compound data) {
        active = null;
        job.done().accept(data);
    }
}
