package com.skyport.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: either an attempt at a block's passcode, or the owner
 * setting a new one.
 *
 * The length cap is not politeness. Everything arriving here is attacker-
 * controlled, and an uncapped string is a free way to make the server hash
 * a megabyte per packet; readUtf refuses anything longer before a byte of
 * it is looked at.
 *
 * @param setting true if the owner is setting this as the new code, false
 *                if it is someone trying to get in
 * @param code    the code, blank to clear it when setting
 */
public record PasscodePayload(BlockPos pos, boolean setting, String code) implements CustomPacketPayload {

    /** Long enough for a real passphrase, short enough to be free to hash. */
    public static final int MAX_LENGTH = 64;

    public static final Type<PasscodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "passcode"));

    public static final StreamCodec<FriendlyByteBuf, PasscodePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeBoolean(payload.setting());
                buf.writeUtf(payload.code(), MAX_LENGTH);
            },
            buf -> new PasscodePayload(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(MAX_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
