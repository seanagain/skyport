package com.skyport.logic;

import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flight plan an admin sees for one aircraft.
 *
 * What is worth pinning is the reading rather than the wording: which stop it
 * is on, which stops are only flown over, and what a stop whose airport was
 * deleted looks like - that last one being how a broken route is meant to
 * announce itself rather than printing a bare id or throwing.
 */
class FlightPlanReportTest {

    private static final UUID HEATHROW = UUID.randomUUID();
    private static final UUID NORTH = UUID.randomUUID();

    private static final Map<UUID, String> AIRPORTS = Map.of(HEATHROW, "Heathrow");
    private static final Map<UUID, String> VORS = Map.of(NORTH, "North");

    private static List<String> lines(FlightSchedule schedule, int current) {
        return FlightPlanReport.lines(schedule, current, AIRPORTS::get, VORS::get);
    }

    private static FlightSchedule schedule(ScheduleEntry... stops) {
        FlightSchedule schedule = new FlightSchedule();
        for (ScheduleEntry stop : stops) schedule.entries().add(stop);
        return schedule;
    }

    private static ScheduleEntry gate() {
        return new ScheduleEntry(HEATHROW, "Gate A", ScheduleEntry.WaitCondition.TIMER, 30);
    }

    @Test
    void anAirportStopNamesItsAirportGateAndWait() {
        assertEquals(List.of("  1. Heathrow / Gate A  [30s]"), lines(schedule(gate()), -1));
    }

    @Test
    void aVorStopReadsAsSomethingFlownOver() {
        List<String> out = lines(schedule(ScheduleEntry.vor(NORTH)), -1);
        assertEquals(List.of("  1. via North  [fly over]"), out);
    }

    /** The whole point of running this against a live aircraft. */
    @Test
    void theStopItIsOnIsMarked() {
        List<String> out = lines(schedule(gate(), ScheduleEntry.vor(NORTH), gate()), 1);
        assertTrue(out.get(0).startsWith("  1."), "not this one");
        assertTrue(out.get(1).startsWith("> 2."), "this one: " + out.get(1));
        assertTrue(out.get(2).startsWith("  3."), "nor this one");
    }

    /** A station or VOR broken since the route was built. Saying so is the
     *  point - this is what an admin is looking at the plan to find. */
    @Test
    void aDeletedAirportOrVorSaysSoRatherThanPrintingAnId() {
        UUID gone = UUID.randomUUID();
        List<String> out = lines(schedule(
                new ScheduleEntry(gone, "Gate A", ScheduleEntry.WaitCondition.TIMER, 0),
                ScheduleEntry.vor(gone)), -1);
        assertTrue(out.get(0).contains(FlightPlanReport.MISSING), out.get(0));
        assertTrue(out.get(1).contains(FlightPlanReport.MISSING), out.get(1));
    }

    @Test
    void everyWaitConditionHasWords() {
        for (ScheduleEntry.WaitCondition condition : ScheduleEntry.WaitCondition.values()) {
            List<String> out = lines(schedule(new ScheduleEntry(HEATHROW, "Gate A", condition, 5)), -1);
            assertTrue(out.get(0).contains("["), out.get(0));
            assertTrue(!out.get(0).contains("null"), "condition " + condition + " has no wording");
        }
    }

    @Test
    void aStopWithNoGateSaysSo() {
        List<String> out = lines(schedule(
                new ScheduleEntry(HEATHROW, "", ScheduleEntry.WaitCondition.TIMER, 0)), -1);
        assertTrue(out.get(0).contains("(no gate)"), out.get(0));
    }

    @Test
    void anEmptyScheduleStillPrintsSomething() {
        assertEquals(List.of("  (no stops)"), lines(schedule(), -1));
    }

    @Test
    void theSummaryCoversHowItFliesRatherThanWhere() {
        FlightSchedule schedule = schedule(gate());
        schedule.setCruiseAltitude(150);
        schedule.setCruiseSpeed(24);
        schedule.setLoop(true);

        String summary = FlightPlanReport.summary(schedule);

        assertTrue(summary.contains("150"), summary);
        assertTrue(summary.contains("24"), summary);
        assertTrue(summary.contains("loop on"), summary);
    }
}
