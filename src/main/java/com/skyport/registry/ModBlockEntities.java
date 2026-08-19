package com.skyport.registry;

import com.skyport.Skyport;
import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.blockentity.AtcBlockEntity;
import com.skyport.blockentity.AutopilotBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Skyport.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AirportStationBlockEntity>> AIRPORT_STATION =
            REGISTER.register("airport_station", () -> BlockEntityType.Builder.of(
                    AirportStationBlockEntity::new, ModBlocks.AIRPORT_STATION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutopilotBlockEntity>> AUTOPILOT =
            REGISTER.register("autopilot", () -> BlockEntityType.Builder.of(
                    AutopilotBlockEntity::new, ModBlocks.AUTOPILOT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AtcBlockEntity>> ATC =
            REGISTER.register("atc", () -> BlockEntityType.Builder.of(
                    AtcBlockEntity::new, ModBlocks.ATC.get()).build(null));
}
