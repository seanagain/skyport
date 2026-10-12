package com.skyport.compat.cc;

import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.TrafficReport;
import com.skyport.logic.LuaSchedule;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One airport, as its own Airport Station sees it: what is drawn here, and
 * what is coming here.
 *
 * Deliberately narrower than the tower. A departures board on a terminal
 * wall, or a program that assigns gates, wants this airport and not the
 * network - and giving it only this airport means it keeps working when
 * somebody builds a second one across the valley.
 *
 * Read-only. Editing a layout is drawing on a map: gates and runways are
 * placed by eye against terrain, against where the taxiway actually runs,
 * and a script setting a gate to a coordinate it worked out arithmetically
 * would produce an airport that looks right in a table and taxis aircraft
 * into a wall. The schedules are the part worth automating; the geometry is
 * the part worth building by hand.
 */
public class StationPeripheral implements IPeripheral {

    private final AirportStationBlockEntity station;

    StationPeripheral(BlockEntity blockEntity) {
        this.station = (AirportStationBlockEntity) blockEntity;
    }

    @Override
    public String getType() {
        return "skyport_station";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof StationPeripheral peripheral && peripheral.station == station;
    }

    @Override
    public Object getTarget() {
        return station;
    }

    /**
     * This airport: name, gates, helipads, runway count, and the settings the
     * station screen edits. Nil until a layout has actually been drawn and
     * saved here, which is a state worth being able to detect - a freshly
     * placed station is a real thing a script may find.
     */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> getAirport() throws LuaException {
        return layout().map(CCPeripherals::describeAirport).orElse(null);
    }

    @LuaFunction(mainThread = true)
    @Nullable
    public final String getName() throws LuaException {
        return layout().map(AirportLayout::displayName).orElse(null);
    }

    /** Gate names only, for the common case of listing them on a monitor. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, String> getGates() throws LuaException {
        return LuaSchedule.luaList(layout()
                .map(airport -> List.copyOf(airport.gates().keySet()))
                .orElse(List.of()));
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> getHelipads() throws LuaException {
        return LuaSchedule.luaList(layout()
                .map(airport -> List.copyOf(airport.helipads().keySet()))
                .orElse(List.of()));
    }

    /**
     * Traffic for this airport: what is inbound here, and what is sitting
     * here.
     *
     * "Here" is by destination and by proximity, the same test the station's
     * own screen uses, so what a monitor shows matches what a player sees
     * when they open the block.
     */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getTraffic() throws LuaException {
        ServerLevel level = level();
        AirportRegistry registry = AirportRegistry.get(level);
        AirportLayout airport = layout().orElse(null);
        if (airport == null) return LuaSchedule.luaList(List.of());

        String name = airport.displayName();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TrafficReport report : registry.allTraffic(level.getGameTime())) {
            if (report.destination().equalsIgnoreCase(name)) {
                out.add(CCPeripherals.describeTraffic(report));
            }
        }
        return LuaSchedule.luaList(out);
    }

    private java.util.Optional<AirportLayout> layout() throws LuaException {
        UUID id = station.airportId();
        if (id == null) return java.util.Optional.empty();
        return AirportRegistry.get(level()).byId(id);
    }

    private ServerLevel level() throws LuaException {
        if (station.getLevel() instanceof ServerLevel serverLevel) return serverLevel;
        throw new LuaException("this station is not in a loaded world");
    }
}
