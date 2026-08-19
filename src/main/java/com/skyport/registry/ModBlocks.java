package com.skyport.registry;

import com.skyport.Skyport;
import com.skyport.block.AirportStationBlock;
import com.skyport.block.AtcBlock;
import com.skyport.block.AutopilotBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** All blocks this addon adds, plus a matching BlockItem for each. */
public class ModBlocks {

    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(Skyport.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            net.minecraft.core.registries.Registries.ITEM, Skyport.MOD_ID);

    // Placed by the player somewhere near the runway. Right-clicking it
    // opens the map editor for that airport (see AirportMapScreen).
    public static final DeferredBlock<AirportStationBlock> AIRPORT_STATION = REGISTER.register(
            "airport_station",
            () -> new AirportStationBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // Placed on an assembled Create Aeronautics plane. Right-clicking it
    // opens the destination picker (see AutopilotScreen).
    public static final DeferredBlock<AutopilotBlock> AUTOPILOT = REGISTER.register(
            "autopilot",
            () -> new AutopilotBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(2.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    // The control tower desk - see AtcBlock.
    public static final DeferredBlock<AtcBlock> ATC = REGISTER.register(
            "atc",
            () -> new AtcBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> AIRPORT_STATION_ITEM = ITEMS.register(
            "airport_station", () -> new BlockItem(AIRPORT_STATION.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> AUTOPILOT_ITEM = ITEMS.register(
            "autopilot", () -> new BlockItem(AUTOPILOT.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> ATC_ITEM = ITEMS.register(
            "atc", () -> new BlockItem(ATC.get(), new Item.Properties()));

    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        REGISTER.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
