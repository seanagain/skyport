package com.seaport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One stop on a ship's schedule: where to sail, and what has to happen before
 * it casts off again.
 *
 * The same shape as Skyport's {@code ScheduleEntry}, which in turn is the
 * same shape as a Create train schedule - a destination plus a departure
 * condition. Players on a Create server already know how to read one, and
 * there is no reason a ship should work differently from a train or a plane
 * just because it floats.
 *
 * A stop is one of two kinds. A PORT stop ties up at a named berth and waits
 * there. A MARK stop is passed on the way to the next port and does neither,
 * so its berth, condition and wait mean nothing and sit at inert values.
 * Exactly one of portId and beaconId is set, and the constructor refuses
 * anything else - a stop that names both, or neither, is one no vessel can
 * sail, and that would otherwise surface much later as a destination that
 * quietly resolves to nowhere.
 */
public record VoyageStop(@Nullable UUID portId, String berthName, WaitCondition condition,
                         int waitSeconds, @Nullable UUID beaconId) {

    /** A whole Minecraft day is already an absurd time to sit alongside;
     *  past that it is somebody typing rather than planning. */
    public static final int MAX_WAIT_SECONDS = 24 * 60 * 60;

    public enum WaitCondition {
        /** Lie alongside for {@link #waitSeconds}, then sail. */
        TIMER,
        /** Wait until somebody is aboard or on the quay - the "don't sail
         *  without me" case, and how a passenger run works. */
        PLAYER,
        /** Wait until the ship is carrying something: the loading end of a
         *  freight run. */
        CARGO_LOADED,
        /** Wait until it has been emptied: the unloading end. */
        CARGO_EMPTY
    }

    public VoyageStop {
        if ((portId == null) == (beaconId == null)) {
            throw new IllegalArgumentException(
                    "A stop names a port or a mark - not both, and not neither");
        }
        if (berthName == null) berthName = "";
        if (condition == null) condition = WaitCondition.TIMER;
        waitSeconds = Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
    }

    /** A port stop: tie up at this berth and wait. */
    public static VoyageStop port(UUID portId, String berthName,
                                  WaitCondition condition, int waitSeconds) {
        return new VoyageStop(portId, berthName, condition, waitSeconds, null);
    }

    /** A mark to pass on the way. */
    public static VoyageStop mark(UUID beaconId) {
        return new VoyageStop(null, "", WaitCondition.TIMER, 0, beaconId);
    }

    public boolean isMark() {
        return beaconId != null;
    }

    public VoyageStop withBerth(String name) {
        return new VoyageStop(portId, name, condition, waitSeconds, beaconId);
    }

    public VoyageStop withCondition(WaitCondition newCondition) {
        return new VoyageStop(portId, berthName, newCondition, waitSeconds, beaconId);
    }

    public VoyageStop withWait(int seconds) {
        return new VoyageStop(portId, berthName, condition, seconds, beaconId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (isMark()) {
            tag.putUUID("beaconId", beaconId);
            return tag;
        }
        tag.putUUID("portId", portId);
        tag.putString("berthName", berthName);
        tag.putString("condition", condition.name());
        tag.putInt("waitSeconds", waitSeconds);
        return tag;
    }

    /**
     * One stop, or null if this tag does not describe one.
     *
     * Null rather than an exception, because a schedule is a list and one
     * unreadable entry in it should cost that entry, not the voyage. Skyport
     * learned this the expensive way: an unguarded read here threw, the whole
     * schedule failed to load, and a fleet of aircraft came back from a
     * restart with no idea where they were going.
     */
    @Nullable
    public static VoyageStop load(CompoundTag tag) {
        try {
            if (tag.hasUUID("beaconId")) return mark(tag.getUUID("beaconId"));
            if (!tag.hasUUID("portId")) return null;
            WaitCondition condition;
            try {
                condition = WaitCondition.valueOf(tag.getString("condition"));
            } catch (IllegalArgumentException unknown) {
                // A condition this build does not have - from a newer version,
                // or a hand-edited file. Sailing on a timer is the safe
                // reading: the ship keeps moving rather than sitting at a
                // berth forever waiting for something that cannot happen.
                condition = WaitCondition.TIMER;
            }
            return port(tag.getUUID("portId"), tag.getString("berthName"),
                    condition, tag.getInt("waitSeconds"));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isMark());
        if (isMark()) {
            buf.writeUUID(beaconId);
            return;
        }
        buf.writeUUID(portId);
        buf.writeUtf(berthName, Wharf.MAX_NAME_LENGTH * 4);
        buf.writeEnum(condition);
        buf.writeVarInt(waitSeconds);
    }

    public static VoyageStop read(FriendlyByteBuf buf) {
        if (buf.readBoolean()) return mark(buf.readUUID());
        return port(buf.readUUID(), buf.readUtf(Wharf.MAX_NAME_LENGTH * 4),
                buf.readEnum(WaitCondition.class), buf.readVarInt());
    }
}
