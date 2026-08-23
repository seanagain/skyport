package com.skyport.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -&gt; server: "load the world around this parked aircraft for a while,
 * so its schedule can start running again."
 *
 * Carries the aircraft rather than a position because the whole point is that
 * the aircraft is somewhere the player is not - possibly in another
 * dimension. The server looks up where it parked.
 */
public record WakeAircraftPayload(UUID planeId) implements CustomPacketPayload {

    public static final Type<WakeAircraftPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("skyport", "wake_aircraft"));

    public static final StreamCodec<FriendlyByteBuf, WakeAircraftPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUUID(payload.planeId()),
            buf -> new WakeAircraftPayload(buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
