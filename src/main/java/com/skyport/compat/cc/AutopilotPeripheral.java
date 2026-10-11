package com.skyport.compat.cc;

import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import com.skyport.logic.FuelBurn;
import com.skyport.logic.LuaSchedule;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One aircraft, as a ComputerCraft peripheral: `peripheral.wrap("left")` on
 * a computer sitting next to an Autopilot block.
 *
 * Works on the ground and aboard a craft. Aboard is the interesting one - a
 * computer on the aeroplane, next to the autopilot it is flying - and it is
 * why nothing here asks the block where it is: a mounted block's own
 * coordinates are plot-local, so position comes from the craft through
 * craftPosition() instead. That distinction is the single sharpest edge in
 * this codebase and it has drawn blood before.
 *
 * Every method is mainThread. CC runs Lua on its own thread, and every call
 * below touches a block entity, a level or the airport registry - none of
 * which are safe to read from off the server thread, and all of which would
 * fail the way concurrency always does: rarely, and never while you are
 * looking.
 */
public class AutopilotPeripheral implements IPeripheral {

    private final AutopilotBlockEntity autopilot;

    AutopilotPeripheral(BlockEntity blockEntity) {
        this.autopilot = (AutopilotBlockEntity) blockEntity;
    }

    @Override
    public String getType() {
        return "skyport_autopilot";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof AutopilotPeripheral peripheral && peripheral.autopilot == autopilot;
    }

    @Override
    public Object getTarget() {
        return autopilot;
    }

    // ---- reading ---------------------------------------------------------

    /** The aircraft's name if it has been given one, otherwise the generated
     *  callsign - the same identity the tower and the chat messages use. */
    @LuaFunction(mainThread = true)
    public final String getCallsign() {
        return autopilot.callsign();
    }

    /** What the aircraft is doing: "idle", "taxi_out", "cruise", "approach"
     *  and so on - the lower-cased flight states. */
    @LuaFunction(mainThread = true)
    public final String getState() {
        return LuaSchedule.luaName(autopilot.flightState());
    }

    @LuaFunction(mainThread = true)
    public final boolean isEngaged() {
        return autopilot.isEngaged();
    }

    /** Where the craft is in the world, not where the block thinks it is. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() {
        return CCPeripherals.position(autopilot.craftPosition());
    }

    /** The id the tower knows this aircraft by, or nil before its first
     *  flight - it does not get one until it has been engaged. */
    @LuaFunction(mainThread = true)
    @Nullable
    public final String getAircraftId() {
        UUID id = autopilot.aircraftId();
        return id == null ? null : id.toString();
    }

    /**
     * Sable's id for the craft this autopilot is riding, or nil for a loose
     * block. The same string CC: Sable's `sublevel.getUniqueId()` returns, so
     * a program aboard can confirm the autopilot it found is on the aeroplane
     * it is standing on rather than one parked alongside.
     */
    @LuaFunction(mainThread = true)
    @Nullable
    public final String getSubLevelId() {
        UUID id = autopilot.subLevelId();
        return id == null ? null : id.toString();
    }

    /**
     * Fuel aboard, as a table, or nil on a server where fuel is not burned.
     *
     * `seconds` is the honest number to show a player and it is an estimate:
     * burn rate rises with cruise speed, so it answers "at the speed this
     * schedule is set to", and throttling back in flight makes it go up.
     */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> getFuel() {
        if (!com.skyport.SkyportConfig.powerRequirement
                .resolved(autopilot.schedule().prefersFuel()).needsFuel()) {
            return null;
        }
        double reserve = autopilot.fuelRemaining();
        double rate = FuelBurn.rateForSpeed(autopilot.schedule().cruiseSpeed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reserve", reserve);
        out.put("seconds", rate <= 0 ? 0.0 : reserve / rate / 20.0);
        out.put("dry", reserve <= 0);
        return out;
    }

    /** The whole flight plan: name, craft type, loop, cruise settings, and
     *  the stops as a 1..n list. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getSchedule() throws LuaException {
        return LuaSchedule.describe(autopilot.schedule(), CCPeripherals.lookup(level()));
    }

    /**
     * Everywhere this aircraft could be sent, with each airport's gates and
     * helipads - the same list the Autopilot screen offers a player, and the
     * one a script has to read before it can name a destination.
     *
     * Here rather than only on the tower because a computer bolted to an
     * aeroplane is nowhere near a tower, and because a script that has to be
     * told the airport names in advance is a script that breaks the moment
     * somebody renames one.
     */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getAirports() throws LuaException {
        return LuaSchedule.luaList(CCPeripherals.sortedAirports(level()));
    }

    /** Every VOR beacon, which is what a stop can name besides an airport. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getVors() throws LuaException {
        return LuaSchedule.luaList(CCPeripherals.sortedVors(level()));
    }

    /** Which stop it is working on, 1-based to match the list getSchedule
     *  hands back and the numbers on the Autopilot screen. */
    @LuaFunction(mainThread = true)
    public final int getCurrentStop() {
        return autopilot.currentStopIndex() + 1;
    }

    /** Seconds of unattended running left before the aircraft is allowed to
     *  sleep, or nil where the server does not bound it. */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Integer getUnattendedSeconds() {
        int left = autopilot.unattendedSecondsLeft();
        return left <= 0 ? null : left;
    }

    // ---- writing ---------------------------------------------------------

    /**
     * Change the flight plan. A partial table on purpose:
     * `setSchedule({cruiseSpeed = 40})` changes the speed and leaves the
     * route alone, because the alternative - every field you did not mention
     * reverting - turns the obvious one-line script into one that deletes a
     * route.
     *
     * Everything about what a valid schedule is lives in LuaSchedule, and so
     * does every refusal message, which is why they say "no gate called
     * \"Gate Q\" there - it has Gate A, Gate B" rather than just "false".
     */
    @LuaFunction(mainThread = true)
    public final void setSchedule(Map<?, ?> table) throws LuaException {
        autopilot.setSchedule(parse(table));
    }

    /** Add one stop to the end of the route. */
    @LuaFunction(mainThread = true)
    public final int addStop(Map<?, ?> table) throws LuaException {
        FlightSchedule schedule = autopilot.schedule();
        ScheduleEntry entry;
        try {
            entry = LuaSchedule.parseStop(table, schedule.craftType(), CCPeripherals.lookup(level()));
        } catch (LuaSchedule.BadSchedule bad) {
            throw new LuaException(bad.getMessage());
        }
        schedule.entries().add(entry);
        autopilot.setChanged();
        return schedule.entries().size();
    }

    /** Remove the stop at this 1-based index. */
    @LuaFunction(mainThread = true)
    public final void removeStop(int index) throws LuaException {
        FlightSchedule schedule = autopilot.schedule();
        schedule.entries().remove(checkStop(index, schedule));
        autopilot.setChanged();
    }

    /**
     * Move a stop earlier (negative) or later (positive) in the route, and
     * say where it ended up.
     *
     * Worth having rather than leaving scripts to rebuild the list, because
     * order is the whole meaning of a VOR: one below the airport it was meant
     * to route via is not flown at all.
     */
    @LuaFunction(mainThread = true)
    public final int moveStop(int index, int delta) throws LuaException {
        FlightSchedule schedule = autopilot.schedule();
        int moved = schedule.moveStop(checkStop(index, schedule), delta);
        autopilot.setChanged();
        return moved + 1;
    }

    @LuaFunction(mainThread = true)
    public final void setName(String name) throws LuaException {
        setSchedule(Map.of("name", name));
    }

    @LuaFunction(mainThread = true)
    public final void setCruiseAltitude(int altitude) throws LuaException {
        setSchedule(Map.of("cruiseAltitude", (double) altitude));
    }

    @LuaFunction(mainThread = true)
    public final void setCruiseSpeed(int speed) throws LuaException {
        setSchedule(Map.of("cruiseSpeed", (double) speed));
    }

    @LuaFunction(mainThread = true)
    public final void setLoop(boolean loop) throws LuaException {
        setSchedule(Map.of("loop", loop));
    }

    /**
     * Which half this aircraft pays to fly, where the server takes either -
     * "rotation" or "fuel". Does nothing anywhere else, because there the
     * server has already decided.
     */
    @LuaFunction(mainThread = true)
    public final void setPowerSource(String source) throws LuaException {
        setSchedule(Map.of("power", source));
    }

    /**
     * Fly the saved schedule.
     *
     * Rate-limited, and sharing one budget with the redstone path rather than
     * getting its own. Engaging rescans the whole route for terrain and loads
     * chunks to do it, which is what once hung a server for minutes at a time
     * when a neighbouring block re-triggered it every tick. A Lua `while true
     * do ap.engage() end` is the same shape of mistake, except deliberate -
     * so it gets the same five-second gate, and is told so.
     */
    @LuaFunction(mainThread = true)
    public final void engage() throws LuaException {
        String refusal = autopilot.engageFromComputer(level());
        if (refusal != null) throw new LuaException(refusal);
    }

    @LuaFunction(mainThread = true)
    public final void disengage() {
        autopilot.disengage();
    }

    // ---- plumbing --------------------------------------------------------

    private FlightSchedule parse(Map<?, ?> table) throws LuaException {
        try {
            return LuaSchedule.parse(table, autopilot.schedule(), CCPeripherals.lookup(level()));
        } catch (LuaSchedule.BadSchedule bad) {
            throw new LuaException(bad.getMessage());
        }
    }

    /** 1-based from Lua, 0-based in the list, and refused rather than clamped
     *  - a script that asked for stop 7 of a four-stop route has a bug, and
     *  silently editing stop 4 instead hides it. */
    private int checkStop(int index, FlightSchedule schedule) throws LuaException {
        if (index < 1 || index > schedule.entries().size()) {
            throw new LuaException("there is no stop " + index + " - the route has "
                    + schedule.entries().size());
        }
        return index - 1;
    }

    private ServerLevel level() throws LuaException {
        if (autopilot.getLevel() instanceof ServerLevel serverLevel) return serverLevel;
        throw new LuaException("this autopilot is not in a loaded world");
    }
}
