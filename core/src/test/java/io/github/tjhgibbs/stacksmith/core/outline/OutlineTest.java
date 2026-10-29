package io.github.tjhgibbs.stacksmith.core.outline;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OutlineTest {
    private static final String ITEM = """
            {"minecraft:custom_name":{text:"Excalibur",color:"gold",italic:false},
             "minecraft:enchantments":{"minecraft:sharpness":5,"minecraft:unbreaking":3},
             "minecraft:lore":[{text:"Forged in the deep",color:"gray"},"A plain line",{text:"",extra:["a","b"]}],
             "minecraft:attribute_modifiers":[{type:"attack_damage",amount:12.0,operation:"add_value",id:"x:y",slot:"mainhand"}],
             "minecraft:unbreakable":{},
             "minecraft:custom_data":{tags:["legendary","bound"],n:5b,s:"5",t:"true",q:" padded ",colon:"a: b",nested:[[1,2],[{a:1}]],ints:[I;1,2],empty:[]}}
            """;

    @Test
    void readsLikeTheDocExample() throws SnbtException {
        Nbt.Compound c = Outline.readCompound("""
                custom_name:
                  text: Excalibur
                  color: gold
                  italic: false
                enchantments:
                  minecraft:sharpness: 5
                  minecraft:unbreaking: 3
                lore:
                  - text: Forged in the deep
                    color: gray
                  - A plain line
                # comment
                tags: [legendary, bound]
                glider:
                """);
        assertEquals("Excalibur", c.getCompound("custom_name").getString("text", null));
        assertEquals(Nbt.bool(false), c.getCompound("custom_name").get("italic"));
        assertEquals(Nbt.i(5), c.getCompound("enchantments").get("minecraft:sharpness"));
        assertEquals(2, c.getList("lore").size());
        assertEquals("gray", ((Nbt.Compound) c.getList("lore").get(0)).getString("color", null));
        assertEquals(new Nbt.Str("A plain line"), c.getList("lore").get(1));
        assertEquals(Nbt.list(Nbt.str("legendary"), Nbt.str("bound")), c.get("tags"));
        assertEquals(Nbt.compound(), c.get("glider"));
    }

    @Test
    void roundTripsLosslessly() throws SnbtException {
        Nbt n = Snbt.parse(ITEM);
        String text = Outline.write(n);
        assertEquals(n, Outline.read(text), text);
        assertTrue(text.contains("  text: Excalibur\n"), text);
        assertTrue(text.contains("  s: \"5\"\n"), text);
        assertTrue(text.contains("  t: \"true\"\n"), text);
        assertTrue(text.contains("  n: 5b\n"), text);
        assertTrue(text.contains("tags: [legendary, bound]"), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"just a string\"", "5", "[1, 2]", "[{a:1},{b:[2,3]}]", "{}", "[]", "[[1],[2]]", "\"- dash\"", "{a:[\"x: y\",\"-z\",\"\"]}"})
    void roundTripsAnyShape(String snbt) throws SnbtException {
        Nbt n = Snbt.parse(snbt);
        String text = Outline.write(n);
        assertEquals(n, Outline.read(text), text);
    }

    @Test
    void acceptsInlineSnbtValues() throws SnbtException {
        Nbt.Compound c = Outline.readCompound("a: {x: 1, y: [2, 3]}\nb: [I; 1, 2]\nc: \"quoted: yes\"\nd: 2.5f");
        assertEquals(Nbt.i(1), c.getCompound("a").get("x"));
        assertInstanceOf(Nbt.Arr.class, c.get("b"));
        assertEquals("quoted: yes", c.getString("c", null));
        assertEquals(Nbt.f(2.5), c.get("d"));
    }

    @Test
    void errorsCarryLineNumbers() {
        SnbtException e = assertThrows(SnbtException.class, () -> Outline.read("a: 1\nb:\n  c: {x:\n"));
        assertEquals(3, e.line);
        e = assertThrows(SnbtException.class, () -> Outline.read("a: 1\n    b: 2"));
        assertEquals(2, e.line);
        e = assertThrows(SnbtException.class, () -> Outline.read("a: 1\na: 2"));
        assertEquals(2, e.line);
        e = assertThrows(SnbtException.class, () -> Outline.read("a: 12x"));
        assertEquals(1, e.line);
    }

    @Test
    void tabsAndBlankLinesAreForgiven() throws SnbtException {
        Nbt.Compound c = Outline.readCompound("a:\n\tb: 1\n\n\tc: 2\n");
        assertEquals(2, c.getCompound("a").size());
    }
}
