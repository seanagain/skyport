package com.seaport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A ship's standing orders: the stops it works through, and which one is
 * next.
 *
 * Loops by default, because a vessel that stops dead at the end of its list
 * is a vessel somebody has to go and find. Running the list round again is
 * what a ferry, a supply run and a patrol all want, and the one case that
 * does not - a delivery made once - is served by taking the schedule off the
 * autopilot when it arrives.
 */
public class VoyageSchedule {

    /** Long enough for any route worth sailing, short enough that a
     *  schedule cannot be used to make a save file enormous. */
    public static final int MAX_STOPS = 64;

    private final List<VoyageStop> stops = new ArrayList<>();
    private int cursor;
    private boolean looping = true;

    public List<VoyageStop> stops() {
        return stops;
    }

    public boolean isEmpty() {
        return stops.isEmpty();
    }

    public boolean looping() {
        return looping;
    }

    public void setLooping(boolean value) {
        looping = value;
    }

    /**
     * Which stop the ship is working towards.
     *
     * Clamped on the way out rather than trusted, because the list can be
     * edited under it: deleting the stop a vessel is halfway to would
     * otherwise leave the cursor past the end, and the next thing to read it
     * would be a ship at sea.
     */
    public int cursor() {
        if (stops.isEmpty()) return 0;
        return Math.floorMod(cursor, stops.size());
    }

    public void setCursor(int index) {
        cursor = index;
    }

    public VoyageStop current() {
        return stops.isEmpty() ? null : stops.get(cursor());
    }

    /**
     * Move on to the next stop.
     *
     * @return false when the list has run out and this schedule does not
     *         loop, which is the signal to stop sailing rather than wrap
     */
    public boolean advance() {
        if (stops.isEmpty()) return false;
        int next = cursor() + 1;
        if (next >= stops.size()) {
            if (!looping) return false;
            next = 0;
        }
        cursor = next;
        return true;
    }

    /** Only the port stops. The marks between them are waypoints, not
     *  destinations - nothing berths at a buoy. */
    public List<VoyageStop> portStops() {
        return stops.stream().filter(stop -> !stop.isMark()).toList();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (VoyageStop stop : stops) list.add(stop.save());
        tag.put("stops", list);
        tag.putInt("cursor", cursor);
        tag.putBoolean("looping", looping);
        return tag;
    }

    public static VoyageSchedule load(CompoundTag tag) {
        VoyageSchedule schedule = new VoyageSchedule();
        ListTag list = tag.getList("stops", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && schedule.stops.size() < MAX_STOPS; i++) {
            VoyageStop stop = VoyageStop.load(list.getCompound(i));
            // One unreadable stop costs that stop, not the voyage - see the
            // note on VoyageStop#load.
            if (stop != null) schedule.stops.add(stop);
        }
        schedule.cursor = tag.getInt("cursor");
        // Absent means an older save, from before this flag existed, and
        // looping is what those did.
        schedule.looping = !tag.contains("looping") || tag.getBoolean("looping");
        return schedule;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(stops.size());
        for (VoyageStop stop : stops) stop.write(buf);
        buf.writeVarInt(cursor);
        buf.writeBoolean(looping);
    }

    public static VoyageSchedule read(FriendlyByteBuf buf) {
        VoyageSchedule schedule = new VoyageSchedule();
        int count = buf.readVarInt();
        // Refused rather than truncated. Reading fewer stops than were
        // written leaves the cursor and the loop flag being read out of the
        // middle of a stop, and everything after that is garbage that looks
        // like data. A client that sends more than the editor can make is
        // not one to carry on talking to.
        if (count < 0 || count > MAX_STOPS) {
            throw new io.netty.handler.codec.DecoderException(
                    "schedule of " + count + " stops, limit is " + MAX_STOPS);
        }
        for (int i = 0; i < count; i++) schedule.stops.add(VoyageStop.read(buf));
        schedule.cursor = buf.readVarInt();
        schedule.looping = buf.readBoolean();
        return schedule;
    }
}
