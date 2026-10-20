package com.seaport;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Mod entry point.
 *
 * Harbours and shipping lanes, the way Skyport is airports and flight plans.
 * The two share a core, because almost everything either one does - booking a
 * berth, holding a schedule, keeping a roster, holding chunks open, answering
 * a computer - has nothing to do with whether the vehicle flies or floats.
 *
 * The four blocks are registered and placeable. They do nothing yet: the
 * first thing this mod has to be able to do is know where the water is deep
 * enough to sail, and that is worth proving against a real world before
 * anything is built on top of it.
 */
@Mod(SeaPort.MOD_ID)
public class SeaPort {

    public static final String MOD_ID = "seaport";

    /** Shared logger. Diagnostics go here rather than to chat: a fault that
     *  needs a timeline needs a file. */
    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public SeaPort(IEventBus modEventBus, ModContainer modContainer) {
        com.seaport.registry.SeaPortBlocks.register(modEventBus);
        com.seaport.registry.SeaPortCreativeTabs.register(modEventBus);
    }
}
