package com.skyport.client.gui;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import com.skyport.network.SaveAirportLayoutPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opened when a player right-clicks an Airport Station block - a small
 * landing screen in front of the map editor, so naming an airport isn't
 * buried inside {@link AirportMapScreen} (whose toolbar has no room for a
 * text field) and so it's obvious an airport has a real identity, and what
 * it currently contains, before you start drawing on it.
 */
public class AirportStationScreen extends Screen {

    private final BlockPos stationPos;
    private final AirportLayout layout;
    private EditBox nameBox;
    private Button taxiSpeedButton;

    public AirportStationScreen(BlockPos stationPos, AirportLayout layout) {
        super(Component.translatable("gui.skyport.airport_station.title"));
        this.stationPos = stationPos;
        this.layout = layout;
        // Let the world projector trace this airport while the player holds
        // a station - see LayoutProjector.
        com.skyport.client.LayoutProjector.show(layout);
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int boxW = Math.min(200, width - 40);
        int left = centerX - boxW / 2;
        int top = Math.max(30, height / 2 - 52);

        nameBox = addRenderableWidget(new EditBox(font, left, top, boxW, 20,
                Component.translatable("gui.skyport.airport_station.name")));
        nameBox.setValue(layout.displayName());
        nameBox.setMaxLength(32);
        nameBox.setResponder(layout::setDisplayName);
        setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_station.edit_map"),
                        b -> openMap())
                .bounds(left, top + 26, boxW, 20)
                .build());

        // Taxi speed is a property of the field, not of the aircraft: a
        // cramped airport where the taxiway doubles as the runway wants
        // everything slow enough to stop, however fast the visiting aircraft
        // is set up to cruise.
        int stepW = 22;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustTaxiSpeed(-1))
                .bounds(left, top + 50, stepW, 20)
                .build());
        taxiSpeedButton = addRenderableWidget(Button.builder(taxiSpeedLabel(), b -> { })
                .bounds(left + stepW + 2, top + 50, boxW - stepW * 2 - 4, 20)
                .build());
        taxiSpeedButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustTaxiSpeed(1))
                .bounds(left + boxW - stepW, top + 50, stepW, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_station.save"),
                        b -> saveAndClose())
                .bounds(left, top + 74, boxW, 20)
                .build());

        // Reaching the lock through the screen rather than through sneak +
        // right-click: sneaking only reaches a block when both hands are
        // empty, and the player is nearly always still holding the block
        // they just placed.
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.passcode.button"),
                        b -> PacketDistributor.sendToServer(
                                new com.skyport.network.LockRequestPayload(stationPos)))
                .bounds(left, top + 98, boxW, 20)
                .build());
    }

    private Component taxiSpeedLabel() {
        return Component.literal("Taxi speed: " + layout.taxiSpeed() + " b/s");
    }

    private void adjustTaxiSpeed(int delta) {
        layout.setTaxiSpeed(layout.taxiSpeed() + delta);
        if (taxiSpeedButton != null) taxiSpeedButton.setMessage(taxiSpeedLabel());
    }

    private void save() {
        layout.setDisplayName(nameBox.getValue());
        PacketDistributor.sendToServer(new SaveAirportLayoutPayload(stationPos, layout));
    }

    private void saveAndClose() {
        save();
        super.onClose();
    }

    private void openMap() {
        // Save first: the map editor is a separate screen, and the name typed
        // here should survive even if the player closes the game from there.
        save();
        Minecraft.getInstance().setScreen(new AirportMapScreen(stationPos, layout));
    }

    /** Escape saves too, rather than dropping a rename on the floor. */
    @Override
    public void onClose() {
        save();
        super.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(font, title, width / 2, Math.max(8, height / 2 - 72), 0xFFFFFF);

        // What this airport actually contains right now - so "did my drawing
        // save" is answerable without opening the map editor again.
        String summary = "Runway " + tick(Waypoint.Type.RUNWAY, 2)
                + (layout.hasDepartureRunway() ? " +dep" : "")
                + "   Taxiway " + tick(Waypoint.Type.TAXIWAY, 2)
                + "   Holding " + tick(Waypoint.Type.HOLDING_PATTERN, 3)
                + "   Final " + tick(Waypoint.Type.FINAL_LEG, 2)
                + "   Gates " + layout.gates().size()
                + "   Pads " + layout.helipads().size();
        guiGraphics.drawCenteredString(font, summary, width / 2, Math.max(20, height / 2 - 60), 0xFFAAAAAA);
    }

    /** "ok" if this element has enough points to be usable, else how many it has. */
    private String tick(Waypoint.Type type, int required) {
        int size = layout.waypoints(type).size();
        return size >= required ? "ok" : (size + "/" + required);
    }

    /** Vanilla's default blurs the world behind the GUI; a flat fill keeps
     *  this screen crisp and consistent with the map editor. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
