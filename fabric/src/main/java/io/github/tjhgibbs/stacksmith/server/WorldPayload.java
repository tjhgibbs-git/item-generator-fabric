package io.github.tjhgibbs.stacksmith.server;

import io.github.tjhgibbs.stacksmith.core.network.Wire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded frame of the optional world protocol, without an extra length prefix. */
public record WorldPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<WorldPayload> TYPE = new Type<>(Identifier.parse(Wire.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldPayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeBytes(value.bytes()), buf -> {
                int size = buf.readableBytes();
                if (size > Wire.PART + 16) throw new IllegalArgumentException("Oversized Stacksmith frame");
                byte[] bytes = new byte[size]; buf.readBytes(bytes); return new WorldPayload(bytes);
            });
    @Override public Type<WorldPayload> type() { return TYPE; }
}
