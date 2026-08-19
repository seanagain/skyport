package com.skyport.client.gui;

import com.skyport.data.AirportSummary;
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
 * Opened when a player right-clicks an Autopilot block. `airports` is a
 * snapshot sent by the server when the screen was requested (see
 * AutopilotBlockEntity#openDestinationPicker) - this screen has no server
 * access of its own, it just reads what it was given and reports the
 * player's choice back over EngageAutopilotPayload.
 *
 * Vanilla has no dropdown widget, so airport/gate selection is done with
 * plain "click to cycle" buttons instead of trying to build a real
 * dropdown - simplest thing that works for a prototype.
 */
public class AutopilotScreen extends Screen {

    private final BlockPos autopilotPos;
    private final List<AirportSummary> airports;

    private int airportIndex = 0;
    private int gateIndex = 0;

    private Button airportButton;
    private Button gateButton;
    private Button engageButton;

    public AutopilotScreen(BlockPos autopilotPos, List<AirportSummary> airports) {
        super(Component.translatable("gui.skyport.autopilot.title"));
        this.autopilotPos = autopilotPos;
        this.airports = airports;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int top = height / 2 - 50;

        airportButton = addRenderableWidget(Button.builder(airportLabel(), b -> cycleAirport())
                .bounds(centerX - 100, top, 200, 20)
                .build());

        gateButton = addRenderableWidget(Button.builder(gateLabel(), b -> cycleGate())
                .bounds(centerX - 100, top + 24, 200, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.disengage"),
                        b -> {
                            PacketDistributor.sendToServer(new DisengageAutopilotPayload(autopilotPos));
                            onClose();
                        })
                .bounds(centerX - 100, top + 56, 96, 20)
                .build());

        engageButton = addRenderableWidget(Button.builder(Component.translatable("gui.skyport.autopilot.engage"),
                        b -> engage())
                .bounds(centerX + 4, top + 56, 96, 20)
                .build());

        boolean hasAirports = !airports.isEmpty();
        airportButton.active = hasAirports;
        gateButton.active = hasAirports && !currentAirport().gateNames().isEmpty();
        engageButton.active = gateButton.active;
    }

    private AirportSummary currentAirport() {
        return airports.get(airportIndex);
    }

    private Component airportLabel() {
        if (airports.isEmpty()) return Component.translatable("gui.skyport.autopilot.no_airports");
        return Component.literal(currentAirport().displayName());
    }

    private Component gateLabel() {
        if (airports.isEmpty()) return Component.empty();
        List<String> gates = currentAirport().gateNames();
        if (gates.isEmpty()) return Component.translatable("gui.skyport.autopilot.no_gates");
        return Component.literal(gates.get(gateIndex));
    }

    private void cycleAirport() {
        if (airports.isEmpty()) return;
        airportIndex = (airportIndex + 1) % airports.size();
        gateIndex = 0;
        airportButton.setMessage(airportLabel());
        gateButton.setMessage(gateLabel());
        boolean hasGates = !currentAirport().gateNames().isEmpty();
        gateButton.active = hasGates;
        engageButton.active = hasGates;
    }

    private void cycleGate() {
        List<String> gates = currentAirport().gateNames();
        if (gates.isEmpty()) return;
        gateIndex = (gateIndex + 1) % gates.size();
        gateButton.setMessage(gateLabel());
    }

    private void engage() {
        if (airports.isEmpty()) return;
        List<String> gates = currentAirport().gateNames();
        if (gates.isEmpty()) return;
        PacketDistributor.sendToServer(new EngageAutopilotPayload(autopilotPos, currentAirport().id(), gates.get(gateIndex)));
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
        guiGraphics.drawCenteredString(font, title, width / 2, Math.max(8, height / 2 - 66), 0xFFFFFF);

        if (airports.isEmpty()) {
            guiGraphics.drawCenteredString(font,
                    "Draw and save a layout at an Airport Station first.",
                    width / 2, Math.max(20, height / 2 - 52), 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
