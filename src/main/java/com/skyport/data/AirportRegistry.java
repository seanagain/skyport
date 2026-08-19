package com.skyport.data;

import com.skyport.Skyport;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The world-level list of every airport that exists, keyed by a random id
 * assigned when its station block is placed. This is what makes "pick any
 * airport, anywhere" possible in the autopilot GUI, instead of only being
 * able to fly to airports within loaded chunks.
 *
 * Always stored on the Overworld (see {@link #get}) so it survives even if
 * an airport itself is in the Nether/End and that dimension isn't loaded.
 *
 * NB: SavedData's exact save/load signature has moved around between
 * Minecraft versions (HolderLookup.Provider was added fairly recently).
 * Double check this against the current net.minecraft.world.level.saveddata
 * package when you get to implementing this for real - treat this file as
 * "the shape of the idea", not a guaranteed-compiling final version.
 */
public class AirportRegistry extends SavedData {

    private static final String DATA_NAME = Skyport.MOD_ID + "_airports";

    public static final SavedData.Factory<AirportRegistry> FACTORY =
            new SavedData.Factory<>(AirportRegistry::new, AirportRegistry::load, null);

    private final Map<UUID, AirportLayout> airports = new HashMap<>();

    public static AirportRegistry get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<AirportLayout> byId(UUID id) {
        return Optional.ofNullable(airports.get(id));
    }

    public Collection<AirportLayout> all() {
        return airports.values();
    }

    public void put(AirportLayout layout) {
        airports.put(layout.id(), layout);
        setDirty();
    }

    public void remove(UUID id) {
        airports.remove(id);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (AirportLayout layout : airports.values()) {
            list.add(layout.save());
        }
        tag.put("airports", list);
        return tag;
    }

    private static AirportRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        AirportRegistry registry = new AirportRegistry();
        ListTag list = tag.getList("airports", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            AirportLayout layout = AirportLayout.load(list.getCompound(i));
            registry.airports.put(layout.id(), layout);
        }
        return registry;
    }
}
