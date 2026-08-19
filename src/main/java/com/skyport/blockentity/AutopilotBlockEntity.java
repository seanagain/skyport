package com.skyport.blockentity;

import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.AirportSummary;
import com.skyport.data.Waypoint;
import com.skyport.network.OpenAutopilotPayload;
import com.skyport.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backs one Autopilot block. Runs a small flight state machine and, while
 * PROTOTYPE, drives a *simulated* position rather than actually moving a
 * Create Aeronautics contraption - see the big comment on
 * {@link #applyMotionTowards} for why, and what to change once you're
 * ready to wire this into a real plane.
 *
 * Everything else here - state transitions, waypoint sequencing, arrival
 * detection, telemetry - is real logic, not a stub, and is exactly what
 * would drive real contraption movement once applyMotionTowards is swapped
 * out. Test this by watching the action bar / chat while a plane "flies"
 * in place.
 *
 * Engaging checks where the plane currently is (see {@link #engage}):
 * parked on the ground somewhere that isn't a taxiway, runway or gate
 * refuses to engage (nothing to tow it onto a real path from); parked
 * correctly runs the full taxi-out/takeoff/climb/cruise/hold/approach/
 * taxi-in loop; already airborne (re-engaging mid-flight) skips straight
 * to climbing to a safe cruising altitude and heading for the destination's
 * holding pattern, since there's no ground path to follow from mid-air.
 */
public class AutopilotBlockEntity extends BlockEntity {

    public enum FlightState {
        IDLE, TAXI_OUT, TAKEOFF_ROLL, CLIMB, CRUISE, HOLDING, APPROACH, TAXI_IN
    }

    private static final double SIMULATED_SPEED_BLOCKS_PER_TICK = 0.5; // ~10 blocks/sec
    private static final double ARRIVAL_RADIUS = 1.0;
    private static final int TELEMETRY_INTERVAL_TICKS = 20; // once a second

    /** Y level CLIMB aims for before CRUISE starts covering ground distance. */
    private static final int SAFE_CRUISE_ALTITUDE = 200;
    /**
     * How far to either side of a gate/runway/taxiway line still counts as
     * "parked there" - a buffer along the line, not a radius around its
     * drawn nodes, since the plane joins the nearest point ON the line (see
     * {@link #nearestPointOnGroundPath}) rather than needing to sit on a node.
     */
    private static final double GROUND_PATH_RADIUS = 8.0;

    /** Steepest climb/descent the autopilot will command, in degrees. Applied
     *  as a cap on the vertical component of each move, so a plane pitches up
     *  to leave the runway and noses down on final rather than teleporting
     *  vertically. */
    private static final double MAX_PITCH_DEGREES = 30.0;
    private static final double MAX_PITCH_TANGENT = Math.tan(Math.toRadians(MAX_PITCH_DEGREES));
    /** How far above the terrain still counts as "on the ground" rather than airborne. */
    private static final int GROUND_HEIGHT_TOLERANCE = 6;

    @org.jetbrains.annotations.Nullable
    private UUID destinationAirportId;
    @org.jetbrains.annotations.Nullable
    private String destinationGateName;
    @org.jetbrains.annotations.Nullable
    private UUID controllingPlayerId;
    // Which airport this plane taxied out from, for TAXI_OUT/TAKEOFF_ROLL to
    // use that airport's own runway rather than the destination's. Found by
    // ground-proximity at engage() time (see findGroundOrigin), not stored -
    // same reasoning as simulatedPosition below: a prototype flight detail,
    // not worth persisting across a server restart.
    @org.jetbrains.annotations.Nullable
    private UUID originAirportId;
    // Where the plane first turns onto the ground network, so it doesn't have
    // to be parked exactly on a drawn node. Same non-persisted reasoning as
    // simulatedPosition below.
    @org.jetbrains.annotations.Nullable
    private BlockPos joinPoint;

    private FlightState state = FlightState.IDLE;
    private int currentWaypointIndex = 0;
    // Which holding-pattern point CRUISE picked as the nearest entry - HOLDING
    // starts its lap there instead of always at index 0. -1 = "not picked yet".
    private int holdingEntryIndex = -1;
    @org.jetbrains.annotations.Nullable
    private Vec3 simulatedPosition;
    // Current climb/descent angle, capped at MAX_PITCH_DEGREES. Reported in
    // telemetry; this is the value a real contraption's rotation would be
    // driven from once movement is wired up.
    private float pitchDegrees = 0;
    private int tickCounter = 0;

    public AutopilotBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AUTOPILOT.get(), pos, state);
    }

    public void openDestinationPicker(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        List<AirportSummary> airports = AirportRegistry.get(serverLevel).all().stream()
                .map(AirportSummary::of)
                .toList();
        PacketDistributor.sendToPlayer(player, new OpenAutopilotPayload(getBlockPos(), airports));
    }

    public void engage(UUID airportId, String gateName, ServerPlayer player) {
        // Deliberately go through the PLAYER for both the level and the
        // position, not this block entity's own.
        //
        // Once this block is part of an assembled Create contraption, the
        // blocks get moved into contraption-local space: getBlockPos()
        // returns a small local offset rather than a world position (which
        // read as the plane being "a million blocks" from the taxiway), and
        // getLevel() is Create's wrapper world rather than the real
        // ServerLevel. The player who just right-clicked the block is
        // standing on the plane, in the real world, so their position is a
        // reliable stand-in for where the plane actually is.
        //
        // The proper fix, once real contraption movement is wired up, is to
        // resolve the owning AbstractContraptionEntity and use
        // toGlobalVector() to convert local -> world.
        ServerLevel serverLevel = player.serverLevel();
        BlockPos reference = player.blockPosition();

        this.destinationAirportId = airportId;
        this.destinationGateName = gateName;
        this.controllingPlayerId = player.getUUID();
        this.currentWaypointIndex = 0;
        this.holdingEntryIndex = -1;
        this.simulatedPosition = player.position();

        if (isOnGround(serverLevel, reference)) {
            AirportRegistry registry = AirportRegistry.get(serverLevel);
            AirportLayout origin = findGroundOrigin(registry, reference);
            if (origin == null) {
                message(player, "Can't engage here - not parked on a taxiway or at a gate.");
                // Nothing marks these positions in the world, so a bare
                // refusal is a dead end - name somewhere concrete to tow to.
                NearestGround nearest = findNearestGround(registry, reference);
                if (nearest == null) {
                    message(player, "No airport has any runway, taxiway or gate drawn yet. Draw one at an Airport Station first.");
                } else {
                    message(player, String.format("Nearest is %s at %d, %d (%.0f blocks away) - tow the plane there.",
                            nearest.description(), nearest.pos().getX(), nearest.pos().getZ(), nearest.distance()));
                }
                return;
            }
            this.originAirportId = origin.id();
            // Turn onto the line before following it, so parking anywhere
            // within the buffer alongside a taxiway is good enough.
            this.joinPoint = nearestPointOnGroundPath(origin, reference);
            setState(FlightState.TAXI_OUT);
            message(player, "Autopilot engaged - joining the taxiway, then out to the runway.");
        } else {
            this.originAirportId = null;
            setState(FlightState.CLIMB);
            message(player, "Autopilot engaged in flight - climbing to cruising altitude.");
        }
    }

    public void disengage() {
        setState(FlightState.IDLE);
        destinationAirportId = null;
        destinationGateName = null;
        controllingPlayerId = null;
        originAirportId = null;
        joinPoint = null;
        pitchDegrees = 0;
        setChanged();
    }

    /** Runs every server tick this block entity is loaded and ticking. */
    public void serverTick() {
        if (state == FlightState.IDLE || !(level instanceof ServerLevel serverLevel)) return;
        if (destinationAirportId == null || simulatedPosition == null) return;

        AirportLayout destination = AirportRegistry.get(serverLevel).byId(destinationAirportId).orElse(null);
        if (destination == null) {
            // Destination vanished (deleted station?) - park it rather than
            // fly forever toward nothing.
            disengage();
            return;
        }

        switch (state) {
            // Back out of the gate along its taxiway spur, then forward
            // along the rest of the taxiway network to the runway's gate
            // end - see groundTaxiPath. Simplification: this walks every
            // taxiway point in the order they were drawn rather than
            // pathfinding a specific gate's spur, so with multiple gates
            // every plane currently taxis the whole network, not just its
            // own branch. Fine for one or two gates; revisit with a real
            // graph if that gets confusing with more.
            case TAXI_OUT -> {
                AirportLayout origin = originLayout(serverLevel);
                List<BlockPos> path = new ArrayList<>();
                if (joinPoint != null) path.add(joinPoint);
                if (origin != null) path.addAll(groundTaxiPath(origin, false));
                followWaypoints(path, () -> setState(FlightState.TAKEOFF_ROLL));
            }
            case TAKEOFF_ROLL -> {
                AirportLayout origin = originLayout(serverLevel);
                List<BlockPos> runway = origin != null ? positionsOf(origin, Waypoint.Type.RUNWAY) : List.of();
                followWaypoints(runway, () -> setState(FlightState.CLIMB));
            }
            // Climb toward where we're going, not straight up: the pitch cap
            // in applyMotionTowards limits how fast altitude comes, so this
            // covers ground on the way up the way a real departure does.
            // Straight up would have zero horizontal distance to pitch
            // against, which the cap can't express as an angle at all.
            case CLIMB -> {
                List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
                BlockPos ahead = loop.isEmpty()
                        ? new BlockPos((int) Math.round(simulatedPosition.x) + 200, SAFE_CRUISE_ALTITUDE, (int) Math.round(simulatedPosition.z))
                        : withY(loop.get(nearestIndex(loop, simulatedPosition)), SAFE_CRUISE_ALTITUDE);
                applyMotionTowards(ahead);
                if (simulatedPosition.y >= SAFE_CRUISE_ALTITUDE - 1) setState(FlightState.CRUISE);
            }
            case CRUISE -> {
                List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
                if (loop.isEmpty()) {
                    setState(FlightState.APPROACH);
                } else {
                    if (holdingEntryIndex < 0) holdingEntryIndex = nearestIndex(loop, simulatedPosition);
                    BlockPos entry = withY(loop.get(holdingEntryIndex), destination.holdingPatternHeight());
                    if (applyMotionTowards(entry)) setState(FlightState.HOLDING);
                }
            }
            // One full lap of the holding pattern starting at the point CRUISE
            // entered from, in the layout's configured direction, then
            // cleared to land - no real ATC/queueing yet (see DESIGN.md).
            case HOLDING -> followWaypoints(holdingLap(destination), () -> setState(FlightState.APPROACH));
            // Final leg (holding pattern -> runway far end, descending), then
            // roll down the runway to the gate end, ready to taxi in.
            case APPROACH -> followWaypoints(approachPath(destination), () -> setState(FlightState.TAXI_IN));
            case TAXI_IN -> followWaypoints(groundTaxiPath(destination, true), this::arrive);
            default -> { }
        }

        if (++tickCounter % TELEMETRY_INTERVAL_TICKS == 0) {
            sendTelemetry(serverLevel.getServer());
        }
    }

    private void arrive() {
        message("Arrived at gate " + destinationGateName + ".");
        disengage();
    }

    private void setState(FlightState newState) {
        this.state = newState;
        this.currentWaypointIndex = 0;
        setChanged();
        message(switch (newState) {
            case TAKEOFF_ROLL -> "On the runway, taking off.";
            case CLIMB -> "Climbing to cruising altitude.";
            case CRUISE -> "Airborne, cruising toward destination.";
            case HOLDING -> "Entering the holding pattern - waiting for clearance.";
            case APPROACH -> "Cleared to land, on final approach.";
            case TAXI_IN -> "Landed, taxiing to gate.";
            default -> null;
        });
    }

    /** Walks `targets` in order, one at a time, calling onFinished once the last is reached. */
    private void followWaypoints(List<BlockPos> targets, Runnable onFinished) {
        if (targets.isEmpty() || currentWaypointIndex >= targets.size()) {
            onFinished.run();
            return;
        }
        if (applyMotionTowards(targets.get(currentWaypointIndex))) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= targets.size()) onFinished.run();
        }
    }

    /**
     * PROTOTYPE ONLY: advances {@link #simulatedPosition} toward `target`
     * at a fixed speed and reports whether it arrived this tick. This
     * stands in for actually moving a Create Aeronautics contraption,
     * which needs APIs from that mod this scaffold was written without
     * access to (see README "Before your first real build"). Everything
     * that CALLS this method - the state machine above - is the real
     * logic; only this one method needs to change to move a real plane
     * instead of a phantom position: look at how Create's own train
     * entities update their position each tick and apply the same delta
     * to the contraption here instead of to `simulatedPosition`.
     */
    private boolean applyMotionTowards(BlockPos target) {
        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 delta = targetCenter.subtract(simulatedPosition);
        double distance = delta.length();
        if (distance <= ARRIVAL_RADIUS) {
            simulatedPosition = targetCenter;
            pitchDegrees = 0;
            return true;
        }

        Vec3 step = delta.normalize().scale(Math.min(SIMULATED_SPEED_BLOCKS_PER_TICK, distance));

        // Cap how steeply the plane climbs or descends. A plane can't gain
        // altitude vertically - it noses up and covers ground while doing it,
        // so the vertical part of each step is limited to what
        // MAX_PITCH_DEGREES allows for the horizontal distance travelled.
        double horizontal = Math.sqrt(step.x * step.x + step.z * step.z);
        double maxVertical = horizontal * MAX_PITCH_TANGENT;
        if (horizontal > 1.0e-4 && Math.abs(step.y) > maxVertical) {
            step = new Vec3(step.x, Math.copySign(maxVertical, step.y), step.z);
        }

        simulatedPosition = simulatedPosition.add(step);
        pitchDegrees = horizontal > 1.0e-4
                ? (float) Math.toDegrees(Math.atan2(step.y, horizontal))
                : (float) Math.copySign(90, step.y);
        return false;
    }

    private static List<BlockPos> positionsOf(AirportLayout layout, Waypoint.Type type) {
        return layout.waypoints(type).stream().map(Waypoint::pos).toList();
    }

    private static BlockPos withY(BlockPos pos, int y) {
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static int nearestIndex(List<BlockPos> points, Vec3 from) {
        int best = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = Vec3.atCenterOf(points.get(i)).distanceToSqr(from);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = i;
            }
        }
        return best;
    }

    /** One lap of the holding pattern starting at holdingEntryIndex, in the
     *  layout's configured direction, ending back where it started. */
    private List<BlockPos> holdingLap(AirportLayout destination) {
        List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
        int n = loop.size();
        if (n == 0) return List.of();
        int start = holdingEntryIndex >= 0 && holdingEntryIndex < n ? holdingEntryIndex : 0;
        int step = destination.holdingPatternClockwise() ? 1 : -1;
        List<BlockPos> lap = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            int at = Math.floorMod(start + i * step, n);
            lap.add(withY(loop.get(at), destination.holdingPatternHeight()));
        }
        return lap;
    }

    /** Every taxiway point (backbone + every gate's spur, in drawing order),
     *  ending at the runway's gate end - or, reversed, starting there and
     *  ending at the named destination gate. See the TAXI_OUT case comment
     *  for the multi-gate simplification this implies. */
    private List<BlockPos> groundTaxiPath(AirportLayout layout, boolean arriving) {
        List<BlockPos> taxiway = new ArrayList<>(positionsOf(layout, Waypoint.Type.TAXIWAY));
        List<BlockPos> runway = positionsOf(layout, Waypoint.Type.RUNWAY);
        BlockPos gateEnd = runway.isEmpty() ? null : runway.get(0);

        List<BlockPos> path = new ArrayList<>();
        if (arriving) {
            java.util.Collections.reverse(taxiway);
            path.addAll(taxiway);
            BlockPos gate = layout.gates().get(destinationGateName);
            if (gate != null) path.add(gate);
        } else {
            path.addAll(taxiway);
            if (gateEnd != null) path.add(gateEnd);
        }
        return path;
    }

    /** Final leg (holding pattern side -> runway far end) then the runway
     *  itself down to the gate end, ready for TAXI_IN. */
    private static List<BlockPos> approachPath(AirportLayout destination) {
        List<BlockPos> path = new ArrayList<>(positionsOf(destination, Waypoint.Type.FINAL_LEG));
        List<BlockPos> runway = positionsOf(destination, Waypoint.Type.RUNWAY);
        if (runway.size() == 2) path.add(runway.get(0));
        return path;
    }

    @org.jetbrains.annotations.Nullable
    private AirportLayout originLayout(ServerLevel level) {
        return originAirportId == null ? null : AirportRegistry.get(level).byId(originAirportId).orElse(null);
    }

    /** Finds the first registered airport whose runway, taxiway, a gate, or a
     *  gate's short segment to the runway's gate end passes within
     *  GROUND_PATH_RADIUS of this block - i.e. "is this plane actually
     *  parked somewhere on charted airport ground infrastructure." */
    @org.jetbrains.annotations.Nullable
    private AirportLayout findGroundOrigin(AirportRegistry registry, BlockPos pos) {
        for (AirportLayout candidate : registry.all()) {
            if (isNearGroundPath(candidate, pos)) return candidate;
        }
        return null;
    }

    /**
     * The closest point ON a taxiway or runway line (not just the closest
     * drawn node) to `from`. Engaging steers the plane here first, so parking
     * anywhere within the buffer alongside a line is enough - the plane turns
     * and joins the line itself rather than needing to sit exactly on a node.
     */
    @org.jetbrains.annotations.Nullable
    private static BlockPos nearestPointOnGroundPath(AirportLayout layout, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        List<BlockPos> runway = positionsOf(layout, Waypoint.Type.RUNWAY);
        if (runway.size() == 2) {
            BlockPos p = closestPointOnSegment(runway.get(0), runway.get(1), from);
            best = p;
            bestDist = horizontalDistance(p, from);
        }
        List<BlockPos> taxiway = positionsOf(layout, Waypoint.Type.TAXIWAY);
        for (int i = 0; i + 1 < taxiway.size(); i += 2) {
            BlockPos p = closestPointOnSegment(taxiway.get(i), taxiway.get(i + 1), from);
            double d = horizontalDistance(p, from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** The point on segment a-b closest to p, keeping a/b's Y (ground height
     *  varies; the horizontal projection is what matters here). */
    private static BlockPos closestPointOnSegment(BlockPos a, BlockPos b, BlockPos p) {
        double ax = a.getX(), az = a.getZ();
        double dx = b.getX() - ax, dz = b.getZ() - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0 : ((p.getX() - ax) * dx + (p.getZ() - az) * dz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        return new BlockPos(
                (int) Math.round(ax + t * dx),
                (int) Math.round(a.getY() + t * (b.getY() - a.getY())),
                (int) Math.round(az + t * dz));
    }

    /** The closest piece of drawn ground infrastructure to this block, across
     *  every registered airport, for the "tow it here" hint. */
    private record NearestGround(String description, BlockPos pos, double distance) { }

    @org.jetbrains.annotations.Nullable
    private NearestGround findNearestGround(AirportRegistry registry, BlockPos from) {
        NearestGround best = null;
        for (AirportLayout layout : registry.all()) {
            for (var gate : layout.gates().entrySet()) {
                best = closer(best, new NearestGround(
                        layout.displayName() + " " + gate.getKey(), gate.getValue(),
                        horizontalDistance(gate.getValue(), from)));
            }
            best = closer(best, nearestOnPoints(layout, Waypoint.Type.RUNWAY, "runway", from));
            best = closer(best, nearestOnPoints(layout, Waypoint.Type.TAXIWAY, "taxiway", from));
        }
        return best;
    }

    @org.jetbrains.annotations.Nullable
    private static NearestGround nearestOnPoints(AirportLayout layout, Waypoint.Type type, String label, BlockPos from) {
        NearestGround best = null;
        for (BlockPos point : positionsOf(layout, type)) {
            best = closer(best, new NearestGround(
                    layout.displayName() + " " + label, point, horizontalDistance(point, from)));
        }
        return best;
    }

    @org.jetbrains.annotations.Nullable
    private static NearestGround closer(@org.jetbrains.annotations.Nullable NearestGround a,
                                        @org.jetbrains.annotations.Nullable NearestGround b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.distance() < a.distance() ? b : a;
    }

    private static boolean isNearGroundPath(AirportLayout layout, BlockPos pos) {
        for (BlockPos gate : layout.gates().values()) {
            if (horizontalDistance(gate, pos) <= GROUND_PATH_RADIUS) return true;
        }
        if (isNearSegment(positionsOf(layout, Waypoint.Type.RUNWAY), pos)) return true;
        // Taxiway is a set of 2-point segments (backbone + one per gate
        // spur, see AirportMapScreen), not one line - check every pair.
        List<BlockPos> taxiway = positionsOf(layout, Waypoint.Type.TAXIWAY);
        for (int i = 0; i + 1 < taxiway.size(); i += 2) {
            if (distanceToSegment(taxiway.get(i), taxiway.get(i + 1), pos) <= GROUND_PATH_RADIUS) return true;
        }
        return false;
    }

    private static boolean isNearSegment(List<BlockPos> line, BlockPos pos) {
        return line.size() == 2 && distanceToSegment(line.get(0), line.get(1), pos) <= GROUND_PATH_RADIUS;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Closest distance from `p` to the line segment a-b, ignoring Y (ground
     *  proximity is a horizontal question - ramps and terrain height vary). */
    private static double distanceToSegment(BlockPos a, BlockPos b, BlockPos p) {
        double ax = a.getX(), az = a.getZ();
        double bx = b.getX(), bz = b.getZ();
        double dx = bx - ax, dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0 : ((p.getX() - ax) * dx + (p.getZ() - az) * dz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double closestX = ax + t * dx;
        double closestZ = az + t * dz;
        double ddx = p.getX() - closestX, ddz = p.getZ() - closestZ;
        return Math.sqrt(ddx * ddx + ddz * ddz);
    }

    /** "On the ground" = close to the terrain surface below, as opposed to
     *  mid-flight. A real contraption would have proper flight/grounded
     *  state to read instead of this heightmap proxy - see class doc. */
    private static boolean isOnGround(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        return pos.getY() <= surfaceY + GROUND_HEIGHT_TOLERANCE;
    }

    private void sendTelemetry(MinecraftServer server) {
        if (controllingPlayerId == null || simulatedPosition == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(controllingPlayerId);
        if (player == null) return;
        String pos = String.format("%.0f, %.0f, %.0f", simulatedPosition.x, simulatedPosition.y, simulatedPosition.z);
        String pitch = Math.abs(pitchDegrees) < 1 ? "level" : String.format("%+.0f deg", pitchDegrees);
        player.displayClientMessage(Component.literal("[Skyport] " + state + " @ " + pos + "  " + pitch), true);
    }

    private void message(String text) {
        if (level == null || level.isClientSide || controllingPlayerId == null) return;
        ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(controllingPlayerId);
        message(player, text);
    }

    private void message(@org.jetbrains.annotations.Nullable ServerPlayer player, @org.jetbrains.annotations.Nullable String text) {
        if (player == null || text == null) return;
        player.sendSystemMessage(Component.literal("[Skyport] " + text));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (destinationAirportId != null) tag.putUUID("destinationAirportId", destinationAirportId);
        if (destinationGateName != null) tag.putString("destinationGateName", destinationGateName);
        if (controllingPlayerId != null) tag.putUUID("controllingPlayerId", controllingPlayerId);
        tag.putString("state", state.name());
        tag.putInt("currentWaypointIndex", currentWaypointIndex);
        // originAirportId, holdingEntryIndex and simulatedPosition are
        // intentionally NOT persisted - this is a prototype stand-in for
        // real contraption movement, not worth preserving across a server
        // restart. Re-initializes next time it starts moving.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("destinationAirportId")) destinationAirportId = tag.getUUID("destinationAirportId");
        if (tag.contains("destinationGateName")) destinationGateName = tag.getString("destinationGateName");
        if (tag.hasUUID("controllingPlayerId")) controllingPlayerId = tag.getUUID("controllingPlayerId");
        if (tag.contains("state")) state = FlightState.valueOf(tag.getString("state"));
        currentWaypointIndex = tag.getInt("currentWaypointIndex");
        if (state != FlightState.IDLE) simulatedPosition = getBlockPos().getCenter();
    }
}
