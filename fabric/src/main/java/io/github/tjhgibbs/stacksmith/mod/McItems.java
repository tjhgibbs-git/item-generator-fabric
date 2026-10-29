package io.github.tjhgibbs.stacksmith.mod;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import io.github.tjhgibbs.stacksmith.core.item.ItemSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Item stacks to and from {@link ItemSpec}, using the game's own codecs and parser so the
 * result is exactly what the game would accept. All item API use is in this class.
 */
public final class McItems {
    private McItems() {
    }

    /** The game's stack for a spec, or the reason it is invalid. */
    public static Result toStack(ItemSpec spec, HolderLookup.Provider registries) {
        try {
            Tag tag = McNbt.toGame(spec.toTag());
            DataResult<ItemStack> r = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);
            return r.result().map(Result::ok).orElseGet(() -> Result.error(r.error().map(e -> e.message()).orElse("invalid item")));
        } catch (CommandSyntaxException e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(String.valueOf(e.getMessage()));
        }
    }

    public record Result(ItemStack stack, String error) {
        static Result ok(ItemStack s) {
            return new Result(s, null);
        }

        static Result error(String e) {
            return new Result(null, e);
        }

        public boolean ok() {
            return stack != null;
        }
    }

    /** The spec of an existing stack (id, count and component patch). */
    public static ItemSpec fromStack(ItemStack stack, HolderLookup.Provider registries) {
        Tag tag = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
        return ItemSpec.fromTag((Nbt.Compound) McNbt.fromGame(tag));
    }

    /**
     * Checks a command item argument ({@code stick[damage=3]}) with the game's parser.
     * Returns null when valid, else the message and cursor position.
     */
    public static Problem checkArgument(String argument, HolderLookup.Provider registries) {
        try {
            new ItemParser(registries).parse(new StringReader(argument));
            return null;
        } catch (CommandSyntaxException e) {
            return new Problem(e.getRawMessage().getString(), e.getCursor());
        }
    }

    public record Problem(String message, int cursor) {
    }

    /** The component's value on the item by default (from the item's prototype), or null. */
    public static Nbt defaultComponent(String itemId, String componentId, HolderLookup.Provider registries) {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId));
        Optional<DataComponentType<?>> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(Identifier.parse(componentId));
        if (item.isEmpty() || type.isEmpty()) {
            return null;
        }
        return encodeDefault(item.get(), type.get(), registries);
    }

    private static <T> Nbt encodeDefault(Item item, DataComponentType<T> type, HolderLookup.Provider registries) {
        T value = item.components().get(type);
        if (value == null || type.codec() == null) {
            return null;
        }
        return type.codec().encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).result().map(McNbt::fromGame).orElse(null);
    }

    /** Components the item has by default, as full ids. */
    public static List<String> defaultComponentIds(String itemId) {
        List<String> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).ifPresent(item -> {
            for (var typed : item.components()) {
                Identifier key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
                if (key != null) {
                    out.add(key.toString());
                }
            }
        });
        return out;
    }

    /** Every component a player can put on an item (persistent ones), as full ids. */
    public static List<String> componentIds() {
        List<String> out = new ArrayList<>();
        for (DataComponentType<?> t : BuiltInRegistries.DATA_COMPONENT_TYPE) {
            if (!t.isTransient()) {
                Identifier key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(t);
                if (key != null) {
                    out.add(key.toString());
                }
            }
        }
        return out;
    }

    public static boolean isItem(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ITEM.containsKey(key);
    }
}
