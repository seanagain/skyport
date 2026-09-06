package com.skyport.client;

import com.skyport.Skyport;
import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Draws the airport layout in the world, as particles along the lines you
 * drew on the map.
 *
 * The map editor is precise but abstract: it tells you a gate is at
 * -272, 680 and nothing in the world says so. That gap was the single most
 * confusing thing about setting an airport up - you cannot build a runway
 * where you drew one, or tow an aircraft to a taxiway, if you cannot see
 * where either is. This closes it without adding any blocks: while you hold
 * the Airport Station, its layout is traced out in front of you.
 *
 * Client-side and purely visual - particles only, no entities, nothing
 * saved. Held to a modest range and a slow tick so a large airport doesn't
 * turn into a fog bank.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, value = Dist.CLIENT)
public final class LayoutProjector {

    /** Only trace points within this distance of the player. */
    private static final double RANGE = 96;
    /** Ticks between traces. Particles linger, so this needn't be every tick. */
    private static final int INTERVAL = 10;
    /** Blocks between particles along a line. */
    private static final double SPACING = 2.0;

    private static final Vector3f RUNWAY = new Vector3f(0.95f, 0.95f, 0.92f);
    private static final Vector3f TAXIWAY = new Vector3f(0.91f, 0.76f, 0.29f);
    private static final Vector3f PATTERN = new Vector3f(0.31f, 0.56f, 0.88f);
    private static final Vector3f FINAL_LEG = new Vector3f(0.50f, 0.82f, 0.88f);
    private static final Vector3f GATE = new Vector3f(0.88f, 0.51f, 0.18f);
    private static final Vector3f PAD = new Vector3f(0.39f, 0.84f, 0.42f);
    private static final Vector3f HOLD = new Vector3f(0.84f, 0.27f, 0.31f);

    /**
     * The layout to draw, handed over by the station screen when it opens.
     *
     * Held statically because the projector has no other way to know about
     * an airport: layouts live on the server, and this only ever needs the
     * one the player was most recently looking at.
     */
    private static AirportLayout showing;

    private static int counter;

    private LayoutProjector() { }

    public static void show(AirportLayout layout) {
        showing = layout;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (showing == null) return;
        if (++counter % INTERVAL != 0) return;

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) return;

        // Only while holding a station, so the airfield isn't permanently
        // glowing at everyone standing near it.
        if (!isHoldingStation(player)) return;

        // Pairs, not a path: with two runways drawn, tracing them as one
        // connected line put a stripe across the airfield joining the end of
        // one to the start of the other.
        traceSegmentPairs(level, player, showing.waypoints(Waypoint.Type.RUNWAY), RUNWAY);
        traceSegmentPairs(level, player, showing.waypoints(Waypoint.Type.TAXIWAY), TAXIWAY);
        trace(level, player, showing.waypoints(Waypoint.Type.FINAL_LEG), FINAL_LEG, false);
        trace(level, player, showing.waypoints(Waypoint.Type.HOLDING_PATTERN), PATTERN, true);
        markers(level, player, showing.gates(), GATE);
        markers(level, player, showing.helipads(), PAD);
        // A hold point is a single place on the taxiway, marked with a pillar
        // so it can be found from the ground. It used to be drawn as a line
        // between consecutive points, which paired up unrelated ones and drew
        // lines across the airfield between them.
        for (Waypoint hold : showing.waypoints(Waypoint.Type.HOLD_SHORT)) {
            pillar(level, player, hold.pos(), HOLD);
        }
    }

    private static boolean isHoldingStation(Player player) {
        return player.getMainHandItem().getDescriptionId().endsWith("airport_station")
                || player.getOffhandItem().getDescriptionId().endsWith("airport_station");
    }

    private static void trace(Level level, Player player, List<Waypoint> points, Vector3f color, boolean loop) {
        for (int i = 1; i < points.size(); i++) {
            line(level, player, points.get(i - 1).pos(), points.get(i).pos(), color);
        }
        if (loop && points.size() > 2) {
            line(level, player, points.get(points.size() - 1).pos(), points.get(0).pos(), color);
        }
    }

    private static void traceSegmentPairs(Level level, Player player, List<Waypoint> points, Vector3f color) {
        for (int i = 0; i + 1 < points.size(); i += 2) {
            line(level, player, points.get(i).pos(), points.get(i + 1).pos(), color);
        }
    }

    private static void markers(Level level, Player player, Map<String, BlockPos> named, Vector3f color) {
        for (BlockPos pos : named.values()) pillar(level, player, pos, color);
    }

    /** A short vertical column, so a single point is visible over terrain
     *  rather than lost in the grass. */
    private static void pillar(Level level, Player player, BlockPos pos, Vector3f color) {
        if (player.distanceToSqr(pos.getX(), player.getY(), pos.getZ()) > RANGE * RANGE) return;
        int ground = groundAt(level, pos);
        for (int dy = 0; dy < 4; dy++) {
            level.addParticle(new DustParticleOptions(color, 1.4f),
                    pos.getX() + 0.5, ground + dy, pos.getZ() + 0.5, 0, 0, 0);
        }
    }

    private static void line(Level level, Player player, BlockPos from, BlockPos to, Vector3f color) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.5) return;
        int steps = (int) (length / SPACING);

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.getX() + dx * t + 0.5;
            double z = from.getZ() + dz * t + 0.5;
            if (player.distanceToSqr(x, player.getY(), z) > RANGE * RANGE) continue;
            BlockPos at = BlockPos.containing(x, 0, z);
            level.addParticle(new DustParticleOptions(color, 1.0f),
                    x, groundAt(level, at) + 0.2, z, 0, 0, 0);
        }
    }

    /** Trace along the ground rather than at the layout's stored Y, so the
     *  lines follow the terrain the player is actually standing on. */
    private static int groundAt(Level level, BlockPos pos) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
    }
}
