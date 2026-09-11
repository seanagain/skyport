package com.skyport.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VOR stops in a schedule, and VOR beacons in the registry.
 *
 * Two kinds of thing are pinned. The formats, because a VOR stop was added to
 * a schedule format that already exists in people's worlds, and an airport
 * stop saved before VORs existed has to load exactly as it did. And the
 * routing rule - which airport a leg starting at some stop is really flying
 * to - because every VOR decision in flight rests on it, and getting it wrong
 * looks like an aircraft that takes off and heads for nowhere.
 */
class VorScheduleTest {

    private static final UUID HEATHROW = UUID.randomUUID();
    private static final UUID GATWICK = UUID.randomUUID();

    private static ScheduleEntry airport(UUID id) {
        return new ScheduleEntry(id, "Gate A", ScheduleEntry.WaitCondition.TIMER, 10);
    }

    private static ScheduleEntry vor() {
        return ScheduleEntry.vor(UUID.randomUUID());
    }

    private static FlightSchedule schedule(boolean loop, ScheduleEntry... stops) {
        FlightSchedule schedule = new FlightSchedule();
        for (ScheduleEntry stop : stops) schedule.entries().add(stop);
        schedule.setLoop(loop);
        return schedule;
    }

    // ---- formats ----

    @Test
    void aVorStopSurvivesSaveAndLoad() {
        ScheduleEntry original = vor();
        ScheduleEntry loaded = ScheduleEntry.load(original.save());
        assertTrue(loaded.isVor());
        assertEquals(original, loaded);
    }

    @Test
    void aVorStopSurvivesTheWire() {
        ScheduleEntry original = vor();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        assertEquals(original, ScheduleEntry.read(buf));
        assertEquals(0, buf.readableBytes(), "reader should consume exactly what the writer wrote");
    }

    @Test
    void anAirportStopStillSurvivesTheWire() {
        ScheduleEntry original = new ScheduleEntry(HEATHROW, "Gate B", ScheduleEntry.WaitCondition.CARGO_LOADED, 30);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        assertEquals(original, ScheduleEntry.read(buf));
        assertEquals(0, buf.readableBytes());
    }

    /** Written the way every schedule stop was before VORs existed. */
    @Test
    void anAirportStopSavedBeforeVorsExistedLoadsUnchanged() {
        CompoundTag old = new CompoundTag();
        old.putUUID("airportId", HEATHROW);
        old.putString("gateName", "Gate A");
        old.putString("condition", "PLAYER");
        old.putInt("waitSeconds", 0);

        ScheduleEntry loaded = ScheduleEntry.load(old);

        assertFalse(loaded.isVor());
        assertEquals(HEATHROW, loaded.airportId());
        assertEquals("Gate A", loaded.gateName());
        assertEquals(ScheduleEntry.WaitCondition.PLAYER, loaded.condition());
    }

    @Test
    void aStopNamesExactlyOnePlace() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleEntry(null, "", ScheduleEntry.WaitCondition.TIMER, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleEntry(HEATHROW, "Gate A", ScheduleEntry.WaitCondition.TIMER, 0, UUID.randomUUID()));
    }

    @Test
    void aWholeScheduleWithVorsSurvivesSaveAndLoad() {
        FlightSchedule original = schedule(true, airport(HEATHROW), vor(), airport(GATWICK));
        FlightSchedule loaded = FlightSchedule.load(original.save());
        assertEquals(original.entries(), loaded.entries());
        assertTrue(loaded.loop());
    }

    // ---- routing ----

    @Test
    void aLegStartingAtAVorFliesToTheAirportAfterIt() {
        FlightSchedule s = schedule(false, airport(HEATHROW), vor(), airport(GATWICK));
        assertEquals(2, s.destinationIndexFrom(1));
    }

    @Test
    void severalVorsInARowAreAllFlownOver() {
        FlightSchedule s = schedule(false, vor(), vor(), airport(GATWICK));
        assertEquals(2, s.destinationIndexFrom(0));
    }

    @Test
    void aLegStartingAtAnAirportFliesToThatAirport() {
        FlightSchedule s = schedule(false, airport(HEATHROW), vor(), airport(GATWICK));
        assertEquals(0, s.destinationIndexFrom(0));
        assertEquals(2, s.destinationIndexFrom(2));
    }

    /** VORs after the last airport of a schedule that does not loop lead
     *  nowhere - departing toward them would leave the aircraft without a
     *  destination the moment it passed the last one. */
    @Test
    void trailingVorsWithoutALoopLeadNowhere() {
        FlightSchedule s = schedule(false, airport(HEATHROW), vor());
        assertEquals(-1, s.destinationIndexFrom(1));
    }

    @Test
    void trailingVorsInALoopLeadBackToTheStart() {
        FlightSchedule s = schedule(true, airport(HEATHROW), vor());
        assertEquals(0, s.destinationIndexFrom(1));
    }

    /** And must not go round forever looking for one. */
    @Test
    void aLoopOfNothingButVorsHasNowhereToLand() {
        FlightSchedule s = schedule(true, vor(), vor());
        assertFalse(s.hasAirportStop());
        assertEquals(-1, s.destinationIndexFrom(0));
        assertEquals(-1, s.destinationIndexFrom(1));
    }

    @Test
    void anIndexOffEitherEndHasNoDestination() {
        FlightSchedule s = schedule(true, airport(HEATHROW));
        assertEquals(-1, s.destinationIndexFrom(5));
        assertEquals(-1, s.destinationIndexFrom(-1));
    }

    // ---- registry ----

    @Test
    void vorsSurviveTheRegistrySave() {
        AirportRegistry registry = new AirportRegistry();
        VorBeacon north = new VorBeacon(UUID.randomUUID(), "North", "minecraft:overworld", new BlockPos(120, 70, -340));
        registry.putVor(north);

        AirportRegistry loaded = AirportRegistry.FACTORY.deserializer()
                .apply(registry.save(new CompoundTag(), null), null);

        assertEquals(north, loaded.vorById(north.id()).orElseThrow());
    }

    /** A registry written before VORs existed must still load - an unreadable
     *  registry would take every airport on the server with it. */
    @Test
    void aRegistrySavedBeforeVorsExistedLoadsWithNone() {
        CompoundTag tag = new CompoundTag();
        tag.put("airports", new ListTag());
        tag.put("parked", new ListTag());

        AirportRegistry loaded = AirportRegistry.FACTORY.deserializer().apply(tag, null);

        assertTrue(loaded.allVors().isEmpty());
    }

    @Test
    void removingAVorForgetsIt() {
        AirportRegistry registry = new AirportRegistry();
        VorBeacon north = new VorBeacon(UUID.randomUUID(), "North", "minecraft:overworld", BlockPos.ZERO);
        registry.putVor(north);
        registry.removeVor(north.id());
        assertTrue(registry.vorById(north.id()).isEmpty());
    }

    @Test
    void aVorBeaconSurvivesTheWire() {
        VorBeacon north = new VorBeacon(UUID.randomUUID(), "North", "minecraft:the_nether", new BlockPos(-5, 64, 9));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        north.write(buf);
        assertEquals(north, VorBeacon.read(buf));
        assertEquals(0, buf.readableBytes());
    }

    // ---- names ----

    @Test
    void aBlankNameKeepsTheOldOne() {
        assertEquals("North", VorBeacon.cleanName("   ", "North"));
        assertEquals("North", VorBeacon.cleanName(null, "North"));
    }

    /** The section sign starts a formatting code; without it the code letter
     *  is just a letter. Control characters go entirely. */
    @Test
    void formattingCodesAndControlCharactersAreDisarmed() {
        assertEquals("cNorth", VorBeacon.cleanName("§cNor\nth", "x"));
    }

    @Test
    void longNamesAreCutToFit() {
        assertEquals(VorBeacon.MAX_NAME_LENGTH, VorBeacon.cleanName("x".repeat(100), "y").length());
    }
}
