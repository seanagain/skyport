package com.skyport.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clearances that keep aircraft out of each other's way.
 *
 * Worth testing because the failure is silent and looks like something else
 * entirely: an aircraft holding forever over an airport with nothing on it,
 * which reads as a navigation or steering bug rather than as a lease nobody
 * gave back. That exact bug shipped - a departure took the taxiway on
 * pushback, only released it inside the hold-line branch, and so never
 * released it at all at an airport with no hold line drawn.
 */
class ClearanceTest {

    private static final UUID AIRPORT = UUID.randomUUID();

    /**
     * The shape of the bug, at the level this class can see it: holding the
     * taxiway alone is enough to stop every arrival, because an arrival takes
     * both together. Releasing only the runway is not releasing.
     */
    @Test
    void anArrivalIsBlockedByATaxiwayLeaseAlone() {
        AirportRegistry registry = new AirportRegistry();
        UUID departing = UUID.randomUUID();
        UUID arriving = UUID.randomUUID();

        assertTrue(registry.tryClaimTaxiway(AIRPORT, departing, 0));
        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, departing, 0));

        // The departure gives back only the runway, and keeps flying - so it
        // keeps its lease alive, and the timeout never rescues anyone.
        registry.releaseTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, departing);
        registry.heartbeat(departing, 100);

        assertFalse(registry.tryClaimArrival(AIRPORT, arriving, 100),
                "the stale taxiway lease alone blocks the arrival");

        // Releasing both is what actually frees the field.
        registry.releaseTaxiway(AIRPORT, departing);
        assertTrue(registry.tryClaimArrival(AIRPORT, arriving, 100),
                "with both handed back the airport is usable again");
    }

    /**
     * The safety net for every path that cannot be relied on to run - the
     * block broken mid-flight, the chunk unloaded, the server stopped. A
     * holder that goes quiet loses its claim.
     */
    @Test
    void aSilentHolderLosesItsClaim() {
        AirportRegistry registry = new AirportRegistry();
        UUID gone = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();

        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, gone, 0));
        registry.heartbeat(gone, 0);

        assertFalse(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, waiting, 100),
                "still within the lease, so still refused");
        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, waiting, 1000),
                "long silent, so the clearance can be taken");
    }

    /** Re-asking for something you already hold has to keep working - the
     *  flight loop does exactly that on every pass. */
    @Test
    void reclaimingYourOwnClearanceIsHarmless() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();

        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, plane, 0));
        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, plane, 20));
        assertTrue(registry.tryClaimTraffic(AIRPORT, AirportRegistry.ARRIVAL_RUNWAY, plane, 40));
    }

    /**
     * Helipads share nothing with the runway. A helicopter sitting on a pad
     * must not stop an aeroplane landing, or a field with one busy pad would
     * serialise aircraft that never come near each other.
     */
    @Test
    void padsDoNotBlockTheRunway() {
        AirportRegistry registry = new AirportRegistry();
        UUID heli = UUID.randomUUID();
        UUID plane = UUID.randomUUID();

        assertTrue(registry.tryClaimPad(AIRPORT, "Pad 1", heli, 0));
        registry.heartbeat(heli, 0);

        assertTrue(registry.tryClaimArrival(AIRPORT, plane, 0),
                "a helicopter on a pad is not on the runway");
    }

    /** And two helicopters must not share one pad. */
    @Test
    void onePadHoldsOneAircraft() {
        AirportRegistry registry = new AirportRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(registry.tryClaimPad(AIRPORT, "Pad 1", first, 0));
        registry.heartbeat(first, 0);

        assertFalse(registry.tryClaimPad(AIRPORT, "Pad 1", second, 10));
        assertTrue(registry.tryClaimPad(AIRPORT, "Pad 2", second, 10),
                "but the pad next to it is free");
    }
}
