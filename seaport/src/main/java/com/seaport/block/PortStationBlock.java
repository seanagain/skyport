package com.seaport.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Defines one port: its name, its wharves, and the settings that govern how
 * ships berth there.
 *
 * Skyport's Airport Station draws a whole airfield - runways, taxiways,
 * holding patterns, gates. A port needs far less of that, because the sea is
 * already a surface ships can cross: what has to be drawn is where the quay
 * edge runs, and everything else is open water and collision avoidance.
 *
 * Inert for now. The block exists so the set can be placed and looked at
 * before any of that is built.
 */
public class PortStationBlock extends Block {

    public PortStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        // The model is a console that stops short of the block's top, so the
        // game must light around its actual shape rather than treat it as a
        // solid cube - see the note in SeaPortBlocks about noOcclusion.
        return true;
    }
}
