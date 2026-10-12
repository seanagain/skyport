package com.skyport.registry;

import com.skyport.Skyport;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A dedicated creative tab for the addon's blocks, so you can actually get them
 * in your inventory to test with rather than hunting the search tab.
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Skyport.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SKYPORT_TAB = REGISTER.register(
            "skyport",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.skyport"))
                    .icon(() -> ModBlocks.AIRPORT_STATION_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.AIRPORT_STATION_ITEM.get());
                        output.accept(ModBlocks.AUTOPILOT_ITEM.get());
                        output.accept(ModBlocks.ATC_ITEM.get());
                        output.accept(ModBlocks.VOR_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
    }
}
