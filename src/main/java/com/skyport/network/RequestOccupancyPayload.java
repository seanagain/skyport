package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "who is using this airport right now?"
 *
 * Asked for rather than pushed. Occupancy changes constantly - a clearance
 * can be taken and given back inside a second - so a server that sent every
 * change would be sending them to a screen nobody has open, for an airport
 * nobody is looking at. The map screen asks while its occupancy tab is open
 * and stops the moment it is not, which costs nothing when nobody cares.
 */
public record RequestOccupancyPayload(BlockPos stationPos) implements CustomPacketPayload {

    public static final Type<RequestOccupancyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "request_occupancy"));

    public static final StreamCodec<FriendlyByteBuf, RequestOccupancyPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> buf.writeBlockPos(payload.stationPos()),
                    buf -> new RequestOccupancyPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
