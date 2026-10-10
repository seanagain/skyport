package com.skyport.data;

import com.skyport.blockentity.AutopilotBlockEntity.FlightState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which flight states have booked something at the airport they are heading
 * for, and therefore owe it back if the aircraft is sent somewhere else.
 *
 * A computer can rewrite a schedule at any moment, including the moment an
 * aircraft is turning onto final. Until this was handled, doing so left the
 * runway slot or the helipad reserved in the name of an aircraft that had
 * gone, and everything behind it queued for a ghost - a failure nobody would
 * connect to a line of Lua typed ten minutes earlier at another airport.
 *
 * The two questions are asked of the enum rather than worked out at the call
 * site so they are stated once and can be checked without a world, a craft
 * or a running server. The exhaustive loops are the point: a state added
 * later shows up here as a decision someone has to make rather than quietly
 * defaulting to "books nothing", which is the answer that leaks.
 */
class RerouteStateTest {

    @Test
    void holdingAndApproachingHaveBookedSomething() {
        assertTrue(FlightState.HOLDING.holdsDestinationBooking(), "a holding pattern is a queue for a runway");
        assertTrue(FlightState.APPROACH.holdsDestinationBooking(), "cleared to land means the runway is yours");
        assertTrue(FlightState.HOVERING.holdsDestinationBooking(), "hovering is waiting for a pad to clear");
        assertTrue(FlightState.VERTICAL_DESCENT.holdsDestinationBooking(), "descending onto a claimed pad");
    }

    /** Clearance is asked for on arrival, so nothing is held before then and
     *  a reroute out of the cruise costs nobody anything. */
    @Test
    void cruisingAndClimbingHaveBookedNothing() {
        assertFalse(FlightState.CRUISE.holdsDestinationBooking());
        assertFalse(FlightState.CLIMB.holdsDestinationBooking());
        assertFalse(FlightState.VERTICAL_CLIMB.holdsDestinationBooking());
    }

    /** Whatever is held on the ground belongs to the airport the aircraft is
     *  standing at, which a change of destination does not abandon. */
    @Test
    void groundStatesHoldNothingAtTheDestination() {
        assertFalse(FlightState.IDLE.holdsDestinationBooking());
        assertFalse(FlightState.PUSHBACK.holdsDestinationBooking());
        assertFalse(FlightState.TAXI_OUT.holdsDestinationBooking());
        assertFalse(FlightState.TAKEOFF_ROLL.holdsDestinationBooking());
        assertFalse(FlightState.TAXI_IN.holdsDestinationBooking());
        assertFalse(FlightState.WAITING.holdsDestinationBooking());
    }

    @Test
    void airborneMeansOffTheGround() {
        assertTrue(FlightState.CLIMB.airborne());
        assertTrue(FlightState.CRUISE.airborne());
        assertTrue(FlightState.HOLDING.airborne());
        assertTrue(FlightState.APPROACH.airborne());
        assertTrue(FlightState.VERTICAL_CLIMB.airborne());
        assertTrue(FlightState.VERTICAL_DESCENT.airborne());
        assertTrue(FlightState.HOVERING.airborne());
    }

    /** Taxiing is the autopilot very much running, and still on the ground:
     *  what this distinguishes is turning around in the air versus simply
     *  departing somewhere else on reaching the runway. */
    @Test
    void taxiingAndIdlingAreNotAirborne() {
        assertFalse(FlightState.IDLE.airborne());
        assertFalse(FlightState.PUSHBACK.airborne());
        assertFalse(FlightState.TAXI_OUT.airborne());
        assertFalse(FlightState.TAKEOFF_ROLL.airborne());
        assertFalse(FlightState.TAXI_IN.airborne());
        assertFalse(FlightState.WAITING.airborne());
    }

    /** Anything that has booked a slot at the airport it is flying to must
     *  be in the air to have done so - a state that claims otherwise is a
     *  contradiction, and whichever of the two answers is wrong, the reroute
     *  built on them is wrong too. */
    @Test
    void everythingHoldingABookingIsAirborne() {
        for (FlightState state : FlightState.values()) {
            if (state.holdsDestinationBooking()) {
                assertTrue(state.airborne(), state + " books a landing slot without being in the air");
            }
        }
    }

    /** IDLE is the one state where a schedule change is not a reroute at
     *  all, and the whole of setSchedule's old behaviour still applies. */
    @Test
    void idleIsNeitherAirborneNorBooked() {
        assertFalse(FlightState.IDLE.airborne());
        assertFalse(FlightState.IDLE.holdsDestinationBooking());
    }
}
