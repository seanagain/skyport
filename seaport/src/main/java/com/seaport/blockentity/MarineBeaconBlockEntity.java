package com.seaport.blockentity;

import com.seaport.data.Beacon;
import com.seaport.data.PortRegistry;
import com.seaport.network.OpenMarineBeaconPayload;
import com.seaport.registry.SeaPortBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Backs one channel marker.
 *
 * Holds only the mark's id. Its name and position live in the
 * {@link PortRegistry}, which is where every schedule naming this mark looks
 * them up - loaded or not. A ship three thousand blocks away has to be able
 * to find out where it is going without the destination's chunks being in
 * memory, and a block entity cannot answer anything while its chunk is
 * unloaded.
 */
public class MarineBeaconBlockEntity extends BlockEntity {

    @Nullable
    private UUID beaconId;

    public MarineBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(SeaPortBlockEntities.MARINE_BEACON.get(), pos, state);
    }

    /**
     * This mark's registry entry, created if it has none.
     *
     * Re-created under the SAME id rather than a fresh one when the entry has
     * gone missing, so schedules that already name this mark keep working.
     * The position is rewritten when it disagrees with where the block
     * actually stands: the registry is a record of the block, never the other
     * way round.
     */
    public Beacon ensureRegistered(ServerLevel level) {
        PortRegistry registry = PortRegistry.get(level);
        String dimension = level.dimension().location().toString();
        BlockPos pos = getBlockPos();

        Beacon existing = beaconId == null ? null : registry.beaconById(beaconId).orElse(null);
        if (existing != null) {
            if (existing.pos().equals(pos) && existing.dimension().equals(dimension)) return existing;
            Beacon moved = new Beacon(existing.id(), existing.name(), dimension, pos);
            registry.putBeacon(moved);
            return moved;
        }

        Beacon created = new Beacon(beaconId != null ? beaconId : UUID.randomUUID(),
                Beacon.defaultName(pos), dimension, pos);
        registry.putBeacon(created);
        beaconId = created.id();
        setChanged();
        return created;
    }

    public void openScreen(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Beacon beacon = ensureRegistered(serverLevel);
        PacketDistributor.sendToPlayer(player,
                new OpenMarineBeaconPayload(getBlockPos(), beacon.name()));
    }

    /**
     * Rename this mark.
     *
     * @return the mark as stored, with the name after cleaning - or null off
     *         the server, where there is no registry to write to
     */
    @Nullable
    public Beacon rename(String requested) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        Beacon beacon = ensureRegistered(serverLevel);
        Beacon renamed = beacon.withName(Beacon.cleanName(requested, beacon.name()));
        PortRegistry.get(serverLevel).putBeacon(renamed);
        return renamed;
    }

    /** Take this mark out of the directory - its block has been broken. */
    public void unregister(ServerLevel level) {
        if (beaconId == null) return;
        PortRegistry.get(level).removeBeacon(beaconId);
        beaconId = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (beaconId != null) tag.putUUID("beaconId", beaconId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("beaconId")) beaconId = tag.getUUID("beaconId");
    }
}
