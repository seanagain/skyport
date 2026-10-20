package com.seaport.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The harbour's radar: every vessel under way, wherever it is.
 *
 * Skyport's equivalent is the ATC block, and the job is the same one - a
 * roster of craft, where they are, and what they are waiting for. Shipping
 * differs mainly in that a vessel out of sight is still somewhere specific,
 * which is what the chart is for.
 *
 * Inert for now.
 */
public class MarineRadarBlock extends Block {

    public MarineRadarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
