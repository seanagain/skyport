package com.skyport;

import com.skyport.network.ModNetworking;
import com.skyport.registry.ModBlockEntities;
import com.skyport.registry.ModBlocks;
import com.skyport.registry.ModCreativeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Mod entry point. NeoForge finds this class because its package matches
 * the "javafml" mod loader's scan, and constructs exactly one instance of
 * it at startup - this is where every DeferredRegister gets wired to the
 * mod event bus.
 *
 * Everything actually interesting (the two blocks, their block entities,
 * the flight-plan data model) lives in the other packages. This class
 * should stay small and just be "plumbing".
 */
@Mod(Skyport.MOD_ID)
public class Skyport {

    public static final String MOD_ID = "skyport";

    // NeoForge injects whichever of these constructor parameters you ask
    // for, in any order - IEventBus, ModContainer, FMLModContainer, Dist.
    // We only need the event bus to wire up our registries.
    public Skyport(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.REGISTER.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, SkyportConfig.SPEC);
        // Cache the values whenever the file loads or is edited in game, so
        // the flight code can read plain fields rather than going through the
        // config machinery on every message and every tick.
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == SkyportConfig.SPEC) SkyportConfig.refresh();
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == SkyportConfig.SPEC) SkyportConfig.refresh();
        });

        // ModNetworking listens for RegisterPayloadHandlersEvent itself
        // (see @EventBusSubscriber on that class) so nothing to call here -
        // just needs to be class-loaded, which importing it guarantees.
    }
}
