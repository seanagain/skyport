package com.skyport.data;

import com.skyport.Skyport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.ArrayList;
import java.util.List;
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
 * Also holds the live traffic picture and the clearances that keep aircraft
 * out of each other's way. Those are transient - see each field for why -
 * with the exception of parked aircraft, which must survive precisely
 * because a parked aircraft stops ticking and cannot report itself.
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
     * Who is on each helipad, keyed by airport and pad name.
     *
     * Deliberately its own thing, sharing nothing with the runway and taxiway
     * clearances. A helicopter landing on a pad doesn't stop a plane using
     * the runway, and vice versa - they don't occupy the same ground - so
     * folding pads into the airport-wide clearance would serialise aircraft
     * that never actually conflict. One pad, one aircraft; nothing more.
     */
    private final transient Map<String, UUID> padClaims = new HashMap<>();

    private static String padKey(UUID airportId, String padName) {
        return airportId + "/" + padName;
    }

    /** Claim a pad to land on, or keep one already held. Refused if another
     *  aircraft is parked there and still reporting. */
    public boolean tryClaimPad(UUID airportId, String padName, UUID planeId, long now) {
        String key = padKey(airportId, padName);
        UUID holder = padClaims.get(key);
        if (holder != null && !holder.equals(planeId)) {
            Long seen = clearanceSeen.get(holder);
            if (seen != null && now - seen <= CLEARANCE_TIMEOUT_TICKS) return false;
        }
        padClaims.put(key, planeId);
        clearanceSeen.put(planeId, now);
        return true;
    }

    /** Free a pad - on lifting off from it, or on disengaging. */
    public void releasePad(UUID airportId, String padName, UUID planeId) {
        padClaims.remove(padKey(airportId, padName), planeId);
    }

    /** Who is sitting on this pad, if anyone. */
    @org.jetbrains.annotations.Nullable
    public UUID padHolder(UUID airportId, String padName) {
        return padClaims.get(padKey(airportId, padName));
    }

    /** Who currently holds the runway here, if anyone - so a plane stuck in
     *  the pattern can say what it's waiting for instead of just circling. */
    @org.jetbrains.annotations.Nullable
    public UUID trafficHolder(UUID airportId) {
        return trafficClearances.get(airportId);
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
     * Where aircraft are parked with a schedule still to fly.
     *
     * Unlike everything else in this class's runtime state, this IS persisted
     * - and it has to be. A parked aircraft releases its chunks, so its block
     * entity stops ticking entirely, so it cannot report itself, wake itself,
     * or be found by anything that only looks at loaded chunks. Without a
     * written-down list, a fleet parked at airports nobody is standing in
     * would be invisible and unreachable until a player walked there.
     */
    private final Map<UUID, ParkedAircraft> parked = new HashMap<>();

    /** A parked aircraft: enough to find it again with the world unloaded. */
    /**
     * A parked aircraft: enough to find it again with the world unloaded,
     * and enough to list it.
     *
     * Carries a callsign and a destination as well as a position because a
     * parked aircraft used to vanish from the tower entirely. It stops
     * ticking, so it stops publishing a TrafficReport, so it dropped off the
     * ATC map and the departure boards until something woke it - which read
     * as aircraft randomly disappearing and reappearing. It is still traffic;
     * it is just traffic that is standing still.
     */
    public record ParkedAircraft(UUID planeId, String dimension, BlockPos position,
                                 String callsign, String destination) { }

    /**
     * When each parked aircraft last reported in.
     *
     * A parked aircraft that is still ticking and one whose chunks have been
     * released look identical in the list above - both are simply "parked" -
     * but only the second needs waking, and the tower is where someone would
     * go to find that out. Transient because it means nothing after a
     * restart: everything is asleep then, which is precisely what the absence
     * of an entry says.
     */
    private final transient Map<UUID, Long> parkedLastSeen = new HashMap<>();

    /** Longer than the report interval, short enough that an aircraft whose
     *  chunks were just released shows as asleep almost immediately. */
    private static final long PARKED_AWAKE_WINDOW_TICKS = 40;

    public void reportParked(UUID planeId, String dimension, BlockPos position,
                             String callsign, String destination, long now) {
        // Always freshen the heartbeat, even when nothing persisted changed -
        // "still here" is the whole point of it.
        parkedLastSeen.put(planeId, now);

        ParkedAircraft existing = parked.get(planeId);
        if (existing != null && existing.position().equals(position)
                && existing.callsign().equals(callsign)
                && existing.destination().equals(destination)) return;
        parked.put(planeId, new ParkedAircraft(planeId, dimension, position, callsign, destination));
        setDirty();
    }

    /** Is this parked aircraft still ticking, or have its chunks gone? */
    public boolean isAwake(UUID planeId, long now) {
        Long seen = parkedLastSeen.get(planeId);
        return seen != null && now - seen <= PARKED_AWAKE_WINDOW_TICKS;
    }

    /** A parked aircraft by id, for waking one on request. */
    public Optional<ParkedAircraft> parkedById(UUID planeId) {
        return Optional.ofNullable(parked.get(planeId));
    }

    /**
     * Everything the tower should be able to see: what is moving, plus what
     * is standing at a gate. Parked entries are synthesised rather than
     * stored as reports, since a TrafficReport is explicitly a live snapshot
     * and these aircraft are not running.
     */
    public List<TrafficReport> allTraffic(long now) {
        List<TrafficReport> all = new ArrayList<>();

        // An aircraft that stops reporting has NOT gone anywhere - almost
        // always its chunks were released and it is frozen mid-flight,
        // exactly where it was. Dropping it from the board made aircraft
        // vanish off the map and reappear minutes later when something else
        // happened to load that patch of world, which reads as the mod losing
        // track of them. It keeps its last known position and says it is
        // resting, which is the truth.
        for (TrafficReport report : airborneTraffic.values()) {
            Long seen = airborneSeen.get(report.planeId());
            boolean live = seen != null && now - seen <= PARKED_AWAKE_WINDOW_TICKS;
            all.add(live ? report : new TrafficReport(report.planeId(), report.callsign(),
                    "ASLEEP", report.position(), report.destination(), false));
        }

        for (ParkedAircraft aircraft : parked.values()) {
            if (airborneTraffic.containsKey(aircraft.planeId())) continue;
            // ASLEEP is not a flight state - it is the absence of one. The
            // aircraft is parked either way; the difference is whether
            // anything is running to notice, and that is what decides
            // whether the tower can offer to wake it.
            String state = isAwake(aircraft.planeId(), now) ? "WAITING" : "ASLEEP";
            all.add(new TrafficReport(aircraft.planeId(), aircraft.callsign(), state,
                    Vec3.atCenterOf(aircraft.position()), aircraft.destination(), false));
        }
        return all;
    }

    public void clearParked(UUID planeId) {
        if (parked.remove(planeId) != null) setDirty();
    }

    public Collection<ParkedAircraft> parked() {
        return parked.values();
    }

    /**
     * Where every airborne plane currently is, for separation checks.
     *
     * Transient for the same reason as the clearances: a stale position from
     * before a restart would have live aircraft dodging a ghost.
     */
    private final transient Map<UUID, TrafficReport> airborneTraffic = new HashMap<>();

    /** When each airborne report last arrived - see allTraffic. */
    private final transient Map<UUID, Long> airborneSeen = new HashMap<>();

    public void reportAirborne(TrafficReport report, long now) {
        airborneTraffic.put(report.planeId(), report);
        airborneSeen.put(report.planeId(), now);
    }

    /**
     * Take an aircraft off the board for good - it parked, or disengaged.
     *
     * Deliberately the only way an entry leaves. An aircraft that simply
     * stops reporting is NOT removed: see allTraffic for why.
     */
    public void clearAirborne(UUID planeId) {
        airborneTraffic.remove(planeId);
        airborneSeen.remove(planeId);
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

        ListTag parkedList = new ListTag();
        for (ParkedAircraft aircraft : parked.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("planeId", aircraft.planeId());
            entry.putString("dimension", aircraft.dimension());
            entry.putInt("x", aircraft.position().getX());
            entry.putInt("y", aircraft.position().getY());
            entry.putInt("z", aircraft.position().getZ());
            entry.putString("callsign", aircraft.callsign());
            entry.putString("destination", aircraft.destination());
            parkedList.add(entry);
        }
        tag.put("parked", parkedList);
        return tag;
    }

    private static AirportRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        AirportRegistry registry = new AirportRegistry();
        ListTag list = tag.getList("airports", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            AirportLayout layout = AirportLayout.load(list.getCompound(i));
            registry.airports.put(layout.id(), layout);
        }

        ListTag parkedList = tag.getList("parked", 10);
        for (int i = 0; i < parkedList.size(); i++) {
            CompoundTag entry = parkedList.getCompound(i);
            UUID id = entry.getUUID("planeId");
            // Worlds saved before parked aircraft carried a callsign read as
            // empty strings rather than failing to load.
            registry.parked.put(id, new ParkedAircraft(id, entry.getString("dimension"),
                    new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z")),
                    entry.getString("callsign"), entry.getString("destination")));
        }
        return registry;
    }
}
