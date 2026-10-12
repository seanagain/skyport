package com.skyport.client.gui;

import com.skyport.network.PasscodePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One box, used both for the owner setting a block's passcode and for
 * everyone else being asked for it.
 *
 * Two screens would be the tidier reading, but they would differ only in a
 * label and which button is present, and the important half of this feature
 * is not here at all - it is in AccessControl, on the server. This screen
 * cannot grant anyone anything; it sends what was typed and is told
 * afterwards, in chat, whether it worked. Written that way on purpose: a
 * screen that decided for itself whether the code was right would have to
 * be given the code to check it against, which is the one thing a client
 * must never hold.
 */
public class PasscodeScreen extends Screen {

    private final BlockPos pos;
    private final boolean setting;
    private final boolean hasCode;
    private final String label;
    private EditBox codeBox;

    public PasscodeScreen(BlockPos pos, boolean setting, boolean hasCode, String label) {
        super(Component.translatable(setting
                ? "gui.skyport.passcode.set_title"
                : "gui.skyport.passcode.enter_title"));
        this.pos = pos;
        this.setting = setting;
        this.hasCode = hasCode;
        this.label = label;
    }

    @Override
    protected void init() {
        int boxW = Math.min(200, width - 40);
        int left = width / 2 - boxW / 2;
        int top = height / 2 - 20;

        codeBox = addRenderableWidget(new EditBox(font, left, top, boxW, 20,
                Component.translatable("gui.skyport.passcode.field")));
        codeBox.setMaxLength(PasscodePayload.MAX_LENGTH);
        setInitialFocus(codeBox);

        addRenderableWidget(Button.builder(Component.translatable(setting
                                ? "gui.skyport.passcode.save"
                                : "gui.skyport.passcode.unlock"),
                        b -> submit(codeBox.getValue()))
                .bounds(left, top + 26, boxW, 20)
                .build());

        // Only offered when there is something to remove, so the button is
        // never a no-op that looks like it did something.
        if (setting && hasCode) {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.skyport.passcode.clear"), b -> submit(""))
                    .bounds(left, top + 50, boxW, 20)
                    .build());
        }
    }

    private void submit(String code) {
        PacketDistributor.sendToServer(new PasscodePayload(pos, setting, code));
        onClose();
    }

    /** Enter submits, because typing a code and reaching for the mouse is
     *  not how anyone expects a passcode box to work. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter, numpad Enter
            submit(codeBox.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 56, 0xFFFFFF);
        guiGraphics.drawCenteredString(font, label, width / 2, height / 2 - 42, 0xFFAAAAAA);
    }

    /** Matches the other Skyport screens - vanilla's blur is wrong next to
     *  the map editor. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }
}
