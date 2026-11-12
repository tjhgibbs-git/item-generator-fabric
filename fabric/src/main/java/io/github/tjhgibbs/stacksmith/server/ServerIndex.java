package io.github.tjhgibbs.stacksmith.server;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.index.RegionCacheFile;
import io.github.tjhgibbs.stacksmith.core.index.RegionCacheFile.Stamp;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
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

import net.minecraft.server.MinecraftServer;

/** Server-owned snapshot. Disk reads never load or generate terrain. */
public final class ServerIndex {
    public record Entry(String dimension, BlockPos pos, SpawnerSpec spec, Nbt.Compound raw) {
        public String key() { return dimension + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ(); }
    }
    final Map<String, Entry> entries = new LinkedHashMap<>();
    private Set<String> fullChunks = Set.of();
    private Map<String, Region> regions = Map.of();
    volatile int generation;
    volatile String status = "";
    int regionsRead, regionsReused;
    private final MinecraftServer server;
    private final java.util.function.BooleanSupplier active;
    public ServerIndex(MinecraftServer server, java.util.function.BooleanSupplier active) { this.server = server; this.active = active; }
    private static String chunkKey(String dim, int x, int z) { return dim + ":" + x + ":" + z; }

    /**
     * Where a dimension's region files are. Since 26.1 every dimension, the vanilla three included,
     * is stored under {@code dimensions/<namespace>/<path>/region}; older worlds kept the overworld
     * in {@code region} and the Nether/End in {@code DIM-1}/{@code DIM1}.
     */
    static Path regionFolder(Path root, String dim) {
        Path current = root.resolve("dimensions/" + dim.replaceFirst(":", "/") + "/region");
        if (Files.isDirectory(current)) return current;
        String legacy = switch (dim) {
            case "minecraft:overworld" -> "region";
            case "minecraft:the_nether" -> "DIM-1/region";
            case "minecraft:the_end" -> "DIM1/region";
            default -> null;
        };
        return legacy != null && Files.isDirectory(root.resolve(legacy)) ? root.resolve(legacy) : current;
    }
    public boolean safeToLoad(String dim, ChunkPos pos) {
        for (int x = pos.x() - 11; x <= pos.x() + 11; x++)
            for (int z = pos.z() - 11; z <= pos.z() + 11; z++)
                if (!fullChunks.contains(chunkKey(dim, x, z))) return false;
        return true;
    }
    public void cancel() { generation++; }
    /** Called on the server thread; futures finish on that thread too. */
    public void refresh(java.util.function.Consumer<String> done) {
        int gen = ++generation;
        // Flush before reading existing region files. Live chunks overlay the disk snapshot below.
        server.saveEverything(true, true, false);
        Path root = server.getWorldPath(LevelResource.ROOT);
        Map<String, Path> folders = new LinkedHashMap<>();
        for (var level : server.getAllLevels()) {
            String dim = level.dimension().identifier().toString();
            folders.put(dim, regionFolder(root, dim));
        }
        Map<String, Region> previous = regions;
        regionsRead = regionsReused = 0;
        CompletableFuture.supplyAsync(() -> scan(root, gen, previous, folders)).whenComplete((result, error) -> server.execute(() -> {
            if (generation != gen) return;
            if (!active.getAsBoolean()) { done.accept("Session ended."); return; }
            if (error != null) { done.accept("Could not scan saved regions."); return; }
            try {
                Map<String, Entry> merged = result.entries();
                Set<String> full = result.full();
                Set<String> liveChunks = new HashSet<>();
                Map<String, Entry> live = new LinkedHashMap<>();
                for (var level : server.getAllLevels()) {
                    String dim = level.dimension().identifier().toString();
                    level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
                        String key = chunkKey(dim, chunk.getPos().x(), chunk.getPos().z());
                        full.add(key); liveChunks.add(key);
                        for (var be : chunk.getBlockEntities().values()) if (be instanceof SpawnerBlockEntity) {
                            Entry e = entry(dim, be.getBlockPos(), McNbt.fromGame(be.saveWithoutMetadata(server.registryAccess())));
                            live.put(e.key(), e);
                        }
                    });
                }
                merged.values().removeIf(e -> liveChunks.contains(chunkKey(e.dimension(), e.pos().getX() >> 4, e.pos().getZ() >> 4)));
                merged.putAll(live);
                entries.clear(); entries.putAll(merged); fullChunks = Set.copyOf(full); regions = result.regions();
                done.accept(result.skipped() == 0 ? "" : result.skipped() + " chunks unread; results are partial.");
            } catch (RuntimeException ex) { done.accept("Could not overlay current server chunks."); }
        }));
    }
    public void recordUpdated(Entry before, Nbt.Compound after) { Entry fresh = entry(before.dimension(), before.pos(), after); entries.put(fresh.key(), fresh); }
    public void recordMissing(Entry before) { entries.remove(before.key()); }
    private record Region(Stamp stamp, Map<String, Entry> entries, Set<String> full, int skipped) {}
    private record Scan(Map<String, Region> regions, long savedAt, boolean cached) {
        Map<String, Entry> entries() { Map<String, Entry> out = new LinkedHashMap<>(); regions.values().forEach(r -> out.putAll(r.entries())); return out; }
        Set<String> full() { Set<String> out = new HashSet<>(); regions.values().forEach(r -> out.addAll(r.full())); return out; }
        int skipped() { return regions.values().stream().mapToInt(Region::skipped).sum(); }
    }

    private static Scan merge(Map<String, Region> regions, long savedAt, boolean cached) {
        return new Scan(Map.copyOf(regions), savedAt, cached);
    }

    private Scan scan(Path root, int gen, Map<String, Region> previous, Map<String, Path> folders) {
        Map<String, Region> next = new LinkedHashMap<>();
        int checked = 0;
        try {
            Map<Path, String> dimensions = new LinkedHashMap<>();
            for (var folder : folders.entrySet()) {
                String dim = folder.getKey();
                Path region = folder.getValue();
                if (region != null && Files.isDirectory(region)) try (var paths = Files.list(region)) {
                    paths.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                            .sorted().forEach(p -> dimensions.put(p, dim));
                }
            }
            List<Path> files = List.copyOf(dimensions.keySet());
            for (Path path : files) {
                if (gen != generation) throw new java.util.concurrent.CancellationException();
                String dim = dimensions.get(path);
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
        return scan;
    }

    private Region readRegion(Path path, String dim, Stamp stamp, int gen) {
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

}
