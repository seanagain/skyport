package com.seaport.data;

import com.seaport.logic.Berthing;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One berth: a named stretch of quay edge that a ship can be sent to.
 *
 * Two points and a name, and that is deliberately all. Skyport's airports
 * need runways, taxiways, holding patterns and gates drawn out, because an
 * aeroplane cannot go anywhere a player has not described. A ship can go
 * anywhere there is water deep enough, which the chart already knows - so the
 * only thing a harbour has to be told is where its stone ends and where a
 * vessel is meant to stop.
 *
 * The two points are the ENDS OF THE QUAY EDGE, not the corners of a parking
 * space. A ship ties up alongside the line, on whichever side it approached
 * from, and how far out it stops is the port's clearance setting rather than
 * anything drawn here. See Berthing for what that means in arithmetic.
 */
public record Wharf(String name, BlockPos from, BlockPos to) {

    public static final int MAX_NAME_LENGTH = 24;

    /** Shortest quay worth having. Both ends in one block gives a line with
     *  no direction, and a direction is what every berthing calculation is
     *  measured against. */
    public static final int MIN_LENGTH = 1;

    public Wharf {
        name = clean(name, "Berth");
    }

    /** The geometry, at the water's surface - height plays no part in tying
     *  a ship up. */
    public Berthing.QuayLine line() {
        return new Berthing.QuayLine(
                new Berthing.Point(from.getX() + 0.5, from.getZ() + 0.5),
                new Berthing.Point(to.getX() + 0.5, to.getZ() + 0.5));
    }

    public double length() {
        return line().length();
    }

    /** Long enough to be a quay rather than a point. */
    public boolean isUsable() {
        return length() >= MIN_LENGTH;
    }

    public Wharf withName(String newName) {
        return new Wharf(newName, from, to);
    }

    public static String clean(String raw, String fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return fallback;
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putLong("from", from.asLong());
        tag.putLong("to", to.asLong());
        return tag;
    }

    /**
     * @return the wharf, or null if the tag does not describe one - a stop
     *         that cannot be read is skipped rather than taking the whole
     *         port down with it, the same rule Skyport's schedules learned
     */
    public static Wharf load(CompoundTag tag) {
        if (!tag.contains("from") || !tag.contains("to")) return null;
        return new Wharf(tag.getString("name"),
                BlockPos.of(tag.getLong("from")), BlockPos.of(tag.getLong("to")));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeBlockPos(from);
        buf.writeBlockPos(to);
    }

    public static Wharf read(FriendlyByteBuf buf) {
        return new Wharf(buf.readUtf(), buf.readBlockPos(), buf.readBlockPos());
    }
}
