package com.skyport.logic;

import com.skyport.data.CraftType;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A flight schedule as a computer sees it, and back again.
 *
 * Reading is the easy direction. Writing is the one with teeth: a schedule
 * handed back by a Lua program is the only route into this mod's flight
 * planning that was not drawn by a player on a screen that only offered
 * valid choices. The destination list could only ever contain real airports;
 * a script can name "Heathrwo". The gate button could only cycle gates that
 * existed; a script can ask for "Gate Q" at an airport with three. So every
 * field arriving from Lua is checked here, and a refusal says what was wrong
 * with it rather than leaving an aircraft that quietly declines to fly.
 *
 * Deliberately free of any ComputerCraft type. Everything crossing this
 * boundary is a plain Map, a String or a number, which keeps the awkward half
 * of the integration - the parsing and the validation - testable without a
 * game, a computer or CC on the classpath at all.
 *
 * Unknown keys are rejected rather than ignored. A table containing
 * `cruisespeed = 40` would otherwise be accepted in full and change nothing,
 * and the script author would have no way to tell that from a bug in the mod.
 */
public final class LuaSchedule {

    private LuaSchedule() { }

    /**
     * The world's airports and beacons, as much of them as parsing needs.
     *
     * An interface rather than the registry itself so the validation can be
     * tested against a handful of made-up airports instead of a loaded world.
     */
    public interface Lookup {
        /** The airport with this display name, case-insensitively. */
        Optional<UUID> airportByName(String name);

        /** That airport's display name, if it still exists. */
        Optional<String> airportName(UUID id);

        /**
         * Where an aircraft of this type can stop at that airport: gates for
         * a plane, helipads for anything that lands vertically. Empty when
         * the airport has none drawn, which is a real state - an airport
         * under construction.
         */
        List<String> stopNames(UUID airportId, CraftType craftType);

        Optional<UUID> vorByName(String name);

        Optional<String> vorName(UUID id);
    }

    /** A schedule a script asked for that cannot be flown, and why. */
    public static class BadSchedule extends Exception {
        public BadSchedule(String message) {
            super(message);
        }
    }

    private static final Set<String> SCHEDULE_KEYS =
            Set.of("name", "craft", "loop", "cruiseAltitude", "cruiseSpeed", "power", "stops");
    private static final Set<String> STOP_KEYS =
            Set.of("kind", "airport", "airportId", "gate", "wait", "waitSeconds", "vor", "vorId");

    // ---- out to Lua ------------------------------------------------------

    public static Map<String, Object> describe(FlightSchedule schedule, Lookup lookup) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", schedule.craftName());
        out.put("craft", luaName(schedule.craftType()));
        out.put("loop", schedule.loop());
        out.put("cruiseAltitude", schedule.cruiseAltitude());
        out.put("cruiseSpeed", schedule.cruiseSpeed());
        out.put("power", schedule.prefersFuel() ? "fuel" : "rotation");
        List<Map<String, Object>> stops = new ArrayList<>();
        for (ScheduleEntry entry : schedule.entries()) {
            stops.add(describeStop(entry, lookup));
        }
        out.put("stops", luaList(stops));
        return out;
    }

    public static Map<String, Object> describeStop(ScheduleEntry entry, Lookup lookup) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (entry.isVor()) {
            out.put("kind", "vor");
            out.put("vorId", entry.vorId().toString());
            // A beacon that has been broken since the route was written: the
            // stop is still on the schedule and still skipped, so saying so
            // beats reporting a blank name as though it were fine.
            out.put("vor", lookup.vorName(entry.vorId()).orElse("(removed)"));
            return out;
        }
        out.put("kind", "airport");
        out.put("airportId", entry.airportId().toString());
        out.put("airport", lookup.airportName(entry.airportId()).orElse("(removed)"));
        out.put("gate", entry.gateName());
        out.put("wait", luaName(entry.condition()));
        out.put("waitSeconds", entry.waitSeconds());
        return out;
    }

    /**
     * A Java list as a Lua array.
     *
     * Lua has no lists, only tables with the keys 1..n, so a sequence has to
     * be handed over as exactly that. Building it here rather than relying on
     * whatever a given CC version does with a Collection also means a script
     * can always trust `#sched.stops` to be the number of stops.
     */
    public static <T> Map<Integer, T> luaList(List<T> items) {
        Map<Integer, T> out = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) out.put(i + 1, items.get(i));
        return out;
    }

    // ---- in from Lua -----------------------------------------------------

    /**
     * The schedule a script asked for, built on the one the aircraft has.
     *
     * A partial update on purpose: a table naming only `cruiseSpeed` changes
     * the speed and leaves the route alone. Replacing the whole schedule
     * whenever one field was mentioned would make the obvious one-line script
     * - set the speed - silently delete the flight plan.
     *
     * Returns a new schedule rather than editing the aircraft's own, so a
     * table that turns out to be invalid half way through parsing cannot
     * leave a half-applied route behind.
     */
    public static FlightSchedule parse(Map<?, ?> table, FlightSchedule base, Lookup lookup)
            throws BadSchedule {
        rejectUnknownKeys(table, SCHEDULE_KEYS, "schedule");

        FlightSchedule out = new FlightSchedule();
        out.setCraftName(optString(table, "name").orElse(base.craftName()));
        out.setLoop(optBoolean(table, "loop").orElse(base.loop()));
        out.setCruiseAltitude(range(optInt(table, "cruiseAltitude").orElse(base.cruiseAltitude()),
                FlightSchedule.MIN_CRUISE_ALTITUDE, FlightSchedule.MAX_CRUISE_ALTITUDE, "cruiseAltitude"));
        out.setCruiseSpeed(range(optInt(table, "cruiseSpeed").orElse(base.cruiseSpeed()),
                FlightSchedule.MIN_CRUISE_SPEED, FlightSchedule.MAX_CRUISE_SPEED, "cruiseSpeed"));

        Optional<String> power = optString(table, "power");
        if (power.isPresent()) {
            String choice = power.get().toLowerCase(Locale.ROOT);
            if (choice.equals("fuel")) out.setPrefersFuel(true);
            else if (choice.equals("rotation")) out.setPrefersFuel(false);
            else throw new BadSchedule("power is \"rotation\" or \"fuel\", not \"" + power.get() + "\"");
        } else {
            out.setPrefersFuel(base.prefersFuel());
        }

        // Before the stops, because which stop names are valid at an airport
        // depends on it: a helicopter wants pads where a plane wants gates.
        CraftType craftType = optString(table, "craft").isPresent()
                ? parseEnum(optString(table, "craft").get(), CraftType.class, "craft")
                : base.craftType();
        out.setCraftType(craftType);

        Object stops = get(table, "stops");
        if (stops == null) {
            out.entries().addAll(base.entries());
        } else {
            if (!(stops instanceof Map<?, ?> list)) {
                throw new BadSchedule("stops has to be a table of stops, not " + typeName(stops));
            }
            int index = 1;
            for (Object raw : luaSequence(list, "stops")) {
                if (!(raw instanceof Map<?, ?> stop)) {
                    throw new BadSchedule("stop " + index + " has to be a table, not " + typeName(raw));
                }
                try {
                    out.entries().add(parseStop(stop, craftType, lookup));
                } catch (BadSchedule bad) {
                    throw new BadSchedule("stop " + index + ": " + bad.getMessage());
                }
                index++;
            }
        }
        return out;
    }

    public static ScheduleEntry parseStop(Map<?, ?> table, CraftType craftType, Lookup lookup)
            throws BadSchedule {
        rejectUnknownKeys(table, STOP_KEYS, "stop");

        boolean namesVor = get(table, "vor") != null || get(table, "vorId") != null;
        boolean namesAirport = get(table, "airport") != null || get(table, "airportId") != null;
        Optional<String> kind = optString(table, "kind");
        if (kind.isPresent()) {
            String k = kind.get().toLowerCase(Locale.ROOT);
            if (k.equals("vor")) namesVor = true;
            else if (k.equals("airport")) namesAirport = true;
            else throw new BadSchedule("kind is \"airport\" or \"vor\", not \"" + kind.get() + "\"");
        }
        if (namesVor && namesAirport) {
            throw new BadSchedule("a stop is an airport or a VOR, not both");
        }
        if (!namesVor && !namesAirport) {
            throw new BadSchedule("needs an airport or a vor");
        }

        if (namesVor) {
            UUID vorId = resolve(table, "vor", "vorId", lookup::vorByName, lookup::vorName, "VOR");
            return ScheduleEntry.vor(vorId);
        }

        UUID airportId =
                resolve(table, "airport", "airportId", lookup::airportByName, lookup::airportName, "airport");
        List<String> valid = lookup.stopNames(airportId, craftType);
        String where = craftType.isVertical() ? "helipad" : "gate";

        Optional<String> asked = optString(table, "gate");
        String gate;
        if (asked.isPresent()) {
            gate = valid.stream().filter(name -> name.equalsIgnoreCase(asked.get())).findFirst()
                    .orElseThrow(() -> new BadSchedule(valid.isEmpty()
                            ? "that airport has no " + where + " drawn yet"
                            : "no " + where + " called \"" + asked.get() + "\" there - it has "
                                    + String.join(", ", valid)));
        } else {
            // No gate named is the common case in a script that only cares
            // where it is going. Picking the first keeps that script working,
            // and an airport with nothing drawn yet still parses - Engage is
            // what refuses a route it cannot fly, the same as on the screen.
            gate = valid.isEmpty() ? "" : valid.get(0);
        }

        ScheduleEntry.WaitCondition wait = optString(table, "wait").isPresent()
                ? parseEnum(optString(table, "wait").get(), ScheduleEntry.WaitCondition.class, "wait")
                : ScheduleEntry.WaitCondition.TIMER;
        int seconds = optInt(table, "waitSeconds").orElse(0);
        if (seconds < FlightSchedule.MIN_WAIT_SECONDS) {
            throw new BadSchedule("waitSeconds cannot be negative");
        }
        return new ScheduleEntry(airportId, gate, wait, seconds);
    }

    // ---- the fiddly bits -------------------------------------------------

    /**
     * Either key, resolved to an id that exists.
     *
     * Names are what a script author actually types, so they come first; the
     * id is there for a program that read one out of describe() and wants to
     * hand back exactly the stop it was given, unambiguously, even on a
     * server with two airports called "Main".
     */
    private static UUID resolve(Map<?, ?> table, String nameKey, String idKey,
                                java.util.function.Function<String, Optional<UUID>> byName,
                                java.util.function.Function<UUID, Optional<String>> nameOf,
                                String what) throws BadSchedule {
        Optional<String> id = optString(table, idKey);
        if (id.isPresent()) {
            UUID parsed;
            try {
                parsed = UUID.fromString(id.get());
            } catch (IllegalArgumentException malformed) {
                throw new BadSchedule(idKey + " is not an id: \"" + id.get() + "\"");
            }
            if (nameOf.apply(parsed).isEmpty()) {
                throw new BadSchedule("no " + what + " with that " + idKey + " any more");
            }
            return parsed;
        }
        String name = optString(table, nameKey)
                .orElseThrow(() -> new BadSchedule("needs " + nameKey + " or " + idKey));
        return byName.apply(name)
                .orElseThrow(() -> new BadSchedule("no " + what + " called \"" + name + "\""));
    }

    /**
     * The 1..n part of a Lua table, in order.
     *
     * Lua array keys arrive as numbers - and as Doubles, because that is the
     * only number Lua has - so this walks 1, 2, 3 until one is missing rather
     * than iterating the map, whose order means nothing. Stopping at the
     * first gap is also what Lua itself does with `#`, so a table with a hole
     * in it is read here exactly as the script that built it would read it.
     */
    private static List<Object> luaSequence(Map<?, ?> table, String what) throws BadSchedule {
        List<Object> out = new ArrayList<>();
        for (int i = 1; ; i++) {
            Object value = table.get((double) i);
            if (value == null) value = table.get(i);
            if (value == null) value = table.get(Long.valueOf(i));
            if (value == null) break;
            out.add(value);
        }
        // A table with only string keys read as an empty sequence would
        // silently wipe a route, so say so instead.
        if (out.isEmpty() && !table.isEmpty()) {
            throw new BadSchedule(what + " looks like a single entry rather than a list of them");
        }
        return out;
    }

    private static void rejectUnknownKeys(Map<?, ?> table, Set<String> allowed, String what)
            throws BadSchedule {
        for (Object key : table.keySet()) {
            if (key instanceof String name && !allowed.contains(name)) {
                List<String> sorted = new ArrayList<>(allowed);
                java.util.Collections.sort(sorted);
                throw new BadSchedule("\"" + name + "\" is not a " + what + " field - try one of "
                        + String.join(", ", sorted));
            }
        }
    }

    private static Object get(Map<?, ?> table, String key) {
        return table.get(key);
    }

    private static Optional<String> optString(Map<?, ?> table, String key) throws BadSchedule {
        Object value = get(table, key);
        if (value == null) return Optional.empty();
        if (value instanceof String s) return Optional.of(s);
        throw new BadSchedule(key + " has to be text, not " + typeName(value));
    }

    private static Optional<Boolean> optBoolean(Map<?, ?> table, String key) throws BadSchedule {
        Object value = get(table, key);
        if (value == null) return Optional.empty();
        if (value instanceof Boolean b) return Optional.of(b);
        throw new BadSchedule(key + " has to be true or false, not " + typeName(value));
    }

    /**
     * Lua has one number type and it is a double, so 150 arrives as 150.0.
     * A fractional altitude is a mistake worth naming rather than rounding:
     * a script computing one is a script with a bug in it.
     */
    private static Optional<Integer> optInt(Map<?, ?> table, String key) throws BadSchedule {
        Object value = get(table, key);
        if (value == null) return Optional.empty();
        if (!(value instanceof Number number)) {
            throw new BadSchedule(key + " has to be a number, not " + typeName(value));
        }
        double d = number.doubleValue();
        if (d != Math.floor(d) || Double.isInfinite(d)) {
            throw new BadSchedule(key + " has to be a whole number, not " + d);
        }
        return Optional.of((int) d);
    }

    private static int range(int value, int min, int max, String key) throws BadSchedule {
        if (value < min || value > max) {
            throw new BadSchedule(key + " has to be between " + min + " and " + max + ", not " + value);
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String key)
            throws BadSchedule {
        for (E candidate : type.getEnumConstants()) {
            if (luaName(candidate).equalsIgnoreCase(raw)) return candidate;
        }
        List<String> names = new ArrayList<>();
        for (E candidate : type.getEnumConstants()) names.add(luaName(candidate));
        throw new BadSchedule(key + " is one of " + String.join(", ", names) + ", not \"" + raw + "\"");
    }

    /** Enum names as a Lua script writes them: lower case, so CARGO_LOADED
     *  is "cargo_loaded". */
    public static String luaName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String typeName(Object value) {
        if (value == null) return "nil";
        if (value instanceof String) return "text";
        if (value instanceof Number) return "a number";
        if (value instanceof Boolean) return "a boolean";
        if (value instanceof Map) return "a table";
        return value.getClass().getSimpleName();
    }
}
