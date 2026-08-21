package com.skyport.client;

import com.skyport.SkyportConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;


/**
 * Remembers terrain colours the client has seen, so a map can still draw
 * ground that has since unloaded.
 *
 * Without this an ATC map is nearly blank: it can only sample chunks the
 * client currently has loaded, which is a small patch around the player, so
 * an airport across the world shows as empty backdrop even though you flew
 * over it a minute ago. Sampling is cheap but the *opportunity* to sample is
 * rare, so anything seen once is worth keeping.
 *
 * Client-side and session-scoped: nothing is written to disk, so it starts
 * empty each launch and fills in as you travel. Keyed on a coarse grid
 * rather than per block, since it backs a zoomed-out map where a few blocks
 * of precision would be invisible anyway - and that keeps the map bounded.
 */
public final class TerrainMemory {

    /** World blocks per remembered sample. */
    private static final int GRID = 4;

    /**
     * Primitive long -> int rather than HashMap&lt;Long, Integer&gt;.
     *
     * The boxed version costs roughly 60 bytes per entry once the Long, the
     * Integer and the map node are counted - tens of megabytes at the sizes
     * this can reach on a long session. fastutil ships with Minecraft and
     * stores the same data in flat arrays, an order of magnitude smaller,
     * for a map that is written constantly and never iterated.
     */
    private static final Long2IntOpenHashMap REMEMBERED = new Long2IntOpenHashMap();

    static {
        REMEMBERED.defaultReturnValue(0);
    }
    /** The world these colours came from, so they can be dropped on leaving
     *  it - coordinates from one save mean nothing in the next. */
    @Nullable
    private static Level rememberedFor;

    private TerrainMemory() { }

    public static void clear() {
        REMEMBERED.clear();
        rememberedFor = null;
    }

    private static void forgetIfWorldChanged(@Nullable Level level) {
        if (level != rememberedFor) {
            REMEMBERED.clear();
            rememberedFor = level;
        }
    }

    private static long key(int x, int z) {
        return (((long) Math.floorDiv(x, GRID)) << 32) ^ (Math.floorDiv(z, GRID) & 0xFFFFFFFFL);
    }

    /**
     * The colour at this column: freshly sampled if the chunk is loaded,
     * otherwise whatever was last seen there, or 0 for never-visited ground.
     */
    public static int colorAt(@Nullable Level level, int x, int z) {
        forgetIfWorldChanged(level);
        long k = key(x, z);
        // hasChunk(chunkX, chunkZ) rather than hasChunkAt(BlockPos): this runs
        // thousands of times per map refresh and the BlockPos was pure garbage.
        if (level != null && level.hasChunk(x >> 4, z >> 4)) {
            int color = sample(level, x, z);
            if (color != 0) {
                int limit = SkyportConfig.terrainMemoryLimit;
                if (limit <= 0) return color; // remembering disabled
                if (REMEMBERED.size() >= limit) REMEMBERED.clear();
                REMEMBERED.put(k, color);
                return color;
            }
        }
        return REMEMBERED.get(k);
    }

    /** Reused across samples - client-side and single-threaded, so one
     *  mutable position beats allocating thousands. */
    private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

    private static int sample(Level level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos surface = SCRATCH.set(x, surfaceY - 1, z);
        MapColor mapColor = level.getBlockState(surface).getMapColor(level, surface);
        if (mapColor == MapColor.NONE) return 0;

        int northY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1);
        MapColor.Brightness brightness = switch (Integer.compare(surfaceY, northY)) {
            case 1 -> MapColor.Brightness.HIGH;
            case -1 -> MapColor.Brightness.LOW;
            default -> MapColor.Brightness.NORMAL;
        };
        // MapColor packs ABGR; GuiGraphics wants ARGB - see AirportMapScreen.
        int abgr = mapColor.calculateRGBColor(brightness);
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
