package com.skyport.compat;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.skyport.Skyport;
import com.skyport.registry.ModBlocks;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Wiring into Create's own systems, kept in one place so it is obvious what
 * this addon touches beyond its own blocks.
 *
 * Registration goes through RegisterEvent rather than a DeferredRegister,
 * and that is not a style preference. A DeferredRegister has to name its
 * target registry in a static field initialiser, which loads Create's
 * CreateRegistries class during our own mod constructor - far earlier than
 * Create expects - and that crashed registry initialisation outright, taking
 * the whole game down before anything could load. RegisterEvent touches the
 * registry only once registration is actually happening.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CreateCompat {

    private static final ResourceLocation FLIGHT_BOARD_ID =
            ResourceLocation.fromNamespaceAndPath(Skyport.MOD_ID, "flight_board");

    private static FlightBoardDisplaySource flightBoard;

    private CreateCompat() { }

    @SubscribeEvent
    static void onRegister(RegisterEvent event) {
        event.register(CreateRegistries.DISPLAY_SOURCE, registry -> {
            flightBoard = new FlightBoardDisplaySource();
            registry.register(FLIGHT_BOARD_ID, flightBoard);
        });
    }

    /**
     * Bind the source to the blocks a Display Link may read from.
     *
     * Separate from registering it: the binding is by Block instance, so it
     * has to wait until blocks exist.
     */
    @SubscribeEvent
    static void bindDisplaySources(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (flightBoard == null) return; // registration didn't run; nothing to bind
            DisplaySource.BY_BLOCK.add(ModBlocks.AIRPORT_STATION.get(), flightBoard);
            DisplaySource.BY_BLOCK.add(ModBlocks.ATC.get(), flightBoard);
        });
    }
}
