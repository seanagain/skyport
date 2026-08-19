package com.skyport.network;

import com.skyport.data.AirportLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "here's the layout I drew, save it." `stationPos"
 * identifies which Airport Station block entity to hand the layout to.
 */
public record SaveAirportLayoutPayload(BlockPos stationPos, AirportLayout layout) implements CustomPacketPayload {

    public static final Type<SaveAirportLayoutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "save_airport_layout"));

    public static final StreamCodec<FriendlyByteBuf, SaveAirportLayoutPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.stationPos());
                payload.layout().write(buf);
            },
            buf -> new SaveAirportLayoutPayload(buf.readBlockPos(), AirportLayout.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
