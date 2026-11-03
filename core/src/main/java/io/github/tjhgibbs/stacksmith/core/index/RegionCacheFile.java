package io.github.tjhgibbs.stacksmith.core.index;

import java.io.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Disposable index storage: versioned reads and replace-only-after-success writes. */
public final class RegionCacheFile {
    private RegionCacheFile() {}
    public record Stamp(long size, long modified, long header) {}
    @FunctionalInterface public interface Reader<T> { T read(DataInputStream in) throws IOException; }
    @FunctionalInterface public interface Writer { void write(DataOutputStream out) throws IOException; }

    /** Cheap fingerprint: both region tables, precise modification time and payload length. */
    public static Stamp stamp(Path path) throws IOException {
        var before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        byte[] header = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            if (in.readNBytes(header, 0, header.length) != header.length) throw new EOFException("Region header");
        }
        var after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Region changed while checking it");
        CRC32 crc = new CRC32(); crc.update(header);
        return new Stamp(after.size(), after.lastModifiedTime().to(TimeUnit.NANOSECONDS), crc.getValue());
    }

    public static <T> T read(Path file, int magic, int version, Reader<T> reader) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != magic || in.readInt() != version) throw new IOException("Index version");
            T value = reader.read(in);
            if (in.read() != -1) throw new IOException("Trailing index data");
            return value;
        }
    }

    /** A cancelled or failed write leaves the previous cache intact. */
    public static void write(Path target, int magic, int version, BooleanSupplier current, Writer writer) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "spawner-index-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                out.writeInt(magic); out.writeInt(version); writer.write(out);
            }
            if (!current.getAsBoolean()) return;
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
