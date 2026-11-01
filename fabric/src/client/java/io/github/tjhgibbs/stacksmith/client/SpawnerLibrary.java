package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.preset.Presets;
import io.github.tjhgibbs.stacksmith.core.preset.SpawnerLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * The spawners you've used and the ones you starred. Every spawner written to the world or
 * taken out as an item goes in the log ({@code config/stacksmith/spawner-log.snbt}). Starred
 * spawners are the saved spawner presets ({@code config/stacksmith/spawners/}), so they are
 * never dropped from the log's end and stay shareable files.
 */
public final class SpawnerLibrary {
    private SpawnerLibrary() {
    }

    static final Presets PRESETS = new Presets(PresetsScreen.ROOT);
    private static SpawnerLog log;
    /** The starred spawners as last read from disk, or null to read again. */
    private static List<Presets.Preset> starred;

    static SpawnerLog log() {
        if (log == null) {
            log = new SpawnerLog(PresetsScreen.ROOT.resolve("spawner-log.snbt"));
        }
        return log;
    }

    /** Logs {@code spawner} as just used ({@code how}: "Saved", "Spawner item"...), at {@code pos} if it went into the world. */
    static void record(SpawnerSpec spawner, String how, BlockPos pos) {
        if (!spawner.entries().isEmpty()) {
            EntityTags.useAll(spawner);
            log().record(spawner.copy(), how, pos == null ? null : pos.getX() + " " + pos.getY() + " " + pos.getZ(), world(), System.currentTimeMillis());
        }
    }

    /**
     * Which world this is, for remembering things per world: the server address, or the
     * singleplayer save's folder name (unique, unlike the world's name), or null.
     */
    static String world() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) {
            return mc.getCurrentServer().ip;
        }
        if (mc.getSingleplayerServer() == null) {
            return null;
        }
        java.nio.file.Path root = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize();
        return root.getFileName() != null ? root.getFileName().toString() : mc.getSingleplayerServer().getWorldData().getLevelName();
    }

    /** Starred spawners, newest first. */
    static List<Presets.Preset> starred() {
        if (starred == null) {
            starred = PRESETS.list(Presets.Kind.SPAWNER);
        }
        return starred;
    }

    /** Reads the starred folder again (it can be edited by hand). */
    static void reload() {
        starred = null;
    }

    /** The name {@code spawner} is starred under, or null. */
    static String starredName(SpawnerSpec spawner) {
        for (Presets.Preset p : starred()) {
            try {
                if (SpawnerSpec.fromTag(p.data()).sameAs(spawner)) {
                    return p.name();
                }
            } catch (RuntimeException e) {
                // A hand-edited file that isn't a spawner: not this one.
            }
        }
        return null;
    }

    /** Stars {@code spawner} under {@code name} (or a free name based on its mobs); returns the name, or null on failure. */
    static String star(SpawnerSpec spawner, String name) {
        try {
            String n = name != null ? name : PRESETS.freeName(Presets.Kind.SPAWNER, SpawnerLog.title(spawner) + " spawner");
            return PRESETS.save(Presets.Kind.SPAWNER, n, io.github.tjhgibbs.stacksmith.core.container.BlockTemplate.save(
                    io.github.tjhgibbs.stacksmith.core.command.Commands.setSpawner(spawner, "~ ~ ~", io.github.tjhgibbs.stacksmith.core.command.Commands.Style.DEFAULT)));
        } catch (IOException e) {
            return null;
        } finally {
            starred = null;
        }
    }

    /**
     * Gives a starred spawner a new nickname (its file name). Returns the name it got, or null
     * with {@code why} explaining (taken by another starred spawner, or the disk refused).
     */
    static String rename(String from, String to, Consumer<String> why) {
        if (to.isBlank()) {
            why.accept("A nickname can't be empty");
            return null;
        }
        try {
            return PRESETS.rename(Presets.Kind.SPAWNER, from, to.strip());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            why.accept("Another starred spawner is called \"" + e.getFile() + "\"");
        } catch (IOException e) {
            why.accept("Couldn't rename: " + e.getMessage());
        } finally {
            starred = null;
        }
        return null;
    }

    /** Unstars the spawner saved as {@code name}. It stays in the log, so it is never simply gone. */
    static boolean unstar(String name, Nbt.Compound data) {
        try {
            PRESETS.delete(Presets.Kind.SPAWNER, name);
            SpawnerSpec s = SpawnerSpec.fromTag(data);
            if (log().entries().stream().noneMatch(e -> e.spawner().sameAs(s))) {
                log().record(s, "Unstarred", null, null, System.currentTimeMillis());
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            starred = null;
        }
    }
}
