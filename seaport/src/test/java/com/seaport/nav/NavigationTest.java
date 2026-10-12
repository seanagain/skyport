package com.seaport.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Charting water, and finding a way across it.
 *
 * The distinction this whole area turns on is between water that is known to
 * be shallow and water nobody has looked at. They are stored differently,
 * answered differently and routed through differently, because confusing them
 * produces the worst possible failure: a confident course drawn through an
 * island that was simply never surveyed.
 *
 * The other thing under test is that searching a sea is bounded. There is no
 * wall at the edge of an ocean to stop an unbounded search, so a route to
 * somewhere unreachable has to run out of budget and say so, inside a tick,
 * rather than expanding until the server notices.
 */
class NavigationTest {

    private static final int SEA_LEVEL = 63;
    /** Water under the keel, over and above the draft itself. */
    private static final int MARGIN = 1;

    // ---- soundings -------------------------------------------------------

    @Test
    void depthIsMeasuredDownFromSeaLevel() {
        assertEquals(13, Soundings.depth(50, SEA_LEVEL));
        assertEquals(1, Soundings.depth(62, SEA_LEVEL));
    }

    /** A seabed at or above sea level is a beach, and a beach is not water
     *  one block deep. */
    @Test
    void groundAtOrAboveSeaLevelIsDry() {
        assertEquals(Soundings.DRY, Soundings.depth(SEA_LEVEL, SEA_LEVEL));
        assertEquals(Soundings.DRY, Soundings.depth(90, SEA_LEVEL), "a hill is not negative water");
    }

    /** Exactly enough water is not enough. A hull drawing three blocks in
     *  three blocks of water is aground by any useful definition. */
    @Test
    void floatingNeedsWaterUnderTheKeelNotJustAroundIt() {
        assertFalse(Soundings.floats(3, 3, MARGIN));
        assertTrue(Soundings.floats(4, 3, MARGIN));
    }

    /** A patch of water is as navigable as its worst point. Averaging would
     *  make a sandbar disappear into the deep water around it. */
    @Test
    void aPatchIsJudgedByItsShallowestSounding() {
        assertEquals(2, Soundings.shallowest(new int[] { 14, 9, 2, 11 }));
        assertEquals(Soundings.DRY, Soundings.shallowest(new int[] { }));
    }

    @Test
    void draftIsHowFarTheHullReachesBelowTheWaterline() {
        assertEquals(4, Soundings.draftOf(59, SEA_LEVEL));
        assertEquals(1, Soundings.draftOf(62.4, SEA_LEVEL), "part of a block still displaces water");
    }

    /** A craft sitting entirely above the water draws nothing. Saying zero
     *  rather than a negative number keeps every comparison honest. */
    @Test
    void somethingAboveTheWaterDrawsNothing() {
        assertEquals(0, Soundings.draftOf(70, SEA_LEVEL));
    }

    // ---- the chart -------------------------------------------------------

    @Test
    void unsurveyedWaterIsNotTheSameAsShallowWater() {
        NavChart chart = new NavChart();
        assertEquals(NavChart.UNCHARTED, chart.depth(5, 5));
        assertFalse(chart.isCharted(5, 5));

        chart.record(5, 5, Soundings.DRY, 100);
        assertEquals(Soundings.DRY, chart.depth(5, 5));
        assertTrue(chart.isCharted(5, 5), "known to be land is a survey result, not an absence");
    }

    /** The whole point of the distinction: a ship must not route through
     *  water nobody has looked at, however deep it might turn out to be. */
    @Test
    void unsurveyedWaterIsImpassable() {
        NavChart chart = new NavChart();
        assertFalse(chart.passable(0, 0, 3, MARGIN));
        chart.record(0, 0, 10, 100);
        assertTrue(chart.passable(0, 0, 3, MARGIN));
    }

    @Test
    void aCellIsPassableOnlyForHullsThatFitIt() {
        NavChart chart = new NavChart();
        chart.record(0, 0, 4, 100);
        assertTrue(chart.passable(0, 0, 2, MARGIN), "a tug gets through");
        assertFalse(chart.passable(0, 0, 4, MARGIN), "a freighter does not");
    }

    /** Players dig canals and build causeways. A chart that only ever gained
     *  entries would sail ships into a strait somebody filled in. */
    @Test
    void aCellCanBeForgottenWhenTheWorldChanges() {
        NavChart chart = new NavChart();
        chart.record(2, 2, 10, 100);
        chart.forget(2, 2);
        assertFalse(chart.isCharted(2, 2));
        assertEquals(NavChart.UNCHARTED, chart.depth(2, 2));
    }

    @Test
    void surveyTimeIsKeptSoStaleWaterCanBeLookedAtAgain() {
        NavChart chart = new NavChart();
        assertEquals(0, chart.surveyedAt(1, 1), "never surveyed");
        chart.record(1, 1, 8, 4200);
        assertEquals(4200, chart.surveyedAt(1, 1));
    }

    /** Block -1 belongs to cell -1. Rounding toward zero instead would put a
     *  seam through the chart along the world origin, where two different
     *  patches of sea share a cell. */
    @Test
    void cellsTileTheWorldWithoutASeamAtTheOrigin() {
        assertEquals(0, NavChart.toCell(0));
        assertEquals(0, NavChart.toCell(3));
        assertEquals(1, NavChart.toCell(4));
        assertEquals(-1, NavChart.toCell(-1));
        assertEquals(-1, NavChart.toCell(-4));
        assertEquals(-2, NavChart.toCell(-5));
    }

    // ---- routing ---------------------------------------------------------

    /** Open water, charted deep. */
    private static NavChart openSea(int halfWidth, int depth) {
        NavChart chart = new NavChart();
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfWidth; z <= halfWidth; z++) chart.record(x, z, depth, 1);
        }
        return chart;
    }

    @Test
    void aCourseAcrossOpenWaterIsFound() {
        NavRoute.Plan plan = NavRoute.plan(openSea(10, 20),
                new NavRoute.Cell(-5, 0), new NavRoute.Cell(5, 0), 3, MARGIN, 10_000);
        assertTrue(plan.found());
        assertFalse(plan.exhausted());
        assertEquals(new NavRoute.Cell(-5, 0), plan.cells().get(0));
        assertEquals(new NavRoute.Cell(5, 0), plan.cells().get(plan.cells().size() - 1));
    }

    /** A wall of shallows with one gap in it. The course has to use the gap,
     *  which is the whole reason to pathfind rather than steer a bearing. */
    @Test
    void aCourseGoesThroughTheGapInABar() {
        NavChart chart = openSea(10, 20);
        for (int z = -10; z <= 10; z++) {
            if (z == 4) continue;                 // the one gap
            chart.record(0, z, 1, 1);             // too shallow for anything
        }

        NavRoute.Plan plan = NavRoute.plan(chart,
                new NavRoute.Cell(-5, 0), new NavRoute.Cell(5, 0), 3, MARGIN, 10_000);
        assertTrue(plan.found());
        assertTrue(plan.cells().contains(new NavRoute.Cell(0, 4)),
                "the only way through the bar is the gap");
    }

    /** The same bar, no gap. There is genuinely no way across, and that has
     *  to come back as a refusal rather than a search that never ends. */
    @Test
    void aSeaWithNoWayThroughIsRefusedRatherThanSearchedForever() {
        NavChart chart = openSea(10, 20);
        for (int z = -10; z <= 10; z++) chart.record(0, z, 1, 1);

        NavRoute.Plan plan = NavRoute.plan(chart,
                new NavRoute.Cell(-5, 0), new NavRoute.Cell(5, 0), 3, MARGIN, 10_000);
        assertFalse(plan.found());
        assertFalse(plan.exhausted(), "it finished looking - there is no route, which is a real answer");
    }

    /**
     * Running out of budget and having no route are different answers, and
     * the caller does different things about them: one is "ask again with
     * more", the other is "there is no way through".
     */
    @Test
    void anExhaustedSearchSaysSoRatherThanClaimingNoRoute() {
        NavRoute.Plan plan = NavRoute.plan(openSea(40, 20),
                new NavRoute.Cell(-40, -40), new NavRoute.Cell(40, 40), 3, MARGIN, 25);
        assertFalse(plan.found());
        assertTrue(plan.exhausted());
        assertTrue(plan.opened() <= 25, "the ceiling is what keeps this inside a tick");
    }

    /** A berth too shallow for the ship booked into it should cost one
     *  comparison, not a full search of the sea that fails at the last cell. */
    @Test
    void anUnreachableDestinationIsRefusedBeforeSearching() {
        NavChart chart = openSea(10, 20);
        chart.record(5, 0, 2, 1);

        NavRoute.Plan plan = NavRoute.plan(chart,
                new NavRoute.Cell(-5, 0), new NavRoute.Cell(5, 0), 3, MARGIN, 10_000);
        assertFalse(plan.found());
        assertEquals(0, plan.opened(), "it should not have looked at a single cell");
    }

    @Test
    void aRouteToWhereYouAlreadyAreIsTrivialRatherThanEmpty() {
        NavRoute.Plan plan = NavRoute.plan(openSea(5, 20),
                new NavRoute.Cell(1, 1), new NavRoute.Cell(1, 1), 3, MARGIN, 10_000);
        assertTrue(plan.found());
        assertEquals(List.of(new NavRoute.Cell(1, 1)), plan.cells());
    }

    /** A deep hull and a shallow one get different courses through the same
     *  water, which is the point of charting depth rather than passability. */
    @Test
    void draftDecidesWhichWatersAreOpenToAShip() {
        NavChart chart = openSea(10, 20);
        for (int z = -10; z <= 10; z++) {
            if (z == 4) continue;
            chart.record(0, z, 5, 1);             // a shoal: deep enough for a tug only
        }

        NavRoute.Cell from = new NavRoute.Cell(-5, 0);
        NavRoute.Cell to = new NavRoute.Cell(5, 0);
        assertFalse(NavRoute.plan(chart, from, to, 3, MARGIN, 10_000).cells()
                        .contains(new NavRoute.Cell(0, 4)),
                "a shallow hull crosses the shoal directly rather than detouring");
        assertTrue(NavRoute.plan(chart, from, to, 6, MARGIN, 10_000).cells()
                        .contains(new NavRoute.Cell(0, 4)),
                "a deep hull has to use the gap");
    }

    // ---- the course a helmsman actually wants ----------------------------

    /** A* hands back every cell it stepped through, which across open sea is
     *  a straight line of hundreds. A ship wants the corners. */
    @Test
    void aStraightRunIsReducedToItsEnds() {
        List<NavRoute.Cell> straight = List.of(
                new NavRoute.Cell(0, 0), new NavRoute.Cell(1, 0), new NavRoute.Cell(2, 0),
                new NavRoute.Cell(3, 0), new NavRoute.Cell(4, 0));
        assertEquals(List.of(new NavRoute.Cell(0, 0), new NavRoute.Cell(4, 0)),
                NavRoute.corners(straight));
    }

    @Test
    void aTurnIsKeptBecauseThatIsWhereTheCourseChanges() {
        List<NavRoute.Cell> dogleg = List.of(
                new NavRoute.Cell(0, 0), new NavRoute.Cell(1, 0), new NavRoute.Cell(2, 0),
                new NavRoute.Cell(2, 1), new NavRoute.Cell(2, 2));
        assertEquals(List.of(new NavRoute.Cell(0, 0), new NavRoute.Cell(2, 0), new NavRoute.Cell(2, 2)),
                NavRoute.corners(dogleg));
    }

    @Test
    void aCourseTooShortToHaveCornersComesBackUnchanged() {
        List<NavRoute.Cell> pair = List.of(new NavRoute.Cell(0, 0), new NavRoute.Cell(1, 1));
        assertEquals(pair, NavRoute.corners(pair));
    }
}
