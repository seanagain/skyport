package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "take this Autopilot block back off autopilot." */
public record DisengageAutopilotPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<DisengageAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "disengage_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, DisengageAutopilotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new DisengageAutopilotPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
