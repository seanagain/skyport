package com.skyport.compat;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.skyport.Skyport;
import com.skyport.registry.ModBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Wiring into Create's own systems, kept in one place so it's obvious what
 * this addon touches beyond its own blocks.
 *
 * Right now that's the Display Link: a source has to be registered into
 * Create's registry to have an identity, and then bound to the blocks it can
 * read from.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CreateCompat {

    private static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, Skyport.MOD_ID);

    public static final DeferredHolder<DisplaySource, FlightBoardDisplaySource> FLIGHT_BOARD =
            DISPLAY_SOURCES.register("flight_board", FlightBoardDisplaySource::new);

    private CreateCompat() { }

    public static void register(IEventBus modEventBus) {
        DISPLAY_SOURCES.register(modEventBus);
    }

    /**
     * Bind the source to the blocks a Display Link may read.
     *
     * Done in common setup rather than at registration because the binding
     * is by Block instance, and those must exist first.
     */
    @SubscribeEvent
    static void bindDisplaySources(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DisplaySource.BY_BLOCK.add(ModBlocks.AIRPORT_STATION.get(), FLIGHT_BOARD.get());
            DisplaySource.BY_BLOCK.add(ModBlocks.ATC.get(), FLIGHT_BOARD.get());
        });
    }
}
