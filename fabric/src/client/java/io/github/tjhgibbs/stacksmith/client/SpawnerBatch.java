package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copying the edited spawner onto the other spawners around it: find the ones that match the
 * linked spawner as it is in the world, i.e. as loaded or last saved, not the unsaved edits
 * (identical, or spawning the same mobs), then overwrite them.
 *
 * <p>Finding reads each spawner's full data from the server, one at a time (the game only
 * tracks one block query at once). Writing goes through {@link CommandQueue}. Both need op: the
 * same {@code /data} and {@code /setblock} as loading and saving one spawner.
 */
public final class SpawnerBatch {
    private SpawnerBatch() {
    }

    public enum Match {
        /** Same settings and mobs (the spawn countdown aside); overwritten completely. */
        IDENTICAL,
        /** Same mobs, any settings; gets the new mobs, keeping its own settings unless asked. */
        SAME_MOBS
    }

    public static final int MAX_RADIUS = 128;

    public static int radius = 32;
    public static Match match = Match.IDENTICAL;
    /** With {@link Match#SAME_MOBS}: also overwrite their timing and ranges. */
    public static boolean overwriteSettings;

    /** Every spawner found within the radius (not the edited one), or null before a search. */
    private static Map<BlockPos, SpawnerSpec> found;
    private static int toRead, read;
    private static boolean searching;
    /** Bumped to drop the answers of a search that was replaced or abandoned. */
    private static int generation;
    private static int written;
    /** The next read of a search, run on the coming tick. */
    private static Runnable nextRead;
    private static String status = "";
    /** Matches outlined in the world for a while after a search. */
    private static List<BlockPos> highlighted = List.of();
    private static long highlightUntil;
    private static final long HIGHLIGHT_MS = 15_000;

    /** Drops a search's results (the edited spawner changed). */
    public static void forget() {
        generation++;
        nextRead = null;
        found = null;
        searching = false;
        status = "";
    }

    public static boolean searching() {
        return searching;
    }

    public static boolean writing() {
        return CommandQueue.busy();
    }

    public static boolean searched() {
        return found != null && !searching;
    }

    /** A line about the last search or write, for the editor. */
    public static String status() {
        if (searching) {
            return "Reading spawners… " + read + "/" + toRead;
        }
        return status;
    }

    /** Where the search is centred: the linked spawner, or else the player. */
    public static BlockPos centre() {
        Minecraft mc = Minecraft.getInstance();
        return Session.spawnerPos != null ? Session.spawnerPos : mc.player != null ? mc.player.blockPosition() : null;
    }

    /** Reads every loaded spawner within {@link #radius} of {@link #centre()}. */
    public static void search(Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos c = centre();
        if (ChestIndex.busy) { status = "Wait for container indexing to finish."; return; }
        if (mc.level == null || c == null) {
            return;
        }
        List<BlockPos> spots = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (int cx = SectionPos.blockToSectionCoord(c.getX() - radius); cx <= SectionPos.blockToSectionCoord(c.getX() + radius); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(c.getZ() - radius); cz <= SectionPos.blockToSectionCoord(c.getZ() + radius); cz++) {
                if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (e.getValue() instanceof SpawnerBlockEntity && e.getKey().distSqr(c) <= r2 && !e.getKey().equals(Session.spawnerPos)) {
                        spots.add(e.getKey().immutable());
                    }
                }
            }
        }
        spots.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        int gen = ++generation;
        found = new LinkedHashMap<>();
        searching = true;
        toRead = spots.size();
        read = 0;
        readNext(spots, 0, gen, done);
    }

    private static void readNext(List<BlockPos> spots, int i, int gen, Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        if (gen != generation) {
            return;
        }
        if (i >= spots.size() || mc.level == null) {
            searching = false;
            status = spots.isEmpty() ? "No other spawners within " + radius + " blocks." : "";
            done.run();
            return;
        }
        if (!(mc.level.getBlockEntity(spots.get(i)) instanceof SpawnerBlockEntity be)) {
            readNext(spots, i + 1, gen, done);
            return;
        }
        WorldSpawner.read(be, data -> {
            if (gen == generation) {
                found.put(spots.get(i), SpawnerSpec.fromTag(data));
                read = i + 1;
                // Next tick: the game clears its query callback after this one returns, which
                // would drop a query started from here.
                nextRead = () -> readNext(spots, i + 1, gen, done);
            }
        });
    }

    /** Outlines {@code spots} in the world for a while (see {@link RangeView}). */
    public static void highlight(List<BlockPos> spots) {
        highlighted = List.copyOf(spots);
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
    }

    /** The spawners to outline now. */
    static List<BlockPos> highlighted() {
        return System.currentTimeMillis() < highlightUntil ? highlighted : List.of();
    }

    /** How many spawners the last search read. */
    public static int foundCount() {
        return found == null ? 0 : found.size();
    }

    /** The found spawners that match {@code reference} under the current {@link #match}. */
    public static List<BlockPos> matches(SpawnerSpec reference) {
        List<BlockPos> out = new ArrayList<>();
        if (found == null || searching || reference == null) {
            return out;
        }
        found.forEach((pos, s) -> {
            if (match == Match.IDENTICAL ? s.sameAs(reference) : s.sameMobs(reference)) {
                out.add(pos);
            }
        });
        return out;
    }

    /**
     * Overwrites every match of {@code reference} with {@code edited} (or, matching by mob and
     * keeping settings, with just its mobs). Returns null when started, else why not.
     */
    public static String apply(SpawnerSpec reference, SpawnerSpec edited, Commands.Style style) {
        List<BlockPos> targets = matches(reference);
        if (targets.isEmpty()) {
            return "Nothing to apply to.";
        }
        List<String> commands = new ArrayList<>();
        for (BlockPos p : targets) {
            SpawnerSpec target = found.get(p);
            SpawnerSpec out = match == Match.SAME_MOBS && !overwriteSettings ? target.withPoolOf(edited)
                    // Delay is each spawner's own countdown to its next spawn: left as it was.
                    : edited.copy().set(SpawnerSpec.Setting.DELAY, target.get(SpawnerSpec.Setting.DELAY));
            List<String> steps = Commands.setSpawnerInSteps(out, p.getX() + " " + p.getY() + " " + p.getZ(), style, Commands.CHAT_LIMIT);
            if (steps == null) {
                return "This spawner is too long to send from chat.";
            }
            commands.addAll(steps);
        }
        written = targets.size();
        CommandQueue.add(commands, () -> status = "Applied to " + written + (written == 1 ? " spawner." : " spawners."));
        highlight(targets);
        // They no longer hold what was found; search again to see the result.
        generation++;
        found = null;
        status = "Writing " + written + (written == 1 ? " spawner…" : " spawners…");
        return null;
    }

    /** Continues a search (called every client tick). */
    static void tick() {
        if (nextRead != null) {
            Runnable r = nextRead;
            nextRead = null;
            r.run();
        }
    }
}
