package com.portcore.survey;

/**
 * Reducing a chunk's heightmaps to the handful of numbers worth keeping.
 *
 * A chunk's heightmap is 256 heights. Storing that for every chunk anyone has
 * ever walked through would be hundreds of megabytes for a well-travelled
 * world, and nothing needs that resolution - a ship does not steer to the
 * block at ocean scale, and a map drawn at four blocks to the pixel cannot
 * show it.
 *
 * So each chunk becomes {@value #CELLS_PER_SIDE} by {@value #CELLS_PER_SIDE}
 * cells of {@value #CELL_BLOCKS} blocks, sixteen numbers instead of two
 * hundred and fifty-six. A whole chunk as one number would be smaller still
 * and is too blunt: one rock in open water would close a lane that is plainly
 * there, and a map would lose every river narrower than sixteen blocks.
 *
 * Pure arithmetic on arrays, deliberately. Everything about getting the
 * heights - loaded chunk, disk read, budget, threads - lives elsewhere, and
 * this half is the one that would quietly produce a wrong seabed, so it is
 * the half worth being able to test without a server.
 */
public final class ChunkSummary {

    private ChunkSummary() { }

    public static final int CELL_BLOCKS = 4;
    public static final int CELLS_PER_SIDE = 16 / CELL_BLOCKS;
    public static final int CELLS_PER_CHUNK = CELLS_PER_SIDE * CELLS_PER_SIDE;

    /**
     * Summarise one chunk.
     *
     * @param floor   256 heights, indexed {@code z * 16 + x} as Minecraft's
     *                own heightmaps are - the highest non-fluid block
     * @param surface the same, for the highest block of any kind
     * @return {@value #CELLS_PER_CHUNK} cells, indexed
     *         {@code cellZ * CELLS_PER_SIDE + cellX}
     */
    public static CellSurvey[] summarise(int[] floor, int[] surface) {
        if (floor.length != 256 || surface.length != 256) {
            throw new IllegalArgumentException(
                    "a chunk heightmap is 256 heights, got " + floor.length + " and " + surface.length);
        }

        CellSurvey[] cells = new CellSurvey[CELLS_PER_CHUNK];
        for (int cellZ = 0; cellZ < CELLS_PER_SIDE; cellZ++) {
            for (int cellX = 0; cellX < CELLS_PER_SIDE; cellX++) {
                int highestFloor = Integer.MIN_VALUE;
                int highestSurface = Integer.MIN_VALUE;

                for (int dz = 0; dz < CELL_BLOCKS; dz++) {
                    for (int dx = 0; dx < CELL_BLOCKS; dx++) {
                        int index = (cellZ * CELL_BLOCKS + dz) * 16 + (cellX * CELL_BLOCKS + dx);
                        highestFloor = Math.max(highestFloor, floor[index]);
                        highestSurface = Math.max(highestSurface, surface[index]);
                    }
                }
                cells[cellZ * CELLS_PER_SIDE + cellX] = new CellSurvey(highestFloor, highestSurface);
            }
        }
        return cells;
    }

    /** The world-space cell coordinate a chunk-local cell index belongs to. */
    public static int cellX(int chunkX, int index) {
        return chunkX * CELLS_PER_SIDE + (index % CELLS_PER_SIDE);
    }

    public static int cellZ(int chunkZ, int index) {
        return chunkZ * CELLS_PER_SIDE + (index / CELLS_PER_SIDE);
    }

    /**
     * Which cell a block coordinate falls in.
     *
     * An arithmetic shift, so it floors for negatives too. Rounding toward
     * zero instead would make block -1 and block 0 share a cell and put a
     * seam through every chart along the world origin.
     */
    public static int toCell(int blockCoordinate) {
        return blockCoordinate >> 2;
    }

    public static int cellToBlock(int cell) {
        return cell * CELL_BLOCKS;
    }
}
