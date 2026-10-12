package com.portcore.survey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a world into something a ship or a map can read, without reading
 * the world.
 *
 * Everything here is the arithmetic and the bookkeeping - the half that would
 * quietly produce a wrong seabed or an unbounded queue, and the half that can
 * be checked without a server. Getting the heights is elsewhere, because that
 * part is threads and disk and budgets rather than decisions.
 *
 * The recurring theme is that this runs on somebody's existing world. It will
 * be switched on against a server that has been played for a year, with
 * hundreds of thousands of generated chunks, and the first thing it does must
 * not be the thing that makes them uninstall it.
 */
class SurveyTest {

    private static final int SEA_LEVEL = 63;

    private static int[] flat(int height) {
        int[] out = new int[256];
        java.util.Arrays.fill(out, height);
        return out;
    }

    // ---- a cell -----------------------------------------------------------

    @Test
    void waterIsTheGapBetweenTheFloorAndSeaLevel() {
        assertEquals(13, new CellSurvey(50, 63).waterDepth(SEA_LEVEL));
        assertEquals(0, new CellSurvey(70, 75).waterDepth(SEA_LEVEL), "a hill holds no water");
    }

    @Test
    void groundAtOrAboveSeaLevelIsLand() {
        assertTrue(new CellSurvey(63, 63).isLand(SEA_LEVEL));
        assertFalse(new CellSurvey(62, 63).isLand(SEA_LEVEL), "one block of water is still water");
    }

    /** Unsurveyed is not shallow, not land, and not zero depth - it is
     *  nothing, and every reader has to be able to tell. */
    @Test
    void anUnsurveyedCellKnowsThatItIsUnsurveyed() {
        assertFalse(CellSurvey.NONE.known());
        assertFalse(CellSurvey.NONE.isLand(SEA_LEVEL));
        assertEquals(0, CellSurvey.NONE.waterDepth(SEA_LEVEL));
    }

    // ---- summarising a chunk ---------------------------------------------

    @Test
    void aChunkBecomesSixteenCells() {
        CellSurvey[] cells = ChunkSummary.summarise(flat(50), flat(63));
        assertEquals(16, cells.length);
        for (CellSurvey cell : cells) {
            assertEquals(50, cell.floor());
            assertEquals(63, cell.surface());
        }
    }

    /**
     * The decision the whole chart rests on. A cell takes its HIGHEST floor,
     * not its average - a sandbar in otherwise deep water has to show up as a
     * sandbar, because a ship that averages it finds out by stopping on it.
     */
    @Test
    void aCellTakesItsShallowestPointNotItsAverage() {
        int[] floor = flat(20);
        floor[0] = 60;                                  // one pinnacle, cell (0,0)

        CellSurvey[] cells = ChunkSummary.summarise(floor, flat(63));
        assertEquals(60, cells[0].floor(), "the cell is as shallow as its worst block");
        assertEquals(20, cells[1].floor(), "and its neighbour is unaffected");
    }

    @Test
    void cellsCoverTheChunkWithoutOverlapOrGap() {
        int[] floor = flat(20);
        // One block in the far corner of the chunk: x=15, z=15 -> cell (3,3).
        floor[15 * 16 + 15] = 55;

        CellSurvey[] cells = ChunkSummary.summarise(floor, flat(63));
        assertEquals(55, cells[3 * ChunkSummary.CELLS_PER_SIDE + 3].floor());
        for (int i = 0; i < cells.length; i++) {
            if (i == 15) continue;
            assertEquals(20, cells[i].floor(), "cell " + i + " should not have seen it");
        }
    }

    @Test
    void chunkLocalCellsMapOntoWorldCells() {
        // Chunk (2, -3), its cell index 0, is the chunk's north-west corner.
        assertEquals(8, ChunkSummary.cellX(2, 0));
        assertEquals(-12, ChunkSummary.cellZ(-3, 0));
        // Index 15 is the south-east corner, three cells along each way.
        assertEquals(11, ChunkSummary.cellX(2, 15));
        assertEquals(-9, ChunkSummary.cellZ(-3, 15));
    }

    /** Block -1 belongs to cell -1. Rounding toward zero would make it share
     *  a cell with block 0 and put a seam through every chart at the origin. */
    @Test
    void cellsTileTheWorldWithoutASeamAtTheOrigin() {
        assertEquals(0, ChunkSummary.toCell(0));
        assertEquals(0, ChunkSummary.toCell(3));
        assertEquals(1, ChunkSummary.toCell(4));
        assertEquals(-1, ChunkSummary.toCell(-1));
        assertEquals(-2, ChunkSummary.toCell(-5));
    }

    @Test
    void aHeightmapThatIsNotAChunkIsRefusedRatherThanSummarised() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkSummary.summarise(new int[12], new int[12]));
    }

    // ---- region files ----------------------------------------------------

    @Test
    void aRegionFileNameSaysWhichChunksItHolds() {
        RegionFiles.Region region = RegionFiles.parse("r.1.-2.mca");
        assertEquals(1, region.x());
        assertEquals(-2, region.z());
        assertEquals(32, region.firstChunkX());
        assertEquals(-64, region.firstChunkZ());
        assertEquals(63, region.lastChunkX());
        assertEquals(-33, region.lastChunkZ());
        assertEquals(1024, region.chunkCount());
    }

    /**
     * The directory holds other things - an ancient .mcr, a .tmp left by a
     * crash, somebody's backup. Every one has to come back null rather than
     * throwing, because one odd file must not stop a server surveying.
     */
    @Test
    void anythingThatIsNotARegionFileIsIgnoredRatherThanThrown() {
        assertNull(RegionFiles.parse("r.0.0.mcr"), "an older format");
        assertNull(RegionFiles.parse("r.0.0.mca.tmp"), "a half-written write");
        assertNull(RegionFiles.parse("r.x.0.mca"), "not a number");
        assertNull(RegionFiles.parse("readme.txt"));
        assertNull(RegionFiles.parse("r.0.mca"), "too few parts");
        assertNull(RegionFiles.parse(null));
    }

    @Test
    void chunksMapBackToTheRegionHoldingThem() {
        assertEquals(new RegionFiles.Region(0, 0), RegionFiles.containing(0, 0));
        assertEquals(new RegionFiles.Region(0, 0), RegionFiles.containing(31, 31));
        assertEquals(new RegionFiles.Region(1, 1), RegionFiles.containing(32, 32));
        assertEquals(new RegionFiles.Region(-1, -1), RegionFiles.containing(-1, -1),
                "chunk -1 is in region -1, not region 0");
    }

    @Test
    void chunkCoordinatesSurviveBeingPackedIntoALong() {
        for (int[] pair : new int[][] { {0, 0}, {5, -7}, {-100000, 100000}, {-1, -1} }) {
            long key = RegionFiles.chunkKey(pair[0], pair[1]);
            assertEquals(pair[0], RegionFiles.chunkX(key));
            assertEquals(pair[1], RegionFiles.chunkZ(key));
        }
    }

    // ---- the backlog -----------------------------------------------------

    @Test
    void theSameChunkIsNotQueuedTwice() {
        SurveyBacklog backlog = new SurveyBacklog();
        assertTrue(backlog.offer(1));
        assertFalse(backlog.offer(1), "it arrives from several directions and is one answer");
        assertEquals(1, backlog.size());
    }

    @Test
    void workIsTakenInOrderAndStopsBeingQueued() {
        SurveyBacklog backlog = new SurveyBacklog();
        backlog.offer(1);
        backlog.offer(2);
        backlog.offer(3);

        assertEquals(List.of(1L, 2L), backlog.take(2));
        assertFalse(backlog.isWaiting(1));
        assertEquals(1, backlog.size());
    }

    /**
     * A player sailing into unsurveyed water must not wait behind a region
     * file from last year - and without this they would, because the backlog
     * drains in order and the order is a year old.
     */
    @Test
    void urgentWorkJumpsTheQueue() {
        SurveyBacklog backlog = new SurveyBacklog();
        backlog.offer(1);
        backlog.offer(2);
        backlog.offerUrgent(99);

        assertEquals(List.of(99L), backlog.take(1));
    }

    @Test
    void workAlreadyWaitingIsPromotedRatherThanDuplicated() {
        SurveyBacklog backlog = new SurveyBacklog();
        backlog.offer(1);
        backlog.offer(2);
        backlog.offerUrgent(2);

        assertEquals(2, backlog.size(), "promoted, not added again");
        assertEquals(List.of(2L, 1L), backlog.take(2));
    }

    /** An unbounded queue fed by region enumeration is a memory leak with a
     *  respectable name. Dropping is safe: the work comes back the moment
     *  anything asks about that chunk again. */
    @Test
    void theBacklogIsBoundedAndSaysWhatItTurnedAway() {
        SurveyBacklog backlog = new SurveyBacklog(2);
        assertTrue(backlog.offer(1));
        assertTrue(backlog.offer(2));
        assertFalse(backlog.offer(3), "full");

        assertEquals(2, backlog.size());
        assertEquals(1, backlog.droppedCount());
    }

    /** Urgent work is worth more than the oldest ordinary work, so a full
     *  backlog makes room rather than refusing the chunk somebody is in. */
    @Test
    void aFullBacklogStillAcceptsUrgentWork() {
        SurveyBacklog backlog = new SurveyBacklog(2);
        backlog.offer(1);
        backlog.offer(2);
        backlog.offerUrgent(99);

        assertEquals(2, backlog.size());
        assertEquals(List.of(99L, 1L), backlog.take(2), "the oldest ordinary work was evicted");
    }

    @Test
    void takingFromAnEmptyBacklogIsNothingRatherThanAFailure() {
        SurveyBacklog backlog = new SurveyBacklog();
        assertTrue(backlog.take(10).isEmpty());
        assertTrue(backlog.isEmpty());
        assertTrue(backlog.take(0).isEmpty());
    }

    /** Surveying the places people are before the far corner of a world
     *  somebody visited once. */
    // ---- which slots a region actually holds -----------------------------

    /** Four bytes a slot: three of offset, one of length. All zero means
     *  nothing was ever written there. */
    private static byte[] headerWith(int... presentSlots) {
        byte[] header = new byte[RegionFiles.HEADER_BYTES];
        for (int slot : presentSlots) {
            header[slot * 4 + 2] = 2;      // a non-zero offset
            header[slot * 4 + 3] = 1;      // one sector long
        }
        return header;
    }

    /**
     * The measurement that prompted this.
     *
     * A region file covers a thousand chunk slots and a world generated
     * around a spawn point fills about fifty of them. Queueing every slot
     * meant four thousand disk reads to find forty-nine chunks, with every
     * one of the rest returning nothing - and the statistics called them
     * "skipped", which made four thousand futile reads look deliberate.
     */
    @Test
    void onlySlotsHoldingAChunkAreReported() {
        assertArrayEquals(new int[] { 0, 5, 1023 },
                RegionFiles.presentSlots(headerWith(0, 5, 1023)));
    }

    @Test
    void anEmptyRegionHoldsNothing() {
        assertEquals(0, RegionFiles.presentSlots(new byte[RegionFiles.HEADER_BYTES]).length);
    }

    /** A half-written header yields the chunks it can rather than none: one
     *  truncated file must not cost a server all the others. */
    @Test
    void aTruncatedHeaderYieldsWhatItCan() {
        byte[] truncated = java.util.Arrays.copyOf(headerWith(0, 5, 900), 100 * 4);
        assertArrayEquals(new int[] { 0, 5 }, RegionFiles.presentSlots(truncated));
    }

    /** An offset with no length is not a chunk, and treating it as one would
     *  queue a read that cannot return anything. */
    @Test
    void anEntryWithNoLengthIsNotAChunk() {
        byte[] header = new byte[RegionFiles.HEADER_BYTES];
        header[2] = 2;
        assertEquals(0, RegionFiles.presentSlots(header).length);
    }

    @Test
    void slotsMapBackOntoWorldChunks() {
        RegionFiles.Region region = new RegionFiles.Region(1, -1);
        assertEquals(32, RegionFiles.slotChunkX(region, 0));
        assertEquals(-32, RegionFiles.slotChunkZ(region, 0));
        // Slot 33 is one east and one south of the corner.
        assertEquals(33, RegionFiles.slotChunkX(region, 33));
        assertEquals(-31, RegionFiles.slotChunkZ(region, 33));
    }

    @Test
    void regionsKnowHowFarTheyAreFromSomewhere() {
        RegionFiles.Region near = new RegionFiles.Region(0, 0);
        RegionFiles.Region far = new RegionFiles.Region(10, 10);
        assertTrue(near.chunkDistanceTo(16, 16) < far.chunkDistanceTo(16, 16));
    }
}
