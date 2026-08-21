package com.skyport.world;

import com.skyport.Skyport;
import com.skyport.SkyportConfig;
import com.skyport.data.AirportRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Temporarily loads parked aircraft so a fleet can run while nobody is
 * standing at its airports.
 *
 * The problem this solves: a parked aircraft releases its chunks, which
 * means its block entity stops ticking, which means its gate wait never
 * counts down and it never departs. Correct behaviour for an unattended
 * corner of the world - the alternative is every aircraft pinning a patch
 * of world open forever - but it does mean a schedule quietly stops when
 * you walk away from it.
 *
 * So: opening the ATC block wakes every parked aircraft on the server for a
 * few minutes. They finish their waits, take off, and once airborne they
 * hold their own chunks anyway (see FlightChunkLoader), so the wake ticket
 * has done its job and can lapse. An aircraft still parked when the timer
 * runs out simply goes back to sleep.
 *
 * The cost is bounded and deliberate: it only happens when a player asks
 * for it, it covers one chunk per aircraft rather than a flying bubble, and
 * it expires on its own rather than needing anyone to turn it off.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID)
public final class FleetWake {

    public static final TicketController CONTROLLER =
            new TicketController(ResourceLocation.fromNamespaceAndPath(Skyport.MOD_ID, "fleet_wake"));

    /** One wake ticket: which chunk, in which level, and when to drop it. */
    private record Waking(ResourceKey<Level> dimension, ChunkPos chunk, UUID planeId, long expiresAtTick) { }

    private static final List<Waking> ACTIVE = new ArrayList<>();

    private FleetWake() { }

    @SubscribeEvent
    static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Wake every parked aircraft the server knows about.
     *
     * @return how many were woken, for the message back to the player
     */
    public static int wakeAll(MinecraftServer server) {
        int minutes = SkyportConfig.fleetWakeMinutes;
        if (minutes <= 0) return 0;

        ServerLevel overworld = server.overworld();
        long expiry = overworld.getGameTime() + minutes * 60L * 20L;
        Set<UUID> alreadyAwake = new HashSet<>();
        for (Waking waking : ACTIVE) alreadyAwake.add(waking.planeId());

        int woken = 0;
        for (AirportRegistry.ParkedAircraft aircraft : List.copyOf(AirportRegistry.get(overworld).parked())) {
            if (alreadyAwake.contains(aircraft.planeId())) continue;

            ServerLevel level = levelFor(server, aircraft.dimension());
            if (level == null) continue;

            ChunkPos chunk = new ChunkPos(aircraft.position());
            CONTROLLER.forceChunk(level, aircraft.planeId(), chunk.x, chunk.z, true, true);
            ACTIVE.add(new Waking(level.dimension(), chunk, aircraft.planeId(), expiry));
            woken++;
        }
        return woken;
    }

    private static ServerLevel levelFor(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        return null;
    }

    /**
     * Drop wake tickets as they expire.
     *
     * Checked once a second rather than every tick - these last minutes, and
     * a second either way is irrelevant next to the cost of scanning the list
     * sixty times as often for no reason.
     */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % 20 != 0) return;

        ACTIVE.removeIf(waking -> {
            if (now < waking.expiresAtTick()) return false;
            ServerLevel level = server.getLevel(waking.dimension());
            if (level != null) {
                CONTROLLER.forceChunk(level, waking.planeId(), waking.chunk().x, waking.chunk().z, false, true);
            }
            return true;
        });
    }
}
