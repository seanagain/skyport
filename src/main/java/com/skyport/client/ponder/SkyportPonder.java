package com.skyport.client.ponder;

import com.skyport.Skyport;
import com.skyport.registry.ModBlocks;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

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
 * The airfield is built up piece by piece as it is described, rather than
 * shown finished, because the order is itself part of the lesson - the
 * editor insists on the runway before anything can connect to it. Each piece
 * is outlined as it is named, so there is no guessing which part of the
 * scene a sentence is about.
 *
 * NOTE: every text() here needs a matching entry in en_us.json under
 * skyport.ponder.&lt;scene&gt;.text_N. Ponder derives lang keys from the scene
 * and looks those up rather than using these strings, so changing the
 * wording in only one place shows the raw key in game.
 */
public class SkyportPonder implements PonderPlugin {

    @Override
    public String getModId() {
        return Skyport.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<Block> blocks =
                helper.withKeyFunction(BuiltInRegistries.BLOCK::getKey);

        blocks.forComponents(ModBlocks.AIRPORT_STATION.get())
                .addStoryBoard("airport_station", SkyportPonder::airportStation);
        blocks.forComponents(ModBlocks.AUTOPILOT.get())
                .addStoryBoard("autopilot", SkyportPonder::autopilot);
        blocks.forComponents(ModBlocks.ATC.get())
                .addStoryBoard("atc", SkyportPonder::atc);
    }

    private static void airportStation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("airport_station", "Laying out an airport");
        scene.configureBasePlate(0, 0, 11);
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().position(1, 1, 9), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(new Vec3(1.5, 1.5, 9.5), Pointing.DOWN, 40).rightClick();
        scene.overlay().showText(80)
                .text("The Airport Station defines one airport. Name it, then draw its "
                        + "layout on a map of the surrounding terrain.")
                .pointAt(new Vec3(1.5, 1.5, 9.5))
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(1, 1, 4, 9, 1, 6), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().fromTo(1, 1, 4, 9, 1, 6), 80)
                .text("Draw the runway first - two points, gate end then far end. "
                        + "Everything else connects to it.")
                .colored(PonderPalette.WHITE)
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(7, 1, 7, 8, 1, 9), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().fromTo(7, 1, 7, 8, 1, 9), 80)
                .text("Taxiway clicks are read in PAIRS. Each pair is one segment: a "
                        + "backbone from the runway, then a spur out to each gate.")
                .colored(PonderPalette.OUTPUT)
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().position(8, 1, 10), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().position(8, 1, 10), 80)
                .text("A Hold Line on the taxiway is where aircraft queue for the "
                        + "runway. Gates and Pads are where they park.")
                .colored(PonderPalette.RED)
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(0, 1, 0, 4, 1, 2), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().fromTo(0, 1, 0, 4, 1, 2), 90)
                .text("The Holding Pattern is a loop flown in the air, at its own "
                        + "altitude. Arrivals circle here when the runway is busy.")
                .colored(PonderPalette.BLUE)
                .placeNearTarget();
        scene.idle(100);

        scene.world().showSection(util.select().fromTo(2, 1, 2, 3, 1, 3), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().fromTo(2, 1, 2, 3, 1, 3), 90)
                .text("The Final Leg runs FROM the pattern TO the runway - draw it "
                        + "that way round. Aircraft descend along it to land.")
                .colored(PonderPalette.MEDIUM)
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.GREEN, "layout",
                util.select().fromTo(0, 1, 0, 9, 1, 10), 70);
        scene.overlay().showText(70)
                .text("Hold this block to see the layout traced in the world, so you "
                        + "can build a runway where you drew one.")
                .placeNearTarget();
        scene.idle(80);
    }

    private static void autopilot(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("autopilot", "Flying a schedule");
        scene.configureBasePlate(0, 0, 11);
        scene.showBasePlate();
        scene.idle(10);

        // The field it will use, then the aircraft standing on it.
        scene.world().showSection(util.select().fromTo(1, 1, 4, 9, 1, 6), Direction.DOWN);
        scene.idle(10);

        Selection aircraft = util.select().fromTo(2, 2, 4, 5, 3, 6);
        scene.world().showSection(aircraft, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutlineWithText(util.select().position(4, 3, 5), 80)
                .text("The Autopilot flies one aircraft. Place it on the craft with "
                        + "the arrow on top pointing along the fuselage.")
                .colored(PonderPalette.WHITE)
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("That arrow is how it knows which way the nose faces - a craft "
                        + "is just a pile of blocks otherwise.")
                .colored(PonderPalette.FAST)
                .pointAt(new Vec3(4.5, 3.5, 5.5))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showControls(new Vec3(4.5, 3.5, 5.5), Pointing.DOWN, 40).rightClick();
        scene.overlay().showText(80)
                .text("Build a schedule of stops, like a train's. Each stop waits for "
                        + "a timer, a player, or cargo to be loaded or unloaded.")
                .pointAt(new Vec3(4.5, 3.5, 5.5))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(70)
                .text("Choose Plane, Heli or Blimp. Planes need a runway; the other "
                        + "two lift straight off a pad.")
                .colored(PonderPalette.MEDIUM)
                .placeNearTarget();
        scene.idle(80);

        // Engaged: roll down the runway and climb away. Detaching the
        // aircraft from the world section is what lets it be moved at all -
        // Ponder can animate a section, but only once it is independent of
        // the structure it was loaded as part of.
        scene.overlay().showText(70)
                .text("Engage from the screen, or with a redstone signal - powered "
                        + "flies the saved schedule, unpowered stops it.")
                .colored(PonderPalette.RED)
                .placeNearTarget();
        scene.idle(40);

        ElementLink<WorldSectionElement> flying = scene.world().makeSectionIndependent(aircraft);
        scene.world().moveSection(flying, new Vec3(4, 0, 0), 40);   // takeoff roll
        scene.idle(40);
        scene.world().moveSection(flying, new Vec3(5, 4, 0), 50);   // rotate and climb out
        scene.idle(55);
    }

    private static void atc(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("atc", "Watching the traffic");
        scene.configureBasePlate(0, 0, 11);
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().fromTo(1, 1, 4, 9, 1, 6), Direction.DOWN);
        scene.idle(8);
        scene.world().showSection(util.select().fromTo(7, 1, 7, 8, 1, 10), Direction.DOWN);
        scene.idle(8);
        scene.world().showSection(util.select().fromTo(0, 1, 0, 4, 1, 3), Direction.DOWN);
        scene.idle(12);

        // The tower last, so it reads as overlooking what came before.
        scene.world().showSection(util.select().fromTo(1, 1, 9, 1, 3, 9), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showControls(new Vec3(1.5, 3.5, 9.5), Pointing.DOWN, 40).rightClick();
        scene.overlay().showText(80)
                .text("Air Traffic Control shows every airport and every aircraft "
                        + "under autopilot on one live map.")
                .pointAt(new Vec3(1.5, 3.5, 9.5))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("Drag to pan, scroll to zoom. The strip on the right lists each "
                        + "aircraft and what it is doing right now.")
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("Point a Create Display Link at this block, or at a station, to "
                        + "put the same traffic on a departures board.")
                .colored(PonderPalette.OUTPUT)
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showOutline(PonderPalette.GREEN, "field",
                util.select().fromTo(0, 1, 0, 9, 1, 10), 70);
        scene.overlay().showText(70)
                .text("Opening it also wakes aircraft parked at airports nobody is "
                        + "visiting, so their schedules pick up again.")
                .placeNearTarget();
        scene.idle(80);
    }
}
