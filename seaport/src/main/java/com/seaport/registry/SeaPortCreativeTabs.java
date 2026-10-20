package com.seaport.registry;

import com.seaport.SeaPort;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A tab of its own, so the blocks can be got hold of to test with rather than
 * hunted for in the search tab.
 *
 * Ordered the way a harbour is built rather than alphabetically: the port
 * first, then the thing that watches it, then the markers, then what sails.
 */
public class SeaPortCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SeaPort.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SEAPORT_TAB =
            REGISTER.register("seaport",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.seaport"))
                            .icon(() -> SeaPortBlocks.PORT_STATION_ITEM.get().getDefaultInstance())
                            .displayItems((params, output) -> {
                                output.accept(SeaPortBlocks.PORT_STATION_ITEM.get());
                                output.accept(SeaPortBlocks.MARINE_RADAR_ITEM.get());
                                output.accept(SeaPortBlocks.MARINE_BEACON_ITEM.get());
                                output.accept(SeaPortBlocks.MARINE_AUTOPILOT_ITEM.get());
                            })
                            .build());

    private SeaPortCreativeTabs() { }

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }
}
