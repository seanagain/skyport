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
     * When each clearance was last confirmed by the plane holding it.
     *
     * A clearance is a lease, not a lock. Every path that hands one back can
     * be skipped - the block is broken mid-flight, its chunk unloads, the
     * server stops - and a clearance nobody is left to release locks the
     * airport permanently, with every other aircraft circling a field that is
     * actually empty. Holders re-confirm each tick (see heartbeat), so a
     * clearance whose holder has gone quiet can simply be taken.
     */
    private final transient Map<UUID, Long> clearanceSeen = new HashMap<>();

    /** How long a holder can go silent before its clearance is up for grabs. */
    private static final long CLEARANCE_TIMEOUT_TICKS = 200; // 10 seconds

    private boolean available(Map<UUID, UUID> clearances, UUID airportId, UUID planeId, long now) {
        UUID holder = clearances.get(airportId);
        if (holder == null || holder.equals(planeId)) return true;
        Long seen = clearanceSeen.get(holder);
        return seen == null || now - seen > CLEARANCE_TIMEOUT_TICKS;
    }

    /** Called every tick by a plane holding any clearance, to keep its lease
     *  alive. Silence is what lets a stuck clearance be reclaimed. */
    public void heartbeat(UUID planeId, long now) {
        clearanceSeen.put(planeId, now);
    }

    /**
     * Ask for the run of an airport. Granted if nobody else holds it, if this
     * plane already does (so re-asking every tick is harmless), or if the
     * current holder has gone silent long enough to be presumed gone.
     */
    public boolean tryClaimTraffic(UUID airportId, UUID planeId, long now) {
        if (!available(trafficClearances, airportId, planeId, now)) return false;
        trafficClearances.put(airportId, planeId);
        clearanceSeen.put(planeId, now);
        return true;
    }

    /** Give the airport back - parked at a gate, safely airborne, or disengaged. */
    public void releaseTraffic(UUID airportId, UUID planeId) {
        trafficClearances.remove(airportId, planeId);
    }

    /**
     * Who is on the taxiway - the stretch between the gates and the hold
     * point. Separate from the runway clearance so a plane can be waiting at
     * the hold line while another is taking off, but still only one plane at
     * a time, because a shared strip has nowhere to pass.
     */
    private final transient Map<UUID, UUID> taxiwayClearances = new HashMap<>();

    public boolean tryClaimTaxiway(UUID airportId, UUID planeId, long now) {
        if (!available(taxiwayClearances, airportId, planeId, now)) return false;
        taxiwayClearances.put(airportId, planeId);
        clearanceSeen.put(planeId, now);
        return true;
    }

    public void releaseTaxiway(UUID airportId, UUID planeId) {
        taxiwayClearances.remove(airportId, planeId);
    }

    /**
     * Take the runway and the taxiway together, or neither.
     *
     * Arrivals have to claim both up front, and this is a deadlock fix, not
     * caution: a departure takes the taxiway then wants the runway, so an
     * arrival taking them in the opposite order - runway first, then the
     * taxiway to reach a gate - is the classic cycle where each holds what
     * the other needs. Grabbing both atomically means an arrival that cannot
     * have the whole path simply stays in the pattern, holding nothing.
     */
    public boolean tryClaimArrival(UUID airportId, UUID planeId, long now) {
        if (!available(trafficClearances, airportId, planeId, now)) return false;
        if (!available(taxiwayClearances, airportId, planeId, now)) return false;
        trafficClearances.put(airportId, planeId);
        taxiwayClearances.put(airportId, planeId);
        clearanceSeen.put(planeId, now);
        return true;
    }

    /**
     * Where every airborne plane currently is, for separation checks.
     *
     * Transient for the same reason as the clearances: a stale position from
     * before a restart would have live aircraft dodging a ghost.
     */
    private final transient Map<UUID, TrafficReport> airborneTraffic = new HashMap<>();

    public void reportAirborne(TrafficReport report) {
        airborneTraffic.put(report.planeId(), report);
    }

    public void clearAirborne(UUID planeId) {
        airborneTraffic.remove(planeId);
    }

    public Map<UUID, TrafficReport> airborneTraffic() {
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
