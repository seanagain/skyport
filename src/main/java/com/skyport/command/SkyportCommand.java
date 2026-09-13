package com.skyport.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.skyport.Skyport;
import com.skyport.blockentity.AutopilotBlockEntity;
import com.skyport.data.AirportLayout;
import com.skyport.data.AirportRegistry;
import com.skyport.data.FlightSchedule;
import com.skyport.data.VorBeacon;
import com.skyport.logic.FlightPlanReport;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Admin commands for looking at aircraft, and for getting rid of one.
 *
 * These exist for the case the screens cannot reach: an aircraft that is on
 * the tower's roster but not really there any more. A contraption picked up
 * with a wrench or a container takes its blocks away without its autopilot
 * ever hearing about it, and what is left is an entry on the map holding
 * clearances that nothing will ever release. Until now the only cure was
 * editing the save.
 *
 * Level 2 throughout, info included. What it prints - schedules, positions,
 * ids - is exactly what a player would need to plan around somebody else's
 * aircraft, and the tower already shows everyone what is public about it.
 */
@EventBusSubscriber(modid = Skyport.MOD_ID)
public final class SkyportCommand {

    private SkyportCommand() { }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skyport")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list")
                        .executes(SkyportCommand::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("aircraft", StringArgumentType.greedyString())
                                .suggests(SkyportCommand::suggestAircraft)
                                .executes(context -> info(context,
                                        StringArgumentType.getString(context, "aircraft")))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("aircraft", StringArgumentType.greedyString())
                                .suggests(SkyportCommand::suggestAircraft)
                                .executes(context -> forget(context,
                                        StringArgumentType.getString(context, "aircraft"))))));
    }

    // ---- list ----

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AirportRegistry registry = registry(source);
        long now = source.getServer().overworld().getGameTime();

        List<AirportRegistry.KnownAircraft> all = new ArrayList<>(registry.known());
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No aircraft are engaged."), false);
            return 0;
        }
        all.sort(java.util.Comparator.comparing(SkyportCommand::label, String.CASE_INSENSITIVE_ORDER));

        source.sendSuccess(() -> Component.literal(all.size() + " aircraft:").withStyle(ChatFormatting.AQUA), false);
        for (AirportRegistry.KnownAircraft aircraft : all) {
            String line = "  " + label(aircraft)
                    + "  " + (registry.isAwake(aircraft.planeId(), now) ? pretty(aircraft.state()) : "asleep")
                    + "  at " + aircraft.position().getX() + ", " + aircraft.position().getZ()
                    + "  -> " + aircraft.destination();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    // ---- info ----

    private static int info(CommandContext<CommandSourceStack> context, String query) {
        CommandSourceStack source = context.getSource();
        AirportRegistry registry = registry(source);
        AirportRegistry.KnownAircraft aircraft = resolve(source, registry, query).orElse(null);
        if (aircraft == null) return 0;

        MinecraftServer server = source.getServer();
        long now = server.overworld().getGameTime();
        boolean awake = registry.isAwake(aircraft.planeId(), now);
        AutopilotBlockEntity autopilot = AutopilotBlockEntity.loaded(aircraft.planeId());

        say(source, label(aircraft), ChatFormatting.AQUA);
        say(source, "  aircraft id   " + aircraft.planeId());
        say(source, "  sable id      " + sableId(autopilot));
        say(source, "  state         " + pretty(aircraft.state()) + (awake ? "" : "  (asleep - not ticking)"));
        say(source, "  where         " + aircraft.dimension()
                + "  " + aircraft.position().getX() + ", " + aircraft.position().getY()
                + ", " + aircraft.position().getZ());
        say(source, "  destination   " + aircraft.destination());

        if (autopilot == null) {
            // Everything below lives on the block entity, and its chunk is
            // not loaded. Say which half is missing rather than printing
            // blanks that read like an aircraft with no schedule.
            say(source, "  Its chunk is not loaded, so the flight plan cannot be read from here.");
            say(source, "  Wake it from the tower, or fly out to it, and ask again.");
            return 1;
        }

        FlightSchedule schedule = autopilot.schedule();
        say(source, "  plan          " + FlightPlanReport.summary(schedule));
        if (autopilot.unattendedSecondsLeft() > 0) {
            say(source, "  unattended    " + autopilot.unattendedSecondsLeft() + "s left");
        }
        if (autopilot.fuelRemaining() > 0) {
            say(source, "  fuel          " + String.format(java.util.Locale.ROOT, "%.1f", autopilot.fuelRemaining()));
        }
        ServerLevel overworld = server.overworld();
        for (String line : FlightPlanReport.lines(schedule, autopilot.currentStopIndex(),
                id -> AirportRegistry.get(overworld).byId(id).map(AirportLayout::displayName).orElse(null),
                id -> AirportRegistry.get(overworld).vorById(id).map(VorBeacon::name).orElse(null))) {
            say(source, "  " + line);
        }
        return 1;
    }

    // ---- forget ----

    private static int forget(CommandContext<CommandSourceStack> context, String query) {
        CommandSourceStack source = context.getSource();
        AirportRegistry registry = registry(source);
        AirportRegistry.KnownAircraft aircraft = resolve(source, registry, query).orElse(null);
        if (aircraft == null) return 0;

        String name = label(aircraft);
        AutopilotBlockEntity autopilot = AutopilotBlockEntity.loaded(aircraft.planeId());
        // Disengaging first is what makes this stick for an aircraft that
        // still exists: it hands back the runway and taxiway it was holding,
        // drops its chunks, and stops it writing itself back onto the roster
        // on its next tick.
        if (autopilot != null) autopilot.disengage();
        registry.clearAirborne(aircraft.planeId());
        registry.forget(aircraft.planeId());

        say(source, "Removed " + name + " from the tower.", ChatFormatting.YELLOW);
        if (autopilot != null) {
            say(source, "  Its autopilot was loaded, so it has been disengaged and its clearances released.");
            say(source, "  The block is still on the aircraft - engaging it again puts it back on the map.");
        } else {
            say(source, "  Its autopilot was not loaded. If the aircraft does still exist and is still");
            say(source, "  engaged, it will reappear the next time its chunk loads - break or disengage");
            say(source, "  the Autopilot block to be rid of it for good.");
        }
        return 1;
    }

    // ---- shared ----

    /**
     * Find the aircraft an admin meant.
     *
     * By id first, because that is what the other two commands print and what
     * survives two aircraft sharing a name. Callsigns are matched case
     * insensitively, and an ambiguous one is refused with the ids rather than
     * guessed at - "forget" is not a command to run on the wrong aeroplane.
     */
    private static Optional<AirportRegistry.KnownAircraft> resolve(
            CommandSourceStack source, AirportRegistry registry, String query) {
        String wanted = query.trim();
        try {
            UUID id = UUID.fromString(wanted);
            Optional<AirportRegistry.KnownAircraft> byId = registry.knownById(id);
            if (byId.isEmpty()) say(source, "No aircraft with id " + id + ".", ChatFormatting.RED);
            return byId;
        } catch (IllegalArgumentException notAnId) {
            // A callsign then.
        }

        List<AirportRegistry.KnownAircraft> matches = registry.known().stream()
                .filter(aircraft -> label(aircraft).equalsIgnoreCase(wanted))
                .toList();
        if (matches.isEmpty()) {
            say(source, "No aircraft called " + wanted + ". Try /skyport list.", ChatFormatting.RED);
            return Optional.empty();
        }
        if (matches.size() > 1) {
            say(source, matches.size() + " aircraft are called " + wanted + " - name one by id:", ChatFormatting.RED);
            for (AirportRegistry.KnownAircraft aircraft : matches) {
                say(source, "  " + aircraft.planeId());
            }
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static CompletableFuture<Suggestions> suggestAircraft(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        AirportRegistry registry = registry(context.getSource());
        return SharedSuggestionProvider.suggest(registry.known().stream().map(SkyportCommand::label), builder);
    }

    /** What to call an aircraft: its callsign, or its id when it has none. */
    private static String label(AirportRegistry.KnownAircraft aircraft) {
        return aircraft.callsign() == null || aircraft.callsign().isBlank()
                ? aircraft.planeId().toString()
                : aircraft.callsign();
    }

    private static String sableId(AutopilotBlockEntity autopilot) {
        if (autopilot == null) return "(not loaded)";
        UUID id = autopilot.subLevelId();
        return id == null ? "(not on an assembled craft)" : id.toString();
    }

    private static String pretty(String state) {
        return state.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    private static AirportRegistry registry(CommandSourceStack source) {
        return AirportRegistry.get(source.getServer().overworld());
    }

    private static void say(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void say(CommandSourceStack source, String line, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
