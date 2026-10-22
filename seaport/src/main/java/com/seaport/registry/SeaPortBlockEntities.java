package com.seaport.registry;

import com.seaport.SeaPort;
import com.seaport.blockentity.MarineAutopilotBlockEntity;
import com.seaport.blockentity.MarineBeaconBlockEntity;
import com.seaport.blockentity.PortStationBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entities, for the blocks that have to remember something. */
public class SeaPortBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SeaPort.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PortStationBlockEntity>>
            PORT_STATION = REGISTER.register("port_station",
                    () -> BlockEntityType.Builder.of(
                            PortStationBlockEntity::new,
                            SeaPortBlocks.PORT_STATION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MarineBeaconBlockEntity>>
            MARINE_BEACON = REGISTER.register("marine_beacon",
                    () -> BlockEntityType.Builder.of(
                            MarineBeaconBlockEntity::new,
                            SeaPortBlocks.MARINE_BEACON.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MarineAutopilotBlockEntity>>
            MARINE_AUTOPILOT = REGISTER.register("marine_autopilot",
                    () -> BlockEntityType.Builder.of(
                            MarineAutopilotBlockEntity::new,
                            SeaPortBlocks.MARINE_AUTOPILOT.get()).build(null));

    private SeaPortBlockEntities() { }
}
