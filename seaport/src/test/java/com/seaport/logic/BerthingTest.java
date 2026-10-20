package com.seaport.logic;

import com.seaport.logic.Berthing.Hull;
import com.seaport.logic.Berthing.Point;
import com.seaport.logic.Berthing.QuayLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing a rectangle to rest alongside a line.
 *
 * The rule is simple to state - the hull's edge stops one block off the quay -
 * and the trap is that the edge is not a fixed thing. A ship lying alongside
 * presents its flank, one coming in bow-first presents its stem, and a hull
 * at an angle presents a corner that reaches further than either. Fix the
 * distance once when the voyage starts and a vessel that drifts a few degrees
 * will believe it is berthed with a corner still well out in the water.
 *
 * So most of what is tested here is the same ship, at the same place, pointing
 * different ways, and insisting the answer changes.
 */
class BerthingTest {

    /** A quay twenty blocks long running east-west along z = 0. */
    private static final QuayLine QUAY = new QuayLine(new Point(0, 0), new Point(20, 0));

    private static final double EAST = 0;
    private static final double NORTH = Math.PI / 2;

    private static final double TOLERANCE = 0.25;

    /** A small freighter: twelve long, four across. */
    private static Hull ship(double x, double z, double heading) {
        return new Hull(new Point(x, z), heading, 6, 2);
    }

    // ---- which way the hull faces -----------------------------------------

    /** Lying parallel to the quay, the beam is what faces it: half of four
     *  is two, so a centre five blocks out leaves a three block gap. */
    @Test
    void aShipAlongsidePresentsItsBeam() {
        assertEquals(3.0, Berthing.edgeGap(ship(10, 5, EAST), QUAY), 1e-9);
    }

    /** Turned to face the quay, the length faces it instead: half of twelve
     *  is six, so the same centre is now a block INSIDE the stone. */
    @Test
    void theSameShipBowOnReachesMuchFurther() {
        assertEquals(-1.0, Berthing.edgeGap(ship(10, 5, NORTH), QUAY), 1e-9);
    }

    /**
     * At forty-five degrees it is a corner that reaches, and a corner reaches
     * further than either side alone. This is the case that a fixed edge
     * distance gets wrong and then drives into the wall.
     */
    @Test
    void aShipAtAnAngleReachesByItsCorner() {
        double diagonal = Berthing.edgeGap(ship(10, 8, Math.PI / 4), QUAY);
        double alongside = Berthing.edgeGap(ship(10, 8, EAST), QUAY);
        assertTrue(diagonal < alongside,
                "a corner must reach further than a flank: " + diagonal + " vs " + alongside);
        // halfLength*cos45 + halfBeam*sin45 = (6 + 2) * 0.7071 = 5.657
        assertEquals(8 - 5.657, diagonal, 0.01);
    }

    @Test
    void theGapIsNegativeWhenTheHullOverlapsTheQuay() {
        assertTrue(Berthing.edgeGap(ship(10, 1, EAST), QUAY) < 0,
                "reporting the overlap as a number beats clamping it to zero - "
                        + "a ship touching a wall and one driving through it need different answers");
    }

    // ---- berthed, or not --------------------------------------------------

    @Test
    void aShipOneBlockOffTheQuayIsBerthed() {
        // Beam reach 2, so a centre 3 out leaves exactly the wanted 1.
        assertTrue(Berthing.isBerthed(ship(10, 3, EAST), QUAY, 1.0, TOLERANCE));
    }

    @Test
    void aShipStillOutInTheHarbourIsNotBerthed() {
        assertFalse(Berthing.isBerthed(ship(10, 9, EAST), QUAY, 1.0, TOLERANCE));
    }

    /** Through the quay is not berthed however close the number looks. */
    @Test
    void aShipInsideTheQuayIsNotBerthed() {
        assertFalse(Berthing.isBerthed(ship(10, 0.5, EAST), QUAY, 1.0, TOLERANCE));
    }

    /**
     * A rigid body never stops exactly anywhere. Without a tolerance a ship
     * hunts for a number it cannot hit and never declares itself berthed.
     */
    @Test
    void aShipCloseEnoughCountsAsBerthed() {
        assertTrue(Berthing.isBerthed(ship(10, 3.2, EAST), QUAY, 1.0, TOLERANCE));
        assertFalse(Berthing.isBerthed(ship(10, 3.6, EAST), QUAY, 1.0, TOLERANCE),
                "but not arbitrarily close - the tolerance is a window, not a shrug");
    }

    /** The clearance is a port setting, so a harbour that wants its ships
     *  standing further off gets them. */
    @Test
    void theWantedGapIsWhateverThePortAsksFor() {
        Hull hull = ship(10, 5, EAST);              // gap of 3
        assertTrue(Berthing.isBerthed(hull, QUAY, 3.0, TOLERANCE));
        assertFalse(Berthing.isBerthed(hull, QUAY, 1.0, TOLERANCE));
    }

    // ---- the ends of the quay ---------------------------------------------

    @Test
    void aShipBesideTheQuayIsAlongsideIt() {
        assertTrue(Berthing.isAlongside(ship(10, 3, EAST), QUAY));
    }

    /** Past the end of the stone is beside open water, which is not berthed
     *  however near the line the ship has come. */
    @Test
    void aShipPastTheEndOfTheQuayIsNot() {
        assertFalse(Berthing.isAlongside(ship(40, 3, EAST), QUAY));
        assertFalse(Berthing.isBerthed(ship(40, 3, EAST), QUAY, 1.0, TOLERANCE));
    }

    /** A long ship overhanging the end is still alongside, because any
     *  reasonable person looking at it would say so. */
    @Test
    void aShipOverhangingTheEndIsStillAlongside() {
        assertTrue(Berthing.isAlongside(ship(23, 3, EAST), QUAY),
                "its stern is still against the stone");
    }

    // ---- steering toward a berth ------------------------------------------

    /** One signed number, so the caller never has to work out which side of
     *  the quay it started on - the step that gets inverted and drives ships
     *  into the harbour wall. */
    @Test
    void theApproachSaysHowFarAndWhichWay() {
        assertEquals(2.0, Berthing.approachOffset(ship(10, 5, EAST), QUAY, 1.0), 1e-9);
        assertTrue(Berthing.approachOffset(ship(10, 2.5, EAST), QUAY, 1.0) < 0,
                "too close - back off");
    }

    @Test
    void theBerthIsOffsetFromTheQuayByTheHullsOwnReach() {
        Hull hull = ship(10, 9, EAST);
        Point target = Berthing.berthCentre(hull, QUAY, 1.0, 10);
        // Beam reach 2, plus the wanted 1, on the side the ship is already on.
        assertEquals(10, target.x(), 1e-9);
        assertEquals(3, target.z(), 1e-9);

        Hull berthed = new Hull(target, EAST, 6, 2);
        assertTrue(Berthing.isBerthed(berthed, QUAY, 1.0, TOLERANCE),
                "steering to the point it gives should actually berth the ship");
    }

    /**
     * A harbour has water on one side of its quay and stone on the other, and
     * nothing records which. Where the ship already is settles it - so a
     * vessel approaching from the south must not be sent to the north side.
     */
    @Test
    void theBerthIsOnTheSideTheShipIsAlreadyOn() {
        assertTrue(Berthing.berthCentre(ship(10, 9, EAST), QUAY, 1.0, 10).z() > 0);
        assertTrue(Berthing.berthCentre(ship(10, -9, EAST), QUAY, 1.0, 10).z() < 0);
    }

    /** A bow-on ship has to stop further out than a side-on one, and the
     *  berth point has to know that. */
    @Test
    void aBowOnBerthStandsFurtherOff() {
        double sideOn = Berthing.berthCentre(ship(10, 9, EAST), QUAY, 1.0, 10).z();
        double bowOn = Berthing.berthCentre(ship(10, 9, NORTH), QUAY, 1.0, 10).z();
        assertEquals(3, sideOn, 1e-9);
        assertEquals(7, bowOn, 1e-9);
    }

    @Test
    void aBerthPastTheEndOfTheQuayIsBroughtBackOntoIt() {
        Point target = Berthing.berthCentre(ship(10, 9, EAST), QUAY, 1.0, 500);
        assertEquals(20, target.x(), 1e-9, "clamped to the end of the stone");
    }

    // ---- degenerate input --------------------------------------------------

    /** A wharf drawn with both ends in one block has no direction. The
     *  layout refuses to store one, but the arithmetic must stay finite
     *  rather than producing NaN that spreads through every later sum. */
    @Test
    void aWharfWithNoLengthDoesNotProduceNonsense() {
        QuayLine degenerate = new QuayLine(new Point(5, 5), new Point(5, 5));
        double gap = Berthing.edgeGap(ship(10, 10, EAST), degenerate);
        assertFalse(Double.isNaN(gap), "NaN here would spread into every later calculation");
    }
}
