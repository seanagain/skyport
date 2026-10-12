package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * A VOR beacon: a named point a schedule can route an aircraft over.
 *
 * Only its x and z matter to a flight. Aircraft cross a VOR at whatever
 * cruise altitude their schedule sets - the beacon's own y is where the block
 * happens to stand, which is somewhere on the ground, and is kept only so the
 * block can be drawn and found again.
 *
 * Registered world-wide alongside the airports (see AirportRegistry), so a
 * schedule can name a VOR nobody has loaded since it was placed.
 */
public record VorBeacon(UUID id, String name, String dimension, BlockPos pos) {

    /** Matches what the schedule list and the tower map have room for. */
    public static final int MAX_NAME_LENGTH = 24;

    /** Minecraft's formatting-code prefix, the section sign. */
    private static final char FORMATTING_PREFIX = '§';

    public VorBeacon withName(String newName) {
        return new VorBeacon(id, newName, dimension, pos);
    }

    /** Named after where it stands until someone names it - more use in a
     *  list of several than "New VOR" three times over. */
    public static String defaultName(BlockPos pos) {
        return "VOR " + pos.getX() + ", " + pos.getZ();
    }

    /**
     * What a player typed, made fit to show everywhere a VOR is listed.
     *
     * Done on the server, because the name arrives in a packet and a packet
     * can say anything: formatting codes that restyle every line the name
     * appears in, control characters, or a thousand characters of it. Blank
     * keeps the previous name rather than leaving a stop in someone's
     * schedule labelled with nothing.
     */
    public static String cleanName(String raw, String fallback) {
        if (raw == null) return fallback;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 32 && c != 127 && c != FORMATTING_PREFIX) kept.append(c);
        }
        String cleaned = kept.toString().strip();
        if (cleaned.isEmpty()) return fallback;
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH).strip() : cleaned;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("dimension", dimension);
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    public static VorBeacon load(CompoundTag tag) {
        return new VorBeacon(tag.getUUID("id"), tag.getString("name"), tag.getString("dimension"),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(dimension);
        buf.writeBlockPos(pos);
    }

    public static VorBeacon read(FriendlyByteBuf buf) {
        return new VorBeacon(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readBlockPos());
    }
}
