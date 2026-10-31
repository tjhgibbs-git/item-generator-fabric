package io.github.tjhgibbs.stacksmith.core.tags;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the entity tags (the {@code Tags} that {@code @e[tag=...]} selects on) a datapack file
 * gives out or tests for:
 * <ul>
 *   <li>{@code tag <targets> add|remove <name>}, also after {@code execute ... run};</li>
 *   <li>selector arguments {@code tag=name} and {@code tag=!name};</li>
 *   <li>{@code Tags:["a","b"]} in NBT (summon, data, nbt= tests, and JSON predicates, escaped or not).</li>
 * </ul>
 * Comments are skipped, and so are names built by a macro ({@code $(var)}).
 */
public final class TagScan {
    private TagScan() {
    }

    /** What an unquoted tag name may contain (the game's unquoted string). */
    public static final Pattern NAME = Pattern.compile("[0-9A-Za-z_.+\\-]+");
    /** Sources kept per tag, to say where it came from. */
    public static final int MAX_SOURCES = 3;

    private static final Pattern SELECTOR_TAG = Pattern.compile("[\\[,]\\s*tag\\s*=\\s*!?\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[0-9A-Za-z_.+\\-]+)");
    private static final Pattern NBT_TAGS = Pattern.compile("\\bTags\\s*:\\s*\\[([^\\]]*)\\]");

    /** The tags {@code text} (an .mcfunction or a JSON file) refers to, in order of first mention. */
    public static Set<String> tags(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("$")) {
                line = line.substring(1);
            }
            Matcher m = SELECTOR_TAG.matcher(line);
            while (m.find()) {
                add(out, m.group(1));
            }
            List<String> tokens = tokens(line);
            for (int i = 0; i + 3 < tokens.size(); i++) {
                if (tokens.get(i).equals("tag") && (tokens.get(i + 2).equals("add") || tokens.get(i + 2).equals("remove"))) {
                    add(out, tokens.get(i + 3));
                }
            }
            Matcher n = NBT_TAGS.matcher(line);
            while (n.find()) {
                for (String item : n.group(1).split(",")) {
                    add(out, item);
                }
            }
        }
        return out;
    }

    /** Adds the tags in {@code text} to {@code into} (tag → where it was seen), noting {@code source}. */
    public static void scan(String text, String source, Map<String, List<String>> into) {
        add(tags(text), source, into);
    }

    private static void add(Set<String> tags, String source, Map<String, List<String>> into) {
        for (String tag : tags) {
            List<String> sources = into.computeIfAbsent(tag, t -> new ArrayList<>());
            if (sources.size() < MAX_SOURCES && !sources.contains(source)) {
                sources.add(source);
            }
        }
    }

    /** What a scan of a datapacks folder found: tag → where seen, how many files were read, which packs had tags. */
    public record Result(Map<String, List<String>> tags, int files, Set<String> packs) {
    }

    /** Folders in {@code data/<namespace>/} whose files can mention entity tags (also the pre-1.21 plural names). */
    private static final Set<String> FOLDERS = Set.of("function", "functions", "predicate", "predicates", "advancement", "advancements",
            "loot_table", "loot_tables", "item_modifier", "item_modifiers");

    /**
     * Reads every pack in a world's {@code datapacks} folder (folders and .zip files, enabled or
     * not, loaded cleanly or not) for entity tags. Sources read "pack: namespace:path".
     */
    public static Result scanPacks(java.nio.file.Path datapacks) {
        Map<String, List<String>> found = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> packs = new java.util.TreeSet<>();
        int[] files = {0};
        if (!java.nio.file.Files.isDirectory(datapacks)) {
            return new Result(found, 0, packs);
        }
        try (var entries = java.nio.file.Files.list(datapacks)) {
            for (java.nio.file.Path pack : entries.sorted().toList()) {
                String name = pack.getFileName().toString();
                if (java.nio.file.Files.isDirectory(pack)) {
                    scanPack(pack, name, found, packs, files);
                } else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    try (var zip = java.nio.file.FileSystems.newFileSystem(pack)) {
                        scanPack(zip.getPath("/"), name.substring(0, name.length() - 4), found, packs, files);
                    } catch (java.io.IOException | RuntimeException e) {
                        // Not a readable zip: skip it.
                    }
                }
            }
        } catch (java.io.IOException e) {
            // The folder went away: report what was read.
        }
        return new Result(found, files[0], packs);
    }

    private static void scanPack(java.nio.file.Path root, String pack, Map<String, List<String>> found, Set<String> packs, int[] files)
            throws java.io.IOException {
        java.nio.file.Path data = root.resolve("data");
        if (!java.nio.file.Files.isDirectory(data)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(data)) {
            for (java.nio.file.Path f : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                String rel = data.relativize(f).toString().replace('\\', '/');
                String[] parts = rel.split("/", 3); // namespace, folder, rest
                if (parts.length < 3 || !FOLDERS.contains(parts[1]) || !(rel.endsWith(".mcfunction") || rel.endsWith(".json"))) {
                    continue;
                }
                String text;
                try {
                    text = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException | RuntimeException e) {
                    continue; // unreadable or not UTF-8
                }
                files[0]++;
                Set<String> inFile = tags(text);
                if (!inFile.isEmpty()) {
                    packs.add(pack);
                    String source = pack + ": " + parts[0] + ":" + parts[2].replaceFirst("\\.(mcfunction|json)$", "");
                    add(inFile, source, found);
                }
            }
        }
    }

    /** Unquotes (JSON-escaped quotes too) and keeps it if it is a plain tag name. */
    private static void add(Set<String> out, String raw) {
        String s = raw.strip().replace("\\", "");
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        if (!s.isEmpty() && !s.contains("$(") && NAME.matcher(s).matches()) {
            out.add(s);
        }
    }

    /** Splits a command at spaces, keeping [...] {...} and quoted text (which may hold spaces) whole. */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < line.length()) {
                    cur.append(line.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if ((c == ']' || c == '}') && depth > 0) {
                depth--;
            } else if (c == ' ' && depth == 0) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
