package com.portcore.survey;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Everything that has been surveyed, kept per dimension and kept on disk.
 *
 * Stored by CHUNK rather than by cell, which is not a storage detail: a chunk
 * is the unit everything else works in. Surveys arrive a chunk at a time,
 * invalidation happens a chunk at a time when somebody digs a canal, and the
 * sixteen cells of a chunk are always written together. Keying by cell would
 * have meant sixteen entries where one will do, and sixteen chances for a
 * chunk to end up half re-surveyed.
 *
 * Queried by cell, because that is the unit a ship or a map thinks in. The
 * arithmetic between the two is a shift.
 *
 * Per dimension on purpose. The Nether's seabed is not the Overworld's, and a
 * single shared chart would have them overwrite each other at the same
 * coordinates - which is exactly what happens when you walk through a portal
 * and the coordinates divide by eight.
 */
public class SurveyStore extends SavedData {

    private static final String DATA_NAME = "portcore_survey";

    public static final SavedData.Factory<SurveyStore> FACTORY =
            new SavedData.Factory<>(SurveyStore::new, SurveyStore::load, null);

    /** One chunk's worth: sixteen cells, and when they were taken. */
    private record Record(short[] floor, short[] surface, long surveyedAt) { }

    private final Map<Long, Record> chunks = new HashMap<>();

    /**
     * Per dimension rather than on the overworld, unlike most saved data in
     * these mods - see the note above about the same coordinates meaning
     * different seabeds in different dimensions.
     */
    public static SurveyStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void recordChunk(int chunkX, int chunkZ, CellSurvey[] cells, long gameTime) {
        if (cells.length != ChunkSummary.CELLS_PER_CHUNK) {
            throw new IllegalArgumentException("a chunk summarises to "
                    + ChunkSummary.CELLS_PER_CHUNK + " cells, got " + cells.length);
        }
        short[] floor = new short[cells.length];
        short[] surface = new short[cells.length];
        for (int i = 0; i < cells.length; i++) {
            // Shorts because world heights fit in one with room to spare, and
            // this is the array that gets multiplied by every chunk anyone has
            // ever visited.
            floor[i] = (short) cells[i].floor();
            surface[i] = (short) cells[i].surface();
        }
        chunks.put(RegionFiles.chunkKey(chunkX, chunkZ), new Record(floor, surface, gameTime));
        setDirty();
    }

    /** What is known about one cell, or {@link CellSurvey#NONE}. */
    public CellSurvey cell(int cellX, int cellZ) {
        // Four cells to a chunk along each axis; arithmetic shift so it
        // floors for negatives rather than folding -1 onto 0.
        Record record = chunks.get(RegionFiles.chunkKey(cellX >> 2, cellZ >> 2));
        if (record == null) return CellSurvey.NONE;

        int index = (cellZ & 3) * ChunkSummary.CELLS_PER_SIDE + (cellX & 3);
        return new CellSurvey(record.floor()[index], record.surface()[index]);
    }

    /** What is known about the cell a block coordinate falls in. */
    public CellSurvey at(int blockX, int blockZ) {
        return cell(ChunkSummary.toCell(blockX), ChunkSummary.toCell(blockZ));
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return chunks.containsKey(RegionFiles.chunkKey(chunkX, chunkZ));
    }

    public long surveyedAt(int chunkX, int chunkZ) {
        Record record = chunks.get(RegionFiles.chunkKey(chunkX, chunkZ));
        return record == null ? 0 : record.surveyedAt();
    }

    /**
     * Throw away what was known about a chunk.
     *
     * For when the world changed under it - a canal dug, a causeway built, a
     * bay drained. A chart that only ever gained entries would route ships
     * through a strait somebody filled in last week, and they would find out
     * by sailing into it.
     */
    public void forgetChunk(int chunkX, int chunkZ) {
        if (chunks.remove(RegionFiles.chunkKey(chunkX, chunkZ)) != null) setDirty();
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int cellCount() {
        return chunks.size() * ChunkSummary.CELLS_PER_CHUNK;
    }

    // ---- disk ------------------------------------------------------------

    /**
     * Written as four parallel arrays rather than a compound per chunk.
     *
     * A hundred thousand chunks is an ordinary number for a played-in world,
     * and a hundred thousand compound tags - each with its own keys spelled
     * out - is tens of megabytes of mostly field names. Flat arrays are the
     * same information without the spelling.
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        int count = chunks.size();
        long[] keys = new long[count];
        long[] times = new long[count];
        int[] floors = new int[count * ChunkSummary.CELLS_PER_CHUNK];
        int[] surfaces = new int[count * ChunkSummary.CELLS_PER_CHUNK];

        int i = 0;
        for (Map.Entry<Long, Record> entry : chunks.entrySet()) {
            keys[i] = entry.getKey();
            times[i] = entry.getValue().surveyedAt();
            for (int c = 0; c < ChunkSummary.CELLS_PER_CHUNK; c++) {
                floors[i * ChunkSummary.CELLS_PER_CHUNK + c] = entry.getValue().floor()[c];
                surfaces[i * ChunkSummary.CELLS_PER_CHUNK + c] = entry.getValue().surface()[c];
            }
            i++;
        }

        tag.putLongArray("chunks", keys);
        tag.putLongArray("surveyedAt", times);
        tag.putIntArray("floor", floors);
        tag.putIntArray("surface", surfaces);
        return tag;
    }

    public static SurveyStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SurveyStore store = new SurveyStore();
        long[] keys = tag.getLongArray("chunks");
        long[] times = tag.getLongArray("surveyedAt");
        int[] floors = tag.getIntArray("floor");
        int[] surfaces = tag.getIntArray("surface");

        // A file half-written by a crash, or from a version that stored this
        // differently, must come back as "nothing surveyed yet" rather than
        // throwing - the survey rebuilds itself, and refusing to load the
        // world over a cache would be a poor trade.
        int expected = keys.length * ChunkSummary.CELLS_PER_CHUNK;
        if (times.length != keys.length || floors.length != expected || surfaces.length != expected) {
            return store;
        }

        for (int i = 0; i < keys.length; i++) {
            short[] floor = new short[ChunkSummary.CELLS_PER_CHUNK];
            short[] surface = new short[ChunkSummary.CELLS_PER_CHUNK];
            for (int c = 0; c < ChunkSummary.CELLS_PER_CHUNK; c++) {
                floor[c] = (short) floors[i * ChunkSummary.CELLS_PER_CHUNK + c];
                surface[c] = (short) surfaces[i * ChunkSummary.CELLS_PER_CHUNK + c];
            }
            store.chunks.put(keys[i], new Record(floor, surface, times[i]));
        }
        return store;
    }
}
