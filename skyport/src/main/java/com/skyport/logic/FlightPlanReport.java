package com.skyport.logic;

import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * A schedule written out as lines, for the admin command to print.
 *
 * Kept apart from the command itself, and free of Minecraft types, because
 * the interesting part is the reading rather than the printing: which stop an
 * aircraft is actually on, which of them are VORs it only flies over, and
 * which name has gone missing since the route was built. Those are the things
 * worth a test, and none of them need a server.
 *
 * Names are looked up through functions rather than a registry so the caller
 * decides what "missing" reads as - and so this can be tested without one.
 */
public final class FlightPlanReport {

    private FlightPlanReport() { }

    /** What a stop whose airport or VOR has been deleted is called. */
    public static final String MISSING = "(removed)";

    /**
     * One line per stop, in route order.
     *
     * @param currentIndex the stop the aircraft is working on, marked with an
     *                     arrow; -1 while it is not flying the plan
     */
    public static List<String> lines(FlightSchedule schedule, int currentIndex,
                                     Function<UUID, String> airportName,
                                     Function<UUID, String> vorName) {
        List<String> lines = new ArrayList<>();
        List<ScheduleEntry> entries = schedule.entries();
        for (int i = 0; i < entries.size(); i++) {
            ScheduleEntry stop = entries.get(i);
            String marker = i == currentIndex ? "> " : "  ";
            if (stop.isVor()) {
                lines.add(marker + (i + 1) + ". via " + name(vorName, stop.vorId()) + "  [fly over]");
            } else {
                lines.add(marker + (i + 1) + ". " + name(airportName, stop.airportId())
                        + " / " + (stop.gateName().isEmpty() ? "(no gate)" : stop.gateName())
                        + "  [" + condition(stop) + "]");
            }
        }
        if (entries.isEmpty()) lines.add("  (no stops)");
        return lines;
    }

    /** The one-line summary that goes above those: how it flies, not where. */
    public static String summary(FlightSchedule schedule) {
        return schedule.craftType().label()
                + ", cruise Y " + schedule.cruiseAltitude()
                + " at " + schedule.cruiseSpeed()
                + ", loop " + (schedule.loop() ? "on" : "off");
    }

    private static String name(Function<UUID, String> lookup, UUID id) {
        if (id == null) return MISSING;
        String name = lookup.apply(id);
        return name == null || name.isBlank() ? MISSING : name;
    }

    private static String condition(ScheduleEntry stop) {
        return switch (stop.condition()) {
            case TIMER -> stop.waitSeconds() + "s";
            case PLAYER -> "waits for a player";
            case CARGO_LOADED -> "waits until loaded";
            case CARGO_EMPTY -> "waits until emptied";
        };
    }
}
