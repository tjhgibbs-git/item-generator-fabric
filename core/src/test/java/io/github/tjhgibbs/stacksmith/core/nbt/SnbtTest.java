package io.github.tjhgibbs.stacksmith.core.nbt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SnbtTest {
    @Test
    void readsEveryNumberKind() throws SnbtException {
        assertEquals(Nbt.b(5), Snbt.parse("5b"));
        assertEquals(Nbt.s(-3), Snbt.parse("-3s"));
        assertEquals(Nbt.i(42), Snbt.parse("42"));
        assertEquals(Nbt.l(9_000_000_000L), Snbt.parse("9000000000L"));
        assertEquals(Nbt.f(1.5), Snbt.parse("1.5f"));
        assertEquals(Nbt.d(2.0), Snbt.parse("2.0"));
        assertEquals(Nbt.d(3), Snbt.parse("3d"));
        assertEquals(Nbt.d(0.5), Snbt.parse(".5"));
        assertEquals(Nbt.i(255), Snbt.parse("0xFF"));
        assertEquals(Nbt.b(-1), Snbt.parse("255ub"));
        assertEquals(Nbt.i(5), Snbt.parse("0b101"));
        assertEquals(Nbt.b(0), Snbt.parse("0b"));
        assertEquals(Nbt.i(1_000_000), Snbt.parse("1_000_000"));
        assertEquals(Nbt.bool(true), Snbt.parse("true"));
    }

    @Test
    void readsStructures() throws SnbtException {
        Nbt n = Snbt.parse("{id:\"minecraft:stone\", count: 2, 'odd key': [1, 2b, \"x\"], arr: [I; 1, 2, 3], e:{}, l:[]}");
        Nbt.Compound c = (Nbt.Compound) n;
        assertEquals("minecraft:stone", c.getString("id", null));
        assertEquals(2, c.getLong("count", 0));
        assertEquals(3, c.getList("odd key").size());
        assertEquals(new Nbt.Arr(Nbt.Num.Kind.INT, new long[]{1, 2, 3}), c.get("arr"));
        assertTrue(c.getCompound("e").isEmpty());
    }

    @Test
    void readsOperationsAndEscapes() throws SnbtException {
        assertEquals(Nbt.bool(true), Snbt.parse("bool(1)"));
        Nbt.Arr uuid = (Nbt.Arr) Snbt.parse("uuid(\"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\")");
        assertEquals(4, uuid.length());
        assertEquals(new Nbt.Str("a\"b\n\u00e9"), Snbt.parse("\"a\\\"b\\n\\u00e9\""));
        assertEquals(new Nbt.Str("it's"), Snbt.parse("'it\\'s'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{a:1,a:2}", "{a:1", "[1,2", "300b", "\"open", "{a 1}", "1.2.3", "{:1}", "[I; 1.5]", "foo(1)"})
    void rejectsBadInput(String text) {
        assertThrows(SnbtException.class, () -> Snbt.parse(text));
    }

    @Test
    void writesCompactAndRoundTrips() throws SnbtException {
        String src = "{id:\"minecraft:diamond_sword\",components:{\"minecraft:enchantments\":{\"minecraft:sharpness\":5},\"minecraft:custom_name\":{text:'He said \"hi\"',italic:0b}},f:1.5f,d:2.0,l:3L,s:4s,arr:[B;1b,2b],list:[1,2]}";
        Nbt n = Snbt.parse(src);
        String out = Snbt.write(n);
        assertEquals(n, Snbt.parse(out));
        assertTrue(out.contains("\"minecraft:enchantments\":{\"minecraft:sharpness\":5}"), out);
        assertTrue(out.contains("text:'He said \"hi\"'"), out);
        assertEquals(n, Snbt.parse(Snbt.pretty(n)));
    }

    @Test
    void numbersWriteBackExactly() throws SnbtException {
        for (String s : new String[]{"0.1f", "1.0E-5", "123.456", "-0.0", "1.0E20"}) {
            Nbt n = Snbt.parse(s);
            assertEquals(n, Snbt.parse(Snbt.write(n)), s + " -> " + Snbt.write(n));
        }
        assertEquals("0.1f", Snbt.write(Nbt.f(0.1)));
        assertEquals("2.0", Snbt.write(Nbt.d(2)));
    }

    @Test
    void reportsErrorPosition() {
        SnbtException e = assertThrows(SnbtException.class, () -> Snbt.parse("{a:1, b:[1,2,}"));
        assertTrue(e.offset >= 12, "offset " + e.offset);
    }

    @Test
    void writesTextComponentsAsJsonAndReadsThemBack() throws SnbtException {
        Nbt c = Snbt.parse("{text:\"Hi \\\"there\\\"\",color:\"gold\",bold:1b,italic:0b,extra:[\"a\",{text:\"b\"}]}");
        String json = Snbt.json(c);
        assertTrue(json.contains("\"bold\": true"), json);
        assertTrue(json.contains("\"italic\": false"), json);
        assertTrue(json.contains("\\\"there\\\""), json);
        assertEquals(c, Snbt.parse(json));
    }
}
