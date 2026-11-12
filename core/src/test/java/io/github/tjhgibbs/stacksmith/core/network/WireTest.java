package io.github.tjhgibbs.stacksmith.core.network;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @Test void fragmentedUnicodeRoundTrip() throws Exception {
        var original = Nbt.compound().put("op", "index").put("text", "🧱é".repeat(20_000));
        var frames = Wire.encode(97, original);
        assertTrue(frames.size() > 1);
        var reader = new Wire.Reader();
        for (int i = 0; i < frames.size() - 1; i++) assertNull(reader.accept(frames.get(i)));
        var result = reader.accept(frames.getLast());
        assertEquals(97, result.id()); assertEquals(original, result.body());
        assertEquals("hello", reader.accept(Wire.encode(98, Nbt.compound().put("op", "hello")).getFirst()).body().getString("op", ""));
    }
    @Test void boundsAndVersionAreCheckedBeforeParsing() {
        var reader = new Wire.Reader();
        assertThrows(IOException.class, () -> reader.accept(new byte[15]));
        assertThrows(IOException.class, () -> reader.accept(new byte[Wire.PART + 17]));
        var wrongVersion = Wire.encode(1, Nbt.compound()).getFirst();
        ByteBuffer.wrap(wrongVersion).putInt(42);
        assertThrows(IOException.class, () -> reader.accept(wrongVersion));
        var tooMany = Wire.encode(1, Nbt.compound()).getFirst();
        ByteBuffer.wrap(tooMany).putInt(12, Wire.MAX_PARTS + 1);
        assertThrows(IOException.class, () -> reader.accept(tooMany));
    }
    @Test void outOfOrderAndInterleavedMessagesAreRejectedAndReaderRecovers() throws Exception {
        var frames = Wire.encode(1, Nbt.compound().put("text", "a".repeat(Wire.PART * 2)));
        var reader = new Wire.Reader();
        assertThrows(IOException.class, () -> reader.accept(frames.get(1)));
        assertNull(reader.accept(frames.getFirst()));
        assertThrows(IOException.class, () -> reader.accept(Wire.encode(2, Nbt.compound()).getFirst()));
        assertNotNull(reader.accept(Wire.encode(3, Nbt.compound()).getFirst()));
    }
    @Test void excessivelyNestedDocumentsAreRejected() {
        Nbt.Compound root = Nbt.compound(), cursor = root;
        for (int i = 0; i < 100; i++) cursor = cursor.child("a");
        var frame = Wire.encode(1, root).getFirst();
        assertThrows(IOException.class, () -> new Wire.Reader().accept(frame));
    }
    @Test void oversizedDocumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(1, Nbt.compound().put("text", "a".repeat(Wire.MAX))));
    }
}
