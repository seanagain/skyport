package com.skyport.block;

import com.skyport.data.AccessControl;
import com.skyport.data.BlockLock;
import com.skyport.data.Lockable;
import com.skyport.network.OpenPasscodePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The right-click half of the lock: what a player sees when they try a
 * block that is not theirs.
 *
 * Shared by both lockable blocks so the two behave identically. A lock that
 * refuses one block with a passcode box and the other with a chat message
 * teaches players that the rule is arbitrary, and they stop reading it.
 */
public final class LockInteraction {

    private LockInteraction() { }

    /**
     * May this player open the block? If not, this has already told them
     * why - or put the passcode box in front of them - so the caller should
     * simply do nothing more.
     */
    public static boolean mayOpen(ServerPlayer player, Lockable lockable, BlockPos pos, String label) {
        BlockLock lock = lockable.skyportLock();
        switch (AccessControl.check(player, lock)) {
            case ALLOWED -> {
                return true;
            }
            case NEEDS_PASSCODE -> PacketDistributor.sendToPlayer(player,
                    new OpenPasscodePayload(pos, false, true, label));
            case DENIED -> player.sendSystemMessage(Component.literal(
                    "[Skyport] " + label + " is locked by " + lock.ownerName() + "."));
        }
        return false;
    }

    /**
     * Sneak-right-click: the owner sets or clears the code.
     *
     * On the same block as opening it rather than on a separate item or
     * command, because a lock you cannot find is a lock nobody uses. The
     * chat line on placement is what tells them it is here.
     */
    public static void administer(ServerPlayer player, Lockable lockable, BlockPos pos, String label) {
        BlockLock lock = lockable.skyportLock();
        if (!AccessControl.mayAdminister(player, lock)) {
            player.sendSystemMessage(Component.literal(
                    "[Skyport] Only " + lock.ownerName() + " can change this lock."));
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new OpenPasscodePayload(pos, true, lock.hasPasscode(), label));
    }

    /**
     * Tell the player the block is now theirs.
     *
     * Said once, on placement, because ownership is otherwise completely
     * invisible until the moment it refuses somebody - and a lock that
     * surprises its own owner is the version of this feature that generates
     * bug reports.
     */
    public static void announceClaim(ServerPlayer player, String label) {
        if (!com.skyport.SkyportConfig.protectBlocks) return;
        player.sendSystemMessage(Component.literal("[Skyport] This " + label
                + " is locked to you. Sneak + right-click it to set a passcode for others."));
    }
}
