package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.index.RegionCacheFile;
import io.github.tjhgibbs.stacksmith.core.index.RegionCacheFile.Stamp;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
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
    private static Map<String, Region> regions = Map.of();
    static volatile int regionsRead, regionsReused;
    static long savedAt;
    private static volatile String cacheWarning = "";
    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE = "stacksmith/spawner-index-v1.bin.gz";
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
        status = indexed ? "Update cancelled · previous saved results retained" : "Discovery cancelled. No spawners changed.";
    }

    public static List<Entry> matches(SpawnerSpec reference) {
        return matches(reference, Session.spawnerDimension, Session.spawnerPos);
    }

    public static List<Entry> matches(SpawnerSpec reference, String sourceDimension, BlockPos sourcePos) {
        if (!current() || reference == null) return List.of();
        return entries.values().stream()
                .filter(e -> !(e.dimension().equals(sourceDimension) && e.pos().equals(sourcePos)))
                .filter(e -> SpawnerBatch.match == SpawnerBatch.Match.IDENTICAL
                        ? e.spec().sameAs(reference) : e.spec().sameMobs(reference))
                .sorted(Comparator.comparing(Entry::dimension)
                        .thenComparingInt(e -> e.pos().getX()).thenComparingInt(e -> e.pos().getY())
                        .thenComparingInt(e -> e.pos().getZ()))
                .toList();
    }

    /** Update the displayed snapshot after a verified write; disk region caches remain independent. */
    public static void recordUpdated(Entry previous, Nbt.Compound after) {
        Object expected = world;
        Nbt.Compound copied = after.copy();
        Minecraft.getInstance().execute(() -> {
            if (expected != world || !current()) return;
            Entry fresh = entry(previous.dimension(), previous.pos(), copied);
            entries.put(fresh.key(), fresh);
        });
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

    /** Reopen saved results without saving or rescanning this world each time. */
    public static void ensure(Runnable done) { refresh(false, done); }

    /** Explicit update: flush dirty world data, then read only changed region files. */
    public static void start(Runnable done) { refresh(true, done); }

    private static void refresh(boolean forceUpdate, Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (busy) return;
        if (server == null || mc.level == null) {
            status = "Whole-world discovery is available in singleplayer. Use nearby on this server.";
            done.run();
            return;
        }
        boolean sameWorld = world == server;
        if (!sameWorld) {
            generation++;
            entries.clear();
            regions = Map.of();
            fullChunks = Set.of();
            indexed = false;
            savedAt = 0;
        }
        int gen = ++generation;
        world = server;
        busy = true;
        regionsRead = regionsReused = 0;
        cacheWarning = "";
        status = indexed && !forceUpdate ? "Refreshing live spawners…" : "Opening saved spawner index…";
        Path root = server.getWorldPath(LevelResource.ROOT);
        Map<String, Region> previous = regions;
        boolean reuseMemory = sameWorld && indexed && !forceUpdate;
        Map<String, Entry> remembered = reuseMemory ? new LinkedHashMap<>(entries) : null;
        Set<String> rememberedFull = fullChunks;
        CompletableFuture<Scan> read;
        if (reuseMemory) {
            regionsReused = previous.size();
            read = CompletableFuture.completedFuture(merge(previous, savedAt, false));
        } else {
            read = CompletableFuture.supplyAsync(() -> {
                if (!forceUpdate && !sameWorld) {
                    Scan cached = loadCache(root);
                    if (cached.cached()) mc.execute(() -> {
                        if (gen != generation || server != mc.getSingleplayerServer()) return;
                        entries.clear(); entries.putAll(cached.entries());
                        regions = cached.regions(); fullChunks = Set.copyOf(cached.full());
                        savedAt = cached.savedAt(); skipped = cached.skipped(); indexed = true;
                        // Cached rows are immediately usable for browsing while file fingerprints
                        // are checked; writes remain disabled until the refresh completes.
                        done.run();
                    });
                    return cached;
                }
                return new Scan(previous, savedAt, false);
            }).thenCompose(cached -> {
                if (gen != generation) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
                boolean saveFirst = forceUpdate || !cached.cached();
                CompletableFuture<Void> saved = saveFirst ? server.submit(() -> {
                    if (gen == generation) { status = "Saving world snapshot…"; server.saveEverything(true, true, false); }
                }) : CompletableFuture.completedFuture(null);
                return saved.thenApplyAsync(ignored -> {
                    if (gen != generation) throw new java.util.concurrent.CancellationException();
                    return scan(root, gen, cached.regions());
                });
            });
        }
        read.whenComplete((scan, error) -> mc.execute(() -> {
            if (gen != generation) return;
            if (server != mc.getSingleplayerServer()) { cancel(); status = "World changed. Open this world's index again."; done.run(); return; }
            if (error != null) {
                busy = false;
                status = indexed ? "Update failed · previous saved results retained" : "Could not open the index. Try Update index.";
                done.run();
                return;
            }
            // Serialization belongs to the server thread. Current loaded chunks replace disk
            // entries, including deleted blocks. Files and live chunks are a session snapshot,
            // not a transaction with world saving.
            server.execute(() -> {
                try {
                    Map<String, Entry> merged = new LinkedHashMap<>(remembered != null ? remembered : scan.entries());
                    Set<String> full = new HashSet<>(remembered != null ? rememberedFull : scan.full());
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
                        entries.clear();
                        entries.putAll(merged);
                        regions = scan.regions();
                        savedAt = scan.savedAt();
                        fullChunks = Set.copyOf(full);
                        skipped = failed[0];
                        busy = false;
                        indexed = true;
                        status = entries.size() + " spawners · " + regionsRead + " regions read · " + regionsReused + " reused"
                                + (skipped > 0 ? " · " + skipped + " unread (partial)" : " · saved index")
                                + cacheWarning;
                        done.run();
                    });
                } catch (RuntimeException overlayError) {
                    mc.execute(() -> {
                        if (gen != generation) return;
                        busy = false;
                        status = indexed ? "Could not refresh live spawners · saved results retained" : "Could not read current spawners. Try Update index.";
                        done.run();
                    });
                }
            });
        }));
    }

    private record Region(Stamp stamp, Map<String, Entry> entries, Set<String> full, int skipped) {}
    private record Scan(Map<String, Region> regions, long savedAt, boolean cached) {
        Map<String, Entry> entries() { Map<String, Entry> out = new LinkedHashMap<>(); regions.values().forEach(r -> out.putAll(r.entries())); return out; }
        Set<String> full() { Set<String> out = new HashSet<>(); regions.values().forEach(r -> out.addAll(r.full())); return out; }
        int skipped() { return regions.values().stream().mapToInt(Region::skipped).sum(); }
    }

    private static Scan merge(Map<String, Region> regions, long savedAt, boolean cached) {
        return new Scan(Map.copyOf(regions), savedAt, cached);
    }

    private static Scan scan(Path root, int gen, Map<String, Region> previous) {
        Map<String, Region> next = new LinkedHashMap<>();
        int checked = 0;
        try (var paths = Files.walk(root)) {
            List<Path> files = paths.filter(p -> p.getParent() != null
                    && p.getParent().getFileName().toString().equals("region")
                    && p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
            for (Path path : files) {
                if (gen != generation) throw new java.util.concurrent.CancellationException();
                String relative = root.relativize(path.getParent().getParent()).toString().replace('\\', '/');
                String dim = relative.equals("DIM-1") ? "minecraft:the_nether"
                        : relative.equals("DIM1") ? "minecraft:the_end"
                        : relative.startsWith("dimensions/") ? relative.substring(11).replaceFirst("/", ":")
                        : relative.isEmpty() ? "minecraft:overworld" : null;
                if (dim == null) continue;
                String key = root.relativize(path).toString().replace('\\', '/');
                status = "Checking regions… " + (++checked) + "/" + files.size();
                try {
                    Stamp before = RegionCacheFile.stamp(path);
                    Region old = previous.get(key);
                    if (old != null && old.skipped() == 0 && old.stamp().equals(before)) {
                        next.put(key, old); regionsReused++; continue;
                    }
                    regionsRead++;
                    Region parsed = readRegion(path, dim, before, gen);
                    // A region rewritten during the read is not eligible for reuse next time.
                    if (!before.equals(RegionCacheFile.stamp(path))) parsed = new Region(before, parsed.entries(), parsed.full(), parsed.skipped() + 1);
                    next.put(key, parsed);
                } catch (IOException ex) {
                    // Do not retain stale entries/full-chunk safety claims for an unread region.
                    next.put(key, new Region(new Stamp(-1, -1, -1), Map.of(), Set.of(), 1));
                }
            }
        } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
        Scan scan = merge(next, System.currentTimeMillis(), true);
        if (gen != generation) throw new java.util.concurrent.CancellationException();
        try { saveCache(root, scan, gen); }
        catch (IOException ex) { if (gen == generation) cacheWarning = " · cache could not be saved; results in memory only"; }
        return scan;
    }

    private static Region readRegion(Path path, String dim, Stamp stamp, int gen) {
        Map<String, Entry> found = new LinkedHashMap<>();
        Set<String> full = new HashSet<>();
        int unread = 0;
        String[] name = path.getFileName().toString().split("\\.");
        int regionX = Integer.parseInt(name[1]), regionZ = Integer.parseInt(name[2]);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            int[] offsets = new int[1024];
            for (int i = 0; i < offsets.length; i++) offsets[i] = file.readInt();
            for (int i = 0; i < offsets.length; i++) {
                if (gen != generation) throw new java.util.concurrent.CancellationException();
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
                    byte[] bytes = new byte[length - 1]; file.readFully(bytes);
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
                        Entry entry = entry(dim, new BlockPos((int) data.getLong("x", 0), (int) data.getLong("y", 0), (int) data.getLong("z", 0)), data);
                        found.put(entry.key(), entry);
                    }
                } catch (IOException | RuntimeException ex) { unread++; }
            }
        } catch (IOException ex) { unread++; }
        return new Region(stamp, Map.copyOf(found), Set.copyOf(full), unread);
    }

    private static Entry entry(String dim, BlockPos pos, Nbt.Compound raw) {
        Nbt.Compound definition = raw.copy();
        for (String metadata : new String[]{"keepPacked", "x", "y", "z", "id"}) definition.remove(metadata);
        return new Entry(dim, pos, SpawnerSpec.fromTag(definition), raw.copy());
    }

    private static Scan loadCache(Path root) {
        Path file = root.resolve(CACHE_FILE);
        try {
            return RegionCacheFile.read(file, 0x53534958, CACHE_VERSION, in -> {
            long time = in.readLong();
            int count = bounded(in.readInt(), 100_000);
            Map<String, Region> cached = new LinkedHashMap<>();
            for (int r = 0; r < count; r++) {
                String key = in.readUTF();
                Stamp stamp = new Stamp(in.readLong(), in.readLong(), in.readLong());
                int unread = bounded(in.readInt(), 1_000_000);
                Set<String> full = new HashSet<>();
                int chunks = bounded(in.readInt(), 1024);
                for (int c = 0; c < chunks; c++) full.add(in.readUTF());
                Map<String, Entry> entries = new LinkedHashMap<>();
                int n = bounded(in.readInt(), 1_000_000);
                for (int e = 0; e < n; e++) {
                    String dim = in.readUTF();
                    BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
                    int length = bounded(in.readInt(), 16 * 1024 * 1024);
                    byte[] bytes = in.readNBytes(length);
                    if (bytes.length != length) throw new EOFException();
                    Entry entry;
                    try { entry = entry(dim, pos, Snbt.parseCompound(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))); }
                    catch (SnbtException invalid) { throw new IOException("Invalid cached spawner", invalid); }
                    entries.put(entry.key(), entry);
                }
                cached.put(key, new Region(stamp, Map.copyOf(entries), Set.copyOf(full), unread));
            }
            return merge(cached, time, true);
            });
        } catch (IOException | RuntimeException ex) {
            return merge(Map.of(), 0, false); // Missing/corrupt/old caches are disposable, never world data.
        }
    }

    private static int bounded(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid index count");
        return value;
    }

    private static void saveCache(Path root, Scan scan, int gen) throws IOException {
        Path target = root.resolve(CACHE_FILE);
        RegionCacheFile.write(target, 0x53534958, CACHE_VERSION, () -> gen == generation, out -> {
                out.writeLong(scan.savedAt());
                out.writeInt(scan.regions().size());
                for (var item : scan.regions().entrySet()) {
                    out.writeUTF(item.getKey()); Region region = item.getValue();
                    out.writeLong(region.stamp().size()); out.writeLong(region.stamp().modified()); out.writeLong(region.stamp().header());
                    out.writeInt(region.skipped()); out.writeInt(region.full().size());
                    for (String chunk : region.full()) out.writeUTF(chunk);
                    out.writeInt(region.entries().size());
                    for (Entry entry : region.entries().values()) {
                        out.writeUTF(entry.dimension()); out.writeInt(entry.pos().getX()); out.writeInt(entry.pos().getY()); out.writeInt(entry.pos().getZ());
                        byte[] raw = Snbt.write(entry.raw()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeInt(raw.length); out.write(raw);
                    }
                }
        });
    }
}
