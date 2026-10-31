package io.github.tjhgibbs.stacksmith.core.preset;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.nbt.Snbt;
import io.github.tjhgibbs.stacksmith.core.nbt.SnbtException;
import io.github.tjhgibbs.stacksmith.core.text.TextMarkup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every spawner put into the world or taken out as an item, newest first, in one SNBT file.
 * Using the same spawner again (same mobs and settings, see {@link SpawnerSpec#sameAs}) moves it
 * to the top instead of adding a copy, so the log reads as "spawners I've used".
 */
public final class SpawnerLog {
    /** Oldest entries past this are dropped. Starred spawners are presets, so never lost. */
    public static final int MAX = 300;

    /** One logged spawner: its data, when and how it was last used, where, and how often. */
    public record Entry(Nbt.Compound data, long time, String how, String pos, String world, int uses) {
        public SpawnerSpec spawner() {
            return SpawnerSpec.fromTag(data);
        }

        Nbt.Compound toTag() {
            Nbt.Compound c = new Nbt.Compound().put("data", data).put("time", Nbt.l(time)).put("how", how).put("uses", Nbt.i(uses));
            if (pos != null) {
                c.put("pos", pos);
            }
            if (world != null) {
                c.put("world", world);
            }
            return c;
        }

        static Entry fromTag(Nbt.Compound c) {
            Nbt.Compound data = c.getCompound("data");
            return data == null ? null : new Entry(data, c.getLong("time", 0), c.getString("how", ""),
                    c.getString("pos", null), c.getString("world", null), (int) Math.max(1, c.getLong("uses", 1)));
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    /** Reads {@code file} if it exists; an unreadable file starts an empty log (and is kept until the next write). */
    public SpawnerLog(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Nbt.List list = Snbt.parseCompound(Files.readString(file, StandardCharsets.UTF_8)).getList("spawners");
                for (Nbt n : list == null ? List.<Nbt>of() : list.items()) {
                    Entry e = n instanceof Nbt.Compound c ? Entry.fromTag(c) : null;
                    if (e != null) {
                        entries.add(e);
                    }
                }
            }
        } catch (IOException | SnbtException | RuntimeException e) {
            entries.clear();
        }
    }

    /** Newest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Logs {@code spawner} as used just now: {@code how} ("Saved", "Spawner item"...), at
     * {@code pos} ("x y z", or null) in {@code world} (or null). Returns the entry.
     */
    public Entry record(SpawnerSpec spawner, String how, String pos, String world, long now) {
        int uses = 1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).spawner().sameAs(spawner)) {
                uses += entries.remove(i).uses();
                break;
            }
        }
        Entry e = new Entry(spawner.toTag(), now, how, pos, world, uses);
        entries.addFirst(e);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
        write();
        return e;
    }

    public void remove(Entry e) {
        if (entries.remove(e)) {
            write();
        }
    }

    private void write() {
        Nbt.List list = new Nbt.List();
        entries.forEach(e -> list.add(e.toTag()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Snbt.pretty(new Nbt.Compound().put("spawners", list)) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The log is a convenience: losing a write must not stop the save that caused it.
        }
    }

    // ---- describing and searching -------------------------------------------------------------

    /** What a mob is called: its custom name, else its type ("Bob", "Zombie"). */
    public static String mobName(EntitySpec mob) {
        Nbt n = mob.data().get(EntitySpec.Keys.CUSTOM_NAME);
        String markup = n == null ? null : TextMarkup.fromComponent(n);
        return markup != null && !markup.isBlank() ? TextMarkup.plain(markup) : Ids.pretty(mob.id());
    }

    /** "Zombie", "Bob, Skeleton", "Zombie, Creeper +2", or "Empty". */
    public static String title(SpawnerSpec s) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(s.entries().stream().map(e -> mobName(e.entity)).toList()));
        if (names.isEmpty()) {
            return "Empty";
        }
        return names.size() <= 2 ? String.join(", ", names) : names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2);
    }

    /** Lower-case words a search looks in: mob names, types (and riders), plus {@code more}. */
    public static String searchText(SpawnerSpec s, String... more) {
        Set<String> words = new LinkedHashSet<>();
        for (SpawnerSpec.Entry e : s.entries()) {
            addMob(words, e.entity);
        }
        for (String m : more) {
            if (m != null) {
                words.add(m);
            }
        }
        return String.join(" ", words).toLowerCase(Locale.ROOT);
    }

    private static void addMob(Set<String> words, EntitySpec mob) {
        words.add(mobName(mob));
        words.add(Ids.pretty(mob.id()));
        words.add(Ids.brief(mob.id()));
        mob.passengers().forEach(p -> addMob(words, p));
    }

    /** Whether every word of {@code query} appears in {@code text} (from {@link #searchText}). */
    public static boolean matches(String text, String query) {
        for (String word : query.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (!word.isEmpty() && !text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", "12 weeks ago". */
    public static String ago(long then, long now) {
        long s = Math.max(0, (now - then) / 1000);
        if (s < 60) {
            return "just now";
        }
        if (s < 3600) {
            return s / 60 + " min ago";
        }
        if (s < 86400) {
            return s / 3600 + " h ago";
        }
        long d = s / 86400;
        return d == 1 ? "yesterday" : d < 14 ? d + " days ago" : d / 7 + " weeks ago";
    }
}
