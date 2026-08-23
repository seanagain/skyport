package com.skyport.blockentity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.skyport.SkyportConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Whether an aircraft currently has whatever the server decided flight
 * should cost.
 *
 * The autopilot steers by writing velocity onto the craft's rigid body, so
 * it produces thrust from nothing: without this, an autopilot on a solid
 * cube of iron flies as well as a real aeroplane, and a survival player who
 * built a proper Create powertrain got no benefit from it. This is the
 * throttle that makes flight cost something.
 *
 * Two modes, because they suit different servers rather than because one is
 * better. ROTATION leans on Create - the craft has to carry a powertrain and
 * feed it into the Autopilot block - and costs whatever running that
 * powertrain costs. FUEL is self-contained: furnace fuel out of a chest on
 * the aircraft, no kinetics needed, and readable at a glance.
 *
 * Deliberately NOT reading Aeronautics' propellers. Capping the autopilot by
 * the thrust the craft can really produce would be the most honest version
 * of this, but it makes cruise speed an emergent property of the powertrain,
 * which is a much larger change to how routes are planned and how a schedule
 * behaves. That is a separate decision, and this one is reversible.
 */
final class PowerSource {

    private PowerSource() { }

    /**
     * Is Create rotation reaching this block?
     *
     * Reads the six neighbours rather than making the Autopilot a kinetic
     * block itself. Extending KineticBlockEntity would drag in stress
     * reporting, shaft-connection rules and a model with a shaft on it - a
     * lot of machinery to answer a yes/no question. A shaft or cogwheel
     * against the block is just as clear to build and to look at.
     */
    static boolean hasRotation(Level level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (!(level.getBlockEntity(pos.relative(side)) instanceof KineticBlockEntity kinetic)) continue;
            // Either direction will do - which way the shaft happens to spin
            // is an artefact of how the powertrain was built, not a choice
            // anyone makes deliberately.
            if (Math.abs(kinetic.getSpeed()) >= SkyportConfig.rotationMinimumRpm) return true;
        }
        return false;
    }

    /**
     * Take one item's worth of fuel from the aircraft, and report how many
     * ticks of flight it bought.
     *
     * Returns 0 when there is nothing burnable aboard. Uses the ordinary
     * item-handler capability and vanilla burn times, so anything that works
     * in a furnace works here, including fuels added by other mods.
     */
    static int consumeFuel(Level level, List<BlockPos> containers) {
        if (containers == null) return 0;
        for (BlockPos pos : containers) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                int burnTime = stack.getBurnTime(null);
                if (burnTime <= 0) continue;
                // Only actually take it if the extraction succeeds - a
                // locked or output-only slot will refuse, and burning fuel
                // that stayed in the chest would be a duplication bug in the
                // player's favour that still reads as the mod being broken.
                if (handler.extractItem(slot, 1, false).isEmpty()) continue;
                return (int) Math.max(1, burnTime * SkyportConfig.fuelEfficiency);
            }
        }
        return 0;
    }

    /** Every container on the craft - the same set cargo is counted from,
     *  so a fuel bunker is just a chest with coal in it. Cached by the
     *  caller, because the scan is a cube of blocks and the read is not. */
    static List<BlockPos> findFuelContainers(Level level, BlockPos origin) {
        return CargoSensor.findContainers(level, origin);
    }
}
