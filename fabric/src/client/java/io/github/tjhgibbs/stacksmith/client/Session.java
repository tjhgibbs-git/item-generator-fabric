package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.EntitySpec;
import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import net.minecraft.core.BlockPos;

/** What was being edited, kept while the game runs so closing a screen loses nothing. */
public final class Session {
    public static io.github.tjhgibbs.stacksmith.core.container.ChestSpec chest = new io.github.tjhgibbs.stacksmith.core.container.ChestSpec();
    public static ChestIndex.Entry chestLink;
    public static ItemSpec item = new ItemSpec("diamond_sword");
    public static EntitySpec mob = new EntitySpec("zombie");
    public static SpawnerSpec spawner = new SpawnerSpec();
    public static int spawnerEntry;
    /** The spawner block the Spawner tab saves back to, or null when it isn't tied to one. */
    public static BlockPos spawnerPos;
    /** The linked spawner as it was read from the world (for Reset and matching), or null. */
    public static SpawnerSpec spawnerOriginal;
    /** What the linked block holds now as far as we know: as loaded, then as last saved. */
    public static SpawnerSpec spawnerSaved;
    /** Draw the linked spawner's player range (sphere) / spawn area (box) in the world. */
    public static boolean showPlayerRange, showSpawnRange;
    /** Form cards (false) or the whole item as one outline (true). */
    public static boolean outlineView;
    /** The item tab's simple view: one section (name and lore, enchantments...) at a time. */
    public static boolean simpleItems = true;
    public static String itemSection = "name";
    /** Show times in ticks (20 per second) instead of seconds. */
    public static boolean ticks;
    public static boolean briefCommands = true;
    public static String target = "@s";

    private Session() {
    }
}
