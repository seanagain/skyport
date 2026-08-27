package com.skyport.data;

import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Who owns a block, and the optional passcode that lets someone else in.
 *
 * Ownership is the real mechanism here and the passcode is the exception to
 * it. That is the opposite way round from how it is usually asked for, and
 * it is deliberate: a passcode cannot stop a pickaxe, so something has to
 * know who the block belongs to before breaking it can be refused. Once
 * that exists, ownership answers the common case - your own airport, your
 * own aircraft - with nothing to type, nothing to remember and nothing to
 * leak. The passcode is for the case ownership cannot express: letting a
 * friend, or a whole server, use an airport you built.
 *
 * The code itself is never stored and never sent to a client. What is kept
 * is a salted SHA-256 of it, so a passcode cannot be read back out of a
 * region file or a save backup by whoever has one - including the owner,
 * who has to set a new code rather than being reminded of the old one.
 *
 * An unowned lock - a block placed before this existed, or by something
 * that is not a player - is deliberately open. Retrofitting ownership onto
 * existing blocks would lock people out of their own airports on update,
 * which is a worse failure than a legacy block staying as permissive as it
 * was yesterday. Break it and replace it to claim it.
 */
public final class BlockLock {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UUID owner;
    private String ownerName = "";
    private String passcodeHash = "";
    private String passcodeSalt = "";

    /** Record the player who placed the block. Does nothing if already
     *  owned - claiming is a one-off, not something a later interaction can
     *  quietly redo. */
    public void claim(UUID player, String name) {
        if (owner != null) return;
        owner = player;
        ownerName = name == null ? "" : name;
    }

    public boolean isOwned() {
        return owner != null;
    }

    public boolean isOwner(UUID player) {
        return owner != null && owner.equals(player);
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName.isEmpty() ? "someone else" : ownerName;
    }

    public boolean hasPasscode() {
        return !passcodeHash.isEmpty();
    }

    /**
     * Set or clear the passcode. Null or blank clears it.
     *
     * A fresh salt every time, so setting the same code twice does not
     * produce the same stored value - otherwise two blocks sharing a hash
     * would announce that they share a code.
     */
    public void setPasscode(String plain) {
        if (plain == null || plain.isBlank()) {
            passcodeHash = "";
            passcodeSalt = "";
            return;
        }
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        passcodeSalt = toHex(salt);
        passcodeHash = hash(passcodeSalt, plain.strip());
    }

    /**
     * Does this attempt match?
     *
     * Compared with MessageDigest.isEqual rather than String.equals: it does
     * not return early on the first differing byte, so how long the check
     * takes says nothing about how much of the code was right.
     */
    public boolean passcodeMatches(String attempt) {
        if (!hasPasscode() || attempt == null) return false;
        String candidate = hash(passcodeSalt, attempt.strip());
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                passcodeHash.getBytes(StandardCharsets.UTF_8));
    }

    /** Identifies "the people who know this block's code" without revealing
     *  it - see AccessControl, which remembers a proven code rather than a
     *  position. Empty when there is no code to know. */
    public String passcodeFingerprint() {
        return passcodeHash;
    }

    // --- persistence -----------------------------------------------------

    private static final String KEY = "SkyportLock";

    public void save(CompoundTag parent) {
        if (owner == null && passcodeHash.isEmpty()) return;
        CompoundTag tag = new CompoundTag();
        if (owner != null) {
            tag.putUUID("Owner", owner);
            tag.putString("OwnerName", ownerName);
        }
        if (!passcodeHash.isEmpty()) {
            tag.putString("Hash", passcodeHash);
            tag.putString("Salt", passcodeSalt);
        }
        parent.put(KEY, tag);
    }

    public void load(CompoundTag parent) {
        owner = null;
        ownerName = "";
        passcodeHash = "";
        passcodeSalt = "";
        if (!parent.contains(KEY)) return;
        CompoundTag tag = parent.getCompound(KEY);
        if (tag.hasUUID("Owner")) {
            owner = tag.getUUID("Owner");
            ownerName = tag.getString("OwnerName");
        }
        passcodeHash = tag.getString("Hash");
        passcodeSalt = tag.getString("Salt");
    }

    // --- helpers ---------------------------------------------------------

    private static String hash(String salt, String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(plain.getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java implementation. If it is
            // genuinely missing, failing loudly beats storing something
            // weaker under a name that claims it is hashed.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xF, 16))
                                .append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }
}
