package com.skyport.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reordering stops, and spotting stops that will never be flown.
 *
 * Both exist because of the same trap: the order of a schedule is the route,
 * and a VOR only means anything when it sits before the airport it is meant
 * to route the aircraft toward. A VOR added at the end of a list reads as
 * part of the route and is silently skipped, which is indistinguishable from
 * the feature being broken.
 */
class ScheduleOrderTest {

    private static ScheduleEntry airport() {
        return new ScheduleEntry(UUID.randomUUID(), "Gate A", ScheduleEntry.WaitCondition.TIMER, 10);
    }

    private static FlightSchedule schedule(boolean loop, ScheduleEntry... stops) {
        FlightSchedule schedule = new FlightSchedule();
        for (ScheduleEntry stop : stops) schedule.entries().add(stop);
        schedule.setLoop(loop);
        return schedule;
    }

    @Test
    void aStopMovesUpAndTakesTheSelectionWithIt() {
        ScheduleEntry first = airport();
        ScheduleEntry vor = ScheduleEntry.vor(UUID.randomUUID());
        FlightSchedule s = schedule(false, first, vor);

        assertEquals(0, s.moveStop(1, -1), "the moved stop is now at 0");
        assertEquals(vor, s.entries().get(0));
        assertEquals(first, s.entries().get(1));
    }

    @Test
    void aStopMovesDown() {
        ScheduleEntry vor = ScheduleEntry.vor(UUID.randomUUID());
        ScheduleEntry last = airport();
        FlightSchedule s = schedule(false, vor, last);

        assertEquals(1, s.moveStop(0, 1));
        assertEquals(last, s.entries().get(0));
        assertEquals(vor, s.entries().get(1));
    }

    /** Off either end is a no-op rather than an exception - the buttons are
     *  disabled there, but the buttons are not the only caller. */
    @Test
    void movingPastEitherEndChangesNothing() {
        ScheduleEntry a = airport();
        ScheduleEntry b = airport();
        FlightSchedule s = schedule(false, a, b);

        assertEquals(0, s.moveStop(0, -1));
        assertEquals(1, s.moveStop(1, 1));
        assertEquals(a, s.entries().get(0));
        assertEquals(b, s.entries().get(1));
        assertEquals(3, s.moveStop(3, -1), "an index off the end is left alone");
    }

    /** The exact mistake that made a VOR look broken: added last, after the
     *  airport it was supposed to route toward. */
    @Test
    void vorsAfterTheLastAirportAreFlaggedAsNeverFlown() {
        FlightSchedule s = schedule(false, airport(), ScheduleEntry.vor(UUID.randomUUID()));
        assertTrue(s.hasUnflownTail());
    }

    @Test
    void aVorBeforeAnAirportIsFine() {
        FlightSchedule s = schedule(false, ScheduleEntry.vor(UUID.randomUUID()), airport());
        assertFalse(s.hasUnflownTail());
    }

    /** Looping changes the answer: the stop after the last airport is flown on
     *  the way round to the first one. */
    @Test
    void aLoopHasNoUnflownTail() {
        FlightSchedule s = schedule(true, airport(), ScheduleEntry.vor(UUID.randomUUID()));
        assertFalse(s.hasUnflownTail());
    }

    @Test
    void anOrdinaryScheduleHasNoUnflownTail() {
        assertFalse(schedule(false, airport(), airport()).hasUnflownTail());
        assertFalse(schedule(false).hasUnflownTail());
    }
}
