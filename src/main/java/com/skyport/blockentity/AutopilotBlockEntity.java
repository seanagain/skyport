package com.skyport.blockentity;

import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.AirportSummary;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
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

import com.skyport.block.AutopilotBlock;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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
public class AutopilotBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

    public enum FlightState {
        IDLE, TAXI_OUT, TAKEOFF_ROLL, CLIMB, CRUISE, HOLDING, APPROACH, TAXI_IN, WAITING
    }

    /** How near a player counts as "boarded" for WaitCondition.PLAYER. */
    private static final double BOARDING_RADIUS = 8.0;

    private static final double SIMULATED_SPEED_BLOCKS_PER_TICK = 0.5; // ~10 blocks/sec
    private static final double ARRIVAL_RADIUS = 1.0;
    private static final int TELEMETRY_INTERVAL_TICKS = 20; // once a second

    /** Y level CLIMB aims for before CRUISE starts covering ground distance. */
    private static final int SAFE_CRUISE_ALTITUDE = 200;
    /** How far ahead CLIMB keeps its target, so there's always ground
     *  distance left to pitch against. See {@link #climbTarget}. */
    private static final int CLIMB_LOOKAHEAD_BLOCKS = 300;
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

    // --- real-craft steering (see steerCraftTowards) ---
    // Airborne speed is per-schedule (FlightSchedule#cruiseSpeed) rather than
    // a constant - how fast is worth flying depends on the route.
    /** Taxi speed - slower, so the craft actually settles on tightly spaced
     *  ground waypoints instead of sailing past them and turning back. */
    private static final double CRAFT_TAXI_SPEED = 4.0;
    /** Rotation speed, reached by half the runway's length. */
    private static final double CRAFT_TAKEOFF_SPEED = 14.0;
    /** Climb-out angle. Shallower than the 30-degree structural cap because
     *  a departure that steep looks like a rocket, not a plane. */
    private static final double CLIMB_PITCH_DEGREES = 20.0;
    /** Start turning into the holding pattern this far out, so the plane
     *  banks into it rather than reaching the entry point and pivoting. */
    private static final double HOLDING_ENTRY_LEAD_BLOCKS = 20.0;
    /** Fraction of the velocity error corrected per physics tick. Low on
     *  purpose: a heavy contraption yanked to a new velocity looks wrong. */
    private static final double CRAFT_STEER_GAIN = 0.25;
    /** Slows the craft as it closes on a waypoint so it settles rather than
     *  overshooting - speed is capped at distance * this. */
    private static final double CRAFT_APPROACH_GAIN = 0.6;
    /** Planes are big; "arrived" has to be looser than for a point. */
    private static final double CRAFT_ARRIVAL_RADIUS = 6.0;
    /** How hard the craft turns toward its heading, and the ceiling on how
     *  fast it may rotate (radians/second) - a contraption spinning to face a
     *  new waypoint instantly looks wrong. */
    private static final double TURN_GAIN = 1.5;
    private static final double MAX_TURN_RATE_RAD_PER_SEC = 0.9;
    /** Fraction of the rotation error corrected per tick; damped so the craft
     *  settles on a heading instead of oscillating around it. */
    private static final double TURN_DAMPING = 0.3;
    /** Levelling on the ground is firmer: wheel contact keeps feeding roll
     *  back in, so a gentle correction loses to it. */
    private static final double GROUND_LEVEL_GAIN = 3.0;
    private static final double GROUND_TURN_DAMPING = 0.5;
    /** Throttle floor when pointing the wrong way - see alignmentFactor. */
    private static final double MIN_MISALIGNED_THROTTLE = 0.15;
    /** Hold on the centreline before rolling: at least this long, until
     *  aligned, and never longer than the cap. */
    private static final int TAKEOFF_MIN_HOLD_TICKS = 20;
    private static final int TAKEOFF_MAX_HOLD_TICKS = 100;
    private static final double TAKEOFF_ALIGNMENT_THRESHOLD = 0.98;
    /** How far above the terrain still counts as "on the ground" rather than airborne. */
    private static final int GROUND_HEIGHT_TOLERANCE = 6;

    // The itinerary, and which stop we're currently flying to. The
    // destination airport/gate are read off the current entry rather than
    // stored separately, so there's one source of truth.
    private FlightSchedule schedule = new FlightSchedule();
    private int scheduleIndex = 0;
    /** Ticks left of a WAITING hold; only meaningful while state == WAITING. */
    private int waitTicksRemaining = 0;

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
    /** Where the takeoff roll began, to measure how far down the runway the
     *  plane has got. Transient, like the rest of the in-flight values. */
    @org.jetbrains.annotations.Nullable
    private Vec3 takeoffStart;
    /** Ticks spent lined up on the centreline before the roll begins. */
    private int takeoffHoldTicks = 0;

    private FlightState state = FlightState.IDLE;
    private int currentWaypointIndex = 0;
    // Which holding-pattern point CRUISE picked as the nearest entry - HOLDING
    // starts its lap there instead of always at index 0. -1 = "not picked yet".
    private int holdingEntryIndex = -1;
    // Set only for the duration of a sable$physicsTick callback - its
    // presence is what switches applyMotionTowards from simulating to
    // actually flying.
    @org.jetbrains.annotations.Nullable
    private transient RigidBodyHandle activeBody;
    // Kept between callbacks so engage() can read the craft's real position
    // rather than approximating it from the player's.
    @org.jetbrains.annotations.Nullable
    private transient ServerSubLevel activeSubLevel;
    // Not Long.MIN_VALUE: `gameTime - this` would overflow to a negative
    // number and make the "was I physics-ticked recently" check below always
    // true, silently disabling the simulated path.
    private long lastPhysicsTickGameTime = -1000;

    /** The craft's position: real when assembled, phantom when this block is
     *  just sitting in the world (which is still useful for testing a layout
     *  without building a plane). */
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

    /**
     * Where the craft actually is, if this block is mounted on one. Falls
     * back to {@code fallback} for a block placed loose in the world.
     *
     * This is the real fix for the "a million blocks away" reading: mounted
     * on a craft, getBlockPos() is a sub-level-local coordinate, and the
     * sub-level's pose is what maps it back onto the world.
     */
    private BlockPos craftPositionOr(BlockPos fallback) {
        if (activeSubLevel == null || activeSubLevel.isRemoved()) return fallback;
        return BlockPos.containing(activeSubLevel.logicalPose().transformPosition(getBlockPos().getCenter()));
    }

    @org.jetbrains.annotations.Nullable
    private ScheduleEntry currentEntry() {
        if (scheduleIndex < 0 || scheduleIndex >= schedule.entries().size()) return null;
        return schedule.entries().get(scheduleIndex);
    }

    @org.jetbrains.annotations.Nullable
    private UUID destinationAirportId() {
        ScheduleEntry entry = currentEntry();
        return entry == null ? null : entry.airportId();
    }

    @org.jetbrains.annotations.Nullable
    private String destinationGateName() {
        ScheduleEntry entry = currentEntry();
        return entry == null ? null : entry.gateName();
    }

    public void engage(FlightSchedule newSchedule, ServerPlayer player) {
        if (newSchedule.isEmpty()) {
            message(player, "Schedule is empty - add at least one stop first.");
            return;
        }
        this.schedule = newSchedule;
        this.scheduleIndex = 0;
        this.controllingPlayerId = player.getUUID();
        // Prefer the craft's own position when it's assembled - that's the
        // real answer. The player's position is the fallback for a block
        // sitting loose in the world, where there's no craft to ask.
        engageCurrentLeg(player.serverLevel(), craftPositionOr(player.blockPosition()), player);
    }

    /**
     * @param reference where the plane actually is. The player's position on
     *                  the first leg (they're stood on it, and the block's own
     *                  coordinates are unreliable once assembled - see below);
     *                  the plane's own tracked position on later legs, since
     *                  by then it has flown somewhere the player may not be.
     */
    private void engageCurrentLeg(ServerLevel serverLevel, BlockPos reference,
                                  @org.jetbrains.annotations.Nullable ServerPlayer player) {
        // Note what this deliberately does NOT use: this block entity's own
        // getBlockPos() and getLevel().
        //
        // Once the block is part of an assembled Create contraption, its
        // blocks live in contraption-local space - getBlockPos() returns a
        // small local offset rather than a world position (which read as the
        // plane being "a million blocks" from the taxiway), and getLevel() is
        // Create's wrapper world rather than the real ServerLevel, which
        // would make this method bail out entirely.
        //
        // The proper fix, once real contraption movement is wired up, is to
        // resolve the owning AbstractContraptionEntity and use
        // toGlobalVector() to convert local -> world.
        this.currentWaypointIndex = 0;
        this.holdingEntryIndex = -1;
        this.simulatedPosition = reference.getCenter();

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
        controllingPlayerId = null;
        originAirportId = null;
        joinPoint = null;
        takeoffStart = null;
        takeoffHoldTicks = 0;
        pitchDegrees = 0;
        waitTicksRemaining = 0;
        setChanged();
    }

    /**
     * Sable's hook: called every physics tick while this block is part of an
     * assembled craft, handing over that craft's rigid body. This is where
     * the autopilot stops being a simulation and actually flies something.
     *
     * The state machine is shared with {@link #serverTick} - the only
     * difference is that {@link #applyMotionTowards} steers the real body
     * while {@code activeBody} is set, instead of advancing a phantom
     * position. Everything above it (waypoint sequencing, states, schedule)
     * is the same code either way.
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle body, double deltaSeconds) {
        if (state == FlightState.IDLE) return;
        if (!(subLevel.getLevel() instanceof ServerLevel serverLevel)) return;

        this.activeSubLevel = subLevel;
        this.lastPhysicsTickGameTime = serverLevel.getGameTime();

        // The craft's real world position: this block sits at local
        // coordinates inside the sub-level, and the sub-level's pose maps
        // those onto the world. This is the honest version of the position
        // engage() currently approximates with the player's.
        this.simulatedPosition = subLevel.logicalPose().transformPosition(getBlockPos().getCenter());

        this.activeBody = body;
        try {
            runFlightLogic(serverLevel);
        } finally {
            // Only valid for the duration of this callback.
            this.activeBody = null;
        }
    }

    /** Runs every server tick this block entity is loaded and ticking. */
    public void serverTick() {
        if (state == FlightState.IDLE || !(level instanceof ServerLevel serverLevel)) return;
        if (simulatedPosition == null) return;
        // When mounted on a craft, sable$physicsTick drives everything - don't
        // also run the simulated path and fight it.
        if (serverLevel.getGameTime() - lastPhysicsTickGameTime < 5) return;

        runFlightLogic(serverLevel);
    }

    private void runFlightLogic(ServerLevel serverLevel) {
        if (simulatedPosition == null) return;

        if (state == FlightState.WAITING) {
            tickWaiting(serverLevel);
            return;
        }

        UUID destinationId = destinationAirportId();
        if (destinationId == null) {
            disengage();
            return;
        }

        AirportLayout destination = AirportRegistry.get(serverLevel).byId(destinationId).orElse(null);
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
            // Accelerate along the runway centreline, reaching rotation speed
            // by the halfway point, then pitch up and fly.
            case TAKEOFF_ROLL -> {
                AirportLayout origin = originLayout(serverLevel);
                List<BlockPos> runway = origin != null ? positionsOf(origin, Waypoint.Type.RUNWAY) : List.of();
                if (runway.size() < 2) {
                    setState(FlightState.CLIMB);
                } else {
                    runTakeoffRoll(runway);
                }
            }
            // Hold a fixed climb angle along the current heading.
            //
            // This used to chase a point above the holding pattern, which
            // meant that once the plane arrived over that x/z there was no
            // horizontal distance left - it hovered there climbing straight
            // up. A departure is "keep flying forward, nose up", not "fly to
            // a spot", so that's what this does now.
            case CLIMB -> {
                Vec3 forward = climbHeading(destination);
                double climbY = Math.sin(Math.toRadians(CLIMB_PITCH_DEGREES));
                double climbXZ = Math.cos(Math.toRadians(CLIMB_PITCH_DEGREES));
                flyHeading(new Vec3(forward.x * climbXZ, climbY, forward.z * climbXZ), cruiseSpeed());
                if (simulatedPosition.y >= cruiseAltitude() - 2) setState(FlightState.CRUISE);
            }
            // Level flight toward the nearest holding-pattern point, handing
            // over to HOLDING early enough to turn into the pattern rather
            // than arriving at a point and pivoting on the spot.
            case CRUISE -> {
                List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
                if (loop.isEmpty()) {
                    setState(FlightState.APPROACH);
                } else {
                    if (holdingEntryIndex < 0) holdingEntryIndex = nearestIndex(loop, simulatedPosition);
                    BlockPos entry = withY(loop.get(holdingEntryIndex), destination.holdingPatternHeight());
                    applyMotionTowards(entry);
                    if (horizontalDistance(entry, BlockPos.containing(simulatedPosition)) <= HOLDING_ENTRY_LEAD_BLOCKS) {
                        setState(FlightState.HOLDING);
                    }
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
        ScheduleEntry entry = currentEntry();
        message("Arrived at " + (entry == null ? "gate" : entry.gateName()) + ".");

        if (entry == null) {
            disengage();
            return;
        }

        waitTicksRemaining = Math.max(0, entry.waitSeconds()) * 20;
        setState(FlightState.WAITING);
        message(switch (entry.condition()) {
            case TIMER -> "Holding at the gate for " + entry.waitSeconds() + "s.";
            case PLAYER -> "Waiting for a player to board.";
            case CARGO -> "Cargo conditions aren't readable yet - holding on the timer instead ("
                    + entry.waitSeconds() + "s).";
        });
    }

    /**
     * Sits at a gate until this stop's departure condition is met, then flies
     * the next leg (or stops, if the schedule has run out and isn't looping).
     */
    private void tickWaiting(ServerLevel serverLevel) {
        ScheduleEntry entry = currentEntry();
        if (entry == null) {
            disengage();
            return;
        }

        if (waitTicksRemaining > 0) waitTicksRemaining--;

        boolean ready = switch (entry.condition()) {
            case TIMER -> waitTicksRemaining <= 0;
            case PLAYER -> isPlayerNearby(serverLevel);
            // Falls back to the timer - see ScheduleEntry.WaitCondition.CARGO.
            case CARGO -> waitTicksRemaining <= 0;
        };
        if (!ready) {
            if (++tickCounter % TELEMETRY_INTERVAL_TICKS == 0) sendTelemetry(serverLevel.getServer());
            return;
        }

        int next = schedule.nextIndex(scheduleIndex);
        if (next < 0) {
            message("Schedule complete.");
            disengage();
            return;
        }

        scheduleIndex = next;
        ScheduleEntry nextEntry = schedule.entries().get(next);
        message("Departing for " + nextEntry.gateName() + ".");
        // Depart from where the plane actually is - it flew here itself, so
        // its own tracked position is right even if the player wandered off.
        ServerPlayer player = controllingPlayerId == null ? null
                : serverLevel.getServer().getPlayerList().getPlayer(controllingPlayerId);
        engageCurrentLeg(serverLevel, BlockPos.containing(simulatedPosition), player);
    }

    private boolean isPlayerNearby(ServerLevel serverLevel) {
        if (simulatedPosition == null) return false;
        return serverLevel.getNearestPlayer(
                simulatedPosition.x, simulatedPosition.y, simulatedPosition.z,
                BOARDING_RADIUS, false) != null;
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
        if (activeBody != null) return steerCraftTowards(target);
        return advanceSimulatedTowards(target);
    }

    /**
     * Flies the real craft toward a waypoint by nudging its velocity, rather
     * than by setting its position.
     *
     * Deliberately a velocity controller and not a teleport: Sable is running
     * a rigid-body simulation, and overwriting the transform each tick would
     * fight it (and throw away collisions, and look wrong). Steering by
     * velocity correction leaves the physics engine in charge of how the
     * craft actually gets there. The gain is intentionally gentle - a big
     * correction on a heavy contraption reads as a lurch.
     */
    private boolean steerCraftTowards(BlockPos target) {
        Vec3 position = simulatedPosition;
        Vec3 delta = Vec3.atCenterOf(target).subtract(position);
        double distance = delta.length();
        if (distance <= CRAFT_ARRIVAL_RADIUS) {
            pitchDegrees = 0;
            return true;
        }

        Vec3 heading = delta.normalize();
        boolean onGround = isGroundState();

        if (onGround) {
            // Taxiing is a 2D problem - let gravity and the wheels own the
            // vertical axis rather than steering into or out of the ground.
            double flat = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
            if (flat < 1.0e-4) return true;
            heading = new Vec3(heading.x / flat, 0, heading.z / flat);
        } else {
            // Same 30-degree limit as the simulation, applied to the
            // direction we're asking for rather than to a position step.
            double horizontal = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
            if (horizontal > 1.0e-4) {
                double maxVertical = horizontal * MAX_PITCH_TANGENT;
                if (Math.abs(heading.y) > maxVertical) {
                    heading = new Vec3(heading.x, Math.copySign(maxVertical, heading.y), heading.z).normalize();
                }
            }
        }

        // Ease off on the way in so it settles on the waypoint instead of
        // overshooting and having to come back.
        double topSpeed = onGround ? CRAFT_TAXI_SPEED : cruiseSpeed();
        double speed = Math.min(topSpeed, distance * CRAFT_APPROACH_GAIN) * alignmentFactor(heading);
        Vec3 desired = heading.scale(speed);

        Vector3dc v = activeBody.getLinearVelocity();
        Vec3 correction = desired.subtract(new Vec3(v.x(), v.y(), v.z())).scale(CRAFT_STEER_GAIN);

        activeBody.addLinearAndAngularVelocity(
                new Vector3d(correction.x, correction.y, correction.z),
                angularCorrectionTowards(heading));

        double headingHorizontal = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        pitchDegrees = headingHorizontal > 1.0e-4
                ? (float) Math.toDegrees(Math.atan2(heading.y, headingHorizontal))
                : 0;
        return false;
    }

    /**
     * How much of the commanded speed to actually apply, based on how well
     * the nose is already lined up with where we're going.
     *
     * Steering was pushing the craft along the path regardless of which way
     * it faced, so any time the turn lagged the heading, the plane slid
     * sideways. Aircraft don't: they have to be pointing roughly where
     * they're going to make progress. Backing off the throttle while badly
     * misaligned lets the turn catch up first, which is also what stops the
     * craft carving sideways out of a corner.
     */
    private double alignmentFactor(Vec3 heading) {
        if (activeSubLevel == null) return 1.0;

        Quaterniondc orientation = activeSubLevel.logicalPose().orientation();
        Direction facing = getBlockState().hasProperty(AutopilotBlock.FACING)
                ? getBlockState().getValue(AutopilotBlock.FACING)
                : Direction.NORTH;
        Vector3d nose = orientation.transform(
                new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));

        double noseLen = Math.sqrt(nose.x * nose.x + nose.z * nose.z);
        double headLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        if (noseLen < 1.0e-6 || headLen < 1.0e-6) return 1.0;

        double dot = (nose.x * heading.x + nose.z * heading.z) / (noseLen * headLen);
        // 1 when pointing straight at it, tapering to a crawl when sideways -
        // never zero, or a craft that starts badly aligned could never build
        // the speed it needs for the turn to bite.
        return Math.max(MIN_MISALIGNED_THROTTLE, dot);
    }

    private boolean isGroundState() {
        return state == FlightState.TAXI_OUT
                || state == FlightState.TAKEOFF_ROLL
                || state == FlightState.TAXI_IN
                || state == FlightState.WAITING;
    }

    /**
     * Turns the craft to point where it's going, and keeps its wings level.
     *
     * Without this the autopilot only pushed the craft around, leaving it to
     * fly sideways in whatever attitude it happened to hold. The actual
     * control is in {@link #attitudeCorrection}; this works out the nose and
     * up vectors and the heading to aim for.
     */
    private Vector3d angularCorrectionTowards(Vec3 heading) {
        if (activeSubLevel == null) return new Vector3d();

        Quaterniondc orientation = activeSubLevel.logicalPose().orientation();
        Direction facing = getBlockState().hasProperty(AutopilotBlock.FACING)
                ? getBlockState().getValue(AutopilotBlock.FACING)
                : Direction.NORTH;

        Vector3d nose = orientation.transform(
                new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
        Vector3d up = orientation.transform(new Vector3d(0, 1, 0));

        // On the ground a plane yaws flat; it doesn't pitch its nose down to
        // chase a waypoint that happens to sit below it.
        boolean onGround = isGroundState();
        Vector3d target = new Vector3d(heading.x, onGround ? 0 : heading.y, heading.z);
        if (target.lengthSquared() < 1.0e-8) return new Vector3d();
        target.normalize();

        return attitudeCorrection(nose, up, target, onGround);
    }

    /**
     * Drives the craft's attitude by controlling yaw, pitch and roll
     * separately, rather than by the shortest rotation from its nose to the
     * target heading.
     *
     * The shortest-arc version banked into turns and sometimes wedged: for a
     * turn approaching 180 degrees the two vectors are nearly opposite, and
     * the shortest arc between nearly-opposite vectors has an essentially
     * arbitrary axis - frequently a roll. Planes don't turn like that. They
     * yaw about vertical, pitch about the wing axis, and hold the wings
     * level, which is what these three independent terms do.
     *
     * Roll is always commanded to zero, in the air as well as on the ground.
     * Correcting only the roll RATE (what the ground path did before) leaves
     * a craft that started out banked staying banked - which is exactly the
     * "took off banked 20 degrees" case.
     */
    private Vector3d attitudeCorrection(Vector3d nose, Vector3d up, Vector3d target, boolean onGround) {
        Vector3d worldUp = new Vector3d(0, 1, 0);

        double flatLen = Math.sqrt(nose.x * nose.x + nose.z * nose.z);
        if (flatLen < 1.0e-6) return new Vector3d(); // pointing straight up/down; nothing sane to yaw about

        // --- yaw: turn about the world vertical, always the short way round.
        double noseYaw = Math.atan2(nose.x / flatLen, nose.z / flatLen);
        double targetYaw = Math.atan2(target.x, target.z);
        double yawError = Math.atan2(Math.sin(targetYaw - noseYaw), Math.cos(targetYaw - noseYaw));

        // --- pitch: about the craft's wing axis. Level on the ground.
        double nosePitch = Math.asin(Math.max(-1, Math.min(1, nose.y)));
        double targetPitch = onGround ? 0 : Math.asin(Math.max(-1, Math.min(1, target.y)));
        double pitchError = targetPitch - nosePitch;

        Vector3d right = new Vector3d(nose).cross(worldUp);
        if (right.lengthSquared() < 1.0e-8) return new Vector3d();
        right.normalize();

        // --- roll: rotate the craft's up back onto the level up.
        Vector3d idealUp = new Vector3d(right).cross(nose).normalize();
        double rollError = Math.atan2(up.dot(right), up.dot(idealUp));

        Vector3d noseUnit = new Vector3d(nose).normalize();
        double rollGain = onGround ? GROUND_LEVEL_GAIN : TURN_GAIN;

        Vector3d desiredSpin = new Vector3d();
        desiredSpin.fma(clampRate(yawError * TURN_GAIN), worldUp);
        desiredSpin.fma(clampRate(pitchError * rollGain), right);
        desiredSpin.fma(clampRate(-rollError * rollGain), noseUnit);

        Vector3dc current = activeBody.getAngularVelocity();
        return desiredSpin.sub(current.x(), current.y(), current.z())
                .mul(onGround ? GROUND_TURN_DAMPING : TURN_DAMPING);
    }

    private static double clampRate(double rate) {
        return Math.max(-MAX_TURN_RATE_RAD_PER_SEC, Math.min(MAX_TURN_RATE_RAD_PER_SEC, rate));
    }

    private boolean advanceSimulatedTowards(BlockPos target) {
        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 delta = targetCenter.subtract(simulatedPosition);
        double distance = delta.length();
        if (distance <= ARRIVAL_RADIUS) {
            simulatedPosition = targetCenter;
            pitchDegrees = 0;
            return true;
        }

        Vec3 step = delta.normalize().scale(Math.min(SIMULATED_SPEED_BLOCKS_PER_TICK, distance));

        // Cap how steeply the plane climbs or descends: a plane gains
        // altitude by nosing up and covering ground, not by rising
        // vertically, so the vertical part of each step is limited to what
        // MAX_PITCH_DEGREES allows for the ground distance covered.
        //
        // Only while there is ground left to cover, though. Capping against
        // the step's own horizontal component deadlocks: as the plane closes
        // on a target that is mostly above or below it, the horizontal
        // component shrinks, which shrinks the allowed climb rate, which
        // leaves it hovering just short of altitude forever. Once it's
        // effectively over the target, let it close the remaining height.
        double horizontalToTarget = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double stepHorizontal = Math.sqrt(step.x * step.x + step.z * step.z);
        if (horizontalToTarget > 1.0) {
            double maxVertical = stepHorizontal * MAX_PITCH_TANGENT;
            if (Math.abs(step.y) > maxVertical) {
                step = new Vec3(step.x, Math.copySign(maxVertical, step.y), step.z);
            }
        }

        simulatedPosition = simulatedPosition.add(step);
        pitchDegrees = stepHorizontal > 1.0e-4
                ? (float) Math.toDegrees(Math.atan2(step.y, stepHorizontal))
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

    /**
     * A point well ahead of the plane, at cruise altitude, in the direction
     * of the destination - recomputed every tick so it stays ahead.
     *
     * Deliberately not "the holding pattern entry at cruise altitude": a
     * fixed target the plane can arrive underneath leaves it with no ground
     * distance left to pitch against, so it stops climbing short of altitude.
     * Chasing a receding point keeps a real climb-out angle the whole way up.
     * Overflying the airport while climbing is fine and realistic - CRUISE
     * turns it back toward the holding pattern.
     */
    private int cruiseAltitude() {
        return schedule.cruiseAltitude();
    }

    private double cruiseSpeed() {
        return schedule.cruiseSpeed();
    }

    /**
     * The takeoff roll: accelerate down the runway centreline, hitting
     * rotation speed by the halfway point, and rotate at the far end.
     *
     * Speed is ramped by how far along the runway the plane is rather than
     * by a timer, so it works the same on a short strip or a long one.
     */
    private void runTakeoffRoll(List<BlockPos> runway) {
        BlockPos start = runway.get(0);
        BlockPos end = runway.get(1);

        double dxAlign = end.getX() - start.getX();
        double dzAlign = end.getZ() - start.getZ();
        double alignLen = Math.sqrt(dxAlign * dxAlign + dzAlign * dzAlign);
        Vec3 centreline = alignLen < 1.0e-6
                ? new Vec3(1, 0, 0)
                : new Vec3(dxAlign / alignLen, 0, dzAlign / alignLen);

        // Line up on the centreline before rolling. Starting the roll while
        // still turning is what produced departures that tracked sideways -
        // the craft was committed to a heading it hadn't finished taking.
        if (takeoffHoldTicks < TAKEOFF_MAX_HOLD_TICKS
                && (takeoffHoldTicks < TAKEOFF_MIN_HOLD_TICKS
                    || alignmentFactor(centreline) < TAKEOFF_ALIGNMENT_THRESHOLD)) {
            takeoffHoldTicks++;
            flyHeading(centreline, 0); // hold still, but keep turning onto the heading
            return;
        }

        if (takeoffStart == null) takeoffStart = simulatedPosition;

        double runwayLength = horizontalDistance(start, end);
        double rolled = Math.sqrt(
                Math.pow(simulatedPosition.x - takeoffStart.x, 2)
                        + Math.pow(simulatedPosition.z - takeoffStart.z, 2));

        // Full speed by half the runway, then hold it to the end.
        double ramp = runwayLength <= 1 ? 1 : Math.min(1.0, rolled / (runwayLength * 0.5));
        double speed = CRAFT_TAXI_SPEED + (CRAFT_TAKEOFF_SPEED - CRAFT_TAXI_SPEED) * ramp;

        flyHeading(centreline, speed);

        boolean atRotationSpeed = ramp >= 1.0;
        boolean nearRunwayEnd = horizontalDistance(end, BlockPos.containing(simulatedPosition)) <= CRAFT_ARRIVAL_RADIUS;
        if (atRotationSpeed && nearRunwayEnd) {
            takeoffStart = null;
            takeoffHoldTicks = 0;
            setState(FlightState.CLIMB);
        }
    }

    /** Which way to climb out: toward the destination's holding pattern if
     *  there is one, otherwise straight ahead on the current heading. */
    private Vec3 climbHeading(AirportLayout destination) {
        List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
        if (!loop.isEmpty()) {
            BlockPos entry = loop.get(nearestIndex(loop, simulatedPosition));
            double dx = entry.getX() - simulatedPosition.x;
            double dz = entry.getZ() - simulatedPosition.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0) return new Vec3(dx / len, 0, dz / len);
        }
        if (activeBody != null) {
            Vector3dc v = activeBody.getLinearVelocity();
            double len = Math.sqrt(v.x() * v.x() + v.z() * v.z());
            if (len > 0.1) return new Vec3(v.x() / len, 0, v.z() / len);
        }
        return new Vec3(1, 0, 0);
    }

    /**
     * Fly a heading at a speed, rather than toward a point. Used where the
     * plane should keep going in a direction - the takeoff roll and the climb
     * out - instead of converging on a spot and stopping.
     */
    private void flyHeading(Vec3 direction, double speed) {
        if (activeBody == null) {
            // Simulation fallback: same motion, no physics body to push.
            simulatedPosition = simulatedPosition.add(direction.scale(speed / 20.0));
            pitchDegrees = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, direction.y))));
            return;
        }
        Vec3 desired = direction.scale(speed);
        Vector3dc v = activeBody.getLinearVelocity();
        Vec3 correction = desired.subtract(new Vec3(v.x(), v.y(), v.z())).scale(CRAFT_STEER_GAIN);
        activeBody.addLinearAndAngularVelocity(
                new Vector3d(correction.x, correction.y, correction.z),
                angularCorrectionTowards(direction));
        pitchDegrees = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, direction.y))));
    }

    private BlockPos climbTarget(AirportLayout destination) {
        List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
        double dirX = 1, dirZ = 0;
        if (!loop.isEmpty()) {
            BlockPos entry = loop.get(nearestIndex(loop, simulatedPosition));
            double dx = entry.getX() - simulatedPosition.x;
            double dz = entry.getZ() - simulatedPosition.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0e-3) {
                dirX = dx / len;
                dirZ = dz / len;
            }
        }
        return new BlockPos(
                (int) Math.round(simulatedPosition.x + dirX * CLIMB_LOOKAHEAD_BLOCKS),
                SAFE_CRUISE_ALTITUDE,
                (int) Math.round(simulatedPosition.z + dirZ * CLIMB_LOOKAHEAD_BLOCKS));
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

    /**
     * The actual route across the ground network, from where the plane is to
     * the runway (departing) or to its gate (arriving).
     *
     * This used to just walk every taxiway point in the order they were
     * drawn, which zigzagged between unrelated segments - a plane would head
     * for a point behind it, oscillate around a target it kept overshooting,
     * and sit there looking stopped. Now the taxiway segments are treated as
     * what they are, a graph, and this finds the shortest path through it.
     */
    private List<BlockPos> groundTaxiPath(AirportLayout layout, boolean arriving) {
        List<BlockPos> runway = positionsOf(layout, Waypoint.Type.RUNWAY);
        if (runway.isEmpty()) return List.of();
        BlockPos gateEnd = runway.get(0);

        BlockPos from;
        BlockPos to;
        if (arriving) {
            from = gateEnd;
            BlockPos gate = layout.gates().get(destinationGateName());
            if (gate == null) return List.of();
            to = gate;
        } else {
            from = joinPoint != null ? joinPoint : BlockPos.containing(simulatedPosition);
            to = gateEnd;
        }

        List<BlockPos> route = shortestGroundRoute(layout, from, to);
        // No connected route (a layout drawn before the editor enforced
        // connectivity, say) - head straight there rather than refusing to
        // move at all.
        return route.isEmpty() ? List.of(to) : route;
    }

    /** Taxiway segments plus the runway, as an adjacency map keyed by node. */
    private static Map<BlockPos, List<BlockPos>> groundGraph(AirportLayout layout) {
        Map<BlockPos, List<BlockPos>> graph = new HashMap<>();
        List<BlockPos> taxiway = positionsOf(layout, Waypoint.Type.TAXIWAY);
        for (int i = 0; i + 1 < taxiway.size(); i += 2) {
            link(graph, taxiway.get(i), taxiway.get(i + 1));
        }
        List<BlockPos> runway = positionsOf(layout, Waypoint.Type.RUNWAY);
        if (runway.size() == 2) link(graph, runway.get(0), runway.get(1));
        return graph;
    }

    private static void link(Map<BlockPos, List<BlockPos>> graph, BlockPos a, BlockPos b) {
        graph.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        graph.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
    }

    /**
     * Dijkstra over the ground network. `from` and `to` are snapped to the
     * nearest node first, since the plane is parked somewhere near the line
     * rather than exactly on a drawn point.
     */
    private static List<BlockPos> shortestGroundRoute(AirportLayout layout, BlockPos from, BlockPos to) {
        Map<BlockPos, List<BlockPos>> graph = groundGraph(layout);
        if (graph.isEmpty()) return List.of();

        BlockPos start = nearestNode(graph.keySet(), from);
        BlockPos goal = nearestNode(graph.keySet(), to);
        if (start == null || goal == null) return List.of();

        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> best.getOrDefault(p, Double.MAX_VALUE)));
        best.put(start, 0.0);
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos node = queue.poll();
            if (node.equals(goal)) break;
            double baseCost = best.getOrDefault(node, Double.MAX_VALUE);
            for (BlockPos neighbour : graph.getOrDefault(node, List.of())) {
                double cost = baseCost + horizontalDistance(node, neighbour);
                if (cost < best.getOrDefault(neighbour, Double.MAX_VALUE)) {
                    best.put(neighbour, cost);
                    cameFrom.put(neighbour, node);
                    queue.add(neighbour);
                }
            }
        }
        if (!best.containsKey(goal)) return List.of();

        List<BlockPos> path = new ArrayList<>();
        for (BlockPos at = goal; at != null; at = cameFrom.get(at)) {
            path.add(at);
            if (at.equals(start)) break;
        }
        java.util.Collections.reverse(path);
        // The real destination (a gate) may sit slightly off its node.
        if (!path.isEmpty() && !path.get(path.size() - 1).equals(to)) path.add(to);
        return path;
    }

    @org.jetbrains.annotations.Nullable
    private static BlockPos nearestNode(java.util.Collection<BlockPos> nodes, BlockPos to) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos node : nodes) {
            double d = horizontalDistance(node, to);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        return best;
    }

    /**
     * The descent: join the final leg at pattern altitude, then fly down it
     * so the plane is at runway height by the time it reaches the threshold -
     * a glide slope, rather than arriving high and dropping.
     *
     * The leg's own two points are drawn flat on the map, so the altitudes
     * are supplied here: start at the holding pattern's height, finish at the
     * runway's. After touchdown it rolls out to the runway's gate end.
     */
    private static List<BlockPos> approachPath(AirportLayout destination) {
        List<BlockPos> leg = positionsOf(destination, Waypoint.Type.FINAL_LEG);
        List<BlockPos> runway = positionsOf(destination, Waypoint.Type.RUNWAY);
        List<BlockPos> path = new ArrayList<>();

        if (leg.size() == 2) {
            int runwayY = runway.isEmpty() ? leg.get(1).getY() : runway.get(1).getY();
            path.add(withY(leg.get(0), destination.holdingPatternHeight()));
            path.add(withY(leg.get(1), runwayY));
        } else {
            path.addAll(leg);
        }
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
        // Says outright whether it's flying a real craft or just simulating,
        // so "the plane isn't moving" is answerable at a glance instead of
        // needing a code read.
        boolean flyingReal = server.getTickCount() >= 0
                && lastPhysicsTickGameTime > 0
                && server.overworld().getGameTime() - lastPhysicsTickGameTime < 20;
        String mode = flyingReal ? "FLYING" : "sim";
        player.displayClientMessage(
                Component.literal("[Skyport] " + state + " @ " + pos + "  " + pitch + "  (" + mode + ")"), true);
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
        // The schedule IS worth persisting - it's player-authored config,
        // not transient flight state, and losing it on reload would mean
        // retyping the whole itinerary.
        tag.put("schedule", schedule.save());
        tag.putInt("scheduleIndex", scheduleIndex);
        if (controllingPlayerId != null) tag.putUUID("controllingPlayerId", controllingPlayerId);
        tag.putString("state", state.name());
        tag.putInt("currentWaypointIndex", currentWaypointIndex);
        // originAirportId, holdingEntryIndex, waitTicksRemaining and
        // simulatedPosition are intentionally NOT persisted - transient
        // stand-ins for real contraption movement, not worth preserving
        // across a restart. They re-initialize next time it starts moving.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("schedule")) schedule = FlightSchedule.load(tag.getCompound("schedule"));
        scheduleIndex = tag.getInt("scheduleIndex");
        if (tag.hasUUID("controllingPlayerId")) controllingPlayerId = tag.getUUID("controllingPlayerId");
        if (tag.contains("state")) state = FlightState.valueOf(tag.getString("state"));
        currentWaypointIndex = tag.getInt("currentWaypointIndex");
        if (state != FlightState.IDLE) simulatedPosition = getBlockPos().getCenter();
    }
}
