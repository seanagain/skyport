package com.seaport.registry;

import com.seaport.SeaPort;
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

    private SeaPortBlockEntities() { }
}
