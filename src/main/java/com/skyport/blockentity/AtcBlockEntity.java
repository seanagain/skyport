package com.skyport.blockentity;

import com.skyport.SkyportConfig;
import com.skyport.data.AirportRegistry;
import com.skyport.data.AirportLayout;
import com.skyport.data.TrafficReport;
import com.skyport.network.OpenAtcPayload;
import com.skyport.registry.ModBlockEntities;
import com.skyport.world.FleetWake;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Backs the ATC block. Holds no state of its own - everything it shows lives
 * in {@link AirportRegistry}, so this just takes a snapshot on request and
 * sends it to whoever opened the screen.
 */
public class AtcBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

    public AtcBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ATC.get(), pos, state);
    }

    /**
     * The craft this tower is riding on, when it is riding on one.
     *
     * Set from Sable's actor tick rather than looked up, because there is no
     * lookup: a block entity has no idea it is inside a sub-level. This is
     * the callback that tells it.
     */
    private transient ServerSubLevel activeSubLevel;

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        this.activeSubLevel = subLevel;
    }

    /**
     * Where this block actually is in the world.
     *
     * On the ground these are the same thing and this is a no-op. Mounted on
     * an aircraft they are not: getBlockPos() returns coordinates inside the
     * craft's own sub-level, which are local to the contraption and have
     * nothing to do with where it is flying. Sent to the screen unconverted,
     * the tower would mark itself somewhere near the sub-level origin and
     * drag the map's auto-fit out to include it - so opening the tower in
     * flight would show a map zoomed out to nothing, with the "you are here"
     * cross in an empty quarter of the world.
     *
     * The sub-level's pose is the transform from those local coordinates to
     * world ones, which is the same conversion the autopilot uses to know
     * where its aircraft is.
     */
    public BlockPos worldPosition() {
        if (activeSubLevel == null || activeSubLevel.isRemoved()) return getBlockPos();
        return BlockPos.containing(
                activeSubLevel.logicalPose().transformPosition(getBlockPos().getCenter()));
    }

    /**
     * Forget airports whose station block is no longer there.
     *
     * Breaking a station unregisters its airport, but that only covers
     * removals that go through the block's own callbacks. WorldEdit and
     * friends write blocks straight into the world, so a station can vanish
     * without the mod ever hearing about it - and the airport would linger
     * on the map and in every destination list forever. The tower is the
     * natural place to notice.
     *
     * Only checks stations in loaded chunks: an airport whose chunk is simply
     * out of range is perfectly real, and deleting it would be the same
     * mistake as hooking chunk unload.
     */
    public static void pruneGhostAirports(ServerLevel serverLevel, AirportRegistry registry) {
        for (AirportLayout airport : List.copyOf(registry.all())) {
            BlockPos station = airport.stationPos();
            if (station.equals(BlockPos.ZERO)) continue; // predates position stamping
            if (!serverLevel.isLoaded(station)) continue;
            if (!(serverLevel.getBlockEntity(station) instanceof AirportStationBlockEntity)) {
                registry.remove(airport.id());
            }
        }
    }

    /**
     * Which tower each player currently has open.
     *
     * The refresh packet has to report where the tower is, and it cannot
     * work that out from coordinates: a tower on an aircraft lives in a
     * sub-level, so looking up a block entity by world position finds
     * nothing. Remembering which block answered the open is the only route
     * back to it. Nothing here is persisted - a screen open across a
     * restart is not a thing.
     */
    private static final java.util.Map<java.util.UUID, AtcBlockEntity> OPEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Where the tower this player has open currently is, or null if they
     *  have none - the caller falls back to leaving the marker where it was. */
    public static BlockPos openTowerPosition(ServerPlayer player) {
        AtcBlockEntity tower = OPEN.get(player.getUUID());
        if (tower == null || tower.isRemoved()) {
            OPEN.remove(player.getUUID());
            return null;
        }
        return tower.worldPosition();
    }

    public void openScreen(ServerPlayer player) {
        OPEN.put(player.getUUID(), this);
        ServerLevel serverLevel = player.serverLevel();
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        pruneGhostAirports(serverLevel, registry);

        // Opening the tower can wake the whole fleet, but no longer does by
        // default - see SkyportConfig#WAKE_ON_ATC_OPEN. The screen lists
        // sleeping aircraft and waking one is a click on its name, which is
        // both cheaper and almost always the aircraft you actually meant.
        // Blanket waking on every visit held a patch of world open around
        // every aircraft on the server, and announced it in chat each time.
        if (SkyportConfig.wakeOnAtcOpen) {
            int woken = FleetWake.wakeAll(serverLevel.getServer());
            if (woken > 0 && SkyportConfig.chatMessages) {
                player.sendSystemMessage(Component.literal("[Skyport] Woke " + woken
                        + " sleeping aircraft for " + SkyportConfig.fleetWakeMinutes + " minutes."));
            }
        }

        List<AirportLayout> airports = List.copyOf(registry.all());
        List<TrafficReport> traffic = List.copyOf(registry.allTraffic(serverLevel.getGameTime()));

        PacketDistributor.sendToPlayer(player, new OpenAtcPayload(worldPosition(), airports, List.copyOf(registry.allVors()), traffic));
    }
}
