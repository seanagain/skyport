package com.skyport.logic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Working out who is at which gate, which is inference rather than a reading.
 *
 * Nothing claims a gate. The traffic system reserves runways, the taxiways as
 * a set, and helipads, and that is the whole list - so a gate's occupant has
 * to be deduced from where each aircraft is in its own schedule. Getting it
 * wrong means telling somebody a gate is free while an aeroplane is parked on
 * it, which is worse than showing nothing at all.
 */
class StopOccupancyTest {

    private static StopOccupancy.Aircraft at(String callsign, String parkedAt, String... scheduled) {
        return new StopOccupancy.Aircraft(callsign, parkedAt, List.of(scheduled));
    }

    private static StopOccupancy.Aircraft flying(String callsign, String... scheduled) {
        return new StopOccupancy.Aircraft(callsign, null, List.of(scheduled));
    }

    @Test
    void anAircraftParkedSomewhereOccupiesIt() {
        Map<String, List<String>> occupants = StopOccupancy.occupants(
                List.of(at("Cargo 1", "Gate A", "Gate A"), flying("Cargo 2", "Gate B")));
        assertEquals(List.of("Cargo 1"), occupants.get("Gate A"));
        assertTrue(occupants.get("Gate B") == null, "an aircraft in the air occupies nothing");
    }

    /** Gates are not claimed, so nothing stops two routes naming the same
     *  one. Both get named rather than one silently winning - that is a
     *  routing mistake somebody should be able to see. */
    @Test
    void twoAircraftAtOneGateAreBothNamed() {
        Map<String, List<String>> occupants = StopOccupancy.occupants(
                List.of(at("Cargo 2", "Gate A"), at("Cargo 1", "Gate A")));
        assertEquals(List.of("Cargo 1", "Cargo 2"), occupants.get("Gate A"),
                "sorted, so the list does not reshuffle itself between frames");
    }

    @Test
    void anAircraftParkedNowhereHereIsIgnored() {
        assertTrue(StopOccupancy.occupants(List.of(flying("Cargo 1", "Gate A"))).isEmpty());
        assertTrue(StopOccupancy.occupants(List.of(at("Cargo 1", "", "Gate A"))).isEmpty(),
                "a blank stop name is not a stop");
    }

    @Test
    void expectedListsEveryAircraftRoutedThroughAStop() {
        Map<String, List<String>> expected = StopOccupancy.expected(List.of(
                flying("Cargo 2", "Gate A", "Gate B"),
                flying("Cargo 1", "Gate A"),
                flying("Post 1", "Gate B")));
        assertEquals(List.of("Cargo 1", "Cargo 2"), expected.get("Gate A"));
        assertEquals(List.of("Cargo 2", "Post 1"), expected.get("Gate B"));
    }

    /** A route that visits the same gate twice is one aircraft, not two -
     *  listing it twice would read as a conflict at that gate. */
    @Test
    void aRouteVisitingAStopTwiceListsTheAircraftOnce() {
        Map<String, List<String>> expected = StopOccupancy.expected(
                List.of(flying("Shuttle", "Gate A", "Gate B", "Gate A")));
        assertEquals(List.of("Shuttle"), expected.get("Gate A"));
    }

    /** An aircraft sitting at a gate is still scheduled there. Dropping it
     *  would make the gate look unused at the moment it is being used. */
    @Test
    void anAircraftParkedAtAStopStillCountsAsScheduledThere() {
        Map<String, List<String>> expected = StopOccupancy.expected(
                List.of(at("Cargo 1", "Gate A", "Gate A", "Gate B")));
        assertEquals(List.of("Cargo 1"), expected.get("Gate A"));
    }

    /**
     * The question this screen exists to answer. A gate drawn and never
     * routed to looks exactly like one in constant use until you count.
     */
    @Test
    void stopsNothingIsRoutedToAreCalledOut() {
        Map<String, List<String>> expected = StopOccupancy.expected(
                List.of(flying("Cargo 1", "Gate A")));
        assertEquals(List.of("Gate B", "Gate C"),
                StopOccupancy.unused(List.of("Gate A", "Gate B", "Gate C"), expected));
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
}
