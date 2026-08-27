package com.skyport.world;

import com.skyport.Skyport;
import com.skyport.data.AccessControl;
import com.skyport.data.BlockLock;
import com.skyport.data.Lockable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Stops someone breaking a locked block to get at what it controls.
 *
 * Without this the rest of the lock is theatre. Breaking an Airport Station
 * deletes its airport outright - every aircraft heading there loses its
 * destination - and breaking an Autopilot takes its aircraft off the roster
 * mid-schedule. Both are worse than anything a griefer could do through the
 * screen, and neither needs the screen.
 *
 * This is the one guard that has to live on an event rather than in our own
 * code, because breaking a block is vanilla's business, not the block's.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID)
public final class BlockProtection {

    private BlockProtection() { }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof Lockable lockable)) return;

        BlockLock lock = lockable.skyportLock();
        if (AccessControl.allows(player, lock)) return;

        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
                "[Skyport] That belongs to " + lock.ownerName() + " - you cannot break it."));
    }
}
