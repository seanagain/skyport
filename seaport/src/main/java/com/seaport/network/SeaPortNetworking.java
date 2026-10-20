package com.seaport.network;

import com.seaport.SeaPort;
import com.seaport.blockentity.PortStationBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration and handling.
 *
 * Client handling is kept behind a dist check and in its own class, so the
 * client-only types it touches are never resolved on a dedicated server -
 * naming a screen class in a method a server can reach is enough to crash it
 * on load, whether or not the method runs.
 */
@EventBusSubscriber(modid = SeaPort.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class SeaPortNetworking {

    private SeaPortNetworking() { }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                OpenPortStationPayload.TYPE,
                OpenPortStationPayload.STREAM_CODEC,
                SeaPortNetworking::handleOpenPortStation);

        registrar.playToServer(
                SavePortLayoutPayload.TYPE,
                SavePortLayoutPayload.STREAM_CODEC,
                SeaPortNetworking::handleSavePortLayout);
    }

    private static boolean onClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    private static void handleOpenPortStation(OpenPortStationPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        com.seaport.client.SeaPortClientHandlers.openPortStation(payload, context);
    }

    /**
     * The station is the authority on its own port, not the packet.
     *
     * The id in the layout a client sends back is taken on trust only as far
     * as this block: whatever arrives is stored against the station that was
     * clicked, so a crafted packet can rewrite the harbour in front of the
     * player and no other.
     */
    private static void handleSavePortLayout(SavePortLayoutPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!(player.level() instanceof ServerLevel level)) return;
            if (!(level.getBlockEntity(payload.stationPos()) instanceof PortStationBlockEntity station)) {
                return;
            }
            // Out of reach is somebody else's harbour, or nobody's.
            if (!player.blockPosition().closerThan(payload.stationPos(), 12)) return;
            station.saveLayout(level, payload.layout());
        });
    }
}
