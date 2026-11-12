package io.github.tjhgibbs.stacksmith.server;

import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;

public final class FabricHooks {
    private FabricHooks() {}
    private static final Map<MinecraftServer, WorldService> services = new IdentityHashMap<>();
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(WorldPayload.TYPE, WorldPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldPayload.TYPE, WorldPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(WorldPayload.TYPE, (packet, context) -> {
            var player = context.player();
            var server = player.level().getServer();
            server.execute(() -> services.computeIfAbsent(server, WorldService::new).receive(player.getUUID(), packet.bytes(),
                    () -> server.getPlayerList().getPlayer(player.getUUID()) == player
                            && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
                    bytes -> ServerPlayNetworking.send(player, new WorldPayload(bytes))));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WorldService service = services.get(server);
            if (service != null) service.disconnect(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WorldService service = services.remove(server);
            if (service != null) service.close();
        });
    }
}
