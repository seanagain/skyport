package com.seaport.nav;

/**
 * Turning a column of world into a depth a ship can be measured against.
 *
 * All of it comes from one number per column - the height of the highest
 * non-fluid block, which Minecraft already keeps as the OCEAN_FLOOR heightmap
 * and already writes to disk. That matters more than it sounds: reading a
 * heightmap is a lookup, where reading the blocks themselves means having the
 * chunk in memory, and wanting chunks in memory is what hung a server in the
 * other half of this project.
 *
 * Depths are measured from sea level rather than from the surface the water
 * happens to have. A river at sea level and an ocean at sea level are the same
 * water as far as a hull is concerned, and a pond up a mountain is not
 * reachable from either, so there is nothing to be gained by tracking its own
 * surface height.
 *
 * Every depth here is in whole blocks, because every block is.
 */
public final class Soundings {

    private Soundings() { }

    /** A column with no water a ship could float in. Land, a dried bed, or a
     *  seabed standing above sea level. */
    public static final int DRY = 0;

    /**
     * How deep the water is over a floor at this height.
     *
     * @param floorHeight the top of the highest non-fluid block, as the
     *                    OCEAN_FLOOR heightmap reports it - the Y of the
     *                    first water block, so a floor at 50 with sea level
     *                    at 63 is thirteen blocks of water
     * @param seaLevel    the level's own, since a datapack may move it
     */
    public static int depth(int floorHeight, int seaLevel) {
        return Math.max(DRY, seaLevel - floorHeight);
    }

    /**
     * Can a hull drawing this much water sit here?
     *
     * Equal is not enough. A ship drawing three blocks in three blocks of
     * water is aground by any useful definition - it needs water under the
     * keel, not water exactly as deep as the keel - so the margin is part of
     * the question rather than something each caller remembers separately.
     */
    public static boolean floats(int depth, int draft, int margin) {
        return depth >= draft + margin;
    }

    /**
     * The shallowest of a set of soundings, which is the only one that
     * matters.
     *
     * A patch of water is as navigable as its worst point. Averaging would
     * turn a sandbar in otherwise deep water into a passable cell, and the
     * ship would find out by stopping on it.
     */
    public static int shallowest(int[] depths) {
        if (depths.length == 0) return DRY;
        int worst = Integer.MAX_VALUE;
        for (int depth : depths) worst = Math.min(worst, depth);
        return worst;
    }

    /**
     * How much water a hull draws, from the bounding box a craft reports.
     *
     * The waterline is sea level, so a hull reaching below it draws the
     * difference. A vessel sitting entirely above the water - on a slipway, or
     * a flying machine that happens to be over the sea - draws nothing, and
     * saying zero rather than a negative number keeps every comparison below
     * honest without a special case.
     *
     * @param hullBottomY the lowest point of the craft
     */
    public static int draftOf(double hullBottomY, int seaLevel) {
        return (int) Math.max(0, Math.ceil(seaLevel - hullBottomY));
    }
}
