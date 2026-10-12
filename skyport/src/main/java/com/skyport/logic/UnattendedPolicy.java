package com.skyport.logic;

/**
 * When an aircraft with nobody watching may keep going.
 *
 * The rule changed shape once it met the air. The allowance was a budget for
 * running unattended at all, spent wherever the aircraft happened to be - so
 * when it ran out mid-leg the aircraft stopped holding its chunks, Sable
 * stopped simulating it, and it froze in mid-air over whatever it was flying
 * across. Bounded, and useless: a frozen aeroplane halfway to somewhere is
 * worse than one that never left.
 *
 * So the bound moved to the only place an aircraft can wait harmlessly. An
 * airborne aircraft always finishes its leg and lands, however long that
 * takes. What the allowance governs is whether a parked one may start the
 * next leg - and a gate is exactly where an aircraft can sit indefinitely
 * costing nothing.
 *
 * The cost is the same shape as before: chunks stay loaded for the length of
 * a leg rather than for a fixed number of minutes. A leg ends.
 */
public final class UnattendedPolicy {

    private UnattendedPolicy() { }

    /**
     * May a parked aircraft start its next leg?
     *
     * A player standing nearby is not a separate case: being near refills the
     * allowance (see AutopilotBlockEntity#tickUnattendedAllowance), so an
     * attended aircraft always has time on the clock.
     *
     * @param keepParkedLoaded the server has opted into schedules that run
     *                         regardless, so the allowance does not apply
     * @param allowanceTicksLeft what is left of this aircraft's unattended time
     */
    public static boolean mayDepart(boolean keepParkedLoaded, int allowanceTicksLeft) {
        return keepParkedLoaded || allowanceTicksLeft > 0;
    }

    /**
     * May an airborne aircraft keep holding the world open around itself?
     *
     * Always. It is stated as a method rather than left implicit because the
     * answer used to be no, and the reason it is now yes is worth having
     * somewhere: there is nowhere safe to put an aeroplane down mid-leg.
     */
    public static boolean mayStayAirborne() {
        return true;
    }
}
