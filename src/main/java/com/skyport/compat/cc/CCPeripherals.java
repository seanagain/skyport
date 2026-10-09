package com.skyport.compat.cc;

import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.CraftType;
import com.skyport.data.VorBeacon;
import com.skyport.logic.LuaSchedule;
import com.skyport.registry.ModBlockEntities;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where Skyport's blocks become ComputerCraft peripherals.
 *
 * Only ever touched through {@link CCCompat}, which checks CC is installed
 * before this class is so much as named - see the note there about why that
 * matters more than it looks like it should.
 *
 * Three peripherals, because there are three sensible places to stand:
 *
 * An Autopilot is one aircraft, and it works aboard the craft - a computer
 * bolted to the aeroplane next to its autopilot, flying its own route. (CC
 * runs inside a Sable sub-level; CC: Sable exists precisely to give those
 * computers their craft's pose and velocity, which is as good a proof as any
 * that they tick there.)
 *
 * The ATC block is the whole fleet from the ground, and it can reach an
 * aircraft that is in the air right now, because every loaded autopilot is
 * findable by its aircraft id. That is the one that makes a control room
 * possible without putting a computer on every plane.
 *
 * An Airport Station is one airport's layout - what gates it has, what is
 * inbound - which is what a departures board or a gate-assignment program
 * wants and nothing more.
 */
final class CCPeripherals {

    private CCPeripherals() { }

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), ModBlockEntities.AUTOPILOT.get(),
                (blockEntity, side) -> new AutopilotPeripheral(blockEntity));
        event.registerBlockEntity(PeripheralCapability.get(), ModBlockEntities.ATC.get(),
                (blockEntity, side) -> new TowerPeripheral(blockEntity));
        event.registerBlockEntity(PeripheralCapability.get(), ModBlockEntities.AIRPORT_STATION.get(),
                (blockEntity, side) -> new StationPeripheral(blockEntity));
    }

    // ---- shared conversions ---------------------------------------------

    /**
     * The airports and beacons a script can name, backed by the live
     * registry.
     *
     * Built fresh per call rather than cached: airports are drawn, renamed
     * and broken while the server runs, and a script holding a stale view of
     * them would route an aircraft to a gate that no longer exists.
     */
    static LuaSchedule.Lookup lookup(ServerLevel level) {
        AirportRegistry registry = AirportRegistry.get(level);
        return new LuaSchedule.Lookup() {
            @Override
            public Optional<UUID> airportByName(String name) {
                return registry.all().stream()
                        .filter(airport -> airport.displayName().equalsIgnoreCase(name))
                        .map(AirportLayout::id)
                        .findFirst();
            }

            @Override
            public Optional<String> airportName(UUID id) {
                return registry.byId(id).map(AirportLayout::displayName);
            }

            @Override
            public List<String> stopNames(UUID airportId, CraftType craftType) {
                return registry.byId(airportId)
                        .map(airport -> List.copyOf(
                                (craftType.isVertical() ? airport.helipads() : airport.gates()).keySet()))
                        .orElse(List.of());
            }

            @Override
            public Optional<UUID> vorByName(String name) {
                return registry.allVors().stream()
                        .filter(vor -> vor.name().equalsIgnoreCase(name))
                        .map(VorBeacon::id)
                        .findFirst();
            }

            @Override
            public Optional<String> vorName(UUID id) {
                return registry.vorById(id).map(VorBeacon::name);
            }
        };
    }

    /** An airport as a Lua table: what it is called, and what can be done
     *  there. Positions included because a script may well want to point
     *  something at a gate. */
    static Map<String, Object> describeAirport(AirportLayout airport) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", airport.id().toString());
        out.put("name", airport.displayName());
        out.put("dimension", airport.dimension().location().toString());
        out.put("gates", namedPositions(airport.gates()));
        out.put("helipads", namedPositions(airport.helipads()));
        out.put("runways", airport.runwayCount());
        out.put("taxiSpeed", airport.taxiSpeed());
        out.put("holdingPatternHeight", airport.holdingPatternHeight());
        out.put("takeoff", LuaSchedule.luaName(airport.takeoffSense()));
        out.put("station", position(airport.stationPos()));
        return out;
    }

    static Map<String, Object> describeVor(VorBeacon vor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", vor.id().toString());
        out.put("name", vor.name());
        out.put("dimension", vor.dimension());
        out.put("position", position(vor.pos()));
        return out;
    }

    /** Gate and pad maps keyed by name, each holding its own position - so a
     *  script reads `airport.gates["Gate A"].x` rather than having to pair
     *  two parallel lists up itself. */
    private static Map<String, Object> namedPositions(Map<String, net.minecraft.core.BlockPos> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((name, pos) -> out.put(name, position(pos)));
        return out;
    }

    static Map<String, Object> position(net.minecraft.core.BlockPos pos) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pos == null) return out;
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        return out;
    }

    static Map<String, Object> position(net.minecraft.world.phys.Vec3 pos) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (pos == null) return out;
        out.put("x", pos.x);
        out.put("y", pos.y);
        out.put("z", pos.z);
        return out;
    }

    /** One line of the tower's live traffic list. */
    static Map<String, Object> describeTraffic(com.skyport.data.TrafficReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", report.planeId().toString());
        out.put("callsign", report.callsign());
        out.put("state", report.state().toLowerCase(java.util.Locale.ROOT));
        out.put("destination", report.destination());
        out.put("airborne", report.airborne());
        out.put("position", position(report.position()));
        return out;
    }

    /**
     * One line of the written-down roster, which is thinner than live traffic
     * on purpose and includes aircraft that are asleep - the whole reason the
     * tower keeps it.
     */
    static Map<String, Object> describeKnown(AirportRegistry.KnownAircraft known,
                                             AirportRegistry registry, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", known.planeId().toString());
        out.put("callsign", known.callsign());
        out.put("state", known.state().toLowerCase(java.util.Locale.ROOT));
        out.put("destination", known.destination());
        out.put("dimension", known.dimension());
        out.put("position", position(known.position()));
        out.put("awake", registry.isAwake(known.planeId(), now));
        out.put("loaded", com.skyport.blockentity.AutopilotBlockEntity.loaded(known.planeId()) != null);
        return out;
    }

    static List<Map<String, Object>> sortedTraffic(AirportRegistry registry, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        registry.allTraffic(now).stream()
                .sorted(java.util.Comparator.comparing(com.skyport.data.TrafficReport::callsign,
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(report -> out.add(describeTraffic(report)));
        return out;
    }
}
