package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Sequential, revalidated singleplayer writes without moving the player or rewriting region files. */
public final class SpawnerWorldBatch {
    private SpawnerWorldBatch() {}

    public static volatile boolean busy;
    public static volatile String status = "";
    public static volatile int completed, skipped, failed, total;
    private static volatile Run current;

    public static boolean busy() {
        Run run = current;
        if (run != null && Minecraft.getInstance().getSingleplayerServer() != run.server) {
            run.cancelled = true;
            current = null;
            busy = false;
            status = "World changed · " + completed + " updated · remaining writes stopped";
            run.server.execute(() -> { run.release(); run.finish(); });
        }
        return busy;
    }
    public static String status() { busy(); return status; }

    /** Null means started; otherwise a human-readable reason why no edits were made. */
    public static String start(List<SpawnerIndex.Entry> targets, SpawnerSpec edited, boolean replaceSettings) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (busy() || SpawnerBatch.writing() || SpawnerBatch.searching() || ChestIndex.busy || SpawnerIndex.busy)
            return "Wait for the current operation to finish.";
        if (server == null) return "Whole-world editing requires a singleplayer world.";
        if (!Delivery.canUse("setblock")) return "Operator permission is needed.";
        if (targets.isEmpty()) return "No copies selected.";
        if (!edited.problems().isEmpty()) return "Fix the spawner settings before applying.";
        completed = skipped = failed = 0;
        total = targets.size();
        Run run = new Run(server, List.copyOf(targets), edited.copy(), replaceSettings,
                SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS);
        current = run;
        busy = true;
        status = "Updating 0 / " + total + " copies…";
        server.execute(run::next);
        return null;
    }

    /** Stops future writes; changes already completed remain. */
    public static void cancel() {
        Run run = current;
        if (run != null) {
            run.cancelled = true;
            run.server.execute(() -> { run.release(); run.finish(); });
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final List<SpawnerIndex.Entry> targets;
        final SpawnerSpec edited;
        final boolean settings, sameMobs;
        final TicketType ticket = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
        volatile boolean cancelled;
        int index;
        ServerLevel heldLevel;
        ChunkPos heldChunk;
        boolean finished;

        Run(MinecraftServer server, List<SpawnerIndex.Entry> targets, SpawnerSpec edited, boolean settings, boolean sameMobs) {
            this.server = server; this.targets = targets; this.edited = edited;
            this.settings = settings; this.sameMobs = sameMobs;
        }

        boolean active() {
            return !cancelled && !finished && current == this
                    && Minecraft.getInstance().getSingleplayerServer() == server;
        }

        void next() {
            if (!active() || index >= targets.size()) { finish(); return; }
            SpawnerIndex.Entry target = targets.get(index++);
            status = "Updating " + (index - 1) + " / " + total + " copies…";
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension())));
            if (level == null) { skipped++; later(); return; }
            ChunkPos chunk = new ChunkPos(target.pos().getX() >> 4, target.pos().getZ() >> 4);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                write(level, target); later(); return;
            }
            // FULL saved neighbours are required before adding a loading ticket. Incomplete
            // terrain near the explored edge is deliberately skipped, never generated to edit it.
            if (!SpawnerIndex.safeToLoad(target.dimension(), chunk)) {
                skipped++; later(); return;
            }
            level.getChunkSource().chunkMap.read(chunk).orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((saved, error) -> server.execute(() -> {
                        if (!active()) { finish(); return; }
                        if (error != null || saved == null || saved.isEmpty()) { failed++; later(); return; }
                        Nbt.Compound data = McNbt.fromGame(saved.get());
                        String savedStatus = data.getString("Status", "");
                        if (!savedStatus.equals("minecraft:full") && !savedStatus.equals("full")) {
                            skipped++; later(); return;
                        }
                        heldLevel = level; heldChunk = chunk;
                        level.getChunkSource().addTicketAndLoadWithRadius(ticket, chunk, 0)
                                .orTimeout(30, TimeUnit.SECONDS)
                                .whenComplete((ignored, loadError) -> server.execute(() -> {
                                    if (!active()) { release(); finish(); return; }
                                    try {
                                        if (loadError != null || level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) failed++;
                                        else write(level, target);
                                    } finally { release(); }
                                    later();
                                }));
                    }));
        }

        void write(ServerLevel level, SpawnerIndex.Entry target) {
            try {
                if (!(level.getBlockEntity(target.pos()) instanceof SpawnerBlockEntity be)) { skipped++; return; }
                Nbt.Compound fresh = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                // Compare all raw fields, including unfamiliar/modded data, except location and
                // the live countdown. A changed target is skipped, even if it still looks similar.
                if (!fingerprint(fresh).equals(fingerprint(target.raw()))) { skipped++; return; }
                Nbt.Compound out = fresh.copy();
                Nbt.Compound replacement = edited.toTag();
                if (settings) {
                    for (SpawnerSpec.Setting s : SpawnerSpec.Setting.values())
                        if (s != SpawnerSpec.Setting.DELAY) copyKey(replacement, out, s.key);
                }
                if (sameMobs || !target.spec().withPoolOf(edited).sameAs(target.spec())) {
                    copyKey(replacement, out, "SpawnData");
                    copyKey(replacement, out, "SpawnPotentials");
                }
                be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), McNbt.toGame(out)));
                be.setChanged();
                level.sendBlockUpdated(target.pos(), be.getBlockState(), be.getBlockState(), 3);
                Nbt.Compound after = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                if (fingerprint(after).equals(fingerprint(out)) && java.util.Objects.equals(after.get("Delay"), fresh.get("Delay"))) completed++;
                else failed++;
            } catch (Exception ex) { failed++; }
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
                busy = false;
                status = (cancelled ? "Stopped · " : "Finished · ") + completed + " updated · " + skipped + " skipped · " + failed + " failed";
                current = null;
            }
        }
    }

    private static Nbt.Compound fingerprint(Nbt.Compound raw) {
        Nbt.Compound out = raw.copy();
        for (String key : List.of("Delay", "id", "x", "y", "z", "keepPacked")) out.remove(key);
        return out;
    }

    private static void copyKey(Nbt.Compound from, Nbt.Compound to, String key) {
        Nbt value = from.get(key);
        if (value == null) to.remove(key); else to.put(key, value.copy());
    }
}
