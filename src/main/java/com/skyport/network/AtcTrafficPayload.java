package com.skyport.network;

import com.skyport.data.TrafficReport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Refresh pair for an open ATC screen.
 *
 * {@link Request} goes client -> server a few times a second while the screen
 * is open; the reply carries only the traffic, since airports and terrain
 * don't change while you're watching. Polling rather than a subscription
 * because it needs no server-side bookkeeping of who has a screen open, and
 * it stops the moment the screen closes - a plane that flies for an hour
 * with nobody watching costs nothing.
 */
public record AtcTrafficPayload(List<TrafficReport> traffic) implements CustomPacketPayload {

    public static final Type<AtcTrafficPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "atc_traffic"));

    public static final StreamCodec<FriendlyByteBuf, AtcTrafficPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.traffic().size());
                for (TrafficReport report : payload.traffic()) report.write(buf);
            },
            buf -> {
                int count = buf.readVarInt();
                List<TrafficReport> traffic = new ArrayList<>(count);
                for (int i = 0; i < count; i++) traffic.add(TrafficReport.read(buf));
                return new AtcTrafficPayload(traffic);
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
