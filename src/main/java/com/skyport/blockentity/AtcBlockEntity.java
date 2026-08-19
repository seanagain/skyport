package com.skyport.blockentity;

import com.skyport.data.AirportRegistry;
import com.skyport.data.AirportSummary;
import com.skyport.data.TrafficReport;
import com.skyport.network.OpenAtcPayload;
import com.skyport.registry.ModBlockEntities;
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

        List<AirportSummary> airports = registry.all().stream().map(AirportSummary::of).toList();
        List<TrafficReport> traffic = List.copyOf(registry.airborneTraffic().values());

        PacketDistributor.sendToPlayer(player, new OpenAtcPayload(getBlockPos(), airports, traffic));
    }
}
