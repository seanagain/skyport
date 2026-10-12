package com.skyport.logic;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The airport's ground network - runway plus taxiway segments - as a graph,
 * and the routing over it.
 *
 * Lives here rather than inside the autopilot because the map editor needs
 * the same answer. Once taxiway segments can be made one-way, "is this gate
 * still reachable" stops being obvious from looking at the drawing, and the
 * editor has to be able to warn about a change that strands one. Two
 * implementations of the same graph would drift, and the failure mode of that
 * drift is an editor that says a layout is fine and an autopilot that can't
 * route it.
 *
 * Direction is carried on the first waypoint of each pair - see
 * {@link Waypoint.Flow}.
 */
public final class GroundNetwork {

    private GroundNetwork() {}

    /** Adjacency, honouring each segment's direction. */
    public static Map<BlockPos, List<BlockPos>> graph(AirportLayout layout) {
        Map<BlockPos, List<BlockPos>> graph = new HashMap<>();
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);
        for (int i = 0; i + 1 < taxiway.size(); i += 2) {
            BlockPos a = taxiway.get(i).pos();
            BlockPos b = taxiway.get(i + 1).pos();
            switch (taxiway.get(i).flow()) {
                case BOTH -> link(graph, a, b);
                case FORWARD -> linkOneWay(graph, a, b);
                case REVERSE -> linkOneWay(graph, b, a);
            }
        }
        // Runways are pairs too, so a second one joins the network the same
        // way the first does. Without this a departure runway would be drawn
        // but unreachable, and every aircraft would route to it in a straight
        // line across the grass.
        List<Waypoint> runway = layout.waypoints(Waypoint.Type.RUNWAY);
        for (int i = 0; i + 1 < runway.size(); i += 2) {
            link(graph, runway.get(i).pos(), runway.get(i + 1).pos());
        }
        return graph;
    }

    private static void link(Map<BlockPos, List<BlockPos>> graph, BlockPos a, BlockPos b) {
        linkOneWay(graph, a, b);
        linkOneWay(graph, b, a);
    }

    private static void linkOneWay(Map<BlockPos, List<BlockPos>> graph, BlockPos from, BlockPos to) {
        graph.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        // The far end still has to exist as a node even with nothing leaving
        // it, or a one-way spur into a gate reads as a hole in the network.
        graph.computeIfAbsent(to, k -> new ArrayList<>());
    }

    /**
     * Dijkstra from one point to another. Both are snapped to the nearest
     * node first, since an aircraft is parked near a drawn line rather than
     * exactly on one of its points.
     *
     * Returns an empty list when there is no route - which, with one-way
     * segments in play, is a thing a player can now draw by accident.
     */
    public static List<BlockPos> route(AirportLayout layout, BlockPos from, BlockPos to) {
        Map<BlockPos, List<BlockPos>> graph = graph(layout);
        if (graph.isEmpty()) return List.of();

        BlockPos start = nearestNode(graph.keySet(), from);
        BlockPos goal = nearestNode(graph.keySet(), to);
        if (start == null || goal == null) return List.of();

        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingDouble(p -> best.getOrDefault(p, Double.MAX_VALUE)));
        best.put(start, 0.0);
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos node = queue.poll();
            if (node.equals(goal)) break;
            double baseCost = best.getOrDefault(node, Double.MAX_VALUE);
            for (BlockPos neighbour : graph.getOrDefault(node, List.of())) {
                double cost = baseCost + horizontalDistance(node, neighbour);
                if (cost < best.getOrDefault(neighbour, Double.MAX_VALUE)) {
                    best.put(neighbour, cost);
                    cameFrom.put(neighbour, node);
                    queue.add(neighbour);
                }
            }
        }
        if (!best.containsKey(goal)) return List.of();

        List<BlockPos> path = new ArrayList<>();
        for (BlockPos at = goal; at != null; at = cameFrom.get(at)) {
            path.add(at);
            if (at.equals(start)) break;
        }
        java.util.Collections.reverse(path);
        // The real destination (a gate) may sit slightly off its node.
        if (!path.isEmpty() && !path.get(path.size() - 1).equals(to)) path.add(to);
        return path;
    }

    /**
     * Drop the leading nodes an aircraft has already driven past.
     *
     * A route is snapped to the nearest node of the network, which is right
     * for an aircraft parked beside a line and wrong for one that has just
     * reversed off a stand: on a short spur the nearest node to the junction
     * it is standing on is still the stand itself. Taken literally, the route
     * then begins "drive to the stand", so the aircraft pulls forward onto
     * the stand it just left, turns, and comes back out - which is precisely
     * what a pushback exists to avoid.
     *
     * A node counts as passed when the aircraft is at least as close to the
     * NEXT node as that node is. That is true of a node behind the aircraft
     * and of one it is standing on, and false of one ahead - and unlike a
     * heading, it means something for a craft that is stationary.
     *
     * The last node is never dropped: the end of the route is where the
     * aircraft is going, however close it already is.
     */
    public static List<BlockPos> dropPassed(List<BlockPos> route, BlockPos at) {
        int first = 0;
        while (first + 1 < route.size()
                && horizontalDistance(at, route.get(first + 1))
                        <= horizontalDistance(route.get(first), route.get(first + 1))) {
            first++;
        }
        return first == 0 ? route : List.copyOf(route.subList(first, route.size()));
    }

    /**
     * Gates that can no longer be both reached from and returned to the
     * runway, by name.
     *
     * Both directions matter and they are not the same question once
     * segments are one-way: a gate an aircraft can taxi into but never leave
     * is just as broken as one it can never reach, and strands the aircraft
     * rather than merely refusing it.
     */
    public static List<String> strandedGates(AirportLayout layout) {
        List<Waypoint> runway = layout.waypoints(Waypoint.Type.RUNWAY);
        if (runway.size() < 2 || layout.gates().isEmpty()) return List.of();
        BlockPos gateEnd = runway.get(0).pos();

        Map<BlockPos, List<BlockPos>> graph = graph(layout);
        if (graph.isEmpty()) return List.of();
        BlockPos start = nearestNode(graph.keySet(), gateEnd);
        if (start == null) return List.of();

        Set<BlockPos> fromRunway = reachableFrom(graph, start);
        Set<BlockPos> toRunway = reachableFrom(reversed(graph), start);

        List<String> stranded = new ArrayList<>();
        for (Map.Entry<String, BlockPos> gate : layout.gates().entrySet()) {
            BlockPos node = nearestNode(graph.keySet(), gate.getValue());
            if (node == null || !fromRunway.contains(node) || !toRunway.contains(node)) {
                stranded.add(gate.getKey());
            }
        }
        return stranded;
    }

    private static Set<BlockPos> reachableFrom(Map<BlockPos, List<BlockPos>> graph, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> stack = new ArrayDeque<>();
        stack.push(start);
        seen.add(start);
        while (!stack.isEmpty()) {
            for (BlockPos next : graph.getOrDefault(stack.pop(), List.of())) {
                if (seen.add(next)) stack.push(next);
            }
        }
        return seen;
    }

    /** Every arc flipped - used to ask "can this node get BACK to the runway". */
    private static Map<BlockPos, List<BlockPos>> reversed(Map<BlockPos, List<BlockPos>> graph) {
        Map<BlockPos, List<BlockPos>> flipped = new HashMap<>();
        for (BlockPos node : graph.keySet()) flipped.computeIfAbsent(node, k -> new ArrayList<>());
        for (Map.Entry<BlockPos, List<BlockPos>> entry : graph.entrySet()) {
            for (BlockPos to : entry.getValue()) {
                flipped.computeIfAbsent(to, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        return flipped;
    }

    @Nullable
    public static BlockPos nearestNode(Collection<BlockPos> nodes, BlockPos to) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos node : nodes) {
            double distance = horizontalDistance(node, to);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        return best;
    }

    public static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
