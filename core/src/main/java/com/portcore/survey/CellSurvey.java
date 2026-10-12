package com.portcore.survey;

/**
 * What a survey learned about one cell of the world.
 *
 * Two heights, both taken from heightmaps Minecraft already keeps and already
 * writes to disk, so a survey costs a lookup rather than a chunk in memory.
 *
 * {@code floor} is the highest non-fluid block - the seabed under water, the
 * ground on land. {@code surface} is the highest block of any kind, so over
 * water it is the waterline and on land it is the treetops. The gap between
 * them is water, or canopy, and which one it is depends on where the sea
 * level sits.
 *
 * Both are the HIGHEST in the cell rather than an average, and that is a
 * navigational decision rather than a storage one: a cell is only as deep as
 * its shallowest point, and averaging would hide a sandbar inside the deep
 * water around it. For drawing a map the same number reads as relief, which
 * is close enough to be worth one survey instead of two.
 */
public record CellSurvey(int floor, int surface) {

    /** Nothing has been surveyed here. */
    public static final CellSurvey NONE = new CellSurvey(Integer.MIN_VALUE, Integer.MIN_VALUE);

    public boolean known() {
        return floor != Integer.MIN_VALUE;
    }

    /**
     * How much water stands over this cell at the given sea level, or zero
     * for ground at or above it.
     *
     * Here rather than in the marine mod because it is arithmetic on two
     * numbers this record already holds, and both readers want it - one to
     * decide whether a hull fits, the other to decide whether to draw it blue.
     */
    public int waterDepth(int seaLevel) {
        if (!known()) return 0;
        return Math.max(0, seaLevel - floor);
    }

    /** Standing above the water, whatever is on top of it. */
    public boolean isLand(int seaLevel) {
        return known() && floor >= seaLevel;
    }
}
