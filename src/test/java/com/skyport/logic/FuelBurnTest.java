package com.skyport.logic;

import com.skyport.SkyportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fuel curve, and specifically the property the whole feature rests on.
 *
 * Burning more fuel per second at higher speed is not, on its own, a
 * trade-off: if the rate rose exactly in step with speed, a journey would
 * cost the same fuel however fast it was flown and nobody would ever choose
 * to fly slowly. The decision only exists because the rate rises FASTER than
 * speed does. That is a one-character change away from being lost - swap the
 * exponent for 1, or "simplify" the power into a multiply - and nothing would
 * look broken afterwards. Hence a test that states it outright.
 */
class FuelBurnTest {

    @BeforeEach
    void defaults() {
        // These are plain statics refreshed from config at runtime, so a test
        // can set them directly. Reset each time, since tests below change
        // them and order is not guaranteed.
        SkyportConfig.fuelReferenceSpeed = 24;
        SkyportConfig.fuelSpeedExponent = 2.0;
    }

    @Test
    void referenceSpeedBurnsAtExactlyTheNominalRate() {
        assertEquals(1.0, FuelBurn.rateForSpeed(24), 1e-9,
                "the reference speed is what fuelEfficiency is quoted against - "
                        + "an aircraft nobody retuned must burn what it always did");
    }

    @Test
    void fasterCostsMoreForTheSameJourney() {
        double slow = FuelBurn.perBlock(12);
        double normal = FuelBurn.perBlock(24);
        double fast = FuelBurn.perBlock(48);

        assertTrue(slow < normal, "flying slower must cost less per block");
        assertTrue(normal < fast, "flying faster must cost more per block");
    }

    /** At the default exponent of 2, doubling speed doubles the cost of the
     *  trip. That is the number quoted in the README's range table. */
    @Test
    void doublingSpeedDoublesFuelForTheTrip() {
        assertEquals(2.0, FuelBurn.perBlock(48) / FuelBurn.perBlock(24), 1e-9);
    }

    /**
     * The degenerate setting, stated so nobody later "fixes" the default into
     * it: at exponent 1 the trade-off disappears entirely.
     */
    @Test
    void exponentOfOneRemovesTheTradeOffAltogether() {
        SkyportConfig.fuelSpeedExponent = 1.0;
        assertEquals(FuelBurn.perBlock(12), FuelBurn.perBlock(48), 1e-9,
                "at exponent 1 every speed costs the same per block, which is why "
                        + "the default is above it");
    }

    @Test
    void exponentOfZeroIsAFlatRate() {
        SkyportConfig.fuelSpeedExponent = 0.0;
        assertEquals(1.0, FuelBurn.rateForSpeed(4), 1e-9);
        assertEquals(1.0, FuelBurn.rateForSpeed(80), 1e-9);
    }

    @Test
    void idlingStillBurnsSomething() {
        assertEquals(FuelBurn.IDLE_RATE, FuelBurn.rateForSpeed(0), 1e-9,
                "an aircraft holding with its engine running is not free");
    }

    @Test
    void burnRateIsCapped() {
        SkyportConfig.fuelSpeedExponent = 4.0;
        assertEquals(FuelBurn.MAX_RATE, FuelBurn.rateForSpeed(1000), 1e-9);
    }

    /**
     * The best-range speed is a side effect of the idle floor rather than a
     * designed feature, and the README claims it exists - so pin it down. It
     * sits where the floor stops dominating; below that, range falls off
     * again because you are paying the floor while barely moving.
     */
    @Test
    void thereIsABestRangeSpeed() {
        double veryslow = FuelBurn.perBlock(4);
        double best = FuelBurn.perBlock(8);
        double fast = FuelBurn.perBlock(40);

        assertTrue(best < veryslow, "crawling wastes fuel - the floor is charged either way");
        assertTrue(best < fast, "and so does sprinting");
    }

    @Test
    void referenceSpeedShiftsTheWholeCurve() {
        SkyportConfig.fuelReferenceSpeed = 48;
        assertEquals(1.0, FuelBurn.rateForSpeed(48), 1e-9,
                "raising the reference makes a faster aircraft the new nominal");
        assertTrue(FuelBurn.rateForSpeed(24) < 1.0, "and what used to be nominal is now cheap");
    }
}
