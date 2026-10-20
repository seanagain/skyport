package com.seaport.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every port the world knows about.
 *
 * Kept on the overworld rather than per dimension, unlike the seabed chart,
 * and for the opposite reason. A chart describes the ground, which differs
 * from dimension to dimension; this is a directory of destinations, and a
 * ship wants to be sent to "Blackrock Harbour" without first being told which
 * world it is in. The layout records its own dimension, so a route that
 * crosses one can still be refused.
 */
public class PortRegistry extends SavedData {

    private static final String DATA_NAME = "seaport_ports";

    public static final SavedData.Factory<PortRegistry> FACTORY =
            new SavedData.Factory<>(PortRegistry::new, PortRegistry::load, null);

    private final Map<UUID, PortLayout> ports = new HashMap<>();

    public static PortRegistry get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<PortLayout> byId(UUID id) {
        return Optional.ofNullable(ports.get(id));
    }

    public Collection<PortLayout> all() {
        return ports.values();
    }

    public Optional<PortLayout> byName(String name) {
        return ports.values().stream()
                .filter(port -> port.displayName().equalsIgnoreCase(name))
                .findFirst();
    }

    public void put(PortLayout layout) {
        ports.put(layout.id(), layout);
        setDirty();
    }

    /**
     * Forget a port.
     *
     * Called when its station block is broken. A port left in the directory
     * with no station is a destination ships can still be routed to and never
     * reach - the harbour equivalent of the ghost airports Skyport had to
     * learn to prune.
     */
    public void remove(UUID id) {
        if (ports.remove(id) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PortLayout port : ports.values()) list.add(port.save());
        tag.put("ports", list);
        return tag;
    }

    public static PortRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        PortRegistry registry = new PortRegistry();
        ListTag list = tag.getList("ports", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                PortLayout port = PortLayout.load(list.getCompound(i));
                registry.ports.put(port.id(), port);
            } catch (RuntimeException unreadable) {
                // One corrupt port must not stop the world loading. The rest
                // of the harbours are fine and this one can be rebuilt.
            }
        }
        return registry;
    }
}
