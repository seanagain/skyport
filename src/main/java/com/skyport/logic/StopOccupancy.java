package com.skyport.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which aircraft are at an airport's gates and pads, which are coming, and
 * in what order.
 *
 * Gates are not reserved by anything. The traffic system claims runways, the
 * taxiways as a set, and helipads - that is the whole list, so there is no
 * gate clearance to read. What there is instead is an aircraft sitting at a
 * gate, and a set of schedules that say who else is due, and both can be
 * worked out from where each aircraft is in its own route.
 *
 * So this is inference rather than a reading, and it is kept in one place
 * with its own tests because being wrong means telling somebody a gate is
 * free while an aeroplane is parked on it.
 *
 * The order is the useful part and also the part that cannot be exact. An
 * aircraft on its way here can be given a real estimate; one that reaches
 * this gate in three stops' time cannot, because what happens in between
 * depends on gate waits, traffic and a cargo condition nobody may come to
 * satisfy. Rather than invent a number for that, the ordering falls back to
 * how far down the schedule it is, and says so.
 */
public final class StopOccupancy {

    private StopOccupancy() { }

    /**
     * One visit an aircraft's schedule makes to a stop at this airport.
     *
     * @param stop       the gate or pad name
     * @param stopsAway  how many stops ahead of where it is now, 0 meaning
     *                   this is the one it is heading for right now
     * @param etaSeconds an estimate for the leg it is flying, or negative
     *                   when there is no honest number to give
     */
    public record Leg(String stop, int stopsAway, double etaSeconds) { }

    /**
     * @param callsign what to show
     * @param parkedAt the stop at THIS airport it is sitting at, or null
     * @param legs     every visit its route makes to a stop here
     */
    public record Aircraft(String callsign, String parkedAt, List<Leg> legs) { }

    /**
     * An aircraft due at a stop, and how to describe when.
     *
     * {@code when} is a phrase rather than a number because the three cases
     * are genuinely different kinds of answer - "here", "about 40s" and "2
     * stops" - and flattening them into one unit would be presenting a guess
     * with the same confidence as a measurement.
     */
    public record Arrival(String callsign, String when) { }

    /** Sorts the way somebody reads the list: who has it now, who is nearly
     *  here, then everyone else by how far off they are. */
    private record Ranked(Arrival arrival, int rank, double tiebreak) { }

    public static Map<String, List<String>> occupants(List<Aircraft> fleet) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Aircraft aircraft : fleet) {
            if (aircraft.parkedAt() == null || aircraft.parkedAt().isEmpty()) continue;
            out.computeIfAbsent(aircraft.parkedAt(), stop -> new ArrayList<>())
                    .add(aircraft.callsign());
        }
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    /**
     * Who is due at each stop, soonest first.
     *
     * Only the NEXT visit each aircraft makes to a stop counts. A shuttle
     * looping through the same gate four times is one aeroplane due once,
     * and listing it four times would read as four arrivals to make room
     * for.
     */
    public static Map<String, List<Arrival>> expected(List<Aircraft> fleet) {
        Map<String, List<Ranked>> gathered = new LinkedHashMap<>();

        for (Aircraft aircraft : fleet) {
            Map<String, Leg> soonest = new LinkedHashMap<>();
            for (Leg leg : aircraft.legs()) {
                if (leg.stop() == null || leg.stop().isEmpty()) continue;
                Leg already = soonest.get(leg.stop());
                if (already == null || leg.stopsAway() < already.stopsAway()) {
                    soonest.put(leg.stop(), leg);
                }
            }

            for (Map.Entry<String, Leg> entry : soonest.entrySet()) {
                Leg leg = entry.getValue();
                boolean here = entry.getKey().equals(aircraft.parkedAt());
                gathered.computeIfAbsent(entry.getKey(), stop -> new ArrayList<>())
                        .add(rank(aircraft.callsign(), leg, here));
            }
        }

        Map<String, List<Arrival>> out = new LinkedHashMap<>();
        gathered.forEach((stop, ranked) -> {
            ranked.sort(Comparator.comparingInt(Ranked::rank)
                    .thenComparingDouble(Ranked::tiebreak)
                    // Last resort, so a list of aircraft that are equally far
                    // off does not reshuffle itself between frames.
                    .thenComparing(r -> r.arrival().callsign()));
            List<Arrival> arrivals = new ArrayList<>(ranked.size());
            for (Ranked r : ranked) arrivals.add(r.arrival());
            out.put(stop, arrivals);
        });
        return out;
    }

    private static Ranked rank(String callsign, Leg leg, boolean parkedHere) {
        if (parkedHere) {
            return new Ranked(new Arrival(callsign, "here"), 0, 0);
        }
        if (leg.stopsAway() == 0) {
            // On its way here now. An estimate is honest for this leg and
            // only this one.
            return leg.etaSeconds() >= 0
                    ? new Ranked(new Arrival(callsign, "~" + duration(leg.etaSeconds())), 1,
                            leg.etaSeconds())
                    : new Ranked(new Arrival(callsign, "inbound"), 1, Double.MAX_VALUE);
        }
        return new Ranked(new Arrival(callsign, stopsAway(leg.stopsAway())), 2, leg.stopsAway());
    }

    static String stopsAway(int stops) {
        return stops == 1 ? "next stop" : stops + " stops";
    }

    /** Short enough to sit beside a callsign in a narrow panel. */
    static String duration(double seconds) {
        if (seconds < 60) return Math.max(1, Math.round(seconds)) + "s";
        long minutes = Math.round(seconds) / 60;
        long rest = Math.round(seconds) % 60;
        return rest == 0 ? minutes + "m" : minutes + "m" + rest + "s";
    }

    /**
     * Stops nothing is routed to.
     *
     * Worth calling out rather than leaving as an absence: a gate drawn and
     * then never used looks exactly like a gate in constant use until you
     * count, and an airport quietly running on two of its six gates is the
     * kind of thing this screen exists to show.
     */
    public static List<String> unused(List<String> allStops, Map<String, List<Arrival>> expected) {
        List<String> out = new ArrayList<>();
        for (String stop : allStops) {
            List<Arrival> arrivals = expected.get(stop);
            if (arrivals == null || arrivals.isEmpty()) out.add(stop);
        }
        return out;
    }
}
