package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way a departure rolls on a single-runway field.
 *
 * Landing and taxi routing both anchor to the physical gate end - the point
 * the taxiway network was actually built to reach - and neither of those is
 * what this setting changes. Only the roll direction is, so the tests here
 * are really about takeoffRoll() disagreeing with departureRunway() exactly
 * when it should and never otherwise.
 */
class TakeoffDirectionTest {

    private static final BlockPos GATE_END = new BlockPos(0, 64, 0);
    private static final BlockPos FAR_END = new BlockPos(100, 64, 0);

    private static AirportLayout singleRunway() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Heathrow", Level.OVERWORLD);
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(GATE_END, Waypoint.Type.RUNWAY, 0));
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(FAR_END, Waypoint.Type.RUNWAY, 1));
        return layout;
    }

    @Test
    void normallyRollsGateEndToFarEnd() {
        AirportLayout layout = singleRunway();
        assertEquals(java.util.List.of(GATE_END, FAR_END), layout.takeoffRoll());
    }

    @Test
    void reversedRollsFarEndToGateEnd() {
        AirportLayout layout = singleRunway();
        layout.setReversedTakeoff(true);
        assertEquals(java.util.List.of(FAR_END, GATE_END), layout.takeoffRoll());
    }

    /** Taxi routing is not this setting's business - it has to keep aiming at
     *  the real, physically-connected gate end whichever way the roll goes,
     *  or a reversed departure would be sent taxiing toward a point no
     *  taxiway was ever built to reach. */
    @Test
    void departureRunwayNeverReverses() {
        AirportLayout layout = singleRunway();
        layout.setReversedTakeoff(true);
        assertEquals(java.util.List.of(GATE_END, FAR_END), layout.departureRunway());
    }

    /** A dedicated departure runway already says which way it goes by how it
     *  was drawn - this flag would be a second, conflicting answer to the
     *  same question, so it is ignored the moment one exists. */
    @Test
    void aDedicatedDepartureRunwayIgnoresTheFlag() {
        AirportLayout layout = singleRunway();
        BlockPos depStart = new BlockPos(0, 64, 50);
        BlockPos depEnd = new BlockPos(100, 64, 50);
        layout.setRunwayPoint(1, 0, depStart);
        layout.setRunwayPoint(1, 1, depEnd);
        layout.setReversedTakeoff(true);

        assertTrue(layout.hasDepartureRunway());
        assertEquals(java.util.List.of(depStart, depEnd), layout.takeoffRoll());
    }

    @Test
    void noRunwayDrawnYetIsJustEmpty() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Nowhere", Level.OVERWORLD);
        layout.setReversedTakeoff(true);
        assertTrue(layout.takeoffRoll().isEmpty());
    }

    @Test
    void survivesSaveAndLoad() {
        AirportLayout original = singleRunway();
        original.setReversedTakeoff(true);

        assertTrue(AirportLayout.load(original.save()).reversedTakeoff());
    }

    /** A layout saved before this setting existed loads as the direction it
     *  was already flying - normal, not reversed. */
    @Test
    void aLayoutSavedBeforeThisExistedLoadsAsNormal() {
        AirportLayout original = singleRunway();
        net.minecraft.nbt.CompoundTag tag = original.save();
        tag.remove("reversedTakeoff");

        assertEquals(false, AirportLayout.load(tag).reversedTakeoff());
    }

    @Test
    void survivesTheNetworkRoundTrip() {
        AirportLayout original = singleRunway();
        original.setReversedTakeoff(true);

        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        original.write(buf);
        AirportLayout loaded = AirportLayout.read(buf);

        assertEquals(0, buf.readableBytes(), "reader should consume exactly what the writer wrote");
        assertTrue(loaded.reversedTakeoff());
        assertEquals(java.util.List.of(FAR_END, GATE_END), loaded.takeoffRoll());
    }
}
