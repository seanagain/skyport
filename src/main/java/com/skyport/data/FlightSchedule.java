package com.skyport.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of stops a plane flies, with an optional loop - the
 * plane's equivalent of a Create train schedule.
 *
 * A one-entry non-looping schedule is exactly the old "fly to this gate
 * and stop" behaviour, so nothing was lost by generalising to this.
 */
public class FlightSchedule {

    private final List<ScheduleEntry> entries = new ArrayList<>();
    private boolean loop = true;
    /** Y level the plane levels out at between airports. */
    private int cruiseAltitude = 150;
    /** Airborne speed in blocks/second. Ground speeds stay fixed - taxiing
     *  fast is just a way to overshoot waypoints. */
    private int cruiseSpeed = 24;
    /** What is flying this route - decides whether it uses runways or pads. */
    private CraftType craftType = CraftType.PLANE;
    /** Player-given name for this aircraft. Blank means fall back to the
     *  generated callsign - see AutopilotBlockEntity#callsign. */
    private String craftName = "";

    public List<ScheduleEntry> entries() {
        return entries;
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public int cruiseAltitude() {
        return cruiseAltitude;
    }

    public void setCruiseAltitude(int cruiseAltitude) {
        this.cruiseAltitude = cruiseAltitude;
    }

    public int cruiseSpeed() {
        return cruiseSpeed;
    }

    public void setCruiseSpeed(int cruiseSpeed) {
        this.cruiseSpeed = cruiseSpeed;
    }

    public CraftType craftType() {
        return craftType;
    }

    public void setCraftType(CraftType craftType) {
        this.craftType = craftType;
    }

    public String craftName() {
        return craftName;
    }

    public void setCraftName(String craftName) {
        this.craftName = craftName;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The stop after {@code index}, or -1 when the schedule is finished.
     * Wraps when looping, which is the whole point of the loop flag - a
     * looping schedule never returns -1 as long as it has any entries.
     */
    public int nextIndex(int index) {
        if (entries.isEmpty()) return -1;
        int next = index + 1;
        if (next < entries.size()) return next;
        return loop ? 0 : -1;
    }

    /**
     * The airport a leg starting at this stop is actually flying to.
     *
     * VORs are flown over on the way somewhere, so the destination is the
     * first airport stop at or after the index - followed the way nextIndex
     * follows the schedule, round to the start when it loops and not
     * otherwise. Trailing VORs on a schedule that does not loop therefore
     * lead nowhere, which is the honest answer: there is no airport after
     * them to fly to.
     *
     * @return that stop's index, or -1 if nothing but VORs lies ahead
     */
    public int destinationIndexFrom(int index) {
        if (index < 0 || index >= entries.size()) return -1;
        int i = index;
        // Bounded by the schedule's length, so a loop of nothing but VORs
        // gives up rather than going round forever.
        for (int step = 0; step < entries.size(); step++) {
            if (!entries.get(i).isVor()) return i;
            i = nextIndex(i);
            if (i < 0) return -1;
        }
        return -1;
    }

    /** Whether there is anywhere on this schedule to land at all. A route of
     *  nothing but VORs has no destination to fly toward. */
    public boolean hasAirportStop() {
        for (ScheduleEntry entry : entries) {
            if (!entry.isVor()) return true;
        }
        return false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ScheduleEntry entry : entries) list.add(entry.save());
        tag.put("entries", list);
        tag.putBoolean("loop", loop);
        tag.putInt("cruiseAltitude", cruiseAltitude);
        tag.putInt("cruiseSpeed", cruiseSpeed);
        tag.putString("craftType", craftType.name());
        tag.putString("craftName", craftName);
        return tag;
    }

    public static FlightSchedule load(CompoundTag tag) {
        FlightSchedule schedule = new FlightSchedule();
        ListTag list = tag.getList("entries", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            schedule.entries.add(ScheduleEntry.load(list.getCompound(i)));
        }
        schedule.loop = tag.getBoolean("loop");
        if (tag.contains("cruiseAltitude")) schedule.cruiseAltitude = tag.getInt("cruiseAltitude");
        if (tag.contains("cruiseSpeed")) schedule.cruiseSpeed = tag.getInt("cruiseSpeed");
        if (tag.contains("craftType")) schedule.craftType = CraftType.valueOf(tag.getString("craftType"));
        if (tag.contains("craftName")) schedule.craftName = tag.getString("craftName");
        return schedule;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (ScheduleEntry entry : entries) entry.write(buf);
        buf.writeBoolean(loop);
        buf.writeVarInt(cruiseAltitude);
        buf.writeVarInt(cruiseSpeed);
        buf.writeEnum(craftType);
        buf.writeUtf(craftName);
    }

    public static FlightSchedule read(FriendlyByteBuf buf) {
        FlightSchedule schedule = new FlightSchedule();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) schedule.entries.add(ScheduleEntry.read(buf));
        schedule.loop = buf.readBoolean();
        schedule.cruiseAltitude = buf.readVarInt();
        schedule.cruiseSpeed = buf.readVarInt();
        schedule.craftType = buf.readEnum(CraftType.class);
        schedule.craftName = buf.readUtf();
        return schedule;
    }
}
