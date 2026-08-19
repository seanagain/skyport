package com.skyport.network;

import com.skyport.data.AirportLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: "open the map editor for this station, seeded with
 * this layout." Sent in response to right-clicking an Airport Station
 * block (see AirportStationBlockEntity#openMapEditor).
 */
public record OpenAirportMapPayload(BlockPos stationPos, AirportLayout layout) implements CustomPacketPayload {

    public static final Type<OpenAirportMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_airport_map"));

    public static final StreamCodec<FriendlyByteBuf, OpenAirportMapPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.stationPos());
                payload.layout().write(buf);
            },
            buf -> new OpenAirportMapPayload(buf.readBlockPos(), AirportLayout.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
