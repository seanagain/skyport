package com.seaport.network;

import com.seaport.data.PortLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: "open this port's editor, and here is the port".
 *
 * The layout travels with the packet rather than the client asking for it
 * afterwards, for the same reason Skyport's autopilot screen carries its
 * schedule: a screen that builds a blank one and fills it in later shows the
 * player an empty harbour for a frame, and loses their work if the reply
 * never comes.
 */
public record OpenPortStationPayload(BlockPos stationPos, PortLayout layout)
        implements CustomPacketPayload {

    public static final Type<OpenPortStationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "open_port_station"));

    public static final StreamCodec<FriendlyByteBuf, OpenPortStationPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.stationPos());
                        payload.layout().write(buf);
                    },
                    buf -> new OpenPortStationPayload(buf.readBlockPos(), PortLayout.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
