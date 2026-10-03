package com.skyport.data;

import com.skyport.SkyportConfig.PowerRequirement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each survival power mode actually asks the aircraft for.
 *
 * Every place that charges an aircraft for flying reads these two questions
 * rather than comparing against the mode itself, so this is where "BOTH means
 * both" is actually settled - and where a mode added later has to say which
 * halves it wants rather than quietly meaning neither.
 *
 * EITHER answers yes to both here, which is not what an aircraft under it
 * actually pays - it is resolved against that craft's own choice first. See
 * PowerChoiceTest for what that resolve has to guarantee.
 */
class PowerRequirementTest {

    @Test
    void noneAsksForNothing() {
        assertFalse(PowerRequirement.NONE.needsRotation());
        assertFalse(PowerRequirement.NONE.needsFuel());
    }

    @Test
    void rotationAsksForRotationOnly() {
        assertTrue(PowerRequirement.ROTATION.needsRotation());
        assertFalse(PowerRequirement.ROTATION.needsFuel(), "rotation mode must not touch the bunker");
    }

    @Test
    void fuelAsksForFuelOnly() {
        assertFalse(PowerRequirement.FUEL.needsRotation(), "fuel mode must not demand a powertrain");
        assertTrue(PowerRequirement.FUEL.needsFuel());
    }

    @Test
    void bothAsksForBoth() {
        assertTrue(PowerRequirement.BOTH.needsRotation());
        assertTrue(PowerRequirement.BOTH.needsFuel());
    }

    /** A mode that answers no to both would be a silent second NONE - free
     *  flight under a name that sounds like it costs something. */
    @Test
    void everyModeExceptNoneCostsSomething() {
        for (PowerRequirement mode : PowerRequirement.values()) {
            if (mode == PowerRequirement.NONE) continue;
            assertTrue(mode.needsRotation() || mode.needsFuel(),
                    mode + " charges the aircraft nothing, which is what NONE is for");
        }
    }
}
