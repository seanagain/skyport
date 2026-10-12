package com.skyport.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out whether an aircraft is carrying anything.
 *
 * Deliberately not asking Create for a contraption's MountedStorageManager:
 * a Create Aeronautics craft is a Sable sub-level, which is a small real
 * level containing real blocks, so its chests and barrels are simply blocks
 * near the autopilot. Reading them through the ordinary item-handler
 * capability means this works for any container any mod adds, rather than
 * only the ones Create knows how to mount.
 *
 * The scan is the expensive part, so it happens once per stop and the
 * container positions are remembered. Re-reading a handful of known
 * inventories every second is nothing; sweeping a cube of blocks every
 * second would not be.
 */
final class CargoSensor {

    /** How far from the autopilot to look for containers. Comfortably covers
     *  a cargo hold without sweeping the whole craft. */
    private static final int SCAN_RADIUS = 10;

    private CargoSensor() { }

    /** Finds every container on the craft, once. */
    static List<BlockPos> findContainers(Level level, BlockPos origin) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getBlockEntity(cursor) == null) continue; // containers have block entities
                    if (level.getCapability(Capabilities.ItemHandler.BLOCK, cursor.immutable(), null) != null) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        return found;
    }

    /** How many items those containers currently hold. */
    static int countItems(Level level, List<BlockPos> containers) {
        int total = 0;
        for (BlockPos pos : containers) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue; // container removed since the scan
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                total += handler.getStackInSlot(slot).getCount();
            }
        }
        return total;
    }
}
