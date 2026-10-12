package com.skyport.network;

import com.skyport.SkyportConfig;
import com.skyport.data.AirportSummary;
import com.skyport.data.FlightSchedule;
import com.skyport.data.VorBeacon;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: "open the destination picker for this autopilot
 * block, here's every airport and VOR beacon that currently exists, and
 * here's the schedule it already has." Sent in response
 * to right-clicking an Autopilot block (see
 * AutopilotBlockEntity#openDestinationPicker).
 *
 * The schedule round-trips deliberately: without it the screen built a
 * fresh empty one every time it opened, so reopening an autopilot silently
 * discarded the route you had already set.
 *
 * The server power mode comes along for the same reason the airports do: the
 * screen offers a rotation-or-fuel choice only where the server allows either,
 * and a client has no way to read a server config it was never sent.
 */
public record OpenAutopilotPayload(BlockPos autopilotPos, List<AirportSummary> airports,
                                   List<VorBeacon> vors, FlightSchedule schedule,
                                   SkyportConfig.PowerRequirement serverPower) implements CustomPacketPayload {

    public static final Type<OpenAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, OpenAutopilotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.autopilotPos());
                buf.writeVarInt(payload.airports().size());
                for (AirportSummary summary : payload.airports()) summary.write(buf);
                buf.writeVarInt(payload.vors().size());
                for (VorBeacon vor : payload.vors()) vor.write(buf);
                payload.schedule().write(buf);
                buf.writeEnum(payload.serverPower());
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                int count = buf.readVarInt();
                List<AirportSummary> airports = new ArrayList<>(count);
                for (int i = 0; i < count; i++) airports.add(AirportSummary.read(buf));
                int vorCount = buf.readVarInt();
                List<VorBeacon> vors = new ArrayList<>(vorCount);
                for (int i = 0; i < vorCount; i++) vors.add(VorBeacon.read(buf));
                FlightSchedule schedule = FlightSchedule.read(buf);
                return new OpenAutopilotPayload(pos, airports, vors, schedule,
                        buf.readEnum(SkyportConfig.PowerRequirement.class));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
