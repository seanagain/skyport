package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything drawn at one airport station: its runway, taxiways, holding
 * pattern, and named gates. One of these gets created per Airport Station
 * block and lives inside {@link AirportRegistry}, keyed by {@link #id}.
 *
 * This is pure data - no behaviour. The autopilot block reads an
 * AirportLayout to know what path to fly; the map screen writes one when
 * the player finishes drawing.
 */
public class AirportLayout {

    private final UUID id;
    private String displayName;
    private ResourceKey<Level> dimension;

    // Waypoints grouped by type, each list already sorted by Waypoint#order.
    private final Map<Waypoint.Type, List<Waypoint>> waypoints = new EnumMap<>(Waypoint.Type.class);

    // Y level every HOLDING_PATTERN point is placed/flown at, and which way
    // around the loop a plane flies it. Defaults chosen so a freshly created
    // layout is still flyable before the player touches these settings.
    private int holdingPatternHeight = 100;
    private boolean holdingPatternClockwise = true;

    // Gates are named endpoints, not part of a connected line, so they get
    // their own map instead of living in `waypoints`. LinkedHashMap keeps
    // them in the order they were placed, which is also the order the
    // map editor auto-names them in ("Gate A", "Gate B", ...).
    private final Map<String, BlockPos> gates = new LinkedHashMap<>();

    public AirportLayout(UUID id, String displayName, ResourceKey<Level> dimension) {
        this.id = id;
        this.displayName = displayName;
        this.dimension = dimension;
        for (Waypoint.Type type : Waypoint.Type.values()) {
            waypoints.put(type, new ArrayList<>());
        }
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public List<Waypoint> waypoints(Waypoint.Type type) {
        return waypoints.get(type);
    }

    public void setWaypoints(Waypoint.Type type, List<Waypoint> points) {
        waypoints.put(type, new ArrayList<>(points));
    }

    public int holdingPatternHeight() {
        return holdingPatternHeight;
    }

    public void setHoldingPatternHeight(int holdingPatternHeight) {
        this.holdingPatternHeight = holdingPatternHeight;
    }

    public boolean holdingPatternClockwise() {
        return holdingPatternClockwise;
    }

    public void setHoldingPatternClockwise(boolean holdingPatternClockwise) {
        this.holdingPatternClockwise = holdingPatternClockwise;
    }

    public Map<String, BlockPos> gates() {
        return gates;
    }

    public void setGates(Map<String, BlockPos> newGates) {
        gates.clear();
        gates.putAll(newGates);
    }

    /** "Gate A", "Gate B", ... "Gate Z", then "Gate AA" if you really place that many. */
    public String nextGateName() {
        int index = gates.size();
        StringBuilder suffix = new StringBuilder();
        do {
            suffix.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return "Gate " + suffix;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("displayName", displayName);
        tag.putString("dimension", dimension.location().toString());
        tag.putInt("holdingPatternHeight", holdingPatternHeight);
        tag.putBoolean("holdingPatternClockwise", holdingPatternClockwise);

        for (Waypoint.Type type : Waypoint.Type.values()) {
            ListTag list = new ListTag();
            for (Waypoint w : waypoints.get(type)) {
                list.add(w.save());
            }
            tag.put(type.name(), list);
        }

        ListTag gateList = new ListTag();
        for (Map.Entry<String, BlockPos> entry : gates.entrySet()) {
            CompoundTag gateTag = new CompoundTag();
            gateTag.putString("name", entry.getKey());
            gateTag.putInt("x", entry.getValue().getX());
            gateTag.putInt("y", entry.getValue().getY());
            gateTag.putInt("z", entry.getValue().getZ());
            gateList.add(gateTag);
        }
        tag.put("gates", gateList);

        return tag;
    }

    public static AirportLayout load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        String name = tag.getString("displayName");
        ResourceKey<Level> dimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("dimension")));

        AirportLayout layout = new AirportLayout(id, name, dimension);
        layout.holdingPatternHeight = tag.contains("holdingPatternHeight") ? tag.getInt("holdingPatternHeight") : layout.holdingPatternHeight;
        layout.holdingPatternClockwise = !tag.contains("holdingPatternClockwise") || tag.getBoolean("holdingPatternClockwise");
        for (Waypoint.Type type : Waypoint.Type.values()) {
            ListTag list = tag.getList(type.name(), 10); // 10 = CompoundTag id
            List<Waypoint> points = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                points.add(Waypoint.load(list.getCompound(i)));
            }
            layout.setWaypoints(type, points);
        }

        ListTag gateList = tag.getList("gates", 10);
        for (int i = 0; i < gateList.size(); i++) {
            CompoundTag gateTag = gateList.getCompound(i);
            layout.gates.put(gateTag.getString("name"),
                    new BlockPos(gateTag.getInt("x"), gateTag.getInt("y"), gateTag.getInt("z")));
        }

        return layout;
    }

    /** Network (de)serialization - see Waypoint#write/read for why this is
     *  separate from the NBT save()/load() pair above. */
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(displayName);
        buf.writeUtf(dimension.location().toString());
        buf.writeVarInt(holdingPatternHeight);
        buf.writeBoolean(holdingPatternClockwise);

        for (Waypoint.Type type : Waypoint.Type.values()) {
            List<Waypoint> list = waypoints.get(type);
            buf.writeVarInt(list.size());
            for (Waypoint w : list) w.write(buf);
        }

        buf.writeVarInt(gates.size());
        for (Map.Entry<String, BlockPos> entry : gates.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeBlockPos(entry.getValue());
        }
    }

    public static AirportLayout read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        ResourceKey<Level> dimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(buf.readUtf()));
        int holdingPatternHeight = buf.readVarInt();
        boolean holdingPatternClockwise = buf.readBoolean();

        AirportLayout layout = new AirportLayout(id, name, dimension);
        layout.holdingPatternHeight = holdingPatternHeight;
        layout.holdingPatternClockwise = holdingPatternClockwise;
        for (Waypoint.Type type : Waypoint.Type.values()) {
            int count = buf.readVarInt();
            List<Waypoint> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) points.add(Waypoint.read(buf));
            layout.setWaypoints(type, points);
        }

        int gateCount = buf.readVarInt();
        Map<String, BlockPos> gates = new LinkedHashMap<>();
        for (int i = 0; i < gateCount; i++) {
            gates.put(buf.readUtf(), buf.readBlockPos());
        }
        layout.setGates(gates);

        return layout;
    }
}
