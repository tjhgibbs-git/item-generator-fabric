package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.container.ChestSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gives several containers new contents, each checked against what the scan saw before it is
 * written and read back afterwards. In a world this game is running (singleplayer or a LAN
 * host) it works on the server thread and, like {@link SpawnerWorldBatch}, loads far-away saved
 * chunks for a moment but never generates terrain (containers next to unexplored terrain are
 * skipped and listed). Anywhere else it uses commands on the loaded containers in your
 * dimension ({@link CommandRun}).
 */
public final class ChestWorldBatch {
    private ChestWorldBatch() {}

    public static volatile boolean busy;
    public static volatile int completed, skipped, failed, total;
    public static volatile boolean stopped;
    private static volatile Run current;

    /** A container that was not updated, and why. */
    public record Problem(ChestIndex.Entry entry, String reason) {}
    public static final List<Problem> problems = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A container the last update changed: as it is now, and its data before. */
    private record Undo(ChestIndex.Entry after, Nbt.Compound before) {}
    private static volatile List<Undo> lastChanges = List.of();
    private static Object lastChangesWorld;

    static boolean available() {
        return Backend.host() != null || Minecraft.getInstance().getConnection() != null;
    }

    /** No world of ours to work in: changes go out as commands, to loaded containers in your dimension. */
    static boolean byCommands() {
        return Backend.host() == null;
    }

    /** The last run (or the one running) was by commands. */
    static volatile boolean lastByCommands;

    /** How far the running update is, 0 to 1. */
    static float progress() {
        if (lastByCommands && busy) return CommandRun.progress();
        return (completed + skipped + failed) / (float) Math.max(1, total);
    }

    static String status() {
        return lastByCommands && busy ? CommandRun.status() : "";
    }

    public static boolean busy() {
        Run run = current;
        if (run != null && Backend.host() != run.server) {
            run.cancelled = true;
            current = null;
            busy = false;
            run.server.execute(() -> { run.release(); run.finish(); });
        }
        return busy;
    }

    /**
     * Gives each of {@code targets} the contents {@code wanted} holds for it (by key). Null
     * means started; otherwise why not.
     */
    public static String start(List<ChestIndex.Entry> targets, Map<String, ChestSpec> wanted) {
        return start(targets, wanted, null);
    }

    public static boolean canUndo() {
        // By commands the before-data is kept here, until you disconnect.
        return !busy() && !lastChanges.isEmpty() && lastChangesWorld == Backend.world();
    }

    /** Puts back what the last update changed, wherever the container is still as that update left it. */
    public static String undo() {
        if (!canUndo()) return "Nothing to undo.";
        Map<String, Nbt.Compound> restore = new HashMap<>();
        List<ChestIndex.Entry> targets = new ArrayList<>();
        for (Undo u : lastChanges) { restore.put(u.after().key(), u.before()); targets.add(u.after()); }
        return start(targets, null, restore);
    }

    private static String start(List<ChestIndex.Entry> targets, Map<String, ChestSpec> edited, Map<String, Nbt.Compound> restore) {
        MinecraftServer server = Backend.host();
        if (busy() || SpawnerWorldBatch.busy() || CommandQueue.busy() || ChestIndex.busy || SpawnerIndex.busy)
            return "Wait for the current operation to finish.";
        if (server == null) return commandStart(targets, edited == null ? null : Map.copyOf(edited), restore);
        if (!Delivery.canUse("setblock")) return "Operator permission is needed.";
        if (targets.isEmpty()) return "No containers selected.";
        completed = skipped = failed = 0;
        stopped = false;
        problems.clear();
        total = targets.size();
        Run run = new Run(server, List.copyOf(targets), edited == null ? null : Map.copyOf(edited), restore);
        current = run;
        busy = true;
        lastByCommands = false;
        server.execute(run::next);
        return null;
    }

    /**
     * By commands: reads each container fresh (skipping any that changed since the scan),
     * builds the new contents in command storage and merges them in (the block stays as it is,
     * so nothing drops), then reads each back. Null means started; otherwise why not.
     */
    private static String commandStart(List<ChestIndex.Entry> targets, Map<String, ChestSpec> edited, Map<String, Nbt.Compound> restore) {
        if (targets.isEmpty()) return "No containers selected.";
        Object connection = Minecraft.getInstance().getConnection();
        List<Undo> made = new ArrayList<>();
        CommandRun.Job<ChestIndex.Entry> job = new CommandRun.Job<>() {
            @Override public net.minecraft.core.BlockPos pos(ChestIndex.Entry t) { return t.pos(); }
            @Override public String dimension(ChestIndex.Entry t) { return t.dimension(); }
            @Override public boolean present(BlockEntity be) { return ChestIndex.supported(be); }

            @Override public CommandRun.Plan plan(ChestIndex.Entry t, Nbt.Compound fresh) {
                var level = Minecraft.getInstance().level;
                BlockEntity be = level == null ? null : level.getBlockEntity(t.pos());
                if (!ChestIndex.supported(be)) return new CommandRun.Skip("No container there any more");
                String block = ChestIndex.block(be);
                Nbt.Compound now = new ChestSpec(block, fresh).data();
                if (!block.equals(t.chest().block()) || !now.equals(t.chest().data())) {
                    if (block.equals(t.chest().block())) ChestIndex.recordUpdated(t, fresh);
                    return new CommandRun.Skip(restore != null ? "Changed since the update, so left alone" : "Changed since the last scan, so left alone");
                }
                Nbt.Compound wanted = restore != null ? new ChestSpec(block, restore.get(t.key())).data() : edited.get(t.key()).data();
                if (now.equals(wanted)) return new CommandRun.Already();
                List<String> out = new ArrayList<>();
                int limit = Commands.CHAT_LIMIT;
                if (!Commands.writeStorage(Commands.STORAGE, "chest", wanted, limit - 1, out))
                    return new CommandRun.Skip("An item in it is too long to send from chat");
                // The old contents and loot table go; anything else the block had stays.
                for (String key : now.keys()) {
                    if (wanted.has(key)) continue;
                    if (!key.matches("[A-Za-z0-9_]+")) return new CommandRun.Skip("Has data that can't be removed by command (" + key + ")");
                    out.add("data remove block " + t.where() + " " + key);
                }
                out.add("data modify block " + t.where() + " {} merge from storage " + Commands.STORAGE + " chest");
                out.add("data remove storage " + Commands.STORAGE + " chest");
                if (out.stream().anyMatch(c -> c.length() + 1 > limit)) return new CommandRun.Skip("An item in it is too long to send from chat");
                return new CommandRun.Write(out, after -> new ChestSpec(block, after).data().equals(wanted));
            }

            @Override public void missing(ChestIndex.Entry t) { ChestIndex.recordMissing(t); }
            @Override public void skipped(ChestIndex.Entry t, String reason) { skipped++; problems.add(new Problem(t, reason)); }
            @Override public void failed(ChestIndex.Entry t, String reason) { failed++; problems.add(new Problem(t, reason)); }
            @Override public void already(ChestIndex.Entry t) { completed++; }

            @Override public void written(ChestIndex.Entry t, Nbt.Compound before, Nbt.Compound after) {
                completed++;
                made.add(new Undo(new ChestIndex.Entry(t.dimension(), t.pos(), t.state(), new ChestSpec(t.chest().block(), after)), before.copy()));
                ChestIndex.recordUpdated(t, after);
            }

            @Override public void finished(boolean cancelled) {
                lastChanges = restore != null ? List.of() : List.copyOf(made);
                lastChangesWorld = connection;
                stopped = cancelled;
                busy = false;
            }
        };
        completed = skipped = failed = 0;
        stopped = false;
        problems.clear();
        total = targets.size();
        String why = CommandRun.start(job, targets);
        if (why != null) return why;
        busy = true;
        lastByCommands = true;
        return null;
    }

    /** Stops future writes; changes already completed remain. */
    public static void cancel() {
        if (lastByCommands && busy) { CommandRun.cancel(); return; }
        Run run = current;
        if (run != null) {
            run.cancelled = true;
            stopped = true;
            run.server.execute(() -> { run.release(); run.finish(); });
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final List<ChestIndex.Entry> targets;
        final Map<String, ChestSpec> edited;
        /** Undoing: the data to put back, by target key. Null for an ordinary update. */
        final Map<String, Nbt.Compound> restore;
        final List<Undo> changes = new ArrayList<>();
        final TicketType ticket = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
        volatile boolean cancelled;
        int index;
        ServerLevel heldLevel;
        ChunkPos heldChunk;
        boolean finished;

        Run(MinecraftServer server, List<ChestIndex.Entry> targets, Map<String, ChestSpec> edited, Map<String, Nbt.Compound> restore) {
            this.server = server; this.targets = targets; this.edited = edited; this.restore = restore;
        }

        void skip(ChestIndex.Entry target, String reason) { skipped++; problems.add(new Problem(target, reason)); }
        void fail(ChestIndex.Entry target, String reason) { failed++; problems.add(new Problem(target, reason)); }

        boolean active() {
            return !cancelled && !finished && current == this && Backend.host() == server;
        }

        void next() {
            if (!active() || index >= targets.size()) { finish(); return; }
            ChestIndex.Entry target = targets.get(index++);
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension())));
            if (level == null) { skip(target, "Dimension isn't loaded"); later(); return; }
            ChunkPos chunk = new ChunkPos(target.pos().getX() >> 4, target.pos().getZ() >> 4);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                write(level, target); later(); return;
            }
            // As for spawners: only a chunk whose saved neighbourhood is fully generated is
            // loaded from afar, so editing never generates terrain.
            if (!SpawnerIndex.safeToLoad(target.dimension(), chunk)) {
                skip(target, "Near unexplored terrain: teleport there and edit it");
                later(); return;
            }
            level.getChunkSource().chunkMap.read(chunk).orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((saved, error) -> server.execute(() -> {
                        if (!active()) { finish(); return; }
                        if (error != null || saved == null || saved.isEmpty()) { fail(target, "Couldn't read its chunk"); later(); return; }
                        String savedStatus = McNbt.fromGame(saved.get()).getString("Status", "");
                        if (!savedStatus.equals("minecraft:full") && !savedStatus.equals("full")) {
                            skip(target, "Its chunk isn't fully generated"); later(); return;
                        }
                        heldLevel = level; heldChunk = chunk;
                        level.getChunkSource().addTicketAndLoadWithRadius(ticket, chunk, 0)
                                .orTimeout(30, TimeUnit.SECONDS)
                                .whenComplete((ignored, loadError) -> server.execute(() -> {
                                    if (!active()) { release(); finish(); return; }
                                    try {
                                        if (loadError != null || level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) fail(target, "Its chunk didn't load");
                                        else write(level, target);
                                    } finally { release(); }
                                    later();
                                }));
                    }));
        }

        void write(ServerLevel level, ChestIndex.Entry target) {
            try {
                BlockEntity be = level.getBlockEntity(target.pos());
                String block = be == null ? "" : BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
                if (be == null || !ChestSpec.supported(block)) {
                    skip(target, "No container there any more");
                    ChestIndex.recordMissing(target);
                    return;
                }
                Nbt.Compound fresh = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                // Compared as the scan saw it: its items and every other piece of its data.
                Nbt.Compound now = new ChestSpec(block, fresh).data();
                if (!block.equals(target.chest().block()) || !now.equals(target.chest().data())) {
                    skip(target, restore != null ? "Changed since the update, so left alone" : "Changed since the last scan, so left alone");
                    if (block.equals(target.chest().block())) ChestIndex.recordUpdated(target, fresh);
                    return;
                }
                Nbt.Compound out;
                if (restore != null) {
                    out = restore.get(target.key()).copy();
                } else {
                    // The old contents and loot table go; anything else the block had stays.
                    out = fresh.copy();
                    for (String key : now.keys()) out.remove(key);
                    Nbt.Compound wanted = edited.get(target.key()).data();
                    for (String key : wanted.keys()) out.put(key, wanted.get(key).copy());
                }
                be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), McNbt.toGame(out)));
                be.setChanged();
                level.sendBlockUpdated(target.pos(), be.getBlockState(), be.getBlockState(), 3);
                Nbt.Compound after = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                if (new ChestSpec(block, after).data().equals(new ChestSpec(block, out).data())) {
                    completed++;
                    changes.add(new Undo(new ChestIndex.Entry(target.dimension(), target.pos(), target.state(), new ChestSpec(block, after)), fresh.copy()));
                    ChestIndex.recordUpdated(target, after);
                } else {
                    fail(target, "The game didn't keep the change");
                }
            } catch (Exception ex) { fail(target, "Error: " + ex.getMessage()); }
        }

        void later() {
            if (!active() || index >= targets.size()) { finish(); return; }
            CompletableFuture.runAsync(() -> server.execute(this::next),
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        }

        void release() {
            if (heldLevel != null) {
                heldLevel.getChunkSource().removeTicketWithRadius(ticket, heldChunk, 0);
                heldLevel = null; heldChunk = null;
            }
        }

        void finish() {
            if (finished) return;
            finished = true;
            release();
            if (current == this) {
                lastChanges = restore != null ? List.of() : List.copyOf(changes);
                lastChangesWorld = server;
                busy = false;
                current = null;
            }
        }
    }
}
