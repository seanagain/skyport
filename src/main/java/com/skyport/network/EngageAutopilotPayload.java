package com.skyport.network;

import com.skyport.data.FlightSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent client -> server when the player hits "Engage" in AutopilotScreen.
 * `pos` identifies which Autopilot block entity to call {@code engage(...)}
 * on, and `schedule` is the whole itinerary it should fly - a one-stop
 * schedule is the old "fly to this gate" case.
 *
 * All of this addon's payloads use plain FriendlyByteBuf (rather than
 * RegistryFriendlyByteBuf or ByteBuf) via StreamCodec.of, since none of
 * the data here needs registry-aware (de)serialization - just keeps every
 * payload in this package following the same simple pattern.
 */
public record EngageAutopilotPayload(BlockPos pos, FlightSchedule schedule) implements CustomPacketPayload {

    public static final Type<EngageAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "engage_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, EngageAutopilotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                payload.schedule().write(buf);
            },
            buf -> new EngageAutopilotPayload(buf.readBlockPos(), FlightSchedule.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
