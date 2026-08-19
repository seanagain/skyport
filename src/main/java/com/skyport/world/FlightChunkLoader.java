package com.skyport.world;

import com.skyport.Skyport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunks a plane is flying through loaded.
 *
 * This is the problem the project deferred from the start: a contraption in
 * an unloaded chunk stops being simulated, so planes freeze mid-route, and
 * a queue of them piles up at the same spot waiting to be looked at. Create's
 * trains dodge it by tracking position as lightweight data independent of
 * chunk loading and only reassembling when observed, which is a large piece
 * of engineering. Force-loading a moving bubble around each plane is the
 * blunter answer, and the one that works with a real rigid body.
 *
 * The cost is real: every plane in the air holds a patch of world loaded,
 * which is exactly the server load this addon set out to avoid. It's kept
 * as small as it can be - a radius around the plane, only while a flight is
 * actually engaged, released the moment it lands or disengages - and the
 * tickets are non-ticking where possible so entities elsewhere in those
 * chunks don't start running too.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FlightChunkLoader {

    /** Chunks either side of the plane to keep loaded. 2 gives a 5x5 block
     *  of chunks - enough for the craft plus somewhere to arrive. */
    private static final int RADIUS_CHUNKS = 2;

    public static final TicketController CONTROLLER =
            new TicketController(ResourceLocation.fromNamespaceAndPath(Skyport.MOD_ID, "flight"));

    private FlightChunkLoader() { }

    @SubscribeEvent
    static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Moves this plane's loaded bubble to sit around `centre`, adding and
     * dropping tickets only where the set actually changed - re-issuing every
     * ticket every tick would churn chunk loading pointlessly.
     *
     * @param held the chunks currently forced for this plane; updated in place
     */
    public static void follow(ServerLevel level, UUID planeId, ChunkPos centre, Set<ChunkPos> held) {
        Set<ChunkPos> wanted = new HashSet<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                wanted.add(new ChunkPos(centre.x + dx, centre.z + dz));
            }
        }

        for (ChunkPos pos : wanted) {
            if (held.add(pos)) {
                CONTROLLER.forceChunk(level, planeId, pos.x, pos.z, true, true);
            }
        }
        held.removeIf(pos -> {
            if (wanted.contains(pos)) return false;
            CONTROLLER.forceChunk(level, planeId, pos.x, pos.z, false, true);
            return true;
        });
    }

    /** Drops every chunk this plane was holding - on landing, disengaging, or
     *  the block being broken. Leaving these behind would pin the world open. */
    public static void releaseAll(ServerLevel level, UUID planeId, Set<ChunkPos> held) {
        for (ChunkPos pos : held) {
            CONTROLLER.forceChunk(level, planeId, pos.x, pos.z, false, true);
        }
        held.clear();
    }
}
