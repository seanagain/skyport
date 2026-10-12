package com.skyport.compat.cc;

import com.skyport.Skyport;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The door to the ComputerCraft integration, and the only class in this
 * package that is safe to load when CC is not installed.
 *
 * ComputerCraft is genuinely optional here, unlike Create - Skyport is an
 * airport system that can also be driven by a computer, not one that needs
 * one. So this half has to be not merely unused on a server without CC but
 * unreachable: a class whose signatures mention CC types cannot even be
 * loaded without them, and loading one would take the game down at startup
 * rather than quietly doing nothing.
 *
 * Hence the shape. Nothing here names a ComputerCraft type. The check runs
 * first, and only then is {@link CCPeripherals} mentioned at all - which is
 * what keeps the JVM from resolving it, and everything it refers to, on a
 * server that has no CC to resolve them against.
 *
 * CreateCompat has the scar that taught this lesson: a DeferredRegister field
 * initialiser reached into Create's registries during the mod constructor and
 * crashed registry initialisation outright, before anything could load. That
 * was a mod we require. This is one we do not.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CCCompat {

    /** CC: Tweaked's own mod id, as it appears in its mods.toml - and as
     *  CC: Sable declares its dependency on it. */
    private static final String COMPUTERCRAFT = "computercraft";

    private CCCompat() { }

    public static boolean installed() {
        return ModList.get().isLoaded(COMPUTERCRAFT);
    }

    @SubscribeEvent
    static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        if (!installed()) return;
        CCPeripherals.register(event);
    }
}
