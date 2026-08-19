package com.skyport.block;

import com.skyport.blockentity.AutopilotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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

    public AutopilotBlock(Properties properties) {
        super(properties);
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
