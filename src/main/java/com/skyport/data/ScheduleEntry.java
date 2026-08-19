package com.skyport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * One stop on a plane's schedule: where to fly, and what has to happen
 * before it leaves again. Modelled on Create's train schedules, which are
 * the same shape - a destination plus a departure condition - and which
 * players on a Create server already know how to read.
 */
public record ScheduleEntry(UUID airportId, String gateName, WaitCondition condition, int waitSeconds) {

    public enum WaitCondition {
        /** Sit at the gate for {@link #waitSeconds}, then go. */
        TIMER,
        /** Wait until a player is standing near the plane - the "don't leave
         *  without me" case, and how a passenger route works. */
        PLAYER,
        /**
         * Wait until the plane is carrying cargo.
         *
         * NOT DETECTED YET: reading a contraption's inventory needs the same
         * contraption reference that real movement does (Create exposes
         * MountedStorageManager for this). Until that's wired up, the
         * autopilot says so in chat and falls back to the timer rather than
         * silently sitting forever or silently leaving.
         */
        CARGO
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("airportId", airportId);
        tag.putString("gateName", gateName);
        tag.putString("condition", condition.name());
        tag.putInt("waitSeconds", waitSeconds);
        return tag;
    }

    public static ScheduleEntry load(CompoundTag tag) {
        return new ScheduleEntry(
                tag.getUUID("airportId"),
                tag.getString("gateName"),
                WaitCondition.valueOf(tag.getString("condition")),
                tag.getInt("waitSeconds"));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(airportId);
        buf.writeUtf(gateName);
        buf.writeEnum(condition);
        buf.writeVarInt(waitSeconds);
    }

    public static ScheduleEntry read(FriendlyByteBuf buf) {
        return new ScheduleEntry(
                buf.readUUID(),
                buf.readUtf(),
                buf.readEnum(WaitCondition.class),
                buf.readVarInt());
    }
}
