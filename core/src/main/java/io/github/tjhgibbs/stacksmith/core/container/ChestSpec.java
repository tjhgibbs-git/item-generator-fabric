package io.github.tjhgibbs.stacksmith.core.container;

import io.github.tjhgibbs.stacksmith.core.Ids;
import io.github.tjhgibbs.stacksmith.core.command.Commands;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;

/** One physical container (a double chest has two independently editable halves). */
public final class ChestSpec {
    private String block;
    private final ItemSpec[] slots = new ItemSpec[27];
    private final Nbt.Compound extra;

    public ChestSpec() {
        this("minecraft:chest", Nbt.compound());
    }

    public ChestSpec(String block, Nbt.Compound data) {
        this.block = Ids.full(block);
        if (!supported(this.block))
            throw new IllegalArgumentException("Unsupported container: " + block);
        extra = data.copy();
        for (String k : List.of("id", "x", "y", "z", "Items")) extra.remove(k);
        Nbt.List items = data.getList("Items");
        if (items != null)
            for (Nbt n : items.items())
                if (n instanceof Nbt.Compound c) {
                    int slot = (int) c.getLong("Slot", -1);
                    if (slot >= 0 && slot < 27) slots[slot] = ItemSpec.fromTag(c);
                }
    }

    public static boolean supported(String id) {
        String b = Ids.brief(id);
        return b.equals("chest") || b.equals("trapped_chest") || b.equals("barrel") || shulker(id);
    }

    public static boolean shulker(String id) {
        return id.equals("shulker_box")
                || id.equals("minecraft:shulker_box")
                || id.startsWith("minecraft:") && id.endsWith("_shulker_box");
    }

    public String block() {
        return block;
    }

    public void block(String id) {
        id = Ids.full(id);
        if (!supported(id)) throw new IllegalArgumentException("Unsupported container");
        if (shulker(id) && hasShulkers())
            throw new IllegalArgumentException(
                    "Remove the shulker boxes before converting this container");
        block = id;
    }

    public ItemSpec slot(int i) {
        return slots[i] == null ? null : slots[i].copy();
    }

    public void slot(int i, ItemSpec item) {
        if (item != null && shulker(block) && shulker(item.id()))
            throw new IllegalArgumentException("Shulker boxes cannot contain shulker boxes");
        slots[i] = item == null ? null : item.copy();
    }

    public boolean hasShulkers() {
        for (ItemSpec s : slots) if (s != null && shulker(s.id())) return true;
        return false;
    }

    public int occupied() {
        int n = 0;
        for (ItemSpec s : slots) if (s != null) n++;
        return n;
    }

    public boolean lootTable() {
        return extra.has("LootTable");
    }

    public void unpackLootTable() {
        extra.remove("LootTable");
        extra.remove("LootTableSeed");
    }

    public Nbt.Compound data() {
        Nbt.Compound out = extra.copy();
        Nbt.List items = Nbt.list();
        for (int i = 0; i < 27; i++)
            if (slots[i] != null) items.add(slots[i].toTag().put("Slot", Nbt.b(i)));
        return out.put("Items", items);
    }

    public Nbt.Compound toTag() {
        return Nbt.compound().put("block", block).put("data", data());
    }

    public static ChestSpec fromTag(Nbt.Compound tag) {
        if (tag.has("command"))
            return new ChestSpec(BlockTemplate.block(tag), BlockTemplate.data(tag));
        return new ChestSpec(
                tag.getString("block", "chest"),
                tag.getCompound("data") == null ? Nbt.compound() : tag.getCompound("data"));
    }

    public ChestSpec copy() {
        return fromTag(toTag());
    }

    public String command() {
        return Commands.setblock("~ ~ ~", block, data(), "replace", new Commands.Style(true, "@s"));
    }

    public ItemSpec item() {
        ItemSpec item = new ItemSpec(block);
        Nbt.List contents = Nbt.list();
        for (int i = 0; i < 27; i++)
            if (slots[i] != null)
                contents.add(Nbt.compound().put("slot", Nbt.i(i)).put("item", slots[i].toTag()));
        item.set("container", contents);
        Nbt.Compound rest = extra.copy();
        if (!rest.isEmpty())
            item.set(
                    "block_entity_data",
                    rest.put("id", shulker(block) ? "minecraft:shulker_box" : block));
        return item;
    }

    public static ChestSpec fromItem(ItemSpec item) {
        Nbt.Compound data =
                item.get("block_entity_data") instanceof Nbt.Compound c ? c : Nbt.compound();
        ChestSpec s = new ChestSpec(item.id(), data);
        if (item.get("container") instanceof Nbt.List list)
            for (Nbt n : list.items())
                if (n instanceof Nbt.Compound c) {
                    int i = (int) c.getLong("slot", -1);
                    if (i >= 0 && i < 27 && c.getCompound("item") != null)
                        s.slot(i, ItemSpec.fromTag(c.getCompound("item")));
                }
        return s;
    }

    /** Keeps the original item's name, count and unrelated component patch. */
    public ItemSpec updateItem(ItemSpec original) {
        ItemSpec out = original.copy().id(block);
        out.set("container", item().get("container"));
        if (out.get("block_entity_data") instanceof Nbt.Compound be) {
            be.remove("Items");
            be.put("id", shulker(block) ? "minecraft:shulker_box" : block);
        }
        return out;
    }

    /** Build the full payload before touching the world; no item drops during replacement. */
    public List<String> write(String pos, String state, boolean existing, int limit) {
        List<String> out = new ArrayList<>();
        if (!Commands.writeStorage(Commands.STORAGE, "chest", data(), limit - 1, out)) return null;
        if (existing) out.add("data remove block " + pos + " Items");
        out.add("setblock " + pos + " " + state);
        out.add(
                "data modify block "
                        + pos
                        + " {} merge from storage "
                        + Commands.STORAGE
                        + " chest");
        out.add("data remove storage " + Commands.STORAGE + " chest");
        return out.stream().anyMatch(c -> c.length() + 1 > limit) ? null : out;
    }
}
