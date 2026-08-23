package com.skyport.compat;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.skyport.blockentity.AirportStationBlockEntity;
import com.skyport.blockentity.AtcBlockEntity;
import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.TrafficReport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A departures board: point a Create Display Link at an Airport Station or
 * an ATC block and write the traffic onto a sign, nixie tubes or a display
 * board.
 *
 * This is the piece that makes an airport feel like one. Everything else
 * this mod knows about aircraft lives in a GUI you have to walk up to and
 * open; a board on the terminal wall shows it to everyone, in the same way
 * Create players already display train schedules and stock levels.
 *
 * Linked to a station it lists that airport's own traffic - what is inbound
 * and what is parked there. Linked to the ATC block it lists everything
 * flying anywhere, which is what a tower would actually want.
 */
public class FlightBoardDisplaySource extends DisplaySource {

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.level() instanceof ServerLevel serverLevel)) return EMPTY;

        BlockEntity source = context.getSourceBlockEntity();
        AirportRegistry registry = AirportRegistry.get(serverLevel);

        List<TrafficReport> traffic = new ArrayList<>(registry.allTraffic(serverLevel.getGameTime()));
        // Newest information first is useless on a board; a stable order is
        // what makes one readable at a glance.
        traffic.sort(Comparator.comparing(TrafficReport::callsign));

        if (source instanceof AirportStationBlockEntity station) {
            UUID airportId = station.airportId();
            if (airportId == null) return List.of(line("No airport drawn", ChatFormatting.GRAY));
            AirportLayout layout = registry.byId(airportId).orElse(null);
            if (layout == null) return List.of(line("No airport drawn", ChatFormatting.GRAY));
            // Airport board: gates, since the airport is a given here.
            return board(layout.displayName(), forAirport(traffic, layout), stats, false);
        }

        if (source instanceof AtcBlockEntity) {
            // Tower board: airports, since that is what differs.
            return board("All traffic", traffic, stats, true);
        }

        return EMPTY;
    }

    /** Only aircraft heading for, or sitting at, this airport. */
    private static List<TrafficReport> forAirport(List<TrafficReport> all, AirportLayout layout) {
        List<TrafficReport> mine = new ArrayList<>();
        for (TrafficReport report : all) {
            if (report.destination().startsWith(layout.displayName())) mine.add(report);
        }
        return mine;
    }

    /**
     * Where an aircraft is going, worded for whichever board is asking.
     *
     * At an airport the airport name is redundant - everything on that board
     * is coming here - so it shows the gate, which is what someone standing
     * in the terminal actually wants. The tower board shows the airport,
     * since that is the part that varies.
     */
    private static String destination(TrafficReport report, boolean showAirport) {
        String where = report.destination();
        int split = where.indexOf(" / ");
        if (split < 0) return where;
        return showAirport ? where.substring(0, split) : where.substring(split + 3);
    }

    private static List<MutableComponent> board(String heading, List<TrafficReport> traffic,
                                                DisplayTargetStats stats, boolean showAirport) {
        List<MutableComponent> lines = new ArrayList<>();
        lines.add(line(heading, ChatFormatting.WHITE));

        if (traffic.isEmpty()) {
            lines.add(line("No movements", ChatFormatting.DARK_GRAY));
            return lines;
        }

        // Leave a line for the heading, and never write more rows than the
        // target can show - a board that overflows just loses the bottom.
        int room = Math.max(1, stats.maxRows() - 1);
        for (int i = 0; i < traffic.size() && i < room; i++) {
            TrafficReport report = traffic.get(i);
            lines.add(Component.literal(report.callsign() + "  " + destination(report, showAirport)
                            + "  " + phase(report.state()))
                    .withStyle(report.airborne() ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        }
        if (traffic.size() > room) {
            lines.add(line("+" + (traffic.size() - room) + " more", ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** Flight states as a board would word them, not as the enum spells them. */
    private static String phase(String state) {
        return switch (state) {
            case "WAITING" -> "At gate";
            case "PUSHBACK", "TAXI_OUT" -> "Departing";
            case "TAKEOFF_ROLL", "CLIMB", "VERTICAL_CLIMB" -> "Departed";
            case "CRUISE" -> "En route";
            case "HOLDING", "HOVERING" -> "Holding";
            case "APPROACH", "VERTICAL_DESCENT" -> "Landing";
            case "TAXI_IN" -> "Arrived";
            default -> state.charAt(0) + state.substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static MutableComponent line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    /** Boards should keep up with aircraft without a redstone pulse each
     *  time; roughly once a second is enough for something people glance at. */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}
