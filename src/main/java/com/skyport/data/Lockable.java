package com.skyport.data;

/**
 * A block entity that can be locked to the player who placed it.
 *
 * Exists so the access checks can be written once against both the Airport
 * Station and the Autopilot, which have nothing else in common - one is a
 * map editor bolted to the ground, the other a flight computer on a moving
 * craft.
 */
public interface Lockable {

    BlockLock skyportLock();
}
