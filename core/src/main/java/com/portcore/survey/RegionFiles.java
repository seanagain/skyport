package com.portcore.survey;

import org.jetbrains.annotations.Nullable;

/**
 * Reading the shape of an explored world off the names of its region files.
 *
 * Installed on a server that has been running for a year, the first question
 * is which chunks exist at all. Asking the world is hopeless - the coordinate
 * space is effectively infinite, and probing it would mean millions of misses
 * to find the thousands of hits.
 *
 * The answer is already on disk, in the file names. Minecraft stores chunks in
 * region files called {@code r.X.Z.mca}, each holding a 32 by 32 block of
 * chunks, and it only writes one when something in it was generated. So a
 * directory listing IS the map of what has ever been explored: cheap,
 * complete, and it costs nothing but a readdir.
 *
 * This class is only the arithmetic and the parsing. Listing the directory
 * belongs to the surveyor, which is also where the decision not to touch any
 * of it on the main thread lives.
 */
public final class RegionFiles {

    private RegionFiles() { }

    /** Chunks along one side of a region file. */
    public static final int CHUNKS_PER_SIDE = 32;

    /** Where a region sits, in regions rather than chunks or blocks. */
    public record Region(int x, int z) {

        public int firstChunkX() {
            return x * CHUNKS_PER_SIDE;
        }

        public int firstChunkZ() {
            return z * CHUNKS_PER_SIDE;
        }

        public int lastChunkX() {
            return firstChunkX() + CHUNKS_PER_SIDE - 1;
        }

        public int lastChunkZ() {
            return firstChunkZ() + CHUNKS_PER_SIDE - 1;
        }

        public int chunkCount() {
            return CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
        }

        /** Distance in chunks from this region's centre to a point, for
         *  surveying the places people actually are before the far corners
         *  of a world somebody visited once. */
        public double chunkDistanceTo(int chunkX, int chunkZ) {
            double centreX = firstChunkX() + CHUNKS_PER_SIDE / 2.0;
            double centreZ = firstChunkZ() + CHUNKS_PER_SIDE / 2.0;
            double dx = centreX - chunkX;
            double dz = centreZ - chunkZ;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /**
     * The region a file name describes, or null if it is not one.
     *
     * Anvil names are {@code r.X.Z.mca} with signed, variable-length
     * coordinates. The directory can hold other things - {@code .mcr} from an
     * ancient world, a {@code .tmp} half-written by a crash, somebody's
     * backup - and every one of those has to come back null rather than
     * throwing, because a single odd file must not stop a server surveying.
     */
    @Nullable
    public static Region parse(String fileName) {
        if (fileName == null) return null;
        String[] parts = fileName.split("\\.");
        if (parts.length != 4) return null;
        if (!parts[0].equals("r") || !parts[3].equals("mca")) return null;
        try {
            return new Region(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException notARegion) {
            return null;
        }
    }

    /** Which region a chunk lives in. Arithmetic shift, so it floors for
     *  negative coordinates - chunk -1 is in region -1, not region 0. */
    public static Region containing(int chunkX, int chunkZ) {
        return new Region(chunkX >> 5, chunkZ >> 5);
    }

    /** Packs a chunk coordinate into one long, which is how the backlog
     *  queues work without knowing what a chunk is. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
