package com.seaport.network;

import com.seaport.data.PortLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "keep this port". */
public record SavePortLayoutPayload(BlockPos stationPos, PortLayout layout)
        implements CustomPacketPayload {

    public static final Type<SavePortLayoutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "save_port_layout"));

    public static final StreamCodec<FriendlyByteBuf, SavePortLayoutPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.stationPos());
                        payload.layout().write(buf);
                    },
                    buf -> new SavePortLayoutPayload(buf.readBlockPos(), PortLayout.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
