package com.skyport.logic;

/**
 * Decides which redstone neighbour updates are worth acting on.
 *
 * A block gets neighbourChanged for any change to any neighbour, not only for
 * one that altered its redstone signal - and some blocks call
 * updateNeighborsAt every single tick. The autopilot took each of those at
 * face value and re-ran its engage logic, which checks the whole route for
 * terrain and loads chunks to do it. On a server where an Autopilot sat next
 * to one of those blocks, that was the main thread gone: a reported hang of
 * two minutes at a stretch, with nothing in chat to say why, because a
 * redstone engage has no player to send its refusal to.
 *
 * Two rules, and the second is the one that matters:
 *
 * The signal has to have CHANGED for an update to mean anything. That alone
 * settles a neighbour that merely rattles - the signal it reports is the same
 * one as last tick, so there is nothing to do.
 *
 * And an engage may only be attempted once every so often however it was
 * asked for. That covers what the first rule cannot: a signal genuinely
 * toggling, and a lever left on above a plane that cannot engage - out of
 * fuel, off the taxiway, no route - which would otherwise retry, and rescan,
 * every tick for as long as it stayed there.
 *
 * Retrying at all is deliberate. A lever left on means "this plane should be
 * flying", so whatever refused it a moment ago is worth asking about again in
 * case it has been fixed. Just not twenty times a second.
 *
 * Nothing here is persisted. After a reload the first update looks like a
 * change, which is exactly right: a lever left on should engage the plane it
 * is sitting over.
 */
public final class RedstoneGate {

    /**
     * How long a failed engage waits before it is worth trying again.
     *
     * Longer than the ten to twenty ticks the bug report suggested, because
     * the thing being bounded is not a stutter but a standing condition - an
     * aircraft that cannot engage now usually cannot engage in a second
     * either, and each attempt is a route check that may generate terrain. At
     * five seconds a player who fixes whatever was wrong sees it take off
     * without wondering whether the lever works, and a misconfigured one
     * costs a fraction of a percent of what it used to.
     */
    public static final long ENGAGE_RETRY_TICKS = 100;

    private boolean lastSignal;
    private boolean everSeen;
    private boolean everAttempted;
    private long lastEngageAttempt;

    /**
     * Record the signal this update reports, and say whether it differs from
     * the one last recorded.
     *
     * The first call is always a change - nothing has been acted on yet.
     */
    public boolean signalChanged(boolean powered) {
        boolean changed = !everSeen || powered != lastSignal;
        lastSignal = powered;
        everSeen = true;
        return changed;
    }

    /**
     * Has enough time passed since the last engage attempt to make another?
     *
     * The flag rather than a sentinel timestamp: Long.MIN_VALUE as "never"
     * reads naturally and then overflows the subtraction below on a fresh
     * world, where game time is near zero - which blocked the FIRST engage,
     * the one attempt that should never be refused.
     */
    public boolean mayEngage(long gameTime) {
        return !everAttempted || gameTime - lastEngageAttempt >= ENGAGE_RETRY_TICKS;
    }

    /** Note that an engage was just attempted, successful or not. */
    public void engageAttempted(long gameTime) {
        lastEngageAttempt = gameTime;
        everAttempted = true;
    }
}
