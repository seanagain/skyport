package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "I want to manage this block's lock."
 *
 * A request rather than a command. The client cannot open the passcode box
 * on its own, because deciding who may set a code is exactly the thing the
 * client must not decide - the server answers with OpenPasscodePayload, or
 * does not.
 *
 * This exists because the original way in - sneak and right-click - only
 * reaches a block when both hands are empty, and the player is almost
 * always holding the block they just placed. A button inside the screen
 * they already opened does not depend on what they happen to be carrying.
 */
public record LockRequestPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<LockRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "lock_request"));

    public static final StreamCodec<FriendlyByteBuf, LockRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new LockRequestPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
