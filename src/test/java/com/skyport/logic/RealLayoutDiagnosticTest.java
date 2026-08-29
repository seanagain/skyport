package com.skyport.logic;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a real saved airport off disk and reports whether it can actually
 * be taxied, rather than asking a player to work that out by watching an
 * aircraft not move.
 *
 * Skipped unless -Dskyport.layout=&lt;path to skyport_airports.dat&gt; is
 * given, so it never runs in a normal build - it is a diagnostic tool that
 * happens to be convenient to launch as a test, not a test of anything.
 *
 * Written because a playtest kept producing "the plane freezes near a node"
 * and there was no way to tell a routing dead end from a physics fault by
 * looking at the aircraft. The routing question has a definite answer and
 * the save file contains everything needed to compute it.
 */
class RealLayoutDiagnosticTest {

    @Test
    @EnabledIfSystemProperty(named = "skyport.layout", matches = ".+")
    void reportOnTheSavedAirports() throws Exception {
        Path file = Path.of(System.getProperty("skyport.layout"));
        CompoundTag root;
        try (var in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        CompoundTag data = root.contains("data") ? root.getCompound("data") : root;
        ListTag airports = data.getList("airports", Tag.TAG_COMPOUND);

        System.out.println("=== " + airports.size() + " airport(s) in " + file.getFileName() + " ===");

        for (int i = 0; i < airports.size(); i++) {
            AirportLayout layout = AirportLayout.load(airports.getCompound(i));
            System.out.println();
            System.out.println("--- " + layout.displayName() + " ---");

            for (Waypoint.Type type : Waypoint.Type.values()) {
                int size = layout.waypoints(type).size();
                if (size > 0) System.out.println("  " + type + ": " + size);
            }
            System.out.println("  gates: " + layout.gates().size()
                    + "  helipads: " + layout.helipads().size()
                    + "  taxiSpeed: " + layout.taxiSpeed());
            System.out.println("  departure runway drawn: " + layout.hasDepartureRunway());

            List<String> stranded = GroundNetwork.strandedGates(layout);
            System.out.println("  STRANDED GATES: " + (stranded.isEmpty() ? "none" : stranded));

            // The route every departure actually has to drive: gate to the
            // gate-end of the runway it will use. A gate that cannot reach it
            // is an aircraft that will sit at the stand forever.
            List<BlockPos> runway = layout.hasDepartureRunway()
                    ? layout.departureRunway() : layout.arrivalRunway();
            if (runway.isEmpty()) {
                System.out.println("  !! no runway - nothing can depart");
                continue;
            }
            BlockPos runwayEnd = runway.get(0);

            layout.gates().forEach((name, pos) -> {
                List<BlockPos> route = GroundNetwork.route(layout, pos, runwayEnd);
                System.out.println("  gate '" + name + "' at " + pos
                        + " -> runway " + runwayEnd
                        + " : " + (route.isEmpty() ? "NO ROUTE" : route.size() + " nodes"));
            });
        }
    }
}
