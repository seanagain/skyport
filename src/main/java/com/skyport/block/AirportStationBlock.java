package com.skyport.block;

import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.registry.ModBlockEntities;
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
 * Placed near a runway. Right-clicking it opens the map editor where the
 * player draws the runway, taxiways, gates, and holding pattern for that
 * airport (see AirportMapScreen - client-side GUI, not written yet).
 */
public class AirportStationBlock extends Block implements EntityBlock {

    public AirportStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AirportStationBlockEntity station) {
            // TODO: open the map editor. Concretely this should send a
            // custom S2C payload containing the station's current
            // AirportLayout (or "none yet" if this is a brand new
            // station) so the client can open AirportMapScreen populated
            // with whatever's already been drawn.
            if (player instanceof ServerPlayer serverPlayer) {
                station.openMapEditor(serverPlayer);
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
