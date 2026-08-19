package com.skyport.network;

import com.skyport.Skyport;
import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.client.gui.AirportStationScreen;
import com.skyport.client.gui.AtcScreen;
import com.skyport.client.gui.AutopilotScreen;
import com.skyport.data.Waypoint;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
 * Client-only calls (Minecraft.getInstance(), setScreen(...)) live inside
 * handler method bodies below, never at class/field-init time, which is
 * why this one class can safely reference both client and server code -
 * a dedicated server never actually executes the client-handler methods,
 * it just has them sitting unused on the classpath. If that ever trips
 * up a stricter build setup, split the two playToClient handlers out into
 * a class under com.skyport.client instead.
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

    private static void handleAtcTrafficRequest(AtcTrafficPayload.Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            var registry = com.skyport.data.AirportRegistry.get(player.serverLevel());
            PacketDistributor.sendToPlayer(player,
                    new AtcTrafficPayload(List.copyOf(registry.airborneTraffic().values())));
        });
    }

    private static void handleAtcTraffic(AtcTrafficPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof AtcScreen atc) {
                atc.updateTraffic(payload.traffic());
            }
        });
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
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(new AirportStationScreen(payload.stationPos(), payload.layout())));
    }

    private static void handleOpenAtc(OpenAtcPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new AtcScreen(payload.atcPos(), payload.airports(), payload.traffic())));
    }

    private static void handleOpenAutopilot(OpenAutopilotPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(new AutopilotScreen(
                        payload.autopilotPos(), payload.airports(), payload.schedule())));
    }
}
