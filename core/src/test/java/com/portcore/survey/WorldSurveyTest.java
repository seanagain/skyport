package com.portcore.survey;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading heightmaps out of the bytes a chunk was saved as.
 *
 * This is the one piece of the survey that can be wrong without looking
 * wrong. Minecraft packs 256 heights into longs at nine bits each, refusing
 * to let an entry straddle a long, so seven fit per long and five bits go
 * spare at the top of each - and the heights are stored relative to the
 * world's floor rather than as absolute Y. Get the stride wrong, or forget to
 * add the floor back, and the seabed comes out plausible: deep where it
 * should be shallow, by an amount that reads like terrain. Ships would be
 * routed onto it and nobody would suspect the reader.
 *
 * So these tests pack heights the way Minecraft does and insist on getting
 * exactly them back.
 */
class WorldSurveyTest {

    private static final int BITS = 9;
    private static final int PER_LONG = 7;

    /** Packs heights the way Minecraft writes them: relative to minY, nine
     *  bits each, seven to a long, never straddling. */
    private static long[] pack(int[] heights, int minY) {
        long[] packed = new long[(256 + PER_LONG - 1) / PER_LONG];
        for (int index = 0; index < heights.length; index++) {
            long value = heights[index] - minY;
            packed[index / PER_LONG] |= (value & ((1L << BITS) - 1)) << ((index % PER_LONG) * BITS);
        }
        return packed;
    }

    private static CompoundTag chunkWith(int[] floor, int[] surface, int minY) {
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("OCEAN_FLOOR", pack(floor, minY));
        heightmaps.putLongArray("WORLD_SURFACE", pack(surface, minY));

        CompoundTag chunk = new CompoundTag();
        chunk.put("Heightmaps", heightmaps);
        chunk.putString("Status", "minecraft:full");
        return chunk;
    }

    private static int[] flat(int height) {
        int[] out = new int[256];
        java.util.Arrays.fill(out, height);
        return out;
    }

    @Test
    void heightsSurviveBeingReadBackOutOfPackedLongs() {
        CellSurvey[] cells = WorldSurvey.fromNbt(chunkWith(flat(50), flat(63), -64), -64);
        assertNotNull(cells);
        assertEquals(16, cells.length);
        for (CellSurvey cell : cells) {
            assertEquals(50, cell.floor());
            assertEquals(63, cell.surface());
        }
    }

    /**
     * The failure that would look like terrain. Heights are stored relative
     * to the world floor, so a world with a negative build limit - every
     * world since 1.18 - reads sixty-four blocks too high if the floor is not
     * added back, and a seabed sixty-four blocks too high is dry land.
     */
    @Test
    void heightsAreRelativeToTheWorldFloorNotToZero() {
        CellSurvey[] atDepth = WorldSurvey.fromNbt(chunkWith(flat(20), flat(63), -64), -64);
        assertEquals(20, atDepth[0].floor(), "forgetting minY would make this 84 and call it land");

        // A world whose floor is zero stores the same height differently, and
        // has to read back the same.
        CellSurvey[] oldStyle = WorldSurvey.fromNbt(chunkWith(flat(20), flat(63), 0), 0);
        assertEquals(20, oldStyle[0].floor());
    }

    /**
     * Every index has to land on its own nine bits. A stride that ignores the
     * five spare bits at the top of each long drifts: the first few entries
     * are right and everything after is somebody else's height.
     */
    @Test
    void everyColumnIsReadFromItsOwnBits() {
        int[] floor = flat(40);
        // Pick columns either side of a long boundary - index 6 is the last
        // in its long, 7 the first of the next - since that is exactly where
        // a wrong stride starts lying.
        floor[6] = 55;
        floor[7] = 61;
        floor[13] = 48;
        floor[14] = 33;

        CellSurvey[] cells = WorldSurvey.fromNbt(chunkWith(floor, flat(63), -64), -64);

        // Indices 6 and 7 are x=6,z=0 and x=7,z=0, both in cell (1,0).
        assertEquals(61, cells[1].floor(), "the taller of the two in that cell");
        // 13 and 14 are x=13,z=0 and x=14,z=0, in cells (3,0).
        assertEquals(48, cells[3].floor());
    }

    @Test
    void theShallowestColumnInACellIsTheOneThatCounts() {
        int[] floor = flat(20);
        floor[0] = 61;                                   // x=0, z=0 -> cell 0
        CellSurvey[] cells = WorldSurvey.fromNbt(chunkWith(floor, flat(63), -64), -64);
        assertEquals(61, cells[0].floor());
        assertEquals(20, cells[1].floor());
    }

    // ---- things that are not a chunk -------------------------------------

    @Test
    void nbtWithNoHeightmapsIsRefusedRatherThanGuessedAt() {
        assertNull(WorldSurvey.fromNbt(new CompoundTag(), -64));
        assertNull(WorldSurvey.fromNbt(null, -64));
    }

    /** An older or truncated heightmap must not be read as if it were a
     *  current one - a half-length array would produce heights for columns
     *  it does not describe. */
    @Test
    void aTruncatedHeightmapIsRefused() {
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("OCEAN_FLOOR", new long[4]);
        heightmaps.putLongArray("WORLD_SURFACE", new long[4]);
        CompoundTag chunk = new CompoundTag();
        chunk.put("Heightmaps", heightmaps);

        assertNull(WorldSurvey.fromNbt(chunk, -64));
    }

    /**
     * A chunk saved part-way through generation describes a world about to
     * change. Surveying one would record a seabed that is wrong by the time
     * anybody sails it, and it costs nothing to wait - something has to load
     * that chunk to finish it, and the survey happens then.
     */
    @Test
    void onlyFinishedChunksAreWorthSurveying() {
        CompoundTag full = new CompoundTag();
        full.putString("Status", "minecraft:full");
        assertTrue(WorldSurvey.isFullyGenerated(full));

        CompoundTag unnamespaced = new CompoundTag();
        unnamespaced.putString("Status", "full");
        assertTrue(WorldSurvey.isFullyGenerated(unnamespaced), "older saves wrote it without a namespace");

        CompoundTag partial = new CompoundTag();
        partial.putString("Status", "minecraft:features");
        assertFalse(WorldSurvey.isFullyGenerated(partial));

        assertFalse(WorldSurvey.isFullyGenerated(new CompoundTag()), "no status at all");
        assertFalse(WorldSurvey.isFullyGenerated(null));
    }
}
