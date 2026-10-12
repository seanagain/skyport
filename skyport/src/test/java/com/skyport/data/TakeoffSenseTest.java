package com.skyport.data;

import com.skyport.data.AirportLayout.TakeoffSense;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Towards the final leg" and "away from it", as the airport screen offers
 * them.
 *
 * The runway's two ends are only called gate end and far end because of the
 * order they were clicked in. What a person reasons about is whether a
 * departure leaves the way arrivals come in from or the way they are flying,
 * so that is what is measured - against where the final leg really points -
 * and what is tested here.
 *
 * Drawn the way the README says to: runway gate end first, final leg from the
 * pattern side in to the threshold. Landing aircraft therefore fly from the
 * far end toward the gate end, and a departure rolling gate end to far end
 * leaves head-on to them - toward the final leg.
 */
class TakeoffSenseTest {

    private static final BlockPos GATE_END = new BlockPos(0, 64, 0);
    private static final BlockPos FAR_END = new BlockPos(100, 64, 0);

    private static AirportLayout runwayOnly() {
        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "Heathrow", Level.OVERWORLD);
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(GATE_END, Waypoint.Type.RUNWAY, 0));
        layout.waypoints(Waypoint.Type.RUNWAY).add(new Waypoint(FAR_END, Waypoint.Type.RUNWAY, 1));
        return layout;
    }

    private static void finalLeg(AirportLayout layout, BlockPos... points) {
        for (int i = 0; i < points.length; i++) {
            layout.waypoints(Waypoint.Type.FINAL_LEG).add(new Waypoint(points[i], Waypoint.Type.FINAL_LEG, i));
        }
    }

    /** Pattern side out past the far end, threshold at the far end: the
     *  conventional drawing, and the one every existing airport has. */
    private static AirportLayout conventional() {
        AirportLayout layout = runwayOnly();
        finalLeg(layout, new BlockPos(300, 64, 0), FAR_END);
        return layout;
    }

    @Test
    void theDefaultRollIsTowardsTheFinalLegOnAConventionalAirport() {
        assertEquals(TakeoffSense.TOWARDS_FINAL_LEG, conventional().takeoffSense());
    }

    @Test
    void reversedIsAwayFromIt() {
        AirportLayout layout = conventional();
        layout.setReversedTakeoff(true);
        assertEquals(TakeoffSense.AWAY_FROM_FINAL_LEG, layout.takeoffSense());
    }

    /** The whole reason to measure it rather than trust the draw order: the
     *  same words have to mean the same thing however the runway was clicked. */
    @Test
    void itFollowsTheFinalLegNotTheOrderTheRunwayWasDrawnIn() {
        AirportLayout layout = runwayOnly();
        // Final leg coming in from the gate-end side instead.
        finalLeg(layout, new BlockPos(-200, 64, 0), GATE_END);
        assertEquals(TakeoffSense.AWAY_FROM_FINAL_LEG, layout.takeoffSense(),
                "rolling gate end to far end now runs the way landing aircraft fly");
    }

    @Test
    void choosingAwayReversesAConventionalAirport() {
        AirportLayout layout = conventional();
        layout.setTakeoffSense(TakeoffSense.AWAY_FROM_FINAL_LEG);

        assertEquals(TakeoffSense.AWAY_FROM_FINAL_LEG, layout.takeoffSense());
        assertEquals(List.of(FAR_END, GATE_END), layout.takeoffRoll());
    }

    @Test
    void choosingTowardsPutsItBack() {
        AirportLayout layout = conventional();
        layout.setTakeoffSense(TakeoffSense.AWAY_FROM_FINAL_LEG);
        layout.setTakeoffSense(TakeoffSense.TOWARDS_FINAL_LEG);

        assertEquals(TakeoffSense.TOWARDS_FINAL_LEG, layout.takeoffSense());
        assertEquals(List.of(GATE_END, FAR_END), layout.takeoffRoll());
    }

    /** Asking for what it already is must not flip it - a setter that toggled
     *  would make choosing the current option change the airport. */
    @Test
    void choosingWhatAlreadyHoldsChangesNothing() {
        AirportLayout layout = conventional();
        layout.setTakeoffSense(TakeoffSense.TOWARDS_FINAL_LEG);
        layout.setTakeoffSense(TakeoffSense.TOWARDS_FINAL_LEG);

        assertEquals(List.of(GATE_END, FAR_END), layout.takeoffRoll());
    }

    /** On the unconventional drawing "away" is the roll it always had, so
     *  choosing it must not introduce a backtrack. */
    @Test
    void choosingAwayOnTheOtherDrawingNeedsNoReversal() {
        AirportLayout layout = runwayOnly();
        finalLeg(layout, new BlockPos(-200, 64, 0), GATE_END);
        layout.setTakeoffSense(TakeoffSense.AWAY_FROM_FINAL_LEG);

        assertEquals(List.of(GATE_END, FAR_END), layout.takeoffRoll());
    }

    @Test
    void noFinalLegMeansNothingToMeasureAgainst() {
        assertEquals(TakeoffSense.UNDEFINED, runwayOnly().takeoffSense());
    }

    /** A leg drawn across the runway is not an approach to it. */
    @Test
    void aFinalLegAcrossTheRunwayIsUndefined() {
        AirportLayout layout = runwayOnly();
        finalLeg(layout, new BlockPos(50, 64, 200), new BlockPos(50, 64, 0));
        assertEquals(TakeoffSense.UNDEFINED, layout.takeoffSense());
    }

    /** A little crooked is still an approach. */
    @Test
    void aSlightlyCrookedFinalLegStillCounts() {
        AirportLayout layout = runwayOnly();
        finalLeg(layout, new BlockPos(300, 64, 60), FAR_END);
        assertEquals(TakeoffSense.TOWARDS_FINAL_LEG, layout.takeoffSense());
    }

    /** Asking for something that cannot be answered leaves the airport as it
     *  was, rather than guessing. */
    @Test
    void anUnanswerableRequestChangesNothing() {
        AirportLayout layout = runwayOnly();
        layout.setTakeoffSense(TakeoffSense.AWAY_FROM_FINAL_LEG);
        assertEquals(false, layout.reversedTakeoff());

        AirportLayout withLeg = conventional();
        withLeg.setTakeoffSense(TakeoffSense.UNDEFINED);
        assertEquals(false, withLeg.reversedTakeoff());
    }

    /** A dedicated departure runway already says which way it goes. */
    @Test
    void aDedicatedDepartureRunwayIsNotOverriddenByTheChoice() {
        AirportLayout layout = conventional();
        layout.setRunwayPoint(1, 0, new BlockPos(0, 64, 50));
        layout.setRunwayPoint(1, 1, new BlockPos(100, 64, 50));
        layout.setTakeoffSense(TakeoffSense.AWAY_FROM_FINAL_LEG);

        assertEquals(false, layout.reversedTakeoff());
    }

    /** With more than two points the aircraft is flying the last segment when
     *  it reaches the runway, so that is the direction that counts. */
    @Test
    void aBentFinalLegIsJudgedByItsLastSegment() {
        AirportLayout layout = runwayOnly();
        finalLeg(layout, new BlockPos(0, 64, 300), new BlockPos(300, 64, 0), FAR_END);
        assertEquals(TakeoffSense.TOWARDS_FINAL_LEG, layout.takeoffSense());
    }
}
