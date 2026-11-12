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

    /** A target that was not updated, and why (for the results list). */
    public record Problem(SpawnerIndex.Entry entry, String reason) {}
    public static final List<Problem> problems = new java.util.concurrent.CopyOnWriteArrayList<>();

    private enum Pool { ALWAYS, IF_DIFFERENT, NEVER }

    static boolean available() { return Minecraft.getInstance().getSingleplayerServer() != null || ServerHooks.apply; }
    private static boolean remote;
    private static Object remoteConnection;
    private static int remoteGeneration;
    private static String remoteStart(List<SpawnerIndex.Entry> targets, SpawnerSpec base, SpawnerSpec edited, boolean pool, boolean settings) {
        if (!ServerHooks.apply) return ServerHooks.REQUIRED;
        if (busy()) return "Wait for the current operation to finish.";
        if (targets.isEmpty()) return "No copies selected.";
        if (!edited.problems().isEmpty()) return "Fix the spawner settings before applying.";
        int gen = ++remoteGeneration;
        remote = true; remoteConnection = Minecraft.getInstance().getConnection();
        busy = true; completed = skipped = failed = 0; total = targets.size(); problems.clear();
        status = "Waiting for server…";
        io.github.tjhgibbs.stacksmith.core.nbt.Nbt.List keys = Nbt.list();
        targets.forEach(e -> keys.add(Nbt.str(e.key())));
        Nbt.Compound body = Nbt.compound().put("op", "apply").put("revision", SpawnerIndex.remoteRevision()).put("targets", keys).put("edited", edited.toTag())
                .put("pool", pool ? 1 : 0).put("settings", settings ? 1 : 0);
        if (base != null) body.put("base", base.toTag());
        ServerHooks.request(body, reply -> {
            if (gen != remoteGeneration) return;
            if (!reply.getString("op", "").equals("progress")) {
                status = reply.getString("message", "Server write failed."); busy = remote = false; return;
            }
            completed = (int) reply.getLong("completed", 0); skipped = (int) reply.getLong("skipped", 0);
            failed = (int) reply.getLong("failed", 0); total = (int) reply.getLong("total", total);
            status = reply.getString("status", ""); busy = reply.getLong("busy", 0) != 0;
            if (!busy) {
                if (reply.getList("problems") != null) for (Nbt value : reply.getList("problems").items()) if (value instanceof Nbt.Compound problem) {
                    var entry = SpawnerIndex.entries.get(problem.getString("key", ""));
                    if (entry != null) problems.add(new Problem(entry, problem.getString("reason", "")));
                }
                if (reply.getCompound("snapshot") != null) SpawnerIndex.remoteSnapshot(reply.getCompound("snapshot"));
                remote = false;
            }
        });
        return null;
    }
    public static boolean busy() {
        if (remote && remoteConnection != Minecraft.getInstance().getConnection()) {
            remoteGeneration++; remote = busy = false; status = "Disconnected · remaining writes stopped";
        }
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
        if (Minecraft.getInstance().getSingleplayerServer() == null) return remoteStart(targets, null, edited, SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS, replaceSettings);
        java.util.EnumSet<SpawnerSpec.Setting> settings = java.util.EnumSet.noneOf(SpawnerSpec.Setting.class);
        if (replaceSettings) for (SpawnerSpec.Setting st : SpawnerSpec.Setting.values()) if (st != SpawnerSpec.Setting.DELAY) settings.add(st);
        return start(targets, edited, SpawnerBatch.match == SpawnerBatch.Match.SAME_MOBS ? Pool.ALWAYS : Pool.IF_DIFFERENT, settings, null);
    }

    /**
     * Applies only what changed from {@code base} to {@code edited}: the mob pool if it was
     * edited, and each setting that was edited. Everything else stays as each target has it.
     */
    public static String startChanges(List<SpawnerIndex.Entry> targets, SpawnerSpec base, SpawnerSpec edited) {
        if (Minecraft.getInstance().getSingleplayerServer() == null) return remoteStart(targets, base, edited, false, false);
        Changes c = changes(base, edited);
        if (c.isEmpty()) return "Nothing was changed.";
        var player = Minecraft.getInstance().player;
        return start(targets, edited, c.pool() ? Pool.ALWAYS : Pool.NEVER, c.settings(), player == null ? null : player.getUUID());
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

    /** {@code traveller}: the player to take briefly to spawners that can't be loaded from afar, or null to skip those. */
    private static String start(List<SpawnerIndex.Entry> targets, SpawnerSpec edited, Pool pool,
                                java.util.EnumSet<SpawnerSpec.Setting> settings, java.util.UUID traveller) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (busy() || SpawnerBatch.writing() || SpawnerBatch.searching() || ChestIndex.busy || SpawnerIndex.busy)
            return "Wait for the current operation to finish.";
        if (server == null) return "Whole-world editing requires a singleplayer world.";
        if (!Delivery.canUse("setblock")) return "Operator permission is needed.";
        if (targets.isEmpty()) return "No copies selected.";
        if (!edited.problems().isEmpty()) return "Fix the spawner settings before applying.";
        completed = skipped = failed = 0;
        problems.clear();
        total = targets.size();
        Run run = new Run(server, List.copyOf(targets), edited.copy(), pool, settings, traveller);
        current = run;
        busy = true;
        status = "Updating 0 / " + total + " copies…";
        server.execute(run::next);
        return null;
    }

    /** Stops future writes; changes already completed remain. */
    public static void cancel() {
        if (remote) { ServerHooks.cancel(); return; }
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
        final Pool pool;
        final java.util.EnumSet<SpawnerSpec.Setting> settings;
        final TicketType ticket = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
        volatile boolean cancelled;
        int index;
        /** Spawners next to unexplored terrain, visited in person after the others. */
        final java.util.UUID traveller;
        final java.util.List<SpawnerIndex.Entry> far = new java.util.ArrayList<>();
        int farIndex;
        /** Where the traveller was before the first visit, to bring them back. */
        ServerLevel homeLevel;
        double homeX, homeY, homeZ;
        float homeYaw, homePitch;
        ServerLevel heldLevel;
        ChunkPos heldChunk;
        boolean finished;

        Run(MinecraftServer server, List<SpawnerIndex.Entry> targets, SpawnerSpec edited, Pool pool,
            java.util.EnumSet<SpawnerSpec.Setting> settings, java.util.UUID traveller) {
            this.server = server; this.targets = targets; this.edited = edited;
            this.pool = pool; this.settings = settings; this.traveller = traveller;
        }

        void skip(SpawnerIndex.Entry target, String reason) { skipped++; problems.add(new Problem(target, reason)); }
        void fail(SpawnerIndex.Entry target, String reason) { failed++; problems.add(new Problem(target, reason)); }

        boolean active() {
            return !cancelled && !finished && current == this
                    && Minecraft.getInstance().getSingleplayerServer() == server;
        }

        void next() {
            if (!active()) { finish(); return; }
            if (index >= targets.size()) { visitNext(); return; }
            SpawnerIndex.Entry target = targets.get(index++);
            status = "Updating " + (index - 1) + " / " + total + " copies…";
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension())));
            if (level == null) { skip(target, "Dimension isn't loaded"); later(); return; }
            ChunkPos chunk = new ChunkPos(target.pos().getX() >> 4, target.pos().getZ() >> 4);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                write(level, target); later(); return;
            }
            // FULL saved neighbours are required before adding a loading ticket. Incomplete
            // terrain near the explored edge is deliberately skipped, never generated to edit it.
            if (!SpawnerIndex.safeToLoad(target.dimension(), chunk)) {
                if (traveller != null) far.add(target);
                else skip(target, "Near unexplored terrain: teleport there and edit it");
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

        void write(ServerLevel level, SpawnerIndex.Entry target) {
            try {
                if (!(level.getBlockEntity(target.pos()) instanceof SpawnerBlockEntity be)) { skip(target, "No spawner there any more"); SpawnerIndex.recordMissing(target); return; }
                Nbt.Compound fresh = McNbt.fromGame(be.saveWithoutMetadata(level.registryAccess()));
                // Compare all raw fields, including unfamiliar/modded data, except location and
                // the live countdown. A changed target is skipped, even if it still looks similar.
                if (!fingerprint(fresh).equals(fingerprint(target.raw()))) {
                    skip(target, "Changed since the last scan, so left alone");
                    SpawnerIndex.recordUpdated(target, fresh);
                    return;
                }
                Nbt.Compound out = fresh.copy();
                Nbt.Compound replacement = edited.toTag();
                // toTag leaves defaults out, so a setting put back to its default is removed: the game reads that as the default.
                for (SpawnerSpec.Setting s : settings) copyKey(replacement, out, s.key);
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
                    SpawnerIndex.recordUpdated(target, after);
                }
                else fail(target, "The game didn't keep the change");
            } catch (Exception ex) { fail(target, "Error: " + ex.getMessage()); }
        }

        /**
         * Takes the player to the next spawner that couldn't be loaded from afar, and writes it
         * once the chunk is loaded the usual way (by the player being there).
         */
        void visitNext() {
            if (!active() || farIndex >= far.size()) { finish(); return; }
            SpawnerIndex.Entry target = far.get(farIndex++);
            status = "Visiting " + farIndex + " of " + far.size() + " by unexplored terrain…";
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(traveller);
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(target.dimension())));
            if (player == null || level == null) { skip(target, "Near unexplored terrain: teleport there and edit it"); later(); return; }
            if (homeLevel == null) {
                homeLevel = (ServerLevel) player.level();
                homeX = player.getX(); homeY = player.getY(); homeZ = player.getZ();
                homeYaw = player.getYRot(); homePitch = player.getXRot();
            }
            player.teleportTo(level, target.pos().getX() + .5, target.pos().getY() + 1, target.pos().getZ() + .5,
                    java.util.Set.of(), player.getYRot(), player.getXRot(), false);
            awaitChunk(level, target, 0);
        }

        void awaitChunk(ServerLevel level, SpawnerIndex.Entry target, int attempt) {
            if (!active()) { finish(); return; }
            if (level.getChunkSource().getChunkNow(target.pos().getX() >> 4, target.pos().getZ() >> 4) != null) {
                write(level, target); later(); return;
            }
            if (attempt >= 200) { fail(target, "Its chunk didn't load after going there"); later(); return; }
            CompletableFuture.runAsync(() -> server.execute(() -> awaitChunk(level, target, attempt + 1)),
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        }

        /** Puts the traveller back where they were, facing the same way. */
        void returnHome() {
            if (homeLevel == null) return;
            var player = server.getPlayerList().getPlayer(traveller);
            if (player != null) player.teleportTo(homeLevel, homeX, homeY, homeZ, java.util.Set.of(), homeYaw, homePitch, false);
            homeLevel = null;
        }

        void later() {
            if (!active() || index >= targets.size() && farIndex >= far.size()) { finish(); return; }
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
            // Cancelled or done: anything not visited yet is reported, and the player goes home.
            for (int i = farIndex; i < far.size(); i++) skip(far.get(i), "Near unexplored terrain: teleport there and edit it");
            farIndex = far.size();
            returnHome();
            if (current == this) {
                busy = false;
                status = (cancelled ? "Stopped · " : "Finished · ") + completed + " updated · " + skipped + " skipped · " + failed + " failed";
                current = null;
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
