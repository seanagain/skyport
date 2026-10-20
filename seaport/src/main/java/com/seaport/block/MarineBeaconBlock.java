package com.seaport.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A named point a ship can be routed through - a channel marker.
 *
 * The same idea as Skyport's VOR, and a more load-bearing one here. An
 * aircraft can ignore a beacon and fly straight; a ship cannot, because the
 * straight line may be an island. A beacon is how a player tells the mod
 * which way a strait runs, for the stretches where charting alone will not
 * settle it.
 *
 * Inert for now.
 */
public class MarineBeaconBlock extends Block {

    public MarineBeaconBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
