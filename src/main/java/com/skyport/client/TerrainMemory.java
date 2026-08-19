package com.skyport.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

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
    /** Enough for a very large area at this grid; old entries are dropped
     *  wholesale rather than tracked individually, which is cruder than an
     *  LRU but costs nothing to maintain. */
    private static final int MAX_ENTRIES = 400_000;

    private static final Map<Long, Integer> REMEMBERED = new HashMap<>();
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
        if (level != null && level.hasChunkAt(new BlockPos(x, 0, z))) {
            int color = sample(level, x, z);
            if (color != 0) {
                if (REMEMBERED.size() >= MAX_ENTRIES) REMEMBERED.clear();
                REMEMBERED.put(k, color);
                return color;
            }
        }
        return REMEMBERED.getOrDefault(k, 0);
    }

    private static int sample(Level level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos surface = new BlockPos(x, surfaceY - 1, z);
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
