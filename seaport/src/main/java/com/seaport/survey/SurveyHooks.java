package com.seaport.survey;

import com.portcore.survey.ChunkSurveyor;
import com.seaport.SeaPort;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Wiring the surveyor to the events that feed it.
 *
 * The engine lives in core, which is not a mod and has no event bus of its
 * own, so each mod attaches it to its own. That is the right way round: core
 * knows how to survey, and the mod decides when it is worth doing.
 *
 * SeaPort surveys everywhere, because a ship could be sent anywhere there is
 * water and a chart is only useful ahead of the voyage rather than during it.
 * Skyport, when it wants this for drawing maps, will reasonably want it only
 * near airports - which is why the decision is here and not in core.
 */
@EventBusSubscriber(modid = SeaPort.MOD_ID)
public final class SurveyHooks {

    /**
     * Outstanding disk reads, and surveys filed per tick.
     *
     * Conservative on purpose. The backlog is a year-old world being filled
     * in, nobody is waiting for it, and the cost of setting these too high is
     * competing with the server for its own disk at the exact moment somebody
     * is deciding whether the mod made things worse.
     */
    private static final int CONCURRENT_READS = 4;
    private static final int FILED_PER_TICK = 8;

    /** How often to notice where players are. Every tick would be sixty times
     *  a second to learn something that changes at walking pace. */
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 40;

    /** Ten seconds. Often enough to watch a backlog move, rare enough that a
     *  server catching up for an hour does not fill its log with it. */
    private static final int PROGRESS_LOG_INTERVAL_TICKS = 200;

    /** Dimensions that had work outstanding last tick, so finishing can be
     *  reported once rather than every tick for the rest of the session. */
    private static final java.util.Set<String> WAS_BUSY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SurveyHooks() { }

    @SubscribeEvent
    static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Reading the region directory tells us everything this world has
        // ever generated, for the cost of a listing - see ChunkSurveyor.
        ChunkSurveyor surveyor = ChunkSurveyor.of(level, CONCURRENT_READS, FILED_PER_TICK);
        surveyor.enumerateRegions();
        SeaPort.LOGGER.info("[survey] {}", surveyor.summary());
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) ChunkSurveyor.forget(level);
    }

    /**
     * The free route: this chunk is in memory, so surveying it costs an array
     * read and the answer is better than anything the disk could give.
     */
    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkSurveyor surveyor = ChunkSurveyor.existing(level);
        if (surveyor != null) surveyor.surveyLoaded(event.getChunk());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ChunkSurveyor surveyor = ChunkSurveyor.existing(level);
            if (surveyor == null) continue;
            surveyor.tick();

            if (level.getGameTime() % PLAYER_SCAN_INTERVAL_TICKS == 0) prioritiseAroundPlayers(level, surveyor);

            // While there is a backlog, say so occasionally. An operator who
            // has just installed this on a world with a year of exploration
            // behind it should be able to watch it catch up rather than
            // wonder whether it hung.
            //
            // And say so once when it finishes, which is the moment anybody
            // watching actually cares about. Reporting only while there is
            // work left meant the survey went silent exactly when it
            // succeeded, which reads identically to it having died.
            boolean busy = surveyor.progress().backlog() > 0;
            if (busy && level.getGameTime() % PROGRESS_LOG_INTERVAL_TICKS == 0) {
                SeaPort.LOGGER.info("[survey] {}", surveyor.summary());
            }
            if (!busy && WAS_BUSY.remove(level.dimension().location().toString())) {
                SeaPort.LOGGER.info("[survey] caught up - {}", surveyor.summary());
            } else if (busy) {
                WAS_BUSY.add(level.dimension().location().toString());
            }
        }
    }

    /**
     * Put the water people are actually near at the front of the queue.
     *
     * Without this a player sailing into unsurveyed sea waits behind a region
     * file from last year, because the backlog drains in order and the order
     * is a year old. One ring of chunks around each player is enough: they
     * are moving, and the ring moves with them.
     */
    private static void prioritiseAroundPlayers(ServerLevel level, ChunkSurveyor surveyor) {
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) surveyor.prioritise(at.x + dx, at.z + dz);
            }
        }
    }
}
