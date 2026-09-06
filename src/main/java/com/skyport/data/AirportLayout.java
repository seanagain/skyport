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
    /** Where the station block that owns this airport stands, so a layout
     *  whose station has gone can be spotted and cleaned up. */
    private BlockPos stationPos = BlockPos.ZERO;

    // Waypoints grouped by type, each list already sorted by Waypoint#order.
    private final Map<Waypoint.Type, List<Waypoint>> waypoints = new EnumMap<>(Waypoint.Type.class);

    // Y level every HOLDING_PATTERN point is placed/flown at, and which way
    // around the loop a plane flies it. Defaults chosen so a freshly created
    // layout is still flyable before the player touches these settings.
    private int holdingPatternHeight = 100;
    /**
     * How fast aircraft taxi here, in blocks per second.
     *
     * Per-airport rather than a global constant because airports differ: a
     * long field with wide taxiways can move traffic briskly, and a cramped
     * one where the taxiway doubles as the runway wants everything slow
     * enough to stop. It also sets pushback, proportionally - reversing off a
     * stand should read as more deliberate than taxiing whatever the speed.
     */
    private int taxiSpeed = 4;
    private boolean holdingPatternClockwise = true;

    // Gates are named endpoints, not part of a connected line, so they get
    // their own map instead of living in `waypoints`. LinkedHashMap keeps
    // them in the order they were placed, which is also the order the
    // map editor auto-names them in ("Gate A", "Gate B", ...).
    private final Map<String, BlockPos> gates = new LinkedHashMap<>();

    // Helipads, named and ordered the same way gates are - they serve the
    // same purpose for rotorcraft and airships, which never touch a runway.
    // Kept separate rather than mixed in with gates so a schedule can only
    // ever send a plane to a gate and a helicopter to a pad.
    private final Map<String, BlockPos> helipads = new LinkedHashMap<>();

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

    public BlockPos stationPos() {
        return stationPos;
    }

    public void setStationPos(BlockPos stationPos) {
        this.stationPos = stationPos;
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

    /**
     * The runway arrivals land on: always the first pair drawn.
     *
     * Runways are stored as pairs in the RUNWAY list, exactly like taxiway
     * segments - index 0 of each pair is its gate end, index 1 the far end.
     * One pair means one runway used for everything, which is what every
     * airport drawn before this was.
     */
    public List<BlockPos> arrivalRunway() {
        List<Waypoint> points = waypoints(Waypoint.Type.RUNWAY);
        if (points.size() < 2) return List.of();
        return List.of(points.get(0).pos(), points.get(1).pos());
    }

    /**
     * The runway departures roll down - the second pair if one was drawn,
     * otherwise the same strip everyone else uses.
     *
     * Deliberately the whole of the feature: a second runway is a place for
     * departures to go so they stop queueing behind arrivals. It gets no
     * pattern, no final leg and no taxi rerouting of its own, because none of
     * those are what makes an airport with one runway feel congested.
     */
    public List<BlockPos> departureRunway() {
        List<Waypoint> points = waypoints(Waypoint.Type.RUNWAY);
        if (points.size() >= 4) return List.of(points.get(2).pos(), points.get(3).pos());
        return arrivalRunway();
    }

    /** Does this airport separate departures onto their own strip? */
    public boolean hasDepartureRunway() {
        return waypoints(Waypoint.Type.RUNWAY).size() >= 4;
    }

    /** How many runways are drawn, rounded down - a half-finished second one
     *  does not count until both its ends exist. */
    public int runwayCount() {
        return waypoints(Waypoint.Type.RUNWAY).size() / 2;
    }

    /**
     * Place one end of a specific runway, leaving the other alone.
     *
     * The editor draws runways by index rather than by appending, because
     * with two of them the player picks which one they are editing. Slots
     * before the one being written are padded so index 2 always means the
     * departure runway even if the arrival runway is half drawn - otherwise
     * points would silently shift roles as the list grew.
     */
    public void setRunwayPoint(int runwayIndex, int end, BlockPos pos) {
        List<Waypoint> points = waypoints(Waypoint.Type.RUNWAY);
        int slot = runwayIndex * 2 + end;
        while (points.size() <= slot) {
            points.add(new Waypoint(pos, Waypoint.Type.RUNWAY, points.size()));
        }
        points.set(slot, new Waypoint(pos, Waypoint.Type.RUNWAY, slot));
    }

    /** Drop everything past the given number of runways - used when the
     *  editor is toggled back down to one. */
    public void trimRunwaysTo(int runways) {
        List<Waypoint> points = waypoints(Waypoint.Type.RUNWAY);
        while (points.size() > runways * 2) points.remove(points.size() - 1);
    }

    public int taxiSpeed() {
        return taxiSpeed;
    }

    public void setTaxiSpeed(int taxiSpeed) {
        this.taxiSpeed = Math.max(1, Math.min(20, taxiSpeed));
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

    public Map<String, BlockPos> helipads() {
        return helipads;
    }

    public void setHelipads(Map<String, BlockPos> newPads) {
        helipads.clear();
        helipads.putAll(newPads);
    }

    /** "Pad A", "Pad B", ... matching the gate naming scheme. */
    public String nextHelipadName() {
        int index = helipads.size();
        StringBuilder suffix = new StringBuilder();
        do {
            suffix.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return "Pad " + suffix;
    }

    public void setGates(Map<String, BlockPos> newGates) {
        gates.clear();
        gates.putAll(newGates);
    }

    /**
     * Rename a gate, keeping it where it sits in the order.
     *
     * The obvious implementation - remove and put - moves the gate to the
     * end, because this is a LinkedHashMap and that is what insertion order
     * means. Renaming Gate A would silently reshuffle the board. So the map
     * is rebuilt in place instead.
     *
     * Returns false and changes nothing if the new name is blank or already
     * taken; the caller reports that rather than this quietly merging two
     * stands into one.
     *
     * Schedules refer to gates BY NAME, so a rename orphans any stop
     * pointing at the old one. Nothing here can reach those - they live on
     * aircraft anywhere in the world - which is why the autopilot now says
     * so plainly when it cannot find its gate rather than behaving as though
     * it had arrived.
     */
    public boolean renameGate(String from, String to) {
        if (to == null || to.isBlank() || !gates.containsKey(from)) return false;
        String trimmed = to.strip();
        if (trimmed.equals(from)) return true;
        if (gates.containsKey(trimmed)) return false;

        Map<String, BlockPos> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> entry : gates.entrySet()) {
            if (entry.getKey().equals(from)) rebuilt.put(trimmed, entry.getValue());
            else rebuilt.put(entry.getKey(), entry.getValue());
        }
        gates.clear();
        gates.putAll(rebuilt);
        return true;
    }

    /**
     * Move a gate up or down the list.
     *
     * The order is what the Autopilot's gate picker cycles through, so it is
     * worth being able to put the stands in the order they actually run down
     * the apron rather than the order they were clicked.
     */
    public boolean moveGate(String name, int delta) {
        List<String> order = new ArrayList<>(gates.keySet());
        int from = order.indexOf(name);
        if (from < 0) return false;
        int to = from + delta;
        if (to < 0 || to >= order.size()) return false;

        order.remove(from);
        order.add(to, name);

        Map<String, BlockPos> rebuilt = new LinkedHashMap<>();
        for (String key : order) rebuilt.put(key, gates.get(key));
        gates.clear();
        gates.putAll(rebuilt);
        return true;
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
        tag.putInt("taxiSpeed", taxiSpeed);
        tag.putBoolean("holdingPatternClockwise", holdingPatternClockwise);
        tag.putLong("stationPos", stationPos.asLong());

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

        ListTag padList = new ListTag();
        for (Map.Entry<String, BlockPos> entry : helipads.entrySet()) {
            CompoundTag padTag = new CompoundTag();
            padTag.putString("name", entry.getKey());
            padTag.putInt("x", entry.getValue().getX());
            padTag.putInt("y", entry.getValue().getY());
            padTag.putInt("z", entry.getValue().getZ());
            padList.add(padTag);
        }
        tag.put("helipads", padList);

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
        // Absent on airports drawn before taxi speed was settable, which is
        // exactly the default they were flying at.
        if (tag.contains("taxiSpeed")) layout.taxiSpeed = tag.getInt("taxiSpeed");
        layout.holdingPatternClockwise = !tag.contains("holdingPatternClockwise") || tag.getBoolean("holdingPatternClockwise");
        if (tag.contains("stationPos")) layout.stationPos = BlockPos.of(tag.getLong("stationPos"));
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

        ListTag padList = tag.getList("helipads", 10);
        for (int i = 0; i < padList.size(); i++) {
            CompoundTag padTag = padList.getCompound(i);
            layout.helipads.put(padTag.getString("name"),
                    new BlockPos(padTag.getInt("x"), padTag.getInt("y"), padTag.getInt("z")));
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
        buf.writeVarInt(taxiSpeed);
        buf.writeBoolean(holdingPatternClockwise);
        buf.writeBlockPos(stationPos);

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

        buf.writeVarInt(helipads.size());
        for (Map.Entry<String, BlockPos> entry : helipads.entrySet()) {
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
        int taxiSpeed = buf.readVarInt();
        boolean holdingPatternClockwise = buf.readBoolean();
        BlockPos stationPos = buf.readBlockPos();

        AirportLayout layout = new AirportLayout(id, name, dimension);
        layout.holdingPatternHeight = holdingPatternHeight;
        layout.taxiSpeed = taxiSpeed;
        layout.holdingPatternClockwise = holdingPatternClockwise;
        layout.stationPos = stationPos;
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

        int padCount = buf.readVarInt();
        Map<String, BlockPos> pads = new LinkedHashMap<>();
        for (int i = 0; i < padCount; i++) {
            pads.put(buf.readUtf(), buf.readBlockPos());
        }
        layout.setHelipads(pads);

        return layout;
    }
}
