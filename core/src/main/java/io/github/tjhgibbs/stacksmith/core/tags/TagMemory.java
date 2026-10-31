package io.github.tjhgibbs.stacksmith.core.tags;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The entity tags known in each world, in one SNBT file: those you have used there (most
 * recent first) and those found in the world's datapacks (with where each was seen). Also
 * whether the datapack ones are shown, and a hidden list of every tag used anywhere in the
 * tool, newest first, which orders the lists: tags you used before come first, most recent
 * first, then the rest alphabetically.
 */
public final class TagMemory {
    /** Tags you used, per world, past which the oldest are dropped. */
    public static final int MAX_USED = 200;

    /** What is known about one world's tags. */
    public static final class World {
        final List<String> used = new ArrayList<>();
        /** Tag → up to {@link TagScan#MAX_SOURCES} places it was seen, sorted by tag. */
        final Map<String, List<String>> datapack = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        long scannedAt;
        int scannedFiles;

        public List<String> used() {
            return List.copyOf(used);
        }

        public Map<String, List<String>> datapack() {
            return datapack;
        }

        /** When the datapacks were last scanned (0: never), and how many files that read. */
        public long scannedAt() {
            return scannedAt;
        }

        public int scannedFiles() {
            return scannedFiles;
        }
    }

    private final Path file;
    private final Map<String, World> worlds = new LinkedHashMap<>();
    /** Every tag used in the tool, in any world, newest first (never shown as such; it orders). */
    private final List<String> recent = new ArrayList<>();
    private boolean showDatapack = true;

    public TagMemory(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Nbt.Compound root = Snbt.parseCompound(Files.readString(file, StandardCharsets.UTF_8));
                showDatapack = root.getBool("showDatapack", true);
                Nbt.List r = root.getList("recent");
                for (Nbt n : r == null ? List.<Nbt>of() : r.items()) {
                    if (n instanceof Nbt.Str s) {
                        recent.add(s.value());
                    }
                }
                Nbt.Compound ws = root.getCompound("worlds");
                if (ws != null) {
                    for (var e : ws.entries()) {
                        if (e.getValue() instanceof Nbt.Compound c) {
                            worlds.put(e.getKey(), read(c));
                        }
                    }
                }
            }
        } catch (IOException | SnbtException | RuntimeException e) {
            worlds.clear();
        }
    }

    private static World read(Nbt.Compound c) {
        World w = new World();
        Nbt.List used = c.getList("used");
        for (Nbt n : used == null ? List.<Nbt>of() : used.items()) {
            if (n instanceof Nbt.Str s) {
                w.used.add(s.value());
            }
        }
        Nbt.Compound dp = c.getCompound("datapack");
        if (dp != null) {
            for (var e : dp.entries()) {
                List<String> sources = new ArrayList<>();
                if (e.getValue() instanceof Nbt.List l) {
                    l.items().forEach(n -> {
                        if (n instanceof Nbt.Str s) {
                            sources.add(s.value());
                        }
                    });
                }
                w.datapack.put(e.getKey(), sources);
            }
        }
        w.scannedAt = c.getLong("scannedAt", 0);
        w.scannedFiles = (int) c.getLong("scannedFiles", 0);
        return w;
    }

    /** The world's tags ({@code world} may be null: "no world"). */
    public World world(String world) {
        return worlds.computeIfAbsent(world == null ? "" : world, k -> new World());
    }

    public boolean showDatapack() {
        return showDatapack;
    }

    public void showDatapack(boolean show) {
        showDatapack = show;
        write();
    }

    /** Notes {@code tags} as just used in {@code world}: they move to the front, there and in the tool's recency. */
    public void use(String world, Collection<String> tags) {
        List<String> fresh = tags.stream().filter(t -> TagScan.NAME.matcher(t).matches()).distinct().toList();
        if (!fresh.isEmpty() && toFront(world(world).used, fresh) | toFront(recent, fresh)) {
            write();
        }
    }

    /** Moves {@code fresh} to the front of {@code list}; false if it was there already (nothing to write). */
    private static boolean toFront(List<String> list, List<String> fresh) {
        if (list.size() >= fresh.size() && list.subList(0, fresh.size()).equals(fresh)) {
            return false;
        }
        list.removeAll(fresh);
        list.addAll(0, fresh);
        while (list.size() > MAX_USED) {
            list.removeLast();
        }
        return true;
    }

    /** Forgets that {@code tag} was used, here and in the tool's recency (a datapack tag goes back to its place). */
    public void forget(String world, String tag) {
        if (world(world).used.remove(tag) | recent.remove(tag)) {
            write();
        }
    }

    /** Sorts {@code tags}: used before first (most recent first), then the rest alphabetically. */
    public List<String> byRecency(Collection<String> tags) {
        List<String> out = new ArrayList<>(new LinkedHashSet<>(tags));
        out.sort(java.util.Comparator.<String>comparingInt(t -> {
            int i = recent.indexOf(t);
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Whether {@code tag} was used in the tool before (in any world). */
    public boolean usedBefore(String tag) {
        return recent.contains(tag);
    }

    /** Replaces the world's datapack tags with a scan's result. */
    public void scanned(String world, Map<String, List<String>> found, int files, long now) {
        World w = world(world);
        w.datapack.clear();
        w.datapack.putAll(found);
        w.scannedAt = now;
        w.scannedFiles = files;
        write();
    }

    /**
     * The tags you used before that belong in this world's lists, most recent first: those used
     * here, and (if shown) datapack tags you used anywhere.
     */
    public List<String> recentlyUsed(String world) {
        World w = world(world);
        Set<String> out = new LinkedHashSet<>(w.used);
        if (showDatapack) {
            w.datapack.keySet().stream().filter(recent::contains).forEach(out::add);
        }
        // Tags used here but past the tool-wide limit keep their order here, after the rest.
        List<String> sorted = byRecency(out.stream().filter(recent::contains).toList());
        out.stream().filter(t -> !recent.contains(t)).forEach(sorted::add);
        return sorted;
    }

    /** Datapack tags (if shown) never used in the tool, alphabetically. */
    public List<String> unusedDatapack(String world) {
        return showDatapack ? world(world).datapack.keySet().stream().filter(t -> !recentlyUsed(world).contains(t)).toList() : List.of();
    }

    /** Suggestions for a tag box: {@link #recentlyUsed}, then {@link #unusedDatapack}. */
    public List<String> known(String world) {
        List<String> out = new ArrayList<>(recentlyUsed(world));
        out.addAll(unusedDatapack(world));
        return List.copyOf(out);
    }

    private void write() {
        Nbt.Compound ws = new Nbt.Compound();
        worlds.forEach((name, w) -> {
            if (w.used.isEmpty() && w.datapack.isEmpty() && w.scannedAt == 0) {
                return;
            }
            Nbt.List used = new Nbt.List();
            w.used.forEach(t -> used.add(Nbt.str(t)));
            Nbt.Compound dp = new Nbt.Compound();
            w.datapack.forEach((t, sources) -> {
                Nbt.List l = new Nbt.List();
                sources.forEach(s -> l.add(Nbt.str(s)));
                dp.put(t, l);
            });
            ws.put(name, new Nbt.Compound().put("used", used).put("datapack", dp)
                    .put("scannedAt", Nbt.l(w.scannedAt)).put("scannedFiles", Nbt.i(w.scannedFiles)));
        });
        try {
            Files.createDirectories(file.getParent());
            Nbt.List r = new Nbt.List();
            recent.forEach(t -> r.add(Nbt.str(t)));
            Files.writeString(file, Snbt.pretty(new Nbt.Compound().put("showDatapack", Nbt.b(showDatapack ? 1 : 0))
                            .put("recent", r).put("worlds", ws)) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A convenience: a failed write must not break editing.
        }
    }
}
