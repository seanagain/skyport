package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Just enough about one airport to populate AutopilotScreen's pickers -
 * built server-side from {@link AirportLayout} (see AirportRegistry),
 * sent to the client instead of the full layout since the autopilot GUI
 * never needs the actual runway/taxiway/holding-pattern geometry, only
 * which airports exist and which gates and pads each one has.
 */
public record AirportSummary(UUID id, String displayName, List<String> gateNames,
                             List<String> padNames, BlockPos position) {

    public static AirportSummary of(AirportLayout layout) {
        return new AirportSummary(layout.id(), layout.displayName(),
                List.copyOf(layout.gates().keySet()),
                List.copyOf(layout.helipads().keySet()), locate(layout));
    }

    /**
     * Somewhere sensible to draw this airport on a map. The layout has no
     * single "position" of its own, so take the runway if there is one (that
     * is what an airport IS, effectively), and fall back through the other
     * drawn elements rather than reporting the origin for a half-built field.
     */
    private static BlockPos locate(AirportLayout layout) {
        List<Waypoint> runway = layout.waypoints(Waypoint.Type.RUNWAY);
        if (!runway.isEmpty()) return runway.get(0).pos();
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);
        if (!taxiway.isEmpty()) return taxiway.get(0).pos();
        if (!layout.gates().isEmpty()) return layout.gates().values().iterator().next();
        return BlockPos.ZERO;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(displayName);
        buf.writeVarInt(gateNames.size());
        for (String name : gateNames) buf.writeUtf(name);
        buf.writeVarInt(padNames.size());
        for (String name : padNames) buf.writeUtf(name);
        buf.writeBlockPos(position);
    }

    public static AirportSummary read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        int count = buf.readVarInt();
        List<String> gates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) gates.add(buf.readUtf());
        int padCount = buf.readVarInt();
        List<String> pads = new ArrayList<>(padCount);
        for (int i = 0; i < padCount; i++) pads.add(buf.readUtf());
        return new AirportSummary(id, name, gates, pads, buf.readBlockPos());
    }
}
