package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "name this VOR beacon this."
 *
 * The name is read with a length limit, and cleaned again on arrival (see
 * VorBeacon#cleanName). The screen caps what can be typed, but the screen is
 * not the only thing that can send this.
 */
public record RenameVorPayload(BlockPos pos, String name) implements CustomPacketPayload {

    /** Generous next to the 24 characters a name may actually keep - this
     *  only stops a client making the server read a megabyte first. */
    private static final int MAX_WIRE_LENGTH = 64;

    public static final Type<RenameVorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "rename_vor"));

    public static final StreamCodec<FriendlyByteBuf, RenameVorPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.name(), MAX_WIRE_LENGTH);
            },
            buf -> new RenameVorPayload(buf.readBlockPos(), buf.readUtf(MAX_WIRE_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
