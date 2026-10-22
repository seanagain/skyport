package com.seaport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: "open this mark's editor, and here is what it is called".
 *
 * The name travels with the packet rather than the client asking afterwards,
 * so the box is never briefly blank and never loses a name because a reply
 * did not arrive.
 */
public record OpenMarineBeaconPayload(BlockPos beaconPos, String name)
        implements CustomPacketPayload {

    public static final Type<OpenMarineBeaconPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "open_marine_beacon"));

    public static final StreamCodec<FriendlyByteBuf, OpenMarineBeaconPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.beaconPos());
                        buf.writeUtf(payload.name());
                    },
                    buf -> new OpenMarineBeaconPayload(buf.readBlockPos(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
