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

    /**
     * Bytes of header at the front of a region file: 1024 four-byte entries,
     * one per chunk slot, saying where in the file that chunk lives.
     */
    public static final int HEADER_BYTES = 4096;

    /**
     * Which of a region's 1024 slots actually hold a chunk.
     *
     * A region file covers a 32 by 32 block of chunk slots, and almost never
     * fills it - a world generated around a spawn point might have fifty
     * chunks in a file with room for a thousand. Queueing every slot and
     * finding out by reading means a thousand disk accesses to discover
     * fifty, and on a world with a few hundred region files that is hundreds
     * of thousands of reads that were never going to return anything.
     *
     * The file says so in its first four kilobytes. Each slot has a four-byte
     * entry - three bytes of offset, one of length - and an entry of zero
     * means nothing was ever written there. Reading that header is one small
     * read per region instead of a thousand per region.
     *
     * @param header the first {@value #HEADER_BYTES} bytes of the file; a
     *               shorter array is read as far as it goes, because a
     *               truncated region file should yield the chunks it can
     *               rather than none
     * @return slot indices, where index = localX + localZ * 32
     */
    public static int[] presentSlots(byte[] header) {
        int slots = Math.min(1024, header.length / 4);
        int[] found = new int[slots];
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            int at = slot * 4;
            // Three bytes of offset. Any non-zero means something is there;
            // the actual position does not matter to us, only that it exists.
            int offset = ((header[at] & 0xFF) << 16)
                    | ((header[at + 1] & 0xFF) << 8)
                    | (header[at + 2] & 0xFF);
            int sectors = header[at + 3] & 0xFF;
            if (offset != 0 && sectors != 0) found[count++] = slot;
        }
        return java.util.Arrays.copyOf(found, count);
    }

    /** The chunk a slot index refers to, within the region it came from. */
    public static int slotChunkX(Region region, int slot) {
        return region.firstChunkX() + (slot % CHUNKS_PER_SIDE);
    }

    public static int slotChunkZ(Region region, int slot) {
        return region.firstChunkZ() + (slot / CHUNKS_PER_SIDE);
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
