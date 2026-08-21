package com.skyport.client.ponder;

import com.skyport.Skyport;
import com.skyport.registry.ModBlocks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * In-game explanations for the three blocks, using Create's own Ponder
 * system - so pressing W over a Skyport block does the same thing as
 * pressing W over a Create one.
 *
 * Worth the effort because the layout rules genuinely need explaining: that
 * taxiway clicks are read in pairs, that the final leg runs from the pattern
 * to the runway and not the other way, that a hold line is where aircraft
 * queue. We had to bolt hint text onto the editor for exactly this reason,
 * and hint text is a worse version of a Ponder scene.
 *
 * The scenes are deliberately explanatory rather than animated: they name
 * the parts and the rules, which is what's actually hard to guess. Showing
 * an aircraft fly a circuit would need a scripted contraption, and Ponder
 * cannot simulate the physics this mod relies on anyway.
 */
public class SkyportPonder implements PonderPlugin {

    @Override
    public String getModId() {
        return Skyport.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<net.minecraft.world.level.block.Block> blocks =
                helper.withKeyFunction(block ->
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));

        blocks.forComponents(ModBlocks.AIRPORT_STATION.get())
                .addStoryBoard("airport_station", SkyportPonder::airportStation);
        blocks.forComponents(ModBlocks.AUTOPILOT.get())
                .addStoryBoard("autopilot", SkyportPonder::autopilot);
        blocks.forComponents(ModBlocks.ATC.get())
                .addStoryBoard("atc", SkyportPonder::atc);
    }

    private static void airportStation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("airport_station", "Laying out an airport");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        // showBasePlate only reveals the ground layer - without this the
        // block being explained never appears.
        scene.world().showSection(util.select().layersFrom(1), net.minecraft.core.Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(90)
                .text("The Airport Station defines one airport. Name it, then draw its "
                        + "layout on a map of the surrounding terrain.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Draw the runway first - two points, gate end then far end. "
                        + "Everything else connects to it.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Taxiway clicks are read in PAIRS. Each pair is one segment: a "
                        + "backbone from the runway, then a spur out to each gate.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("A Hold Line on the taxiway is where aircraft queue for the "
                        + "runway. Gates and Pads are where they park.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Hold this block to see the layout traced in the world, so you "
                        + "can build a runway where you drew one.")
                .placeNearTarget();
        scene.idle(90);
    }

    private static void autopilot(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("autopilot", "Flying a schedule");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        // showBasePlate only reveals the ground layer - without this the
        // block being explained never appears.
        scene.world().showSection(util.select().layersFrom(1), net.minecraft.core.Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(90)
                .text("The Autopilot flies one aircraft. Place it on the craft with "
                        + "the arrow on top pointing along the fuselage.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("That arrow is how it knows which way the nose faces - a craft "
                        + "is just a pile of blocks otherwise.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Build a schedule of stops, like a train's. Each stop waits for "
                        + "a timer, a player, or cargo to be loaded or unloaded.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Choose Plane, Heli or Blimp. Planes need a runway; the other "
                        + "two lift straight off a pad.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Engage from the screen, or with a redstone signal - powered "
                        + "flies the saved schedule, unpowered stops it.")
                .placeNearTarget();
        scene.idle(90);
    }

    private static void atc(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("atc", "Watching the traffic");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        // showBasePlate only reveals the ground layer - without this the
        // block being explained never appears.
        scene.world().showSection(util.select().layersFrom(1), net.minecraft.core.Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(90)
                .text("Air Traffic Control shows every airport and every aircraft "
                        + "under autopilot on one live map.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Drag to pan, scroll to zoom. The strip on the right lists each "
                        + "aircraft and what it is doing right now.")
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("Opening it also wakes aircraft parked at airports nobody is "
                        + "visiting, so their schedules pick up again.")
                .placeNearTarget();
        scene.idle(90);
    }
}
