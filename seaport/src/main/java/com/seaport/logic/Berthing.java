package com.seaport.logic;

/**
 * Where a hull's edge is relative to a quay, and when that counts as berthed.
 *
 * A wharf is a line: the edge of the quay, drawn between two points. A ship is
 * a rectangle. Berthing is the rectangle coming to rest alongside the line
 * with a set gap between them - one block by default, so the vessel is
 * touching distance from the stone rather than inside it.
 *
 * The part that is easy to get wrong is that the gap depends on which way the
 * ship is POINTING. A vessel lying alongside presents its flank, one coming in
 * bow-first presents its stem, and a hull at forty-five degrees presents a
 * corner that reaches further than either. So the hull's dimensions are fixed
 * when the voyage starts - they cannot change - but the distance from its edge
 * to the quay has to be worked out afresh from its current heading, every
 * time. Measure it once at the start and a ship that drifts a few degrees
 * believes it is berthed while a corner is still three blocks out.
 *
 * Everything here is in the horizontal plane. Ships float at one level, and a
 * quay is a line on the water's surface, so height never enters into it.
 */
public final class Berthing {

    private Berthing() { }

    /** A point, in blocks, on the water's surface. */
    public record Point(double x, double z) {

        Point minus(Point other) {
            return new Point(x - other.x(), z - other.z());
        }

        double dot(Point other) {
            return x * other.x() + z * other.z();
        }

        double length() {
            return Math.sqrt(x * x + z * z);
        }

        Point unit() {
            double len = length();
            // A wharf drawn with both ends in the same block has no direction
            // to speak of. Returning something rather than dividing by zero
            // keeps the caller's arithmetic finite; the layout check is what
            // refuses to accept such a wharf in the first place.
            return len < 1e-9 ? new Point(1, 0) : new Point(x / len, z / len);
        }
    }

    /**
     * A ship, reduced to the rectangle it occupies.
     *
     * @param centre       the middle of the hull
     * @param headingRad   which way the bow points, measured the way
     *                     Minecraft measures yaw
     * @param halfLength   bow to centre
     * @param halfBeam     centreline to rail
     */
    public record Hull(Point centre, double headingRad, double halfLength, double halfBeam) {

        /** The bow direction as a vector. */
        public Point forward() {
            return new Point(Math.cos(headingRad), Math.sin(headingRad));
        }

        /** Ninety degrees off the bow - the direction the beam runs. */
        public Point starboard() {
            return new Point(-Math.sin(headingRad), Math.cos(headingRad));
        }

        /**
         * How far the hull reaches from its centre in a given direction.
         *
         * This is the whole trick. A rectangle's reach along any direction is
         * its half-length times how much the bow lines up with that direction,
         * plus its half-beam times how much the flank does. Side-on it gives
         * the beam, bow-on the length, and at an angle it gives the corner -
         * which is the number that matters, because the corner is what hits
         * the quay first.
         */
        public double reachAlong(Point direction) {
            Point unit = direction.unit();
            return halfLength * Math.abs(forward().dot(unit))
                    + halfBeam * Math.abs(starboard().dot(unit));
        }
    }

    /** The geometry of a quay edge - the line a ship ties up against. The named
     * facility a player draws is data.Wharf; this is the two points it reduces
     * to once the arithmetic starts. */
    public record QuayLine(Point from, Point to) {

        public Point direction() {
            return to.minus(from).unit();
        }

        /** Perpendicular to the quay, pointing off to one side of it. */
        public Point normal() {
            Point along = direction();
            return new Point(-along.z(), along.x());
        }

        public double length() {
            return to.minus(from).length();
        }

        /**
         * How far off the quay a point lies, and on which side.
         *
         * Signed deliberately: the sign says which side of the line the ship
         * is on, and a ship that has crossed to the wrong side is inside the
         * quay rather than alongside it. An unsigned distance cannot tell
         * those apart, and they are the difference between berthed and
         * embedded in the harbour wall.
         */
        public double signedDistanceTo(Point point) {
            return point.minus(from).dot(normal());
        }

        /**
         * How far along the quay a point lies, from 0 at one end to the
         * wharf's length at the other.
         *
         * Outside that range the ship is past the end of the quay - beside
         * open water rather than alongside stone - which is not berthed
         * however close it has come to the line.
         */
        public double distanceAlong(Point point) {
            return point.minus(from).dot(direction());
        }
    }

    /**
     * The gap between the hull's nearest edge and the quay.
     *
     * Negative means the hull has crossed the line and is overlapping the
     * quay, which is worth reporting as a number rather than clamping to
     * zero: a ship driving into a wall and a ship exactly touching one need
     * different responses from whatever is steering.
     */
    public static double edgeGap(Hull hull, QuayLine wharf) {
        double centreGap = Math.abs(wharf.signedDistanceTo(hull.centre()));
        return centreGap - hull.reachAlong(wharf.normal());
    }

    /**
     * Is the ship alongside this stretch of quay, rather than past its end?
     *
     * Measured against the hull's reach along the quay as well as its centre,
     * so a long ship counts as alongside when any part of it is - which is
     * what somebody looking at it would say.
     */
    public static boolean isAlongside(Hull hull, QuayLine wharf) {
        double along = wharf.distanceAlong(hull.centre());
        double reach = hull.reachAlong(wharf.direction());
        return along + reach >= 0 && along - reach <= wharf.length();
    }

    /**
     * Berthed: alongside, on the water side, and within tolerance of the
     * wanted gap.
     *
     * The tolerance exists because a rigid body never stops exactly anywhere.
     * Without it a ship would creep past the target gap and back, hunting for
     * a number it cannot hit, and never declare itself berthed.
     */
    public static boolean isBerthed(Hull hull, QuayLine wharf, double wantedGap, double tolerance) {
        if (!isAlongside(hull, wharf)) return false;
        double gap = edgeGap(hull, wharf);
        if (gap < -tolerance) return false;            // through the quay
        return Math.abs(gap - wantedGap) <= tolerance;
    }

    /**
     * How far, and which way, the ship still has to move to sit at the wanted
     * gap - as a distance along the quay's normal.
     *
     * Positive means close in, negative means back off. Handing back one
     * signed number rather than a target position means the caller does not
     * have to work out which side of the quay it started on, which is the
     * step that gets inverted and drives ships into the harbour wall.
     */
    public static double approachOffset(Hull hull, QuayLine wharf, double wantedGap) {
        return edgeGap(hull, wharf) - wantedGap;
    }

    /**
     * The point a ship should aim its centre at to berth here.
     *
     * Offset from the quay by the hull's own reach plus the gap, on whichever
     * side the ship is currently on - a harbour has water on one side of its
     * quay and stone on the other, and which is which is not written down
     * anywhere. Where the ship actually is settles it.
     */
    public static Point berthCentre(Hull hull, QuayLine wharf, double wantedGap, double alongQuay) {
        double side = Math.signum(wharf.signedDistanceTo(hull.centre()));
        if (side == 0) side = 1;                       // dead on the line; pick one
        double standOff = hull.reachAlong(wharf.normal()) + wantedGap;

        Point along = wharf.direction();
        Point normal = wharf.normal();
        double clamped = Math.max(0, Math.min(wharf.length(), alongQuay));
        return new Point(
                wharf.from().x() + along.x() * clamped + normal.x() * standOff * side,
                wharf.from().z() + along.z() * clamped + normal.z() * standOff * side);
    }
}
