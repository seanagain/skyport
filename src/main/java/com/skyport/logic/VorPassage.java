package com.skyport.logic;

/**
 * Whether an aircraft has flown over a VOR yet.
 *
 * "Over" is deliberately loose - a VOR is a point to route by, not a spot to
 * hit - and there are three ways to have passed one, because the obvious
 * single test fails in two different directions.
 *
 * Close enough is the ordinary case. But an aircraft at cruise speed turns
 * wide, and one arriving at an angle can swing past just outside any radius
 * tight enough to mean anything; the controller then turns it back round to
 * try again, indefinitely. So a VOR also counts as passed once the aircraft
 * has come near and is opening the distance again, and once it has come near
 * and stopped getting any nearer - which is what circling looks like from
 * the outside.
 *
 * Free of Minecraft types so it can be tested without a world.
 */
public final class VorPassage {

    /** Within this, horizontally, the aircraft is over the VOR. */
    public static final double OVERHEAD_BLOCKS = 16.0;

    /** The near-miss rules only apply inside this. Further out, a growing
     *  distance is an aircraft still turning toward the VOR, not leaving it. */
    public static final double CAPTURE_BLOCKS = 64.0;

    /** How far back out a near miss has to open before it counts - enough
     *  that ordinary wobble in the steering does not trip it. */
    public static final double OVERSHOOT_BLOCKS = 8.0;

    /** How long near and not closing counts as circling. */
    public static final long STALL_TICKS = 200;

    /** Closing by less than this is not closing. Otherwise an orbit drifting
     *  a fraction of a block inward each lap would reset the clock forever. */
    public static final double PROGRESS_BLOCKS = 0.5;

    private double closest = Double.MAX_VALUE;
    private long closerAt;

    /** Start measuring a new pass. */
    public void reset() {
        closest = Double.MAX_VALUE;
        closerAt = 0;
    }

    /**
     * @param distance horizontal distance to the VOR now
     * @param now      game time, not a count of calls - the flight logic runs
     *                 off the physics tick, which fires more than twenty times
     *                 a second
     * @return true once the VOR has been passed
     */
    public boolean update(double distance, long now) {
        if (closest == Double.MAX_VALUE || distance < closest - PROGRESS_BLOCKS) {
            closest = distance;
            closerAt = now;
        }
        if (distance <= OVERHEAD_BLOCKS) return true;
        if (closest > CAPTURE_BLOCKS) return false;
        return distance > closest + OVERSHOOT_BLOCKS || now - closerAt >= STALL_TICKS;
    }
}
