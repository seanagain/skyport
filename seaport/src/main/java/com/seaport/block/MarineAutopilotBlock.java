package com.seaport.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Placed on a ship, and the thing that sails it.
 *
 * The counterpart to Skyport's Autopilot, and the block most of this mod will
 * eventually live behind: a schedule of wharves, a hull draft measured from
 * the vessel it is mounted on, a course across charted water, and the small
 * corrections that keep it off a sandbar and out of another ship.
 *
 * Inert for now.
 */
public class MarineAutopilotBlock extends Block {

    public MarineAutopilotBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
