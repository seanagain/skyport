package com.skyport.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A single point the player placed while drawing an airport layout on the
 * station's map. A {@link AirportLayout} is just an ordered list of these,
 * grouped by {@link Type}.
 *
 * Kept deliberately dumb (a position + a type + an index) - all the
 * interesting behaviour (steering a plane from one waypoint to the next,
 * deciding when to switch from RUNWAY to TAXIWAY, etc.) belongs in the
 * autopilot's flight state machine, not here.
 */
public record Waypoint(BlockPos pos, Type type, int order, Flow flow) {

    /**
     * Which way traffic may move along a taxiway segment.
     *
     * Only meaningful on the FIRST point of a TAXIWAY pair, and ignored
     * everywhere else - a runway is flown in the direction the wind and the
     * layout dictate, not the direction it was drawn.
     *
     * One-way taxiways exist because a two-way ground network turns into a
     * standoff the moment two aircraft want the same strip from opposite
     * ends. Marking the inbound and outbound halves of a loop separately
     * lets arrivals and departures pass each other instead of queueing on
     * one shared line.
     */
    public enum Flow {
        /** Traffic may move either way. The default, and right for a spur
         *  that dead-ends at a gate - there is nothing to conflict with. */
        BOTH,
        /** Only first point -> second point, in drawing order. */
        FORWARD,
        /** Only second point -> first point. */
        REVERSE;

        public Flow next() {
            return switch (this) {
                case BOTH -> FORWARD;
                case FORWARD -> REVERSE;
                case REVERSE -> BOTH;
            };
        }

        public String label() {
            return switch (this) {
                case BOTH -> "two-way";
                case FORWARD -> "one-way";
                case REVERSE -> "one-way (reversed)";
            };
        }
    }

    /** Most waypoints have no direction to speak of. */
    public Waypoint(BlockPos pos, Type type, int order) {
        this(pos, type, order, Flow.BOTH);
    }

    public Waypoint withFlow(Flow newFlow) {
        return new Waypoint(pos, type, order, newFlow);
    }


    public enum Type {
        /**
         * Exactly two points: index 0 is the "gate end" (where the taxiway
         * and every gate's short taxi segment meet the runway), index 1 is
         * the "far end" (where FINAL_LEG touches down). One straight line,
         * not a polygon - the map editor enforces the 2-point cap.
         */
        RUNWAY,
        /**
         * A set of 2-point segments, not one line: points (0,1) are the
         * backbone (runway gate-end junction <-> holding pattern entry),
         * and every later pair (2,3), (4,5), ... is one gate's spur, drawn
         * from that gate's position out to wherever it meets the backbone.
         * TAXI_OUT/TAXI_IN walk every point in drawing order (see
         * AutopilotBlockEntity#groundTaxiPath) rather than pathfinding a
         * specific gate's spur - fine with one or two gates, revisit with
         * real graph routing if that gets confusing with more.
         */
        TAXIWAY,
        /** Points forming the loop a plane circles while waiting to land, at
         *  AirportLayout#holdingPatternHeight and traversed in the direction
         *  AirportLayout#holdingPatternClockwise says. */
        HOLDING_PATTERN,
        /**
         * Exactly two points: index 0 near/on the holding pattern (where a
         * plane peels off to land), index 1 the runway's far end (where it
         * touches down). This is the descent path for FlightState.APPROACH.
         */
        FINAL_LEG,
        /**
         * A single point on the taxiway marking where the protected area
         * begins - the runway and everything committed to it.
         *
         * This is the boundary the traffic clearance actually guards. Without
         * it the whole airport had to be one resource (nowhere to wait but
         * the gate, since on a shared strip there's nowhere to pass); with
         * it, a departure can taxi up to here freely and only needs clearance
         * to go beyond, and an arrival frees the runway as soon as it taxis
         * back past this point rather than when it finally parks.
         */
        HOLD_SHORT
        // Gates are NOT a Waypoint type - see AirportLayout#gates. A gate
        // needs a name ("Gate A") so the autopilot GUI can list it, and
        // isn't part of a connected line the way the other four are (it
        // connects to the runway's gate end by an implicit straight line,
        // not a drawn one), so it gets its own small map instead of living
        // in this enum.
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("type", type.name());
        tag.putInt("order", order);
        if (flow != Flow.BOTH) tag.putString("flow", flow.name());
        return tag;
    }

    public static Waypoint load(CompoundTag tag) {
        return new Waypoint(
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                Type.valueOf(tag.getString("type")),
                tag.getInt("order"),
                // Absent on layouts drawn before one-way taxiways existed,
                // which is exactly the two-way behaviour they had.
                tag.contains("flow") ? Flow.valueOf(tag.getString("flow")) : Flow.BOTH);
    }

    /** Network (de)serialization - separate from save()/load() (NBT) so
     *  packet payloads don't need to round-trip through CompoundTag. */
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(type);
        buf.writeVarInt(order);
        buf.writeEnum(flow);
    }

    public static Waypoint read(FriendlyByteBuf buf) {
        return new Waypoint(buf.readBlockPos(), buf.readEnum(Type.class), buf.readVarInt(),
                buf.readEnum(Flow.class));
    }
}
