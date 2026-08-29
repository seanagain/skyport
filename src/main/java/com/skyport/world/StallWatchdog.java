package com.skyport.world;

import com.skyport.Skyport;
import com.skyport.blockentity.AutopilotBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Hands a frozen aircraft back to the physics engine.
 *
 * This exists because of where it runs, not because of what it does. The
 * autopilot's entire flight loop lives inside Sable's physics tick, and a
 * rigid body that comes to rest is parked by the engine and stops being
 * ticked - so an aircraft that stopped could not restart itself, and went
 * quiet on the tower's map at the same moment. Any recovery written inside
 * that tick is unreachable by definition. It has to be driven from the
 * server tick, which keeps running whatever the craft is doing.
 *
 * Every tick rather than every few: an aircraft frozen mid-pushback is
 * visibly broken, and the check is a map that is empty on almost every
 * server and a timestamp comparison on the ones where it is not.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID)
public final class StallWatchdog {

    private StallWatchdog() { }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        AutopilotBlockEntity.nudgeStalledAircraft(event.getServer().overworld());
    }
}
