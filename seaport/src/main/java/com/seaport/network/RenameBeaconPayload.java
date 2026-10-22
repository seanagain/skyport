package com.seaport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "call this mark that".
 *
 * The name is sent raw and cleaned on arrival. A packet can say anything -
 * formatting codes that restyle every line the name appears in, control
 * characters, a thousand characters of it - so the server decides what a mark
 * is actually called. See Beacon#cleanName.
 */
public record RenameBeaconPayload(BlockPos beaconPos, String name) implements CustomPacketPayload {

    /** A hard cap on the wire, well above the length a name is cleaned down
     *  to, so a hostile client cannot make the server allocate a megabyte
     *  before it gets as far as trimming it. */
    private static final int MAX_WIRE_LENGTH = 256;

    public static final Type<RenameBeaconPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("seaport", "rename_beacon"));

    public static final StreamCodec<FriendlyByteBuf, RenameBeaconPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.beaconPos());
                        buf.writeUtf(payload.name(), MAX_WIRE_LENGTH);
                    },
                    buf -> new RenameBeaconPayload(buf.readBlockPos(), buf.readUtf(MAX_WIRE_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
