package io.github.tjhgibbs.stacksmith.server;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.network.Wire;
import io.github.tjhgibbs.stacksmith.mod.McNbt;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerHooksGameTests {
    private static final String EMPTY = "stacksmith-gametest:empty64";
    private static ServerIndex.Entry place(GameTestHelper h, int x) {
        var pos = h.absolutePos(new BlockPos(x, 3, 5));
        var level = h.getLevel();
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
                "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " spawner{Delay:123s,SpawnCount:2s,SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
        var raw = McNbt.fromGame(level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess()));
        return new ServerIndex.Entry(level.dimension().identifier().toString(), pos, SpawnerSpec.fromTag(raw), raw);
    }
    @GameTest(structure = EMPTY, maxTicks = 100)
    public void writesOnlyChangedSettingsAndSkipsStaleTargets(GameTestHelper h) {
        var first = place(h, 4); var stale = place(h, 6);
        var server = h.getLevel().getServer();
        var index = new ServerIndex(server, () -> true);
        index.entries.put(first.key(), first); index.entries.put(stale.key(), stale);
        var changed = stale.raw().copy().put("SpawnCount", Nbt.s(9));
        try { h.getLevel().getBlockEntity(stale.pos()).loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), McNbt.toGame(changed))); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        var writer = new ServerBatch(server, index, () -> true, () -> {});
        String error = writer.startChanges(List.of(first, stale), first.spec(), first.spec().copy().set(SpawnerSpec.Setting.COUNT, 7));
        if (error != null) { h.fail(error); return; }
        h.runAfterDelay(10, () -> {
            if (writer.busy() || writer.completed != 1 || writer.skipped != 1 || writer.failed != 0) { h.fail("Unexpected write results: " + writer.status); return; }
            var raw = McNbt.fromGame(h.getLevel().getBlockEntity(first.pos()).saveWithoutMetadata(h.getLevel().registryAccess()));
            if (raw.getLong("SpawnCount", 0) != 7 || raw.getLong("Delay", 0) != 123 || !Objects.equals(raw.get("SpawnData"), first.raw().get("SpawnData"))) {
                h.fail("Write did not preserve the pool/countdown: " + raw); return;
            }
            var untouched = McNbt.fromGame(h.getLevel().getBlockEntity(stale.pos()).saveWithoutMetadata(h.getLevel().registryAccess()));
            if (untouched.getLong("SpawnCount", 0) != 9) { h.fail("Changed target was overwritten"); return; }
            h.succeed();
        });
    }
    @GameTest(structure = EMPTY, maxTicks = 100)
    public void permissionLossStopsRemainingWrites(GameTestHelper h) {
        var first = place(h, 4); var second = place(h, 6);
        var server = h.getLevel().getServer(); var allowed = new AtomicBoolean(true);
        var index = new ServerIndex(server, allowed::get);
        var writer = new ServerBatch(server, index, allowed::get, () -> {});
        writer.startChanges(List.of(first, second), first.spec(), first.spec().copy().set(SpawnerSpec.Setting.COUNT, 7));
        allowed.set(false);
        h.runAfterDelay(10, () -> {
            var raw = McNbt.fromGame(h.getLevel().getBlockEntity(second.pos()).saveWithoutMetadata(h.getLevel().registryAccess()));
            if (writer.busy() || raw.getLong("SpawnCount", 0) != 2) { h.fail("A write continued after the session ended"); return; }
            h.succeed();
        });
    }
    @GameTest(structure = EMPTY, maxTicks = 400)
    public void worldIndexAndVerifiedWriteRoundTrip(GameTestHelper h) {
        var target = place(h, 4);
        var service = new WorldService(h.getLevel().getServer());
        var reader = new Wire.Reader(); var player = UUID.randomUUID();
        var index = new java.util.concurrent.atomic.AtomicReference<Nbt.Compound>();
        var done = new java.util.concurrent.atomic.AtomicReference<Nbt.Compound>();
        var error = new java.util.concurrent.atomic.AtomicReference<String>();
        java.util.function.Consumer<byte[]> reply = part -> {
            try {
                var message = reader.accept(part);
                if (message == null) return;
                String op = message.body().getString("op", "");
                if (op.equals("index")) index.set(message.body());
                if (op.equals("error")) error.set(message.body().getString("message", ""));
                if (op.equals("progress") && message.body().getLong("busy", 0) == 0) done.set(message.body());
            } catch (java.io.IOException ex) { error.set(ex.toString()); }
        };
        for (byte[] part : Wire.encode(21, Nbt.compound().put("op", "index"))) service.receive(player, part, () -> true, reply);
        var applied = new AtomicBoolean();
        h.succeedWhen(() -> {
            h.assertTrue(error.get() == null, "RPC failed: " + error.get());
            h.assertTrue(index.get() != null, "Waiting for server index");
            if (!applied.get()) {
                boolean found = index.get().getList("entries").items().stream().anyMatch(n -> n instanceof Nbt.Compound row
                        && row.getLong("x", 0) == target.pos().getX() && row.getLong("y", 0) == target.pos().getY()
                        && row.getLong("z", 0) == target.pos().getZ() && row.getString("dimension", "").equals(target.dimension()));
                h.assertTrue(found, "World scan missed the test spawner");
                applied.set(true);
                var body = Nbt.compound().put("op", "apply").put("revision", index.get().getLong("revision", 0)).put("targets", Nbt.list(Nbt.str(target.key())))
                        .put("base", target.spec().toTag()).put("edited", target.spec().copy().set(SpawnerSpec.Setting.COUNT, 8).toTag());
                for (byte[] part : Wire.encode(22, body)) service.receive(player, part, () -> true, reply);
            }
            h.assertTrue(done.get() != null, "Waiting for verified write");
            h.assertTrue(done.get().getLong("completed", 0) == 1 && done.get().getLong("failed", 0) == 0, "Server did not confirm the write");
            var raw = McNbt.fromGame(h.getLevel().getBlockEntity(target.pos()).saveWithoutMetadata(h.getLevel().registryAccess()));
            h.assertTrue(raw.getLong("SpawnCount", 0) == 8, "RPC did not update the world");
            service.close();
        });
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void handshakeAndSessionResetUseBoundedProtocol(GameTestHelper h) {
        var service = new WorldService(h.getLevel().getServer());
        var reader = new Wire.Reader(); var responses = new ArrayList<Wire.Message>(); var id = UUID.randomUUID();
        java.util.function.Consumer<byte[]> reply = part -> {
            try { var message = reader.accept(part); if (message != null) responses.add(message); }
            catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        };
        for (byte[] frame : Wire.encode(11, Nbt.compound().put("op", "hello"))) service.receive(id, frame, () -> true, reply);
        if (responses.size() != 1 || responses.getFirst().body().getLong("version", 0) != Wire.VERSION
                || responses.getFirst().body().getLong("index", 0) != 1) { h.fail("No compatible handshake"); return; }
        responses.clear(); service.disconnect(id);
        for (byte[] frame : Wire.encode(12, Nbt.compound().put("op", "apply").put("targets", Nbt.list(Nbt.str("old snapshot"))))) service.receive(id, frame, () -> true, reply);
        if (responses.size() != 1 || !responses.getFirst().body().getString("op", "").equals("error")) { h.fail("Disconnected snapshot was accepted"); return; }
        responses.clear();
        for (byte[] frame : Wire.encode(13, Nbt.compound().put("op", "hello"))) service.receive(UUID.randomUUID(), frame, () -> false, reply);
        if (!responses.isEmpty()) { h.fail("Unauthorized session was offered hooks"); return; }
        service.close(); h.succeed();
    }
}
