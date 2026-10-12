package com.skyport.logic;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ground network, because one-way taxiways made it the piece
 * most able to break quietly.
 *
 * A wrong answer here doesn't throw or log - it produces an aircraft that
 * routes the long way round, or drives across the grass, or refuses a gate
 * that is perfectly reachable. All three look like a physics or steering
 * problem from inside the game, which is a long way from the actual cause.
 * The graph is also the one part of the flight system that is pure data, so
 * it is the one part that can be tested without a running game.
 */
class GroundNetworkTest {

    /**
     * A minimal but realistic field:
     *
     *   gate(30,0) --- junction(20,0) --- runway gate end(0,0) --- far end(100,0)
     *
     * Drawn the way the editor makes them: the runway first, then taxiway
     * segments in pairs.
     */
    private static AirportLayout field() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Test", Level.OVERWORLD);
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(new BlockPos(0, 64, 0), Waypoint.Type.RUNWAY, 0));
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(new BlockPos(100, 64, 0), Waypoint.Type.RUNWAY, 1));

        // Backbone: runway gate end <-> junction.
        addSegment(layout, new BlockPos(0, 64, 0), new BlockPos(20, 64, 0), Waypoint.Flow.BOTH);
        // Spur: junction <-> gate.
        addSegment(layout, new BlockPos(20, 64, 0), new BlockPos(30, 64, 0), Waypoint.Flow.BOTH);

        layout.gates().put("Gate A", new BlockPos(30, 64, 0));
        return layout;
    }

    private static void addSegment(AirportLayout layout, BlockPos from, BlockPos to, Waypoint.Flow flow) {
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);
        taxiway.add(new Waypoint(from, Waypoint.Type.TAXIWAY, taxiway.size(), flow));
        taxiway.add(new Waypoint(to, Waypoint.Type.TAXIWAY, taxiway.size(), flow));
    }

    /** Replaces the flow on the segment starting at index `pair * 2`. */
    private static void setFlow(AirportLayout layout, int pair, Waypoint.Flow flow) {
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);
        taxiway.set(pair * 2, taxiway.get(pair * 2).withFlow(flow));
        taxiway.set(pair * 2 + 1, taxiway.get(pair * 2 + 1).withFlow(flow));
    }

    @Test
    void routesFromRunwayToGate() {
        List<BlockPos> route = GroundNetwork.route(field(), new BlockPos(0, 64, 0), new BlockPos(30, 64, 0));
        assertFalse(route.isEmpty(), "a fully two-way field should route");
        assertEquals(new BlockPos(30, 64, 0), route.get(route.size() - 1), "route should end at the gate");
    }

    @Test
    void twoWayFieldStrandsNothing() {
        assertTrue(GroundNetwork.strandedGates(field()).isEmpty());
    }

    /**
     * The case the editor warning exists for: a spur an aircraft can taxi
     * into but never leave. Reaching a gate and being able to get back out
     * are different questions once segments are one-way, and only checking
     * the first would let a player strand an aircraft permanently.
     */
    @Test
    void oneWaySpurIntoGateStrandsIt() {
        AirportLayout layout = field();
        // Junction -> gate only. Nothing comes back out.
        setFlow(layout, 1, Waypoint.Flow.FORWARD);

        assertEquals(List.of("Gate A"), GroundNetwork.strandedGates(layout),
                "a gate that can be entered but not left is stranded");
    }

    @Test
    void oneWaySpurOutOfGateStrandsItToo() {
        AirportLayout layout = field();
        // Gate -> junction only. Nothing can get in.
        setFlow(layout, 1, Waypoint.Flow.REVERSE);

        assertEquals(List.of("Gate A"), GroundNetwork.strandedGates(layout),
                "a gate that can be left but not reached is stranded");
    }

    /**
     * A one-way segment is only a problem when it is the only way through.
     * A loop with an inbound and an outbound half is the whole point of the
     * feature and must not be flagged.
     */
    @Test
    void oneWayLoopStrandsNothing() {
        AirportLayout layout = field();
        // Outbound: gate end -> junction, one way.
        setFlow(layout, 0, Waypoint.Flow.FORWARD);
        // Return leg the other way round, via a second route.
        addSegment(layout, new BlockPos(20, 64, 0), new BlockPos(10, 64, 40), Waypoint.Flow.FORWARD);
        addSegment(layout, new BlockPos(10, 64, 40), new BlockPos(0, 64, 0), Waypoint.Flow.FORWARD);

        assertTrue(GroundNetwork.strandedGates(layout).isEmpty(),
                "a one-way loop with a return leg is exactly the intended use");
    }

    @Test
    void routeRespectsDirection() {
        AirportLayout layout = field();
        // Gate spur is exit-only, so there is no way in.
        setFlow(layout, 1, Waypoint.Flow.REVERSE);

        List<BlockPos> inbound = GroundNetwork.route(layout, new BlockPos(0, 64, 0), new BlockPos(30, 64, 0));
        assertTrue(inbound.isEmpty(), "routing into a one-way exit should fail rather than ignore the arrow");

        List<BlockPos> outbound = GroundNetwork.route(layout, new BlockPos(30, 64, 0), new BlockPos(0, 64, 0));
        assertFalse(outbound.isEmpty(), "the permitted direction should still route");
    }

    @Test
    void emptyLayoutRoutesNowhereWithoutThrowing() {
        AirportLayout empty = new AirportLayout(UUID.randomUUID(), "Empty", Level.OVERWORLD);
        assertTrue(GroundNetwork.route(empty, BlockPos.ZERO, new BlockPos(10, 0, 10)).isEmpty());
        assertTrue(GroundNetwork.strandedGates(empty).isEmpty());
    }
}
