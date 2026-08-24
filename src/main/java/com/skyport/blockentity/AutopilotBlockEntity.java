package com.skyport.blockentity;

import com.skyport.SkyportConfig;
import com.skyport.data.AirportLayout;
import com.skyport.data.CraftType;
import com.skyport.data.AirportRegistry;
import com.skyport.data.AirportSummary;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import com.skyport.data.TrafficReport;
import com.skyport.data.Waypoint;
import com.skyport.network.OpenAutopilotPayload;
import com.skyport.registry.ModBlockEntities;
import com.skyport.logic.FuelBurn;
import com.skyport.logic.GroundNetwork;
import com.skyport.world.FleetWake;
import com.skyport.world.FlightChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Backs one Autopilot block: the flight state machine, and the thing that
 * actually flies the aircraft.
 *
 * It runs from two places, which is worth knowing before changing anything
 * in here. While mounted on an assembled craft, Sable calls
 * {@link #sable$physicsTick} and hands over the craft's rigid body, and that
 * drives everything - at the physics rate, which is NOT the 20/s game tick,
 * so anything time-based must use game time rather than counting calls.
 * While the block is sitting loose in the world with no craft,
 * {@link #serverTick} drives the same logic against a simulated position
 * instead, which stays useful for testing a layout without building a plane.
 *
 * Steering is by velocity and angular-velocity correction rather than by
 * setting the transform: Sable is running a real rigid-body simulation, and
 * overwriting its state each tick would fight it and discard collisions.
 *
 * Routes differ by craft type. A plane taxis, rolls, climbs out on the
 * runway heading, joins a pattern and flies an approach; a helicopter or
 * airship goes up, across and down onto a pad. Engaging checks where the
 * aircraft is: on the ground away from any drawn infrastructure it refuses
 * and says where to tow it, and mid-air it picks up from the climb.
 */
public class AutopilotBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

    public enum FlightState {
        IDLE, PUSHBACK, TAXI_OUT, TAKEOFF_ROLL, CLIMB, CRUISE, HOLDING, APPROACH, TAXI_IN, WAITING,
        /** Rotorcraft and airships: straight up from the pad, and straight
         *  down onto the one at the far end. */
        VERTICAL_CLIMB, VERTICAL_DESCENT,
        /** Rotorcraft equivalent of the holding pattern: stop and wait for
         *  the pad to clear. */
        HOVERING
    }

    /** How near a player counts as "boarded" for WaitCondition.PLAYER. */
    private static final double BOARDING_RADIUS = 8.0;

    private static final double SIMULATED_SPEED_BLOCKS_PER_TICK = 0.5; // ~10 blocks/sec
    private static final double ARRIVAL_RADIUS = 1.0;
    private static final int TELEMETRY_INTERVAL_TICKS = 20; // once a second

    // Cruise altitude is per-schedule (FlightSchedule#cruiseAltitude); CLIMB
    // flies a locked heading at a fixed angle until it gets there.
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
    /** Pushback, as a fraction of the airport's taxi speed. Reversing should
     *  read as deliberate rather than brisk whatever the field is set to. */
    private static final double PUSHBACK_SPEED_FRACTION = 0.75;
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
    /** One server tick. The steering gains were tuned per physics step, so
     *  this is what they are re-scaled against when a step runs long. */
    private static final double NOMINAL_STEP_SECONDS = 0.05;
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
    /** How much bank to carry per radian of heading error, and the ceiling on
     *  it - about 18 degrees, a gentle airliner bank rather than aerobatics. */
    private static final double BANK_PER_YAW_ERROR = 0.8;
    private static final double MAX_BANK_RADIANS = Math.toRadians(18);
    /** Throttle floor when pointing the wrong way - see alignmentFactor. */
    private static final double MIN_MISALIGNED_THROTTLE = 0.15;
    /** Hold on the centreline before rolling: at least this long, until
     *  aligned, and never longer than the cap. */
    private static final int TAKEOFF_MIN_HOLD_TICKS = 20;
    private static final int TAKEOFF_MAX_HOLD_TICKS = 100;
    private static final double TAKEOFF_ALIGNMENT_THRESHOLD = 0.98;
    /** Seconds of travel before a turn point to start cutting the corner. */
    private static final double TURN_ANTICIPATION_SECONDS = 1.5;
    /** Never cut more than this fraction of the leg being flown - see
     *  turnAnticipationRadius. */
    private static final double MAX_CORNER_CUT = 0.3;
    /** Speed in the pattern and on approach. Slow enough to actually track
     *  the drawn lines instead of overshooting and correcting back. */
    private static final double CRAFT_PATTERN_SPEED = 20.0;
    /** Vertical rate for rotorcraft and airships, up and down. */
    private static final double CRAFT_VERTICAL_SPEED = 8.0;
    /** How far a helicopter tips its nose down to move forward in the cruise.
     *  A blimp gets its lift from buoyancy and stays level. */
    private static final double HELI_CRUISE_PITCH_DEGREES = 8.0;
    /** How far to one side of a busy pad to wait. Off to the side rather
     *  than overhead, so the aircraft leaving can climb straight out. */
    private static final int HOVER_STANDOFF_BLOCKS = 24;
    private static final int HOVER_RECHECK_INTERVAL_TICKS = 20;
    /** How far back from the hold line to wait, so the junction stays clear
     *  for anything that needs to pass. */
    private static final int HOLD_LINE_STANDOFF_BLOCKS = 8;
    /** How the route is sampled for terrain, and how much air to insist on
     *  above the highest ground found. */
    /** Tighter than it was: a one-block-wide line sampled every 32 blocks
     *  reliably finds a mountain range, and reliably threads straight
     *  between floating islands. */
    private static final int TERRAIN_SCAN_SPACING = 12;
    private static final int TERRAIN_SCAN_MAX_SAMPLES = 256;
    /** Chunks the scan may generate before it settles for reading whatever
     *  is already loaded. Generating one costs tens of milliseconds and this
     *  runs on the server thread with a player waiting. */
    private static final int TERRAIN_SCAN_MAX_GENERATED_CHUNKS = 64;
    private static final int TERRAIN_CLEARANCE_BLOCKS = 20;
    /** How often to repeat a "this airport is missing something" warning
     *  while an aircraft holds waiting for the player to fix it. */
    private static final int MISSING_FACILITY_REPEAT_TICKS = 200;
    /** How far a vertical craft may drift off the pad before the autopilot
     *  starts pulling it back. Wide enough that a craft making its own thrust
     *  is not corrected every tick, narrow enough to still land on the pad. */
    private static final double VERTICAL_DRIFT_DEADBAND_BLOCKS = 1.5;
    /** Sideways authority once outside the deadband, as a fraction of normal.
     *  Gentle on purpose: the craft is nudged back over a few seconds rather
     *  than snapped, which is what made the descent judder. */
    private static final double VERTICAL_SIDEWAYS_GAIN_SCALE = 0.35;
    /** Beyond this the craft is not drifting, it is off course, and gets the
     *  full correction it needs to actually get back over the pad. */
    private static final double VERTICAL_FULL_AUTHORITY_BLOCKS = 5.0;
    /** How often to re-check power and burn fuel. One second. */
    private static final int POWER_CHECK_INTERVAL_TICKS = 20;
    /** The arrival chime: two note-block bells a fourth apart, high then low,
     *  which is roughly what a cabin seatbelt sign sounds like. */
    private static final float CHIME_VOLUME = 1.0f;
    private static final float CHIME_HIGH_PITCH = 1.335f;
    private static final float CHIME_LOW_PITCH = 1.0f;
    /** Long enough to read as two notes rather than a chord. */
    private static final int CHIME_GAP_TICKS = 7;
    /** Most world time one fuel check may charge for. A craft that has been
     *  unloaded for an hour was not flying for that hour, and should not be
     *  billed as though it were. */
    private static final long MAX_FUEL_CATCHUP_TICKS = 100;
    /** How many unsuccessful laps between "still waiting" reports. */
    private static final int HOLDING_REPORT_EVERY_LAPS = 2;
    /** How often to move the force-loaded bubble; every tick would churn. */
    private static final int CHUNK_FOLLOW_INTERVAL_TICKS = 20;
    /** Traffic within this horizontal distance and altitude band counts as a
     *  conflict; the giving-way plane climbs by the altitude figure. */
    private static final double SEPARATION_RADIUS = 96.0;
    private static final int SEPARATION_ALTITUDE = 24;
    private static final int SEPARATION_CHECK_INTERVAL_TICKS = 10;
    /** How far above the terrain still counts as "on the ground" rather than airborne. */
    private static final int GROUND_HEIGHT_TOLERANCE = 6;

    // The itinerary, and which stop we're currently flying to. The
    // destination airport/gate are read off the current entry rather than
    // stored separately, so there's one source of truth.
    private FlightSchedule schedule = new FlightSchedule();
    private int scheduleIndex = 0;
    /** Game time this WAITING hold ends. -1 until the hold actually starts,
     *  so it is set from a real ServerLevel rather than guessed at. */
    private long waitUntilGameTime = -1;

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
    /** Heading held throughout the climb, fixed at rotation. */
    @org.jetbrains.annotations.Nullable
    private Vec3 climbHeadingLocked;
    /** Stable identity for runway clearances; see planeId(). */
    @org.jetbrains.annotations.Nullable
    private UUID planeId;
    /** Whether the waypoint being steered to is one to stop at, rather than
     *  a turn point to carry speed through. */
    private boolean steeringToLastWaypoint = true;
    /** Length of the last physics step, in seconds. Held between the tick
     *  callback and the steering code, which needs it to stay lag-neutral. */
    private double physicsStepSeconds = NOMINAL_STEP_SECONDS;
    /** The waypoint the distance below belongs to. Compared by value so that
     *  every way of changing target - arriving, cutting a corner, changing
     *  state - resets the memory, rather than only the ones we remembered. */
    private BlockPos lastWaypointTarget;
    /** Distance to the current waypoint on the previous steering pass, for
     *  spotting one that was flown straight past - see steerCraftTowards. */
    private double lastWaypointDistance = Double.MAX_VALUE;
    /** Chunks this plane is currently force-loading. Transient: tickets do
     *  not survive a restart, and neither should our record of them. */
    private final transient Set<ChunkPos> heldChunks = new HashSet<>();
    /** Extra altitude currently being flown to stay clear of other traffic. */
    private transient int currentSeparationOffset = 0;
    /** Whether this plane has been cleared past the hold point for the leg
     *  it is currently flying. Reset per leg, not persisted. */
    private transient boolean clearedPastHoldShort = false;
    /** Laps flown waiting for clearance, for the periodic status report. */
    private transient int holdingLaps = 0;
    /** Containers found on this craft, located once per stop. Cleared when
     *  it leaves, since a rebuilt aircraft may have different holds. */
    @org.jetbrains.annotations.Nullable
    private transient java.util.List<BlockPos> cargoContainers;
    /** Containers to draw fuel from, located once and remembered - same
     *  reasoning as cargoContainers. */
    private transient java.util.List<BlockPos> fuelContainers;
    /** Fuel left in the currently-burning item, in reference-speed ticks -
     *  it drains faster than one per tick when flying fast. Persisted, or
     *  reloading would refund whatever was already lit. */
    private double fuelReserve;
    /** Game time of the last power check, to keep the cadence honest
     *  regardless of how often this gets called. */
    private long lastPowerCheckGameTime;
    /** Game time the answering chime note is due, or 0 for none. */
    private long chimeSecondNoteAt;
    /** Cached answer from the last power check, so the steering code can ask
     *  every tick without paying for a container sweep every tick. */
    private boolean hasPowerNow = true;
    /** The pad this aircraft is parked on, so it can be released on takeoff. */
    @org.jetbrains.annotations.Nullable
    private transient UUID occupiedPadAirport;
    @org.jetbrains.annotations.Nullable
    private transient String occupiedPadName;
    /** Route for the current phase; see path(). */
    @org.jetbrains.annotations.Nullable
    private transient List<BlockPos> cachedPath;

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
        PacketDistributor.sendToPlayer(player, new OpenAutopilotPayload(getBlockPos(), airports, schedule));
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

    /** Store a route without flying it - see SaveSchedulePayload. */
    public void setSchedule(FlightSchedule newSchedule) {
        this.schedule = newSchedule;
        this.scheduleIndex = 0;
        setChanged();
    }

    public FlightSchedule schedule() {
        return schedule;
    }

    /**
     * Start the saved schedule with no player involved - what a redstone
     * pulse does.
     *
     * Position comes from the craft itself here. There's no player standing
     * on it to borrow a position from, which is fine while it's assembled;
     * a loose block with no craft falls back to its own coordinates.
     */
    public void engageFromRedstone(ServerLevel serverLevel) {
        if (state != FlightState.IDLE || schedule.isEmpty()) return;
        this.scheduleIndex = 0;
        this.controllingPlayerId = null;
        engageCurrentLeg(serverLevel, craftPositionOr(getBlockPos()), null);
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

        // Refuse a route that flies into the ground rather than discovering it
        // the hard way somewhere over a mountain range.
        AirportLayout target = destinationAirportId() == null ? null
                : AirportRegistry.get(serverLevel).byId(destinationAirportId()).orElse(null);
        if (target != null && !destinationIsUsable(target)) {
            setState(FlightState.IDLE);
            return;
        }
        if (target != null && !routeIsFlyable(serverLevel, reference, target)) {
            setState(FlightState.IDLE);
            return;
        }

        // Power last of the refusals, because in FUEL mode asking the
        // question lights an item: check it only once everything free to
        // check has already passed, or a layout mistake would quietly burn a
        // coal every time the player pressed Engage.
        fuelContainers = null;
        if (!hasPower(serverLevel.getGameTime())) {
            message(powerMissingReason());
            setState(FlightState.IDLE);
            return;
        }
        hasPowerNow = true;

        // Rotorcraft and airships have no ground route to join - they lift
        // off from wherever they are standing. No taxiway check, and nothing
        // to claim: they use pads, not the runway everyone else queues for.
        if (craftType().isVertical()) {
            this.originAirportId = null;
            setState(isOnGround(serverLevel, reference)
                    ? FlightState.VERTICAL_CLIMB : FlightState.CRUISE);
            message(player, "Autopilot engaged - lifting off.");
            return;
        }

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
            setState(FlightState.PUSHBACK);
            message(player, "Autopilot engaged - pushing back.");
        } else {
            this.originAirportId = null;
            setState(FlightState.CLIMB);
            message(player, "Autopilot engaged in flight - climbing to cruising altitude.");
        }
    }

    public void disengage() {
        // Going idle stops this block entity ticking altogether, so a chime
        // still waiting on its second note would never get one. The last stop
        // of a schedule is exactly when that happens, and a half-played
        // arrival chime is more noticeable than none at all.
        flushArrivalChime();
        releaseApproach();
        releasePad();
        releaseChunks();
        // Drop out of the parked list too. That list is persisted, and an
        // aircraft with no schedule left to fly would otherwise be woken by
        // every ATC visit for the rest of the world's life.
        forgetParked();
        setState(FlightState.IDLE);
        controllingPlayerId = null;
        originAirportId = null;
        joinPoint = null;
        takeoffStart = null;
        takeoffHoldTicks = 0;
        climbHeadingLocked = null;
        clearedPastHoldShort = false;
        holdingLaps = 0;
        pitchDegrees = 0;
        waitUntilGameTime = -1;
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
        // Clamped, not trusted: a stalled server can hand back a huge step,
        // and scaling a velocity correction by it would fire the craft off
        // the map on the first tick after the hitch.
        this.physicsStepSeconds = Math.max(0.005, Math.min(0.25, deltaSeconds));

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

        // Before the state switch, so the answering note lands whatever the
        // aircraft went on to do - including a final arrival, which leaves
        // the state machine idle and would otherwise swallow it.
        tickArrivalChime(serverLevel);

        if (state == FlightState.WAITING) {
            // Parked at a gate: stop holding the world open. A stationary
            // aircraft has nothing to fly into, and a long gate wait - or a
            // PLAYER condition nobody comes to satisfy - would otherwise pin
            // a patch of chunks open indefinitely for a plane that isn't
            // going anywhere.
            //
            // The consequence is deliberate: a parked plane's wait only
            // counts down while its chunk is loaded for some other reason,
            // which is how everything else in Minecraft behaves. It resumes
            // when someone comes near, and re-acquires its bubble the moment
            // it starts moving again.
            // Write down where we are, so the ATC block can find and wake us
            // even with these chunks unloaded.
            rememberSelf(serverLevel);

            if (SkyportConfig.keepParkedLoaded) {
                // Server has opted into schedules that run unattended: hold a
                // small area rather than sleeping, so the gate wait keeps
                // counting down with nobody around.
                FlightChunkLoader.follow(serverLevel, planeId(),
                        new ChunkPos(BlockPos.containing(simulatedPosition)), heldChunks,
                        SkyportConfig.parkedChunkRadius);
            } else {
                releaseChunks();
            }
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
            handleLostDestination(serverLevel);
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
            // Don't leave the gate at all until the airport is ours. On a
            // field where the taxiway and runway are the same strip there is
            // nowhere to pass or hold short, so the decision has to be made
            // before pushback rather than at the runway threshold.
            /*
             * Reverse off the stand before taxiing, the way a real departure
             * does - a plane parked at a gate can't drive forwards out of it.
             *
             * Only when the gate's spur actually has a node between it and the
             * runway: if the gate sits on the runway or its taxiway runs
             * straight there, there's nothing to back away from and the plane
             * just rotates and goes. Attitude is deliberately left alone here
             * (see PUSHBACK in steerCraftTowards) so it tracks backwards
             * rather than swinging its nose round on the stand; the turn onto
             * the taxiway happens once it's clear, in TAXI_OUT.
             */
            case PUSHBACK -> {
                AirportLayout origin = originLayout(serverLevel);
                BlockPos back = origin == null ? null : pushbackTarget(origin);
                if (back == null) {
                    setState(FlightState.TAXI_OUT);
                } else if (applyMotionTowards(back)) {
                    // Reversing onto the taxiway IS joining the network, so
                    // forget where we joined from. Leaving it set made the
                    // taxi route start beside the gate again - the aircraft
                    // backed out, turned, drove back to the stand it had just
                    // left, turned once more, and only then set off.
                    joinPoint = null;
                    note("Pushback complete.");
                    setState(FlightState.TAXI_OUT);
                }
            }
            // Taxi out to the hold point without needing clearance, then wait
            // there for the runway. If no hold point is drawn there's nowhere
            // safe to wait, so the whole airport has to be claimed up front.
            case TAXI_OUT -> {
                AirportLayout origin = originLayout(serverLevel);
                // The line guarding the runway this departure is using.
                BlockPos holdShort = origin == null ? null
                        : holdShortPoint(origin, origin.departureRunway());

                // Nobody leaves a gate while another plane is on the taxiway,
                // hold point included - there's nowhere to pass, so a second
                // plane pushing back would just queue into the first.
                if (origin != null && !claimTaxiway(serverLevel, origin)) {
                    if (tickCounter % 100 == 0) note("Holding at the gate - taxiway occupied.");
                    break;
                }

                if (holdShort == null) {
                    if (origin != null && !claimTraffic(serverLevel, origin)) {
                        if (tickCounter % 100 == 0) note("Holding at the gate - airport busy.");
                        break;
                    }
                } else if (!clearedPastHoldShort) {
                    // Roll up to just short of the line, not onto it. Stopping
                    // exactly on the hold point parks the aircraft in the
                    // junction itself, where it blocks anything trying to get
                    // past; a real one waits back from the line and leaves the
                    // intersection clear.
                    final AirportLayout departure = origin;
                    final BlockPos holdTarget = holdShort;
                    followWaypoints(path(() -> {
                        List<BlockPos> toHold = new ArrayList<>();
                        if (joinPoint != null) toHold.add(joinPoint);
                        toHold.add(standoffBefore(departure, holdTarget));
                        return toHold;
                    }), () -> {
                        // ...and only ask for the runway once we're sitting on it.
                        if (claimTraffic(serverLevel, origin)) {
                            clearedPastHoldShort = true;
                            currentWaypointIndex = 0;
                            // The route past the line is a different one.
                            invalidatePath();
                            // Off the taxiway and onto the runway - the next
                            // aircraft can start taxiing out behind us.
                            releaseTaxiway(serverLevel, origin);
                            note("Cleared to line up.");
                        } else if (tickCounter % 100 == 0) {
                            note("Holding short - runway in use.");
                        }
                    });
                    break;
                }

                final AirportLayout departureAirport = origin;
                followWaypoints(path(() -> {
                    List<BlockPos> out = new ArrayList<>();
                    if (joinPoint != null && !clearedPastHoldShort) out.add(joinPoint);
                    if (departureAirport != null) out.addAll(groundTaxiPath(departureAirport, false));
                    return out;
                }), () -> setState(FlightState.TAKEOFF_ROLL));
            }
            // Accelerate along the runway centreline, reaching rotation speed
            // by the halfway point, then pitch up and fly.
            case TAKEOFF_ROLL -> {
                AirportLayout origin = originLayout(serverLevel);
                // Departures roll down the departure runway, which is the
                // second one if the field has one and the only one if not.
                List<BlockPos> runway = origin != null ? origin.departureRunway() : List.<BlockPos>of();
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
                // Airborne now - the departure runway is free for the next
                // aircraft, whether that's an arrival or another departure.
                releaseOriginRunway(serverLevel);
                // Locked at rotation, not recomputed: re-aiming at the
                // destination every tick made the plane wander through the
                // climb instead of flying the runway heading out.
                if (climbHeadingLocked == null) climbHeadingLocked = climbHeading(destination);
                Vec3 forward = climbHeadingLocked;
                double climbY = Math.sin(Math.toRadians(CLIMB_PITCH_DEGREES));
                double climbXZ = Math.cos(Math.toRadians(CLIMB_PITCH_DEGREES));
                flyHeading(new Vec3(forward.x * climbXZ, climbY, forward.z * climbXZ), cruiseSpeed());
                if (simulatedPosition.y >= cruiseAltitude() - 2) setState(FlightState.CRUISE);
            }
            // Level flight toward the nearest holding-pattern point, handing
            // over to HOLDING early enough to turn into the pattern rather
            // than arriving at a point and pivoting on the spot.
            // Head for the holding pattern, but if the runway is free by the
            // time we get near it, go straight in - circling an empty airport
            // is just a delay. Holding is for when someone else is landing.
            // Straight up off the pad. No forward speed at all - a helicopter
            // leaving a rooftop wants to clear whatever it's sitting between
            // before going anywhere.
            case VERTICAL_CLIMB -> {
                // Clear of the pad now - free it for the next arrival.
                releasePad();
                climbVertically(cruiseAltitude());
                if (simulatedPosition.y >= cruiseAltitude() - 2) setState(FlightState.CRUISE);
            }
            /*
             * Waiting for a pad, holding station just off to one side of it.
             *
             * Offset rather than directly overhead on purpose: the aircraft
             * on the pad has to be able to lift straight up to leave, and
             * parking in that column is precisely where it would collide.
             * A helicopter can simply stop and wait, which is the whole
             * advantage it has over a plane in a holding pattern.
             */
            case HOVERING -> {
                BlockPos pad = destinationPad(destination);
                if (pad == null) {
                    holdForMissingPad(destination);
                } else {
                    BlockPos standoff = new BlockPos(
                            pad.getX() + HOVER_STANDOFF_BLOCKS, cruiseAltitude(), pad.getZ());
                    applyMotionTowards(standoff);
                    if (tickCounter % HOVER_RECHECK_INTERVAL_TICKS == 0
                            && claimDestinationPad(serverLevel, destination)) {
                        note("Pad clear - descending.");
                        setState(FlightState.VERTICAL_DESCENT);
                    }
                }
            }
            // Straight down onto the pad, and parked once it's there.
            case VERTICAL_DESCENT -> {
                BlockPos pad = destinationPad(destination);
                if (pad == null) {
                    holdForMissingPad(destination);
                } else if (applyMotionTowards(pad)) {
                    arrive();
                }
            }
            case CRUISE -> {
                // Rotorcraft don't hold or fly approaches: cross to the pad at
                // altitude, then go straight down onto it.
                if (craftType().isVertical()) {
                    BlockPos pad = destinationPad(destination);
                    if (pad == null) {
                        holdForMissingPad(destination);
                    } else {
                        BlockPos overhead = withY(pad, cruiseAltitude());
                        applyMotionTowards(overhead);
                        if (horizontalDistance(overhead, BlockPos.containing(simulatedPosition)) <= CRAFT_ARRIVAL_RADIUS) {
                            // Only start down if the pad is actually free.
                            if (claimDestinationPad(serverLevel, destination)) {
                                setState(FlightState.VERTICAL_DESCENT);
                            } else {
                                setState(FlightState.HOVERING);
                            }
                        }
                    }
                    break;
                }

                List<BlockPos> loop = positionsOf(destination, Waypoint.Type.HOLDING_PATTERN);
                if (loop.isEmpty()) {
                    beginApproach(serverLevel, destination);
                } else {
                    if (holdingEntryIndex < 0) holdingEntryIndex = nearestIndex(loop, simulatedPosition);
                    BlockPos entry = withY(loop.get(holdingEntryIndex), holdingAltitude(destination));
                    applyMotionTowards(entry);
                    if (horizontalDistance(entry, BlockPos.containing(simulatedPosition)) <= HOLDING_ENTRY_LEAD_BLOCKS) {
                        if (claimArrival(serverLevel, destination)) {
                            note("Runway clear - straight in.");
                            setState(FlightState.APPROACH);
                        } else {
                            setState(FlightState.HOLDING);
                        }
                    }
                }
            }
            // One full lap of the holding pattern starting at the point CRUISE
            // entered from, in the layout's configured direction, then
            // cleared to land - no real ATC/queueing yet (see DESIGN.md).
            // Circle until the runway frees up, re-asking each lap.
            case HOLDING -> followWaypoints(path(() -> holdingLap(destination)), () -> {
                if (claimArrival(serverLevel, destination)) {
                    setState(FlightState.APPROACH);
                } else {
                    // Still occupied - go round again rather than landing on
                    // top of whoever's down there. Say what we're waiting for,
                    // so a plane circling forever is diagnosable rather than
                    // just mysterious.
                    currentWaypointIndex = 0;
                    if (++holdingLaps % HOLDING_REPORT_EVERY_LAPS == 0) {
                        note("Still holding - " + blockerDescription(serverLevel, destination) + ".");
                    }
                }
            });
            // Final leg (holding pattern -> runway far end, descending), then
            // roll down the runway to the gate end, ready to taxi in.
            case APPROACH -> followWaypoints(path(() -> approachPath(destination)), () -> setState(FlightState.TAXI_IN));
            // Once back past the hold point the plane is clear of the runway,
            // so release then rather than at the gate - that's the whole
            // reason the hold point exists. Without one, hold the clearance
            // to the gate, since there's no defined point at which the plane
            // stops being in the way.
            case TAXI_IN -> {
                BlockPos holdShort = holdShortPoint(destination, destination.arrivalRunway());
                if (holdShort != null && !clearedPastHoldShort
                        && horizontalDistance(holdShort, BlockPos.containing(simulatedPosition)) <= CRAFT_ARRIVAL_RADIUS) {
                    clearedPastHoldShort = true;
                    releaseApproach();
                    note("Runway vacated.");
                }
                followWaypoints(path(() -> groundTaxiPath(destination, true)), this::arrive);
            }
            default -> { }
        }

        // Publish our position for other planes' separation checks, and work
        // out whether we're the one that has to give way.
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        // Keep the roster current wherever we are. This used to remove the
        // aircraft from the written-down list the moment it started moving,
        // on the theory that only parked aircraft needed finding again - but
        // an aircraft frozen halfway through a leg is exactly as lost as one
        // asleep at a gate, and rather harder to go and look for.
        rememberSelf(serverLevel);
        // Keep any clearance we hold alive. Going quiet is what lets another
        // plane reclaim it, so a flight that ends abruptly can't lock a field.
        registry.heartbeat(planeId(), serverLevel.getGameTime());
        // Report whether airborne or not: a plane taxiing is still traffic
        // the tower should be able to see. Separation, though, stays an
        // airborne-only concern - see separationOffset.
        boolean airborne = !isGroundState();
        registry.reportAirborne(new TrafficReport(
                planeId(), callsign(), state.name(), simulatedPosition,
                destinationLabel(registry), airborne), serverLevel.getGameTime());

        if (!airborne) {
            currentSeparationOffset = 0;
        } else if (tickCounter % SEPARATION_CHECK_INTERVAL_TICKS == 0) {
            currentSeparationOffset = separationOffset(serverLevel);
        }

        // Drag the loaded-chunk bubble along with the plane, so it doesn't
        // fly into unloaded world and freeze - see FlightChunkLoader.
        if (tickCounter % CHUNK_FOLLOW_INTERVAL_TICKS == 0) {
            FlightChunkLoader.follow(serverLevel, planeId(),
                    new ChunkPos(BlockPos.containing(simulatedPosition)), heldChunks);
            // Moving under our own bubble now, so hand back any wake ticket
            // that got us started. It has done its job, and leaving it to
            // time out keeps a second patch of world open at the airport this
            // aircraft has already left.
            FleetWake.release(serverLevel.getServer(), planeId());
        }

        // Power, once a second of world time. Both the cadence and the fuel
        // burn are measured against the game clock rather than counted in
        // passes, because this method runs off the physics tick and that
        // fires faster than twenty times a second.
        long now = serverLevel.getGameTime();
        if (now - lastPowerCheckGameTime >= POWER_CHECK_INTERVAL_TICKS) {
            // Elapsed world time, not the nominal interval: a laggy or newly
            // woken aircraft can be well past due, and charging it for the
            // interval it was supposed to take would let a fleet fly for free
            // through exactly the conditions that stop them ticking.
            long elapsed = Math.min(now - lastPowerCheckGameTime, MAX_FUEL_CATCHUP_TICKS);
            lastPowerCheckGameTime = now;
            if (fuelReserve > 0) fuelReserve -= elapsed * fuelBurnRate();
            boolean powered = hasPower(now);
            if (powered != hasPowerNow) {
                hasPowerNow = powered;
                // Losing power is an engine failure, not a pause: steering
                // below drops the linear correction and keeps only attitude,
                // so the craft coasts and comes down instead of hanging in
                // the air on an autopilot that is no longer paying for it.
                message(powered
                        ? "Power restored - back under thrust."
                        : powerMissingReason() + " Coasting.");
            }
        }

        if (++tickCounter % TELEMETRY_INTERVAL_TICKS == 0) {
            sendTelemetry(serverLevel.getServer());
        }
    }

    private void arrive() {
        ScheduleEntry entry = currentEntry();
        message("Arrived at " + (entry == null ? "gate" : entry.gateName()) + ".");
        playArrivalChime();
        // Parked and out of everyone's way - the airport is free now, and
        // this plane is no longer traffic to be separated from.
        releaseApproach();
        if (level instanceof ServerLevel serverLevel && planeId != null) {
            AirportRegistry.get(serverLevel).clearAirborne(planeId);
        }

        if (entry == null) {
            disengage();
            return;
        }

        waitUntilGameTime = -1; // started on the first waiting tick, in game time
        cargoContainers = null; // re-locate holds for this stop
        setState(FlightState.WAITING);
        message(switch (entry.condition()) {
            case TIMER -> "Holding at the gate for " + entry.waitSeconds() + "s.";
            case PLAYER -> "Waiting for a player to board.";
            case CARGO_LOADED -> "Waiting for cargo to be loaded.";
            case CARGO_EMPTY -> "Waiting to be unloaded.";
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

        // Count in world time, not in calls.
        //
        // This used to decrement a counter once per pass, which is only a
        // second's worth of passes if something calls it twenty times a
        // second - and while mounted on a craft the driver is Sable's physics
        // tick, which runs faster than that. A ten second wait was ending in
        // about five. An absolute deadline in game time is immune to however
        // often, or from where, this gets called.
        long now = serverLevel.getGameTime();
        if (waitUntilGameTime < 0) {
            waitUntilGameTime = now + Math.max(0, entry.waitSeconds()) * 20L;
        }
        boolean timeUp = now >= waitUntilGameTime;

        boolean ready = switch (entry.condition()) {
            case TIMER -> timeUp;
            case PLAYER -> isPlayerNearby(serverLevel);
            // For cargo the timer becomes a timeout rather than the condition:
            // leave when loaded, or give up waiting after waitSeconds. A
            // waitSeconds of 0 means wait as long as it takes, which is what
            // you want at the loading end of a run that isn't ready yet.
            case CARGO_LOADED -> cargoCount() > 0
                    || (entry.waitSeconds() > 0 && timeUp);
            case CARGO_EMPTY -> cargoCount() == 0
                    || (entry.waitSeconds() > 0 && timeUp);
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
        note("Departing for " + nextEntry.gateName() + ".");
        // Depart from where the plane actually is - it flew here itself, so
        // its own tracked position is right even if the player wandered off.
        ServerPlayer player = controllingPlayerId == null ? null
                : serverLevel.getServer().getPlayerList().getPlayer(controllingPlayerId);
        engageCurrentLeg(serverLevel, BlockPos.containing(simulatedPosition), player);
    }

    /**
     * How much the aircraft is currently carrying.
     *
     * Containers are located once per stop and remembered - see CargoSensor.
     * Reads run against this block entity's own level, which while mounted is
     * the craft's sub-level, so "nearby blocks" means the aircraft's own
     * cargo hold rather than whatever happens to be under it on the ground.
     */
    private int cargoCount() {
        if (level == null) return 0;
        if (cargoContainers == null) {
            cargoContainers = CargoSensor.findContainers(level, getBlockPos());
            if (cargoContainers.isEmpty()) {
                note("No cargo containers found on this aircraft.");
            }
        }
        return CargoSensor.countItems(level, cargoContainers);
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
        invalidatePath();
        // Each ground phase starts on the near side of the hold point again:
        // taxiing out hasn't been cleared onto the runway yet, and taxiing in
        // hasn't yet crossed back off it.
        if (newState == FlightState.TAXI_OUT || newState == FlightState.TAXI_IN) {
            this.clearedPastHoldShort = false;
        }
        setChanged();
        // Routine progress goes to the action bar, not chat. One plane
        // narrating every state change was tolerable; several of them turn
        // chat into a wall of telemetry, and the ATC screen is the place to
        // read what everything is doing. Chat is kept for things that need a
        // decision - refusals, and arriving somewhere.
        note(switch (newState) {
            case PUSHBACK -> "Pushing back from the gate.";
            case VERTICAL_CLIMB -> "Lifting off.";
            case VERTICAL_DESCENT -> "Descending onto the pad.";
            case HOVERING -> "Holding clear - pad occupied.";
            case TAKEOFF_ROLL -> "On the runway, taking off.";
            case CLIMB -> "Climbing to cruising altitude.";
            case CRUISE -> "Airborne, cruising toward destination.";
            case HOLDING -> "Entering the holding pattern - waiting for clearance.";
            case APPROACH -> "Cleared to land, on final approach.";
            case TAXI_IN -> "Landed, taxiing to gate.";
            default -> null;
        });
    }

    /**
     * The route for the current phase, worked out once instead of every tick.
     *
     * These paths don't change while a phase runs, but they were being
     * rebuilt on every single tick - and for taxiing that meant a full
     * Dijkstra over the ground network, with its maps and priority queue,
     * sixty times a second per aircraft. All of it immediately discarded.
     * Computing on entry and reusing turns the busiest allocation in the mod
     * into nothing.
     *
     * Invalidated by {@link #setState} and anywhere the route genuinely
     * changes mid-phase (crossing the hold line, for instance).
     */
    private List<BlockPos> path(java.util.function.Supplier<List<BlockPos>> compute) {
        if (cachedPath == null) cachedPath = compute.get();
        return cachedPath;
    }

    private void invalidatePath() {
        cachedPath = null;
    }

    /** Walks `targets` in order, one at a time, calling onFinished once the last is reached. */
    private void followWaypoints(List<BlockPos> targets, Runnable onFinished) {
        if (targets.isEmpty() || currentWaypointIndex >= targets.size()) {
            onFinished.run();
            return;
        }

        BlockPos target = targets.get(currentWaypointIndex);
        boolean isLast = currentWaypointIndex == targets.size() - 1;
        steeringToLastWaypoint = isLast;

        // Start the turn early on intermediate waypoints: airborne, cut the
        // corner well before arriving so the plane eases onto the new leg,
        // rather than flying to the point, stopping, and pivoting. The last
        // waypoint still has to be reached properly.
        if (!isLast && !isGroundState() && activeBody != null) {
            double distance = Math.sqrt(
                    Math.pow(target.getX() + 0.5 - simulatedPosition.x, 2)
                            + Math.pow(target.getZ() + 0.5 - simulatedPosition.z, 2));
            // Measure the leg being flown, so the corner cut is proportional
            // to it. A fixed radius is fine on long cruise legs and disastrous
            // on a tight holding pattern: at cruise speed it exceeded whole
            // pattern legs, so the plane "arrived" at every corner before
            // really flying toward it, cut the entire circuit, and reached the
            // final leg so badly placed it never captured it - going round
            // forever. Scaling to the leg means a small pattern simply gets
            // small anticipation rather than needing a minimum size.
            BlockPos previous = currentWaypointIndex > 0
                    ? targets.get(currentWaypointIndex - 1)
                    : BlockPos.containing(simulatedPosition);
            double legLength = horizontalDistance(previous, target);
            if (distance <= turnAnticipationRadius(legLength)) {
                currentWaypointIndex++;
                return;
            }
        }

        if (applyMotionTowards(target)) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= targets.size()) onFinished.run();
        }
    }

    /**
     * How far out to begin cutting a corner: scaled to speed, because a
     * faster plane needs more room to come round, but never more than a
     * fraction of the leg it's actually flying, or it skips the leg entirely.
     */
    private double turnAnticipationRadius(double legLength) {
        double cap = Math.max(CRAFT_ARRIVAL_RADIUS, legLength * MAX_CORNER_CUT);
        return Math.min(currentTopSpeed() * TURN_ANTICIPATION_SECONDS, cap);
    }

    /**
     * Target speed for whatever the plane is doing now.
     *
     * Cruise speed is for cruising. Flying a holding pattern or an approach
     * at 24+ blocks/second means overshooting every turn and correcting back,
     * which looks nothing like an aircraft in a circuit - so the pattern and
     * the approach run at a slower fixed speed, or the schedule's cruise
     * speed if that's already slower.
     */
    private double currentTopSpeed() {
        if (state == FlightState.PUSHBACK) return groundSpeed() * PUSHBACK_SPEED_FRACTION;
        if (isGroundState()) return groundSpeed();
        if (state == FlightState.HOLDING || state == FlightState.APPROACH) {
            return Math.min(cruiseSpeed(), CRAFT_PATTERN_SPEED);
        }
        return cruiseSpeed();
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

        // A new waypoint starts with no history, or the check below would
        // read "further than last time" off the point we were flying to
        // before and count the new one as already reached.
        if (!target.equals(lastWaypointTarget)) {
            lastWaypointTarget = target;
            lastWaypointDistance = Double.MAX_VALUE;
        }

        // Arrival is a sphere, and a sphere can be jumped clean over: one
        // long physics step - a server hitch, a chunk load - moves the craft
        // further than the radius and it never registers, so it turns around
        // and comes back for a point it has already passed. Receding from a
        // waypoint it had got close to counts as reaching it.
        boolean passed = distance > lastWaypointDistance
                && lastWaypointDistance <= CRAFT_ARRIVAL_RADIUS * 2.5;
        if (distance <= CRAFT_ARRIVAL_RADIUS || passed) {
            pitchDegrees = 0;
            lastWaypointTarget = null;
            lastWaypointDistance = Double.MAX_VALUE;
            return true;
        }
        lastWaypointDistance = distance;

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
        // Only ease off for a waypoint the plane actually has to stop at.
        // Braking for every point in the route is what made it slow down,
        // arrive, and then turn sharply - a plane passing a turn point should
        // carry its speed through the corner.
        double topSpeed = currentTopSpeed();
        double speed = steeringToLastWaypoint
                ? Math.min(topSpeed, distance * CRAFT_APPROACH_GAIN)
                : topSpeed;
        speed *= alignmentFactor(heading);
        Vec3 desired = heading.scale(speed);

        Vector3dc v = activeBody.getLinearVelocity();
        // Correct a fixed fraction of the error per SECOND rather than per
        // call. At the nominal 20 ticks a second this is exactly the old
        // behaviour; when the server is running long steps it stops the
        // craft steering sluggishly just because it is being asked less
        // often. Capped at 1 so it converges instead of overshooting.
        double gain = Math.min(1.0, CRAFT_STEER_GAIN * physicsStepSeconds / NOMINAL_STEP_SECONDS);
        Vec3 correction = desired.subtract(new Vec3(v.x(), v.y(), v.z())).scale(gain);

        // Unpowered: steer, but don't drive. Attitude control below still
        // runs - an aircraft losing its engine keeps its wings level - but
        // nothing accelerates the craft or holds it up, so it coasts and
        // descends the way an unpowered aircraft should. Braking is still
        // allowed, or an aircraft that ran dry on the taxiway would be
        // unable to stop.
        if (!hasPowerNow) {
            boolean slowingDown = correction.dot(new Vec3(v.x(), v.y(), v.z())) < 0;
            correction = slowingDown ? correction.multiply(1, 0, 1) : Vec3.ZERO;
        }

        // Going straight up or down, ease off sideways.
        //
        // A rotorcraft or airship carrying sails makes its own forward thrust,
        // and it does not stop making it because the autopilot would rather
        // hold station. Correcting that away at full gain on every physics
        // tick is a controller fighting a constant disturbance: the craft is
        // shoved back, the sails push again, and the result is a visible
        // judder all the way down.
        //
        // Inside the deadband nothing is corrected at all, so a little drift
        // is simply allowed. Beyond it the correction returns gently. The
        // vertical axis keeps full authority throughout - that is the one
        // actually flying the manoeuvre, and a soft descent rate would be a
        // real problem rather than a cosmetic one.
        if (isVerticalManoeuvre()) {
            double driftX = position.x - (target.getX() + 0.5);
            double driftZ = position.z - (target.getZ() + 0.5);
            double drift = Math.sqrt(driftX * driftX + driftZ * driftZ);
            // Graded, not simply on or off. Full authority while genuinely
            // off course, or a craft under its own thrust would never make it
            // to the pad in the first place - it would drift away at a third
            // of the strength needed to bring it back. Softened only in the
            // endgame, where the judder actually happens.
            double sideways;
            if (drift <= VERTICAL_DRIFT_DEADBAND_BLOCKS) {
                sideways = 0.0;
            } else if (drift >= VERTICAL_FULL_AUTHORITY_BLOCKS) {
                sideways = 1.0;
            } else {
                sideways = VERTICAL_SIDEWAYS_GAIN_SCALE;
            }
            correction = new Vec3(correction.x * sideways, correction.y, correction.z * sideways);
        }

        // Pushing back: keep the nose where it is and roll backwards. Steering
        // toward the heading would have the plane pirouette on the stand.
        Vector3d spin = state == FlightState.PUSHBACK
                ? levelOnlyCorrection()
                : angularCorrectionTowards(heading);
        activeBody.addLinearAndAngularVelocity(
                new Vector3d(correction.x, correction.y, correction.z), spin);

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
        // Reversing off a stand is nose-backwards on purpose, so a dot of -1
        // there means perfectly aligned, not perfectly wrong. Without this
        // the whole pushback ran at the misalignment floor - a crawl - which
        // only became obvious once pushback covered the full gate spur.
        if (state == FlightState.PUSHBACK) dot = Math.abs(dot);
        // 1 when pointing straight at it, tapering to a crawl when sideways -
        // never zero, or a craft that starts badly aligned could never build
        // the speed it needs for the turn to bite.
        return Math.max(MIN_MISALIGNED_THROTTLE, dot);
    }

    /** Hold the current heading, just keep the craft level and stop it
     *  spinning - used while reversing off a stand. */
    private Vector3d levelOnlyCorrection() {
        if (activeSubLevel == null || activeBody == null) return new Vector3d();
        Quaterniondc orientation = activeSubLevel.logicalPose().orientation();
        Direction facing = getBlockState().hasProperty(AutopilotBlock.FACING)
                ? getBlockState().getValue(AutopilotBlock.FACING)
                : Direction.NORTH;
        Vector3d nose = orientation.transform(
                new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
        Vector3d up = orientation.transform(new Vector3d(0, 1, 0));
        // Target heading == current heading, so only the roll/pitch levelling
        // terms do anything.
        return attitudeCorrection(nose, up, new Vector3d(nose), true);
    }

    /** Straight up, straight down, or holding station - the manoeuvres where
     *  the craft has no forward flight to speak of. */
    private boolean isVerticalManoeuvre() {
        return state == FlightState.VERTICAL_CLIMB
                || state == FlightState.VERTICAL_DESCENT
                || state == FlightState.HOVERING;
    }

    private boolean isGroundState() {
        return state == FlightState.PUSHBACK
                || state == FlightState.TAXI_OUT
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
        //
        // A straight-up or straight-down target has no heading to speak of,
        // and atan2(0, 0) quietly answers "north" - so a descending helicopter
        // was being told to swing its nose round to face north, and to bank
        // into that turn, which is exactly the wobble you'd see on the way
        // down. With no horizontal component there is nothing to aim at, so
        // hold whatever heading the craft already has.
        double targetFlat = Math.sqrt(target.x * target.x + target.z * target.z);
        double yawError = 0;
        if (targetFlat > 1.0e-4) {
            double noseYaw = Math.atan2(nose.x / flatLen, nose.z / flatLen);
            double targetYaw = Math.atan2(target.x, target.z);
            yawError = Math.atan2(Math.sin(targetYaw - noseYaw), Math.cos(targetYaw - noseYaw));
        }

        // --- pitch: about the craft's wing axis. Level on the ground.
        double nosePitch = Math.asin(Math.max(-1, Math.min(1, nose.y)));
        // Level on the ground, and level on the way down: a plane on final
        // holds its attitude and descends, rather than aiming its nose at the
        // threshold. Only the climb actually pitches.
        boolean holdLevel = onGround || state == FlightState.APPROACH;
        double targetPitch;
        if (craftType().isVertical()) {
            // A helicopter tips its nose down to push itself along, and holds
            // that attitude in the cruise; an airship just floats level. Both
            // stay level going straight up or down, where there's no forward
            // motion to lean into.
            boolean movingAlong = state == FlightState.CRUISE;
            targetPitch = (movingAlong && craftType() == CraftType.HELICOPTER)
                    ? -Math.toRadians(HELI_CRUISE_PITCH_DEGREES)
                    : 0;
        } else {
            targetPitch = holdLevel ? 0 : Math.asin(Math.max(-1, Math.min(1, target.y)));
        }
        double pitchError = targetPitch - nosePitch;

        Vector3d right = new Vector3d(nose).cross(worldUp);
        if (right.lengthSquared() < 1.0e-8) return new Vector3d();
        right.normalize();

        // --- roll: hold the wings level, except in a turn.
        //
        // Airborne, bank into the turn by an amount proportional to how hard
        // we're turning - a plane that changes heading with its wings dead
        // level looks like it's sliding sideways through the air. Kept well
        // short of a real aerobatic bank: enough to read as an aeroplane, not
        // enough to look like it's falling out of the sky. On the ground the
        // target stays zero, since a taxiing plane has wheels, not wings.
        Vector3d idealUp = new Vector3d(right).cross(nose).normalize();
        double actualRoll = Math.atan2(up.dot(right), up.dot(idealUp));
        double desiredRoll = onGround ? 0
                : Math.max(-MAX_BANK_RADIANS, Math.min(MAX_BANK_RADIANS, -yawError * BANK_PER_YAW_ERROR));
        double rollError = actualRoll - desiredRoll;

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

    /** This plane's identity for runway clearances - generated once and
     *  persisted, so a clearance can be matched back to its holder. */
    private UUID planeId() {
        if (planeId == null) {
            planeId = UUID.randomUUID();
            setChanged();
        }
        return planeId;
    }

    /**
     * How far above this plane's normal altitude to fly to stay clear of
     * other traffic - 0 when there's nobody to avoid.
     *
     * Altitude separation rather than steering around each other, which is
     * how real ATC does it and for the same reason: if both aircraft turn,
     * they can turn into each other, and a dodge that depends on predicting
     * the other's dodge is unstable. Here only ONE plane ever moves - the one
     * with the higher id, compared directly - and it moves along an axis the
     * other isn't using. No negotiation, no oscillation, and it resolves the
     * same way no matter which plane runs its tick first.
     */
    private int separationOffset(ServerLevel serverLevel) {
        if (simulatedPosition == null || planeId == null) return 0;

        int stacked = 0;
        for (Map.Entry<UUID, TrafficReport> other : AirportRegistry.get(serverLevel).airborneTraffic().entrySet()) {
            if (other.getKey().equals(planeId)) continue;
            // Planes on the ground aren't a separation problem - they're
            // handled by the taxiway and runway clearances instead.
            if (!other.getValue().airborne()) continue;
            Vec3 pos = other.getValue().position();
            double dx = pos.x - simulatedPosition.x;
            double dz = pos.z - simulatedPosition.z;
            if (dx * dx + dz * dz > SEPARATION_RADIUS * SEPARATION_RADIUS) continue;
            if (Math.abs(pos.y - simulatedPosition.y) > SEPARATION_ALTITUDE) continue;
            // Only the higher id gives way, so the pair never both move.
            if (planeId.compareTo(other.getKey()) > 0) stacked++;
        }
        return stacked * SEPARATION_ALTITUDE;
    }

    /**
     * Where to reverse to when leaving a stand: the next node along the route
     * out, but only if that node isn't already the runway.
     *
     * Returns null when the gate connects straight to the runway - there's
     * nothing to back out of, so the plane just turns and goes.
     */
    @org.jetbrains.annotations.Nullable
    /**
     * A waiting spot short of the hold line, backed off along the route the
     * aircraft arrived on.
     *
     * Falls back to the line itself if there's no route to measure a
     * direction from - being on the line beats not knowing where to stop.
     */
    private BlockPos standoffBefore(AirportLayout origin, BlockPos holdShort) {
        List<BlockPos> route = groundTaxiPath(origin, false);
        BlockPos previous = null;
        for (BlockPos node : route) {
            if (node.equals(holdShort)) break;
            previous = node;
        }
        if (previous == null) previous = joinPoint;
        if (previous == null) return holdShort;

        double dx = previous.getX() - holdShort.getX();
        double dz = previous.getZ() - holdShort.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0) return holdShort;

        return new BlockPos(
                (int) Math.round(holdShort.getX() + dx / length * HOLD_LINE_STANDOFF_BLOCKS),
                holdShort.getY(),
                (int) Math.round(holdShort.getZ() + dz / length * HOLD_LINE_STANDOFF_BLOCKS));
    }

    private BlockPos pushbackTarget(AirportLayout origin) {
        List<BlockPos> route = groundTaxiPath(origin, false);
        if (route.size() < 2) return null; // straight onto the runway

        // The SECOND node, not the first. The first is whatever node the
        // aircraft is already parked on, so reversing to it barely moves -
        // which is why pushback used to be a nudge and a pirouette. A real
        // pushback runs the length of the stand's spur, back to the junction
        // where it can turn and drive forward.
        BlockPos junction = route.get(1);

        // Unless that junction is the runway itself: a stand that opens
        // straight onto it has nothing to back along, so just turn and go.
        // Either runway's gate end counts - a stand opening straight onto
        // one has nothing to back along whichever strip it is.
        List<BlockPos> arrival = origin.arrivalRunway();
        List<BlockPos> departure = origin.departureRunway();
        if (!arrival.isEmpty() && junction.equals(arrival.get(0))) return null;
        if (!departure.isEmpty() && junction.equals(departure.get(0))) return null;
        return junction;
    }

    /** Names whatever is keeping this plane out of the airport, so "why is it
     *  circling" has an answer in game rather than needing a code read. */
    private String blockerDescription(ServerLevel serverLevel, AirportLayout destination) {
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        UUID holder = registry.trafficHolder(destination.id(), AirportRegistry.ARRIVAL_RUNWAY);
        if (holder == null) return "waiting for the taxiway to clear";
        TrafficReport report = registry.airborneTraffic().get(holder);
        return report == null
                ? "runway held by an aircraft that isn't reporting"
                : "runway held by " + report.callsign() + " (" + prettyState(report.state()) + ")";
    }

    private static String prettyState(String state) {
        return state.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    /** "Airport / Gate" for the ATC readout, or the gate alone if the airport
     *  has since been deleted out from under us. */
    private String destinationLabel(AirportRegistry registry) {
        ScheduleEntry entry = currentEntry();
        if (entry == null) return "-";
        String gate = entry.gateName();
        return registry.byId(entry.airportId())
                .map(layout -> layout.displayName() + " / " + gate)
                .orElse(gate);
    }

    /** Remove this aircraft from the persisted parked list, wherever we can
     *  reach a real level from. */
    /** Write down where this aircraft is, so the tower can find it again
     *  once its chunks are gone. */
    private void rememberSelf(ServerLevel serverLevel) {
        if (simulatedPosition == null) return;
        registry(serverLevel).remember(planeId(),
                serverLevel.dimension().location().toString(),
                BlockPos.containing(simulatedPosition),
                callsign(), destinationLabel(registry(serverLevel)),
                state.name(), serverLevel.getGameTime());
    }

    private void forgetParked() {
        if (planeId == null) return;
        ServerLevel serverLevel = level instanceof ServerLevel direct ? direct
                : (activeSubLevel != null && activeSubLevel.getLevel() instanceof ServerLevel parent ? parent : null);
        if (serverLevel != null) AirportRegistry.get(serverLevel).forget(planeId);
    }

    private static AirportRegistry registry(ServerLevel level) {
        return AirportRegistry.get(level);
    }

    /**
     * What to do when the airport this aircraft was heading for no longer
     * exists - someone broke its station block, possibly while the aircraft
     * was halfway there.
     *
     * Simply disengaging, which is what this used to do, is the worst
     * available answer: an aircraft in the cruise stops being controlled
     * wherever it happens to be, which for a physics-driven craft means it
     * drops out of the sky. Something has to be done with it instead.
     *
     * In order of preference: continue to the next stop that still exists;
     * failing that, divert to the nearest airport that does; and only if the
     * world contains no airports at all, give up - because at that point
     * there is genuinely nowhere to go.
     */
    private void handleLostDestination(ServerLevel serverLevel) {
        AirportRegistry registry = AirportRegistry.get(serverLevel);

        // Drop every stop whose airport is gone - keeping them would just
        // strand the aircraft again at the next leg.
        schedule.entries().removeIf(entry -> registry.byId(entry.airportId()).isEmpty());

        if (!schedule.entries().isEmpty()) {
            scheduleIndex = 0;
            ScheduleEntry next = schedule.entries().get(0);
            message("Destination removed - continuing to " + next.gateName() + ".");
            replanFrom(serverLevel);
            return;
        }

        AirportLayout alternate = nearestAirport(registry);
        if (alternate == null) {
            // Nothing left anywhere. Say so plainly rather than silently
            // going limp - this is the one case with no good outcome.
            message("Destination removed and no other airport exists - autopilot off.");
            disengage();
            return;
        }

        String stop = firstStopAt(alternate);
        schedule.entries().add(new ScheduleEntry(alternate.id(), stop,
                ScheduleEntry.WaitCondition.TIMER, 10));
        schedule.setLoop(false); // a diversion is a one-off, not a new route
        scheduleIndex = 0;
        message("Destination removed - diverting to " + alternate.displayName() + ".");
        replanFrom(serverLevel);
    }

    /** Re-enter the route from wherever the aircraft currently is, rather than
     *  from a gate it may be nowhere near. */
    private void replanFrom(ServerLevel serverLevel) {
        ServerPlayer player = controllingPlayerId == null ? null
                : serverLevel.getServer().getPlayerList().getPlayer(controllingPlayerId);
        engageCurrentLeg(serverLevel, BlockPos.containing(simulatedPosition), player);
    }

    @org.jetbrains.annotations.Nullable
    private AirportLayout nearestAirport(AirportRegistry registry) {
        AirportLayout best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AirportLayout candidate : registry.all()) {
            BlockPos at = AirportSummary.of(candidate).position();
            double distance = horizontalDistance(at, BlockPos.containing(simulatedPosition));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** A gate or pad to aim for at a diversion airport, whichever this craft
     *  can actually use. */
    private String firstStopAt(AirportLayout airport) {
        var stops = craftType().isVertical() ? airport.helipads() : airport.gates();
        return stops.isEmpty() ? "" : stops.keySet().iterator().next();
    }

    private CraftType craftType() {
        return schedule.craftType();
    }

    /** What a route scan found: whether the cruise band is clear, and if not,
     *  the lowest altitude that clears everything sampled. */
    private record RouteScan(boolean clear, int blockedAtY, BlockPos blockedNear, int clearAbove,
                             boolean partial) {}

    /**
     * Is the cruise altitude band clear the whole way there?
     *
     * This used to ask "how high is the ground" via the WORLD_SURFACE
     * heightmap and demand the plane fly above it. Two things were wrong with
     * that. Level#getHeight returns getMinBuildHeight() for a chunk that
     * isn't loaded rather than reading it off disk, so on any route through
     * terrain nobody had loaded - the normal case - every sample came back as
     * the world floor and the check passed trivially. And a heightmap only
     * knows the topmost block in a column, so on a world with floating
     * islands it reported an island's roof and refused routes that would fly
     * safely underneath.
     *
     * So: scan the band the aircraft will actually occupy, and load chunks
     * far enough to have real blocks in them. Anything outside the band is
     * not this check's business.
     */
    private RouteScan scanRoute(ServerLevel serverLevel, BlockPos from, BlockPos to, int altitude) {
        double distance = horizontalDistance(from, to);
        int samples = (int) Math.min(TERRAIN_SCAN_MAX_SAMPLES,
                Math.max(2, distance / TERRAIN_SCAN_SPACING));

        int bandBottom = Math.max(serverLevel.getMinBuildHeight(), altitude - TERRAIN_CLEARANCE_BLOCKS);
        int bandTop = Math.min(serverLevel.getMaxBuildHeight() - 1, altitude + TERRAIN_CLEARANCE_BLOCKS);

        boolean clear = true;
        int blockedAtY = 0;
        BlockPos blockedNear = null;
        int highestSolid = serverLevel.getMinBuildHeight();

        ChunkAccess chunk = null;
        long chunkKey = Long.MIN_VALUE;
        int generated = 0;
        boolean partial = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);

            // Consecutive samples usually land in the same chunk - fetching it
            // once per chunk rather than once per sample is the difference
            // between a hitch and a stall on a long route.
            long key = ChunkPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            if (key != chunkKey) {
                chunkKey = key;
                // SURFACE, not FULL: enough to have the landmass in place,
                // without running features, structures and spawning for every
                // chunk along a route the player is merely flying over.
                //
                // Generating one is still tens of milliseconds, and a long
                // route crosses hundreds of chunks - enough to freeze the
                // server outright while someone stands at a gate. So there's
                // a budget: past it the scan reads chunks that happen to be
                // loaded and reports itself as partial rather than stalling
                // the tick to be thorough.
                boolean mayGenerate = generated < TERRAIN_SCAN_MAX_GENERATED_CHUNKS;
                chunk = serverLevel.getChunk(SectionPos.blockToSectionCoord(x),
                        SectionPos.blockToSectionCoord(z),
                        ChunkStatus.SURFACE, mayGenerate);
                if (chunk == null) partial = true;
                else if (mayGenerate) generated++;
            }
            if (chunk == null) continue;

            for (int y = bandBottom; y <= bandTop; y++) {
                cursor.set(x, y, z);
                if (chunk.getBlockState(cursor).isAir()) continue;
                if (clear) {
                    clear = false;
                    blockedAtY = y;
                    blockedNear = new BlockPos(x, y, z);
                }
                highestSolid = Math.max(highestSolid, y);
            }

            // Only worth finding a clear altitude to suggest once something
            // has actually blocked the way.
            if (!clear) {
                for (int y = bandTop; y < serverLevel.getMaxBuildHeight(); y++) {
                    cursor.set(x, y, z);
                    if (!chunk.getBlockState(cursor).isAir()) highestSolid = Math.max(highestSolid, y);
                }
            }
        }

        return new RouteScan(clear, blockedAtY, blockedNear,
                highestSolid + TERRAIN_CLEARANCE_BLOCKS + 1, partial);
    }

    /**
     * Refuse to set off into a mountain - or an island.
     *
     * Raising the altitude automatically would be friendlier right up until
     * it silently flew a schedule at some height the player never chose and
     * couldn't see; telling them the number to set is both honest and
     * actionable. Checked at engage, when there's still someone standing
     * there to read it.
     *
     * Note the asymmetry: the check permits any altitude whose band is clear,
     * including one that threads under a floating island, but the number it
     * suggests always clears everything on the route. Permissive about what
     * you fly, conservative about what it advises.
     */
    private boolean routeIsFlyable(ServerLevel serverLevel, BlockPos from, AirportLayout destination) {
        BlockPos to = AirportSummary.of(destination).position();
        RouteScan scan = scanRoute(serverLevel, from, to, cruiseAltitude());
        if (scan.clear()) {
            // Let it go, but don't pretend the route was checked end to end -
            // silently approving an unverified route is how a plane ends up
            // inside a hill the scan never looked at.
            if (scan.partial()) {
                message("Route is clear as far as could be checked - part of it "
                        + "runs through unloaded terrain.");
            }
            return true;
        }

        String where = scan.blockedNear() == null ? "on this route"
                : "near " + scan.blockedNear().getX() + ", " + scan.blockedNear().getZ();
        message("Cruising at Y " + cruiseAltitude() + " runs into terrain at Y "
                + scan.blockedAtY() + " " + where
                + " - set cruise altitude to at least Y " + scan.clearAbove() + ".");
        return false;
    }

    /**
     * Take the destination pad if it's free, remembering which one so it can
     * be given back on departure.
     *
     * Held right through the landing and the gate wait, not just the descent:
     * a parked helicopter is still occupying that pad, and the next one
     * arriving needs to know.
     */
    private boolean claimDestinationPad(ServerLevel serverLevel, AirportLayout destination) {
        String padName = destinationPadName(destination);
        if (padName == null) return true; // nothing named to contend over
        boolean granted = AirportRegistry.get(serverLevel)
                .tryClaimPad(destination.id(), padName, planeId(), serverLevel.getGameTime());
        if (granted) {
            occupiedPadAirport = destination.id();
            occupiedPadName = padName;
        }
        return granted;
    }

    /** Give back whichever pad this aircraft is sitting on - it's leaving. */
    private void releasePad() {
        if (occupiedPadName == null || occupiedPadAirport == null || planeId == null) return;
        ServerLevel serverLevel = level instanceof ServerLevel direct ? direct
                : (activeSubLevel != null && activeSubLevel.getLevel() instanceof ServerLevel parent ? parent : null);
        if (serverLevel != null) {
            AirportRegistry.get(serverLevel).releasePad(occupiedPadAirport, occupiedPadName, planeId);
        }
        occupiedPadAirport = null;
        occupiedPadName = null;
    }

    /** The pad name this leg targets, if the destination actually has it. */
    @org.jetbrains.annotations.Nullable
    private String destinationPadName(AirportLayout destination) {
        String name = destinationGateName();
        if (name != null && destination.helipads().containsKey(name)) return name;
        return destination.helipads().isEmpty() ? null
                : destination.helipads().keySet().iterator().next();
    }

    /** The pad this leg is heading for. Falls back to the airport's only pad
     *  if the schedule names one that has since been renamed or removed. */
    @org.jetbrains.annotations.Nullable
    /**
     * The destination has no pad to land on. Hold, don't pretend to arrive.
     *
     * This used to call arrive(), which is how a helicopter ended up taking
     * off and immediately landing back on the pad it left. arrive() parks the
     * aircraft: it sets WAITING, which stops issuing any motion at all, so
     * the craft simply fell out of the air onto whatever was beneath it -
     * usually its own departure pad, because the fake arrival happened the
     * moment it reached cruise altitude overhead. Worse, it counted the leg
     * as flown, so the schedule advanced past a stop the aircraft never
     * reached.
     *
     * Now it holds altitude and keeps saying so. There is no correct
     * automatic recovery - the layout is missing something only the player
     * can draw - so the useful behaviour is to stay safely airborne and be
     * loud about why.
     */
    private void holdForMissingPad(AirportLayout destination) {
        climbVertically(cruiseAltitude());
        if (tickCounter % MISSING_FACILITY_REPEAT_TICKS == 0) {
            message("No landing pad at " + destination.displayName()
                    + " - holding. Draw a pad there, or send this craft somewhere else.");
        }
    }

    /**
     * Can this craft actually land where it is being sent?
     *
     * Checked at engage alongside the terrain scan, for the same reason: the
     * player is standing there and can fix it. A rotorcraft needs a pad and a
     * plane needs a runway, and neither can be improvised on arrival.
     */
    private boolean destinationIsUsable(AirportLayout destination) {
        if (craftType().isVertical()) {
            if (!destination.helipads().isEmpty()) return true;
            message(destination.displayName() + " has no landing pad - "
                    + "draw one on its map before sending a " + craftType().name().toLowerCase(Locale.ROOT)
                    + " there.");
            return false;
        }
        if (positionsOf(destination, Waypoint.Type.RUNWAY).size() >= 2) return true;
        message(destination.displayName() + " has no runway drawn - "
                + "a plane cannot land there.");
        return false;
    }

    /**
     * Does the craft have power right now, burning fuel if that's the mode?
     *
     * Called once a second rather than every physics tick: a fuel check
     * sweeps containers, and the difference between running out of coal now
     * and running out a second from now is not worth a per-tick scan.
     */
    private boolean hasPower(long now) {
        if (SkyportConfig.powerRequirement == SkyportConfig.PowerRequirement.NONE) return true;
        if (level == null) return true;

        if (SkyportConfig.powerRequirement == SkyportConfig.PowerRequirement.ROTATION) {
            return PowerSource.hasRotation(level, getBlockPos());
        }

        // FUEL: burn down what's lit, then light another item when it runs
        // out. Fuel is only spent while actually flying a leg - an aircraft
        // sitting at a gate for ten minutes shouldn't empty its bunker.
        //
        if (fuelReserve > 0) return true;

        if (fuelContainers == null) fuelContainers = PowerSource.findFuelContainers(level, getBlockPos());
        int burnTime = PowerSource.consumeFuel(level, fuelContainers);
        if (burnTime <= 0) {
            // The hold may have been refilled since the scan, or the scan may
            // predate a chest being added. Look once more before declaring
            // the aircraft dry.
            fuelContainers = PowerSource.findFuelContainers(level, getBlockPos());
            burnTime = PowerSource.consumeFuel(level, fuelContainers);
        }
        if (burnTime <= 0) return false;
        fuelReserve = burnTime;
        setChanged();
        return true;
    }

    /**
     * How fast fuel drains right now, relative to flying at the reference
     * speed.
     *
     * Rate rises with speed raised to a configured power, which is what makes
     * choosing a cruise speed a real decision. It has to rise FASTER than
     * linearly for there to be a decision at all: if the rate merely doubled
     * with speed, a trip would cost the same fuel however fast it was flown,
     * and nobody would ever fly slowly. At the default exponent of 2 the fuel
     * per block travelled scales with speed, so doubling cruise speed doubles
     * the cost of the journey. Drag genuinely rises with the square of speed,
     * so this is close to the honest answer as well as the interesting one.
     *
     * Measured from the craft's real velocity where there is one, so a
     * headwind or a climb costs what it actually costs, falling back to the
     * commanded speed for the phantom simulation.
     */
    private double fuelBurnRate() {
        double speed;
        if (activeBody != null) {
            Vector3dc v = activeBody.getLinearVelocity();
            speed = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
        } else {
            speed = currentTopSpeed();
        }

        // The curve itself lives in FuelBurn, where it can be tested: the
        // property that makes it worth having - faster costs more for the
        // same journey, not just more per second - is easy to break by
        // accident and invisible when broken.
        return FuelBurn.rateForSpeed(speed);
    }

    /** What to tell the player when there's no power, in this mode's terms. */
    private String powerMissingReason() {
        return SkyportConfig.powerRequirement == SkyportConfig.PowerRequirement.ROTATION
                ? "No rotational force at the autopilot - it needs at least "
                        + SkyportConfig.rotationMinimumRpm + " RPM from a shaft or cogwheel against it."
                : "No fuel aboard - put something burnable in a container on the aircraft.";
    }

    private BlockPos destinationPad(AirportLayout destination) {
        String name = destinationGateName();
        BlockPos pad = name == null ? null : destination.helipads().get(name);
        if (pad != null) return pad;
        return destination.helipads().isEmpty() ? null : destination.helipads().values().iterator().next();
    }

    /**
     * Rise straight up, no forward component.
     *
     * The pitch cap that keeps a plane from climbing like a rocket is
     * deliberately not applied - that rule exists because wings need airflow,
     * and a rotor or a gasbag doesn't care. This is the one place a craft is
     * allowed to gain height without covering ground.
     */
    private void climbVertically(int targetY) {
        double remaining = targetY - simulatedPosition.y;
        double speed = Math.min(CRAFT_VERTICAL_SPEED, Math.max(1.0, Math.abs(remaining)));
        flyHeading(new Vec3(0, Math.signum(remaining), 0), speed);
    }

    /**
     * Where on the hold line an aircraft stops, if one is drawn.
     *
     * The line is two points painted across the taxiway, the way a real one
     * is - it is a boundary, not a parking spot. Aircraft aim at its middle,
     * which is the part of it the taxiway actually crosses.
     *
     * A single point still works: that is what every airport drawn before the
     * line existed has, and it is exactly the position they were using.
     */
    @org.jetbrains.annotations.Nullable
    private static BlockPos holdShortPoint(AirportLayout layout, List<BlockPos> runway) {
        List<BlockPos> points = positionsOf(layout, Waypoint.Type.HOLD_SHORT);
        if (points.isEmpty()) return null;

        // Which line guards this runway? The nearest one to the end aircraft
        // enter and leave by. An airport can draw as many as it likes - one
        // per runway is the case that matters, and a field with two runways
        // wants two - so picking by proximity means each line ends up
        // protecting the strip it was drawn beside, without anyone having to
        // say so.
        BlockPos reference = runway.isEmpty() ? null : runway.get(0);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i += 2) {
            BlockPos middle = i + 1 < points.size() ? midpoint(points.get(i), points.get(i + 1))
                    : points.get(i); // a lone point: a hold line from before they were lines
            if (reference == null) return middle;
            double distance = horizontalDistance(middle, reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = middle;
            }
        }
        return best;
    }

    /**
     * The two-tone cabin chime, on arriving at a gate.
     *
     * Built from vanilla note-block bells rather than a bundled sound file:
     * two notes a fourth apart, the high one first, which is what a seatbelt
     * sign sounds like. A real recording would be better and this mod ships
     * no audio assets at all, so borrowing an instrument that is already in
     * everyone's resource pack is the version that actually exists.
     *
     * The second note is scheduled rather than played immediately, because a
     * chord is not a chime. Nothing here needs a radius check - playSound
     * already only reaches players near the aircraft, which for once is
     * exactly the rule we want.
     */
    private void playArrivalChime() {
        ServerLevel world = worldLevel();
        if (world == null || simulatedPosition == null) return;
        world.playSound(null, simulatedPosition.x, simulatedPosition.y, simulatedPosition.z,
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                CHIME_VOLUME, CHIME_HIGH_PITCH);
        chimeSecondNoteAt = world.getGameTime() + CHIME_GAP_TICKS;
    }

    /** Plays the answering note once its moment arrives. */
    private void tickArrivalChime(ServerLevel serverLevel) {
        if (chimeSecondNoteAt <= 0 || serverLevel.getGameTime() < chimeSecondNoteAt) return;
        playSecondChimeNote(serverLevel);
    }

    /**
     * Play the answering note now, due or not.
     *
     * Going idle stops this block entity ticking, so a chime still waiting on
     * its second note would never get one - and the last stop of a schedule
     * is exactly when that happens.
     */
    private void flushArrivalChime() {
        ServerLevel world = worldLevel();
        if (chimeSecondNoteAt > 0 && world != null) playSecondChimeNote(world);
    }

    private void playSecondChimeNote(ServerLevel world) {
        chimeSecondNoteAt = 0;
        if (simulatedPosition == null) return;
        world.playSound(null, simulatedPosition.x, simulatedPosition.y, simulatedPosition.z,
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS,
                CHIME_VOLUME, CHIME_LOW_PITCH);
    }

    private static BlockPos midpoint(BlockPos a, BlockPos b) {
        return new BlockPos((a.getX() + b.getX()) / 2, a.getY(), (a.getZ() + b.getZ()) / 2);
    }

    /**
     * Short identifier for this plane, so chat is readable once more than one
     * aircraft is flying - "[Skyport] Holding short" is ambiguous the moment
     * there are two of them.
     */
    private String callsign() {
        // A name the player typed beats a generated one: "Cargo 1" is what
        // they'll look for on the tower's traffic strip, not SKY-4F2A.
        String named = schedule.craftName();
        if (named != null && !named.isBlank()) return named;
        return "SKY-" + planeId().toString().substring(0, 4).toUpperCase(java.util.Locale.ROOT);
    }

    /** Ask for the run of an airport - covers the runway and everything
     *  committed to it, bounded by the hold-short point where one is drawn. */
    private boolean claimTraffic(ServerLevel level, AirportLayout departure) {
        return AirportRegistry.get(level).tryClaimTraffic(departure.id(),
                departureRunwayIndex(departure), planeId(), level.getGameTime());
    }

    /** Which runway a departure uses here: its own if the field has one,
     *  otherwise the same strip arrivals use. */
    private static int departureRunwayIndex(AirportLayout airport) {
        return airport.hasDepartureRunway()
                ? AirportRegistry.DEPARTURE_RUNWAY
                : AirportRegistry.ARRIVAL_RUNWAY;
    }

    private boolean claimTaxiway(ServerLevel level, AirportLayout airport) {
        return AirportRegistry.get(level).tryClaimTaxiway(airport.id(), planeId(), level.getGameTime());
    }

    private void releaseTaxiway(ServerLevel level, AirportLayout airport) {
        if (planeId != null) AirportRegistry.get(level).releaseTaxiway(airport.id(), planeId);
    }

    /** Arrivals take runway and taxiway together - see tryClaimArrival for
     *  why splitting them deadlocks against departures. */
    private boolean claimArrival(ServerLevel level, AirportLayout destination) {
        return AirportRegistry.get(level).tryClaimArrival(destination.id(), planeId(), level.getGameTime());
    }

    /** Take the clearance if it's going, then start down regardless - used
     *  where there's no holding pattern to wait in. */
    private void beginApproach(ServerLevel level, AirportLayout destination) {
        claimTraffic(level, destination);
        setState(FlightState.APPROACH);
    }

    /** Hand back every force-loaded chunk. Called on disengage and when the
     *  block is removed - a plane that vanished while holding tickets would
     *  pin that patch of world open for the rest of the session. */
    private void releaseChunks() {
        if (heldChunks.isEmpty() || planeId == null) return;
        ServerLevel serverLevel = level instanceof ServerLevel direct ? direct
                : (activeSubLevel != null && activeSubLevel.getLevel() instanceof ServerLevel parent ? parent : null);
        if (serverLevel == null) {
            heldChunks.clear();
            return;
        }
        FlightChunkLoader.releaseAll(serverLevel, planeId, heldChunks);
    }

    /**
     * Called when this block entity stops existing - which includes the chunk
     * simply unloading, not only the block being broken.
     *
     * That distinction is the whole point of this comment. Releasing chunks
     * and clearances here is right either way: an aircraft that has stopped
     * ticking should not keep holding tickets or lock an airport. But taking
     * it off the tower's roster here was wrong, and quietly defeated every
     * attempt to make sleeping aircraft visible - the aircraft wrote down
     * where it was, released its chunks, and the resulting unload deleted the
     * record a tick later. Nothing showed on the map, and the entry was gone
     * before anyone could ask to wake it.
     *
     * Roster removal belongs to genuine removal, so it lives in
     * {@link #unregister} instead, called from the block's onRemove.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        releaseChunks();
        // Hand back any clearance too. Breaking an autopilot mid-flight used
        // to leave its airport locked with no plane left to release it, so
        // everyone else circled a field that was actually empty. The lease
        // timeout would recover it eventually; this makes it immediate.
        releaseApproach();
    }

    /**
     * The autopilot block itself is gone for good - take its aircraft off the
     * tower's roster.
     *
     * Deliberately called from the block's onRemove rather than from
     * setRemoved, which also fires on a chunk unload. Same reasoning as
     * AirportStationBlockEntity#unregister, and the same trap.
     */
    public void unregister() {
        forgetParked();
    }

    /** Give the departure airport's runway back once safely airborne. */
    /**
     * Hand back everything the departure airport lent us, now we are airborne.
     *
     * Both clearances, not just the runway. A departure takes the taxiway on
     * pushback and only gives it back at the hold line - and that release
     * lives inside the hold-line branch, so an airport with no hold line
     * drawn never ran it. This method then nulled originAirportId, putting
     * the lease permanently out of reach of every other release path, while
     * the aircraft went on heartbeating happily from the far side of the map
     * so the lease never timed out either.
     *
     * The symptom was an arrival holding forever over an airport with nothing
     * on it: arrivals claim runway and taxiway together, and the taxiway was
     * still held by an aeroplane that had left minutes ago.
     */
    /** Hand back both runway keys rather than working out which one is held.
     *  Releasing a clearance you do not hold is a no-op, and guessing wrong
     *  here leaves an airport locked with no aircraft left to clear it. */
    private void releaseBothRunways(AirportRegistry registry, UUID airportId) {
        registry.releaseTraffic(airportId, AirportRegistry.ARRIVAL_RUNWAY, planeId);
        registry.releaseTraffic(airportId, AirportRegistry.DEPARTURE_RUNWAY, planeId);
    }

    private void releaseOriginRunway(ServerLevel serverLevel) {
        if (originAirportId == null || planeId == null) return;
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        releaseBothRunways(registry, originAirportId);
        registry.releaseTaxiway(originAirportId, planeId);
        originAirportId = null;
    }

    /** Release every runway this plane might be holding - both the one it was
     *  landing at and the one it was departing from. Disengaging mid-taxi
     *  while holding the departure runway would otherwise block that airport
     *  with no plane left to clear it. */
    private void releaseApproach() {
        if (planeId == null) return;
        ServerLevel serverLevel = level instanceof ServerLevel direct ? direct
                : (activeSubLevel != null && activeSubLevel.getLevel() instanceof ServerLevel parent ? parent : null);
        if (serverLevel == null) return;

        AirportRegistry registry = AirportRegistry.get(serverLevel);
        UUID destinationId = destinationAirportId();
        if (destinationId != null) {
            releaseBothRunways(registry, destinationId);
            registry.releaseTaxiway(destinationId, planeId);
        }
        if (originAirportId != null) {
            releaseBothRunways(registry, originAirportId);
            registry.releaseTaxiway(originAirportId, planeId);
        }
        // Stop advertising as traffic too, or everyone else keeps dodging a
        // plane that is no longer flying anywhere.
        registry.clearAirborne(planeId);
    }

    /** Cruise altitude including any separation offset - so a plane giving
     *  way climbs above the traffic rather than through it. */
    /**
     * Taxi speed at whichever airport this aircraft is actually on.
     *
     * Departing, that is the origin; arriving, the destination. It matters
     * which: an aircraft that taxis briskly at its home field should still
     * creep at the cramped one it is visiting, and reading the speed off the
     * wrong end of the route would get that exactly backwards.
     *
     * Falls back to the old constant when there is no airport to ask - an
     * aircraft engaged somewhere off-airport still has to be able to move.
     */
    private double groundSpeed() {
        AirportLayout here = null;
        if (level instanceof ServerLevel serverLevel) {
            AirportRegistry registry = AirportRegistry.get(serverLevel);
            UUID id = state == FlightState.TAXI_IN ? destinationAirportId() : originAirportId;
            if (id == null) id = originAirportId != null ? originAirportId : destinationAirportId();
            if (id != null) here = registry.byId(id).orElse(null);
        }
        return here == null ? CRAFT_TAXI_SPEED : here.taxiSpeed();
    }

    private int cruiseAltitude() {
        return schedule.cruiseAltitude() + currentSeparationOffset;
    }

    /** The destination's holding altitude, likewise offset - two planes in
     *  the same pattern need to be at different heights, not the same one. */
    private int holdingAltitude(AirportLayout destination) {
        return destination.holdingPatternHeight() + currentSeparationOffset;
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

        // Rotate at the halfway mark, which is also where the speed ramp
        // finishes - so the plane is up to speed exactly when it starts to
        // climb, and has the back half of the runway as margin rather than
        // running to the far end first.
        boolean atRotationSpeed = ramp >= 1.0;
        if (atRotationSpeed) {
            takeoffStart = null;
            takeoffHoldTicks = 0;
            // Climb out along the runway heading. Locking it here rather than
            // letting CLIMB work it out is the point: CLIMB used to take its
            // heading from the bearing to the destination, so the plane
            // rotated and immediately struck off across the airfield - often
            // perpendicular to the strip it had just used. Departures fly the
            // runway heading out, then turn once they're up.
            climbHeadingLocked = centreline;
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
            lap.add(withY(loop.get(at), holdingAltitude(destination)));
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
        // Taxi to whichever runway this leg actually uses: out to the
        // departure runway, in from the one arrivals land on.
        List<BlockPos> runway = arriving ? layout.arrivalRunway() : layout.departureRunway();
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
        if (!route.isEmpty()) return route;

        // No connected route - head straight there rather than refusing to
        // move at all, but say so. This used to be a quiet fallback because
        // the only way to reach it was a layout drawn before the editor
        // enforced connectivity, which nobody would hit by accident. One-way
        // taxiways make it something a player can create on purpose, and an
        // aircraft that answers by driving across the grass in a straight
        // line needs to explain itself rather than just look broken.
        message("No taxi route to " + (arriving ? "the gate" : "the runway")
                + " - the one-way directions may not allow it. Going direct.");
        return List.of(to);
    }

    /**
     * Route across the ground network.
     *
     * The graph and the search live in {@link GroundNetwork} because the map
     * editor needs the same answers - once taxiway segments can be one-way,
     * "can an aircraft still get to that gate" is a question the editor has
     * to be able to ask before the player closes the screen.
     */
    private static List<BlockPos> shortestGroundRoute(AirportLayout layout, BlockPos from, BlockPos to) {
        return GroundNetwork.route(layout, from, to);
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
        // Always the arrival runway - the final leg is drawn to it, and a
        // departure runway has no approach path of its own by design.
        List<BlockPos> runway = destination.arrivalRunway();
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
        if (!SkyportConfig.telemetry) return;
        if (controllingPlayerId == null || simulatedPosition == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(controllingPlayerId);
        // Same earshot rule as everything else. This is the noisiest thing
        // the mod can do - once a second, forever - and it was the one path
        // that skipped the radius check, so turning telemetry on to watch one
        // aircraft meant every aircraft narrating from across the map.
        if (player == null || !withinEarshot(player)) return;
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
                Component.literal("[" + callsign() + "] " + state + " @ " + pos + "  " + pitch
                        + fuelReadout() + "  (" + mode + ")"), true);
    }

    /**
     * Seconds of flight left in the lit fuel, at the rate it is burning now.
     *
     * Seconds rather than a raw reserve because the reserve is denominated in
     * reference-speed ticks, which means nothing to anyone: the useful
     * question is how long you have at the speed you are actually flying, and
     * that answer moves when the throttle does.
     */
    private String fuelReadout() {
        if (SkyportConfig.powerRequirement != SkyportConfig.PowerRequirement.FUEL) return "";
        if (fuelReserve <= 0) return "  DRY";
        return String.format("  fuel %.0fs", fuelReserve / fuelBurnRate() / 20.0);
    }

    private void message(String text) {
        if (level == null || level.isClientSide || controllingPlayerId == null) return;
        ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(controllingPlayerId);
        if (!withinEarshot(player)) return;
        message(player, text);
    }

    /** Progress update - action bar rather than chat, so a fleet of planes
     *  doesn't bury everything else the player is reading. */
    private void note(@org.jetbrains.annotations.Nullable String text) {
        if (!SkyportConfig.actionBarMessages) return;
        if (text == null || level == null || level.isClientSide || controllingPlayerId == null) return;
        ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(controllingPlayerId);
        if (player == null || !withinEarshot(player)) return;
        player.displayClientMessage(Component.literal("[" + callsign() + "] " + text), true);
    }

    /**
     * Is the player close enough to this aircraft to be told what it is doing?
     *
     * An aircraft narrating its whole schedule to whoever last touched it is
     * fine with one aeroplane and unbearable with six, especially when they
     * are all somewhere else. Standing next to the thing is a good proxy for
     * caring what it is up to right now; the tower is where you go to check
     * on one you are not standing next to.
     *
     * Measured against the craft's tracked position rather than getBlockPos,
     * which is sub-level-local while mounted and would compare a player's
     * world coordinates against an offset inside the aircraft.
     */
    /** The real world this aircraft is in, seeing through the sub-level it
     *  may be riding inside. */
    @org.jetbrains.annotations.Nullable
    private ServerLevel worldLevel() {
        if (activeSubLevel != null && activeSubLevel.getLevel() instanceof ServerLevel parent) return parent;
        return level instanceof ServerLevel direct ? direct : null;
    }

    private boolean withinEarshot(@org.jetbrains.annotations.Nullable ServerPlayer player) {
        if (player == null) return false;
        int radius = SkyportConfig.messageRadius;
        if (radius <= 0) return true; // 0 means "wherever I am", as before
        if (simulatedPosition == null) return true;
        // A player in the Nether is not near an aircraft in the Overworld,
        // however close the numbers happen to look.
        ServerLevel world = worldLevel();
        if (world != null && player.level() != world) return false;
        // simulatedPosition is already in world coordinates, so comparing a
        // player's position against it is valid whether the block is mounted
        // on a craft or sitting on the ground.
        return player.position().closerThan(simulatedPosition, radius);
    }

    private void message(@org.jetbrains.annotations.Nullable ServerPlayer player, @org.jetbrains.annotations.Nullable String text) {
        if (!SkyportConfig.chatMessages) return;
        if (player == null || text == null) return;
        player.sendSystemMessage(Component.literal("[" + callsign() + "] " + text));
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
        if (planeId != null) tag.putUUID("planeId", planeId);
        tag.putString("state", state.name());
        tag.putInt("currentWaypointIndex", currentWaypointIndex);
        // Fuel already lit is spent - reloading must not hand it back. Held
        // as a remaining amount rather than a deadline, so it simply pauses
        // while this block entity is unloaded instead of draining away in a
        // world clock the aircraft was not flying in.
        if (fuelReserve > 0) tag.putDouble("fuelReserve", fuelReserve);
        // originAirportId, holdingEntryIndex, waitUntilGameTime and
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
        if (tag.hasUUID("planeId")) planeId = tag.getUUID("planeId");
        if (tag.contains("state")) state = FlightState.valueOf(tag.getString("state"));
        currentWaypointIndex = tag.getInt("currentWaypointIndex");
        fuelReserve = tag.getDouble("fuelReserve");
        if (state != FlightState.IDLE) simulatedPosition = getBlockPos().getCenter();
    }
}
