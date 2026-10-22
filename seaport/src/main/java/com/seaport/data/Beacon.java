package com.seaport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * A channel marker: a named point a schedule can route a ship through.
 *
 * Only its x and z matter. A vessel passes a beacon at whatever the water is
 * doing there; the mark's own y is wherever the block happens to stand, kept
 * only so it can be drawn on a chart and found again.
 *
 * This is the one piece of steering a player gets to do by hand. The router
 * will find a way across open water on its own, but "on its own" means the
 * shortest navigable line, and the shortest line through an archipelago is
 * not always the one a harbourmaster wants vessels taking - it may thread a
 * gap that another ship is already sitting in, or cut inside a reef that is
 * deep enough in the chart and horrible in practice. Dropping a beacon in the
 * channel you want used, and naming it in the schedule, says so.
 *
 * Registered world-wide alongside the ports (see {@link PortRegistry}), so a
 * schedule can name a mark nobody has loaded since it was placed.
 */
public record Beacon(UUID id, String name, String dimension, BlockPos pos) {

    /** Matches what the schedule list has room for. */
    public static final int MAX_NAME_LENGTH = 24;

    /** Minecraft's formatting-code prefix, the section sign. */
    private static final char FORMATTING_PREFIX = '§';

    public Beacon withName(String newName) {
        return new Beacon(id, newName, dimension, pos);
    }

    /** Named after where it stands until somebody names it - more use in a
     *  list of several than "New Beacon" three times over. */
    public static String defaultName(BlockPos pos) {
        return "Mark " + pos.getX() + ", " + pos.getZ();
    }

    /**
     * What a player typed, made fit to show everywhere a beacon is listed.
     *
     * Done on the server, because the name arrives in a packet and a packet
     * can say anything: formatting codes that restyle every line the name
     * appears in, control characters, or a thousand characters of it. Blank
     * keeps the previous name rather than leaving a stop in somebody's
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
        return cleaned.length() > MAX_NAME_LENGTH
                ? cleaned.substring(0, MAX_NAME_LENGTH).strip()
                : cleaned;
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

    public static Beacon load(CompoundTag tag) {
        return new Beacon(tag.getUUID("id"), tag.getString("name"), tag.getString("dimension"),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(dimension);
        buf.writeBlockPos(pos);
    }

    public static Beacon read(FriendlyByteBuf buf) {
        return new Beacon(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readBlockPos());
    }
}
