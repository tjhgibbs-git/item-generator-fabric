package io.github.tjhgibbs.stacksmith.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Which way the world tools reach the world. In order of preference: the server this game is
 * running (singleplayer or LAN host), the optional Stacksmith hooks on a dedicated server, or
 * plain commands and {@code /data} queries over the chunks loaded around you. Chosen afresh each
 * time; never a setting.
 */
final class Backend {
    private Backend() {}

    /**
     * Dev switch ({@code -Dstacksmith.clientOnly=true}): behave as if there were no integrated
     * server and no hooks, so the command path can be driven in singleplayer. Commands and
     * {@code /data} queries work the same against the integrated server.
     */
    static final boolean CLIENT_ONLY = Boolean.getBoolean("stacksmith.clientOnly");

    /** The server this game runs, or null (also null under {@link #CLIENT_ONLY}). */
    static MinecraftServer host() {
        return CLIENT_ONLY ? null : Minecraft.getInstance().getSingleplayerServer();
    }

    /** Neither a host nor hooks for {@code hooked}: the command path. */
    static boolean commandsOnly(boolean hooked) {
        return host() == null && !hooked;
    }

    /** The current world, for "is this still the world we scanned": the host, else the connection. */
    static Object world() {
        MinecraftServer host = host();
        return host != null ? host : Minecraft.getInstance().getConnection();
    }

    static String dimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.dimension().identifier().toString();
    }

    /** How far from you the client has chunks loaded, in blocks (the reach of a client-side scan). */
    static int loadedReach() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }
}
