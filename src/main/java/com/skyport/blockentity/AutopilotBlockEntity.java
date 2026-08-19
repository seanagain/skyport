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
    /** How close (horizontally) to a gate/runway/taxiway line counts as "parked there". */
    private static final double GROUND_PATH_RADIUS = 4.0;
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

    private FlightState state = FlightState.IDLE;
    private int currentWaypointIndex = 0;
    // Which holding-pattern point CRUISE picked as the nearest entry - HOLDING
    // starts its lap there instead of always at index 0. -1 = "not picked yet".
    private int holdingEntryIndex = -1;
    @org.jetbrains.annotations.Nullable
    private Vec3 simulatedPosition;
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
        if (!(level instanceof ServerLevel serverLevel)) return;

        this.destinationAirportId = airportId;
        this.destinationGateName = gateName;
        this.controllingPlayerId = player.getUUID();
        this.currentWaypointIndex = 0;
        this.holdingEntryIndex = -1;
        this.simulatedPosition = getBlockPos().getCenter();

        if (isOnGround(serverLevel)) {
            AirportLayout origin = findGroundOrigin(AirportRegistry.get(serverLevel));
            if (origin == null) {
                message(player, "Can't engage here - not parked on a taxiway or at a gate. Tow this plane onto marked airport ground infrastructure first.");
                return;
            }
            this.originAirportId = origin.id();
            setState(FlightState.TAXI_OUT);
            message(player, "Autopilot engaged - taxiing out toward runway.");
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
                followWaypoints(origin != null ? groundTaxiPath(origin, false) : List.of(), () -> setState(FlightState.TAKEOFF_ROLL));
            }
            case TAKEOFF_ROLL -> {
                AirportLayout origin = originLayout(serverLevel);
                List<BlockPos> runway = origin != null ? positionsOf(origin, Waypoint.Type.RUNWAY) : List.of();
                followWaypoints(runway, () -> setState(FlightState.CLIMB));
            }
            case CLIMB -> {
                BlockPos climbTarget = new BlockPos((int) Math.round(simulatedPosition.x), SAFE_CRUISE_ALTITUDE, (int) Math.round(simulatedPosition.z));
                if (applyMotionTowards(climbTarget)) setState(FlightState.CRUISE);
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
            return true;
        }
        simulatedPosition = simulatedPosition.add(delta.normalize().scale(Math.min(SIMULATED_SPEED_BLOCKS_PER_TICK, distance)));
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
    private AirportLayout findGroundOrigin(AirportRegistry registry) {
        BlockPos pos = getBlockPos();
        for (AirportLayout candidate : registry.all()) {
            if (isNearGroundPath(candidate, pos)) return candidate;
        }
        return null;
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
    private boolean isOnGround(ServerLevel level) {
        BlockPos pos = getBlockPos();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        return pos.getY() <= surfaceY + GROUND_HEIGHT_TOLERANCE;
    }

    private void sendTelemetry(MinecraftServer server) {
        if (controllingPlayerId == null || simulatedPosition == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(controllingPlayerId);
        if (player == null) return;
        String pos = String.format("%.0f, %.0f, %.0f", simulatedPosition.x, simulatedPosition.y, simulatedPosition.z);
        player.displayClientMessage(Component.literal("[Skyport] " + state + " @ " + pos), true);
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
