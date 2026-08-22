package com.skyport.client;

import com.skyport.client.gui.AirportStationScreen;
import com.skyport.client.gui.AtcScreen;
import com.skyport.client.gui.AutopilotScreen;
import com.skyport.network.AtcTrafficPayload;
import com.skyport.network.OpenAirportMapPayload;
import com.skyport.network.OpenAtcPayload;
import com.skyport.network.OpenAutopilotPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The half of {@link com.skyport.network.ModNetworking} that opens screens.
 *
 * This lives in its own class because a dedicated server crashed on startup
 * without it. ModNetworking carries {@code @EventBusSubscriber}, so FML calls
 * {@code Class.forName} on it during mod construction; verifying the handler
 * bodies meant checking that our screens are assignable to {@code Screen},
 * which loads {@code Screen}, which is client-only - "Attempted to load class
 * net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER".
 *
 * ModNetworking's own comment claimed a dedicated server could safely carry
 * these methods unused on the classpath, and offered this split as the fix if
 * that ever stopped being true. It was never true; nobody had started a
 * dedicated server.
 *
 * Nothing here may be referenced from common code except through a
 * {@code FMLEnvironment.dist} guard: a plain {@code invokestatic} to this
 * class is fine because the verifier doesn't resolve the owner, but anything
 * that puts one of these types in a signature will load it and crash again.
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    public static void atcTraffic(AtcTrafficPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof AtcScreen atc) {
                atc.refresh(payload.airports(), payload.traffic());
            }
        });
    }

    public static void openAirportMap(OpenAirportMapPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new AirportStationScreen(payload.stationPos(), payload.layout())));
    }

    public static void openAtc(OpenAtcPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new AtcScreen(payload.atcPos(), payload.airports(), payload.traffic())));
    }

    public static void openAutopilot(OpenAutopilotPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                Minecraft.getInstance().setScreen(new AutopilotScreen(
                        payload.autopilotPos(), payload.airports(), payload.schedule())));
    }
}
