package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional second runway.
 *
 * The whole feature is "departures stop queueing behind arrivals", and it is
 * worth nothing unless two things hold: an airport with one runway keeps
 * behaving exactly as it did, and an airport with two can actually run a
 * departure and an arrival at the same time. Both are easy to break without
 * anything looking wrong on the map.
 */
class RunwayTest {

    private static AirportLayout oneRunway() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Small", Level.OVERWORLD);
        addRunway(layout, new BlockPos(0, 64, 0), new BlockPos(100, 64, 0));
        return layout;
    }

    private static AirportLayout twoRunways() {
        AirportLayout layout = oneRunway();
        addRunway(layout, new BlockPos(0, 64, 40), new BlockPos(100, 64, 40));
        return layout;
    }

    private static void addRunway(AirportLayout layout, BlockPos gateEnd, BlockPos farEnd) {
        List<Waypoint> points = layout.waypoints(Waypoint.Type.RUNWAY);
        points.add(new Waypoint(gateEnd, Waypoint.Type.RUNWAY, points.size()));
        points.add(new Waypoint(farEnd, Waypoint.Type.RUNWAY, points.size()));
    }

    @Test
    void oneRunwayServesBothRoles() {
        AirportLayout layout = oneRunway();
        assertFalse(layout.hasDepartureRunway());
        assertEquals(layout.arrivalRunway(), layout.departureRunway(),
                "with one strip drawn, departures use the strip arrivals use");
    }

    @Test
    void theSecondPairIsTheDepartureRunway() {
        AirportLayout layout = twoRunways();
        assertTrue(layout.hasDepartureRunway());
        assertEquals(new BlockPos(0, 64, 0), layout.arrivalRunway().get(0));
        assertEquals(new BlockPos(0, 64, 40), layout.departureRunway().get(0),
                "departures roll down the second one");
    }

    @Test
    void anAirportWithNoRunwayHasNeither() {
        AirportLayout empty = new AirportLayout(UUID.randomUUID(), "Empty", Level.OVERWORLD);
        assertTrue(empty.arrivalRunway().isEmpty());
        assertTrue(empty.departureRunway().isEmpty());
        assertFalse(empty.hasDepartureRunway());
    }

    /**
     * The point of the whole thing. Two aircraft, two strips, no waiting.
     */
    @Test
    void aDepartureAndAnArrivalCanShareAFieldWithTwoRunways() {
        AirportRegistry registry = new AirportRegistry();
        UUID airport = UUID.randomUUID();
        UUID departing = UUID.randomUUID();
        UUID arriving = UUID.randomUUID();

        assertTrue(registry.tryClaimTraffic(airport, AirportRegistry.DEPARTURE_RUNWAY, departing, 0));
        registry.heartbeat(departing, 0);

        assertTrue(registry.tryClaimArrival(airport, arriving, 0),
                "the arrival runway is a different strip and should still be free");
    }

    /**
     * And the guard on it: one runway must still mean one aircraft. A field
     * with a single strip resolves both roles to the same key, so this is the
     * test that stops the feature quietly removing separation everywhere.
     */
    @Test
    void oneRunwayStillSerialisesTraffic() {
        AirportRegistry registry = new AirportRegistry();
        UUID airport = UUID.randomUUID();
        UUID departing = UUID.randomUUID();
        UUID arriving = UUID.randomUUID();

        // A single-runway field books the arrival runway for its departure,
        // because that is the only strip there is.
        assertTrue(registry.tryClaimTraffic(airport, AirportRegistry.ARRIVAL_RUNWAY, departing, 0));
        registry.heartbeat(departing, 0);

        assertFalse(registry.tryClaimArrival(airport, arriving, 0),
                "one strip, one aircraft - an arrival must wait");
    }

    /** Releasing is done blind across both keys, so it has to be safe to
     *  release one the aircraft never held. */
    @Test
    void releasingARunwayYouNeverHeldIsHarmless() {
        AirportRegistry registry = new AirportRegistry();
        UUID airport = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(registry.tryClaimTraffic(airport, AirportRegistry.ARRIVAL_RUNWAY, holder, 0));
        registry.heartbeat(holder, 0);

        registry.releaseTraffic(airport, AirportRegistry.DEPARTURE_RUNWAY, other);
        registry.releaseTraffic(airport, AirportRegistry.ARRIVAL_RUNWAY, other);

        assertFalse(registry.tryClaimTraffic(airport, AirportRegistry.ARRIVAL_RUNWAY, other, 10),
                "someone else's release must not hand away a live clearance");
    }
}
