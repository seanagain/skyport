package com.seaport;

import com.seaport.data.VoyageSchedule;
import com.seaport.data.VoyageStop;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orders a ship sails under, and what happens when they are wrong.
 *
 * Most of this is about surviving bad input rather than handling good input,
 * because that is where Skyport's equivalent actually broke: a schedule that
 * threw while loading took the whole list with it, and a fleet came back
 * from a restart with no idea where it was going.
 */
class VoyageScheduleTest {

    private static final UUID PORT = UUID.randomUUID();
    private static final UUID MARK = UUID.randomUUID();

    @Test
    void aStopNamesAPortOrAMarkButNotBoth() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoyageStop(PORT, "", VoyageStop.WaitCondition.TIMER, 0, MARK),
                "both is a stop nothing can sail");
        assertThrows(IllegalArgumentException.class,
                () -> new VoyageStop(null, "", VoyageStop.WaitCondition.TIMER, 0, null),
                "neither is a destination that resolves to nowhere");
    }

    @Test
    void anAbsurdWaitIsClampedRatherThanStored() {
        VoyageStop stop = VoyageStop.port(PORT, "Berth 1",
                VoyageStop.WaitCondition.TIMER, Integer.MAX_VALUE);
        assertEquals(VoyageStop.MAX_WAIT_SECONDS, stop.waitSeconds());

        VoyageStop negative = VoyageStop.port(PORT, "Berth 1",
                VoyageStop.WaitCondition.TIMER, -5);
        assertEquals(0, negative.waitSeconds());
    }

    @Test
    void aStopSurvivesBeingWrittenAndReadBack() {
        VoyageStop original = VoyageStop.port(PORT, "North Quay",
                VoyageStop.WaitCondition.CARGO_EMPTY, 120);
        VoyageStop loaded = VoyageStop.load(original.save());

        assertNotNull(loaded);
        assertEquals(original, loaded);
    }

    @Test
    void aMarkSurvivesBeingWrittenAndReadBack() {
        VoyageStop original = VoyageStop.mark(MARK);
        VoyageStop loaded = VoyageStop.load(original.save());

        assertNotNull(loaded);
        assertTrue(loaded.isMark());
        assertEquals(MARK, loaded.beaconId());
    }

    @Test
    void anUnreadableStopIsNullRatherThanAnException() {
        // A tag naming neither a port nor a mark. Returning null costs that
        // one stop; throwing would cost the voyage.
        assertNull(VoyageStop.load(new CompoundTag()));
    }

    @Test
    void aConditionThisBuildDoesNotKnowFallsBackToTheClock() {
        CompoundTag tag = VoyageStop.port(PORT, "Berth 1",
                VoyageStop.WaitCondition.PLAYER, 30).save();
        tag.putString("condition", "WAIT_FOR_THE_TIDE");

        VoyageStop loaded = VoyageStop.load(tag);
        assertNotNull(loaded, "an unknown condition should not lose the stop");
        assertEquals(VoyageStop.WaitCondition.TIMER, loaded.condition(),
                "a ship should keep moving rather than wait for something that cannot happen");
    }

    @Test
    void oneCorruptStopDoesNotCostTheWholeSchedule() {
        VoyageSchedule schedule = new VoyageSchedule();
        schedule.stops().add(VoyageStop.port(PORT, "A", VoyageStop.WaitCondition.TIMER, 10));
        schedule.stops().add(VoyageStop.mark(MARK));

        CompoundTag tag = schedule.save();
        // Wedge an unreadable entry into the middle of the saved list.
        ListTag stops = tag.getList("stops", CompoundTag.TAG_COMPOUND);
        stops.add(1, new CompoundTag());

        VoyageSchedule loaded = VoyageSchedule.load(tag);
        assertEquals(2, loaded.stops().size(), "the two good stops should both survive");
    }

    @Test
    void theCursorStaysInsideTheListWhenStopsAreDeletedUnderIt() {
        VoyageSchedule schedule = new VoyageSchedule();
        schedule.stops().add(VoyageStop.port(PORT, "A", VoyageStop.WaitCondition.TIMER, 10));
        schedule.stops().add(VoyageStop.port(PORT, "B", VoyageStop.WaitCondition.TIMER, 10));
        schedule.stops().add(VoyageStop.port(PORT, "C", VoyageStop.WaitCondition.TIMER, 10));
        schedule.setCursor(2);

        // Editing the list while a ship is halfway to stop C.
        schedule.stops().remove(2);
        schedule.stops().remove(1);

        assertEquals(0, schedule.cursor(), "a cursor past the end must not escape the list");
        assertNotNull(schedule.current());
    }

    @Test
    void aLoopingScheduleWrapsAndAOnceOnlyOneStops() {
        VoyageSchedule looping = new VoyageSchedule();
        looping.stops().add(VoyageStop.port(PORT, "A", VoyageStop.WaitCondition.TIMER, 0));
        looping.stops().add(VoyageStop.port(PORT, "B", VoyageStop.WaitCondition.TIMER, 0));

        assertTrue(looping.advance());
        assertEquals(1, looping.cursor());
        assertTrue(looping.advance(), "a looping schedule runs the list round again");
        assertEquals(0, looping.cursor());

        looping.setLooping(false);
        looping.setCursor(1);
        assertFalse(looping.advance(), "a once-only schedule stops at the end");
        assertEquals(1, looping.cursor(), "and stays where it stopped");
    }

    @Test
    void anEmptyScheduleAdvancesNowhereRatherThanDividingByZero() {
        VoyageSchedule empty = new VoyageSchedule();
        assertFalse(empty.advance());
        assertEquals(0, empty.cursor());
        assertNull(empty.current());
    }

    @Test
    void aScheduleSavedBeforeLoopingExistedStillLoops() {
        CompoundTag old = new VoyageSchedule().save();
        old.remove("looping");
        assertTrue(VoyageSchedule.load(old).looping(),
                "absent means an older save, and those looped");
    }

    @Test
    void marksAreNotDestinations() {
        VoyageSchedule schedule = new VoyageSchedule();
        schedule.stops().add(VoyageStop.port(PORT, "A", VoyageStop.WaitCondition.TIMER, 0));
        schedule.stops().add(VoyageStop.mark(MARK));
        schedule.stops().add(VoyageStop.port(PORT, "B", VoyageStop.WaitCondition.TIMER, 0));

        assertEquals(2, schedule.portStops().size(), "nothing berths at a buoy");
    }
}
