package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.tags.TagMemory;
import io.github.tjhgibbs.stacksmith.core.tags.TagScan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Entity tags for the mob editor: the ones used in this world (remembered in
 * {@code config/stacksmith/entity-tags.snbt}) and the ones its datapacks use, found by reading
 * the files in the world's datapacks folder.
 */
public final class EntityTags {
    private EntityTags() {
    }

    private static TagMemory memory;

    static TagMemory memory() {
        if (memory == null) {
            memory = new TagMemory(PresetsScreen.ROOT.resolve("entity-tags.snbt"));
        }
        return memory;
    }

    /** This world's tag memory. */
    static TagMemory.World here() {
        return memory().world(SpawnerLibrary.world());
    }

    /** Suggestions for a tag box in this world. */
    static List<String> known() {
        return memory().known(SpawnerLibrary.world());
    }

    static void use(Collection<String> tags) {
        memory().use(SpawnerLibrary.world(), tags);
    }

    /** Notes the tags on {@code mob} and its riders as used: it was just summoned, given or saved. */
    static void useAll(EntitySpec mob) {
        List<String> tags = new ArrayList<>();
        collect(mob, tags);
        if (!tags.isEmpty()) {
            use(tags);
        }
    }

    /** Notes the tags on every mob a spawner makes as used. */
    static void useAll(SpawnerSpec spawner) {
        List<String> tags = new ArrayList<>();
        spawner.entries().forEach(e -> collect(e.entity, tags));
        if (!tags.isEmpty()) {
            use(tags);
        }
    }

    private static void collect(EntitySpec mob, List<String> into) {
        into.addAll(mob.tags());
        mob.passengers().forEach(p -> collect(p, into));
    }

    /** Whether the datapacks can be read: only when this game runs the world (singleplayer or LAN host). */
    static boolean canScan() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /**
     * Reads every pack in this world's {@code datapacks} folder for entity tags (off the render
     * thread), then remembers them for this world. Reads the files themselves, so a pack that
     * failed to load (which stops the game loading any of them) is still read. {@code done} gets
     * a line saying what was found, on the client thread.
     */
    static void scan(Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            done.accept("Only in a world you're running: a server's datapacks can't be read from here.");
            return;
        }
        String world = SpawnerLibrary.world();
        java.nio.file.Path folder = server.getWorldPath(LevelResource.DATAPACK_DIR);
        CompletableFuture.supplyAsync(() -> TagScan.scanPacks(folder)).thenAccept(r -> mc.execute(() -> {
            memory().scanned(world, r.tags(), r.files(), System.currentTimeMillis());
            done.accept(r.tags().isEmpty() ? "No tags found in " + r.files() + " datapack files."
                    : "Found " + r.tags().size() + " tags in " + r.packs().size() + (r.packs().size() == 1 ? " pack" : " packs")
                    + " (" + r.files() + " files).");
        }));
    }
}
