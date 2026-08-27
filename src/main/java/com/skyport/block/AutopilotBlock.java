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
 * Known limitation: placement is unrestricted, so this can be put on any
 * block rather than only on an assembled craft. Convenient while drawing a
 * layout, wrong for a finished mod - Create Aeronautics' own control seat
 * and helm blocks are the precedent to follow when tightening it.
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

    /** Remember who put it down - see BlockLock. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) return;
        if (level.getBlockEntity(pos) instanceof AutopilotBlockEntity autopilot) {
            autopilot.skyportLock().claim(player.getUUID(), player.getGameProfile().getName());
            autopilot.setChanged();
            LockInteraction.announceClaim(player, "Autopilot");
        }
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AutopilotBlockEntity autopilot) {
            if (player instanceof ServerPlayer serverPlayer) {
                // Sneaking is how the owner reaches the lock itself; a plain
                // right-click is the ordinary way in, which the lock may
                // refuse or answer with a passcode box.
                if (serverPlayer.isShiftKeyDown()) {
                    LockInteraction.administer(serverPlayer, autopilot, pos, "Autopilot");
                } else if (LockInteraction.mayOpen(serverPlayer, autopilot, pos, "Autopilot")) {
                    autopilot.openDestinationPicker(serverPlayer);
                }
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

    /**
     * Breaking the block takes its aircraft off the tower's roster.
     *
     * Here rather than in the block entity's setRemoved, because setRemoved
     * also fires when a chunk merely unloads - and an aircraft whose chunks
     * unloaded is precisely the one the tower most needs to still know about.
     * Hooking there deleted the record a tick after the aircraft wrote it,
     * which is why sleeping aircraft never appeared on the map. The state
     * check is because onRemove also runs for a blockstate change on the same
     * block, and turning to face a different way is not being broken.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof AutopilotBlockEntity autopilot) {
            autopilot.unregister();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
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
