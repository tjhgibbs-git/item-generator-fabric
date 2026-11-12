package io.github.tjhgibbs.stacksmith.server;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.network.Wire;
import io.github.tjhgibbs.stacksmith.core.tags.TagScan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.CompletableFuture;

/** Optional Fabric RPC service. All entrypoints and replies run on the server thread. */
public final class WorldService implements AutoCloseable {
    private final MinecraftServer server;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean closed;
    private Session working;
    public WorldService(MinecraftServer server) { this.server = server; }
    public void receive(UUID player, byte[] bytes, BooleanSupplier authorized, Consumer<byte[]> reply) {
        if (closed || !authorized.getAsBoolean()) { disconnect(player); return; }
        Session s = sessions.computeIfAbsent(player, id -> new Session(id, authorized, reply));
        int requestId = 0;
        try {
            Wire.Message message = s.reader.accept(bytes);
            if (message != null) { requestId = message.id(); s.handle(message.id(), message.body()); }
        } catch (Exception ex) {
            s.reader.clear(); s.index.cancel(); s.scanning = false;
            if (working == s && !s.batch.busy()) working = null;
            s.send(requestId, error("Invalid request or server operation failed."));
        }
    }
    public void disconnect(UUID id) {
        Session s = sessions.remove(id);
        if (s != null) { s.index.cancel(); s.batch.cancel(); if (working == s) working = null; }
    }
    @Override public void close() { closed = true; for (UUID id : List.copyOf(sessions.keySet())) disconnect(id); }
    private static Nbt.Compound error(String text) { return Nbt.compound().put("op", "error").put("message", text); }
    private final class Session {
        final UUID player;
        final BooleanSupplier authorized;
        final Consumer<byte[]> reply;
        final Wire.Reader reader = new Wire.Reader();
        final ServerIndex index;
        final ServerBatch batch;
        int request;
        long revision;
        long lastScan;
        int operation;
        boolean scanning;
        Session(UUID player, BooleanSupplier authorized, Consumer<byte[]> reply) {
            this.player = player; this.authorized = authorized; this.reply = reply;
            index = new ServerIndex(server, this::active);
            batch = new ServerBatch(server, index, this::active, this::progress);
        }
        boolean active() { return !closed && sessions.get(player) == this && authorized.getAsBoolean(); }
        void send(int id, Nbt.Compound body) {
            if (!active()) return;
            try { for (byte[] part : Wire.encode(id, body)) reply.accept(part); }
            catch (IllegalArgumentException ex) { for (byte[] part : Wire.encode(id, error("Result exceeds the 8 MiB transfer limit."))) reply.accept(part); }
        }
        void handle(int id, Nbt.Compound body) {
            String op = body.getString("op", "");
            if (op.equals("hello")) {
                send(id, Nbt.compound().put("op", "hello").put("version", Wire.VERSION)
                        .put("index", 1).put("apply", 1).put("tags", 1)); return;
            }
            if (op.equals("cancel")) {
                operation++;
                index.cancel(); scanning = false; batch.cancel();
                if (working == this && !batch.busy()) working = null;
                send(id, Nbt.compound().put("op", "cancelled")); return;
            }
            if (working != null) { send(id, error("Another world operation is running. Try again when it finishes.")); return; }
            switch (op) {
                case "index" -> {
                    if (System.currentTimeMillis() - lastScan < 5000) { send(id, error("Wait a few seconds before rescanning.")); return; }
                    lastScan = System.currentTimeMillis(); request = id; scanning = true; working = this;
                    int token = ++operation;
                    index.refresh(warning -> {
                        if (token != operation) return;
                        if (!active()) { if (working == this) working = null; return; }
                        scanning = false; working = null;
                        if (warning.startsWith("Could not")) send(id, error(warning));
                        else { revision++; send(id, snapshot(warning)); }
                    });
                }
                case "apply" -> {
                    if (revision == 0 || body.getLong("revision", 0) != revision) {
                        send(id, error("Snapshot expired. Rescan and review the targets before applying.")); return;
                    }
                    Nbt.List keys = body.getList("targets");
                    if (keys == null || keys.size() == 0 || keys.size() > 100_000) { send(id, error("Invalid targets.")); return; }
                    List<ServerIndex.Entry> targets = new ArrayList<>(); Set<String> seen = new HashSet<>();
                    for (Nbt key : keys.items()) {
                        if (!(key instanceof Nbt.Str str) || !seen.add(str.value()) || !index.entries.containsKey(str.value())) {
                            send(id, error("Snapshot expired or target missing. Rescan before applying.")); return;
                        }
                        targets.add(index.entries.get(str.value()));
                    }
                    Nbt.Compound tag = body.getCompound("edited");
                    if (tag == null) { send(id, error("Missing spawner definition.")); return; }
                    SpawnerSpec edited = SpawnerSpec.fromTag(tag);
                    request = id; working = this;
                    String why = body.getCompound("base") != null
                            ? batch.startChanges(targets, SpawnerSpec.fromTag(body.getCompound("base")), edited)
                            : batch.start(targets, edited, body.getLong("pool", 0) != 0, body.getLong("settings", 0) != 0);
                    if (why != null) { working = null; send(id, error(why)); }
                }
                case "tags" -> {
                    working = this;
                    int token = ++operation;
                    var folder = server.getWorldPath(LevelResource.DATAPACK_DIR);
                    CompletableFuture.supplyAsync(() -> TagScan.scanPacks(folder)).whenComplete((result, failure) -> server.execute(() -> {
                        if (token != operation) return;
                        if (!active()) { if (working == this) working = null; return; }
                        working = null;
                        if (failure != null) { send(id, error("Datapack scan failed.")); return; }
                        Nbt.Compound tags = Nbt.compound();
                        result.tags().forEach((name, sources) -> { Nbt.List list = Nbt.list(); sources.forEach(source -> list.add(new Nbt.Str(source))); tags.put(name, list); });
                        send(id, Nbt.compound().put("op", "tags").put("tags", tags).put("files", result.files()));
                    }));
                }
                default -> send(id, error("Unsupported Stacksmith operation."));
            }
        }
        Nbt.Compound snapshot(String warning) {
            Nbt.List list = Nbt.list();
            index.entries.values().forEach(e -> list.add(Nbt.compound().put("dimension", e.dimension())
                    .put("x", e.pos().getX()).put("y", e.pos().getY()).put("z", e.pos().getZ()).put("raw", e.raw())));
            return Nbt.compound().put("op", "index").put("revision", revision).put("entries", list).put("warning", warning);
        }
        void progress() {
            if (!active()) { if (working == this) working = null; batch.cancel(); return; }
            Nbt.Compound out = Nbt.compound().put("op", "progress").put("busy", batch.busy ? 1 : 0)
                    .put("completed", batch.completed).put("skipped", batch.skipped).put("failed", batch.failed)
                    .put("total", batch.total).put("status", batch.status);
            if (!batch.busy) {
                working = null;
                Nbt.List problems = Nbt.list();
                batch.problems.forEach(p -> problems.add(Nbt.compound().put("key", p.entry().key()).put("reason", p.reason())));
                out.put("problems", problems);
                revision++;
                out.put("snapshot", snapshot(""));
            }
            send(request, out);
        }
    }
}
