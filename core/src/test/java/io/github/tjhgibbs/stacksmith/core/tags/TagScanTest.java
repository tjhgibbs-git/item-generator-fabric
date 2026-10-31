package io.github.tjhgibbs.stacksmith.core.tags;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TagScanTest {
    @TempDir
    Path root;

    @Test
    void tagCommandsInEveryForm() {
        String fn = """
                # tag @e add commented_out
                tag @s add boss
                tag @e[type=zombie, distance=..10] remove angry
                execute as @e[type=minecraft:husk,tag=!processed] run tag @s add processed
                execute if entity @a[tag=in_arena] run say hi
                tag @p list
                kill @e[tag="quoted.one",limit=1]
                """;
        assertEquals(List.of("boss", "angry", "processed", "in_arena", "quoted.one"), List.copyOf(TagScan.tags(fn)));
    }

    @Test
    void nbtTagsInCommandsAndJson() {
        String fn = "summon zombie ~ ~ ~ {Tags:[\"wave1\",\"mob-2\"],CustomName:'\"x\"'}\n"
                + "data merge entity @s {Tags:[elite, 'quoted']}\n"
                + "execute if entity @e[nbt={Tags:[\"nbt_test\"]}] run say y";
        assertEquals(Set.of("wave1", "mob-2", "elite", "quoted", "nbt_test"), TagScan.tags(fn));
        String predicate = "{\"condition\":\"minecraft:entity_properties\",\"predicate\":{\"nbt\":\"{Tags:[\\\"from_json\\\"]}\"}}";
        assertEquals(Set.of("from_json"), TagScan.tags(predicate));
    }

    @Test
    void skipsWhatIsNotATag() {
        String fn = """
                $tag @s add $(name)
                $tag @s add fixed_in_macro
                tag @s
                execute as @e[tag=] run say no tag
                say tag @s add not_really
                scoreboard players tag fake add x
                """;
        // "say tag @s add not_really" reads like the command inside text: accepted, a harmless suggestion.
        assertTrue(TagScan.tags(fn).containsAll(Set.of("fixed_in_macro")));
        assertFalse(TagScan.tags(fn).contains("$(name)"));
        assertFalse(TagScan.tags(fn).contains(""));
    }

    @Test
    void scanKeepsAFewSources() {
        Map<String, List<String>> into = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            TagScan.scan("tag @s add boss", "pack:f" + i, into);
        }
        TagScan.scan("tag @s add boss", "pack:f0", into);
        assertEquals(List.of("pack:f0", "pack:f1", "pack:f2"), into.get("boss"));
    }

    @Test
    void readsAWorldsDatapacksFolder() throws Exception {
        Path dp = root.resolve("datapacks");
        Path arena = dp.resolve("arena");
        Files.createDirectories(arena.resolve("data/arena/function/waves"));
        Files.writeString(arena.resolve("data/arena/function/waves/start.mcfunction"), "tag @s add in_arena\nkill @e[tag=old]");
        Files.createDirectories(arena.resolve("data/arena/predicate"));
        Files.writeString(arena.resolve("data/arena/predicate/elite.json"), "{\"nbt\":\"{Tags:[\\\"elite\\\"]}\"}");
        // Not places that mention entity tags: a recipe, and a file outside data/.
        Files.createDirectories(arena.resolve("data/arena/recipe"));
        Files.writeString(arena.resolve("data/arena/recipe/r.json"), "{\"x\":\"tag @s add not_this\"}");
        Files.writeString(arena.resolve("notes.mcfunction"), "tag @s add nor_this");
        // A pre-1.21 pack (plural folder names).
        Path old = dp.resolve("old");
        Files.createDirectories(old.resolve("data/legacy/functions"));
        Files.writeString(old.resolve("data/legacy/functions/tick.mcfunction"), "tag @s add old_style\ntag @s add in_arena");
        // A zipped pack.
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dp.resolve("zipped.zip")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            zip.write("{}".getBytes());
            zip.putNextEntry(new java.util.zip.ZipEntry("data/z/function/go.mcfunction"));
            zip.write("summon pig ~ ~ ~ {Tags:[\"from_zip\"]}".getBytes());
        }
        Files.writeString(dp.resolve("broken.zip"), "not a zip");

        TagScan.Result r = TagScan.scanPacks(dp);
        assertEquals(Set.of("in_arena", "old", "elite", "old_style", "from_zip"), r.tags().keySet());
        assertEquals(List.of("arena: arena:waves/start", "old: legacy:tick"), r.tags().get("in_arena"));
        assertEquals(List.of("zipped: z:go"), r.tags().get("from_zip"));
        assertEquals(Set.of("arena", "old", "zipped"), r.packs());
        assertEquals(4, r.files());
        assertTrue(TagScan.scanPacks(root.resolve("missing")).tags().isEmpty());
    }

    @Test
    void tokensKeepBracketsAndQuotesWhole() {
        assertEquals(List.of("tag", "@e[type=zombie, tag=a]", "add", "b"), TagScan.tokens("tag @e[type=zombie, tag=a] add b"));
        assertEquals(List.of("say", "\"a b\"", "{x:[1, 2]}"), TagScan.tokens("say \"a b\" {x:[1, 2]}"));
    }

    @Test
    void memoryPerWorld() throws Exception {
        Path file = root.resolve("tags.snbt");
        TagMemory m = new TagMemory(file);
        m.use("World A", List.of("boss"));
        m.use("World A", List.of("wave1", "boss"));
        m.use("World B", List.of("other"));
        m.scanned("World A", Map.of("from_pack", List.of("p:f"), "boss", List.of("p:g")), 7, 1000);
        TagMemory back = new TagMemory(file);
        assertEquals(List.of("wave1", "boss"), back.world("World A").used());
        assertEquals(List.of("wave1", "boss", "from_pack"), back.known("World A"), "used first, then the pack's own");
        assertEquals(List.of("other"), back.known("World B"));
        assertEquals(7, back.world("World A").scannedFiles());
        back.showDatapack(false);
        assertEquals(List.of("wave1", "boss"), new TagMemory(file).known("World A"));
        back.forget("World A", "wave1");
        assertEquals(List.of("boss"), new TagMemory(file).world("World A").used());
        // Not real tag names are never remembered.
        back.use("World A", List.of("has space", ""));
        assertEquals(List.of("boss"), back.world("World A").used());
        Files.writeString(file, "{worlds:{");
        assertTrue(new TagMemory(file).known("World A").isEmpty());
    }

    @Test
    void tagsUsedBeforeComeFirstNewestFirst() {
        Path file = root.resolve("tags.snbt");
        TagMemory m = new TagMemory(file);
        m.scanned("Arena", Map.of("zeta", List.of("p:a"), "alpha", List.of("p:b"), "mid", List.of("p:c"), "boss", List.of("p:d")), 1, 0);
        // Never used: alphabetical.
        assertEquals(List.of("alpha", "boss", "mid", "zeta"), m.known("Arena"));
        // Used in another world: those datapack tags rise to the top here too, newest first.
        m.use("Survival", List.of("zeta"));
        m.use("Survival", List.of("mid"));
        assertEquals(List.of("mid", "zeta"), m.recentlyUsed("Arena"));
        assertEquals(List.of("alpha", "boss"), m.unusedDatapack("Arena"));
        assertEquals(List.of("mid", "zeta", "alpha", "boss"), m.known("Arena"));
        // Used here: newest of all. A tag used only in another world isn't listed here.
        m.use("Arena", List.of("boss", "my_own"));
        assertEquals(List.of("boss", "my_own", "mid", "zeta", "alpha"), m.known("Arena"));
        assertFalse(m.known("Arena").contains("other_world_only") );
        m.use("Survival", List.of("other_world_only"));
        assertFalse(m.known("Arena").contains("other_world_only"));
        // Using one again moves it up; forgetting puts a datapack tag back in its alphabetical place.
        m.use("Arena", List.of("zeta"));
        assertEquals("zeta", m.known("Arena").getFirst());
        m.forget("Arena", "zeta");
        assertEquals(List.of("boss", "my_own", "mid", "alpha", "zeta"), m.known("Arena"));
        // Kept across restarts; hidden datapack tags drop out unless used here.
        assertEquals(m.known("Arena"), new TagMemory(file).known("Arena"));
        m.showDatapack(false);
        assertEquals(List.of("boss", "my_own"), m.known("Arena"));
        assertTrue(m.usedBefore("mid") && !m.usedBefore("alpha"));
        assertEquals(List.of("mid", "alpha", "zz"), m.byRecency(List.of("zz", "alpha", "mid")));
    }

    @Test
    void entityTags() {
        EntitySpec z = new EntitySpec("zombie");
        assertTrue(z.tags().isEmpty());
        z.tags(List.of("a", "b", "a"));
        assertEquals(List.of("a", "b"), z.tags());
        assertEquals(List.of("a", "b"), EntitySpec.fromTag(z.toTag()).tags());
        z.tags(List.of());
        assertFalse(z.data().has("Tags"));
    }
}
