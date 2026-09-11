package com.skyport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One stop on a plane's schedule: where to fly, and what has to happen
 * before it leaves again. Modelled on Create's train schedules, which are
 * the same shape - a destination plus a departure condition - and which
 * players on a Create server already know how to read.
 *
 * A stop is one of two kinds. An airport stop lands at a gate or pad and
 * waits there. A VOR stop is flown over on the way to the next airport and
 * does neither, so its gate, condition and wait mean nothing and sit at
 * inert values. Exactly one of airportId and vorId is set.
 */
public record ScheduleEntry(@Nullable UUID airportId, String gateName, WaitCondition condition, int waitSeconds,
                            @Nullable UUID vorId) {

    public enum WaitCondition {
        /** Sit at the gate for {@link #waitSeconds}, then go. */
        TIMER,
        /** Wait until a player is standing near the plane - the "don't leave
         *  without me" case, and how a passenger route works. */
        PLAYER,
        /** Wait until the aircraft is carrying something - the loading end of
         *  a delivery run. */
        CARGO_LOADED,
        /** Wait until it has been emptied - the unloading end. */
        CARGO_EMPTY
    }

    public ScheduleEntry {
        // Both, or neither, is a stop nothing can fly - and one that would
        // otherwise surface much later, mid-flight, as a destination that
        // quietly resolves to nowhere.
        if ((airportId == null) == (vorId == null)) {
            throw new IllegalArgumentException("A schedule stop names an airport or a VOR - not both, and not neither");
        }
    }

    /** An airport stop - which is every stop there was, before VORs. */
    public ScheduleEntry(UUID airportId, String gateName, WaitCondition condition, int waitSeconds) {
        this(airportId, gateName, condition, waitSeconds, null);
    }

    /** A VOR to fly over. */
    public static ScheduleEntry vor(UUID vorId) {
        return new ScheduleEntry(null, "", WaitCondition.TIMER, 0, vorId);
    }

    public boolean isVor() {
        return vorId != null;
    }

    /**
     * Airport stops are written exactly as they always were, so a schedule
     * saved before VORs existed loads unchanged: the absence of a vorId is
     * what makes a stop an airport stop.
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (isVor()) {
            tag.putUUID("vorId", vorId);
            return tag;
        }
        tag.putUUID("airportId", airportId);
        tag.putString("gateName", gateName);
        tag.putString("condition", condition.name());
        tag.putInt("waitSeconds", waitSeconds);
        return tag;
    }

    public static ScheduleEntry load(CompoundTag tag) {
        if (tag.hasUUID("vorId")) return vor(tag.getUUID("vorId"));
        return new ScheduleEntry(
                tag.getUUID("airportId"),
                tag.getString("gateName"),
                WaitCondition.valueOf(tag.getString("condition")),
                tag.getInt("waitSeconds"));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isVor());
        if (isVor()) {
            buf.writeUUID(vorId);
            return;
        }
        buf.writeUUID(airportId);
        buf.writeUtf(gateName);
        buf.writeEnum(condition);
        buf.writeVarInt(waitSeconds);
    }

    public static ScheduleEntry read(FriendlyByteBuf buf) {
        if (buf.readBoolean()) return vor(buf.readUUID());
        return new ScheduleEntry(
                buf.readUUID(),
                buf.readUtf(),
                buf.readEnum(WaitCondition.class),
                buf.readVarInt());
    }
}
