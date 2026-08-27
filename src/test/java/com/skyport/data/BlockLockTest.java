package com.skyport.data;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lock that decides who may touch an airport or an aircraft.
 *
 * Worth testing because every failure here is silent in exactly the way
 * security failures are: a lock that accepts the wrong code, or writes the
 * right one into the region file in plain text, behaves identically to a
 * working one from the outside. Nothing in normal play would ever show it.
 */
class BlockLockTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void theFirstPlacerOwnsIt() {
        BlockLock lock = new BlockLock();
        assertFalse(lock.isOwned(), "a fresh lock is unowned, and so open");

        lock.claim(ALICE, "Alice");
        assertTrue(lock.isOwner(ALICE));
        assertFalse(lock.isOwner(BOB));
    }

    /**
     * Claiming is a one-off. If a later interaction could re-claim, anyone
     * who got in once could take the block off its owner - which is the
     * whole thing this is supposed to prevent.
     */
    @Test
    void ownershipCannotBeTakenOver() {
        BlockLock lock = new BlockLock();
        lock.claim(ALICE, "Alice");
        lock.claim(BOB, "Bob");

        assertTrue(lock.isOwner(ALICE), "still Alice's");
        assertFalse(lock.isOwner(BOB));
    }

    @Test
    void onlyTheRightCodeOpensIt() {
        BlockLock lock = new BlockLock();
        assertFalse(lock.passcodeMatches("anything"), "no code set means nothing matches");

        lock.setPasscode("tower-1");
        assertTrue(lock.hasPasscode());
        assertTrue(lock.passcodeMatches("tower-1"));
        assertFalse(lock.passcodeMatches("tower-2"));
        assertFalse(lock.passcodeMatches(""));
        assertFalse(lock.passcodeMatches(null));
    }

    /** Typing a code with a stray space either side is a typo, not a
     *  different code - and the box is easy to paste into. */
    @Test
    void surroundingSpaceIsIgnored() {
        BlockLock lock = new BlockLock();
        lock.setPasscode("  tower-1  ");
        assertTrue(lock.passcodeMatches("tower-1"));
        assertTrue(lock.passcodeMatches(" tower-1 "));
    }

    @Test
    void clearingTheCodeLeavesNothingBehind() {
        BlockLock lock = new BlockLock();
        lock.setPasscode("tower-1");
        lock.setPasscode("");

        assertFalse(lock.hasPasscode());
        assertFalse(lock.passcodeMatches("tower-1"), "the old code must stop working");
        assertEquals("", lock.passcodeFingerprint());
    }

    /**
     * The code must not be recoverable from the save file - by an admin
     * reading a region file, by anyone with a backup, or through the
     * fingerprint the grant system passes around.
     */
    @Test
    void theCodeIsNeverStoredInTheClear() {
        BlockLock lock = new BlockLock();
        lock.claim(ALICE, "Alice");
        lock.setPasscode("tower-1");

        CompoundTag tag = new CompoundTag();
        lock.save(tag);

        assertFalse(tag.toString().contains("tower-1"),
                "the passcode appears verbatim in the saved data");
        assertFalse(lock.passcodeFingerprint().contains("tower-1"));
    }

    /**
     * Two blocks given the same code must not store the same value.
     *
     * Otherwise the stored hashes leak which blocks share a code, which is
     * a map of the estate to anyone who can read one save file.
     */
    @Test
    void thesameCodeStoresDifferentlyEachTime() {
        BlockLock first = new BlockLock();
        BlockLock second = new BlockLock();
        first.setPasscode("tower-1");
        second.setPasscode("tower-1");

        assertNotEquals(first.passcodeFingerprint(), second.passcodeFingerprint());
        assertTrue(first.passcodeMatches("tower-1"));
        assertTrue(second.passcodeMatches("tower-1"));
    }

    @Test
    void ownerAndCodeSurviveAReload() {
        BlockLock saved = new BlockLock();
        saved.claim(ALICE, "Alice");
        saved.setPasscode("tower-1");

        CompoundTag tag = new CompoundTag();
        saved.save(tag);

        BlockLock loaded = new BlockLock();
        loaded.load(tag);

        assertTrue(loaded.isOwner(ALICE));
        assertEquals("Alice", loaded.ownerName());
        assertTrue(loaded.passcodeMatches("tower-1"));
        assertFalse(loaded.passcodeMatches("tower-2"));
    }

    /**
     * A block saved before this feature existed carries no lock tag, and has
     * to come back unowned - which is what leaves existing airports working
     * for everyone who was using them yesterday.
     */
    @Test
    void aBlockFromBeforeTheLockIsUnowned() {
        BlockLock loaded = new BlockLock();
        loaded.load(new CompoundTag());

        assertFalse(loaded.isOwned());
        assertFalse(loaded.hasPasscode());
    }

    /** Loading must also clear whatever was there, or a block entity reused
     *  across a reload would keep an owner the save file no longer has. */
    @Test
    void loadingReplacesRatherThanMerges() {
        BlockLock lock = new BlockLock();
        lock.claim(ALICE, "Alice");
        lock.setPasscode("tower-1");

        lock.load(new CompoundTag());

        assertFalse(lock.isOwned());
        assertFalse(lock.passcodeMatches("tower-1"));
    }
}
