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
import java.util.List;
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
 * for it, it covers a parked aircraft's own chunk (or a small radius, see
 * SkyportConfig) rather than a flying bubble, and it expires on its own
 * rather than needing anyone to turn it off.
 *
 * A server that would rather aircraft never sleep can set keepParkedLoaded
 * instead, and skip this entirely.
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
        int woken = 0;
        for (AirportRegistry.KnownAircraft aircraft
                : List.copyOf(AirportRegistry.get(server.overworld()).known())) {
            // Deliberately does NOT extend aircraft that already hold a
            // ticket. Opening the tower is something a player does often, and
            // pushing every expiry back another five minutes each time meant
            // the tickets never lapsed at all - the world simply stopped
            // unloading. Extending is for asking about one aircraft on
            // purpose; see wake(server, planeId).
            if (holdsTicket(aircraft.planeId())) continue;
            if (wake(server, aircraft)) woken++;
        }
        return woken;
    }

    private static boolean holdsTicket(UUID planeId) {
        for (Waking waking : ACTIVE) {
            if (waking.planeId().equals(planeId)) return true;
        }
        return false;
    }

    /**
     * How long each aircraft has been promised, separate from the tickets
     * currently held for it.
     *
     * The two are not the same thing, and conflating them is what made a
     * woken aircraft sleep again after a couple of minutes. Tickets are
     * dropped as soon as it starts flying under its own bubble - correctly,
     * they would only hold a second patch of world open at an airport it has
     * left - but the promise was "ten minutes of running", and it parks at
     * the far end with nothing holding it and goes straight back to sleep.
     * The deadline outlives the tickets so it can be reasserted there.
     */
    private static final java.util.Map<UUID, Long> WOKEN_UNTIL = new java.util.HashMap<>();

    /**
     * Re-issue a wake ticket at an aircraft's current position if its wake
     * window is still open.
     *
     * Called when one settles at a gate: that is the moment it stops holding
     * its own chunks and would otherwise sleep, however much of its wake
     * remained.
     */
    public static void reassert(MinecraftServer server, UUID planeId, String dimension, ChunkPos at) {
        Long until = WOKEN_UNTIL.get(planeId);
        if (until == null) return;

        long now = server.overworld().getGameTime();
        if (now >= until) {
            WOKEN_UNTIL.remove(planeId);
            return;
        }
        if (holdsTicket(planeId)) return;

        ServerLevel level = levelFor(server, dimension);
        if (level == null) return;

        int radius = SkyportConfig.parkedChunkRadius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(at.x + dx, at.z + dz);
                CONTROLLER.forceChunk(level, planeId, chunk.x, chunk.z, true, true);
                ACTIVE.add(new Waking(level.dimension(), chunk, planeId, until));
            }
        }
    }

    /**
     * Which wake each aircraft has already been credited for, identified by
     * its deadline.
     *
     * A wake hands the aircraft one full unattended allowance and no more.
     * Topping it back up for as long as the window stayed open meant the
     * window's minutes and the allowance's minutes added together - ten and
     * fifteen making twenty-five - which is not what either number says.
     * Pressing Wake again sets a later deadline, and that is a new wake and
     * a new grant, so asking twice still helps an aircraft that needs longer.
     */
    private static final java.util.Map<UUID, Long> GRANTED = new java.util.HashMap<>();

    /**
     * Claim the unattended allowance an open wake window owes this aircraft,
     * once.
     *
     * Pressing Wake is a player attending an aircraft as surely as standing
     * next to it is, and without this the two mechanisms cancel out: the
     * ticket forces the chunks open, the aircraft starts ticking with an
     * allowance of zero, and the first thing it does with its restored life
     * is decide it has no business being awake and drop the chunks again.
     *
     * @return true exactly once per wake, for the caller to fill its allowance
     */
    public static boolean claimWake(MinecraftServer server, UUID planeId) {
        Long until = WOKEN_UNTIL.get(planeId);
        if (until == null || server.overworld().getGameTime() >= until) return false;
        if (until.equals(GRANTED.get(planeId))) return false;
        GRANTED.put(planeId, until);
        return true;
    }

    /**
     * Drop an aircraft's wake tickets early.
     *
     * Called the moment it starts holding its own flying bubble: at that
     * point the wake ticket has done exactly what it was for and is only
     * keeping a second patch of world open behind it. The wake DEADLINE
     * survives this, so parking at the far end re-arms rather than sleeping.
     */
    public static void release(MinecraftServer server, UUID planeId) {
        ACTIVE.removeIf(waking -> {
            if (!waking.planeId().equals(planeId)) return false;
            ServerLevel level = server.getLevel(waking.dimension());
            if (level != null) {
                CONTROLLER.forceChunk(level, waking.planeId(), waking.chunk().x, waking.chunk().z, false, true);
            }
            return true;
        });
    }

    /**
     * Wake one aircraft by id - the tower listing it and someone asking for
     * that one.
     *
     * Waking the whole fleet is the blunt version, and on a server with a
     * large fleet it forces a lot of world open to get one aeroplane moving.
     * Picking the one you actually want is both cheaper and easier to reason
     * about.
     */
    public static boolean wake(MinecraftServer server, UUID planeId) {
        return AirportRegistry.get(server.overworld()).knownById(planeId)
                .map(aircraft -> wake(server, aircraft))
                .orElse(false);
    }

    private static boolean wake(MinecraftServer server, AirportRegistry.KnownAircraft aircraft) {
        int minutes = SkyportConfig.fleetWakeMinutes;
        if (minutes <= 0) return false;

        ServerLevel level = levelFor(server, aircraft.dimension());
        if (level == null) return false;

        long expiry = server.overworld().getGameTime() + minutes * 60L * 20L;
        // The promise, kept separately from the tickets - see WOKEN_UNTIL.
        WOKEN_UNTIL.put(aircraft.planeId(), expiry);

        // Push the expiry back on tickets this aircraft already holds rather
        // than skipping it. Asking again used to be a no-op for anything
        // already awake, so a schedule that needed longer than one wake
        // period could not be helped by asking again - which is exactly what
        // someone does when the aircraft has not moved.
        boolean extended = false;
        for (int i = 0; i < ACTIVE.size(); i++) {
            Waking waking = ACTIVE.get(i);
            if (!waking.planeId().equals(aircraft.planeId())) continue;
            ACTIVE.set(i, new Waking(waking.dimension(), waking.chunk(), waking.planeId(), expiry));
            extended = true;
        }
        if (extended) return true;

        ChunkPos centre = new ChunkPos(aircraft.position());
        int radius = SkyportConfig.parkedChunkRadius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(centre.x + dx, centre.z + dz);
                CONTROLLER.forceChunk(level, aircraft.planeId(), chunk.x, chunk.z, true, true);
                ACTIVE.add(new Waking(level.dimension(), chunk, aircraft.planeId(), expiry));
            }
        }
        return true;
    }

    /**
     * Aircraft asked for recently, and the player who asked, so the result
     * can be reported rather than guessed at.
     *
     * "I pressed Wake and I'm not sure anything happened" is not a useful
     * place to be, and it is where forcing a chunk and saying nothing leaves
     * you - especially since an aircraft can also start ticking because
     * something else wandered past. This checks a few seconds later whether
     * the aircraft actually began reporting, and says so either way.
     */
    private record PendingCheck(UUID planeId, UUID playerId, String callsign, long checkAtTick) { }

    private static final List<PendingCheck> PENDING = new ArrayList<>();

    /** Long enough for a woken aircraft to tick and publish, short enough to
     *  still feel like an answer to the click. */
    private static final long WAKE_CONFIRM_TICKS = 60;

    public static void confirmLater(MinecraftServer server, UUID planeId, UUID playerId, String callsign) {
        PENDING.add(new PendingCheck(planeId, playerId, callsign,
                server.overworld().getGameTime() + WAKE_CONFIRM_TICKS));
    }

    private static void runPendingChecks(MinecraftServer server, long now) {
        if (PENDING.isEmpty()) return;
        PENDING.removeIf(check -> {
            if (now < check.checkAtTick()) return false;
            var player = server.getPlayerList().getPlayer(check.playerId());
            if (player == null) return true;

            boolean running = AirportRegistry.get(server.overworld()).isAwake(check.planeId(), now);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(running
                    ? "[Skyport] " + check.callsign() + " is running again."
                    : "[Skyport] " + check.callsign() + " did not start ticking. Its chunk is "
                            + "loaded, so the craft itself may be held by Create Aeronautics "
                            + "rather than by the world - try flying out to it once."));
            return true;
        });
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
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % 20 != 0) return;

        runPendingChecks(server, now);
        WOKEN_UNTIL.values().removeIf(until -> now >= until);
        GRANTED.keySet().removeIf(planeId -> !WOKEN_UNTIL.containsKey(planeId));
        if (ACTIVE.isEmpty()) return;

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
