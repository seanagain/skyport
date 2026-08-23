package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for everything that gets written to disk or onto the wire.
 *
 * These exist because the failure mode is the worst kind there is: a layout
 * someone spent time drawing comes back wrong, or not at all, after a restart.
 * Nothing throws, nothing is logged, and the player is left thinking the
 * editor never saved.
 *
 * The backward-compatibility cases matter most. Both `Waypoint.flow` and the
 * callsign/destination on a parked aircraft were added to formats that already
 * existed in someone's world, and the code that copes with their absence was
 * written by hand and never exercised. A world saved before those fields
 * existed has to load as though it simply had the defaults.
 */
class SerializationTest {

    private static AirportLayout layout() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Heathrow", Level.OVERWORLD);
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(new BlockPos(0, 64, 0), Waypoint.Type.RUNWAY, 0));
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(new BlockPos(100, 64, 0), Waypoint.Type.RUNWAY, 1));
        layout.gates().put("Gate A", new BlockPos(30, 64, 10));
        layout.helipads().put("Pad 1", new BlockPos(-20, 64, 10));
        return layout;
    }

    @Test
    void waypointSurvivesNbtRoundTripWithItsDirection() {
        for (Waypoint.Flow flow : Waypoint.Flow.values()) {
            Waypoint original = new Waypoint(new BlockPos(1, 2, 3), Waypoint.Type.TAXIWAY, 4, flow);
            Waypoint loaded = Waypoint.load(original.save());
            assertEquals(original, loaded, "waypoint should survive NBT unchanged with flow " + flow);
        }
    }

    @Test
    void waypointSurvivesNetworkRoundTripWithItsDirection() {
        for (Waypoint.Flow flow : Waypoint.Flow.values()) {
            Waypoint original = new Waypoint(new BlockPos(-5, 70, 12), Waypoint.Type.TAXIWAY, 2, flow);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            original.write(buf);
            assertEquals(original, Waypoint.read(buf), "waypoint should survive the wire with flow " + flow);
            assertEquals(0, buf.readableBytes(), "reader should consume exactly what the writer wrote");
        }
    }

    /**
     * A waypoint saved before one-way taxiways existed has no "flow" key.
     * It has to come back two-way, which is how it behaved when it was
     * written - not crash, and not pick an arbitrary direction.
     */
    @Test
    void waypointWithoutFlowKeyLoadsAsTwoWay() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("x", 7);
        legacy.putInt("y", 64);
        legacy.putInt("z", -3);
        legacy.putString("type", "TAXIWAY");
        legacy.putInt("order", 1);

        Waypoint loaded = Waypoint.load(legacy);
        assertEquals(Waypoint.Flow.BOTH, loaded.flow());
        assertEquals(new BlockPos(7, 64, -3), loaded.pos());
    }

    @Test
    void layoutSurvivesRoundTrip() {
        AirportLayout original = layout();
        original.waypoints(Waypoint.Type.TAXIWAY)
                .add(new Waypoint(new BlockPos(0, 64, 0), Waypoint.Type.TAXIWAY, 0, Waypoint.Flow.FORWARD));
        original.waypoints(Waypoint.Type.TAXIWAY)
                .add(new Waypoint(new BlockPos(30, 64, 10), Waypoint.Type.TAXIWAY, 1, Waypoint.Flow.FORWARD));

        AirportLayout loaded = AirportLayout.load(original.save());

        assertEquals(original.id(), loaded.id());
        assertEquals(original.displayName(), loaded.displayName());
        assertEquals(original.gates(), loaded.gates());
        assertEquals(original.helipads(), loaded.helipads());
        assertEquals(original.waypoints(Waypoint.Type.RUNWAY), loaded.waypoints(Waypoint.Type.RUNWAY));
        assertEquals(Waypoint.Flow.FORWARD,
                loaded.waypoints(Waypoint.Type.TAXIWAY).get(0).flow(),
                "a one-way segment must still be one-way after a reload");
    }

    /**
     * Parked aircraft are the one thing in the registry that must survive a
     * restart - they stop ticking, so nothing re-reports them and a lost
     * record means an aircraft nobody can find again.
     */
    @Test
    void parkedAircraftSurvivesRoundTrip() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:overworld", new BlockPos(12, 64, -8),
                "Cargo 1", "Heathrow / Gate A", 1000L);

        AirportRegistry loaded = AirportRegistry.FACTORY.deserializer()
                .apply(registry.save(new CompoundTag(), null), null);

        assertEquals(1, loaded.parked().size());
        AirportRegistry.ParkedAircraft aircraft = loaded.parked().iterator().next();
        assertEquals(plane, aircraft.planeId());
        assertEquals(new BlockPos(12, 64, -8), aircraft.position());
        assertEquals("Cargo 1", aircraft.callsign());
        assertEquals("Heathrow / Gate A", aircraft.destination());
    }

    /**
     * A parked record written before those two fields existed. It must load,
     * with empty strings rather than an exception - an unreadable registry
     * would take every airport on the server with it.
     */
    @Test
    void parkedAircraftWithoutCallsignStillLoads() {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("planeId", UUID.randomUUID());
        entry.putString("dimension", "minecraft:overworld");
        entry.putInt("x", 1);
        entry.putInt("y", 64);
        entry.putInt("z", 2);

        ListTag parked = new ListTag();
        parked.add(entry);
        CompoundTag tag = new CompoundTag();
        tag.put("airports", new ListTag());
        tag.put("parked", parked);

        AirportRegistry loaded = AirportRegistry.FACTORY.deserializer().apply(tag, null);

        assertEquals(1, loaded.parked().size());
        AirportRegistry.ParkedAircraft aircraft = loaded.parked().iterator().next();
        assertEquals("", aircraft.callsign());
        assertEquals(new BlockPos(1, 64, 2), aircraft.position());
    }

    /** Parked aircraft show up as traffic, which is what stopped them
     *  vanishing off the tower while they sat at a gate. */
    @Test
    void parkedAircraftAppearInTheMergedTrafficView() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:overworld", new BlockPos(0, 64, 0),
                "Cargo 1", "Heathrow / Gate A", 1000L);

        assertTrue(registry.airborneTraffic().isEmpty(), "nothing is flying");
        assertEquals(1, registry.allTraffic(1000L).size(), "but something is still traffic");
        assertEquals("Cargo 1", registry.allTraffic(1000L).get(0).callsign());
    }

    /**
     * A parked aircraft that has stopped reporting is asleep, and that is
     * what the tower offers to wake. The distinction is a heartbeat, not a
     * stored flag, so it has to survive the aircraft simply going quiet -
     * which is exactly what happens when its chunks are released.
     */
    @Test
    void aParkedAircraftThatStopsReportingReadsAsAsleep() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:overworld", new BlockPos(0, 64, 0),
                "Cargo 1", "Heathrow / Gate A", 1000L);

        assertTrue(registry.isAwake(plane, 1000L), "it just reported in");
        assertEquals("WAITING", registry.allTraffic(1010L).get(0).state(),
                "still ticking a moment later");

        assertFalse(registry.isAwake(plane, 5000L), "long since gone quiet");
        assertEquals("ASLEEP", registry.allTraffic(5000L).get(0).state(),
                "an aircraft nothing is ticking is asleep, and can be woken");
    }

    /**
     * Nothing has reported since the world loaded, so everything parked is
     * asleep. The heartbeat is deliberately not persisted for this reason -
     * its absence is the correct answer after a restart.
     */
    @Test
    void everythingIsAsleepAfterAReload() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:overworld", new BlockPos(0, 64, 0),
                "Cargo 1", "Heathrow / Gate A", 1000L);

        AirportRegistry loaded = AirportRegistry.FACTORY.deserializer()
                .apply(registry.save(new CompoundTag(), null), null);

        assertFalse(loaded.isAwake(plane, 1000L));
        assertEquals("ASLEEP", loaded.allTraffic(1000L).get(0).state());
    }

    /** Waking one has to be able to find it again by id alone - the player
     *  clicking a row in the tower is nowhere near the aircraft. */
    @Test
    void aParkedAircraftCanBeFoundById() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:the_nether", new BlockPos(3, 70, 4),
                "Cargo 1", "Heathrow / Gate A", 1000L);

        assertTrue(registry.parkedById(plane).isPresent());
        assertEquals("minecraft:the_nether", registry.parkedById(plane).get().dimension());
        assertTrue(registry.parkedById(UUID.randomUUID()).isEmpty());
    }

    /** An aircraft that is flying must not also be listed as parked - it
     *  would show twice on the board, once moving and once not. */
    @Test
    void airborneAircraftIsNotAlsoListedAsParked() {
        AirportRegistry registry = new AirportRegistry();
        UUID plane = UUID.randomUUID();
        registry.reportParked(plane, "minecraft:overworld", new BlockPos(0, 64, 0),
                "Cargo 1", "Heathrow / Gate A", 1000L);
        registry.reportAirborne(new TrafficReport(plane, "Cargo 1", "CRUISE",
                new net.minecraft.world.phys.Vec3(0, 120, 0), "Gatwick / Gate B", true));

        assertEquals(1, registry.allTraffic(1000L).size(), "the same aircraft must appear once");
        assertEquals("CRUISE", registry.allTraffic(1000L).get(0).state(), "the live report wins");
    }
}
