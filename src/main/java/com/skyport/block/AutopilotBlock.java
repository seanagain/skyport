package com.skyport.block;

import com.skyport.blockentity.AutopilotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Placed on an assembled Create Aeronautics plane. Right-clicking it opens
 * the destination picker (airport + gate). Once engaged, the block
 * entity's flight state machine takes over steering the contraption - see
 * AutopilotBlockEntity for where that actually happens.
 *
 * TODO: this almost certainly needs to require being placed on a valid
 * Create Aeronautics contraption (not just anywhere) - look at how Create
 * Aeronautics' own control seat / helm blocks restrict placement, since
 * this block should follow the same rule.
 */
public class AutopilotBlock extends Block implements EntityBlock {

    /**
     * Which way the plane's nose points.
     *
     * A contraption is an arbitrary pile of blocks with no inherent "front",
     * so the autopilot cannot work out which way the craft faces on its own -
     * the player has to say. Placing this block pointing along the fuselage
     * is how they say it, and it's what the attitude controller rotates the
     * craft to align with its direction of travel.
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public AutopilotBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Points the way the player is looking, so "face it toward the nose"
        // is literal - rather than the usual face-the-player convention,
        // which would mean placing it backwards.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AutopilotBlockEntity autopilot) {
            if (player instanceof ServerPlayer serverPlayer) {
                autopilot.openDestinationPicker(serverPlayer);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Redstone runs the autopilot: powered engages the saved schedule,
     * unpowered disengages.
     *
     * Level-triggered rather than edge-triggered on purpose - a lever left on
     * means "this plane should be flying", which survives a reload, whereas a
     * pulse that happened while the chunk was out would just be missed.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block block, BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;
        if (!(level.getBlockEntity(pos) instanceof AutopilotBlockEntity autopilot)) return;

        if (level.hasNeighborSignal(pos)) {
            autopilot.engageFromRedstone(serverLevel);
        } else {
            autopilot.disengage();
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutopilotBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : (lvl, p, st, be) -> {
            if (be instanceof AutopilotBlockEntity autopilot) autopilot.serverTick();
        };
    }
}
