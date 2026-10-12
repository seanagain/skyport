package com.skyport.blockentity;

import com.skyport.data.AirportRegistry;
import com.skyport.data.BlockLock;
import com.skyport.data.Lockable;
import com.skyport.data.VorBeacon;
import com.skyport.network.OpenVorPayload;
import com.skyport.registry.ModBlockEntities;
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
 * Backs one VOR beacon. Holds only the beacon's id and its lock - the name
 * and position live in the AirportRegistry, which is where every schedule
 * that names this VOR looks them up, loaded or not.
 */
public class VorBlockEntity extends BlockEntity implements Lockable {

    private final BlockLock lock = new BlockLock();

    @Nullable
    private UUID vorId;

    public VorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOR.get(), pos, state);
    }

    @Override
    public BlockLock skyportLock() {
        return lock;
    }

    /**
     * This beacon's registry entry, created if it has none.
     *
     * Re-created under the same id rather than a fresh one when the entry has
     * gone missing, so schedules that already name this VOR keep working.
     * The position is rewritten if it disagrees with where the block actually
     * stands - the registry is a record of the block, never the other way
     * round.
     */
    public VorBeacon ensureRegistered(ServerLevel serverLevel) {
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        String dimension = serverLevel.dimension().location().toString();
        BlockPos pos = getBlockPos();

        VorBeacon existing = vorId == null ? null : registry.vorById(vorId).orElse(null);
        if (existing != null) {
            if (existing.pos().equals(pos) && existing.dimension().equals(dimension)) return existing;
            VorBeacon moved = new VorBeacon(existing.id(), existing.name(), dimension, pos);
            registry.putVor(moved);
            return moved;
        }

        VorBeacon created = new VorBeacon(vorId != null ? vorId : UUID.randomUUID(),
                VorBeacon.defaultName(pos), dimension, pos);
        registry.putVor(created);
        vorId = created.id();
        setChanged();
        return created;
    }

    public void openScreen(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        VorBeacon vor = ensureRegistered(serverLevel);
        PacketDistributor.sendToPlayer(player, new OpenVorPayload(getBlockPos(), vor.name()));
    }

    /**
     * Rename this VOR.
     *
     * @return the beacon as stored, with the name after cleaning - or null
     *         off the server, where there is no registry to write to
     */
    @Nullable
    public VorBeacon rename(String requested) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        VorBeacon vor = ensureRegistered(serverLevel);
        VorBeacon renamed = vor.withName(VorBeacon.cleanName(requested, vor.name()));
        AirportRegistry.get(serverLevel).putVor(renamed);
        return renamed;
    }

    /** Take this VOR out of the registry - its block has been broken. */
    public void unregister(ServerLevel serverLevel) {
        if (vorId == null) return;
        AirportRegistry.get(serverLevel).removeVor(vorId);
        vorId = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (vorId != null) tag.putUUID("vorId", vorId);
        lock.save(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("vorId")) vorId = tag.getUUID("vorId");
        lock.load(tag);
    }
}
