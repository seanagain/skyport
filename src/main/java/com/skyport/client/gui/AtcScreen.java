package com.skyport.client.gui;

import com.skyport.data.AirportSummary;
import com.skyport.data.TrafficReport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The ATC overview: every registered airport and every aircraft currently
 * under autopilot, on one map centred on the ATC block.
 *
 * The view auto-fits whatever exists rather than using a fixed scale, since
 * two airports might be 200 blocks apart or 20,000 - there's no sensible
 * default zoom, and a map you have to hunt around isn't much of an overview.
 * Deliberately schematic: no terrain, because at the scale that shows several
 * airports at once, terrain is noise.
 */
public class AtcScreen extends Screen {

    private static final int COLOR_AIRPORT = 0xFF5AD7E0;
    private static final int COLOR_AIRCRAFT = 0xFFE0812F;
    private static final int COLOR_ATC = 0xFFE33A3A;
    private static final int COLOR_GRID = 0xFF2A2A2A;
    private static final int MARGIN_BLOCKS = 200;

    private final BlockPos atcPos;
    private final List<AirportSummary> airports;
    private final List<TrafficReport> traffic;

    private int mapX, mapY, mapW, mapH;
    private double blocksPerPixel = 8;
    private int centreX, centreZ;
    /** Which aircraft the detail line is describing; -1 for none. */
    private int selected = -1;

    public AtcScreen(BlockPos atcPos, List<AirportSummary> airports, List<TrafficReport> traffic) {
        super(Component.translatable("gui.skyport.atc.title"));
        this.atcPos = atcPos;
        this.airports = airports;
        this.traffic = traffic;
    }

    @Override
    protected void init() {
        int rowH = 20;
        mapW = Math.min(340, width - 20);
        mapH = Math.max(80, height - 40 - rowH - 26);
        mapX = (width - mapW) / 2;
        mapY = 30;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(mapX + mapW - 60, mapY + mapH + 16, 60, rowH)
                .build());

        fitToContents();
    }

    /** Picks a centre and scale that gets everything on screen at once. */
    private void fitToContents() {
        int minX = atcPos.getX(), maxX = atcPos.getX();
        int minZ = atcPos.getZ(), maxZ = atcPos.getZ();

        for (AirportSummary airport : airports) {
            minX = Math.min(minX, airport.position().getX());
            maxX = Math.max(maxX, airport.position().getX());
            minZ = Math.min(minZ, airport.position().getZ());
            maxZ = Math.max(maxZ, airport.position().getZ());
        }
        for (TrafficReport report : traffic) {
            minX = Math.min(minX, (int) report.position().x);
            maxX = Math.max(maxX, (int) report.position().x);
            minZ = Math.min(minZ, (int) report.position().z);
            maxZ = Math.max(maxZ, (int) report.position().z);
        }

        centreX = (minX + maxX) / 2;
        centreZ = (minZ + maxZ) / 2;

        double spanX = (maxX - minX) + MARGIN_BLOCKS * 2.0;
        double spanZ = (maxZ - minZ) + MARGIN_BLOCKS * 2.0;
        blocksPerPixel = Math.max(1.0, Math.max(spanX / mapW, spanZ / mapH));
    }

    private int worldToScreenX(double worldX) {
        return mapX + mapW / 2 + (int) Math.round((worldX - centreX) / blocksPerPixel);
    }

    private int worldToScreenY(double worldZ) {
        return mapY + mapH / 2 + (int) Math.round((worldZ - centreZ) / blocksPerPixel);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click near an aircraft to read its details.
        selected = -1;
        for (int i = 0; i < traffic.size(); i++) {
            TrafficReport report = traffic.get(i);
            int x = worldToScreenX(report.position().x);
            int y = worldToScreenY(report.position().z);
            if (Math.abs(mouseX - x) <= 4 && Math.abs(mouseY - y) <= 4) {
                selected = i;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Flat fill rather than vanilla's blur, matching the other Skyport screens. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Screen#render draws the background itself, so ours goes after it.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF111820);
        drawBorder(guiGraphics, mapX, mapY, mapW, mapH, 0xFF2B2B2B);
        drawBorder(guiGraphics, mapX + mapW / 2 - 1, mapY, 2, mapH, COLOR_GRID);
        drawBorder(guiGraphics, mapX, mapY + mapH / 2 - 1, mapW, 2, COLOR_GRID);

        guiGraphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        // The tower itself, so the map has a "you are here".
        int ax = worldToScreenX(atcPos.getX());
        int ay = worldToScreenY(atcPos.getZ());
        guiGraphics.fill(ax - 1, ay - 4, ax + 2, ay + 5, COLOR_ATC);
        guiGraphics.fill(ax - 4, ay - 1, ax + 5, ay + 2, COLOR_ATC);

        for (AirportSummary airport : airports) {
            int x = worldToScreenX(airport.position().getX());
            int y = worldToScreenY(airport.position().getZ());
            drawBorder(guiGraphics, x - 3, y - 3, 7, 7, COLOR_AIRPORT);
            guiGraphics.drawString(font, airport.displayName(), x + 6, y - 4, COLOR_AIRPORT);
        }

        for (int i = 0; i < traffic.size(); i++) {
            TrafficReport report = traffic.get(i);
            int x = worldToScreenX(report.position().x);
            int y = worldToScreenY(report.position().z);
            guiGraphics.fill(x - 2, y - 2, x + 3, y + 3, COLOR_AIRCRAFT);
            if (i == selected) drawBorder(guiGraphics, x - 5, y - 5, 11, 11, 0xFFFFFFFF);
            guiGraphics.drawString(font, report.callsign(), x + 5, y + 3, COLOR_AIRCRAFT);
        }

        // Status line: the selected aircraft, or a summary.
        String status;
        if (selected >= 0 && selected < traffic.size()) {
            TrafficReport report = traffic.get(selected);
            status = String.format("%s  %s  ->  %s   at %.0f, %.0f, %.0f",
                    report.callsign(), report.state(), report.destination(),
                    report.position().x, report.position().y, report.position().z);
        } else if (traffic.isEmpty()) {
            status = airports.size() + " airport(s), no aircraft airborne";
        } else {
            status = airports.size() + " airport(s), " + traffic.size()
                    + " airborne - click an aircraft for details";
        }
        guiGraphics.drawString(font, status, mapX, mapY + mapH + 4, 0xFFAAAAAA);

        guiGraphics.drawString(font, "scale: " + Math.round(blocksPerPixel) + " blk/px",
                mapX, mapY + mapH + 20, 0xFF777777);
    }

    private static void drawBorder(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
