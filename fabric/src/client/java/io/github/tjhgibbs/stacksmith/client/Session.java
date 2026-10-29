package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;

/** What was being edited, kept while the game runs so closing a screen loses nothing. */
public final class Session {
    public static ItemSpec item = new ItemSpec("diamond_sword");
    public static SpawnerSpec spawner = new SpawnerSpec(new EntitySpec("zombie"));
    public static int spawnerEntry;
    /** Form cards (false) or the whole item as one outline (true). */
    public static boolean outlineView;
    public static boolean briefCommands = true;
    public static String target = "@s";

    private Session() {
    }
}
