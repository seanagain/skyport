package com.skyport.block;

import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.registry.ModBlockEntities;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Placed near a runway. Right-clicking it opens the station screen, where
 * the airport is named and its layout drawn (see AirportStationScreen and
 * AirportMapScreen).
 *
 * Breaking one deletes its airport - see onRemove below, and note that this
 * deliberately is not the block entity's setRemoved.
 */
public class AirportStationBlock extends Block implements EntityBlock {

    public AirportStationBlock(Properties properties) {
        super(properties);
    }

    /**
     * Breaking the station deletes its airport.
     *
     * The registry is world-level and survives the chunk, which is what lets
     * an autopilot anywhere pick this airport as a destination - so nothing
     * else would ever clean it up, and a broken station would leave a ghost
     * airport on the ATC map and in every destination list forever.
     *
     * This is the block's onRemove rather than the block entity's
     * setRemoved, and the distinction matters: setRemoved also fires when a
     * chunk merely unloads, so hooking there would delete airports whenever
     * a player walked away from one. The state check is for the same reason -
     * onRemove also runs for a blockstate change on the same block.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof AirportStationBlockEntity station) {
            station.unregister(serverLevel);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Remember who put it down - see BlockLock. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) return;
        if (level.getBlockEntity(pos) instanceof AirportStationBlockEntity station) {
            station.skyportLock().claim(player.getUUID(), player.getGameProfile().getName());
            station.setChanged();
            LockInteraction.announceClaim(player, "Airport Station");
        }
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AirportStationBlockEntity station) {
            if (player instanceof ServerPlayer serverPlayer) {
                // Sneaking is how the owner reaches the lock itself; a plain
                // right-click is the ordinary way in, which the lock may
                // refuse or answer with a passcode box.
                if (serverPlayer.isShiftKeyDown()) {
                    LockInteraction.administer(serverPlayer, station, pos, "Airport Station");
                } else if (LockInteraction.mayOpen(serverPlayer, station, pos, "Airport Station")) {
                    station.openMapEditor(serverPlayer);
                }
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirportStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // No per-tick behaviour needed - this block only reacts to
        // right-clicks and to layout-save packets from the map screen.
        return null;
    }
}
