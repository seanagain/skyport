package com.seaport.blockentity;

import com.seaport.data.PortLayout;
import com.seaport.data.PortRegistry;
import com.seaport.network.OpenPortStationPayload;
import com.seaport.registry.SeaPortBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Backs one Port Station, and owns exactly one port.
 *
 * The station is the port: break the block and the harbour stops existing as
 * a destination. That is deliberate and it is Skyport's arrangement too - a
 * registry entry with no block behind it is somewhere ships can be sent and
 * never arrive, and those are miserable to diagnose because nothing is
 * obviously broken.
 *
 * The layout lives in the world's registry rather than in this block entity's
 * own tag, so a ship halfway across an ocean can look up where it is going
 * without the destination's chunk being loaded. What the block keeps is the
 * id, which is the thread back to it.
 */
public class PortStationBlockEntity extends BlockEntity {

    private UUID portId;

    public PortStationBlockEntity(BlockPos pos, BlockState state) {
        super(SeaPortBlockEntities.PORT_STATION.get(), pos, state);
    }

    public UUID portId() {
        return portId;
    }

    /**
     * This block's port, created the first time anybody asks.
     *
     * Made on demand rather than when the block is placed, so a station
     * somebody put down and never opened does not clutter the directory with
     * an unnamed harbour nothing can be routed to.
     */
    public PortLayout getOrCreate(ServerLevel level) {
        PortRegistry registry = PortRegistry.get(level);
        if (portId != null) {
            PortLayout existing = registry.byId(portId).orElse(null);
            if (existing != null) {
                // The block may have been moved, or the world reloaded with
                // the station somewhere new. The registry's copy is the one
                // ships read, so keep its idea of where to come.
                existing.setStationPos(getBlockPos());
                return existing;
            }
        }

        PortLayout layout = new PortLayout(UUID.randomUUID(), "New Port", level.dimension());
        layout.setStationPos(getBlockPos());
        registry.put(layout);
        portId = layout.id();
        setChanged();
        return layout;
    }

    public void saveLayout(ServerLevel level, PortLayout updated) {
        PortRegistry.get(level).put(updated);
        portId = updated.id();
        setChanged();
    }

    /** Break the block, lose the port - see the note above about
     *  destinations with nothing behind them. */
    public void unregister(ServerLevel level) {
        if (portId != null) PortRegistry.get(level).remove(portId);
    }

    public void openEditor(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayer(player,
                new OpenPortStationPayload(getBlockPos(), getOrCreate(level)));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (portId != null) tag.putUUID("portId", portId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("portId")) portId = tag.getUUID("portId");
    }
}
