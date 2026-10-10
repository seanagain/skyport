package com.skyport.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which aircraft get started again after a server restart.
 *
 * An autopilot issues its own chunk bubble while it ticks and can only tick
 * while its chunk is loaded, which on a fresh server is a deadlock with
 * nothing to break it: the bubble lived in memory and is gone. Aircraft that
 * were in the air sat frozen wherever the world stopped, schedules
 * abandoned, until somebody woke each one by hand - and because an update
 * requires closing the world, this looked for a long time like updates
 * corrupting schedules rather than restarts stranding aeroplanes.
 *
 * The rule has to be exactly "what was in the air". Waking everything on
 * every boot would force a patch of world open for every aeroplane anyone
 * had ever flown; waking nothing is the bug.
 */
class ResumeAirborneTest {

    @Test
    void aircraftThatWereFlyingAreResumed() {
        assertTrue(FleetWake.wasAirborne("CRUISE"));
        assertTrue(FleetWake.wasAirborne("CLIMB"));
        assertTrue(FleetWake.wasAirborne("APPROACH"));
        assertTrue(FleetWake.wasAirborne("HOLDING"));
        assertTrue(FleetWake.wasAirborne("VERTICAL_CLIMB"));
        assertTrue(FleetWake.wasAirborne("VERTICAL_DESCENT"));
        assertTrue(FleetWake.wasAirborne("HOVERING"));
    }

    /** A gate wait costs nothing and the tower can wake it on demand, so an
     *  aircraft on the ground sleeping through a reboot is the design
     *  working rather than something to fix. */
    @Test
    void aircraftOnTheGroundAreLeftAsleep() {
        assertFalse(FleetWake.wasAirborne("IDLE"));
        assertFalse(FleetWake.wasAirborne("WAITING"));
        assertFalse(FleetWake.wasAirborne("PUSHBACK"));
        assertFalse(FleetWake.wasAirborne("TAXI_OUT"));
        assertFalse(FleetWake.wasAirborne("TAKEOFF_ROLL"));
        assertFalse(FleetWake.wasAirborne("TAXI_IN"));
    }

    /**
     * A roster entry written by a version whose states this one does not
     * have - a world rolled back onto an older jar, or a state renamed.
     *
     * Treated as parked, because the two ways of being wrong do not cost the
     * same: guess parked and an aeroplane waits to be woken, guess airborne
     * and a chunk is forced open indefinitely for something that may not be
     * there at all.
     */
    @Test
    void anUnrecognisedStateIsTreatedAsParked() {
        assertFalse(FleetWake.wasAirborne("SUPERCRUISE"));
        assertFalse(FleetWake.wasAirborne("cruise"), "the roster stores the exact enum name");
        assertFalse(FleetWake.wasAirborne(""));
        assertFalse(FleetWake.wasAirborne(null));
    }
}
