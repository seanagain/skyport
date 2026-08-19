package com.skyport.block;

import com.skyport.blockentity.AtcBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The control tower's desk: right-click to see every registered airport and
 * every aircraft currently under autopilot on one map.
 *
 * Read-only on purpose. It answers "where is everything" - which is the
 * question you actually have once more than one plane is flying - without
 * becoming a second place to edit routes; that stays on the Autopilot.
 */
public class AtcBlock extends Block implements EntityBlock {

    public AtcBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AtcBlockEntity atc) {
            if (player instanceof ServerPlayer serverPlayer) {
                atc.openScreen(serverPlayer);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AtcBlockEntity(pos, state);
    }
}
