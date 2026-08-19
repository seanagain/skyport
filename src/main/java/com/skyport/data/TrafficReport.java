package com.skyport.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A live snapshot of one aircraft under autopilot: enough for the ATC screen
 * to draw it and for other planes to keep out of its way.
 *
 * Never persisted - it describes an aeroplane that is moving right now, and a
 * stale one reloaded from disk would show traffic that isn't there.
 */
public record TrafficReport(UUID planeId, String callsign, String state,
                            Vec3 position, String destination) {

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(planeId);
        buf.writeUtf(callsign);
        buf.writeUtf(state);
        buf.writeDouble(position.x);
        buf.writeDouble(position.y);
        buf.writeDouble(position.z);
        buf.writeUtf(destination);
    }

    public static TrafficReport read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String callsign = buf.readUtf();
        String state = buf.readUtf();
        Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new TrafficReport(id, callsign, state, position, buf.readUtf());
    }
}
