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

    public void openScreen(ServerPlayer player) {
        ServerLevel serverLevel = player.serverLevel();
        AirportRegistry registry = AirportRegistry.get(serverLevel);

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
        List<TrafficReport> traffic = List.copyOf(registry.airborneTraffic().values());

        PacketDistributor.sendToPlayer(player, new OpenAtcPayload(getBlockPos(), airports, traffic));
    }
}
