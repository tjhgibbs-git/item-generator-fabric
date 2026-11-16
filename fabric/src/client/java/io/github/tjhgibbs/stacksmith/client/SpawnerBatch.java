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
 * Spawners around you on a server without Stacksmith's world hooks: reads every loaded spawner
 * within a radius, and writes changes to them with ordinary commands.
 *
 * <p>Finding reads each spawner's full data from the server, one at a time (the game only
 * tracks one block query at once). Writing goes through {@link CommandQueue}. Both need op: the
 * same {@code /data} and {@code /setblock} as loading and saving one spawner.
 */
public final class SpawnerBatch {
    private SpawnerBatch() {
    }

    public static final int MAX_RADIUS = 128;

    public static int radius = 32;
    /** Every spawner found within the radius (not the edited one), or null before a search. */
    private static Map<BlockPos, SpawnerSpec> found;
    private static int toRead, read;
    private static boolean searching;
    /** Bumped to drop the answers of a search that was replaced or abandoned. */
    private static int generation;
    private static long readDeadline;
    private static final long READ_TIMEOUT_MS = 10_000;
    private static Object searchWorld;
    private static String searchDimension;
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
        readDeadline = 0;
        highlighted = List.of();
        status = "";
    }

    /** Stops discovery; discarded replies cannot become usable results. */
    public static void cancel() {
        forget();
        status = "Search cancelled. No spawners changed.";
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
        searchWorld = mc.getConnection();
        searchDimension = mc.level.dimension().identifier().toString();
        status = "";
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
            readDeadline = 0;
            status = spots.isEmpty() ? "No other spawners in loaded chunks within " + radius + " blocks." : "";
            done.run();
            return;
        }
        if (!(mc.level.getBlockEntity(spots.get(i)) instanceof SpawnerBlockEntity be)) {
            readNext(spots, i + 1, gen, done);
            return;
        }
        readDeadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
        WorldSpawner.read(be, data -> {
            if (gen == generation) {
                readDeadline = 0;
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

    /**
     * The spawners the last search read, as index entries (their raw data is the parsed
     * spawner's own), nearest first. Empty before a search or after the world changed.
     */
    public static List<SpawnerIndex.Entry> entries() {
        List<SpawnerIndex.Entry> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (found == null || searching || mc.level == null || searchWorld != mc.getConnection()
                || !mc.level.dimension().identifier().toString().equals(searchDimension)) {
            return out;
        }
        found.forEach((pos, s) -> out.add(new SpawnerIndex.Entry(searchDimension, pos, s, s.toTag())));
        return out;
    }

    /**
     * Writes {@code changes} from {@code edited} to each of {@code targets} with commands, each
     * keeping its other settings and its countdown. Returns null when started, else why not.
     * {@code done} runs once the last command is sent (not when the server has acted on it).
     */
    public static String applyChanges(List<SpawnerIndex.Entry> targets, SpawnerSpec edited, SpawnerWorldBatch.Changes changes,
                                      Commands.Style style, Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || searchWorld != mc.getConnection()
                || !mc.level.dimension().identifier().toString().equals(searchDimension)) {
            forget();
            return "World changed. Search again before applying.";
        }
        if (searching || writing()) return "Wait for the current operation to finish.";
        if (ChestIndex.busy) return "Wait for container indexing to finish.";
        if (!edited.problems().isEmpty()) return "Fix the spawner settings before applying.";
        if (targets.isEmpty()) return "No spawners selected.";
        // Validate every location before queuing any write. This detects unloaded or removed
        // blocks, but is not a fresh server-side comparison of their saved data.
        for (SpawnerIndex.Entry t : targets) {
            if (!(mc.level.getBlockEntity(t.pos()) instanceof SpawnerBlockEntity)) {
                forget();
                return "A spawner was removed or unloaded. Search again before applying.";
            }
        }
        List<String> commands = new ArrayList<>();
        List<BlockPos> written = new ArrayList<>();
        for (SpawnerIndex.Entry t : targets) {
            // A copy of the target with only the changes: its countdown and other settings stay.
            SpawnerSpec out = SpawnerWorldBatch.preview(t.spec(), edited, changes);
            if (SpawnerWorldBatch.conflict(out) != null) continue;
            List<String> steps = Commands.setSpawnerInSteps(out, t.where(), style, Commands.CHAT_LIMIT);
            if (steps == null) {
                return "This spawner is too long to send from chat.";
            }
            commands.addAll(steps);
            written.add(t.pos());
        }
        if (written.isEmpty()) return "Every selected spawner would be broken by this change.";
        CommandQueue.add(commands, done);
        highlight(written);
        // They no longer hold what was found; search again to see the result.
        generation++;
        found = null;
        status = "";
        return null;
    }

    /** Continues a search (called every client tick). */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (searching && (mc.level == null || searchWorld != mc.getConnection()
                || !mc.level.dimension().identifier().toString().equals(searchDimension))) {
            forget();
            status = "World changed. Search again.";
        }
        if (searching && readDeadline != 0 && System.currentTimeMillis() >= readDeadline) {
            forget();
            status = "Read timed out. Search again; no spawners changed.";
        }
        if (nextRead != null) {
            Runnable r = nextRead;
            nextRead = null;
            r.run();
        }
    }
}
