package com.skyport.logic;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Trimming the part of a taxi route an aircraft has already driven.
 *
 * The case this exists for: a stand on a short spur. A route is snapped to
 * the nearest node, so an aircraft that has just reversed to the junction can
 * still be nearer the stand than the junction is, and the route comes back
 * beginning at the stand. Followed literally, the aircraft drives forward
 * onto the stand it just left, turns round and comes back out - an expensive
 * way to undo a pushback.
 */
class TaxiRouteTrimTest {

    private static final BlockPos STAND = new BlockPos(0, 64, 0);
    private static final BlockPos JUNCTION = new BlockPos(0, 64, 10);
    private static final BlockPos CORNER = new BlockPos(30, 64, 10);
    private static final BlockPos RUNWAY = new BlockPos(60, 64, 10);
    private static final List<BlockPos> ROUTE = List.of(STAND, JUNCTION, CORNER, RUNWAY);

    @Test
    void anAircraftStandingOnTheJunctionDoesNotGoBackToTheStand() {
        List<BlockPos> trimmed = GroundNetwork.dropPassed(ROUTE, JUNCTION);
        assertFalse(trimmed.contains(STAND), "the stand it just left is behind it");
        assertEquals(CORNER, trimmed.get(0), "the next node out is where it should head");
        assertEquals(RUNWAY, trimmed.get(trimmed.size() - 1));
    }

    /** Mid-pushback, or a pushback that gave up part way along the spur - the
     *  case the log actually caught. */
    @Test
    void anAircraftPartWayDownTheSpurCarriesOnOutwards() {
        List<BlockPos> trimmed = GroundNetwork.dropPassed(ROUTE, new BlockPos(0, 64, 7));
        assertEquals(JUNCTION, trimmed.get(0), "still short of the junction, so that is the next node");
        assertFalse(trimmed.contains(STAND));
    }

    /** Still at the stand: the stand is where it is, so the route starts at
     *  the junction rather than telling it to drive to its own parking spot. */
    @Test
    void anAircraftAtTheStandHeadsForTheJunction() {
        assertEquals(JUNCTION, GroundNetwork.dropPassed(ROUTE, STAND).get(0));
    }

    /** Parked short of the whole network - nothing has been driven yet. */
    @Test
    void anAircraftBehindTheStartKeepsTheWholeRoute() {
        assertEquals(ROUTE, GroundNetwork.dropPassed(ROUTE, new BlockPos(0, 64, -40)));
    }

    /**
     * Nodes are only dropped from the front, and only while each one really
     * is behind the next. An aircraft that is somehow near the far end has not
     * driven the route, so the route is left alone rather than collapsed to
     * its last node - a departure that starts at the runway is a layout
     * problem, and eating the route would hide it.
     */
    @Test
    void beingNearTheEndDoesNotSwallowTheRoute() {
        assertEquals(ROUTE, GroundNetwork.dropPassed(ROUTE, RUNWAY));
    }

    /** The destination is never trimmed away. */
    @Test
    void aSingleNodeRouteSurvives() {
        assertEquals(List.of(RUNWAY), GroundNetwork.dropPassed(List.of(RUNWAY), STAND));
        assertEquals(List.of(RUNWAY), GroundNetwork.dropPassed(List.of(RUNWAY), RUNWAY));
    }

    @Test
    void anEmptyRouteStaysEmpty() {
        assertEquals(List.of(), GroundNetwork.dropPassed(List.of(), STAND));
    }
}
