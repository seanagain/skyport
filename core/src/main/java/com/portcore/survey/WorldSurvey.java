package com.portcore.survey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Getting the two heightmaps out of a chunk, from memory or from its bytes.
 *
 * Two routes in, and the difference between them is the whole reason this
 * class exists.
 *
 * A LOADED chunk is free: the heightmaps are already built and in memory, so
 * reading one costs an array lookup and the chunk was going to be there
 * anyway.
 *
 * An UNLOADED chunk is a file. Minecraft writes the heightmaps into the chunk
 * NBT as packed long arrays, so the bytes on disk already contain the answer
 * and nothing has to be generated, ticked or kept resident to get at it. That
 * matters enormously: the alternative - asking the world for a chunk that is
 * not loaded - generates terrain, and generating terrain in bulk is what hung
 * a server in the other half of this project.
 *
 * The unpacking is the fiddly part. Minecraft stores 256 heights in a long
 * array at nine bits each, without letting an entry straddle a long, so there
 * are seven entries to a long and five bits wasted at the top of each. Get
 * that wrong and the seabed comes out plausible and incorrect, which is worse
 * than coming out broken.
 */
public final class WorldSurvey {

    private WorldSurvey() { }

    /** Heights per long, at nine bits each, not straddling. */
    private static final int HEIGHTS_PER_LONG = 7;
    private static final int BITS_PER_HEIGHT = 9;
    private static final long HEIGHT_MASK = (1L << BITS_PER_HEIGHT) - 1;

    /**
     * Read a chunk that is already in memory.
     *
     * @return sixteen cells, or null if the chunk has not had its heightmaps
     *         built - a chunk part-way through generation has none, and a
     *         half-built answer is worse than none
     */
    @Nullable
    public static CellSurvey[] fromLoaded(ChunkAccess chunk) {
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR)
                || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) {
            return null;
        }
        int[] floor = new int[256];
        int[] surface = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                floor[index] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                surface[index] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
        return ChunkSummary.summarise(floor, surface);
    }

    /**
     * Read a chunk from the NBT that was on disk.
     *
     * Pure data in, pure data out, so it never touches the world and is safe
     * to run off the server thread - which is the point, since the alternative
     * is doing file I/O where the game is trying to tick.
     *
     * @return sixteen cells, or null if this NBT has no usable heightmaps -
     *         an older chunk format, a chunk saved before generation
     *         finished, or something that is not a chunk at all
     */
    @Nullable
    public static CellSurvey[] fromNbt(@Nullable CompoundTag chunkNbt, int minY) {
        if (chunkNbt == null) return null;
        CompoundTag heightmaps = chunkNbt.getCompound("Heightmaps");

        int[] floor = unpack(heightmaps, "OCEAN_FLOOR", minY);
        int[] surface = unpack(heightmaps, "WORLD_SURFACE", minY);
        if (floor == null || surface == null) return null;

        return ChunkSummary.summarise(floor, surface);
    }

    /**
     * Unpack one heightmap.
     *
     * The stored values are heights above the world's floor rather than
     * absolute Y, so a world with a negative build limit - which every world
     * has had since 1.18 - reads about sixty blocks too high without adding
     * minY back. The failure mode is a seabed that looks reasonable, so it
     * would survive a glance and strand ships.
     */
    @Nullable
    private static int[] unpack(CompoundTag heightmaps, String name, int minY) {
        if (!heightmaps.contains(name, Tag.TAG_LONG_ARRAY)) return null;
        long[] packed = heightmaps.getLongArray(name);
        // 256 entries at seven to a long.
        if (packed.length < 37) return null;

        int[] out = new int[256];
        for (int index = 0; index < 256; index++) {
            int word = index / HEIGHTS_PER_LONG;
            int offset = (index % HEIGHTS_PER_LONG) * BITS_PER_HEIGHT;
            out[index] = (int) ((packed[word] >>> offset) & HEIGHT_MASK) + minY;
        }
        return out;
    }

    /**
     * Whether this NBT describes a chunk that finished generating.
     *
     * A chunk saved mid-generation has heightmaps that describe a world half
     * carved, and surveying one would record a seabed that is about to change.
     * Cheaper to skip it: it will be surveyed properly when it finishes, and
     * something has to load it for that to happen anyway.
     */
    public static boolean isFullyGenerated(@Nullable CompoundTag chunkNbt) {
        if (chunkNbt == null) return false;
        String status = chunkNbt.getString("Status");
        // Namespaced since 1.18; accept both spellings rather than guessing
        // which a given save was written by.
        return status.equals("minecraft:full") || status.equals("full");
    }

    /** Unused today, kept because the sections are where a future survey
     *  would read block materials for a coloured map. */
    static int sectionCount(@Nullable CompoundTag chunkNbt) {
        if (chunkNbt == null) return 0;
        ListTag sections = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        return sections.size();
    }
}
