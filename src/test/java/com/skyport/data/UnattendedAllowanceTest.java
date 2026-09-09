package com.skyport.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-aircraft allowance for running with nobody watching.
 *
 * Only the schedule half is testable here - the countdown itself lives in
 * the block entity and needs a server - but the schedule half is where a
 * bad value would be persisted and shipped round the network, so it is
 * worth pinning.
 */
class UnattendedAllowanceTest {

    @Test
    void defaultsToSomethingUseful() {
        // Not zero. An aircraft that sleeps at every gate never completes a
        // loop unless a player walks it round, which is the behaviour this
        // setting exists to escape - so the default should be the interesting
        // one, with zero available for anyone who wants the old way.
        assertEquals(10, new FlightSchedule().unattendedMinutes());
    }

    @Test
    void clampsToARange() {
        FlightSchedule schedule = new FlightSchedule();

        schedule.setUnattendedMinutes(-5);
        assertEquals(0, schedule.unattendedMinutes(), "negative time is not a thing");

        schedule.setUnattendedMinutes(500);
        assertEquals(60, schedule.unattendedMinutes(),
                "an hour is the cap - this pins chunks open, and an unbounded "
                        + "value is an unbounded cost to the server");
    }

    @Test
    void zeroIsAllowedAndMeansOff() {
        FlightSchedule schedule = new FlightSchedule();
        schedule.setUnattendedMinutes(0);
        assertEquals(0, schedule.unattendedMinutes());
    }

    @Test
    void survivesSaveAndLoad() {
        FlightSchedule schedule = new FlightSchedule();
        schedule.setUnattendedMinutes(45);

        FlightSchedule loaded = FlightSchedule.load(schedule.save());

        assertEquals(45, loaded.unattendedMinutes());
    }

    /** A schedule written before this setting existed has no tag for it, and
     *  has to come back with the default rather than zero - otherwise the
     *  update would silently switch every existing aircraft to sleeping at
     *  its next gate. */
    @Test
    void aScheduleFromBeforeTheSettingKeepsTheDefault() {
        FlightSchedule loaded = FlightSchedule.load(new net.minecraft.nbt.CompoundTag());
        assertEquals(10, loaded.unattendedMinutes());
    }
}
