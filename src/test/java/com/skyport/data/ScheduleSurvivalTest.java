package com.skyport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a schedule does when the data it is loading is not what it expected.
 *
 * This is the failure nobody sees coming, because its symptom is not an
 * error message: an exception thrown while a block entity is loading makes
 * Minecraft drop that block entity entirely. The aircraft loses its
 * schedule, its identity and its ability to be woken, and the only trace is
 * a line in a log. Someone looking at it sees a plane that stopped working
 * after an update and reasonably concludes the update corrupted it.
 *
 * Nothing writes a tag like these today. That is not a reason to let one
 * take an aeroplane down if it ever happens - a route missing a stop is
 * obvious the moment anyone opens it, and an aeroplane that quietly ceased
 * to exist is not.
 */
class ScheduleSurvivalTest {

    private static CompoundTag airportStop(UUID airport) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("airportId", airport);
        tag.putString("gateName", "Gate A");
        tag.putString("condition", "TIMER");
        tag.putInt("waitSeconds", 30);
        return tag;
    }

    private static CompoundTag scheduleOf(CompoundTag... stops) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag stop : stops) list.add(stop);
        tag.put("entries", list);
        tag.putBoolean("loop", true);
        return tag;
    }

    @Test
    void anOrdinaryStopStillLoads() {
        UUID airport = UUID.randomUUID();
        ScheduleEntry entry = ScheduleEntry.load(airportStop(airport));
        assertNotNull(entry);
        assertEquals(airport, entry.airportId());
        assertEquals("Gate A", entry.gateName());
        assertEquals(ScheduleEntry.WaitCondition.TIMER, entry.condition());
        assertEquals(30, entry.waitSeconds());
    }

    /** getUUID on a key that is not there throws, and that exception used to
     *  escape all the way out of the aircraft's load. */
    @Test
    void aStopWithNoAirportIsSkippedRatherThanThrown() {
        CompoundTag broken = airportStop(UUID.randomUUID());
        broken.remove("airportId");
        assertNull(ScheduleEntry.load(broken));
    }

    /** valueOf("") throws. A wait this version cannot read becomes a timer,
     *  which is the condition that always completes - the alternative is a
     *  stop the aircraft can never leave. */
    @Test
    void anUnreadableWaitBecomesATimer() {
        CompoundTag missing = airportStop(UUID.randomUUID());
        missing.remove("condition");
        assertEquals(ScheduleEntry.WaitCondition.TIMER, ScheduleEntry.load(missing).condition());

        CompoundTag fromTheFuture = airportStop(UUID.randomUUID());
        fromTheFuture.putString("condition", "WAIT_FOR_SOMETHING_INVENTED_LATER");
        assertEquals(ScheduleEntry.WaitCondition.TIMER, ScheduleEntry.load(fromTheFuture).condition());
    }

    /** The point of all of it: one bad stop costs one stop. */
    @Test
    void oneUnreadableStopDoesNotTakeTheRouteWithIt() {
        UUID heathrow = UUID.randomUUID();
        UUID gatwick = UUID.randomUUID();
        CompoundTag broken = airportStop(UUID.randomUUID());
        broken.remove("airportId");

        FlightSchedule loaded = FlightSchedule.load(
                scheduleOf(airportStop(heathrow), broken, airportStop(gatwick)));

        assertEquals(2, loaded.entries().size(), "the readable stops have to survive");
        assertEquals(heathrow, loaded.entries().get(0).airportId());
        assertEquals(gatwick, loaded.entries().get(1).airportId());
        assertTrue(loaded.loop(), "and the rest of the schedule with them");
    }

    @Test
    void aVorStopStillLoads() {
        UUID vor = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putUUID("vorId", vor);
        ScheduleEntry entry = ScheduleEntry.load(tag);
        assertNotNull(entry);
        assertTrue(entry.isVor());
        assertEquals(vor, entry.vorId());
    }

    /** A tag that is neither kind - the shape that would otherwise reach the
     *  record's own "an airport or a VOR, not neither" check and throw. */
    @Test
    void anEmptyStopIsSkipped() {
        assertNull(ScheduleEntry.load(new CompoundTag()));
    }
}
