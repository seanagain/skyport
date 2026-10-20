package com.seaport.registry;

import com.seaport.SeaPort;
import com.seaport.block.MarineAutopilotBlock;
import com.seaport.block.MarineBeaconBlock;
import com.seaport.block.MarineRadarBlock;
import com.seaport.block.PortStationBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Every block SeaPort adds, and a matching item for each.
 *
 * All four call noOcclusion(), and must keep doing so. None of them fills its
 * block space - the consoles stop short of the top, the radar is inset above
 * its plinth, the beacon is a buoy with air around it. Without noOcclusion()
 * the game still treats them as solid cubes, so a neighbouring block culls the
 * face it shares with one and nothing is drawn there at all: a hole straight
 * through to the sky, which is what you see the moment two are placed side by
 * side or a hull is plated over an autopilot. Skyport learned this the hard
 * way and the models here are Skyport's, so the lesson transfers exactly.
 *
 * requiresCorrectToolForDrops() needs a matching entry in the pickaxe mining
 * tag or nothing counts as the correct tool and the block drops nothing ever -
 * see data/minecraft/tags/block/mineable/pickaxe.json.
 */
public class SeaPortBlocks {

    public static final DeferredRegister.Blocks REGISTER =
            DeferredRegister.createBlocks(SeaPort.MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, SeaPort.MOD_ID);

    /** Defines a port - where its wharves are and how ships berth there. */
    public static final DeferredBlock<PortStationBlock> PORT_STATION = REGISTER.register(
            "port_station",
            () -> new PortStationBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLUE)
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /** Every vessel under way, wherever it is. */
    public static final DeferredBlock<MarineRadarBlock> MARINE_RADAR = REGISTER.register(
            "marine_radar",
            () -> new MarineRadarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /** A channel marker: a named point a route can be run through. */
    public static final DeferredBlock<MarineBeaconBlock> MARINE_BEACON = REGISTER.register(
            "marine_beacon",
            () -> new MarineBeaconBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(3.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /** Placed on a ship, and the thing that will eventually sail it. */
    public static final DeferredBlock<MarineAutopilotBlock> MARINE_AUTOPILOT = REGISTER.register(
            "marine_autopilot",
            () -> new MarineAutopilotBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> PORT_STATION_ITEM = ITEMS.register(
            "port_station", () -> new BlockItem(PORT_STATION.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> MARINE_RADAR_ITEM = ITEMS.register(
            "marine_radar", () -> new BlockItem(MARINE_RADAR.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> MARINE_BEACON_ITEM = ITEMS.register(
            "marine_beacon", () -> new BlockItem(MARINE_BEACON.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> MARINE_AUTOPILOT_ITEM = ITEMS.register(
            "marine_autopilot", () -> new BlockItem(MARINE_AUTOPILOT.get(), new Item.Properties()));

    private SeaPortBlocks() { }

    public static void register(IEventBus modEventBus) {
        REGISTER.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
