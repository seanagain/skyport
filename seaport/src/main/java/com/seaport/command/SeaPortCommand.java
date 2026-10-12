package com.seaport.command;

import com.mojang.brigadier.Command;
import com.portcore.survey.CellSurvey;
import com.portcore.survey.ChunkSummary;
import com.portcore.survey.ChunkSurveyor;
import com.portcore.survey.SurveyStore;
import com.seaport.SeaPort;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Looking at what the surveyor has done.
 *
 * A survey that cannot be inspected is a survey nobody can trust. It runs in
 * the background, costs disk, and its whole output is a number per cell that
 * never appears on screen - so without this, "is it working" has no answer
 * short of reading the save file, and "is it finished" has none at all.
 *
 * Operators only. It reports on the world rather than changing it, but it is
 * a diagnostic rather than something a player should be reading.
 */
@EventBusSubscriber(modid = SeaPort.MOD_ID)
public final class SeaPortCommand {

    private SeaPortCommand() { }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("seaport")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("survey")
                        .executes(context -> reportProgress(context.getSource())))
                .then(Commands.literal("depth")
                        .executes(context -> reportDepth(context.getSource()))));
    }

    private static int reportProgress(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ChunkSurveyor surveyor = ChunkSurveyor.existing(level);
        if (surveyor == null) {
            say(source, "Nothing is surveying this dimension yet.", ChatFormatting.GRAY);
            return 0;
        }

        ChunkSurveyor.Progress progress = surveyor.progress();
        say(source, "Survey of " + level.dimension().location(), ChatFormatting.AQUA);
        say(source, "  charted      " + progress.chunksKnown() + " chunks ("
                + progress.chunksKnown() * ChunkSummary.CELLS_PER_CHUNK + " cells)", null);
        say(source, "  waiting      " + progress.backlog() + " chunks"
                + (progress.backlog() == 0 && progress.enumerated() ? "  (caught up)" : ""), null);
        say(source, "  reading      " + progress.inFlight() + " now", null);
        say(source, "  surveyed     " + progress.fromMemory() + " as they loaded, "
                + progress.fromDisk() + " from disk", null);

        // Both of these are normal in small numbers and mean something in
        // large ones, so they are shown rather than hidden.
        if (progress.skipped() > 0) {
            say(source, "  skipped      " + progress.skipped()
                    + "  (not finished generating, or unreadable)", ChatFormatting.GRAY);
        }
        if (progress.dropped() > 0) {
            say(source, "  dropped      " + progress.dropped()
                    + "  (backlog full - they come back when something asks)", ChatFormatting.GOLD);
        }
        if (!progress.enumerated()) {
            say(source, "  The region listing has not run for this dimension.", ChatFormatting.GOLD);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * What the chart thinks is under your feet.
     *
     * The single most useful thing to be able to ask, because it is checkable:
     * stand somewhere, read the depth, and look down. A survey that is subtly
     * wrong - a bad bit-stride, a forgotten world floor - produces numbers
     * that are plausible everywhere and correct nowhere, and this is how that
     * gets caught.
     */
    private static int reportDepth(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        int blockX = (int) Math.floor(at.x);
        int blockZ = (int) Math.floor(at.z);

        SurveyStore store = SurveyStore.get(level);
        CellSurvey cell = store.at(blockX, blockZ);
        int seaLevel = level.getSeaLevel();

        say(source, "At " + blockX + ", " + blockZ
                + "  (cell " + ChunkSummary.toCell(blockX) + ", " + ChunkSummary.toCell(blockZ) + ")",
                ChatFormatting.AQUA);

        if (!cell.known()) {
            say(source, "  Not surveyed yet.", ChatFormatting.GRAY);
            return 0;
        }

        say(source, "  floor        Y " + cell.floor()
                + "   (the highest ground in this 4-block cell)", null);
        say(source, "  surface      Y " + cell.surface(), null);
        say(source, "  sea level    Y " + seaLevel, null);
        if (cell.isLand(seaLevel)) {
            say(source, "  land", ChatFormatting.GREEN);
        } else {
            say(source, "  water        " + cell.waterDepth(seaLevel) + " blocks deep",
                    ChatFormatting.AQUA);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void say(CommandSourceStack source, String line, ChatFormatting colour) {
        Component message = colour == null
                ? Component.literal(line)
                : Component.literal(line).withStyle(colour);
        source.sendSuccess(() -> message, false);
    }
}
