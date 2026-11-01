package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.container.ChestSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.*;

/**
 * Session index: saved chunks in singleplayer, loaded chunks on remote servers. Never generates
 * terrain.
 */
public final class ChestIndex {
    private ChestIndex() {}

    public record Entry(String dimension, BlockPos pos, String state, ChestSpec chest) {
        String where() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }

        String key() {
            return dimension + ":" + where();
        }

        String searchText() {
            return dimension + " " + where() + " " + state + " " + chest.data();
        }
    }

    static final Map<String, Entry> entries = new LinkedHashMap<>();
    static String status = "Index the world to find containers by type, item or coordinates.";
    static boolean busy;
    private static Object world;
    private static int generation;
    private static final Deque<BlockPos> pending = new ArrayDeque<>();
    private static boolean reading;
    private static long deadline;
    private static int unread;

    static String dimension() {
        return Minecraft.getInstance().level.dimension().identifier().toString();
    }

    static String block(BlockEntity be) {
        return BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
    }

    static boolean supported(BlockEntity be) {
        return be != null && ChestSpec.supported(block(be));
    }

    static String state(BlockEntity be) {
        String s = be.getBlockState().toString();
        // BlockState.toString is Block{minecraft:chest}[facing=north,...].
        int end = s.indexOf('}');
        return block(be) + (end >= 0 ? s.substring(end + 1) : "");
    }

    static Entry entry(BlockEntity be, Nbt.Compound data) {
        return new Entry(
                dimension(),
                be.getBlockPos().immutable(),
                state(be),
                new ChestSpec(block(be), data));
    }

    static void load(Entry entry, java.util.function.Consumer<Entry> done) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !dimension().equals(entry.dimension()) || busy) return;
        BlockEntity be = mc.level.getBlockEntity(entry.pos());
        if (!supported(be) || !Delivery.canUse("data") || reading || SpawnerBatch.searching())
            return;
        WorldSpawner.read(
                be,
                data -> {
                    Entry fresh = entry(be, data);
                    entries.put(fresh.key(), fresh);
                    done.accept(fresh);
                });
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || busy || SpawnerBatch.searching()) return;
        Object current =
                mc.getSingleplayerServer() != null
                        ? mc.getSingleplayerServer()
                        : mc.getConnection();
        if (world != current) {
            entries.clear();
            world = current;
        }
        int gen = ++generation;
        busy = true;
        unread = 0;
        status = "Reading saved chunks…";
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            CompletableFuture.supplyAsync(() -> scan(root))
                    .whenComplete(
                            (result, error) ->
                                    mc.execute(
                                            () -> {
                                                if (gen != generation) return;
                                                if (error == null) {
                                                    entries.clear();
                                                    entries.putAll(result.entries);
                                                    unread = result.skipped;
                                                } else unread++;
                                                loaded();
                                            }));
        } else loaded();
    }

    private static void loaded() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            busy = false;
            return;
        }
        // Integrated server: full, current inventory data, including chunks changed since disk
        // save.
        if (mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            int gen = generation;
            server.execute(
                    () -> {
                        Map<String, Entry> live = new LinkedHashMap<>();
                        Set<String> loadedChunks = new HashSet<>();
                        for (var level : server.getAllLevels())
                            level.getChunkSource()
                                    .chunkMap
                                    .forEachReadyToSendChunk(
                                            chunk -> {
                                                loadedChunks.add(
                                                        level.dimension().identifier()
                                                                + ":"
                                                                + chunk.getPos().x()
                                                                + ":"
                                                                + chunk.getPos().z());
                                                for (BlockEntity be :
                                                        chunk.getBlockEntities().values())
                                                    if (supported(be)) {
                                                        Entry e =
                                                                new Entry(
                                                                        level.dimension()
                                                                                .identifier()
                                                                                .toString(),
                                                                        be.getBlockPos()
                                                                                .immutable(),
                                                                        state(be),
                                                                        new ChestSpec(
                                                                                block(be),
                                                                                McNbt.fromGame(
                                                                                        be
                                                                                                .saveWithoutMetadata(
                                                                                                        server
                                                                                                                .registryAccess()))));
                                                        live.put(e.key(), e);
                                                    }
                                            });
                        mc.execute(
                                () -> {
                                    if (gen == generation) {
                                        entries.values()
                                                .removeIf(
                                                        e ->
                                                                loadedChunks.contains(
                                                                        e.dimension()
                                                                                + ":"
                                                                                + (e.pos().getX()
                                                                                        >> 4)
                                                                                + ":"
                                                                                + (e.pos().getZ()
                                                                                        >> 4)));
                                        entries.putAll(live);
                                        finish();
                                    }
                                });
                    });
            return;
        }
        // Remote: only loaded chunks are visible to a client. Full data requires operator queries.
        if (!Delivery.canUse("data")) {
            status = "Reading contents requires /data permission on this server.";
            busy = false;
            return;
        }
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        int distance = mc.options.getEffectiveRenderDistance() + 1;
        pending.clear();
        for (int x = cx - distance; x <= cx + distance; x++)
            for (int z = cz - distance; z <= cz + distance; z++) {
                if (mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)
                        instanceof LevelChunk chunk)
                    for (BlockEntity be : chunk.getBlockEntities().values())
                        if (supported(be)) pending.add(be.getBlockPos().immutable());
            }
        tick();
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != null
                && world
                        != (mc.getSingleplayerServer() != null
                                ? mc.getSingleplayerServer()
                                : mc.getConnection())) {
            generation++;
            entries.clear();
            pending.clear();
            busy = false;
            reading = false;
            world = null;
            Session.chestLink = null;
        }
        if (world == null && mc.level != null)
            world =
                    mc.getSingleplayerServer() != null
                            ? mc.getSingleplayerServer()
                            : mc.getConnection();
        if (!busy || mc.getSingleplayerServer() != null) return;
        if (reading) {
            if (System.currentTimeMillis() < deadline) return;
            reading = false;
            unread++;
        }
        if (pending.isEmpty()) {
            finish();
            return;
        }
        BlockPos pos = pending.poll();
        BlockEntity be = mc.level == null ? null : mc.level.getBlockEntity(pos);
        if (!supported(be)) return;
        reading = true;
        deadline = System.currentTimeMillis() + 3000;
        int gen = generation;
        mc.getConnection()
                .getDebugQueryHandler()
                .queryBlockEntityTag(
                        pos,
                        tag ->
                                mc.execute(
                                        () -> {
                                            if (gen != generation) return;
                                            if (tag != null) {
                                                Entry e = entry(be, McNbt.fromGame(tag));
                                                entries.put(e.key(), e);
                                            } else unread++;
                                            reading = false;
                                        }));
        status = "Reading loaded containers… " + pending.size() + " left";
    }

    private static void finish() {
        busy = false;
        status =
                entries.size()
                        + " containers indexed · "
                        + (Minecraft.getInstance().getSingleplayerServer() != null
                                ? "saved world + loaded chunks"
                                : "loaded chunks only; re-index after exploring")
                        + (unread > 0 ? " · " + unread + " chunks/queries unread" : "");
    }

    private record Scan(Map<String, Entry> entries, int skipped) {}

    private static Scan scan(Path root) {
        Map<String, Entry> found = new LinkedHashMap<>();
        int skipped = 0;
        try (var paths = Files.walk(root)) {
            for (Path path :
                    paths.filter(
                                    p ->
                                            p.getParent().getFileName().toString().equals("region")
                                                    && p.toString().endsWith(".mca"))
                            .toList()) {
                String relative =
                        root.relativize(path.getParent().getParent()).toString().replace('\\', '/');
                String dim =
                        relative.equals("DIM-1")
                                ? "minecraft:the_nether"
                                : relative.equals("DIM1")
                                        ? "minecraft:the_end"
                                        : relative.startsWith("dimensions/")
                                                ? relative.substring(11).replaceFirst("/", ":")
                                                : "minecraft:overworld";
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    int[] offsets = new int[1024];
                    for (int i = 0; i < 1024; i++) offsets[i] = file.readInt();
                    for (int offset : offsets) {
                        if (offset == 0) continue;
                        try {
                            file.seek((long) (offset >>> 8) * 4096);
                            int len = file.readInt();
                            int compression = file.readUnsignedByte();
                            if (len < 1 || len > 16 * 1024 * 1024 || compression > 3) {
                                skipped++;
                                continue;
                            }
                            byte[] bytes = new byte[len - 1];
                            file.readFully(bytes);
                            InputStream raw = new ByteArrayInputStream(bytes);
                            InputStream stream =
                                    switch (compression) {
                                        case 1 -> new GZIPInputStream(raw);
                                        case 2 -> new InflaterInputStream(raw);
                                        case 3 -> raw;
                                        default -> throw new IOException("compression");
                                    };
                            Nbt.Compound chunk;
                            try (var input = new DataInputStream(stream)) {
                                chunk =
                                        McNbt.fromGame(
                                                NbtIo.read(
                                                        input,
                                                        NbtAccounter.create(64L * 1024 * 1024)));
                            }
                            Nbt.List bes = chunk.getList("block_entities");
                            if (bes == null) continue;
                            for (Nbt n : bes.items())
                                if (n instanceof Nbt.Compound data) {
                                    String id = data.getString("id", "");
                                    if (!ChestSpec.supported(id)) continue;
                                    BlockPos pos =
                                            new BlockPos(
                                                    (int) data.getLong("x", 0),
                                                    (int) data.getLong("y", 0),
                                                    (int) data.getLong("z", 0));
                                    String block = paletteBlock(chunk, pos, id);
                                    Entry e =
                                            new Entry(
                                                    dim,
                                                    pos,
                                                    block,
                                                    new ChestSpec(block.split("\\[")[0], data));
                                    found.put(e.key(), e);
                                }
                        } catch (Exception e) {
                            skipped++;
                        }
                    }
                } catch (IOException e) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            skipped++;
        }
        return new Scan(found, skipped);
    }

    private static String paletteBlock(Nbt.Compound chunk, BlockPos pos, String fallback) {
        Nbt.List sections = chunk.getList("sections");
        if (sections == null) return fallback;
        for (Nbt n : sections.items())
            if (n instanceof Nbt.Compound section
                    && section.getLong("Y", -999) == (pos.getY() >> 4)) {
                Nbt.Compound states = section.getCompound("block_states");
                if (states == null) break;
                Nbt.List palette = states.getList("palette");
                if (palette == null || palette.isEmpty()) break;
                int index = 0;
                if (palette.size() > 1 && states.get("data") instanceof Nbt.Arr packed) {
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                    int per = 64 / bits;
                    int at =
                            ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
                    index =
                            (int)
                                    ((packed.get(at / per) >>> ((at % per) * bits))
                                            & ((1L << bits) - 1));
                }
                Nbt.Compound p = (Nbt.Compound) palette.get(index);
                String block = p.getString("Name", fallback);
                Nbt.Compound props = p.getCompound("Properties");
                if (props != null && !props.isEmpty())
                    block +=
                            "["
                                    + String.join(
                                            ",",
                                            props.entries().stream()
                                                    .map(
                                                            e ->
                                                                    e.getKey()
                                                                            + "="
                                                                            + ((Nbt.Str)
                                                                                            e
                                                                                                    .getValue())
                                                                                    .value())
                                                    .toList())
                                    + "]";
                return block;
            }
        return fallback;
    }
}
