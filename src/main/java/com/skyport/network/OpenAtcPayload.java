package com.skyport.network;

import com.skyport.data.AirportSummary;
import com.skyport.data.TrafficReport;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: "open the ATC screen with this picture of the world."
 *
 * A snapshot rather than a subscription: the screen shows where everything
 * was when you opened it, and refreshes by asking again. Streaming live
 * positions to an open GUI would mean per-tick packets for something a player
 * glances at, which isn't worth the traffic.
 */
public record OpenAtcPayload(BlockPos atcPos, List<AirportSummary> airports,
                             List<TrafficReport> traffic) implements CustomPacketPayload {

    public static final Type<OpenAtcPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_atc"));

    public static final StreamCodec<FriendlyByteBuf, OpenAtcPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.atcPos());
                buf.writeVarInt(payload.airports().size());
                for (AirportSummary airport : payload.airports()) airport.write(buf);
                buf.writeVarInt(payload.traffic().size());
                for (TrafficReport report : payload.traffic()) report.write(buf);
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                int airportCount = buf.readVarInt();
                List<AirportSummary> airports = new ArrayList<>(airportCount);
                for (int i = 0; i < airportCount; i++) airports.add(AirportSummary.read(buf));
                int trafficCount = buf.readVarInt();
                List<TrafficReport> traffic = new ArrayList<>(trafficCount);
                for (int i = 0; i < trafficCount; i++) traffic.add(TrafficReport.read(buf));
                return new OpenAtcPayload(pos, airports, traffic);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
