package com.seaport.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * Placed on a ship, and the thing that sails it.
 *
 * The counterpart to Skyport's Autopilot, and the block most of this mod will
 * eventually live behind: a schedule of wharves, a hull draft measured from
 * the vessel it is mounted on, a course across charted water, and the small
 * corrections that keep it off a sandbar and out of another ship.
 *
 * Inert for now, apart from which way it points.
 */
public class MarineAutopilotBlock extends Block {

    /**
     * Which way the bow is.
     *
     * A ship assembled out of blocks has no inherent front, so the autopilot
     * cannot work out which end is the bow on its own - the player says so by
     * placing this pointing along the hull. Everything about berthing is
     * measured off that: which part of the vessel is nearest the quay, how
     * far the edge reaches along the line, which way to swing to come
     * alongside. Skyport's Autopilot carries the same property for the same
     * reason, and this block's model and blockstate were mirrored from it.
     *
     * Leaving it off was not a cosmetic omission. The blockstate file came
     * across from Skyport declaring facing=north/east/south/west, but the
     * block class did not, so this block had exactly one state - the empty
     * one - and the empty key is not in that file. Nothing matched, no model
     * was ever resolved, and the block rendered as the missing-model
     * checkerboard. Three rounds of fixing the TEXTURE could not have helped:
     * the texture was never reached.
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MarineAutopilotBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Points the way the player is looking, so "face it toward the bow"
        // is literal - rather than the usual face-the-player convention,
        // which would mean placing it stern-first.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
