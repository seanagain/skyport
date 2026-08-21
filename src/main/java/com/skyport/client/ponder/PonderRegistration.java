package com.skyport.client.ponder;

import com.skyport.Skyport;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Hands the Ponder scenes to Ponder.
 *
 * Client-only: Ponder is a tutorial UI and does not exist on a dedicated
 * server, so this must never be touched during common setup.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PonderRegistration {

    private PonderRegistration() { }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PonderIndex.addPlugin(new SkyportPonder()));
    }
}
