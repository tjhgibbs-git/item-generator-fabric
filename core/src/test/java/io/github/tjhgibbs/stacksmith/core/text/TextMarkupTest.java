package io.github.tjhgibbs.stacksmith.core.text;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TextMarkupTest {
    @Test
    void plainUprightName() {
        assertEquals("{text:\"Sword\",italic:false}", Snbt.write(TextMarkup.toComponent("Sword", true)));
        assertEquals("\"Sword\"", Snbt.write(TextMarkup.toComponent("Sword", false)));
    }

    @Test
    void stylesAndColours() {
        Nbt c = TextMarkup.toComponent("&6&lGold &rplain &#ff8800hex &&", true);
        assertEquals("{text:\"\",italic:false,extra:[{text:\"Gold \",color:\"gold\",bold:true},\"plain \",{text:\"hex &\",color:\"#ff8800\"}]}",
                Snbt.write(c));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sword", "&6&lGold &rplain", "&cred &9blue &lbold", "&oslanted &rstraight", "&#12AB34x", "a && b", "&l&nBoth"})
    void markupRoundTrips(String markup) {
        Nbt c = TextMarkup.toComponent(markup, true);
        String back = TextMarkup.fromComponent(c);
        assertNotNull(back);
        assertEquals(TextMarkup.segments(markup), TextMarkup.segments(back), back);
        assertEquals(!markup.contains("&o"), TextMarkup.isUpright(c));
    }

    @Test
    void unsupportedComponentsSayNull() throws SnbtException {
        assertNull(TextMarkup.fromComponent(Snbt.parse("{translate:\"item.minecraft.stick\"}")));
        assertNull(TextMarkup.fromComponent(Snbt.parse("{text:\"x\",click_event:{action:\"open_url\",url:\"https://a\"}}")));
        assertEquals("&ax&cy", TextMarkup.fromComponent(Snbt.parse("[{text:\"x\",color:\"green\"},{text:\"y\",color:\"red\"}]")));
    }

    @Test
    void unknownCodesStayText() {
        assertEquals("&zfoo", TextMarkup.plain("&zfoo"));
        assertEquals("Gold", TextMarkup.plain("&6Gold"));
    }
}
