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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Backs the ATC block. Holds no state of its own - everything it shows lives
 * in {@link AirportRegistry}, so this just takes a snapshot on request and
 * sends it to whoever opened the screen.
 */
public class AtcBlockEntity extends BlockEntity {

    public AtcBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ATC.get(), pos, state);
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

    public void openScreen(ServerPlayer player) {
        ServerLevel serverLevel = player.serverLevel();
        AirportRegistry registry = AirportRegistry.get(serverLevel);
        pruneGhostAirports(serverLevel, registry);

        // Opening the tower wakes the fleet. Aircraft parked where nobody is
        // standing have released their chunks and stopped ticking, so their
        // schedules are stalled; checking on them is exactly the moment you'd
        // want them running. See FleetWake - it lapses on its own.
        int woken = SkyportConfig.wakeOnAtcOpen ? FleetWake.wakeAll(serverLevel.getServer()) : 0;
        if (woken > 0 && SkyportConfig.chatMessages) {
            player.sendSystemMessage(Component.literal("[Skyport] Woke " + woken
                    + " parked aircraft for " + SkyportConfig.fleetWakeMinutes + " minutes."));
        }

        List<AirportLayout> airports = List.copyOf(registry.all());
        List<TrafficReport> traffic = List.copyOf(registry.allTraffic());

        PacketDistributor.sendToPlayer(player, new OpenAtcPayload(getBlockPos(), airports, traffic));
    }
}
