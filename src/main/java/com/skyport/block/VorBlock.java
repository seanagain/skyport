package com.skyport.block;

import com.skyport.blockentity.VorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A VOR beacon - a named point aircraft can be routed over on the way to an
 * airport. Right-clicking it names it (see VorScreen).
 *
 * Built on the Airport Station's pattern throughout, because it has the same
 * three problems: it has to be findable from anywhere once placed, it has to
 * stop being findable when broken, and a stranger breaking it would quietly
 * reroute somebody else's aircraft.
 */
public class VorBlock extends Block implements EntityBlock {

    public VorBlock(Properties properties) {
        super(properties);
    }

    /** Breaking it takes the VOR out of the registry. onRemove rather than
     *  the block entity's setRemoved, for the reason AirportStationBlock
     *  gives: setRemoved also fires when a chunk merely unloads. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof VorBlockEntity vor) {
            vor.unregister(serverLevel);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Registered on placement rather than on first use, so it can be picked
     *  on an Autopilot straight away without anybody opening it first. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(pos) instanceof VorBlockEntity vor)) return;
        vor.ensureRegistered(serverLevel);
        if (placer instanceof ServerPlayer player) {
            vor.skyportLock().claim(player.getUUID(), player.getGameProfile().getName());
            vor.setChanged();
            LockInteraction.announceClaim(player, "VOR Beacon");
        }
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof VorBlockEntity vor) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.isShiftKeyDown()) {
                    LockInteraction.administer(serverPlayer, vor, pos, "VOR Beacon");
                } else if (LockInteraction.mayOpen(serverPlayer, vor, pos, "VOR Beacon")) {
                    vor.openScreen(serverPlayer);
                }
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VorBlockEntity(pos, state);
    }
}
