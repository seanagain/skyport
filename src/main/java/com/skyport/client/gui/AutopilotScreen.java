package com.skyport.client.gui;

import com.skyport.data.AirportSummary;
import com.skyport.data.FlightSchedule;
import com.skyport.data.ScheduleEntry;
import com.skyport.data.VorBeacon;
import com.skyport.network.DisengageAutopilotPayload;
import com.skyport.network.EngageAutopilotPayload;
import com.skyport.network.SaveSchedulePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The Autopilot's schedule editor, deliberately shaped like Create's train
 * schedules: an ordered list of stops, each with a departure condition, and
 * a loop toggle - players running Create already know how to read that.
 *
 * `airports` and `vors` are a snapshot the server sent when the screen was
 * requested (see AutopilotBlockEntity#openDestinationPicker); this screen has
 * no server access of its own, it just reads what it was given and sends the
 * finished schedule back over EngageAutopilotPayload.
 *
 * Vanilla has no dropdown or list widget, so the row being edited is
 * selected with prev/next buttons and its fields are click-to-cycle - the
 * simplest thing that works without building a scrolling list from scratch.
 *
 * A stop can also be a VOR beacon, flown over on the way to the next airport
 * with no gate and no wait. VORs come round on the same destination button
 * as the airports, after them, rather than having a control of their own:
 * this panel is already as tall as it can get without clipping at a large
 * GUI scale.
 */
public class AutopilotScreen extends Screen {

    private static final int ROW_HEIGHT = 12;
    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int[] WAIT_PRESETS = { 0, 5, 10, 15, 30, 60, 120, 300 };
    /** Top of the schedule list. Shared by init and render - they drifted
     *  apart once already when the name field pushed everything down. */
    private static final int LIST_TOP = 46;

    private final BlockPos autopilotPos;
    private final List<AirportSummary> airports;
    /** Every VOR beacon, sorted by name on the server. */
    private final List<VorBeacon> vors;
    private final FlightSchedule schedule;

    /** Which stop the edit buttons act on. -1 when the schedule is empty. */
    private int selected = -1;

    private Button airportButton;
    private Button gateButton;
    private Button conditionButton;
    private Button waitButton;
    private Button loopButton;
    private Button craftButton;
    private Button altitudeButton;
    private Button speedButton;
    private Button engageButton;
    private Button removeButton;
    private EditBox nameBox;

    public AutopilotScreen(BlockPos autopilotPos, List<AirportSummary> airports, List<VorBeacon> vors,
                           FlightSchedule schedule) {
        super(Component.translatable("gui.skyport.autopilot.title"));
        this.autopilotPos = autopilotPos;
        this.airports = airports;
        this.vors = vors;
        // The block's existing schedule, so reopening shows the route you set
        // rather than a blank one.
        this.schedule = schedule;
        this.selected = schedule.entries().isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        int panelW = Math.min(260, width - 20);
        int left = (width - panelW) / 2;
        int listTop = LIST_TOP;
        int top = listTop + MAX_VISIBLE_ROWS * ROW_HEIGHT + 6;
        int half = (panelW - 4) / 2;

        // Aircraft name, above the schedule - it identifies the machine
        // rather than the route, and it's what shows on the tower's map.
        nameBox = addRenderableWidget(new EditBox(font, left, 22, panelW, 18,
                Component.literal("Aircraft name")));
        nameBox.setValue(schedule.craftName());
        nameBox.setMaxLength(24);
        nameBox.setHint(Component.literal("Aircraft name (optional)"));
        nameBox.setResponder(schedule::setCraftName);

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
        // The only way to a VOR is round this button, so say so where the
        // cursor already is rather than leaving it to be discovered.
        airportButton.setTooltip(Tooltip.create(Component.literal(
                "Where this stop goes. Cycles through the airports, then the VOR beacons - "
                        + "a VOR is flown over on the way to the next airport, without landing.")));
        gateButton = addRenderableWidget(Button.builder(gateLabel(), b -> cycleGate())
                .bounds(left + half + 4, top + 24, half, 20).build());
        conditionButton = addRenderableWidget(Button.builder(conditionLabel(), b -> cycleCondition())
                .bounds(left, top + 48, half, 20).build());
        waitButton = addRenderableWidget(Button.builder(waitLabel(), b -> cycleWait())
                .bounds(left + half + 4, top + 48, half, 20).build());

        // --- schedule-wide ---
        int third = (panelW - 8) / 3;
        craftButton = addRenderableWidget(Button.builder(craftLabel(), b -> cycleCraftType())
                .bounds(left, top + 72, third, 20).build());
        loopButton = addRenderableWidget(Button.builder(loopLabel(), b -> toggleLoop())
                .bounds(left + third + 4, top + 72, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.disengage"),
                        b -> {
                            PacketDistributor.sendToServer(new DisengageAutopilotPayload(autopilotPos));
                            onClose();
                        })
                .bounds(left + (third + 4) * 2, top + 72, panelW - (third + 4) * 2, 20).build());

        // Cruise altitude and speed: the settings that describe the flight
        // rather than a particular stop.
        int valueW = half - 44;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustCruiseAltitude(-10))
                .bounds(left, top + 96, 20, 20).build());
        altitudeButton = addRenderableWidget(Button.builder(altitudeLabel(), b -> { })
                .bounds(left + 22, top + 96, valueW, 20).build());
        altitudeButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustCruiseAltitude(10))
                .bounds(left + 22 + valueW + 2, top + 96, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustCruiseSpeed(-4))
                .bounds(left + half + 4, top + 96, 20, 20).build());
        speedButton = addRenderableWidget(Button.builder(speedLabel(), b -> { })
                .bounds(left + half + 26, top + 96, valueW, 20).build());
        speedButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustCruiseSpeed(4))
                .bounds(left + half + 26 + valueW + 2, top + 96, 20, 20).build());

        // Shares the row with Engage rather than adding one below it, so the
        // panel does not grow a line taller and start clipping off the
        // bottom of a small window.
        int lockW = 80;
        engageButton = addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.engage"),
                        b -> engage())
                .bounds(left, top + 120, panelW - lockW - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.passcode.button"),
                        b -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.skyport.network.LockRequestPayload(autopilotPos)))
                .bounds(left + panelW - lockW, top + 120, lockW, 20).build());

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
        List<String> stops = stopsAt(airport);
        String gate = stops.isEmpty() ? "" : stops.get(0);
        schedule.entries().add(new ScheduleEntry(airport.id(), gate, ScheduleEntry.WaitCondition.TIMER, 10));
        select(schedule.entries().size() - 1);
    }

    private void removeStop() {
        if (!hasSelection()) return;
        schedule.entries().remove(selected);
        select(selected - 1);
    }

    /** Everywhere a stop can name: the airports, then the VORs. */
    private int destinationCount() {
        return airports.size() + vors.size();
    }

    /** Where a stop sits in that combined cycle, or -1 if what it names has
     *  gone - broken since the stop was added. */
    private int destinationIndexOf(ScheduleEntry stop) {
        if (stop.isVor()) {
            for (int i = 0; i < vors.size(); i++) {
                if (vors.get(i).id().equals(stop.vorId())) return airports.size() + i;
            }
            return -1;
        }
        for (int i = 0; i < airports.size(); i++) {
            if (airports.get(i).id().equals(stop.airportId())) return i;
        }
        return -1;
    }

    private void cycleAirport() {
        if (!hasSelection() || destinationCount() == 0) return;
        replaceEntry(stopFor(Math.floorMod(destinationIndexOf(entry()) + 1, destinationCount())));
    }

    /**
     * The stop at a position in the destination cycle.
     *
     * Moving between airports keeps what the stop was waiting for, as it
     * always did. Coming back to an airport from a VOR there is nothing to
     * keep, so it starts from the same default as a new stop.
     */
    private ScheduleEntry stopFor(int index) {
        if (index >= airports.size()) return ScheduleEntry.vor(vors.get(index - airports.size()).id());
        AirportSummary airport = airports.get(index);
        List<String> stops = stopsAt(airport);
        String gate = stops.isEmpty() ? "" : stops.get(0);
        ScheduleEntry current = entry();
        return current.isVor()
                ? new ScheduleEntry(airport.id(), gate, ScheduleEntry.WaitCondition.TIMER, 10)
                : new ScheduleEntry(airport.id(), gate, current.condition(), current.waitSeconds());
    }

    private void cycleGate() {
        if (!hasSelection() || entry().isVor()) return;
        AirportSummary airport = airportOf(entry().airportId());
        if (airport == null) return;
        List<String> gates = stopsAt(airport);
        if (gates.isEmpty()) return;
        int current = gates.indexOf(entry().gateName());
        String next = gates.get(Math.floorMod(current + 1, gates.size()));
        replaceEntry(new ScheduleEntry(entry().airportId(), next, entry().condition(), entry().waitSeconds()));
    }

    private void cycleCondition() {
        if (!hasSelection() || entry().isVor()) return;
        var values = ScheduleEntry.WaitCondition.values();
        var next = values[(entry().condition().ordinal() + 1) % values.length];
        replaceEntry(new ScheduleEntry(entry().airportId(), entry().gateName(), next, entry().waitSeconds()));
    }

    private void cycleWait() {
        if (!hasSelection() || entry().isVor()) return;
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

    @Nullable
    private AirportSummary airportOf(@Nullable UUID id) {
        if (id == null) return null;
        for (AirportSummary airport : airports) {
            if (airport.id().equals(id)) return airport;
        }
        return null;
    }

    @Nullable
    private VorBeacon vorOf(@Nullable UUID id) {
        if (id == null) return null;
        for (VorBeacon vor : vors) {
            if (vor.id().equals(id)) return vor;
        }
        return null;
    }

    /** What a stop is called - or a placeholder for somewhere broken since the
     *  stop was added, rather than quietly showing a different airport's name. */
    private String stopName(ScheduleEntry stop) {
        if (stop.isVor()) {
            VorBeacon vor = vorOf(stop.vorId());
            return vor == null ? "(removed VOR)" : vor.name();
        }
        AirportSummary airport = airportOf(stop.airportId());
        return airport == null ? "(removed airport)" : airport.displayName();
    }

    private Component airportLabel() {
        if (!hasSelection()) return Component.translatable("gui.skyport.autopilot.no_airports");
        return Component.literal(entry().isVor() ? "VOR: " + stopName(entry()) : stopName(entry()));
    }

    private Component gateLabel() {
        if (!hasSelection()) return Component.empty();
        if (entry().isVor()) return Component.literal("Fly over");
        String gate = entry().gateName();
        return gate.isEmpty()
                ? Component.literal(schedule.craftType().isVertical() ? "No pads here" : "No gates here")
                : Component.literal(gate);
    }

    private Component conditionLabel() {
        if (!hasSelection()) return Component.empty();
        if (entry().isVor()) return Component.literal("No wait");
        return Component.literal(switch (entry().condition()) {
            case TIMER -> "Wait: timer";
            case PLAYER -> "Wait: player";
            case CARGO_LOADED -> "Wait: loaded";
            case CARGO_EMPTY -> "Wait: emptied";
        });
    }

    private Component waitLabel() {
        if (!hasSelection()) return Component.empty();
        if (entry().isVor()) return Component.literal("-");
        return Component.literal(entry().waitSeconds() + "s");
    }

    private Component loopLabel() {
        return Component.literal(schedule.loop() ? "Loop: on" : "Loop: off");
    }

    private Component craftLabel() {
        return Component.literal(schedule.craftType().label());
    }

    /**
     * Which stops this aircraft can be sent to: gates for a plane, helipads
     * for anything that lands vertically.
     */
    private List<String> stopsAt(AirportSummary airport) {
        return schedule.craftType().isVertical() ? airport.padNames() : airport.gateNames();
    }

    /**
     * Switching craft type re-points every stop, because a gate name is not a
     * pad name - leaving "Gate A" on a helicopter's schedule would just fail
     * to resolve at every airport on the route. VORs have neither, and a stop
     * whose airport has gone has nothing to re-point to.
     */
    private void cycleCraftType() {
        schedule.setCraftType(schedule.craftType().next());
        for (int i = 0; i < schedule.entries().size(); i++) {
            ScheduleEntry entry = schedule.entries().get(i);
            if (entry.isVor()) continue;
            AirportSummary airport = airportOf(entry.airportId());
            if (airport == null) continue;
            List<String> valid = stopsAt(airport);
            if (!valid.contains(entry.gateName())) {
                schedule.entries().set(i, new ScheduleEntry(entry.airportId(),
                        valid.isEmpty() ? "" : valid.get(0), entry.condition(), entry.waitSeconds()));
            }
        }
        refresh();
    }

    private Component altitudeLabel() {
        return Component.literal("Alt Y " + schedule.cruiseAltitude());
    }

    private Component speedLabel() {
        return Component.literal("Spd " + schedule.cruiseSpeed());
    }

    private void adjustCruiseAltitude(int delta) {
        schedule.setCruiseAltitude(Math.max(0, Math.min(400, schedule.cruiseAltitude() + delta)));
        refresh();
    }

    private void adjustCruiseSpeed(int delta) {
        schedule.setCruiseSpeed(Math.max(4, Math.min(80, schedule.cruiseSpeed() + delta)));
        refresh();
    }

    /** Re-syncs every control with the currently selected stop. */
    private void refresh() {
        boolean sel = hasSelection();
        boolean vor = sel && entry().isVor();
        AirportSummary airport = sel && !vor ? airportOf(entry().airportId()) : null;

        airportButton.setMessage(airportLabel());
        gateButton.setMessage(gateLabel());
        conditionButton.setMessage(conditionLabel());
        waitButton.setMessage(waitLabel());
        loopButton.setMessage(loopLabel());
        craftButton.setMessage(craftLabel());
        altitudeButton.setMessage(altitudeLabel());
        speedButton.setMessage(speedLabel());

        airportButton.active = sel && destinationCount() > 1;
        gateButton.active = airport != null && stopsAt(airport).size() > 1;
        // A VOR is flown over, so it has nothing to wait for.
        conditionButton.active = sel && !vor;
        // A player-boarding stop has no timer to set; cargo currently falls
        // back to the timer, so it still does.
        waitButton.active = sel && !vor && entry().condition() != ScheduleEntry.WaitCondition.PLAYER;
        removeButton.active = sel;
        // Somewhere to land, and a gate at every airport on the way. A route
        // of nothing but VORs has no destination at all.
        engageButton.active = schedule.hasAirportStop()
                && schedule.entries().stream().noneMatch(e -> !e.isVor() && e.gateName().isEmpty());
    }

    private void engage() {
        if (schedule.isEmpty()) return;
        PacketDistributor.sendToServer(new EngageAutopilotPayload(autopilotPos, schedule));
        onClose();
    }

    /** Closing keeps the route without flying it - editing a schedule and
     *  starting a flight are different intentions. */
    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new SaveSchedulePayload(autopilotPos, schedule));
        super.onClose();
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
        int listTop = LIST_TOP;
        List<ScheduleEntry> entries = schedule.entries();
        int first = Math.max(0, Math.min(selected - MAX_VISIBLE_ROWS + 1, entries.size() - MAX_VISIBLE_ROWS));
        if (first < 0) first = 0;
        for (int i = first; i < entries.size() && i - first < MAX_VISIBLE_ROWS; i++) {
            ScheduleEntry e = entries.get(i);
            int y = listTop + (i - first) * ROW_HEIGHT;
            boolean isSelected = i == selected;
            if (isSelected) guiGraphics.fill(left - 2, y - 1, left + panelW + 2, y + ROW_HEIGHT - 2, 0xFF3A3A3A);
            String text = e.isVor()
                    ? (i + 1) + ". via " + stopName(e) + "  [fly over]"
                    : (i + 1) + ". " + stopName(e)
                            + " / " + (e.gateName().isEmpty() ? "(no gate)" : e.gateName())
                            + "  [" + shortCondition(e) + "]";
            guiGraphics.drawString(font, text, left, y, isSelected ? 0xFFFFFFFF : 0xFFAAAAAA);
        }

        int belowList = listTop + Math.min(entries.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT;
        if (entries.isEmpty()) {
            guiGraphics.drawString(font, "No stops yet - hit Add Stop.", left, listTop, 0xFFAAAAAA);
        } else if (!schedule.hasAirportStop()) {
            // Engage is greyed out for this, and a disabled button on its own
            // does not say why.
            guiGraphics.drawString(font, "Needs an airport stop - VORs are only flown over.",
                    left, belowList, 0xFFE0B050);
        } else if (schedule.loop()) {
            guiGraphics.drawString(font, "loops back to stop 1", left, belowList, 0xFF7A9E7A);
        }
    }

    private static String shortCondition(ScheduleEntry e) {
        return switch (e.condition()) {
            case TIMER -> e.waitSeconds() + "s";
            case PLAYER -> "player";
            case CARGO_LOADED -> "loaded";
            case CARGO_EMPTY -> "emptied";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
