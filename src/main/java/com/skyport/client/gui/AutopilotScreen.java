package com.skyport.client.gui;

import com.skyport.data.AirportSummary;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import com.skyport.network.DisengageAutopilotPayload;
import com.skyport.network.EngageAutopilotPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The Autopilot's schedule editor, deliberately shaped like Create's train
 * schedules: an ordered list of stops, each with a departure condition, and
 * a loop toggle - players running Create already know how to read that.
 *
 * `airports` is a snapshot the server sent when the screen was requested
 * (see AutopilotBlockEntity#openDestinationPicker); this screen has no
 * server access of its own, it just reads what it was given and sends the
 * finished schedule back over EngageAutopilotPayload.
 *
 * Vanilla has no dropdown or list widget, so the row being edited is
 * selected with prev/next buttons and its fields are click-to-cycle - the
 * simplest thing that works without building a scrolling list from scratch.
 */
public class AutopilotScreen extends Screen {

    private static final int ROW_HEIGHT = 12;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int[] WAIT_PRESETS = { 0, 5, 10, 15, 30, 60, 120, 300 };

    private final BlockPos autopilotPos;
    private final List<AirportSummary> airports;
    private final FlightSchedule schedule = new FlightSchedule();

    /** Which stop the edit buttons act on. -1 when the schedule is empty. */
    private int selected = -1;

    private Button airportButton;
    private Button gateButton;
    private Button conditionButton;
    private Button waitButton;
    private Button loopButton;
    private Button altitudeButton;
    private Button engageButton;
    private Button removeButton;

    public AutopilotScreen(BlockPos autopilotPos, List<AirportSummary> airports) {
        super(Component.translatable("gui.skyport.autopilot.title"));
        this.autopilotPos = autopilotPos;
        this.airports = airports;
    }

    @Override
    protected void init() {
        int panelW = Math.min(260, width - 20);
        int left = (width - panelW) / 2;
        int listTop = 34;
        int top = listTop + MAX_VISIBLE_ROWS * ROW_HEIGHT + 6;
        int half = (panelW - 4) / 2;

        // --- stop selection + add/remove ---
        addRenderableWidget(Button.builder(Component.literal("<"), b -> select(selected - 1))
                .bounds(left, top, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> select(selected + 1))
                .bounds(left + 22, top, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.add_stop"), b -> addStop())
                .bounds(left + 44, top, panelW - 44 - 62, 20).build());
        removeButton = addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.remove_stop"), b -> removeStop())
                .bounds(left + panelW - 60, top, 60, 20).build());

        // --- the selected stop's fields ---
        airportButton = addRenderableWidget(Button.builder(airportLabel(), b -> cycleAirport())
                .bounds(left, top + 24, half, 20).build());
        gateButton = addRenderableWidget(Button.builder(gateLabel(), b -> cycleGate())
                .bounds(left + half + 4, top + 24, half, 20).build());
        conditionButton = addRenderableWidget(Button.builder(conditionLabel(), b -> cycleCondition())
                .bounds(left, top + 48, half, 20).build());
        waitButton = addRenderableWidget(Button.builder(waitLabel(), b -> cycleWait())
                .bounds(left + half + 4, top + 48, half, 20).build());

        // --- schedule-wide ---
        loopButton = addRenderableWidget(Button.builder(loopLabel(), b -> toggleLoop())
                .bounds(left, top + 72, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.disengage"),
                        b -> {
                            PacketDistributor.sendToServer(new DisengageAutopilotPayload(autopilotPos));
                            onClose();
                        })
                .bounds(left + half + 4, top + 72, half, 20).build());

        // Cruise altitude: the one setting that's about the flight rather
        // than about a particular stop.
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustCruiseAltitude(-10))
                .bounds(left, top + 96, 24, 20).build());
        altitudeButton = addRenderableWidget(Button.builder(altitudeLabel(), b -> { })
                .bounds(left + 26, top + 96, panelW - 52, 20).build());
        altitudeButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustCruiseAltitude(10))
                .bounds(left + panelW - 24, top + 96, 24, 20).build());

        engageButton = addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.engage"),
                        b -> engage())
                .bounds(left, top + 120, panelW, 20).build());

        // Seed with one stop so there's something to edit immediately - an
        // empty schedule with every control disabled is a confusing landing.
        if (schedule.isEmpty() && !airports.isEmpty()) addStop();
        refresh();
    }

    private boolean hasSelection() {
        return selected >= 0 && selected < schedule.entries().size();
    }

    private ScheduleEntry entry() {
        return schedule.entries().get(selected);
    }

    private void replaceEntry(ScheduleEntry updated) {
        schedule.entries().set(selected, updated);
        refresh();
    }

    private void select(int index) {
        int size = schedule.entries().size();
        if (size == 0) {
            selected = -1;
        } else {
            selected = Math.floorMod(index, size);
        }
        refresh();
    }

    private void addStop() {
        if (airports.isEmpty()) return;
        AirportSummary airport = airports.get(0);
        String gate = airport.gateNames().isEmpty() ? "" : airport.gateNames().get(0);
        schedule.entries().add(new ScheduleEntry(airport.id(), gate, ScheduleEntry.WaitCondition.TIMER, 10));
        select(schedule.entries().size() - 1);
    }

    private void removeStop() {
        if (!hasSelection()) return;
        schedule.entries().remove(selected);
        select(selected - 1);
    }

    private void cycleAirport() {
        if (!hasSelection() || airports.isEmpty()) return;
        int current = indexOfAirport(entry().airportId());
        AirportSummary next = airports.get(Math.floorMod(current + 1, airports.size()));
        String gate = next.gateNames().isEmpty() ? "" : next.gateNames().get(0);
        replaceEntry(new ScheduleEntry(next.id(), gate, entry().condition(), entry().waitSeconds()));
    }

    private void cycleGate() {
        if (!hasSelection()) return;
        List<String> gates = airportOf(entry().airportId()).gateNames();
        if (gates.isEmpty()) return;
        int current = gates.indexOf(entry().gateName());
        String next = gates.get(Math.floorMod(current + 1, gates.size()));
        replaceEntry(new ScheduleEntry(entry().airportId(), next, entry().condition(), entry().waitSeconds()));
    }

    private void cycleCondition() {
        if (!hasSelection()) return;
        var values = ScheduleEntry.WaitCondition.values();
        var next = values[(entry().condition().ordinal() + 1) % values.length];
        replaceEntry(new ScheduleEntry(entry().airportId(), entry().gateName(), next, entry().waitSeconds()));
    }

    private void cycleWait() {
        if (!hasSelection()) return;
        int current = entry().waitSeconds();
        int next = WAIT_PRESETS[0];
        for (int preset : WAIT_PRESETS) {
            if (preset > current) { next = preset; break; }
        }
        replaceEntry(new ScheduleEntry(entry().airportId(), entry().gateName(), entry().condition(), next));
    }

    private void toggleLoop() {
        schedule.setLoop(!schedule.loop());
        refresh();
    }

    private int indexOfAirport(java.util.UUID id) {
        for (int i = 0; i < airports.size(); i++) {
            if (airports.get(i).id().equals(id)) return i;
        }
        return 0;
    }

    private AirportSummary airportOf(java.util.UUID id) {
        return airports.get(indexOfAirport(id));
    }

    private Component airportLabel() {
        if (!hasSelection()) return Component.translatable("gui.skyport.autopilot.no_airports");
        return Component.literal(airportOf(entry().airportId()).displayName());
    }

    private Component gateLabel() {
        if (!hasSelection()) return Component.empty();
        String gate = entry().gateName();
        return gate.isEmpty()
                ? Component.translatable("gui.skyport.autopilot.no_gates")
                : Component.literal(gate);
    }

    private Component conditionLabel() {
        if (!hasSelection()) return Component.empty();
        return Component.literal(switch (entry().condition()) {
            case TIMER -> "Wait: timer";
            case PLAYER -> "Wait: player";
            case CARGO -> "Wait: cargo";
        });
    }

    private Component waitLabel() {
        if (!hasSelection()) return Component.empty();
        return Component.literal(entry().waitSeconds() + "s");
    }

    private Component loopLabel() {
        return Component.literal(schedule.loop() ? "Loop: on" : "Loop: off");
    }

    private Component altitudeLabel() {
        return Component.literal("Cruise altitude: Y " + schedule.cruiseAltitude());
    }

    private void adjustCruiseAltitude(int delta) {
        schedule.setCruiseAltitude(Math.max(0, Math.min(400, schedule.cruiseAltitude() + delta)));
        refresh();
    }

    /** Re-syncs every control with the currently selected stop. */
    private void refresh() {
        boolean sel = hasSelection();
        airportButton.setMessage(airportLabel());
        gateButton.setMessage(gateLabel());
        conditionButton.setMessage(conditionLabel());
        waitButton.setMessage(waitLabel());
        loopButton.setMessage(loopLabel());
        altitudeButton.setMessage(altitudeLabel());

        airportButton.active = sel && airports.size() > 1;
        gateButton.active = sel && airportOf(entry().airportId()).gateNames().size() > 1;
        conditionButton.active = sel;
        // A player-boarding stop has no timer to set; cargo currently falls
        // back to the timer, so it still does.
        waitButton.active = sel && entry().condition() != ScheduleEntry.WaitCondition.PLAYER;
        removeButton.active = sel;
        engageButton.active = !schedule.isEmpty() && schedule.entries().stream().noneMatch(e -> e.gateName().isEmpty());
    }

    private void engage() {
        if (schedule.isEmpty()) return;
        PacketDistributor.sendToServer(new EngageAutopilotPayload(autopilotPos, schedule));
        onClose();
    }

    /** Vanilla's default blurs the world behind the GUI; a flat fill matches
     *  the airport screens and keeps text crisp. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Screen#render draws the background itself, so anything of ours has
        // to come after it or it gets painted over.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int panelW = Math.min(260, width - 20);
        int left = (width - panelW) / 2;
        guiGraphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        if (airports.isEmpty()) {
            guiGraphics.drawCenteredString(font,
                    "Draw and save a layout at an Airport Station first.",
                    width / 2, 22, 0xFFAAAAAA);
            return;
        }

        // The schedule itself, newest-window-first if it's outgrown the box.
        int listTop = 34;
        List<ScheduleEntry> entries = schedule.entries();
        int first = Math.max(0, Math.min(selected - MAX_VISIBLE_ROWS + 1, entries.size() - MAX_VISIBLE_ROWS));
        if (first < 0) first = 0;
        for (int i = first; i < entries.size() && i - first < MAX_VISIBLE_ROWS; i++) {
            ScheduleEntry e = entries.get(i);
            int y = listTop + (i - first) * ROW_HEIGHT;
            boolean isSelected = i == selected;
            if (isSelected) guiGraphics.fill(left - 2, y - 1, left + panelW + 2, y + ROW_HEIGHT - 2, 0xFF3A3A3A);
            String text = (i + 1) + ". " + airportOf(e.airportId()).displayName()
                    + " / " + (e.gateName().isEmpty() ? "(no gate)" : e.gateName())
                    + "  [" + shortCondition(e) + "]";
            guiGraphics.drawString(font, text, left, y, isSelected ? 0xFFFFFFFF : 0xFFAAAAAA);
        }

        if (entries.isEmpty()) {
            guiGraphics.drawString(font, "No stops yet - hit Add Stop.", left, listTop, 0xFFAAAAAA);
        } else if (schedule.loop()) {
            int y = listTop + Math.min(entries.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT;
            guiGraphics.drawString(font, "loops back to stop 1", left, y, 0xFF7A9E7A);
        }
    }

    private static String shortCondition(ScheduleEntry e) {
        return switch (e.condition()) {
            case TIMER -> e.waitSeconds() + "s";
            case PLAYER -> "player";
            case CARGO -> "cargo";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
