package com.skyport.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an aircraft is allowed to run out of unattended time.
 *
 * The case that matters is the one that used to be wrong: time running out
 * while airborne, which froze the aircraft over open country until somebody
 * flew to it.
 */
class UnattendedPolicyTest {

    @Test
    void aParkedAircraftWithTimeLeftDeparts() {
        assertTrue(UnattendedPolicy.mayDepart(false, 1));
    }

    @Test
    void aParkedAircraftOutOfTimeStaysAtTheGate() {
        assertFalse(UnattendedPolicy.mayDepart(false, 0));
    }

    /** The server has opted into schedules that run with nobody watching, so
     *  the allowance is not the thing deciding. */
    @Test
    void keepParkedLoadedOverridesTheAllowance() {
        assertTrue(UnattendedPolicy.mayDepart(true, 0));
    }

    /** The whole point of the change: there is nowhere to put an aeroplane
     *  down mid-leg, so it finishes the leg whatever the clock says. */
    @Test
    void anAirborneAircraftIsNeverStopped() {
        assertTrue(UnattendedPolicy.mayStayAirborne());
    }
}
