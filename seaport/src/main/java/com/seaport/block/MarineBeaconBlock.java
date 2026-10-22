package com.seaport.block;

import com.seaport.blockentity.MarineBeaconBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * A named point a ship can be routed through - a channel marker.
 *
 * The same idea as Skyport's VOR, and a more load-bearing one here. An
 * aircraft can ignore a beacon and fly straight; a ship cannot, because the
 * straight line may be an island. A beacon is how a player says which way a
 * strait runs, for the stretches where charting alone will not settle it.
 */
public class MarineBeaconBlock extends Block implements EntityBlock {

    public MarineBeaconBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarineBeaconBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MarineBeaconBlockEntity beacon
                && player instanceof ServerPlayer serverPlayer) {
            beacon.openScreen(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Breaking the mark removes it from the directory.
     *
     * A schedule still naming it then routes straight through to the next
     * stop, which is what the router would have done had the mark never been
     * dropped. That is a better failure than leaving an entry pointing at
     * open water nobody marked, which reads as a real instruction and is not.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof MarineBeaconBlockEntity beacon) {
            beacon.unregister(serverLevel);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
