package com.skyport.data;

/**
 * What kind of aircraft an autopilot is flying, which decides the whole
 * shape of its route.
 *
 * A plane needs a runway, so it taxis, rolls, climbs out along the runway
 * heading, joins a pattern and flies an approach. Rotorcraft and airships
 * need none of that: they go up from where they're standing, across, and
 * down onto the pad at the other end. Rather than teaching the plane's state
 * machine to skip half of itself, the two get separate routes through it.
 */
public enum CraftType {
    /** Runway-bound: taxi, takeoff roll, pattern, final approach. */
    PLANE("Plane"),
    /** Vertical up-across-down, nose tipped forward in the cruise the way a
     *  helicopter has to tilt its rotor disc to go anywhere. */
    HELICOPTER("Heli"),
    /** As the helicopter, but flies level - an airship gets its lift from
     *  buoyancy, so it has no reason to tip. */
    BLIMP("Blimp");

    private final String label;

    CraftType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True for the craft that use helipads and skip runways entirely. */
    public boolean isVertical() {
        return this != PLANE;
    }

    public CraftType next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
