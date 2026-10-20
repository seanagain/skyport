package com.seaport.client.gui;

import com.seaport.data.PortLayout;
import com.seaport.data.Wharf;
import com.seaport.network.SavePortLayoutPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The Port Station's editor: name the harbour, set how close ships stop, and
 * mark out its berths.
 *
 * Berths are marked by STANDING somewhere rather than drawn on a map. A quay
 * edge is two points on ground the player has just built, and walking to each
 * end is both more accurate than clicking a map at four blocks to the pixel
 * and far less to build. Skyport needs a map because an airport is a network
 * of lines that has to be seen whole; a harbour is a handful of edges, each
 * of which you can stand on.
 *
 * The layout is edited in place and sent back whole on close, the same as
 * Skyport's airport map: a half-saved harbour is worse than an unsaved one.
 */
public class PortStationScreen extends Screen {

    private final BlockPos stationPos;
    private final PortLayout layout;

    private EditBox nameBox;
    private Button clearanceButton;
    private Button markButton;
    private Button removeButton;

    /**
     * The first end of a berth being marked, or null when not part way
     * through one. Held only on the client and only until the second click -
     * an abandoned half-berth should disappear when the screen does rather
     * than becoming something to clean up.
     */
    @Nullable
    private BlockPos pendingEnd;

    /** Which berth the remove button acts on. -1 when there are none. */
    private int selected = -1;

    public PortStationScreen(BlockPos stationPos, PortLayout layout) {
        super(Component.translatable("gui.seaport.port_station.title"));
        this.stationPos = stationPos;
        this.layout = layout;
        this.selected = layout.wharves().isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        int panelW = Math.min(240, width - 20);
        int left = (width - panelW) / 2;
        int top = 30;

        nameBox = addRenderableWidget(new EditBox(font, left, top, panelW, 20,
                Component.literal("Port name")));
        nameBox.setValue(layout.displayName());
        nameBox.setMaxLength(PortLayout.MAX_NAME_LENGTH);
        nameBox.setHint(Component.literal("Port name"));
        nameBox.setResponder(layout::setDisplayName);

        // How close ships stop. A stepper rather than free text: it is a
        // small whole number with real limits, and a text box invites
        // somebody to type 900 and wonder why nothing docks.
        int step = 20;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustClearance(-1))
                .bounds(left, top + 26, step, 20).build());
        clearanceButton = addRenderableWidget(Button.builder(clearanceLabel(), b -> { })
                .bounds(left + step + 2, top + 26, panelW - step * 2 - 4, 20).build());
        clearanceButton.active = false;
        clearanceButton.setTooltip(Tooltip.create(Component.literal(
                "How far a hull stops from the quay edge. Measured from whichever part of "
                        + "the ship is nearest, so it holds however the vessel is lying.")));
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustClearance(1))
                .bounds(left + panelW - step, top + 26, step, 20).build());

        markButton = addRenderableWidget(Button.builder(markLabel(), b -> markHere())
                .bounds(left, top + 52, panelW, 20).build());
        markButton.setTooltip(Tooltip.create(Component.literal(
                "Stand at one end of the quay edge and press this, then walk to the other "
                        + "end and press it again. The line between them is where ships tie up.")));

        int half = (panelW - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Next berth"), b -> cycle())
                .bounds(left, top + 78, half, 20).build());
        removeButton = addRenderableWidget(Button.builder(Component.literal("Remove berth"),
                        b -> removeSelected())
                .bounds(left + half + 4, top + 78, half, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> saveAndClose())
                .bounds(left, top + 104, panelW, 20).build());

        refresh();
    }

    private Component clearanceLabel() {
        int blocks = layout.berthClearance();
        return Component.literal("Berth clearance: " + blocks + (blocks == 1 ? " block" : " blocks"));
    }

    private Component markLabel() {
        return Component.literal(pendingEnd == null
                ? "Mark berth: first end"
                : "Mark berth: other end");
    }

    private void adjustClearance(int delta) {
        layout.setBerthClearance(layout.berthClearance() + delta);
        refresh();
    }

    /**
     * Take the player's position as one end of a quay edge.
     *
     * Two presses make a berth. The first remembers where they stood; the
     * second closes the line and names it. A line with both ends in the same
     * block is refused rather than stored, because a quay with no direction
     * has no side for a ship to come alongside - every berthing calculation
     * is measured against that direction.
     */
    private void markHere() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        BlockPos here = minecraft.player.blockPosition();

        if (pendingEnd == null) {
            pendingEnd = here;
            refresh();
            return;
        }

        Wharf wharf = new Wharf(layout.nextWharfName(), pendingEnd, here);
        if (!wharf.isUsable()) {
            // Still consumes the pending end: leaving it set would mean the
            // next press tried to close the same zero-length line again.
            pendingEnd = null;
            refresh();
            return;
        }
        layout.wharves().add(wharf);
        selected = layout.wharves().size() - 1;
        pendingEnd = null;
        refresh();
    }

    private void cycle() {
        if (layout.wharves().isEmpty()) {
            selected = -1;
            return;
        }
        selected = (selected + 1) % layout.wharves().size();
        refresh();
    }

    private void removeSelected() {
        if (selected < 0 || selected >= layout.wharves().size()) return;
        layout.wharves().remove(selected);
        selected = layout.wharves().isEmpty() ? -1 : Math.min(selected, layout.wharves().size() - 1);
        refresh();
    }

    private void refresh() {
        clearanceButton.setMessage(clearanceLabel());
        markButton.setMessage(markLabel());
        removeButton.active = selected >= 0;
    }

    private void saveAndClose() {
        layout.setDisplayName(nameBox.getValue());
        PacketDistributor.sendToServer(new SavePortLayoutPayload(stationPos, layout));
        super.onClose();
    }

    /** Closing without pressing Save still saves. Editing a harbour and
     *  walking away is not an instruction to discard it, and Skyport's
     *  autopilot screen settled the same question the same way. */
    @Override
    public void onClose() {
        saveAndClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int panelW = Math.min(240, width - 20);
        int left = (width - panelW) / 2;
        guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        int y = 30 + 130;
        if (pendingEnd != null) {
            guiGraphics.drawString(font, "First end at " + pendingEnd.getX() + ", " + pendingEnd.getZ()
                    + " - walk to the other end", left, y, 0xFFE0B050);
            y += 12;
        }

        if (layout.wharves().isEmpty()) {
            // Worth saying plainly: a port with no berth is a name ships can
            // be sent to and never tie up at.
            guiGraphics.drawString(font, "No berths yet. Ships can be routed here", left, y, 0xFFE0B050);
            guiGraphics.drawString(font, "but will have nowhere to stop.", left, y + 10, 0xFFE0B050);
            return;
        }

        guiGraphics.drawString(font, layout.wharves().size() + " berth"
                + (layout.wharves().size() == 1 ? "" : "s"), left, y, 0xFFD9D9D9);
        y += 12;

        for (int i = 0; i < layout.wharves().size(); i++) {
            if (y > height - 20) {
                guiGraphics.drawString(font, "...", left, y, 0xFF8A8A8A);
                break;
            }
            Wharf wharf = layout.wharves().get(i);
            boolean picked = i == selected;
            guiGraphics.drawString(font,
                    (picked ? "> " : "  ") + wharf.name()
                            + "  " + Math.round(wharf.length()) + " long",
                    left, y, picked ? 0xFFFFFFFF : 0xFFAAAAAA);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
