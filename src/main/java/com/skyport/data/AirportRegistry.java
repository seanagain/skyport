package com.skyport.data;

import com.skyport.Skyport;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The world-level list of every airport that exists, keyed by a random id
 * assigned when its station block is placed. This is what makes "pick any
 * airport, anywhere" possible in the autopilot GUI, instead of only being
 * able to fly to airports within loaded chunks.
 *
 * Always stored on the Overworld (see {@link #get}) so it survives even if
 * an airport itself is in the Nether/End and that dimension isn't loaded.
 *
 * NB: SavedData's exact save/load signature has moved around between
 * Minecraft versions (HolderLookup.Provider was added fairly recently).
 * Double check this against the current net.minecraft.world.level.saveddata
 * package when you get to implementing this for real - treat this file as
 * "the shape of the idea", not a guaranteed-compiling final version.
 */
public class AirportRegistry extends SavedData {

    private static final String DATA_NAME = Skyport.MOD_ID + "_airports";

    public static final SavedData.Factory<AirportRegistry> FACTORY =
            new SavedData.Factory<>(AirportRegistry::new, AirportRegistry::load, null);

    private final Map<UUID, AirportLayout> airports = new HashMap<>();

    public static AirportRegistry get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<AirportLayout> byId(UUID id) {
        return Optional.ofNullable(airports.get(id));
    }

    public Collection<AirportLayout> all() {
        return airports.values();
    }

    public void put(AirportLayout layout) {
        airports.put(layout.id(), layout);
        setDirty();
    }

    public void remove(UUID id) {
        airports.remove(id);
        setDirty();
    }

    /**
     * Which plane currently has the run of each airport - the smallest thing
     * that counts as air traffic control.
     *
     * One clearance covers the whole airport, not the runway alone, because
     * at a small field the taxiway and runway are often the same strip: two
     * planes on it cannot pass each other, so letting one taxi while another
     * lands just moves the collision. A departure holds this from pushback
     * until it is airborne; an arrival holds it from final approach until it
     * is parked at the gate. Between those, at the gate, it is free.
     *
     * Deliberately NOT persisted: it describes planes moving right now, and
     * a clearance surviving a restart would block an airport forever with no
     * plane left to release it.
     */
    private final transient Map<UUID, UUID> trafficClearances = new HashMap<>();

    /**
     * Ask for the run of an airport. Granted if nobody else holds it, or if
     * this plane already does (so re-asking every tick is harmless). A
     * refusal means wait - hold overhead if airborne, stay at the gate if not.
     */
    public boolean tryClaimTraffic(UUID airportId, UUID planeId) {
        UUID holder = trafficClearances.get(airportId);
        if (holder != null && !holder.equals(planeId)) return false;
        trafficClearances.put(airportId, planeId);
        return true;
    }

    /** Give the airport back - parked at a gate, safely airborne, or disengaged. */
    public void releaseTraffic(UUID airportId, UUID planeId) {
        trafficClearances.remove(airportId, planeId);
    }

    /**
     * Where every airborne plane currently is, for separation checks.
     *
     * Transient for the same reason as the clearances: a stale position from
     * before a restart would have live aircraft dodging a ghost.
     */
    private final transient Map<UUID, Vec3> airborneTraffic = new HashMap<>();

    public void reportAirborne(UUID planeId, Vec3 position) {
        airborneTraffic.put(planeId, position);
    }

    public void clearAirborne(UUID planeId) {
        airborneTraffic.remove(planeId);
    }

    public Map<UUID, Vec3> airborneTraffic() {
        return airborneTraffic;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (AirportLayout layout : airports.values()) {
            list.add(layout.save());
        }
        tag.put("airports", list);
        return tag;
    }

    private static AirportRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        AirportRegistry registry = new AirportRegistry();
        ListTag list = tag.getList("airports", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            AirportLayout layout = AirportLayout.load(list.getCompound(i));
            registry.airports.put(layout.id(), layout);
        }
        return registry;
    }
}
