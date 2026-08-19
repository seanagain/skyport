package com.skyport.data;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Just enough about one airport to populate AutopilotScreen's pickers -
 * built server-side from {@link AirportLayout} (see AirportRegistry),
 * sent to the client instead of the full layout since the autopilot GUI
 * never needs the actual runway/taxiway/holding-pattern geometry, only
 * "which airports exist" and "which gates does each one have".
 */
public record AirportSummary(UUID id, String displayName, List<String> gateNames) {

    public static AirportSummary of(AirportLayout layout) {
        return new AirportSummary(layout.id(), layout.displayName(), List.copyOf(layout.gates().keySet()));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(displayName);
        buf.writeVarInt(gateNames.size());
        for (String name : gateNames) buf.writeUtf(name);
    }

    public static AirportSummary read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        int count = buf.readVarInt();
        List<String> gates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) gates.add(buf.readUtf());
        return new AirportSummary(id, name, gates);
    }
}
