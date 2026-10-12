package com.skyport.network;

import com.skyport.data.AirportOccupancy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the answer to {@link RequestOccupancyPayload}.
 *
 * Carries the airport id it describes, so a reply that arrives after the
 * player has closed one station and opened another is recognised as being
 * about the wrong airport and dropped, rather than drawn over the new one.
 */
public record AirportOccupancyPayload(AirportOccupancy occupancy) implements CustomPacketPayload {

    public static final Type<AirportOccupancyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "airport_occupancy"));

    public static final StreamCodec<FriendlyByteBuf, AirportOccupancyPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.occupancy().write(buf),
                    buf -> new AirportOccupancyPayload(AirportOccupancy.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
