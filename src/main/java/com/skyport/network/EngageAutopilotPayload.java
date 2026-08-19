package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent client -> server when the player picks a destination in
 * AutopilotScreen and hits "Engage". `pos` identifies which Autopilot
 * block entity to call {@code engage(...)} on.
 *
 * All of this addon's payloads use plain FriendlyByteBuf (rather than
 * RegistryFriendlyByteBuf or ByteBuf) via StreamCodec.of, since none of
 * the data here needs registry-aware (de)serialization - just keeps every
 * payload in this package following the same simple pattern.
 */
public record EngageAutopilotPayload(BlockPos pos, UUID airportId, String gateName) implements CustomPacketPayload {

    public static final Type<EngageAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "engage_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, EngageAutopilotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUUID(payload.airportId());
                buf.writeUtf(payload.gateName());
            },
            buf -> new EngageAutopilotPayload(buf.readBlockPos(), buf.readUUID(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
