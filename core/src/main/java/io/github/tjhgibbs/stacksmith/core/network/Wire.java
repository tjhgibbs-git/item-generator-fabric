package io.github.tjhgibbs.stacksmith.core.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.tjhgibbs.stacksmith.core.nbt.*;

/** Versioned, bounded framing for the optional Fabric world protocol. */
public final class Wire {
    private Wire() {}
    public static final String CHANNEL = "stacksmith:world_v1";
    public static final int VERSION = 1, PART = 24_000, MAX = 8 * 1024 * 1024;
    public static final int MAX_PARTS = (MAX + PART - 1) / PART;
    public static List<byte[]> encode(int id, Nbt.Compound message) {
        byte[] bytes = Snbt.write(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX) throw new IllegalArgumentException("Stacksmith message too large");
        int count = Math.max(1, (bytes.length + PART - 1) / PART);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream data = new DataOutputStream(buffer);
                data.writeInt(VERSION); data.writeInt(id); data.writeInt(i); data.writeInt(count);
                data.write(bytes, i * PART, Math.min(PART, bytes.length - i * PART));
                out.add(buffer.toByteArray());
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        return out;
    }
    public record Message(int id, Nbt.Compound body) {}
    /** One in-flight document per connection; sequential frames avoid unbounded partial maps. */
    public static final class Reader {
        private int id, next, count;
        private long started;
        private ByteArrayOutputStream bytes;
        public void clear() { bytes = null; next = count = 0; }
        public Message accept(byte[] frame) throws IOException {
            try {
                if (frame.length < 16 || frame.length > PART + 16) throw new IOException("Invalid frame size");
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame));
                if (input.readInt() != VERSION) throw new IOException("Unsupported protocol");
                int request = input.readInt(), part = input.readInt(), parts = input.readInt();
                if (parts < 1 || parts > MAX_PARTS || part < 0 || part >= parts) throw new IOException("Invalid parts");
                if (part == 0) {
                    if (bytes != null) throw new IOException("Interleaved message");
                    id = request; count = parts; next = 0; started = System.currentTimeMillis();
                    bytes = new ByteArrayOutputStream();
                }
                if (bytes == null || request != id || count != parts || part != next++ || System.currentTimeMillis() - started > 30_000)
                    throw new IOException("Out-of-order or expired message");
                bytes.write(input.readAllBytes());
                if (bytes.size() > MAX) throw new IOException("Message too large");
                if (next < count) return null;
                String text = bytes.toString(StandardCharsets.UTF_8);
                clear();
                // Bound nesting before invoking the recursive SNBT parser.
                depth(text);
                return new Message(request, Snbt.parseCompound(text));
            } catch (RuntimeException | SnbtException ex) { clear(); throw new IOException("Invalid message", ex); }
              catch (IOException ex) { clear(); throw ex; }
        }
        private static void depth(String text) throws IOException {
            int depth = 0; char quote = 0; boolean escape = false;
            for (char c : text.toCharArray()) {
                if (quote != 0) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == quote) quote = 0; }
                else if (c == '\"' || c == '\'') quote = c;
                else if ((c == '{' || c == '[') && ++depth > 64) throw new IOException("Message too deeply nested");
                else if (c == '}' || c == ']') depth--;
            }
        }
    }
}
