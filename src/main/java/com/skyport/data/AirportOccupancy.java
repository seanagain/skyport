package com.skyport.data;

import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.logic.StopOccupancy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is using an airport right now, as a snapshot for the map screen.
 *
 * Two different kinds of fact live here and the screen has to tell them
 * apart, because they are not equally trustworthy.
 *
 * Runways, taxiways and helipads are CLAIMED: the traffic system hands out
 * clearances for them, so these are readings. The taxiway claim covers the
 * whole airport at once rather than a particular strip, which is a real
 * property of the traffic model and not a shortcut taken here - anything
 * showing it has to say "the taxiways", never "this taxiway".
 *
 * Gates are not claimed by anything, so who is at one is inferred from where
 * each aircraft is in its own schedule. See StopOccupancy.
 *
 * Schedules live on the autopilot block entity, so only aircraft whose chunk
 * is loaded can be read at all. An aeroplane asleep at the far side of the
 * world is routed here and will not appear. That is a limit worth stating on
 * the screen rather than quietly presenting a short list as a complete one,
 * which is what {@code fleetLoaded} and {@code fleetKnown} are for.
 */
public record AirportOccupancy(UUID airportId,
                               @Nullable Claim taxiways,
                               @Nullable Claim arrivalRunway,
                               @Nullable Claim departureRunway,
                               Map<String, Claim> pads,
                               Map<String, List<String>> occupants,
                               Map<String, List<StopOccupancy.Arrival>> expected,
                               int fleetLoaded,
                               int fleetKnown) {

    /**
     * A held clearance.
     *
     * {@code live} is the part worth having. Every clearance is a lease, not
     * a lock - a holder re-confirms it each tick and one that has gone quiet
     * is already free for the taking. A stale entry and a genuinely occupied
     * runway look identical in the map it is stored in, and they mean
     * opposite things to somebody waiting for it.
     */
    public record Claim(String callsign, boolean live) {

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(callsign);
            buf.writeBoolean(live);
        }

        static Claim read(FriendlyByteBuf buf) {
            return new Claim(buf.readUtf(), buf.readBoolean());
        }
    }

    /** What the screen shows before the server has answered. */
    public static AirportOccupancy empty(UUID airportId) {
        return new AirportOccupancy(airportId, null, null, null,
                Map.of(), Map.of(), Map.of(), 0, 0);
    }

    // ---- building it on the server --------------------------------------

    public static AirportOccupancy of(ServerLevel level, AirportLayout airport) {
        AirportRegistry registry = AirportRegistry.get(level);
        long now = level.getGameTime();
        UUID id = airport.id();

        List<StopOccupancy.Aircraft> fleet = new ArrayList<>();
        int loaded = 0;
        for (AirportRegistry.KnownAircraft known : registry.known()) {
            AutopilotBlockEntity autopilot = AutopilotBlockEntity.loaded(known.planeId());
            if (autopilot == null) continue;
            loaded++;
            fleet.add(describe(autopilot, airport));
        }

        Map<String, Claim> pads = new LinkedHashMap<>();
        for (String pad : airport.helipads().keySet()) {
            Claim claim = claimOf(registry, registry.padHolder(id, pad), now);
            if (claim != null) pads.put(pad, claim);
        }

        return new AirportOccupancy(id,
                claimOf(registry, registry.taxiwayHolder(id), now),
                claimOf(registry, registry.trafficHolder(id, AirportRegistry.ARRIVAL_RUNWAY), now),
                claimOf(registry, registry.trafficHolder(id, AirportRegistry.DEPARTURE_RUNWAY), now),
                pads,
                StopOccupancy.occupants(fleet),
                StopOccupancy.expected(fleet),
                loaded,
                registry.known().size());
    }

    /**
     * One aircraft, reduced to what this airport cares about: whether it is
     * sitting at one of these stops, and which of them its route visits.
     */
    private static StopOccupancy.Aircraft describe(AutopilotBlockEntity autopilot,
                                                   AirportLayout airport) {
        UUID airportId = airport.id();
        FlightSchedule schedule = autopilot.schedule();

        // Parked here means waiting at a gate on THIS airport - not merely
        // heading for one. A craft on approach is coming, not occupying.
        String parkedAt = null;
        int index = autopilot.currentStopIndex();
        if (autopilot.flightState() == AutopilotBlockEntity.FlightState.WAITING
                && index >= 0 && index < schedule.entries().size()) {
            ScheduleEntry here = schedule.entries().get(index);
            if (!here.isVor() && airportId.equals(here.airportId())) parkedAt = here.gateName();
        }

        // Walk the route from where it is now, counting airport stops rather
        // than list positions: a VOR is flown over on the way somewhere and
        // is not an arrival anyone is waiting for, so counting it would put
        // every gate one further off than it is.
        List<StopOccupancy.Leg> legs = new ArrayList<>();
        int at = index;
        int airportsPassed = 0;
        for (int step = 0; step < schedule.entries().size() && at >= 0; step++) {
            ScheduleEntry entry = schedule.entries().get(at);
            if (!entry.isVor()) {
                if (airportId.equals(entry.airportId()) && !entry.gateName().isEmpty()) {
                    legs.add(new StopOccupancy.Leg(entry.gateName(), airportsPassed,
                            airportsPassed == 0
                                    ? etaSeconds(autopilot, airport, entry.gateName())
                                    : -1));
                }
                airportsPassed++;
            }
            at = schedule.nextIndex(at);
        }
        return new StopOccupancy.Aircraft(autopilot.callsign(), parkedAt, legs);
    }

    /**
     * Roughly how long until it gets here, for the leg it is flying now.
     *
     * Straight-line distance over cruise speed, which is an estimate and
     * reads like one on screen. Only offered while it is actually in the air:
     * an aircraft taxiing is moving at a different speed entirely, and one
     * sitting at a gate somewhere else has a departure condition between it
     * and here that no arithmetic is going to predict.
     *
     * @return seconds, or negative when there is no honest number
     */
    private static double etaSeconds(AutopilotBlockEntity autopilot, AirportLayout airport,
                                     String stopName) {
        if (!autopilot.flightState().airborne()) return -1;
        net.minecraft.core.BlockPos target = airport.gates().get(stopName);
        if (target == null) target = airport.helipads().get(stopName);
        if (target == null) return -1;

        int speed = autopilot.schedule().cruiseSpeed();
        if (speed <= 0) return -1;
        double distance = Math.sqrt(autopilot.craftPosition().distSqr(target));
        return distance / speed;
    }

    @Nullable
    private static Claim claimOf(AirportRegistry registry, @Nullable UUID holder, long now) {
        if (holder == null) return null;
        String callsign = registry.knownById(holder)
                .map(AirportRegistry.KnownAircraft::callsign)
                .orElse("unknown aircraft");
        return new Claim(callsign, registry.clearanceIsLive(holder, now));
    }

    // ---- the wire --------------------------------------------------------

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(airportId);
        writeClaim(buf, taxiways);
        writeClaim(buf, arrivalRunway);
        writeClaim(buf, departureRunway);
        buf.writeVarInt(pads.size());
        pads.forEach((name, claim) -> {
            buf.writeUtf(name);
            claim.write(buf);
        });
        writeLists(buf, occupants);
        buf.writeVarInt(expected.size());
        expected.forEach((name, arrivals) -> {
            buf.writeUtf(name);
            buf.writeVarInt(arrivals.size());
            arrivals.forEach(arrival -> {
                buf.writeUtf(arrival.callsign());
                buf.writeUtf(arrival.when());
            });
        });
        buf.writeVarInt(fleetLoaded);
        buf.writeVarInt(fleetKnown);
    }

    public static AirportOccupancy read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        Claim taxiways = readClaim(buf);
        Claim arrival = readClaim(buf);
        Claim departure = readClaim(buf);
        int padCount = buf.readVarInt();
        Map<String, Claim> pads = new LinkedHashMap<>();
        for (int i = 0; i < padCount; i++) pads.put(buf.readUtf(), Claim.read(buf));
        Map<String, List<String>> occupants = readLists(buf);
        int expectedCount = buf.readVarInt();
        Map<String, List<StopOccupancy.Arrival>> expected = new LinkedHashMap<>();
        for (int i = 0; i < expectedCount; i++) {
            String name = buf.readUtf();
            int size = buf.readVarInt();
            List<StopOccupancy.Arrival> arrivals = new ArrayList<>(size);
            for (int j = 0; j < size; j++) {
                arrivals.add(new StopOccupancy.Arrival(buf.readUtf(), buf.readUtf()));
            }
            expected.put(name, arrivals);
        }
        return new AirportOccupancy(id, taxiways, arrival, departure, pads, occupants, expected,
                buf.readVarInt(), buf.readVarInt());
    }

    private static void writeClaim(FriendlyByteBuf buf, @Nullable Claim claim) {
        buf.writeBoolean(claim != null);
        if (claim != null) claim.write(buf);
    }

    @Nullable
    private static Claim readClaim(FriendlyByteBuf buf) {
        return buf.readBoolean() ? Claim.read(buf) : null;
    }

    private static void writeLists(FriendlyByteBuf buf, Map<String, List<String>> source) {
        buf.writeVarInt(source.size());
        source.forEach((name, callsigns) -> {
            buf.writeUtf(name);
            buf.writeVarInt(callsigns.size());
            callsigns.forEach(buf::writeUtf);
        });
    }

    private static Map<String, List<String>> readLists(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf();
            int size = buf.readVarInt();
            List<String> callsigns = new ArrayList<>(size);
            for (int j = 0; j < size; j++) callsigns.add(buf.readUtf());
            out.put(name, callsigns);
        }
        return out;
    }
}
