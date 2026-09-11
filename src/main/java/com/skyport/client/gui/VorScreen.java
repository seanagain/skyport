package com.skyport.client.gui;

import com.skyport.data.VorBeacon;
import com.skyport.network.LockRequestPayload;
import com.skyport.network.RenameVorPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Names a VOR beacon - which is all there is to set on one. A VOR is a name
 * and a place, and the place is wherever the block stands.
 */
public class VorScreen extends Screen {

    private final BlockPos pos;
    private final String originalName;
    /** What is in the box, kept outside it: init() runs again on every
     *  window resize and would otherwise put the old name back. */
    private String name;
    private EditBox nameBox;

    public VorScreen(BlockPos pos, String name) {
        super(Component.translatable("gui.skyport.vor.title"));
        this.pos = pos;
        this.originalName = name;
        this.name = name;
    }

    @Override
    protected void init() {
        int boxW = Math.min(200, width - 40);
        int left = width / 2 - boxW / 2;
        int top = Math.max(50, height / 2 - 24);

        nameBox = addRenderableWidget(new EditBox(font, left, top, boxW, 20,
                Component.translatable("gui.skyport.vor.name")));
        nameBox.setMaxLength(VorBeacon.MAX_NAME_LENGTH);
        nameBox.setValue(name);
        nameBox.setResponder(value -> name = value);
        setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.vor.save"), b -> onClose())
                .bounds(left, top + 26, boxW, 20)
                .build());

        // Saved first: the passcode box replaces this screen without closing
        // it, so a name typed and then left for the lock would be dropped.
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.passcode.button"), b -> {
                    save();
                    PacketDistributor.sendToServer(new LockRequestPayload(pos));
                })
                .bounds(left, top + 50, boxW, 20)
                .build());
    }

    /** Only when it has actually changed - opening a VOR to read its name
     *  should not answer with a "saved" line in chat. */
    private void save() {
        if (name.strip().equals(originalName)) return;
        PacketDistributor.sendToServer(new RenameVorPayload(pos, name));
    }

    /** Enter saves, the way it does in any other box that takes one line. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        int top = Math.max(50, height / 2 - 24);
        guiGraphics.drawCenteredString(font, title, width / 2, top - 38, 0xFFFFFF);
        guiGraphics.drawCenteredString(font, "Aircraft routed over this VOR cross " + pos.getX() + ", " + pos.getZ(),
                width / 2, top - 24, 0xFFAAAAAA);
        guiGraphics.drawCenteredString(font, "at their own cruise altitude.",
                width / 2, top - 13, 0xFFAAAAAA);
    }

    /** A flat fill, matching the other Skyport screens. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
