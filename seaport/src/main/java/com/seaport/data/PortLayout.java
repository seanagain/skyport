package com.seaport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One port: what it is called, where its berths are, and how close ships
 * stop.
 *
 * Far smaller than Skyport's AirportLayout, and that is the point rather than
 * a shortcut. An airport has to be drawn in full because an aeroplane cannot
 * use ground nobody described - every runway, taxiway and gate. A harbour
 * sits on water, which is navigable by default and already charted, so the
 * only thing it has to be told is where its stone ends.
 */
public class PortLayout {

    public static final int MAX_NAME_LENGTH = 32;

    /** How far off the quay a hull stops, in blocks. One means touching
     *  distance - near enough to walk a gangplank, far enough that the hull
     *  is not inside the harbour wall. */
    public static final int DEFAULT_BERTH_CLEARANCE = 1;
    public static final int MIN_BERTH_CLEARANCE = 0;
    /** Beyond this the ship is moored in the harbour rather than at the
     *  quay, and nothing could reach it. */
    public static final int MAX_BERTH_CLEARANCE = 8;

    private final UUID id;
    private String displayName;
    private final ResourceKey<Level> dimension;
    private BlockPos stationPos;
    private final List<Wharf> wharves = new ArrayList<>();
    private int berthClearance = DEFAULT_BERTH_CLEARANCE;

    public PortLayout(UUID id, String displayName, ResourceKey<Level> dimension) {
        this.id = id;
        this.displayName = displayName;
        this.dimension = dimension;
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String name) {
        this.displayName = Wharf.clean(name, "New Port");
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos stationPos() {
        return stationPos;
    }

    public void setStationPos(BlockPos pos) {
        this.stationPos = pos;
    }

    /** Live and mutable, the way AirportLayout's gates are - the screen edits
     *  this list directly and sends the whole layout back. */
    public List<Wharf> wharves() {
        return wharves;
    }

    public int berthClearance() {
        return berthClearance;
    }

    public void setBerthClearance(int blocks) {
        this.berthClearance = Math.max(MIN_BERTH_CLEARANCE,
                Math.min(MAX_BERTH_CLEARANCE, blocks));
    }

    /** A port with no berth is a name on a map: a ship can be routed to it
     *  and will have nowhere to stop. Worth being able to ask. */
    public boolean hasUsableWharf() {
        return wharves.stream().anyMatch(Wharf::isUsable);
    }

    public Wharf wharfNamed(String name) {
        for (Wharf wharf : wharves) {
            if (wharf.name().equalsIgnoreCase(name)) return wharf;
        }
        return null;
    }

    /** A name nothing else here is using, for a berth just added. */
    public String nextWharfName() {
        for (int i = 1; i < 1000; i++) {
            String candidate = "Berth " + i;
            if (wharfNamed(candidate) == null) return candidate;
        }
        return "Berth";
    }

    // ---- disk --------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("displayName", displayName);
        tag.putString("dimension", dimension.location().toString());
        if (stationPos != null) tag.putLong("stationPos", stationPos.asLong());
        tag.putInt("berthClearance", berthClearance);

        ListTag list = new ListTag();
        for (Wharf wharf : wharves) list.add(wharf.save());
        tag.put("wharves", list);
        return tag;
    }

    public static PortLayout load(CompoundTag tag) {
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("dimension")));
        PortLayout layout = new PortLayout(tag.getUUID("id"), tag.getString("displayName"), dimension);
        if (tag.contains("stationPos")) layout.stationPos = BlockPos.of(tag.getLong("stationPos"));
        if (tag.contains("berthClearance")) layout.setBerthClearance(tag.getInt("berthClearance"));

        ListTag list = tag.getList("wharves", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            // Null means that berth could not be read. One unreadable berth
            // must not cost the port the rest of them, or an aircraft's worth
            // of shipping gets stranded by a single bad tag.
            Wharf wharf = Wharf.load(list.getCompound(i));
            if (wharf != null) layout.wharves.add(wharf);
        }
        return layout;
    }

    // ---- the wire ----------------------------------------------------------

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(displayName);
        buf.writeResourceLocation(dimension.location());
        buf.writeBoolean(stationPos != null);
        if (stationPos != null) buf.writeBlockPos(stationPos);
        buf.writeVarInt(berthClearance);
        buf.writeVarInt(wharves.size());
        for (Wharf wharf : wharves) wharf.write(buf);
    }

    public static PortLayout read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        ResourceKey<Level> dimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, buf.readResourceLocation());

        PortLayout layout = new PortLayout(id, name, dimension);
        if (buf.readBoolean()) layout.stationPos = buf.readBlockPos();
        layout.setBerthClearance(buf.readVarInt());
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) layout.wharves.add(Wharf.read(buf));
        return layout;
    }
}
