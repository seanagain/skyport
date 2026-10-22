package com.seaport.client.gui;

import com.seaport.data.Beacon;
import com.seaport.network.RenameBeaconPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A channel marker has exactly one thing to set: what it is called.
 *
 * So this is a text box, and nothing else. The name is what a schedule names,
 * which makes it the whole interface - there is no position to edit, because
 * the mark is wherever its block stands.
 */
public class MarineBeaconScreen extends Screen {

    private final BlockPos beaconPos;
    private final String originalName;

    private EditBox nameBox;

    public MarineBeaconScreen(BlockPos beaconPos, String name) {
        super(Component.translatable("gui.seaport.marine_beacon.title"));
        this.beaconPos = beaconPos;
        this.originalName = name;
    }

    @Override
    protected void init() {
        int panelW = Math.min(220, width - 20);
        int left = (width - panelW) / 2;
        int top = height / 2 - 30;

        nameBox = addRenderableWidget(new EditBox(font, left, top, panelW, 20,
                Component.literal("Mark name")));
        nameBox.setValue(originalName);
        nameBox.setMaxLength(Beacon.MAX_NAME_LENGTH);
        nameBox.setHint(Component.literal("Mark name"));
        setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left, top + 26, panelW, 20).build());
    }

    /**
     * Closing saves, the same as every other screen in this mod.
     *
     * Only when the text actually changed, though: a player who opened a mark
     * to read its name and walked away should not cost a packet, and should
     * not touch the registry's dirty flag either.
     */
    @Override
    public void onClose() {
        String typed = nameBox.getValue();
        if (!typed.equals(originalName)) {
            PacketDistributor.sendToServer(new RenameBeaconPayload(beaconPos, typed));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 48, 0xFFFFFF);
        guiGraphics.drawCenteredString(font,
                Component.literal("X " + beaconPos.getX() + "   Z " + beaconPos.getZ()),
                width / 2, height / 2 + 22, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
