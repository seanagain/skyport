package com.seaport.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finding a way across charted water.
 *
 * Ordinary A*, with two things about it that are not ordinary and both come
 * from where it is running.
 *
 * It is BUDGETED. Searching a sea is unbounded in a way searching a road
 * network is not - there is no wall to stop against, so a route to somewhere
 * unreachable would expand outwards until something gave way. Every search
 * gets a ceiling on how many cells it may open, and hitting that ceiling is a
 * normal outcome reported as one, not an error. The same ceiling is what keeps
 * a search inside a server tick.
 *
 * And it only crosses water somebody has SURVEYED. An uncharted cell is not
 * treated as open sea to be discovered on arrival; it is a wall. That makes
 * the router refuse routes it could technically have found, which is the
 * right trade: the alternative is a confident course through an island nobody
 * has looked at yet.
 *
 * Nothing here touches the world, so a route can be planned from a chart in a
 * test with no server at all.
 */
public final class NavRoute {

    private NavRoute() { }

    /** A cell, in chart coordinates rather than blocks. */
    public record Cell(int x, int z) {

        double distanceTo(Cell other) {
            int dx = x - other.x();
            int dz = z - other.z();
            return Math.sqrt((double) dx * dx + (double) dz * dz);
        }
    }

    /**
     * @param cells     the course, start first and destination last; empty
     *                  when none was found
     * @param opened    how many cells the search looked at, for tuning the
     *                  budget against a real sea rather than a guess
     * @param exhausted the budget ran out - there may well be a route, but
     *                  this search did not get to it. Distinct from simply
     *                  having no route, because the answers differ: one is
     *                  "try again with more" and the other is "there is no
     *                  way through".
     */
    public record Plan(List<Cell> cells, int opened, boolean exhausted) {

        public boolean found() {
            return !cells.isEmpty();
        }

        public static Plan none(int opened, boolean exhausted) {
            return new Plan(List.of(), opened, exhausted);
        }
    }

    /** The eight neighbours. Diagonals included because a ship has no reason
     *  to sail a staircase, and their extra cost is what stops the route
     *  preferring them when they are no shorter. */
    private static final int[][] NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    public static Plan plan(NavChart chart, Cell from, Cell to, int draft, int margin, int budget) {
        if (from.equals(to)) return new Plan(List.of(from), 0, false);

        // The destination being unreachable for this hull is worth finding out
        // before searching the sea rather than after: a berth too shallow for
        // the ship booked into it would otherwise cost a full exhausted search
        // every time somebody tried.
        if (!chart.passable(to.x(), to.z(), draft, margin)) return Plan.none(0, false);

        Map<Cell, Cell> cameFrom = new HashMap<>();
        Map<Cell, Double> costSoFar = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        PriorityQueue<Step> open = new PriorityQueue<>();

        costSoFar.put(from, 0.0);
        open.add(new Step(from, from.distanceTo(to)));
        int opened = 0;

        while (!open.isEmpty()) {
            if (opened >= budget) return Plan.none(opened, true);

            Step current = open.poll();
            if (current.cell().equals(to)) {
                return new Plan(retrace(cameFrom, from, to), opened, false);
            }
            if (!closed.add(current.cell())) continue;
            opened++;

            for (int[] step : NEIGHBOURS) {
                Cell next = new Cell(current.cell().x() + step[0], current.cell().z() + step[1]);
                if (closed.contains(next)) continue;
                // The start is allowed to be somewhere the ship could not
                // route through - it may be in a berth shallower than its
                // own draft, which is exactly where it has to leave from.
                if (!chart.passable(next.x(), next.z(), draft, margin)) continue;

                double cost = costSoFar.get(current.cell()) + current.cell().distanceTo(next);
                Double best = costSoFar.get(next);
                if (best != null && cost >= best) continue;

                costSoFar.put(next, cost);
                cameFrom.put(next, current.cell());
                open.add(new Step(next, cost + next.distanceTo(to)));
            }
        }
        return Plan.none(opened, false);
    }

    private static List<Cell> retrace(Map<Cell, Cell> cameFrom, Cell from, Cell to) {
        List<Cell> course = new ArrayList<>();
        Cell at = to;
        while (!at.equals(from)) {
            course.add(at);
            at = cameFrom.get(at);
            // Should not happen, and a corrupt trail must not spin forever
            // inside a server tick if it ever does.
            if (at == null) return List.of();
        }
        course.add(from);
        Collections.reverse(course);
        return course;
    }

    /**
     * Drop the cells a straight line would have covered anyway.
     *
     * A* returns every cell it stepped through, which across open sea is
     * hundreds of them in a dead straight line. A ship wants the corners - the
     * places where the course actually changes - and keeping the rest means
     * steering toward a waypoint four blocks ahead, over and over, with the
     * rounding error of each one bent into the next.
     */
    public static List<Cell> corners(List<Cell> course) {
        if (course.size() <= 2) return List.copyOf(course);

        List<Cell> out = new ArrayList<>();
        out.add(course.get(0));
        for (int i = 1; i < course.size() - 1; i++) {
            Cell previous = course.get(i - 1);
            Cell here = course.get(i);
            Cell next = course.get(i + 1);
            boolean sameHeading = (here.x() - previous.x()) == (next.x() - here.x())
                    && (here.z() - previous.z()) == (next.z() - here.z());
            if (!sameHeading) out.add(here);
        }
        out.add(course.get(course.size() - 1));
        return out;
    }

    private record Step(Cell cell, double estimate) implements Comparable<Step> {
        @Override
        public int compareTo(Step other) {
            return Double.compare(estimate, other.estimate());
        }
    }
}
