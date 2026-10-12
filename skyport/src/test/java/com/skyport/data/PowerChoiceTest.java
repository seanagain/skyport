package com.skyport.data;

import com.skyport.SkyportConfig.PowerRequirement;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EITHER: a server that will take rotation or fuel and lets each aircraft
 * pick which.
 *
 * Two things have to hold for that to be worth having. The mode has to resolve
 * to a real requirement before anything charges the aircraft - an unresolved
 * EITHER reaching the flight code is the bug that would hand out free flight -
 * and the choice has to survive a restart, because a preference that quietly
 * reverts to rotation would strand a coal-burning fleet at its gates with no
 * sign of why.
 */
class PowerChoiceTest {

    @Test
    void onlyEitherLeavesTheChoiceToTheAircraft() {
        assertTrue(PowerRequirement.EITHER.isChoice());
        for (PowerRequirement mode : PowerRequirement.values()) {
            if (mode == PowerRequirement.EITHER) continue;
            assertFalse(mode.isChoice(), mode + " is the server's decision, not the aircraft's");
        }
    }

    @Test
    void eitherResolvesToWhicheverHalfTheAircraftPicked() {
        assertEquals(PowerRequirement.FUEL, PowerRequirement.EITHER.resolved(true));
        assertEquals(PowerRequirement.ROTATION, PowerRequirement.EITHER.resolved(false));
    }

    /** The aircraft only gets a say where the server offered one: a server set
     *  to BOTH is not negotiable from a screen. */
    @Test
    void everyOtherModeIgnoresTheAircraftPreference() {
        for (PowerRequirement mode : PowerRequirement.values()) {
            if (mode == PowerRequirement.EITHER) continue;
            assertEquals(mode, mode.resolved(true), mode + " should not be swayed by a preference");
            assertEquals(mode, mode.resolved(false), mode + " should not be swayed by a preference");
        }
    }

    /** Whatever an aircraft picked, what it is held to is a concrete answer.
     *  An EITHER surviving a resolve is the free-flight bug. */
    @Test
    void resolvingNeverLeavesEitherBehind() {
        for (boolean prefersFuel : new boolean[] { true, false }) {
            PowerRequirement resolved = PowerRequirement.EITHER.resolved(prefersFuel);
            assertNotSame(PowerRequirement.EITHER, resolved);
            assertTrue(resolved.needsRotation() || resolved.needsFuel(),
                    "a resolved EITHER has to charge the aircraft something");
        }
    }

    /** Both halves, so a resolve that was forgotten somewhere refuses the
     *  aircraft rather than flying it free. See PowerRequirement#needsRotation. */
    @Test
    void anUnresolvedEitherAsksForBothRatherThanNothing() {
        assertTrue(PowerRequirement.EITHER.needsRotation());
        assertTrue(PowerRequirement.EITHER.needsFuel());
    }

    @Test
    void anAircraftDefaultsToPayingInRotation() {
        assertFalse(new FlightSchedule().prefersFuel(),
                "a craft built on Create is likelier to have a powertrain than a coal bunker");
    }

    @Test
    void theChoiceSurvivesNbt() {
        FlightSchedule original = new FlightSchedule();
        original.setPrefersFuel(true);
        assertTrue(FlightSchedule.load(original.save()).prefersFuel(),
                "a fuel-burning aircraft must not come back from a restart wanting rotation");
    }

    @Test
    void theChoiceSurvivesTheWire() {
        FlightSchedule original = new FlightSchedule();
        original.setPrefersFuel(true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        FlightSchedule loaded = FlightSchedule.read(buf);
        assertTrue(loaded.prefersFuel());
        assertEquals(0, buf.readableBytes(), "reader should consume exactly what the writer wrote");
    }

    /** A schedule written before this field existed has no tag for it, and
     *  has to load as rotation rather than throwing or guessing. */
    @Test
    void aScheduleSavedBeforeTheChoiceExistedLoadsAsRotation() {
        CompoundTag old = new FlightSchedule().save();
        old.remove("prefersFuel");
        assertFalse(FlightSchedule.load(old).prefersFuel());
    }
}
