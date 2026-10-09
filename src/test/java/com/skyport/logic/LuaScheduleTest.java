package com.skyport.logic;

import com.skyport.data.CraftType;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a computer is allowed to ask for.
 *
 * This is the only way into flight planning that was not drawn by a player on
 * a screen offering valid choices, so it is the only one that can be handed a
 * gate that does not exist, a cruise altitude below the world, or a table
 * with a typo in the key. Each of those has a test here, because each of them
 * fails in the same unhelpful way if it is not caught: an aircraft that sits
 * at its gate declining to fly, with nothing said about why.
 *
 * The partial-update tests are the ones worth reading twice. A script that
 * sets only the cruise speed must not lose the route, and one that sets only
 * the route must not reset the speed - and getting that wrong would delete
 * someone's flight plan rather than merely refuse it.
 */
class LuaScheduleTest {

    private static final UUID HEATHROW = UUID.randomUUID();
    private static final UUID GATWICK = UUID.randomUUID();
    private static final UUID VOR_ALPHA = UUID.randomUUID();

    /** Two airports, one beacon, and gates that differ from pads - enough to
     *  catch a lookup that ignores craft type. */
    private static final LuaSchedule.Lookup WORLD = new LuaSchedule.Lookup() {
        @Override
        public Optional<UUID> airportByName(String name) {
            if (name.equalsIgnoreCase("Heathrow")) return Optional.of(HEATHROW);
            if (name.equalsIgnoreCase("Gatwick")) return Optional.of(GATWICK);
            return Optional.empty();
        }

        @Override
        public Optional<String> airportName(UUID id) {
            if (HEATHROW.equals(id)) return Optional.of("Heathrow");
            if (GATWICK.equals(id)) return Optional.of("Gatwick");
            return Optional.empty();
        }

        @Override
        public List<String> stopNames(UUID airportId, CraftType craftType) {
            if (!HEATHROW.equals(airportId) && !GATWICK.equals(airportId)) return List.of();
            return craftType.isVertical() ? List.of("Pad 1") : List.of("Gate A", "Gate B");
        }

        @Override
        public Optional<UUID> vorByName(String name) {
            return name.equalsIgnoreCase("ALPHA") ? Optional.of(VOR_ALPHA) : Optional.empty();
        }

        @Override
        public Optional<String> vorName(UUID id) {
            return VOR_ALPHA.equals(id) ? Optional.of("ALPHA") : Optional.empty();
        }
    };

    private static Map<String, Object> table(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    /** Lua has one number type and it is a double, so this is what a script
     *  actually hands over when it writes 150. */
    private static Object num(double value) {
        return value;
    }

    private static Map<Object, Object> list(Object... items) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i++) out.put((double) (i + 1), items[i]);
        return out;
    }

    private static FlightSchedule baseline() {
        FlightSchedule schedule = new FlightSchedule();
        schedule.setCraftName("Cargo 1");
        schedule.setCruiseSpeed(24);
        schedule.setCruiseAltitude(150);
        schedule.entries().add(new ScheduleEntry(HEATHROW, "Gate A",
                ScheduleEntry.WaitCondition.TIMER, 30));
        schedule.entries().add(new ScheduleEntry(GATWICK, "Gate B",
                ScheduleEntry.WaitCondition.PLAYER, 0));
        return schedule;
    }

    // ---- reading ---------------------------------------------------------

    @Test
    void describeNamesEverythingAScriptWouldSwitchOn() {
        Map<String, Object> out = LuaSchedule.describe(baseline(), WORLD);
        assertEquals("Cargo 1", out.get("name"));
        assertEquals("plane", out.get("craft"));
        assertEquals(24, out.get("cruiseSpeed"));
        assertEquals("rotation", out.get("power"));

        @SuppressWarnings("unchecked")
        Map<Integer, Map<String, Object>> stops = (Map<Integer, Map<String, Object>>) out.get("stops");
        assertEquals(2, stops.size());
        assertEquals("Heathrow", stops.get(1).get("airport"));
        assertEquals("Gate A", stops.get(1).get("gate"));
        assertEquals("timer", stops.get(1).get("wait"));
        assertEquals("player", stops.get(2).get("wait"));
    }

    /** Lua indexes from 1, and `#sched.stops` has to be the stop count or
     *  every loop a script writes is off by one. */
    @Test
    void stopsAreAOneBasedLuaArray() {
        Map<Integer, String> out = LuaSchedule.luaList(List.of("a", "b", "c"));
        assertEquals("a", out.get(1));
        assertEquals("c", out.get(3));
        assertFalse(out.containsKey(0), "a Lua array has no index 0");
    }

    /** An airport broken since the route was written: the stop is still there
     *  and still skipped, so reporting a blank name as though it were fine is
     *  worse than saying it has gone. */
    @Test
    void describeSaysWhenAStopsAirportIsGone() {
        FlightSchedule schedule = new FlightSchedule();
        schedule.entries().add(new ScheduleEntry(UUID.randomUUID(), "Gate A",
                ScheduleEntry.WaitCondition.TIMER, 0));
        @SuppressWarnings("unchecked")
        Map<Integer, Map<String, Object>> stops =
                (Map<Integer, Map<String, Object>>) LuaSchedule.describe(schedule, WORLD).get("stops");
        assertEquals("(removed)", stops.get(1).get("airport"));
    }

    // ---- partial updates -------------------------------------------------

    @Test
    void settingOnlyTheSpeedKeepsTheRoute() throws Exception {
        FlightSchedule out = LuaSchedule.parse(table("cruiseSpeed", num(40)), baseline(), WORLD);
        assertEquals(40, out.cruiseSpeed());
        assertEquals(2, out.entries().size(), "a one-field update must not delete the flight plan");
        assertEquals("Cargo 1", out.craftName());
        assertEquals(150, out.cruiseAltitude());
    }

    @Test
    void settingOnlyTheRouteKeepsTheCruiseSettings() throws Exception {
        FlightSchedule out = LuaSchedule.parse(
                table("stops", list(table("airport", "Gatwick"))), baseline(), WORLD);
        assertEquals(1, out.entries().size());
        assertEquals(24, out.cruiseSpeed());
        assertEquals("Cargo 1", out.craftName());
    }

    /** A table that fails half way through must leave the aircraft alone
     *  rather than applying the half that parsed. */
    @Test
    void aRejectedScheduleChangesNothing() {
        FlightSchedule base = baseline();
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("cruiseSpeed", num(40),
                        "stops", list(table("airport", "Nowhere"))), base, WORLD));
        assertEquals(24, base.cruiseSpeed(), "the aircraft's own schedule must be untouched");
        assertEquals(2, base.entries().size());
    }

    // ---- refusals that explain themselves --------------------------------

    @Test
    void anUnknownAirportIsNamedInTheRefusal() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("stops", list(table("airport", "Heathrwo"))),
                        baseline(), WORLD));
        assertTrue(bad.getMessage().contains("Heathrwo"), bad.getMessage());
        assertTrue(bad.getMessage().contains("stop 1"), "say which stop: " + bad.getMessage());
    }

    /** The one that would otherwise cost an hour: the gates that DO exist are
     *  listed, so the fix is in the error rather than in the wiki. */
    @Test
    void anUnknownGateListsTheRealOnes() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(
                        table("stops", list(table("airport", "Heathrow", "gate", "Gate Q"))),
                        baseline(), WORLD));
        assertTrue(bad.getMessage().contains("Gate A"), bad.getMessage());
        assertTrue(bad.getMessage().contains("Gate B"), bad.getMessage());
    }

    /** A helicopter wants pads, so a gate name is wrong for it even though
     *  that gate exists - and the message should say pad, not gate. */
    @Test
    void aVerticalCraftIsOfferedPadsNotGates() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(
                        table("craft", "helicopter",
                                "stops", list(table("airport", "Heathrow", "gate", "Gate A"))),
                        baseline(), WORLD));
        assertTrue(bad.getMessage().contains("helipad"), bad.getMessage());
        assertTrue(bad.getMessage().contains("Pad 1"), bad.getMessage());
    }

    @Test
    void craftTypeIsReadBeforeTheStopsItValidates() throws Exception {
        FlightSchedule out = LuaSchedule.parse(
                table("craft", "helicopter",
                        "stops", list(table("airport", "Heathrow", "gate", "Pad 1"))),
                baseline(), WORLD);
        assertEquals(CraftType.HELICOPTER, out.craftType());
        assertEquals("Pad 1", out.entries().get(0).gateName());
    }

    /** `cruisespeed = 40` would otherwise be accepted in full and change
     *  nothing, which is indistinguishable from a bug in the mod. */
    @Test
    void aMisspelledFieldIsRefusedRatherThanIgnored() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("cruisespeed", num(40)), baseline(), WORLD));
        assertTrue(bad.getMessage().contains("cruisespeed"), bad.getMessage());
        assertTrue(bad.getMessage().contains("cruiseSpeed"), "offer the real one: " + bad.getMessage());
    }

    @Test
    void cruiseSettingsAreHeldToTheSameLimitsAsTheScreen() {
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("cruiseSpeed", num(FlightSchedule.MAX_CRUISE_SPEED + 1)), baseline(), WORLD));
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("cruiseAltitude", num(-1)), baseline(), WORLD));
    }

    /** A script computing an altitude and arriving at 150.5 has a bug in it,
     *  and rounding it away hides the bug. */
    @Test
    void aFractionalAltitudeIsRefused() {
        assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("cruiseAltitude", num(150.5)), baseline(), WORLD));
    }

    @Test
    void theWrongTypeSaysWhatWasWanted() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("loop", "yes"), baseline(), WORLD));
        assertTrue(bad.getMessage().contains("true or false"), bad.getMessage());
    }

    /** Passing one stop where a list was wanted is the easiest mistake in Lua
     *  to make, and silently reading it as an empty list would wipe a route. */
    @Test
    void asingleStopPassedInsteadOfAListIsRefused() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(
                        table("stops", table("airport", "Heathrow")), baseline(), WORLD));
        assertTrue(bad.getMessage().contains("single entry"), bad.getMessage());
    }

    @Test
    void anEmptyRouteIsAllowedBecauseTheScreenAllowsItToo() throws Exception {
        FlightSchedule out = LuaSchedule.parse(table("stops", list()), baseline(), WORLD);
        assertTrue(out.entries().isEmpty(), "saving an unflyable route is not the same as flying it");
        assertFalse(out.hasAirportStop(), "and engage is what refuses it");
    }

    // ---- stops ------------------------------------------------------------

    @Test
    void aStopWithNoGateNamedTakesTheFirstOne() throws Exception {
        FlightSchedule out = LuaSchedule.parse(
                table("stops", list(table("airport", "Heathrow"))), baseline(), WORLD);
        assertEquals("Gate A", out.entries().get(0).gateName());
    }

    @Test
    void aVorStopIsFlownOverRatherThanLandedAt() throws Exception {
        FlightSchedule out = LuaSchedule.parse(
                table("stops", list(table("vor", "ALPHA"), table("airport", "Gatwick"))),
                baseline(), WORLD);
        assertTrue(out.entries().get(0).isVor());
        assertEquals(VOR_ALPHA, out.entries().get(0).vorId());
        assertFalse(out.entries().get(1).isVor());
    }

    @Test
    void aStopCannotBeBothAnAirportAndAVor() {
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("stops", list(table("airport", "Heathrow", "vor", "ALPHA"))), baseline(), WORLD));
    }

    @Test
    void aStopThatNamesNowhereIsRefused() {
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("stops", list(table("waitSeconds", num(30)))), baseline(), WORLD));
    }

    /** Ids are for a program handing back the stop it was given, so an id
     *  read out of describe() has to go straight back in. */
    @Test
    void anIdFromDescribeRoundTripsBackIn() throws Exception {
        @SuppressWarnings("unchecked")
        Map<Integer, Map<String, Object>> stops =
                (Map<Integer, Map<String, Object>>) LuaSchedule.describe(baseline(), WORLD).get("stops");
        Object id = stops.get(1).get("airportId");

        FlightSchedule out = LuaSchedule.parse(
                table("stops", list(table("airportId", id, "gate", "Gate B"))), baseline(), WORLD);
        assertEquals(HEATHROW, out.entries().get(0).airportId());
        assertEquals("Gate B", out.entries().get(0).gateName());
    }

    @Test
    void anIdForSomethingThatNoLongerExistsIsRefused() {
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("stops", list(table("airportId", UUID.randomUUID().toString()))),
                baseline(), WORLD));
    }

    @Test
    void textThatIsNotAnIdAtAllIsRefused() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("stops", list(table("airportId", "not-a-uuid"))),
                        baseline(), WORLD));
        assertTrue(bad.getMessage().contains("not an id"), bad.getMessage());
    }

    @Test
    void waitConditionsUseTheirLuaNames() throws Exception {
        FlightSchedule out = LuaSchedule.parse(
                table("stops", list(table("airport", "Heathrow", "wait", "cargo_loaded"))),
                baseline(), WORLD);
        assertEquals(ScheduleEntry.WaitCondition.CARGO_LOADED, out.entries().get(0).condition());
    }

    @Test
    void anUnknownWaitConditionListsTheRealOnes() {
        LuaSchedule.BadSchedule bad = assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(
                        table("stops", list(table("airport", "Heathrow", "wait", "whenever"))),
                        baseline(), WORLD));
        assertTrue(bad.getMessage().contains("cargo_empty"), bad.getMessage());
    }

    @Test
    void aNegativeWaitIsRefused() {
        assertThrows(LuaSchedule.BadSchedule.class, () -> LuaSchedule.parse(
                table("stops", list(table("airport", "Heathrow", "waitSeconds", num(-5)))),
                baseline(), WORLD));
    }

    // ---- power -----------------------------------------------------------

    @Test
    void thePowerChoiceCanBeSetByName() throws Exception {
        assertTrue(LuaSchedule.parse(table("power", "fuel"), baseline(), WORLD).prefersFuel());
        assertFalse(LuaSchedule.parse(table("power", "rotation"), baseline(), WORLD).prefersFuel());
    }

    @Test
    void anUnknownPowerSourceIsRefused() {
        assertThrows(LuaSchedule.BadSchedule.class,
                () -> LuaSchedule.parse(table("power", "solar"), baseline(), WORLD));
    }

    /** Every enum a script can name is lower case, so a Lua author never has
     *  to guess at SHOUTING_CASE. */
    @Test
    void luaNamesAreLowerCase() {
        assertEquals("cargo_loaded", LuaSchedule.luaName(ScheduleEntry.WaitCondition.CARGO_LOADED));
        assertEquals("helicopter", LuaSchedule.luaName(CraftType.HELICOPTER));
    }

    /** parse builds a new schedule, so the caller can apply it or drop it -
     *  this is what makes the all-or-nothing behaviour above possible. */
    @Test
    void parseDoesNotHandBackTheScheduleItWasGiven() throws Exception {
        FlightSchedule base = baseline();
        assertFalse(base == LuaSchedule.parse(table(), base, WORLD));
        assertSame(base, base);
    }
}
