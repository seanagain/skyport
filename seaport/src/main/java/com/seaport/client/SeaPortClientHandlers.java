package com.seaport.client;

import com.seaport.client.gui.MarineAutopilotScreen;
import com.seaport.client.gui.MarineBeaconScreen;
import com.seaport.client.gui.PortStationScreen;
import com.seaport.network.OpenMarineAutopilotPayload;
import com.seaport.network.OpenMarineBeaconPayload;
import com.seaport.network.OpenPortStationPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side payload handling, in its own class so a dedicated server never
 * has to resolve the screen types named here.
 */
public final class SeaPortClientHandlers {

    private SeaPortClientHandlers() { }

    public static void openMarineAutopilot(OpenMarineAutopilotPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(new MarineAutopilotScreen(
                        payload.autopilotPos(), payload.callsign(), payload.running(),
                        payload.schedule(), payload.directory())));
    }

    public static void openMarineBeacon(OpenMarineBeaconPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new MarineBeaconScreen(payload.beaconPos(), payload.name())));
    }

    public static void openPortStation(OpenPortStationPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new PortStationScreen(payload.stationPos(), payload.layout())));
    }
}
