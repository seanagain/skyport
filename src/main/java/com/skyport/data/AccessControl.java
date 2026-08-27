package com.skyport.data;

import com.skyport.SkyportConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a player may use or break one of the lockable blocks.
 *
 * Every mutating packet goes through here, not just the right-click path,
 * and that is the whole point. A lock enforced in the screen protects
 * nothing: the screen runs on the player's own machine, and the five
 * packets that change state - engage, disengage, save schedule, save
 * layout, and the layout editor behind it - used to be accepted from anyone
 * for any block anywhere in the world, whether or not they had ever opened
 * it. The screen is a front door; this is the lock.
 *
 * Deliberately NOT a proximity check. "Is the player near the block" is the
 * obvious guard and it is the wrong one twice over: it does not stop the
 * griefer standing next to your aeroplane, which is the actual threat, and
 * an autopilot on an assembled craft has coordinates inside its own
 * sub-level, so a distance test against them would refuse legitimate use
 * for reasons that would take a bug report to untangle. Ownership answers
 * the real question directly.
 */
public final class AccessControl {

    private AccessControl() { }

    public enum Result {
        /** Go ahead. */
        ALLOWED,
        /** Locked, but a passcode exists, so there is something to type. */
        NEEDS_PASSCODE,
        /** Locked, and no passcode set - only the owner gets in. */
        DENIED
    }

    /**
     * Players who have proved they know a code, remembered until the server
     * stops.
     *
     * Keyed by the code itself (by its stored hash) rather than by the
     * block's position, for two reasons. An autopilot's position moves with
     * its craft and changes on reassembly, so a position key would quietly
     * forget a grant every time an aircraft was rebuilt. And a base whose
     * station, tower and aircraft all share one code should ask once, not
     * once per block - which is what a player expects a code to mean.
     *
     * Not persisted: a restart asking for the code again is the safe
     * failure, and writing down who may open what is a second thing to keep
     * correct for no gain.
     */
    private record Grant(UUID player, UUID owner, String passcode) { }

    private static final Set<Grant> GRANTS = new HashSet<>();

    /** Ops and single-player hosts. Level 2 is the command-block tier, which
     *  is the line vanilla itself draws for "may edit other people's
     *  things". */
    private static final int OPERATOR_LEVEL = 2;

    public static Result check(ServerPlayer player, BlockLock lock) {
        if (!SkyportConfig.protectBlocks) return Result.ALLOWED;
        // Never claimed, so never locked - see BlockLock on why existing
        // blocks stay open.
        if (!lock.isOwned()) return Result.ALLOWED;
        if (lock.isOwner(player.getUUID())) return Result.ALLOWED;
        if (player.hasPermissions(OPERATOR_LEVEL)) return Result.ALLOWED;
        if (lock.hasPasscode()) {
            return GRANTS.contains(new Grant(player.getUUID(), lock.owner(), lock.passcodeFingerprint()))
                    ? Result.ALLOWED
                    : Result.NEEDS_PASSCODE;
        }
        return Result.DENIED;
    }

    /** Convenience for the many call sites that only care yes/no. */
    public static boolean allows(ServerPlayer player, BlockLock lock) {
        return check(player, lock) == Result.ALLOWED;
    }

    /**
     * Try a passcode. Remembers the player on success so they are not asked
     * again for anything else the same owner locked with the same code.
     */
    public static boolean submit(ServerPlayer player, BlockLock lock, String attempt) {
        if (!lock.passcodeMatches(attempt)) return false;
        GRANTS.add(new Grant(player.getUUID(), lock.owner(), lock.passcodeFingerprint()));
        return true;
    }

    /**
     * Only the owner may change the lock itself.
     *
     * Note this is a stricter test than {@link #check} - knowing the code
     * gets you into the block, not into its lock. Otherwise the first person
     * let in could set a new code and lock the owner out of their own
     * airport.
     */
    public static boolean mayAdminister(ServerPlayer player, BlockLock lock) {
        if (!lock.isOwned()) return true;
        return lock.isOwner(player.getUUID()) || player.hasPermissions(OPERATOR_LEVEL);
    }

    /** Drop every grant for a code that no longer opens anything - called
     *  when the owner changes or clears it, so "I changed the code" actually
     *  turns people out rather than only affecting whoever asks next. */
    public static void forget(BlockLock lock) {
        if (!lock.hasPasscode()) return;
        String fingerprint = lock.passcodeFingerprint();
        GRANTS.removeIf(grant -> grant.passcode().equals(fingerprint));
    }

    /**
     * Drop everything when the server stops.
     *
     * These are meant to last a session, and on a dedicated server the
     * process ending is the session ending, so nothing was needed. A
     * single-player client is one process that starts and stops a server
     * every time a world is opened and closed - so without this, grants
     * outlive the world they were given in and pile up for as long as the
     * game is running. Nothing can be opened with a stale one (the salt
     * makes every code's fingerprint unique to the block it was set on),
     * but "session" should mean what it says.
     */
    public static void forgetEverything() {
        GRANTS.clear();
    }
}
