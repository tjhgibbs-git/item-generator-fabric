package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.*;

/** An explicit session snapshot of saved spawners, overlaid with current server chunks.
 * Reads existing files only: discovery never loads or generates terrain. */
public final class SpawnerIndex {
    private SpawnerIndex() {}

    public record Entry(String dimension, BlockPos pos, SpawnerSpec spec, Nbt.Compound raw) {
        public String key() { return dimension + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ(); }
        public String where() { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    }

    static final Map<String, Entry> entries = new LinkedHashMap<>();
    static boolean busy;
    static boolean indexed;
    static volatile String status = "Discover spawners across all saved dimensions.";
    static int skipped;
    private static volatile int generation;
    private static Object world;
    private static String sourceDimension;
    private static BlockPos sourcePos;
    private static Set<String> fullChunks = Set.of();

    public static boolean available() { return Minecraft.getInstance().getSingleplayerServer() != null; }
    static boolean current() {
        return world != null && world == Minecraft.getInstance().getSingleplayerServer();
    }
    private static String chunkKey(String dim, int x, int z) { return dim + ":" + x + ":" + z; }
    public static boolean isFullChunk(String dim, int x, int z) {
        return current() && fullChunks.contains(chunkKey(dim, x, z));
    }
    /** Conservative saved FULL neighbourhood check for the executor; discovery itself never loads. */
    public static boolean safeToLoad(String dim, ChunkPos pos) {
        for (int x = pos.x() - 11; x <= pos.x() + 11; x++)
            for (int z = pos.z() - 11; z <= pos.z() + 11; z++)
                if (!isFullChunk(dim, x, z)) return false;
        return true;
    }

    public static void cancel() {
        generation++;
        busy = false;
        indexed = false;
        entries.clear();
        fullChunks = Set.of();
        status = "Discovery cancelled. No spawners changed.";
    }

    public static List<Entry> matches(SpawnerSpec reference) {
        if (busy || !current() || reference == null) return List.of();
        return entries.values().stream()
                .filter(e -> !(e.dimension().equals(sourceDimension) && e.pos().equals(sourcePos)))
                .filter(e -> SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL
                        ? e.spec().sameAs(reference) : e.spec().sameMobs(reference))
                .sorted(Comparator.comparing(Entry::dimension)
                        .thenComparingInt(e -> e.pos().getX()).thenComparingInt(e -> e.pos().getY())
                        .thenComparingInt(e -> e.pos().getZ()))
                .toList();
    }

    /** Returns an error, or null when the explicit teleport command was submitted. */
    public static String teleport(Entry entry) {
        Minecraft mc = Minecraft.getInstance();
        if (!current() || mc.player == null) return "World changed. Discover again.";
        if (!Delivery.canUse("execute") || !Delivery.canUse("tp")) return "Teleport requires operator permission.";
        if (CommandQueue.busy()) return "Wait for spawner updates to finish.";
        if (!entry.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "Unknown dimension.";
        BlockPos p = entry.pos();
        var returnTo = mc.gui.screen();
        Object connection = mc.getConnection();
        mc.getConnection().sendCommand("execute in " + entry.dimension() + " run tp @s "
                + (p.getX() + .5) + " " + (p.getY() + 1.0) + " " + (p.getZ() + .5));
        if (!mc.level.dimension().identifier().toString().equals(entry.dimension()))
            restoreAfterTravel(entry, returnTo, connection, 0);
        return null;
    }

    private static void restoreAfterTravel(Entry target, net.minecraft.client.gui.screens.Screen screen, Object connection, int attempt) {
        Minecraft mc = Minecraft.getInstance();
        if (screen == null || attempt >= 150 || mc.getConnection() != connection || !current()) return;
        if (screen instanceof SpawnerBatchScreen browser && browser.dismissed) return;
        var shown = mc.gui.screen();
        if (shown != null && shown != screen && !(shown instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
                && !(shown instanceof net.minecraft.client.gui.screens.GenericMessageScreen)) return;
        boolean arrived = mc.level != null && mc.player != null
                && mc.level.dimension().identifier().toString().equals(target.dimension())
                && mc.player.blockPosition().distSqr(target.pos()) < 64;
        if (arrived && shown == null) { mc.setScreenAndShow(screen); return; }
        if (arrived && shown == screen && attempt > 5) return;
        CompletableFuture.runAsync(() -> mc.execute(() -> restoreAfterTravel(target, screen, connection, attempt + 1)),
                CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    public static void start(Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (busy) return;
        if (server == null || mc.level == null) {
            status = "Whole-world discovery is available in singleplayer. Use nearby on this server.";
            done.run();
            return;
        }
        int gen = ++generation;
        world = server;
        sourceDimension = Session.spawnerDimension != null ? Session.spawnerDimension
                : mc.level.dimension().identifier().toString();
        sourcePos = Session.spawnerPos;
        busy = true;
        indexed = false;
        skipped = 0;
        entries.clear();
        fullChunks = Set.of();
        status = "Saving world snapshot…";
        Path root = server.getWorldPath(LevelResource.ROOT);
        // Flush the server's dirty chunks before reading disk. Ready-to-send chunks alone
        // miss recently visited chunks that are still resident but outside player tracking.
        server.submit(() -> {
            if (gen == generation) server.saveEverything(true, true, false);
        }).thenApplyAsync(ignored -> {
            if (gen == generation) status = "Reading saved regions…";
            return scan(root, gen);
        }).whenComplete((scan, error) -> mc.execute(() -> {
            if (gen != generation) return;
            if (server != mc.getSingleplayerServer()) { cancel(); status = "World changed. Discover again."; done.run(); return; }
            if (error != null) {
                busy = false;
                skipped++;
                status = "Discovery failed. Try again.";
                done.run();
                return;
            }
            // Serialization belongs to the server thread. Current loaded chunks replace disk
            // entries, including deleted blocks. Files and live chunks are a session snapshot,
            // not a transaction with world saving.
            server.execute(() -> {
                try {
                    Map<String, Entry> merged = new LinkedHashMap<>(scan.entries());
                    Set<String> full = new HashSet<>(scan.full());
                    Set<String> liveChunks = new HashSet<>();
                    Map<String, Entry> live = new LinkedHashMap<>();
                    int[] failed = {scan.skipped()};
                    for (var level : server.getAllLevels()) {
                        String dim = level.dimension().identifier().toString();
                        level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
                            String key = chunkKey(dim, chunk.getPos().x(), chunk.getPos().z());
                            liveChunks.add(key);
                            full.add(key);
                            for (var be : chunk.getBlockEntities().values()) {
                                if (!(be instanceof SpawnerBlockEntity)) continue;
                                try {
                                    Nbt.Compound raw = McNbt.fromGame(be.saveWithoutMetadata(server.registryAccess()));
                                    Entry entry = new Entry(dim, be.getBlockPos().immutable(), SpawnerSpec.fromTag(raw), raw.copy());
                                    live.put(entry.key(), entry);
                                } catch (RuntimeException ex) { failed[0]++; }
                            }
                        });
                    }
                    merged.values().removeIf(e -> liveChunks.contains(chunkKey(e.dimension(), e.pos().getX() >> 4, e.pos().getZ() >> 4)));
                    merged.putAll(live);
                    mc.execute(() -> {
                        if (gen != generation) return;
                        if (server != mc.getSingleplayerServer()) { cancel(); status = "World changed. Discover again."; done.run(); return; }
                        entries.putAll(merged);
                        fullChunks = Set.copyOf(full);
                        skipped = failed[0];
                        busy = false;
                        indexed = true;
                        status = entries.size() + " spawners · all saved dimensions + loaded chunks"
                                + (skipped > 0 ? " · " + skipped + " unread (partial)" : " · session snapshot");
                        done.run();
                    });
                } catch (RuntimeException overlayError) {
                    mc.execute(() -> {
                        if (gen != generation) return;
                        busy = false;
                        indexed = false;
                        skipped++;
                        status = "Could not read current spawners. Discover again.";
                        done.run();
                    });
                }
            });
        }));
    }

    private record Scan(Map<String, Entry> entries, Set<String> full, int skipped) {}

    private static Scan scan(Path root, int gen) {
        Map<String, Entry> found = new LinkedHashMap<>();
        Set<String> full = new HashSet<>();
        int unread = 0, regions = 0;
        try (var paths = Files.walk(root)) {
            List<Path> files = paths.filter(p -> p.getParent() != null
                    && p.getParent().getFileName().toString().equals("region")
                    && p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
            for (Path path : files) {
                if (gen != generation) break;
                status = "Reading saved regions… " + (++regions) + "/" + files.size();
                String relative = root.relativize(path.getParent().getParent()).toString().replace('\\', '/');
                String dim = relative.equals("DIM-1") ? "minecraft:the_nether"
                        : relative.equals("DIM1") ? "minecraft:the_end"
                        : relative.startsWith("dimensions/") ? relative.substring(11).replaceFirst("/", ":")
                        : relative.isEmpty() ? "minecraft:overworld" : null;
                if (dim == null) continue; // Do not index backups stored below the world root.
                String[] name = path.getFileName().toString().split("\\.");
                int regionX = Integer.parseInt(name[1]), regionZ = Integer.parseInt(name[2]);
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    int[] offsets = new int[1024];
                    for (int i = 0; i < offsets.length; i++) offsets[i] = file.readInt();
                    for (int i = 0; i < offsets.length; i++) {
                        if (gen != generation) break;
                        int offset = offsets[i];
                        if (offset == 0) continue;
                        try {
                            int sector = offset >>> 8, sectors = offset & 255;
                            if (sector < 2 || sectors == 0) throw new IOException("Invalid location");
                            file.seek((long) sector * 4096);
                            int length = file.readInt();
                            int compression = file.readUnsignedByte();
                            if (length < 1 || length > sectors * 4096 - 4 || compression < 1 || compression > 3)
                                throw new IOException("Unsupported region payload");
                            byte[] bytes = new byte[length - 1];
                            file.readFully(bytes);
                            InputStream raw = new ByteArrayInputStream(bytes);
                            InputStream stream = switch (compression) {
                                case 1 -> new GZIPInputStream(raw);
                                case 2 -> new InflaterInputStream(raw);
                                case 3 -> raw;
                                default -> throw new IOException("Compression");
                            };
                            Nbt.Compound chunk;
                            try (DataInputStream input = new DataInputStream(stream)) {
                                chunk = McNbt.fromGame(NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024)));
                            }
                            String chunkStatus = chunk.getString("Status", "");
                            if (chunkStatus.isEmpty() || chunk.getCompound("Level") != null) { unread++; continue; }
                            if (chunkStatus.equals("minecraft:full") || chunkStatus.equals("full"))
                                full.add(chunkKey(dim, regionX * 32 + (i & 31), regionZ * 32 + (i >> 5)));
                            Nbt.List bes = chunk.getList("block_entities");
                            if (bes == null) continue;
                            for (Nbt n : bes.items()) if (n instanceof Nbt.Compound data
                                    && data.getString("id", "").equals("minecraft:mob_spawner")) {
                                BlockPos pos = new BlockPos((int) data.getLong("x", 0), (int) data.getLong("y", 0), (int) data.getLong("z", 0));
                                Nbt.Compound definition = data.copy();
                                for (String metadata : new String[]{"keepPacked", "x", "y", "z", "id"})
                                    definition.remove(metadata); // Storage metadata, not part of the spawn definition.
                                Entry entry = new Entry(dim, pos, SpawnerSpec.fromTag(definition), data.copy());
                                found.put(entry.key(), entry);
                            }
                        } catch (Exception ex) { unread++; }
                    }
                } catch (IOException ex) { unread++; }
            }
        } catch (IOException ex) { unread++; }
        return new Scan(found, full, unread);
    }
}
