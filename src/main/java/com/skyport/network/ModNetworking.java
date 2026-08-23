package com.skyport.network;

import com.skyport.Skyport;
import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.client.ClientPayloadHandlers;
import com.skyport.data.Waypoint;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * Registers every custom C2S/S2C payload this addon uses. NeoForge scans
 * for @EventBusSubscriber classes automatically, so this class doesn't
 * need to be referenced anywhere except being imported once (Skyport does
 * this) so the classloader picks it up.
 *
 * The screen-opening half lives in {@link com.skyport.client.ClientPayloadHandlers}
 * and is reached only through the dist guard below. This class used to hold
 * those bodies itself, on the theory that a dedicated server would never
 * execute them and could carry them unused on the classpath. It cannot:
 * @EventBusSubscriber makes FML load this class by name during mod
 * construction, and verifying a body that calls setScreen(ourScreen) forces
 * Screen to load, which a dedicated server refuses. That crashed startup
 * outright until the first server was actually run.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                EngageAutopilotPayload.TYPE,
                EngageAutopilotPayload.STREAM_CODEC,
                ModNetworking::handleEngageAutopilot);

        registrar.playToServer(
                DisengageAutopilotPayload.TYPE,
                DisengageAutopilotPayload.STREAM_CODEC,
                ModNetworking::handleDisengageAutopilot);

        registrar.playToServer(
                SaveAirportLayoutPayload.TYPE,
                SaveAirportLayoutPayload.STREAM_CODEC,
                ModNetworking::handleSaveAirportLayout);

        registrar.playToServer(
                SaveSchedulePayload.TYPE,
                SaveSchedulePayload.STREAM_CODEC,
                ModNetworking::handleSaveSchedule);

        registrar.playToServer(
                WakeAircraftPayload.TYPE,
                WakeAircraftPayload.STREAM_CODEC,
                ModNetworking::handleWakeAircraft);

        registrar.playToServer(
                AtcTrafficPayload.Request.TYPE,
                AtcTrafficPayload.Request.STREAM_CODEC,
                ModNetworking::handleAtcTrafficRequest);

        registrar.playToClient(
                AtcTrafficPayload.TYPE,
                AtcTrafficPayload.STREAM_CODEC,
                ModNetworking::handleAtcTraffic);

        registrar.playToClient(
                OpenAirportMapPayload.TYPE,
                OpenAirportMapPayload.STREAM_CODEC,
                ModNetworking::handleOpenAirportMap);

        registrar.playToClient(
                OpenAutopilotPayload.TYPE,
                OpenAutopilotPayload.STREAM_CODEC,
                ModNetworking::handleOpenAutopilot);

        registrar.playToClient(
                OpenAtcPayload.TYPE,
                OpenAtcPayload.STREAM_CODEC,
                ModNetworking::handleOpenAtc);
    }

    // ---- C2S: player -> server ----

    private static void handleEngageAutopilot(EngageAutopilotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player.level().getBlockEntity(payload.pos()) instanceof AutopilotBlockEntity autopilot) {
                // TODO: validate the player is actually allowed to control
                // this autopilot block (distance check, ownership, etc.)
                // before trusting a client-sent payload.
                autopilot.engage(payload.schedule(), player);
            }
        });
    }

    private static void handleDisengageAutopilot(DisengageAutopilotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player.level().getBlockEntity(payload.pos()) instanceof AutopilotBlockEntity autopilot) {
                autopilot.disengage();
            }
        });
    }

    /**
     * Wake one parked aircraft on request from the tower.
     *
     * The player has to be looking at a real ATC screen to send this, and
     * the cost is bounded and self-expiring (see FleetWake), so there is
     * nothing here worth guarding beyond the aircraft existing.
     */
    private static void handleWakeAircraft(WakeAircraftPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            var registry = com.skyport.data.AirportRegistry.get(player.serverLevel());
            String callsign = registry.knownById(payload.planeId())
                    .map(com.skyport.data.AirportRegistry.KnownAircraft::callsign)
                    .orElse("That aircraft");
            boolean woken = com.skyport.world.FleetWake.wake(player.getServer(), payload.planeId());
            if (!woken) {
                player.sendSystemMessage(Component.literal(
                        "[Skyport] Could not wake " + callsign + " - it may have moved already."));
                return;
            }
            player.sendSystemMessage(Component.literal("[Skyport] Loading the world around "
                    + callsign + " for " + com.skyport.SkyportConfig.fleetWakeMinutes + " minutes."));
            // Report back once it has had a chance to tick, rather than
            // leaving "did that do anything" to guesswork.
            com.skyport.world.FleetWake.confirmLater(player.getServer(), payload.planeId(),
                    player.getUUID(), callsign);
        });
    }

    private static void handleAtcTrafficRequest(AtcTrafficPayload.Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            var registry = com.skyport.data.AirportRegistry.get(player.serverLevel());
            com.skyport.blockentity.AtcBlockEntity.pruneGhostAirports(player.serverLevel(), registry);
            PacketDistributor.sendToPlayer(player, new AtcTrafficPayload(
                    List.copyOf(registry.all()),
                    List.copyOf(registry.allTraffic(player.serverLevel().getGameTime()))));
        });
    }

    /**
     * Every screen-opening handler goes through here.
     *
     * The dist check is not belt-and-braces - a dedicated server does load
     * this class (FML resolves @EventBusSubscriber classes by name during mod
     * construction) and would verify these method bodies. Calling into
     * ClientPayloadHandlers by plain invokestatic leaves the reference
     * unresolved until something actually runs it, which on a server is
     * never. Putting a screen type in a signature here instead would load it
     * and crash the server at startup, which is exactly what happened.
     */
    private static boolean onClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    private static void handleAtcTraffic(AtcTrafficPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        ClientPayloadHandlers.atcTraffic(payload, context);
    }

    private static void handleSaveSchedule(SaveSchedulePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player.level().getBlockEntity(payload.pos()) instanceof AutopilotBlockEntity autopilot) {
                autopilot.setSchedule(payload.schedule());
            }
        });
    }

    private static void handleSaveAirportLayout(SaveAirportLayoutPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player.level().getBlockEntity(payload.stationPos()) instanceof AirportStationBlockEntity station) {
                station.saveLayout(payload.layout());
                // Explicit confirmation so "did that actually register" isn't
                // a guessing game - the Autopilot's airport picker only ever
                // sees whatever was last saved here. Reports every element,
                // not just gates: a layout can look drawn but be missing the
                // runway or taxiway the autopilot actually needs.
                var layout = payload.layout();
                player.sendSystemMessage(Component.literal(String.format(
                        "[Skyport] Saved '%s' - runway %d/2, taxiway %d, holding %d, final %d, gates %d.",
                        layout.displayName(),
                        layout.waypoints(Waypoint.Type.RUNWAY).size(),
                        layout.waypoints(Waypoint.Type.TAXIWAY).size(),
                        layout.waypoints(Waypoint.Type.HOLDING_PATTERN).size(),
                        layout.waypoints(Waypoint.Type.FINAL_LEG).size(),
                        layout.gates().size())));
            }
        });
    }

    // ---- S2C: server -> player ----

    private static void handleOpenAirportMap(OpenAirportMapPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        ClientPayloadHandlers.openAirportMap(payload, context);
    }

    private static void handleOpenAtc(OpenAtcPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        ClientPayloadHandlers.openAtc(payload, context);
    }

    private static void handleOpenAutopilot(OpenAutopilotPayload payload, IPayloadContext context) {
        if (!onClient()) return;
        ClientPayloadHandlers.openAutopilot(payload, context);
    }
}
