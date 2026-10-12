package com.seaport.nav;

import java.util.HashMap;
import java.util.Map;

/**
 * What is known about the water, cell by cell.
 *
 * A chart rather than a map, and the word is doing work: it records what has
 * been surveyed, and the absence of a cell means nobody has looked rather than
 * nothing is there. Those are completely different answers to a ship asking
 * whether it can go somewhere, and a structure that cannot tell them apart
 * will cheerfully route a freighter through an island it has never seen.
 *
 * Cells are {@value #CELL_BLOCKS} blocks square - sixteen to a chunk. A whole
 * chunk would have been simpler and is too blunt: one rock in the middle of
 * otherwise open water would mark the entire chunk impassable for a deep hull
 * and close a lane that is plainly there. Finer than this and the memory stops
 * being free for no navigational gain, since nothing steers that precisely at
 * ocean scale anyway.
 *
 * Each cell holds the SHALLOWEST sounding in it, because a patch of water is
 * only as navigable as its worst point - see Soundings#shallowest.
 *
 * Nothing here touches the world. It is handed soundings by whatever surveyed
 * them and answers questions about what it was told, which is what makes the
 * routing on top of it testable without a server.
 */
public final class NavChart {

    /** Blocks to a side. Sixteen cells to a chunk, so a chunk's heightmap
     *  summarises into sixteen numbers. */
    public static final int CELL_BLOCKS = 4;

    /** No survey has reached this cell. Distinct from DRY on purpose. */
    public static final int UNCHARTED = -1;

    private final Map<Long, Integer> depths = new HashMap<>();
    /** When each cell was surveyed, so stale water can be looked at again.
     *  Keyed the same way; absent for cells nobody has charted. */
    private final Map<Long, Long> surveyed = new HashMap<>();

    public static int toCell(int blockCoordinate) {
        // Arithmetic shift, so it floors for negative coordinates too -
        // block -1 belongs to cell -1, not cell 0, and getting that wrong
        // puts a seam through the chart at the world origin.
        return blockCoordinate >> 2;
    }

    public static int toBlock(int cell) {
        return cell * CELL_BLOCKS;
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    /** @return the shallowest depth in this cell, {@link #UNCHARTED} if
     *          nobody has surveyed it, or {@link Soundings#DRY} for land */
    public int depth(int cellX, int cellZ) {
        return depths.getOrDefault(key(cellX, cellZ), UNCHARTED);
    }

    public boolean isCharted(int cellX, int cellZ) {
        return depths.containsKey(key(cellX, cellZ));
    }

    /** When this cell was last surveyed, or 0 if never - so a caller can
     *  decide a cell is old enough to be worth looking at again. */
    public long surveyedAt(int cellX, int cellZ) {
        return surveyed.getOrDefault(key(cellX, cellZ), 0L);
    }

    public void record(int cellX, int cellZ, int depth, long gameTime) {
        long k = key(cellX, cellZ);
        depths.put(k, Math.max(Soundings.DRY, depth));
        surveyed.put(k, gameTime);
    }

    /**
     * Forget a cell, so the next ship to want it surveys it again.
     *
     * Players dig canals, build causeways and drain bays. A chart that only
     * ever gained entries would send ships through a strait somebody filled in
     * last week, and the ship would discover it by running into it.
     */
    public void forget(int cellX, int cellZ) {
        long k = key(cellX, cellZ);
        depths.remove(k);
        surveyed.remove(k);
    }

    /** Whether a hull of this draft can be there at all - which needs the
     *  cell to have been surveyed first, because unknown is not the same as
     *  deep. */
    public boolean passable(int cellX, int cellZ, int draft, int margin) {
        int depth = depth(cellX, cellZ);
        if (depth == UNCHARTED) return false;
        return Soundings.floats(depth, draft, margin);
    }

    public int chartedCells() {
        return depths.size();
    }

    /** Everything charted, for persistence. The caller writes it; this
     *  class has no opinion about where. */
    public Map<Long, Integer> snapshot() {
        return Map.copyOf(depths);
    }
}
