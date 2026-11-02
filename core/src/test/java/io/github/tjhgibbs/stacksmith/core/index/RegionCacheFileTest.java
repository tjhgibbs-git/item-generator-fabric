package io.github.tjhgibbs.stacksmith.core.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RegionCacheFileTest {
    @TempDir Path root;
    private static final int MAGIC = 0x53534958;

    @Test void savedIndexRoundTripsAcrossIndependentReadsAndRejectsOldSchemaOrCorruption() throws Exception {
        Path file = root.resolve("cache/index.gz");
        String mob = "{id: \"minecraft:zombie\", Tags: [\"CTM\\u0000custom\"]}".repeat(2000);
        byte[] payload = mob.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RegionCacheFile.write(file, MAGIC, 1, () -> true, out -> { out.writeInt(payload.length); out.write(payload); });
        assertArrayEquals(payload, RegionCacheFile.read(file, MAGIC, 1, in -> in.readNBytes(in.readInt())));
        assertThrows(IOException.class, () -> RegionCacheFile.read(file, MAGIC, 2, in -> in.readInt()));
        byte[] corrupt = Files.readAllBytes(file); corrupt[corrupt.length / 2] ^= 0x7f; Files.write(file, corrupt);
        assertThrows(IOException.class, () -> RegionCacheFile.read(file, MAGIC, 1, in -> in.readAllBytes()));
    }

    @Test void cancelledOrFailedUpdatePreservesPreviousCacheAndCleansTemporaryFiles() throws Exception {
        Path file = root.resolve("index.gz");
        RegionCacheFile.write(file, MAGIC, 1, () -> true, out -> out.writeInt(42));
        RegionCacheFile.write(file, MAGIC, 1, () -> false, out -> out.writeInt(99));
        assertEquals(42, (int) RegionCacheFile.read(file, MAGIC, 1, in -> in.readInt()));
        assertThrows(IOException.class, () -> RegionCacheFile.write(file, MAGIC, 1, () -> true, out -> {
            out.writeInt(100); throw new IOException("Disk failed during cache update");
        }));
        assertEquals(42, (int) RegionCacheFile.read(file, MAGIC, 1, in -> in.readInt()));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void cheapRegionFingerprintDetectsHeaderChangesAtIdenticalSizeAndTimestamp() throws Exception {
        Path unchanged = root.resolve("r.0.0.mca"), changed = root.resolve("r.1.0.mca");
        Files.write(unchanged, new byte[12288]); Files.write(changed, new byte[12288]);
        var first = RegionCacheFile.stamp(unchanged);
        var before = RegionCacheFile.stamp(changed);
        var time = Files.getLastModifiedTime(changed);
        byte[] bytes = Files.readAllBytes(changed); bytes[4096] = 1;
        Files.write(changed, bytes); Files.setLastModifiedTime(changed, time);
        assertEquals(first, RegionCacheFile.stamp(unchanged));
        assertNotEquals(before, RegionCacheFile.stamp(changed));
        Files.delete(changed);
        assertThrows(IOException.class, () -> RegionCacheFile.stamp(changed));
    }

    @Test void preciseModificationTimeDetectsPayloadChangesWithinOneTimestampSecond() throws Exception {
        Path region = root.resolve("r.0.0.mca"); Files.write(region, new byte[12288]);
        Files.setLastModifiedTime(region, FileTime.from(1_000_000_001L, TimeUnit.NANOSECONDS));
        var before = RegionCacheFile.stamp(region);
        byte[] data = Files.readAllBytes(region); data[9000] = 7; Files.write(region, data);
        Files.setLastModifiedTime(region, FileTime.from(1_000_000_999L, TimeUnit.NANOSECONDS));
        assertNotEquals(before, RegionCacheFile.stamp(region));
    }
}
