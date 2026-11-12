package io.github.tjhgibbs.stacksmith.server;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
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

/** Server-owned sequential writes; revalidate each target and never generate unexplored terrain. */
public final class ServerBatch {
    private final MinecraftServer server;
    private final ServerIndex index;
    private final java.util.function.BooleanSupplier allowed;
    private final Runnable changed;
    public ServerBatch(MinecraftServer server, ServerIndex index, java.util.function.BooleanSupplier allowed, Runnable changed) {
        this.server = server; this.index = index; this.allowed = allowed; this.changed = changed;
    }

    public volatile boolean busy;
    public volatile String status = "";
    public volatile int completed, skipped, failed, total;
    private volatile Run current;

    /** A target that was not updated, and why (for the results list). */
    public record Problem(ServerIndex.Entry entry, String reason) {}
    public final List<Problem> problems = new java.util.concurrent.CopyOnWriteArrayList<>();

    private enum Pool { ALWAYS, IF_DIFFERENT, NEVER }

    public boolean busy() { return busy; }
    public String status() { return status; }
    public String startChanges(List<ServerIndex.Entry> targets, SpawnerSpec base, SpawnerSpec edited) {
        Changes c = changes(base, edited);
        if (c.isEmpty()) return "Nothing was changed.";
        return start(targets, edited, c.pool() ? Pool.ALWAYS : Pool.NEVER, c.settings());
    }
    public String start(List<ServerIndex.Entry> targets, SpawnerSpec edited, boolean pool, boolean settings) {
        var selected = java.util.EnumSet.noneOf(SpawnerSpec.Setting.class);
        if (settings) for (var st : SpawnerSpec.Setting.values()) if (st != SpawnerSpec.Setting.DELAY) selected.add(st);
        return start(targets, edited, pool ? Pool.ALWAYS : Pool.IF_DIFFERENT, selected);
    }

    /** What an edit of {@code base} changed: the pool, and these settings (never the countdown). */
    public record Changes(boolean pool, java.util.EnumSet<SpawnerSpec.Setting> settings) {
        public boolean isEmpty() { return !pool && settings.isEmpty(); }
    }

    public static Changes changes(SpawnerSpec base, SpawnerSpec edited) {
        java.util.EnumSet<SpawnerSpec.Setting> settings = java.util.EnumSet.noneOf(SpawnerSpec.Setting.class);
        for (SpawnerSpec.Setting st : SpawnerSpec.Setting.values())
            if (st != SpawnerSpec.Setting.DELAY && base.get(st) != edited.get(st)) settings.add(st);
        return new Changes(!base.withPoolOf(edited).sameAs(base), settings);
    }

    private String start(List<ServerIndex.Entry> targets, SpawnerSpec edited, Pool pool,
                         java.util.EnumSet<SpawnerSpec.Setting> settings) {
        if (busy) return "Wait for the current operation to finish.";
        if (!allowed.getAsBoolean()) return "Session is no longer authorized.";
        if (targets.isEmpty()) return "No copies selected.";
        if (!edited.problems().isEmpty()) return "Fix the spawner settings before applying.";
        completed = skipped = failed = 0;
        problems.clear(); total = targets.size();
        Run run = new Run(server, List.copyOf(targets), edited.copy(), pool, settings);
        current = run; busy = true; status = "Updating spawners…";
        server.execute(run::next);
        return null;
    }

    /** Stops future writes; changes already completed remain. */
    public void cancel() {
        Run run = current;
        if (run != null) {
            run.cancelled = true;
            run.server.execute(() -> { run.release(); run.finish(); });
        }
    }

    private final class Run {
        final MinecraftServer server;
        final List<ServerIndex.Entry> targets;
        final SpawnerSpec edited;
        final Pool pool;
        final java.util.EnumSet<SpawnerSpec.Setting> settings;
        final TicketType ticket = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
        volatile boolean cancelled;
        int index;
        ServerLevel heldLevel;
        ChunkPos heldChunk;
        boolean finished;

        Run(MinecraftServer server, List<ServerIndex.Entry> targets, SpawnerSpec edited, Pool pool,
            java.util.EnumSet<SpawnerSpec.Setting> settings) {
            this.server = server; this.targets = targets; this.edited = edited;
            this.pool = pool; this.settings = settings;
        }

        void skip(ServerIndex.Entry target, String reason) { skipped++; problems.add(new Problem(target, reason)); }
        void fail(ServerIndex.Entry target, String reason) { failed++; problems.add(new Problem(target, reason)); }

        boolean active() {
            return !cancelled && !finished && current == this
                    && allowed.getAsBoolean();
        }

        void next() {
            if (!active()) { finish(); return; }
            changed.run();
            if (index >= targets.size()) { finish(); return; }
            ServerIndex.Entry target = targets.get(index++);
            status = "Updating " + (index - 1) + " / " + total + " copies…";
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension())));
            if (level == null) { skip(target, "Dimension isn't loaded"); later(); return; }
            ChunkPos chunk = new ChunkPos(target.pos().getX() >> 4, target.pos().getZ() >> 4);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                write(level, target); later(); return;
            }
            // FULL saved neighbours are required before adding a loading ticket. Incomplete
            // terrain near the explored edge is deliberately skipped, never generated to edit it.
            if (!ServerBatch.this.index.safeToLoad(target.dimension(), chunk)) {
                skip(target, "Near unexplored terrain: teleport there and edit it");
                later(); return;
            }
            level.getChunkSource().chunkMap.read(chunk).orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((saved, error) -> server.execute(() -> {
                        if (!active()) { finish(); return; }
                        if (error != null || saved == null || saved.isEmpty()) { fail(target, "Couldn't read its chunk"); later(); return; }
                        Nbt.Compound data = McNbt.fromGame(saved.get());
                        String savedStatus = data.getString("Status", "");
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

        void write(ServerLevel level, ServerIndex.Entry target) {
            try {
                if (!(level.getBlockEntity(target.pos()) instanceof SpawnerBlockEntity be)) { skip(target, "No spawner there any more"); ServerBatch.this.index.recordMissing(target); return; }
                Nbt.Compound fresh = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                // Compare all raw fields, including unfamiliar/modded data, except location and
                // the live countdown. A changed target is skipped, even if it still looks similar.
                if (!fingerprint(fresh).equals(fingerprint(target.raw()))) {
                    skip(target, "Changed since the last scan, so left alone");
                    ServerBatch.this.index.recordUpdated(target, fresh);
                    return;
                }
                Nbt.Compound out = fresh.copy();
                Nbt.Compound replacement = edited.toTag();
                // Written explicitly, defaults included: toTag leaves defaults out, but the game saves
                // every setting, so a removed key would never read back as the change being kept.
                for (SpawnerSpec.Setting s : settings) out.put(s.key, Nbt.s(edited.get(s)));
                if (pool == Pool.ALWAYS || pool == Pool.IF_DIFFERENT && !target.spec().withPoolOf(edited).sameAs(target.spec())) {
                    copyKey(replacement, out, "SpawnData");
                    copyKey(replacement, out, "SpawnPotentials");
                }
                String invalid = conflict(SpawnerSpec.fromTag(out));
                if (invalid != null) { skip(target, invalid); return; }
                be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), McNbt.toGame(out)));
                be.setChanged();
                level.sendBlockUpdated(target.pos(), be.getBlockState(), be.getBlockState(), 3);
                Nbt.Compound after = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                if (fingerprint(after).equals(fingerprint(out)) && java.util.Objects.equals(after.get("Delay"), fresh.get("Delay"))) {
                    completed++;
                    ServerBatch.this.index.recordUpdated(target, after);
                }
                else fail(target, "The game didn't keep the change");
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
                busy = false;
                status = (cancelled ? "Stopped · " : "Finished · ") + completed + " updated · " + skipped + " skipped · " + failed + " failed";
                current = null;
                changed.run();
            }
        }
    }

    /** Why a spawner would be broken after the change (the game fails on a minimum above the maximum), or null. */
    public static String conflict(SpawnerSpec result) {
        int min = result.get(SpawnerSpec.Setting.MIN_DELAY), max = result.get(SpawnerSpec.Setting.MAX_DELAY);
        return min > max ? "Would wait at least " + seconds(min) + " but at most " + seconds(max) : null;
    }

    private static String seconds(int ticks) {
        return (ticks % 20 == 0 ? Integer.toString(ticks / 20) : Double.toString(ticks / 20.0)) + " s";
    }

    /** {@code target} as it would be after applying {@code changes} from {@code edited}. */
    public static SpawnerSpec preview(SpawnerSpec target, SpawnerSpec edited, Changes changes) {
        SpawnerSpec out = changes.pool() ? target.withPoolOf(edited) : target.copy();
        for (SpawnerSpec.Setting st : changes.settings()) out.set(st, edited.get(st));
        return out;
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
