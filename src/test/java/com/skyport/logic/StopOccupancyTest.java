package com.skyport.logic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Working out who is at which gate and who is due there next.
 *
 * Nothing claims a gate. The traffic system reserves runways, the taxiways as
 * a set, and helipads, and that is the whole list - so a gate's occupant and
 * its queue have to be deduced from where each aircraft is in its own
 * schedule. Getting it wrong means telling somebody a gate is free while an
 * aeroplane is parked on it, or that the aircraft they are waiting for is
 * next when it is fourth.
 *
 * The ordering deserves its own attention because it mixes two kinds of
 * answer. An aircraft on its way here has a real estimate; one that reaches
 * this gate in three stops' time does not, because gate waits and a cargo
 * condition nobody may come to satisfy sit in between. The order has to put
 * them in a sensible sequence anyway, without pretending the second kind is
 * as precise as the first.
 */
class StopOccupancyTest {

    private static StopOccupancy.Aircraft parked(String callsign, String at, StopOccupancy.Leg... legs) {
        return new StopOccupancy.Aircraft(callsign, at, List.of(legs));
    }

    private static StopOccupancy.Aircraft flying(String callsign, StopOccupancy.Leg... legs) {
        return new StopOccupancy.Aircraft(callsign, null, List.of(legs));
    }

    /** On its way there now, arriving in roughly this many seconds. */
    private static StopOccupancy.Leg inbound(String stop, double seconds) {
        return new StopOccupancy.Leg(stop, 0, seconds);
    }

    /** Reaches that stop some number of stops further down its schedule. */
    private static StopOccupancy.Leg later(String stop, int stopsAway) {
        return new StopOccupancy.Leg(stop, stopsAway, -1);
    }

    private static List<String> callsigns(List<StopOccupancy.Arrival> arrivals) {
        List<String> out = new ArrayList<>();
        for (StopOccupancy.Arrival arrival : arrivals) out.add(arrival.callsign());
        return out;
    }

    // ---- who is there now ------------------------------------------------

    @Test
    void anAircraftParkedSomewhereOccupiesIt() {
        Map<String, List<String>> occupants = StopOccupancy.occupants(
                List.of(parked("Cargo 1", "Gate A", later("Gate A", 0)),
                        flying("Cargo 2", inbound("Gate B", 30))));
        assertEquals(List.of("Cargo 1"), occupants.get("Gate A"));
        assertTrue(occupants.get("Gate B") == null, "an aircraft in the air occupies nothing");
    }

    /** Gates are not claimed, so nothing stops two routes naming the same
     *  one. Both get named rather than one silently winning - that is a
     *  routing mistake somebody should be able to see. */
    @Test
    void twoAircraftAtOneGateAreBothNamed() {
        Map<String, List<String>> occupants = StopOccupancy.occupants(
                List.of(parked("Cargo 2", "Gate A"), parked("Cargo 1", "Gate A")));
        assertEquals(List.of("Cargo 1", "Cargo 2"), occupants.get("Gate A"),
                "sorted, so the list does not reshuffle itself between frames");
    }

    @Test
    void anAircraftParkedNowhereHereIsIgnored() {
        assertTrue(StopOccupancy.occupants(List.of(flying("Cargo 1", inbound("Gate A", 10)))).isEmpty());
        assertTrue(StopOccupancy.occupants(List.of(parked("Cargo 1", ""))).isEmpty(),
                "a blank stop name is not a stop");
    }

    // ---- who is due, and in what order -----------------------------------

    /** The whole point of listing names instead of a count: the order has to
     *  mean something. Here, then soonest inbound, then by how far down the
     *  schedule everyone else is. */
    @Test
    void arrivalsAreOrderedByWhoGetsThereFirst() {
        List<StopOccupancy.Arrival> due = StopOccupancy.expected(List.of(
                flying("Far", later("Gate A", 3)),
                flying("Soon", inbound("Gate A", 20)),
                parked("Sitting", "Gate A", later("Gate A", 0)),
                flying("Later", inbound("Gate A", 200)),
                flying("Next", later("Gate A", 1)))).get("Gate A");

        assertEquals(List.of("Sitting", "Soon", "Later", "Next", "Far"), callsigns(due));
    }

    @Test
    void eachArrivalSaysWhenInItsOwnTerms() {
        Map<String, List<StopOccupancy.Arrival>> due = StopOccupancy.expected(List.of(
                parked("Sitting", "Gate A", later("Gate A", 0)),
                flying("Soon", inbound("Gate A", 45)),
                flying("Slow", inbound("Gate A", 150)),
                flying("Next", later("Gate A", 1)),
                flying("Far", later("Gate A", 4))));
        List<StopOccupancy.Arrival> list = due.get("Gate A");

        assertEquals("here", list.get(0).when());
        assertEquals("~45s", list.get(1).when());
        assertEquals("~2m30s", list.get(2).when());
        assertEquals("next stop", list.get(3).when());
        assertEquals("4 stops", list.get(4).when());
    }

    /** An aircraft in the air with no usable estimate still has to appear,
     *  and has to sort after the ones that do - not vanish, and not jump the
     *  queue on a missing number. */
    @Test
    void anInboundWithNoEstimateIsStillListed() {
        List<StopOccupancy.Arrival> due = StopOccupancy.expected(List.of(
                flying("Unknown", new StopOccupancy.Leg("Gate A", 0, -1)),
                flying("Timed", inbound("Gate A", 90)))).get("Gate A");

        assertEquals(List.of("Timed", "Unknown"), callsigns(due));
        assertEquals("inbound", due.get(1).when());
    }

    /** A shuttle looping through the same gate four times is one aeroplane
     *  due once. Listing it four times would read as four arrivals to make
     *  room for. */
    @Test
    void aRouteVisitingAStopTwiceIsOneArrival() {
        List<StopOccupancy.Arrival> due = StopOccupancy.expected(List.of(
                flying("Shuttle", inbound("Gate A", 30), later("Gate A", 2)))).get("Gate A");
        assertEquals(1, due.size());
        assertEquals("~30s", due.get(0).when(), "and it is the SOONEST visit that counts");
    }

    @Test
    void anAircraftParkedAtAStopStillCountsAsDueThere() {
        List<StopOccupancy.Arrival> due = StopOccupancy.expected(
                List.of(parked("Cargo 1", "Gate A", later("Gate A", 0)))).get("Gate A");
        assertEquals(List.of("Cargo 1"), callsigns(due));
        assertEquals("here", due.get(0).when());
    }

    @Test
    void aircraftAreGroupedOntoTheStopsTheyActuallyVisit() {
        Map<String, List<StopOccupancy.Arrival>> due = StopOccupancy.expected(List.of(
                flying("Cargo 2", inbound("Gate A", 10), later("Gate B", 1)),
                flying("Post 1", inbound("Gate B", 5))));
        assertEquals(List.of("Cargo 2"), callsigns(due.get("Gate A")));
        assertEquals(List.of("Post 1", "Cargo 2"), callsigns(due.get("Gate B")),
                "the one arriving in 5s comes before the one a stop away");
    }

    // ---- gates nobody uses -----------------------------------------------

    /**
     * The question this screen exists to answer. A gate drawn and never
     * routed to looks exactly like one in constant use until you count.
     */
    @Test
    void stopsNothingIsRoutedToAreCalledOut() {
        Map<String, List<StopOccupancy.Arrival>> due = StopOccupancy.expected(
                List.of(flying("Cargo 1", inbound("Gate A", 10))));
        assertEquals(List.of("Gate B", "Gate C"),
                StopOccupancy.unused(List.of("Gate A", "Gate B", "Gate C"), due));
    }

    @Test
    void anAirportNobodyFliesToIsEntirelyUnused() {
        assertEquals(List.of("Gate A", "Gate B"),
                StopOccupancy.unused(List.of("Gate A", "Gate B"), Map.of()));
    }

    @Test
    void anEmptyFleetProducesNothingRatherThanFailing() {
        assertTrue(StopOccupancy.occupants(List.of()).isEmpty());
        assertTrue(StopOccupancy.expected(List.of()).isEmpty());
    }

    // ---- the phrasing ----------------------------------------------------

    /** Short enough to sit beside a callsign in a narrow panel. */
    @Test
    void durationsReadAsSomethingAPersonWouldSay() {
        assertEquals("45s", StopOccupancy.duration(45));
        assertEquals("1m", StopOccupancy.duration(60));
        assertEquals("2m30s", StopOccupancy.duration(150));
        assertEquals("1s", StopOccupancy.duration(0.2), "nothing arrives in zero seconds");
    }

    @Test
    void oneStopAwayReadsAsNextRatherThanAsANumber() {
        assertEquals("next stop", StopOccupancy.stopsAway(1));
        assertEquals("2 stops", StopOccupancy.stopsAway(2));
    }
}
