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

    /** Shared logger. Diagnostics go here rather than to chat: a fault that
     *  needs a timeline needs a file, and chat is radius-gated. */
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // NeoForge injects whichever of these constructor parameters you ask
    // for, in any order - IEventBus, ModContainer, FMLModContainer, Dist.
    // We only need the event bus to wire up our registries.
    public Skyport(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.REGISTER.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // SERVER rather than COMMON, so a server's settings are pushed to
        // every client that connects and a player editing their own copy
        // changes nothing. The cost is that this file is per-world, in
        // <world>/serverconfig/ rather than config/ - see SkyportConfig.
        modContainer.registerConfig(ModConfig.Type.SERVER, SkyportConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, SkyportConfig.CLIENT_SPEC);

        // Cache the values whenever a file loads or is edited in game, so
        // the flight code can read plain fields rather than going through the
        // config machinery on every message and every tick. The two halves
        // refresh separately: a dedicated server never loads CLIENT_SPEC,
        // and reading from an unloaded spec throws.
        modEventBus.addListener((ModConfigEvent.Loading event) -> refresh(event.getConfig()));
        modEventBus.addListener((ModConfigEvent.Reloading event) -> refresh(event.getConfig()));

        // ModNetworking listens for RegisterPayloadHandlersEvent itself
        // (see @EventBusSubscriber on that class) so nothing to call here -
        // just needs to be class-loaded, which importing it guarantees.
    }

    private static void refresh(ModConfig config) {
        if (config.getSpec() == SkyportConfig.SERVER_SPEC) SkyportConfig.refreshServer();
        else if (config.getSpec() == SkyportConfig.CLIENT_SPEC) SkyportConfig.refreshClient();
    }
}
