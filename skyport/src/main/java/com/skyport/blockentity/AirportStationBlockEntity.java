package com.skyport.blockentity;

import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.network.OpenAirportMapPayload;
import com.skyport.registry.ModBlockEntities;
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
 * Backs one Airport Station block. Owns exactly one {@link AirportLayout}
 * (created the first time the station is used) and is responsible for
 * keeping it registered in the world's {@link AirportRegistry} so the
 * autopilot GUI can find it from anywhere.
 */
public class AirportStationBlockEntity extends BlockEntity implements com.skyport.data.Lockable {

    /** Circuit height above the station, in blocks. High enough to clear
     *  terrain and read as a pattern, low enough that the descent onto
     *  final is a glide rather than a dive. */
    private static final int DEFAULT_PATTERN_HEIGHT = 80;

    private final com.skyport.data.BlockLock lock = new com.skyport.data.BlockLock();

    @Override
    public com.skyport.data.BlockLock skyportLock() {
        return lock;
    }

    @org.jetbrains.annotations.Nullable
    private UUID airportId;

    public AirportStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIRPORT_STATION.get(), pos, state);
    }

    public void openMapEditor(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        AirportLayout layout = getOrCreateLayout(serverLevel);
        PacketDistributor.sendToPlayer(player, new OpenAirportMapPayload(getBlockPos(), layout));
    }

    /** Which airport this station owns, if its layout has been saved. */
    @org.jetbrains.annotations.Nullable
    public UUID airportId() {
        return airportId;
    }

    /**
     * Take this airport out of the world registry - the station block that
     * defined it has been broken.
     *
     * Deliberately called from the block's onRemove rather than the block
     * entity's setRemoved: setRemoved also fires when a chunk simply
     * unloads, so hooking there would quietly delete every airport whose
     * chunk went out of range.
     */
    public void unregister(ServerLevel serverLevel) {
        if (airportId == null) return;
        AirportRegistry.get(serverLevel).remove(airportId);
        airportId = null;
    }

    public void saveLayout(AirportLayout updated) {
        // Stamp where this station stands, so a layout whose station has
        // been removed by any means can be recognised as a ghost.
        updated.setStationPos(getBlockPos());
        if (!(level instanceof ServerLevel serverLevel)) return;
        AirportRegistry.get(serverLevel).put(updated);
        this.airportId = updated.id();
        setChanged();
    }

    private AirportLayout getOrCreateLayout(ServerLevel serverLevel) {
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        if (airportId != null) {
            var existing = registry.byId(airportId);
            if (existing.isPresent()) return existing.get();
        }

        AirportLayout layout = new AirportLayout(UUID.randomUUID(), "New Airport", serverLevel.dimension());
        layout.setStationPos(getBlockPos());
        // Holding altitude is an absolute Y, and its default used to be an
        // absolute 100 - which quietly assumed the airport was near sea
        // level. On a superflat world sitting at y=-51 that put the pattern
        // 150 blocks above the field, so arrivals climbed away from an
        // airport they were trying to land at. Anchoring it to the station
        // gives the same 80-block circuit wherever the ground happens to be,
        // and the map editor's Hold Y control still overrides it.
        layout.setHoldingPatternHeight(Math.min(320, getBlockPos().getY() + DEFAULT_PATTERN_HEIGHT));
        registry.put(layout);
        airportId = layout.id();
        setChanged();
        return layout;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (airportId != null) tag.putUUID("airportId", airportId);
        lock.save(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("airportId")) airportId = tag.getUUID("airportId");
        lock.load(tag);
    }
}
