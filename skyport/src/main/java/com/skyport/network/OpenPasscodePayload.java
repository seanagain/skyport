package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: open the passcode box for a block.
 *
 * Carries what the screen is allowed to know and nothing else. There is no
 * field here for the code, or its hash, or its length - the server is the
 * only thing that ever sees any of that, and a client asking the server
 * "what is the code" has to be a question the protocol cannot express.
 *
 * @param setting true for the owner setting or clearing a code, false for
 *                someone else being asked to enter one
 * @param hasCode whether a code is currently set, so the owner's box can
 *                offer to clear it rather than guessing
 * @param label   the block's name, for the title bar
 */
public record OpenPasscodePayload(BlockPos pos, boolean setting, boolean hasCode, String label)
        implements CustomPacketPayload {

    public static final Type<OpenPasscodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "open_passcode"));

    public static final StreamCodec<FriendlyByteBuf, OpenPasscodePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeBoolean(payload.setting());
                buf.writeBoolean(payload.hasCode());
                buf.writeUtf(payload.label(), 64);
            },
            buf -> new OpenPasscodePayload(
                    buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
