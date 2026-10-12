package com.skyport.compat.cc;

import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.VorBeacon;
import com.skyport.logic.LuaSchedule;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole network from one block: wrap an ATC block and a computer on the
 * ground can see every airport, every beacon, every aircraft in the air, and
 * every aircraft asleep at a gate.
 *
 * It can also re-route an aircraft that is flying right now, which is the
 * point. Every loaded autopilot is findable by its aircraft id, so the tower
 * does not need a computer on each aeroplane to talk to it - and a control
 * room that can only read would be a strange kind of control room.
 *
 * Aircraft are addressed by callsign or by id, whichever a script has.
 * Callsigns are what a person reads off the tower screen and what they will
 * type; ids are what a program keeps hold of when it wants to be sure. The
 * catch with callsigns is that nothing guarantees they are unique - two
 * aircraft can be named "Cargo 1" - so an ambiguous one is refused by name
 * rather than resolved to whichever came first.
 */
public class TowerPeripheral implements IPeripheral {

    private final BlockEntity tower;

    TowerPeripheral(BlockEntity blockEntity) {
        this.tower = blockEntity;
    }

    @Override
    public String getType() {
        return "skyport_tower";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof TowerPeripheral peripheral && peripheral.tower == tower;
    }

    @Override
    public Object getTarget() {
        return tower;
    }

    // ---- the network -----------------------------------------------------

    /** Every airport that has been drawn and saved, by name. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getAirports() throws LuaException {
        return LuaSchedule.luaList(CCPeripherals.sortedAirports(level()));
    }

    /** One airport by name, or nil. */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> getAirport(String name) throws LuaException {
        AirportRegistry registry = AirportRegistry.get(level());
        return registry.all().stream()
                .filter(airport -> airport.displayName().equalsIgnoreCase(name))
                .findFirst()
                .map(CCPeripherals::describeAirport)
                .orElse(null);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getVors() throws LuaException {
        return LuaSchedule.luaList(CCPeripherals.sortedVors(level()));
    }

    /**
     * What is moving right now - airborne and taxiing both.
     *
     * Live, and therefore only what is loaded and ticking. An aircraft asleep
     * at a gate is not traffic; it is on the roster below.
     */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getTraffic() throws LuaException {
        ServerLevel level = level();
        return LuaSchedule.luaList(
                CCPeripherals.sortedTraffic(AirportRegistry.get(level), level.getGameTime()));
    }

    /**
     * Every aircraft the tower has ever seen, including the ones asleep.
     *
     * This is the written-down list rather than the live one, so it survives
     * restarts and unloaded chunks and is correspondingly thinner - a
     * position, a callsign, a state. `awake` and `loaded` are the two fields
     * worth checking before trying to do anything to one.
     */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getRoster() throws LuaException {
        ServerLevel level = level();
        AirportRegistry registry = AirportRegistry.get(level);
        long now = level.getGameTime();
        List<Map<String, Object>> out = new ArrayList<>();
        registry.known().stream()
                .sorted(Comparator.comparing(AirportRegistry.KnownAircraft::callsign,
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(known -> out.add(CCPeripherals.describeKnown(known, registry, now)));
        return LuaSchedule.luaList(out);
    }

    // ---- one aircraft ----------------------------------------------------

    /**
     * Everything about one aircraft, schedule included - as much as the
     * Autopilot peripheral would give you, for a craft you are nowhere near.
     *
     * Needs the aircraft to be loaded, because a schedule lives on the
     * autopilot block and an unloaded one has nothing to read. The roster
     * says which those are.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAircraft(String which) throws LuaException {
        AutopilotBlockEntity autopilot = find(which);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callsign", autopilot.callsign());
        out.put("state", LuaSchedule.luaName(autopilot.flightState()));
        out.put("engaged", autopilot.isEngaged());
        out.put("position", CCPeripherals.position(autopilot.craftPosition()));
        out.put("currentStop", autopilot.currentStopIndex() + 1);
        UUID id = autopilot.aircraftId();
        if (id != null) out.put("id", id.toString());
        UUID subLevel = autopilot.subLevelId();
        if (subLevel != null) out.put("subLevelId", subLevel.toString());
        out.put("schedule", LuaSchedule.describe(autopilot.schedule(), CCPeripherals.lookup(level())));
        return out;
    }

    /** Re-route an aircraft from the ground. Same partial-table rules and
     *  same refusals as the Autopilot peripheral's own setSchedule. */
    @LuaFunction(mainThread = true)
    public final void setSchedule(String which, Map<?, ?> table) throws LuaException {
        AutopilotBlockEntity autopilot = find(which);
        try {
            autopilot.setSchedule(
                    LuaSchedule.parse(table, autopilot.schedule(), CCPeripherals.lookup(level())));
        } catch (LuaSchedule.BadSchedule bad) {
            throw new LuaException(bad.getMessage());
        }
    }

    @LuaFunction(mainThread = true)
    public final void engage(String which) throws LuaException {
        String refusal = find(which).engageFromComputer(level());
        if (refusal != null) throw new LuaException(refusal);
    }

    @LuaFunction(mainThread = true)
    public final void disengage(String which) throws LuaException {
        find(which).disengage();
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * The autopilot a script means, by id or by callsign.
     *
     * Ids are tried first and exactly: a program that kept one wants that
     * aircraft and no other. Callsigns are then matched case-insensitively
     * across everything loaded, and two matches is an error rather than a
     * coin toss - re-routing the wrong aeroplane because a player named two
     * of them "Cargo 1" is not a failure a script could detect afterwards.
     */
    private AutopilotBlockEntity find(String which) throws LuaException {
        try {
            AutopilotBlockEntity byId = AutopilotBlockEntity.loaded(UUID.fromString(which));
            if (byId != null) return byId;
            throw new LuaException("no loaded aircraft with that id - check the roster");
        } catch (IllegalArgumentException notAnId) {
            // Fall through: it was a callsign, not an id.
        }

        AirportRegistry registry = AirportRegistry.get(level());
        List<AutopilotBlockEntity> matches = new ArrayList<>();
        for (AirportRegistry.KnownAircraft known : registry.known()) {
            AutopilotBlockEntity autopilot = AutopilotBlockEntity.loaded(known.planeId());
            if (autopilot != null && autopilot.callsign().equalsIgnoreCase(which)) matches.add(autopilot);
        }
        if (matches.isEmpty()) {
            throw new LuaException("no loaded aircraft called \"" + which
                    + "\" - it may be asleep, or the name may have changed");
        }
        if (matches.size() > 1) {
            throw new LuaException(matches.size() + " aircraft are called \"" + which
                    + "\" - use the id from the roster instead");
        }
        return matches.get(0);
    }

    private ServerLevel level() throws LuaException {
        if (tower.getLevel() instanceof ServerLevel serverLevel) return serverLevel;
        throw new LuaException("this tower is not in a loaded world");
    }
}
