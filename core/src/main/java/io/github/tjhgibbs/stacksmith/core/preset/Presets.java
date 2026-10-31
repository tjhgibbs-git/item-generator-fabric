package io.github.tjhgibbs.stacksmith.core.preset;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Saved items, mobs and spawners: one SNBT file each under {@code <root>/<kind>/}, named after
 * the preset, so they can be copied, shared or edited by hand.
 */
public final class Presets {
    public enum Kind {
        ITEM("items"), MOB("mobs"), SPAWNER("spawners");

        public final String folder;

        Kind(String folder) {
            this.folder = folder;
        }
    }

    /** A saved preset: its name, data and when it was last saved. */
    public record Preset(String name, Nbt.Compound data, FileTime saved) {
    }

    private final Path root;

    public Presets(Path root) {
        this.root = root;
    }

    private Path dir(Kind kind) {
        return root.resolve(kind.folder);
    }

    /** A file name for {@code name}: letters, digits, spaces and {@code -_.()} only. */
    static String fileName(String name) {
        String clean = name.strip().replaceAll("[^\\p{L}\\p{N} _\\-.()]", "_").replaceAll("^\\.+", "_");
        if (clean.isEmpty()) {
            clean = "unnamed";
        }
        return (clean.length() > 80 ? clean.substring(0, 80) : clean) + ".snbt";
    }

    /** All presets of a kind, newest first. Files that don't parse are skipped. */
    public List<Preset> list(Kind kind) {
        List<Preset> out = new ArrayList<>();
        Path d = dir(kind);
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (Stream<Path> files = Files.list(d)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".snbt")).toList()) {
                try {
                    String name = p.getFileName().toString();
                    out.add(new Preset(name.substring(0, name.length() - 5),
                            Snbt.parseCompound(Files.readString(p, StandardCharsets.UTF_8)), Files.getLastModifiedTime(p)));
                } catch (IOException | SnbtException e) {
                    // A broken file stays on disk for the player to fix; it just isn't listed.
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(Preset::saved).reversed().thenComparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Saves (or overwrites) a preset; returns the name it was saved under. */
    public String save(Kind kind, String name, Nbt.Compound data) throws IOException {
        Files.createDirectories(dir(kind));
        String file = fileName(name);
        Files.writeString(dir(kind).resolve(file), Snbt.pretty(data) + "\n", StandardCharsets.UTF_8);
        return file.substring(0, file.length() - 5);
    }

    public boolean exists(Kind kind, String name) {
        return Files.exists(dir(kind).resolve(fileName(name)));
    }

    /** {@code base}, or the first of "base (2)", "base (3)"... not already saved. */
    public String freeName(Kind kind, String base) {
        String name = base.isBlank() ? "unnamed" : base.strip();
        for (int i = 2; exists(kind, name); i++) {
            name = (base.isBlank() ? "unnamed" : base.strip()) + " (" + i + ")";
        }
        return name;
    }

    /**
     * Renames a preset, keeping its file's date (so its place in the list). Returns the new
     * name; fails if another preset already has it. A change of case alone works on Windows too.
     */
    public String rename(Kind kind, String from, String to) throws IOException {
        String a = fileName(from), b = fileName(to);
        if (a.equals(b)) {
            return b.substring(0, b.length() - 5);
        }
        if (!a.equalsIgnoreCase(b) && Files.exists(dir(kind).resolve(b))) {
            throw new java.nio.file.FileAlreadyExistsException(b.substring(0, b.length() - 5));
        }
        // Through a temporary name, so "pig" → "Pig" isn't a no-op on case-insensitive disks.
        Path tmp = dir(kind).resolve(a + ".renaming");
        Files.move(dir(kind).resolve(a), tmp);
        Files.move(tmp, dir(kind).resolve(b));
        return b.substring(0, b.length() - 5);
    }

    public void delete(Kind kind, String name) throws IOException {
        Files.deleteIfExists(dir(kind).resolve(fileName(name)));
    }
}
