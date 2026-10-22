package com.seaport.network;

import com.seaport.SeaPort;
import com.seaport.blockentity.MarineAutopilotBlockEntity;
import com.seaport.blockentity.MarineBeaconBlockEntity;
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

        registrar.playToClient(
                OpenMarineBeaconPayload.TYPE,
                OpenMarineBeaconPayload.STREAM_CODEC,
                SeaPortNetworking::handleOpenMarineBeacon);

        registrar.playToServer(
                RenameBeaconPayload.TYPE,
                RenameBeaconPayload.STREAM_CODEC,
                SeaPortNetworking::handleRenameBeacon);

        registrar.playToClient(
                OpenMarineAutopilotPayload.TYPE,
                OpenMarineAutopilotPayload.STREAM_CODEC,
                SeaPortNetworking::handleOpenMarineAutopilot);

        registrar.playToServer(
                SaveVoyageSchedulePayload.TYPE,
                SaveVoyageSchedulePayload.STREAM_CODEC,
                SeaPortNetworking::handleSaveVoyageSchedule);
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

    private static void handleOpenMarineBeacon(OpenMarineBeaconPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        com.seaport.client.SeaPortClientHandlers.openMarineBeacon(payload, context);
    }

    /** The block that was clicked decides which mark gets renamed, not the
     *  packet - same reasoning as the layout save above. */
    private static void handleRenameBeacon(RenameBeaconPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!(player.level() instanceof ServerLevel level)) return;
            if (!(level.getBlockEntity(payload.beaconPos()) instanceof MarineBeaconBlockEntity beacon)) {
                return;
            }
            if (!player.blockPosition().closerThan(payload.beaconPos(), 12)) return;
            beacon.rename(payload.name());
        });
    }

    private static void handleOpenMarineAutopilot(OpenMarineAutopilotPayload payload,
                                                  IPayloadContext context) {
        if (!onClient()) return;
        com.seaport.client.SeaPortClientHandlers.openMarineAutopilot(payload, context);
    }

    /**
     * Orders are stored against the autopilot that was clicked.
     *
     * The schedule itself is not validated here beyond what reading it
     * already enforces, and deliberately: a stop can legitimately name a
     * harbour whose station is temporarily broken, or a berth somebody is
     * about to redraw. Refusing those at save time would mean a player
     * cannot write tomorrow's route today. What cannot be forged is WHICH
     * ship gets them, and that is settled by the block in front of the
     * player rather than by anything in the packet.
     */
    private static void handleSaveVoyageSchedule(SaveVoyageSchedulePayload payload,
                                                 IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!(player.level() instanceof ServerLevel level)) return;
            if (!(level.getBlockEntity(payload.autopilotPos())
                    instanceof MarineAutopilotBlockEntity autopilot)) {
                return;
            }
            if (!player.blockPosition().closerThan(payload.autopilotPos(), 12)) return;
            autopilot.setCallsign(payload.callsign());
            autopilot.setSchedule(payload.schedule());
            autopilot.setRunning(payload.running());
        });
    }
}
