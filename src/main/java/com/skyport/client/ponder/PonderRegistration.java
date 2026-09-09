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
 *
 * <p><b>Parked for 1.0.</b> The scenes work, but they teach a version of
 * the editor that no longer exists - they describe the hold line as a single
 * point, and say nothing about second runways, one-way taxiways, dragging
 * nodes, or the survival power modes. A tutorial that is confidently wrong is
 * worse than no tutorial, particularly for someone meeting the mod for the
 * first time, so nothing is registered and the blocks simply have no Ponder
 * entry rather than a misleading one.
 *
 * <p>Everything they need is still here and still compiles - the scenes in
 * {@link SkyportPonder}, their structures under {@code assets/skyport/ponder},
 * and their text in {@code en_us.json}. Re-enabling is one line, once the
 * scenes have been rewritten against the editor as it actually is.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PonderRegistration {

    /** Flip to true once the scenes match the editor again. */
    private static final boolean ENABLED = false;

    private PonderRegistration() { }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (!ENABLED) return;
        event.enqueueWork(() -> PonderIndex.addPlugin(new SkyportPonder()));
    }
}
