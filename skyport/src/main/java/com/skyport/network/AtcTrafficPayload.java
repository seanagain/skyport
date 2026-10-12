package com.skyport.network;

import com.skyport.data.AirportLayout;
import com.skyport.data.TrafficReport;
import com.skyport.data.VorBeacon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Refresh pair for an open ATC screen.
 *
 * {@link Request} goes client -> server a few times a second while the
 * screen is open, and the reply carries the airports and VOR beacons as well
 * as the traffic. Airports were left out originally on the grounds that they
 * don't change while you watch - but they do: breaking a station deletes one,
 * and the map went on showing a ghost until the screen was reopened. VORs
 * are broken just as easily.
 *
 * Polling rather than a subscription
 * because it needs no server-side bookkeeping of who has a screen open, and
 * it stops the moment the screen closes - a plane that flies for an hour
 * with nobody watching costs nothing.
 *
 * @param towerPos where the ATC block itself is, resent every refresh
 *                 because a tower mounted on an aircraft moves. On the
 *                 ground this is the same value every time and costs a
 *                 handful of bytes; in the air it is the difference between
 *                 a "you are here" marker that tracks and one frozen where
 *                 the screen happened to be opened.
 */
public record AtcTrafficPayload(List<AirportLayout> airports,
                                List<VorBeacon> vors,
                                List<TrafficReport> traffic,
                                net.minecraft.core.BlockPos towerPos) implements CustomPacketPayload {

    public static final Type<AtcTrafficPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "atc_traffic"));

    public static final StreamCodec<FriendlyByteBuf, AtcTrafficPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.airports().size());
                for (AirportLayout airport : payload.airports()) airport.write(buf);
                buf.writeVarInt(payload.vors().size());
                for (VorBeacon vor : payload.vors()) vor.write(buf);
                buf.writeVarInt(payload.traffic().size());
                for (TrafficReport report : payload.traffic()) report.write(buf);
                buf.writeBlockPos(payload.towerPos());
            },
            buf -> {
                int airportCount = buf.readVarInt();
                List<AirportLayout> airports = new ArrayList<>(airportCount);
                for (int i = 0; i < airportCount; i++) airports.add(AirportLayout.read(buf));
                int vorCount = buf.readVarInt();
                List<VorBeacon> vors = new ArrayList<>(vorCount);
                for (int i = 0; i < vorCount; i++) vors.add(VorBeacon.read(buf));
                int count = buf.readVarInt();
                List<TrafficReport> traffic = new ArrayList<>(count);
                for (int i = 0; i < count; i++) traffic.add(TrafficReport.read(buf));
                return new AtcTrafficPayload(airports, vors, traffic, buf.readBlockPos());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client -> server: "I have an ATC screen open, send me the traffic." */
    public record Request() implements CustomPacketPayload {

        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "atc_traffic_request"));

        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC =
                StreamCodec.of((buf, payload) -> { }, buf -> new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
