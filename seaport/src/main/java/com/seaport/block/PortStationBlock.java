package com.seaport.block;

import com.seaport.blockentity.PortStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * Defines one port: its name, its berths, and how close ships tie up.
 *
 * Skyport's Airport Station draws a whole airfield, because an aeroplane
 * cannot use ground nobody described. A harbour sits on water, which is
 * navigable by default and already charted, so the only thing this has to be
 * told is where its stone ends - which is what a berth is.
 */
public class PortStationBlock extends Block implements EntityBlock {

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

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PortStationBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof PortStationBlockEntity station
                && player instanceof ServerPlayer serverPlayer) {
            station.openEditor(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Breaking the station removes the port.
     *
     * A harbour left in the directory with no block behind it is a
     * destination ships can still be routed to and never reach, and those are
     * miserable to diagnose because nothing looks broken - the route is
     * valid, the port exists, and the vessel simply never arrives. Skyport
     * had to grow a pruner for exactly this; better not to create them.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof PortStationBlockEntity station) {
            station.unregister(serverLevel);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        // Nothing to tick yet. The station is a record of a place rather than
        // something that acts; it is the autopilot that will need a pulse.
        return null;
    }

}
