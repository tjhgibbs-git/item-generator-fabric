package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.nbt.Nbt;
import io.github.tjhgibbs.stacksmith.core.network.Wire;
import io.github.tjhgibbs.stacksmith.server.WorldPayload;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.function.Consumer;

/** Optional versioned connection. Channel registration alone never enables an operation. */
final class ServerHooks {
    private ServerHooks() {}
    static boolean index, apply, tags;
    static final String REQUIRED = "Requires compatible Stacksmith world hooks on this server (Fabric server mod).";
    private static Object connection;
    private static int serial, ticks;
    private static final Wire.Reader reader = new Wire.Reader();
    private record Pending(Consumer<Nbt.Compound> reply, long deadline, boolean work) {}
    private static final Map<Integer, Pending> pending = new HashMap<>();
    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(WorldPayload.TYPE, (payload, context) -> {
            Object receivedOn = Minecraft.getInstance().getConnection();
            context.client().execute(() -> {
                if (connection != receivedOn) return;
                try {
                    Wire.Message message = reader.accept(payload.bytes());
                    if (message == null) return;
                    Pending p = pending.get(message.id());
                    if (p == null) return;
                    String op = message.body().getString("op", "");
                    if (!op.equals("progress") || message.body().getLong("busy", 0) == 0) pending.remove(message.id());
                    else pending.put(message.id(), new Pending(p.reply(), System.currentTimeMillis() + 300_000, p.work()));
                    p.reply().accept(message.body());
                } catch (Exception ex) { reset("Invalid response from server hooks."); }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset("Disconnected."));
    }
    static void tick() {
        Object now = Minecraft.getInstance().getConnection();
        if (connection != now) { reset("Server changed."); connection = now; }
        if (now == null) return;
        if (++ticks % 100 == 20 && !index && pending.isEmpty() && ClientPlayNetworking.canSend(WorldPayload.TYPE)) {
            request(Nbt.compound().put("op", "hello"), reply -> {
                if (!reply.getString("op", "").equals("hello") || reply.getLong("version", 0) != Wire.VERSION) return;
                index = reply.getLong("index", 0) != 0; apply = reply.getLong("apply", 0) != 0; tags = reply.getLong("tags", 0) != 0;
            });
        }
        long nowMs = System.currentTimeMillis();
        for (var e : List.copyOf(pending.entrySet())) if (nowMs > e.getValue().deadline()) {
            if (!pending.containsKey(e.getKey())) continue;
            pending.remove(e.getKey());
            if (e.getValue().work()) cancel();
            e.getValue().reply().accept(error("Server operation timed out. Rescan before retrying."));
        }
    }
    static void reset(String reason) {
        index = apply = tags = false; ticks = 0; reader.clear();
        var old = List.copyOf(pending.values()); pending.clear();
        connection = null;
        SpawnerIndex.remoteReset();
        for (Pending p : old) p.reply().accept(error(reason));
    }
    static Nbt.Compound error(String message) { return Nbt.compound().put("op", "error").put("message", message); }
    static void request(Nbt.Compound body, Consumer<Nbt.Compound> reply) {
        if (Minecraft.getInstance().getConnection() == null || !ClientPlayNetworking.canSend(WorldPayload.TYPE)) { reply.accept(error(REQUIRED)); return; }
        int id = ++serial;
        try {
            var frames = Wire.encode(id, body);
            String op = body.getString("op", "");
            pending.put(id, new Pending(reply, System.currentTimeMillis() + 300_000,
                    op.equals("index") || op.equals("apply") || op.equals("tags")));
            for (byte[] frame : frames) ClientPlayNetworking.send(new WorldPayload(frame));
        } catch (RuntimeException ex) { pending.remove(id); reply.accept(error("Could not send request: " + ex.getMessage())); }
    }
    static void cancel() {
        var work = pending.entrySet().stream().filter(e -> e.getValue().work()).toList();
        work.forEach(e -> pending.remove(e.getKey()));
        request(Nbt.compound().put("op", "cancel"), ignored -> {});
        work.forEach(e -> e.getValue().reply().accept(error("Cancelled · rescan to refresh results.")));
    }
}
