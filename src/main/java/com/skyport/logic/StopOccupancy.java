package com.skyport.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which aircraft are at an airport's gates and pads, and which are coming.
 *
 * Gates are not reserved by anything. The traffic system claims runways, the
 * taxiways as a set, and helipads - and that is all it has ever claimed, so
 * there is no gate clearance to read. What there is instead is an aircraft
 * sitting at a gate waiting, which is the thing a player actually wants to
 * know about, and which can be worked out from where each aircraft is in its
 * own schedule.
 *
 * So this is inference, not a reading, and the difference matters enough to
 * keep it in one place with its own tests rather than scattered through a
 * screen. Being wrong here means telling somebody a gate is free when an
 * aeroplane is parked on it.
 *
 * Pads are the exception: they ARE claimed, so their occupant is read from
 * the registry and only their expected arrivals come from here.
 */
public final class StopOccupancy {

    private StopOccupancy() { }

    /**
     * One aircraft, as much of it as this airport cares about.
     *
     * @param callsign       what to show
     * @param parkedAt       the gate or pad at THIS airport it is currently
     *                       sitting at, or null if it is not parked here
     * @param scheduledStops every stop at this airport anywhere in its route,
     *                       in schedule order, including the one it is at
     */
    public record Aircraft(String callsign, String parkedAt, List<String> scheduledStops) { }

    /**
     * Who is sitting at each stop right now.
     *
     * Two aircraft claiming the same gate should not happen - a gate is one
     * stop on one schedule at a time - but nothing in the traffic system
     * enforces it, because gates are not claimed at all. So rather than one
     * silently winning, both are named: a gate showing two aeroplanes is a
     * routing mistake somebody should see, not something to hide.
     */
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
     * Which aircraft have each stop somewhere in their route.
     *
     * Everything scheduled here, not only what is inbound now: a gate used by
     * four routes is a different thing from one used by none, and that is the
     * question somebody opening this is asking - whether a gate is pulling
     * its weight, and who would notice if it were removed.
     *
     * An aircraft parked at the stop is still listed as scheduled there. It
     * is, and leaving it out would make a gate look unused at the exact
     * moment it is being used.
     */
    public static Map<String, List<String>> expected(List<Aircraft> fleet) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Aircraft aircraft : fleet) {
            for (String stop : aircraft.scheduledStops()) {
                if (stop == null || stop.isEmpty()) continue;
                List<String> callsigns =
                        out.computeIfAbsent(stop, name -> new ArrayList<>());
                // A route that visits the same gate twice is one aircraft,
                // not two - listing it twice would read as a conflict.
                if (!callsigns.contains(aircraft.callsign())) callsigns.add(aircraft.callsign());
            }
        }
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    /**
     * Stops nothing is scheduled to use.
     *
     * Worth calling out rather than leaving as an absence. A gate drawn and
     * then never routed to looks exactly like a gate in constant use until
     * you count, and an airport quietly running on two of its six gates is
     * the kind of thing this screen exists to show.
     */
    public static List<String> unused(List<String> allStops, Map<String, List<String>> expected) {
        List<String> out = new ArrayList<>();
        for (String stop : allStops) {
            List<String> callsigns = expected.get(stop);
            if (callsigns == null || callsigns.isEmpty()) out.add(stop);
        }
        return out;
    }
}
