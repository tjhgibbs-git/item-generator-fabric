package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Changes several blocks with ordinary commands, for a server without Stacksmith's hooks (or
 * the {@link Backend#CLIENT_ONLY} switch). Three steps, all from the client:
 * <ol>
 * <li>read each target fresh and decide what to write (the job skips any that changed since
 * the list was made, so a change made by someone else is left alone);</li>
 * <li>send every write through {@link CommandQueue}, in order (they share one storage slot);</li>
 * <li>read each written block back, so the results say what really happened.</li>
 * </ol>
 * Only loaded blocks in your dimension can be reached. One run at a time.
 */
final class CommandRun<T> {
    /** What to do with one target, given its fresh data. */
    sealed interface Plan permits Write, Skip, Already {}
    /** Send {@code commands}; afterwards the block counts as changed if {@code kept} accepts its data. */
    record Write(List<String> commands, Predicate<Nbt.Compound> kept) implements Plan {}
    record Skip(String reason) implements Plan {}
    /** Already as wanted: counts as done, nothing sent. */
    record Already() implements Plan {}

    /** The kind of block and what to do with it. */
    interface Job<T> {
        BlockPos pos(T target);
        String dimension(T target);
        /** Whether the client sees the right kind of block there. */
        boolean present(BlockEntity be);
        Plan plan(T target, Nbt.Compound fresh);
        /** Not there any more (gone, or not the right kind). */
        void missing(T target);
        void skipped(T target, String reason);
        void failed(T target, String reason);
        /** Changed as planned: its data before and after. */
        void written(T target, Nbt.Compound before, Nbt.Compound after);
        void already(T target);
        void finished(boolean cancelled);
    }

    private enum Step { READ, SEND, SETTLE, CHECK, DONE }

    private record Pending<T>(T target, Nbt.Compound before, Write write) {}

    private static CommandRun<?> current;

    private final Job<T> job;
    private final List<T> targets;
    private final Object connection;
    private final String dimension;
    private final List<Pending<T>> writes = new ArrayList<>();
    private Step step = Step.READ;
    private int index, checked, settle;
    private boolean waiting, cancelled;

    private CommandRun(Job<T> job, List<T> targets) {
        this.job = job;
        this.targets = List.copyOf(targets);
        this.connection = Minecraft.getInstance().getConnection();
        this.dimension = Backend.dimension();
    }

    /** Starts a run; null means started, else why not. */
    static <T> String start(Job<T> job, List<T> targets) {
        if (busy()) return "Wait for the current update to finish.";
        if (Minecraft.getInstance().getConnection() == null || Backend.dimension() == null) return "Join a world first.";
        if (!Delivery.canUse("data") || !Delivery.canUse("setblock")) return "Operator permission is needed (/data and /setblock).";
        current = new CommandRun<>(job, targets);
        return null;
    }

    static boolean busy() {
        return current != null;
    }

    /** Whether Stop still means "nothing written": only while reading. */
    static boolean canStop() {
        return current != null && current.step == Step.READ;
    }

    static void cancel() {
        if (canStop()) current.cancelled = true;
    }

    /** How far along, 0 to 1: reading is the first half, reading back the second. */
    static float progress() {
        CommandRun<?> run = current;
        if (run == null) return 1;
        int n = Math.max(1, run.targets.size());
        return switch (run.step) {
            case READ -> .5f * run.index / n;
            case SEND, SETTLE -> .5f;
            case CHECK -> .5f + .5f * run.checked / Math.max(1, run.writes.size());
            case DONE -> 1;
        };
    }

    /** What it is doing now. */
    static String status() {
        CommandRun<?> run = current;
        if (run == null) return "";
        return switch (run.step) {
            case READ -> "Reading " + Math.min(run.index + 1, run.targets.size()) + " of " + run.targets.size() + "…";
            case SEND -> "Sending commands…";
            case SETTLE, CHECK -> "Reading back " + Math.min(run.checked + 1, run.writes.size()) + " of " + run.writes.size() + "…";
            case DONE -> "";
        };
    }

    static void tick() {
        if (current != null) current.advance();
    }

    private boolean sameWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() == connection && dimension.equals(Backend.dimension());
    }

    private void advance() {
        Minecraft mc = Minecraft.getInstance();
        if (!sameWorld()) {
            // Commands still queued are dropped by CommandQueue on disconnect; in another
            // dimension they would land in the wrong place, so nothing more goes out.
            if (step == Step.READ) stopReading("World changed before it was reached");
            else for (int i = checked; i < writes.size(); i++)
                job.failed(writes.get(i).target(), "Sent, but the world changed before it could be checked");
            end(step == Step.READ);
            return;
        }
        if (waiting) return;
        switch (step) {
            case READ -> {
                if (cancelled) {
                    stopReading("Stopped before it was reached");
                    end(true);
                    return;
                }
                if (index >= targets.size()) { step = writes.isEmpty() ? Step.DONE : Step.SEND; if (writes.isEmpty()) end(false); return; }
                T target = targets.get(index);
                if (!job.dimension(target).equals(dimension)) {
                    job.skipped(target, "In another dimension: go there to change it");
                    index++;
                    return;
                }
                BlockEntity be = mc.level.getBlockEntity(job.pos(target));
                if (be == null && !mc.level.isLoaded(job.pos(target))) {
                    job.skipped(target, "Not loaded: go near it to change it");
                    index++;
                    return;
                }
                if (!job.present(be)) {
                    job.skipped(target, "Not there any more");
                    job.missing(target);
                    index++;
                    return;
                }
                waiting = true;
                BlockReads.read(job.pos(target), fresh -> {
                    waiting = false;
                    if (current != this) return;
                    index++;
                    if (fresh == null) { job.failed(target, "Couldn't read it from the server"); return; }
                    switch (job.plan(target, fresh)) {
                        case Skip s -> job.skipped(target, s.reason());
                        case Already a -> job.already(target);
                        case Write w -> writes.add(new Pending<>(target, fresh, w));
                    }
                });
            }
            case SEND -> {
                List<String> all = new ArrayList<>();
                for (Pending<T> p : writes) all.addAll(p.write().commands());
                waiting = true;
                CommandQueue.add(all, () -> {
                    waiting = false;
                    settle = 10;
                    step = Step.SETTLE;
                });
            }
            case SETTLE -> {
                // The commands are on their way; give the server a moment before reading back.
                if (--settle <= 0) step = Step.CHECK;
            }
            case CHECK -> {
                if (checked >= writes.size()) { end(false); return; }
                Pending<T> p = writes.get(checked);
                waiting = true;
                BlockReads.read(job.pos(p.target()), after -> {
                    waiting = false;
                    if (current != this) return;
                    checked++;
                    if (after == null) job.failed(p.target(), "Sent, but couldn't read it back");
                    else if (p.write().kept().test(after)) job.written(p.target(), p.before(), after);
                    else job.failed(p.target(), "Sent, but it doesn't show the change");
                });
            }
            case DONE -> end(false);
        }
    }

    /** Nothing has been sent yet: every target not yet settled is left alone. */
    private void stopReading(String reason) {
        for (int i = index; i < targets.size(); i++) job.skipped(targets.get(i), reason);
        for (Pending<T> p : writes) job.skipped(p.target(), reason);
        index = targets.size();
        writes.clear();
    }

    private void end(boolean stopped) {
        step = Step.DONE;
        if (current == this) current = null;
        job.finished(stopped);
    }
}
