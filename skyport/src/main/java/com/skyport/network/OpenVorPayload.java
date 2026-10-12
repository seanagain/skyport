package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: "open the naming screen for this VOR beacon, which is
 * currently called this." Sent in answer to right-clicking one (see
 * VorBlockEntity#openScreen) once the lock has let the player in.
 */
public record OpenVorPayload(BlockPos pos, String name) implements CustomPacketPayload {

    public static final Type<OpenVorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_vor"));

    public static final StreamCodec<FriendlyByteBuf, OpenVorPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.name());
            },
            buf -> new OpenVorPayload(buf.readBlockPos(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
