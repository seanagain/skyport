package com.seaport.network;

import com.seaport.data.VoyageSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: the orders as edited.
 *
 * Sent whole on close rather than a stop at a time. A half-saved schedule is
 * worse than an unsaved one - it is a vessel sailing a route nobody wrote -
 * and the Port Station's layout settled the same question the same way.
 */
public record SaveVoyageSchedulePayload(BlockPos autopilotPos, String callsign,
                                        boolean running, VoyageSchedule schedule)
        implements CustomPacketPayload {

    public static final Type<SaveVoyageSchedulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "save_voyage_schedule"));

    public static final StreamCodec<FriendlyByteBuf, SaveVoyageSchedulePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.autopilotPos());
                        buf.writeUtf(payload.callsign(), 256);
                        buf.writeBoolean(payload.running());
                        payload.schedule().write(buf);
                    },
                    buf -> new SaveVoyageSchedulePayload(
                            buf.readBlockPos(), buf.readUtf(256), buf.readBoolean(),
                            VoyageSchedule.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
