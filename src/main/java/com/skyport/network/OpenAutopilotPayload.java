package com.skyport.network;

import com.skyport.data.AirportSummary;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: "open the destination picker for this autopilot
 * block, here's every airport that currently exists." Sent in response
 * to right-clicking an Autopilot block (see
 * AutopilotBlockEntity#openDestinationPicker).
 */
public record OpenAutopilotPayload(BlockPos autopilotPos, List<AirportSummary> airports) implements CustomPacketPayload {

    public static final Type<OpenAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, OpenAutopilotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.autopilotPos());
                buf.writeVarInt(payload.airports().size());
                for (AirportSummary summary : payload.airports()) summary.write(buf);
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                int count = buf.readVarInt();
                List<AirportSummary> airports = new ArrayList<>(count);
                for (int i = 0; i < count; i++) airports.add(AirportSummary.read(buf));
                return new OpenAutopilotPayload(pos, airports);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
