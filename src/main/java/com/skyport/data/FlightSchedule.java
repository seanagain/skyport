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

    public List<ScheduleEntry> entries() {
        return entries;
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ScheduleEntry entry : entries) list.add(entry.save());
        tag.put("entries", list);
        tag.putBoolean("loop", loop);
        return tag;
    }

    public static FlightSchedule load(CompoundTag tag) {
        FlightSchedule schedule = new FlightSchedule();
        ListTag list = tag.getList("entries", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            schedule.entries.add(ScheduleEntry.load(list.getCompound(i)));
        }
        schedule.loop = tag.getBoolean("loop");
        return schedule;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (ScheduleEntry entry : entries) entry.write(buf);
        buf.writeBoolean(loop);
    }

    public static FlightSchedule read(FriendlyByteBuf buf) {
        FlightSchedule schedule = new FlightSchedule();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) schedule.entries.add(ScheduleEntry.read(buf));
        schedule.loop = buf.readBoolean();
        return schedule;
    }
}
