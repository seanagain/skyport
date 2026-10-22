package com.seaport.client.gui;

import com.seaport.blockentity.MarineAutopilotBlockEntity;
import com.seaport.data.PortDirectory;
import com.seaport.data.VoyageSchedule;
import com.seaport.data.VoyageStop;
import com.seaport.network.SaveVoyageSchedulePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The helm: a ship's name and the list of stops it works through.
 *
 * Laid out as a list rather than a map, which is the opposite choice from
 * the Port Station, and deliberately. A harbour is a place, and a place has
 * to be seen where it is. A schedule is an order of events, and an order is
 * a list - a map of one would show you four harbours and not which came
 * first.
 *
 * Stops come in two kinds and read differently on purpose. A PORT stop is
 * somewhere the ship ties up and waits, so it carries a berth and a
 * condition. A MARK is passed on the way and carries neither; it exists to
 * say which channel to take when the router's own answer would be the
 * shortest line rather than the sensible one.
 */
public class MarineAutopilotScreen extends Screen {

    private static final int ROW_HEIGHT = 12;
    private static final int COLOR_PORT = 0xFFE0E0E0;
    private static final int COLOR_MARK = 0xFF7FC8E0;
    private static final int COLOR_SELECTED = 0xFFFFB45E;
    private static final int COLOR_CURSOR = 0xFF63D66B;
    private static final int COLOR_MUTED = 0xFF8A8A8A;
    private static final int COLOR_WARNING = 0xFFE0603A;

    private final BlockPos autopilotPos;
    private final PortDirectory directory;
    private final VoyageSchedule schedule;

    private String callsign;
    private boolean running;

    private EditBox callsignBox;
    private Button runButton;
    private Button loopButton;
    private Button addButton;
    private Button berthButton;
    private Button conditionButton;
    private Button waitButton;
    private Button upButton;
    private Button downButton;
    private Button removeButton;

    /** Which stop the buttons act on. -1 when the list is empty. */
    private int selected = -1;

    /**
     * Which destination the Add button will add next.
     *
     * One cycling button rather than a list of every harbour, because the
     * list can be long and this screen has no room for a scrolling picker
     * on top of the one it already has. Cycling is slow with twenty ports
     * and fine with the three or four a server actually runs.
     */
    private int addCursor;

    private int listX, listY, listW, listH;

    public MarineAutopilotScreen(BlockPos autopilotPos, String callsign, boolean running,
                                 VoyageSchedule schedule, PortDirectory directory) {
        super(Component.translatable("gui.seaport.marine_autopilot.title"));
        this.autopilotPos = autopilotPos;
        this.callsign = callsign;
        this.running = running;
        this.schedule = schedule;
        this.directory = directory;
        this.selected = schedule.isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        int rowH = 20;
        int gap = 4;
        int panelW = Math.min(300, width - 20);
        int left = (width - panelW) / 2;
        int top = 24;

        int runW = 70;
        callsignBox = addRenderableWidget(new EditBox(font, left, top, panelW - runW - gap, rowH,
                Component.literal("Ship name")));
        callsignBox.setValue(callsign);
        callsignBox.setMaxLength(MarineAutopilotBlockEntity.MAX_CALLSIGN_LENGTH);
        callsignBox.setHint(Component.literal("Ship name"));
        callsignBox.setResponder(typed -> callsign = typed);

        runButton = addRenderableWidget(Button.builder(runLabel(), b -> toggleRunning())
                .bounds(left + panelW - runW, top, runW, rowH).build());
        runButton.setTooltip(Tooltip.create(Component.literal(
                "Start or stop working the schedule. Stopping keeps the ship's place in the "
                        + "list, so starting again carries on rather than going back to the top.")));

        // --- the list of stops
        listX = left;
        listY = top + rowH + gap;
        listW = panelW;
        listH = Math.max(ROW_HEIGHT * 3, height - listY - (rowH + gap) * 3 - 12);

        int row1 = listY + listH + gap;
        int third = (panelW - gap * 2) / 3;

        addButton = addRenderableWidget(Button.builder(addLabel(), b -> addStop())
                .bounds(left, row1, third * 2 + gap, rowH).build());
        addButton.setTooltip(Tooltip.create(Component.literal(
                "Adds the destination shown. Press the arrow to pick a different one.")));
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAdd())
                .bounds(left + third * 2 + gap * 2, row1, third - gap, rowH).build());

        int row2 = row1 + rowH + gap;
        berthButton = addRenderableWidget(Button.builder(Component.literal("Berth"), b -> cycleBerth())
                .bounds(left, row2, third, rowH).build());
        berthButton.setTooltip(Tooltip.create(Component.literal(
                "Which berth to tie up at. 'Any' takes whichever is free when the ship arrives.")));
        conditionButton = addRenderableWidget(Button.builder(Component.literal("Wait"), b -> cycleCondition())
                .bounds(left + third + gap, row2, third, rowH).build());
        conditionButton.setTooltip(Tooltip.create(Component.literal(
                "What has to happen before the ship sails again.")));
        waitButton = addRenderableWidget(Button.builder(Component.literal("0s"), b -> cycleWait())
                .bounds(left + (third + gap) * 2, row2, third, rowH).build());
        waitButton.setTooltip(Tooltip.create(Component.literal(
                "How long to lie alongside, when waiting on the clock.")));

        int row3 = row2 + rowH + gap;
        int quarter = (panelW - gap * 3) / 4;
        upButton = addRenderableWidget(Button.builder(Component.literal("Up"), b -> move(-1))
                .bounds(left, row3, quarter, rowH).build());
        downButton = addRenderableWidget(Button.builder(Component.literal("Down"), b -> move(1))
                .bounds(left + quarter + gap, row3, quarter, rowH).build());
        removeButton = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> remove())
                .bounds(left + (quarter + gap) * 2, row3, quarter, rowH).build());
        loopButton = addRenderableWidget(Button.builder(loopLabel(), b -> toggleLoop())
                .bounds(left + (quarter + gap) * 3, row3, quarter, rowH).build());
        loopButton.setTooltip(Tooltip.create(Component.literal(
                "Looping runs the list round again at the end. Without it the ship stops at "
                        + "the last stop and stays there.")));

        refresh();
    }

    // ---- labels ----------------------------------------------------------

    private Component runLabel() {
        return Component.literal(running ? "Stop" : "Start");
    }

    private Component loopLabel() {
        return Component.literal(schedule.looping() ? "Loop" : "Once");
    }

    /** What the Add button would add. Shows every port first, then every
     *  mark, so the common case is reached before the rare one. */
    private Component addLabel() {
        int ports = directory.ports().size();
        int total = ports + directory.marks().size();
        if (total == 0) return Component.literal("Nothing to sail to");
        int at = Math.floorMod(addCursor, total);
        return Component.literal(at < ports
                ? "Add port: " + directory.ports().get(at).name()
                : "Add mark: " + directory.marks().get(at - ports).name());
    }

    private VoyageStop selectedStop() {
        List<VoyageStop> stops = schedule.stops();
        if (selected < 0 || selected >= stops.size()) return null;
        return stops.get(selected);
    }

    // ---- editing ---------------------------------------------------------

    private void toggleRunning() {
        running = !running;
        refresh();
    }

    private void toggleLoop() {
        schedule.setLooping(!schedule.looping());
        refresh();
    }

    private void cycleAdd() {
        int total = directory.ports().size() + directory.marks().size();
        if (total == 0) return;
        addCursor = Math.floorMod(addCursor + 1, total);
        refresh();
    }

    private void addStop() {
        int ports = directory.ports().size();
        int total = ports + directory.marks().size();
        if (total == 0 || schedule.stops().size() >= VoyageSchedule.MAX_STOPS) return;

        int at = Math.floorMod(addCursor, total);
        if (at < ports) {
            PortDirectory.Entry port = directory.ports().get(at);
            // Berth left blank, meaning "any". Picking one straight away
            // would be guessing which of a harbour's berths the player
            // meant, and "any" is the answer that always works.
            schedule.stops().add(VoyageStop.port(port.id(), "", VoyageStop.WaitCondition.TIMER, 30));
        } else {
            schedule.stops().add(VoyageStop.mark(directory.marks().get(at - ports).id()));
        }
        selected = schedule.stops().size() - 1;
        refresh();
    }

    /**
     * Step through the berths this stop's harbour has, plus "any".
     *
     * A stop whose berth no longer exists - renamed, or deleted on the
     * chart - cycles back into the list from wherever it was, rather than
     * being silently rewritten. The name is kept until the player changes
     * it, because a stop pointing at a berth that is briefly missing is
     * usually a berth about to be put back.
     */
    private void cycleBerth() {
        VoyageStop stop = selectedStop();
        if (stop == null || stop.isMark()) return;
        PortDirectory.Entry port = directory.portById(stop.portId());
        if (port == null || port.berths().isEmpty()) return;

        List<String> berths = port.berths();
        int at = berths.indexOf(stop.berthName());
        // -1 covers both "any" and a name no longer in the list; the next
        // press lands on the first real berth either way.
        String next = at + 1 >= berths.size() ? "" : berths.get(at + 1);
        schedule.stops().set(selected, stop.withBerth(next));
        refresh();
    }

    private void cycleCondition() {
        VoyageStop stop = selectedStop();
        if (stop == null || stop.isMark()) return;
        VoyageStop.WaitCondition[] all = VoyageStop.WaitCondition.values();
        int next = (stop.condition().ordinal() + 1) % all.length;
        schedule.stops().set(selected, stop.withCondition(all[next]));
        refresh();
    }

    /** A short ladder of sensible waits rather than a free number. Nobody
     *  wants to type a duration, and the useful values are few. */
    private static final int[] WAITS = { 0, 5, 10, 15, 30, 60, 120, 300, 600 };

    private void cycleWait() {
        VoyageStop stop = selectedStop();
        if (stop == null || stop.isMark()) return;
        int at = 0;
        for (int i = 0; i < WAITS.length; i++) {
            if (WAITS[i] == stop.waitSeconds()) { at = i; break; }
        }
        schedule.stops().set(selected, stop.withWait(WAITS[(at + 1) % WAITS.length]));
        refresh();
    }

    private void move(int delta) {
        List<VoyageStop> stops = schedule.stops();
        int to = selected + delta;
        if (selected < 0 || to < 0 || to >= stops.size()) return;
        VoyageStop moving = stops.remove(selected);
        stops.add(to, moving);
        selected = to;
        refresh();
    }

    private void remove() {
        List<VoyageStop> stops = schedule.stops();
        if (selected < 0 || selected >= stops.size()) return;
        stops.remove(selected);
        selected = stops.isEmpty() ? -1 : Math.min(selected, stops.size() - 1);
        refresh();
    }

    private void refresh() {
        runButton.setMessage(runLabel());
        loopButton.setMessage(loopLabel());
        addButton.setMessage(addLabel());
        addButton.active = !directory.ports().isEmpty() || !directory.marks().isEmpty();

        VoyageStop stop = selectedStop();
        boolean isPort = stop != null && !stop.isMark();
        berthButton.active = isPort;
        conditionButton.active = isPort;
        waitButton.active = isPort && stop.condition() == VoyageStop.WaitCondition.TIMER;

        berthButton.setMessage(Component.literal(
                !isPort ? "Berth" : (stop.berthName().isEmpty() ? "Berth: any" : stop.berthName())));
        conditionButton.setMessage(Component.literal(isPort ? conditionName(stop.condition()) : "Wait"));
        waitButton.setMessage(Component.literal(isPort ? stop.waitSeconds() + "s" : "0s"));

        upButton.active = selected > 0;
        downButton.active = selected >= 0 && selected < schedule.stops().size() - 1;
        removeButton.active = selected >= 0;
    }

    private static String conditionName(VoyageStop.WaitCondition condition) {
        return switch (condition) {
            case TIMER -> "On the clock";
            case PLAYER -> "For a player";
            case CARGO_LOADED -> "Until loaded";
            case CARGO_EMPTY -> "Until empty";
        };
    }

    // ---- the list --------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH) {
            int row = (int) ((mouseY - listY) / ROW_HEIGHT);
            if (row >= 0 && row < schedule.stops().size()) {
                selected = row;
                refresh();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);

        guiGraphics.fill(listX, listY, listX + listW, listY + listH, 0x60000000);

        List<VoyageStop> stops = schedule.stops();
        if (stops.isEmpty()) {
            guiGraphics.drawString(font, "No orders. Add a port below.",
                    listX + 4, listY + 4, COLOR_MUTED, false);
            if (directory.ports().isEmpty()) {
                guiGraphics.drawString(font, "No harbours exist yet - place a Port Station",
                        listX + 4, listY + 16, COLOR_WARNING, false);
                guiGraphics.drawString(font, "and draw a berth on its chart.",
                        listX + 4, listY + 26, COLOR_WARNING, false);
            }
            return;
        }

        int next = schedule.cursor();
        for (int i = 0; i < stops.size(); i++) {
            int y = listY + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT > listY + listH) {
                guiGraphics.drawString(font, "...", listX + 4, y, COLOR_MUTED, false);
                break;
            }
            if (i == selected) {
                guiGraphics.fill(listX, y, listX + listW, y + ROW_HEIGHT, 0x40FFFFFF);
            }
            // A green bar on the stop the ship is actually making for, which
            // is not necessarily the one being edited.
            if (i == next && running) {
                guiGraphics.fill(listX, y, listX + 2, y + ROW_HEIGHT, COLOR_CURSOR);
            }
            guiGraphics.drawString(font, describe(stops.get(i)), listX + 5, y + 2,
                    i == selected ? COLOR_SELECTED : (stops.get(i).isMark() ? COLOR_MARK : COLOR_PORT),
                    false);
        }
    }

    /**
     * One line of the list.
     *
     * A stop whose harbour or mark is no longer in the directory says so
     * rather than showing a blank. That happens when the block behind it is
     * broken, and a schedule quietly listing an empty row is how somebody
     * spends an evening wondering why their ship will not sail.
     */
    private String describe(VoyageStop stop) {
        if (stop.isMark()) {
            PortDirectory.Mark mark = directory.markById(stop.beaconId());
            return mark == null ? "via (mark removed)" : "via " + mark.name();
        }
        PortDirectory.Entry port = directory.portById(stop.portId());
        String name = port == null ? "(port removed)" : port.name();
        String berth = stop.berthName().isEmpty() ? "any berth" : stop.berthName();
        String wait = stop.condition() == VoyageStop.WaitCondition.TIMER
                ? stop.waitSeconds() + "s"
                : conditionName(stop.condition());
        return name + "  -  " + berth + "  -  " + wait;
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(
                new SaveVoyageSchedulePayload(autopilotPos, callsign, running, schedule));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
