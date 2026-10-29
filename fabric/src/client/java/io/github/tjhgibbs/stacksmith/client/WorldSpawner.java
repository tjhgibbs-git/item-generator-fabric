package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.function.Consumer;

/** The spawner blocks in the world: which one you're looking at, and reading one in to edit. */
public final class WorldSpawner {
    private WorldSpawner() {
    }

    /** The block under the crosshair, or null. */
    public static BlockHitResult lookedAt() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** The block entity under the crosshair, or null. */
    public static BlockEntity lookedAtBlockEntity() {
        BlockHitResult hit = lookedAt();
        return hit == null ? null : Minecraft.getInstance().level.getBlockEntity(hit.getBlockPos());
    }

    /** The spawner under the crosshair, or null. */
    public static BlockPos lookedAtSpawner() {
        return lookedAtBlockEntity() instanceof SpawnerBlockEntity be ? be.getBlockPos() : null;
    }

    /** Whether the spawner {@code pos} points at is still there. */
    public static boolean exists(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return pos != null && mc.level != null && mc.level.getBlockEntity(pos) instanceof SpawnerBlockEntity;
    }

    /**
     * Reads a block entity's data. The client's copy has what the server shares for rendering;
     * operators ask the server for the rest. {@code then} runs on the client thread.
     */
    public static void read(BlockEntity be, Consumer<Nbt.Compound> then) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = be.getBlockPos();
        Nbt.Compound local = McNbt.fromGame(be.saveWithoutMetadata(Delivery.registries()));
        if (Delivery.canUse("data") && mc.getConnection() != null) {
            mc.getConnection().getDebugQueryHandler().queryBlockEntityTag(pos, tag -> mc.execute(() ->
                    then.accept(tag != null ? McNbt.fromGame(tag) : local)));
        } else {
            then.accept(local);
        }
    }

    /** Loads the spawner at {@code pos} into the session (linked to that block), then runs {@code then}. */
    public static void load(BlockPos pos, Runnable then) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Delivery.registries() == null || !(mc.level.getBlockEntity(pos) instanceof SpawnerBlockEntity be)) {
            return;
        }
        read(be, data -> {
            link(SpawnerSpec.fromTag(data), pos);
            then.run();
        });
    }

    /** Makes {@code spawner} the one being edited, saved back to the block at {@code pos} (null: none). */
    public static void link(SpawnerSpec spawner, BlockPos pos) {
        Session.spawner = spawner;
        Session.spawnerEntry = 0;
        Session.spawnerPos = pos == null ? null : pos.immutable();
    }
}
