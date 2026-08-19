package com.skyport;

import com.skyport.network.ModNetworking;
import com.skyport.registry.ModBlockEntities;
import com.skyport.registry.ModBlocks;
import com.skyport.registry.ModCreativeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

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
    public Skyport(IEventBus modEventBus) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.REGISTER.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // ModNetworking listens for RegisterPayloadHandlersEvent itself
        // (see @EventBusSubscriber on that class) so nothing to call here -
        // just needs to be class-loaded, which importing it guarantees.

        // TODO: as you add data generation (blockstates/models/loot
        // tables/recipes), register a GatherDataEvent listener here too.
    }
}
