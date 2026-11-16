package io.github.tjhgibbs.stacksmith.client;

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
 * Gives several containers the same contents, in a world this game is running (singleplayer or
 * a LAN host): one at a time on the server thread, each checked against what the scan saw
 * before it is written and read back afterwards. Like {@link SpawnerWorldBatch}, it loads
 * far-away saved chunks for a moment but never generates terrain; unlike it, it doesn't travel
 * to containers next to unexplored terrain: those are skipped and listed.
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
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public static boolean busy() {
        Run run = current;
        if (run != null && Minecraft.getInstance().getSingleplayerServer() != run.server) {
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
        return !busy() && !lastChanges.isEmpty() && lastChangesWorld == Minecraft.getInstance().getSingleplayerServer();
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
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (busy() || SpawnerWorldBatch.busy() || CommandQueue.busy() || ChestIndex.busy || SpawnerIndex.busy)
            return "Wait for the current operation to finish.";
        if (server == null) return "Changing many containers at once needs a world you're running (singleplayer or LAN host).";
        if (!Delivery.canUse("setblock")) return "Operator permission is needed.";
        if (targets.isEmpty()) return "No containers selected.";
        completed = skipped = failed = 0;
        stopped = false;
        problems.clear();
        total = targets.size();
        Run run = new Run(server, List.copyOf(targets), edited == null ? null : Map.copyOf(edited), restore);
        current = run;
        busy = true;
        server.execute(run::next);
        return null;
    }

    /** Stops future writes; changes already completed remain. */
    public static void cancel() {
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
            return !cancelled && !finished && current == this && Minecraft.getInstance().getSingleplayerServer() == server;
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
