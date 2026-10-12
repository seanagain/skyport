package com.skyport.logic;

import com.skyport.SkyportConfig;

/**
 * How fast an aircraft burns fuel at a given speed.
 *
 * Split out of the autopilot because it is pure arithmetic that decides
 * something a player will plan routes around, and because the property that
 * makes it worth having - that flying faster costs more fuel for the same
 * journey, not merely more per second - is not obvious from reading the
 * formula. It is easy to "fix" this into something that quietly removes the
 * trade-off, and a test says so where a comment only asks nicely.
 */
public final class FuelBurn {

    /** Burn for a craft that is powered but going nowhere. Not zero: an
     *  aircraft holding with its engine running is still burning. */
    public static final double IDLE_RATE = 0.1;
    /** Cap, so a steep exponent and a silly cruise speed cannot drain a
     *  whole hold between two checks. */
    public static final double MAX_RATE = 20.0;

    private FuelBurn() { }

    /**
     * Fuel drained per tick at this speed, relative to the reference speed.
     *
     * Rises with speed raised to the configured exponent. The exponent must
     * exceed 1 for cruise speed to be a real decision: at exactly 1 the rate
     * rises in step with speed, so fuel per block travelled is constant and
     * flying slowly buys nothing. At the default of 2 the fuel per block
     * scales with speed, which also happens to be roughly honest - drag rises
     * with the square of speed.
     */
    public static double rateForSpeed(double speed) {
        double reference = Math.max(1, SkyportConfig.fuelReferenceSpeed);
        double rate = Math.pow(Math.max(0, speed) / reference, SkyportConfig.fuelSpeedExponent);
        return Math.max(IDLE_RATE, Math.min(MAX_RATE, rate));
    }

    /**
     * Fuel to cover one block at this speed - the number that decides whether
     * flying slowly is worth anything.
     */
    public static double perBlock(double speed) {
        if (speed <= 0) return Double.POSITIVE_INFINITY;
        return rateForSpeed(speed) / speed;
    }
}
