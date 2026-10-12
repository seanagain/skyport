package com.skyport.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which redstone neighbour updates get acted on.
 *
 * Written against a reported server hang: an Autopilot placed beside a block
 * that calls updateNeighborsAt every tick re-ran its engage logic every tick,
 * route check and chunk loading included, and held the main thread for
 * minutes at a time. Both rules here exist to make that impossible, and the
 * tests are shaped around the two ways it happened rather than around the
 * methods.
 */
class RedstoneGateTest {

    /** A neighbour rattling every tick without changing the signal - the
     *  reported case, and the one that took the server down. */
    @Test
    void aNeighbourThatChangesNothingIsIgnored() {
        RedstoneGate gate = new RedstoneGate();
        assertTrue(gate.signalChanged(true), "the first update is always something new");

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(gate.signalChanged(true), "the signal has not changed, so there is nothing to do");
        }
    }

    @Test
    void theFirstUpdateCountsWhicheverWayItReads() {
        assertTrue(new RedstoneGate().signalChanged(true));
        assertTrue(new RedstoneGate().signalChanged(false));
    }

    @Test
    void aRealChangeIsActedOnBothWays() {
        RedstoneGate gate = new RedstoneGate();
        gate.signalChanged(false);
        assertTrue(gate.signalChanged(true), "switched on");
        assertTrue(gate.signalChanged(false), "switched off");
    }

    /** The hole the change check alone leaves: a signal that really is
     *  toggling, which is a change every time and so passes the first rule. */
    @Test
    void aClockCannotEngageMoreOftenThanTheCooldown() {
        RedstoneGate gate = new RedstoneGate();
        int attempts = 0;

        for (long tick = 0; tick < RedstoneGate.ENGAGE_RETRY_TICKS * 3; tick++) {
            gate.signalChanged(tick % 2 == 0);
            if (gate.mayEngage(tick)) {
                gate.engageAttempted(tick);
                attempts++;
            }
        }

        assertTrue(attempts <= 3, "three cooldowns should allow at most three attempts, got " + attempts);
    }

    @Test
    void theFirstEngageIsNeverBlocked() {
        assertTrue(new RedstoneGate().mayEngage(0), "nothing has been attempted yet");
    }

    @Test
    void aSecondEngageWaitsForTheCooldown() {
        RedstoneGate gate = new RedstoneGate();
        gate.engageAttempted(1000);

        assertFalse(gate.mayEngage(1000), "same tick");
        assertFalse(gate.mayEngage(1000 + RedstoneGate.ENGAGE_RETRY_TICKS - 1), "one tick short");
        assertTrue(gate.mayEngage(1000 + RedstoneGate.ENGAGE_RETRY_TICKS), "exactly due");
        assertTrue(gate.mayEngage(1000 + RedstoneGate.ENGAGE_RETRY_TICKS * 10), "long overdue");
    }

    /** A lever left on over a plane that cannot engage has to keep asking, or
     *  fixing whatever was wrong would never start the flight - just not
     *  twenty times a second. */
    @Test
    void aLeverLeftOnRetriesEventually() {
        RedstoneGate gate = new RedstoneGate();
        gate.signalChanged(true);
        gate.engageAttempted(0);

        assertFalse(gate.mayEngage(50), "too soon to be worth rescanning the route");
        assertTrue(gate.mayEngage(RedstoneGate.ENGAGE_RETRY_TICKS), "worth another try by now");
    }
}
