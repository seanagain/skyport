package com.seaport.block;

import com.seaport.blockentity.MarineAutopilotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Placed on a ship, and the thing that sails it.
 *
 * The counterpart to Skyport's Autopilot, and the block most of this mod will
 * eventually live behind: a schedule of wharves, a hull draft measured from
 * the vessel it is mounted on, a course across charted water, and the small
 * corrections that keep it off a sandbar and out of another ship.
 *
 * The block entity holds the orders; nothing sails them yet.
 */
public class MarineAutopilotBlock extends Block implements EntityBlock {

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

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarineAutopilotBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MarineAutopilotBlockEntity autopilot
                && player instanceof ServerPlayer serverPlayer) {
            autopilot.openScreen(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
}
