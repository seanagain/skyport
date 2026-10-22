package com.seaport.network;

import com.seaport.data.PortDirectory;
import com.seaport.data.VoyageSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: "open the helm, here are its orders, and here is
 * everywhere it could be sent".
 *
 * The directory travels with the packet because the client has no registry
 * of its own - the harbours and marks are world save data, and most of them
 * are in chunks nobody has loaded. A screen that could only offer what
 * happens to be nearby would be useless for exactly the routes this mod
 * exists to run.
 */
public record OpenMarineAutopilotPayload(BlockPos autopilotPos, String callsign, boolean running,
                                         VoyageSchedule schedule, PortDirectory directory)
        implements CustomPacketPayload {

    public static final Type<OpenMarineAutopilotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "open_marine_autopilot"));

    public static final StreamCodec<FriendlyByteBuf, OpenMarineAutopilotPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.autopilotPos());
                        buf.writeUtf(payload.callsign(), 256);
                        buf.writeBoolean(payload.running());
                        payload.schedule().write(buf);
                        payload.directory().write(buf);
                    },
                    buf -> new OpenMarineAutopilotPayload(
                            buf.readBlockPos(), buf.readUtf(256), buf.readBoolean(),
                            VoyageSchedule.read(buf), PortDirectory.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
