package com.skyport.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When an aircraft counts as having flown over a VOR.
 *
 * Each rule here is the fix for a way the obvious test goes wrong in flight:
 * too tight and a wide-turning aircraft circles a VOR forever, too loose and
 * it turns for the next point before it has gone anywhere near this one.
 */
class VorPassageTest {

    @Test
    void flyingStraightOverCountsOnceOverhead() {
        VorPassage pass = new VorPassage();
        long t = 0;
        for (double d = 300; d > 20; d -= 10) {
            assertFalse(pass.update(d, t++), "not over it yet at " + d + " blocks");
        }
        assertTrue(pass.update(15, t));
    }

    /** Swinging past just outside the overhead radius - the case that would
     *  otherwise turn back round and try again indefinitely. */
    @Test
    void aNearMissCountsOnceItOpensBackOut() {
        VorPassage pass = new VorPassage();
        long t = 0;
        for (double d = 200; d >= 30; d -= 10) assertFalse(pass.update(d, t++));
        assertFalse(pass.update(34, t++), "a little wobble is not a miss");
        assertTrue(pass.update(40, t));
    }

    /** Far out, a growing distance is an aircraft still turning toward the
     *  VOR - the climb-out often points it somewhere else entirely. */
    @Test
    void movingAwayFromAFarVorIsNotPassingIt() {
        VorPassage pass = new VorPassage();
        assertFalse(pass.update(500, 0));
        assertFalse(pass.update(520, 1));
        assertFalse(pass.update(600, 2));
    }

    /** Near, and no longer getting any nearer, for long enough. */
    @Test
    void circlingNearbyCountsEventually() {
        VorPassage pass = new VorPassage();
        assertFalse(pass.update(30, 0));
        assertFalse(pass.update(30.2, VorPassage.STALL_TICKS - 1));
        assertTrue(pass.update(29.9, VorPassage.STALL_TICKS));
    }

    /** A loop over the same VOR each lap needs a fresh pass every time. A
     *  closest distance left over from the last lap would call the VOR passed
     *  the moment the next lap began. */
    @Test
    void resetForgetsThePreviousPass() {
        VorPassage pass = new VorPassage();
        pass.update(30, 0);
        pass.reset();
        assertFalse(pass.update(200, 1000));
    }
}
