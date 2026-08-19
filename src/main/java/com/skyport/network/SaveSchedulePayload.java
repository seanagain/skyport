package com.skyport.network;

import com.skyport.data.FlightSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "keep this schedule, but don't fly it yet."
 *
 * Separate from engaging because writing a route and starting a flight are
 * different intentions - you might set a plane's schedule now and have
 * redstone launch it later, or just close the screen mid-edit without
 * wanting the work thrown away.
 */
public record SaveSchedulePayload(BlockPos pos, FlightSchedule schedule) implements CustomPacketPayload {

    public static final Type<SaveSchedulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "save_schedule"));

    public static final StreamCodec<FriendlyByteBuf, SaveSchedulePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                payload.schedule().write(buf);
            },
            buf -> new SaveSchedulePayload(buf.readBlockPos(), FlightSchedule.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
