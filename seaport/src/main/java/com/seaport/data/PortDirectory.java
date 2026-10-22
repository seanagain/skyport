package com.seaport.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What a schedule can be built out of: every port with its berths, and every
 * channel marker.
 *
 * Sent to the client with the autopilot screen rather than looked up there,
 * because the client does not have a registry - the ports and marks live in
 * world save data on the server, and most of them are in chunks nobody has
 * loaded. A screen that could only offer what happens to be nearby would be
 * useless for exactly the routes this mod exists to run.
 *
 * It is a snapshot, deliberately. It goes stale the moment somebody renames
 * a berth, and that is fine: the ids are what a stop stores, so a stale name
 * shows a wrong label for one screenful and still points at the right place.
 */
public record PortDirectory(List<Entry> ports, List<Mark> marks) {

    /** A hard cap on what one packet will carry. A world with more harbours
     *  than this has a scrolling problem the screen cannot solve anyway, and
     *  an unbounded list is a packet somebody can make enormous. */
    public static final int MAX_ENTRIES = 256;

    /** One harbour, and what can be tied up at in it. */
    public record Entry(UUID id, String name, String dimension, List<String> berths) {

        public void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name, PortLayout.MAX_NAME_LENGTH * 4);
            buf.writeUtf(dimension, 256);
            buf.writeVarInt(berths.size());
            for (String berth : berths) buf.writeUtf(berth, Wharf.MAX_NAME_LENGTH * 4);
        }

        public static Entry read(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(PortLayout.MAX_NAME_LENGTH * 4);
            String dimension = buf.readUtf(256);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new io.netty.handler.codec.DecoderException("berth list of " + count);
            }
            List<String> berths = new ArrayList<>(count);
            for (int i = 0; i < count; i++) berths.add(buf.readUtf(Wharf.MAX_NAME_LENGTH * 4));
            return new Entry(id, name, dimension, List.copyOf(berths));
        }
    }

    /** One channel marker. Its position comes along so the screen can say
     *  where a mark is without a second round trip. */
    public record Mark(UUID id, String name, int x, int z) {

        public void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name, Beacon.MAX_NAME_LENGTH * 4);
            buf.writeVarInt(x);
            buf.writeVarInt(z);
        }

        public static Mark read(FriendlyByteBuf buf) {
            return new Mark(buf.readUUID(), buf.readUtf(Beacon.MAX_NAME_LENGTH * 4),
                    buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * Everything in the registry, sorted by name.
     *
     * Sorted here rather than in the screen so the order is the same for
     * every player who opens it: a list that reshuffles between sessions
     * because a HashMap iterated differently is one nobody can learn.
     */
    public static PortDirectory snapshot(ServerLevel level) {
        PortRegistry registry = PortRegistry.get(level);

        List<Entry> ports = new ArrayList<>();
        for (PortLayout port : registry.all()) {
            if (ports.size() >= MAX_ENTRIES) break;
            List<String> berths = port.wharves().stream()
                    .filter(Wharf::isUsable)
                    .map(Wharf::name)
                    .sorted()
                    .toList();
            ports.add(new Entry(port.id(), port.displayName(),
                    port.dimension().location().toString(), berths));
        }
        ports.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));

        List<Mark> marks = new ArrayList<>();
        for (Beacon beacon : registry.beacons()) {
            if (marks.size() >= MAX_ENTRIES) break;
            marks.add(new Mark(beacon.id(), beacon.name(),
                    beacon.pos().getX(), beacon.pos().getZ()));
        }
        marks.sort(Comparator.comparing(Mark::name, String.CASE_INSENSITIVE_ORDER));

        return new PortDirectory(List.copyOf(ports), List.copyOf(marks));
    }

    public Entry portById(UUID id) {
        for (Entry entry : ports) if (entry.id().equals(id)) return entry;
        return null;
    }

    public Mark markById(UUID id) {
        for (Mark mark : marks) if (mark.id().equals(id)) return mark;
        return null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(ports.size());
        for (Entry entry : ports) entry.write(buf);
        buf.writeVarInt(marks.size());
        for (Mark mark : marks) mark.write(buf);
    }

    public static PortDirectory read(FriendlyByteBuf buf) {
        int portCount = countOf(buf);
        List<Entry> ports = new ArrayList<>(portCount);
        for (int i = 0; i < portCount; i++) ports.add(Entry.read(buf));
        int markCount = countOf(buf);
        List<Mark> marks = new ArrayList<>(markCount);
        for (int i = 0; i < markCount; i++) marks.add(Mark.read(buf));
        return new PortDirectory(List.copyOf(ports), List.copyOf(marks));
    }

    private static int countOf(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new io.netty.handler.codec.DecoderException("directory of " + count + " entries");
        }
        return count;
    }
}
