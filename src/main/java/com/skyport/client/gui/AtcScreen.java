package com.skyport.client.gui;

import com.skyport.data.AirportLayout;
import com.skyport.data.TrafficReport;
import com.skyport.data.Waypoint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The ATC overview: every registered airport - drawn as its actual runway,
 * taxiways and pattern rather than a dot - plus every aircraft currently
 * under autopilot, on one map centred on the ATC block.
 *
 * The view auto-fits whatever exists rather than using a fixed scale, since
 * two airports might be 200 blocks apart or 20,000 and there's no sensible
 * default zoom for both. Deliberately no terrain: at a scale that shows
 * several airports at once it would be noise, and the point here is the
 * layout and who's flying it.
 */
public class AtcScreen extends Screen {

    private static final int COLOR_RUNWAY = 0xFFF2F2EA;
    private static final int COLOR_TAXIWAY = 0xFFE8C34A;
    private static final int COLOR_HOLDING = 0xFF4F8FE0;
    private static final int COLOR_FINAL_LEG = 0xFF7FD1E0;
    private static final int COLOR_GATE = 0xFFE0812F;
    private static final int COLOR_HOLD_SHORT = 0xFFD64550;
    private static final int COLOR_LABEL = 0xFF5AD7E0;
    private static final int COLOR_AIRCRAFT = 0xFFE0812F;
    private static final int COLOR_ATC = 0xFFE33A3A;
    private static final int COLOR_GRID = 0xFF2A2A2A;
    private static final int MARGIN_BLOCKS = 120;

    private final BlockPos atcPos;
    private final List<AirportLayout> airports;
    private final List<TrafficReport> traffic;

    private int mapX, mapY, mapW, mapH;
    private double blocksPerPixel = 8;
    private int centreX, centreZ;
    /** Which aircraft the detail line is describing; -1 for none. */
    private int selected = -1;

    public AtcScreen(BlockPos atcPos, List<AirportLayout> airports, List<TrafficReport> traffic) {
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

        for (AirportLayout airport : airports) {
            for (BlockPos p : allPoints(airport)) {
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ());
                maxZ = Math.max(maxZ, p.getZ());
            }
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

    private static List<BlockPos> allPoints(AirportLayout airport) {
        List<BlockPos> points = new java.util.ArrayList<>();
        for (Waypoint.Type type : Waypoint.Type.values()) {
            for (Waypoint w : airport.waypoints(type)) points.add(w.pos());
        }
        points.addAll(airport.gates().values());
        return points;
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
        guiGraphics.fill(mapX + mapW / 2, mapY, mapX + mapW / 2 + 1, mapY + mapH, COLOR_GRID);
        guiGraphics.fill(mapX, mapY + mapH / 2, mapX + mapW, mapY + mapH / 2 + 1, COLOR_GRID);

        guiGraphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        for (AirportLayout airport : airports) drawAirport(guiGraphics, airport);

        // The tower itself, so the map has a "you are here".
        int ax = worldToScreenX(atcPos.getX());
        int ay = worldToScreenY(atcPos.getZ());
        guiGraphics.fill(ax, ay - 4, ax + 1, ay + 5, COLOR_ATC);
        guiGraphics.fill(ax - 4, ay, ax + 5, ay + 1, COLOR_ATC);

        for (int i = 0; i < traffic.size(); i++) {
            TrafficReport report = traffic.get(i);
            int x = worldToScreenX(report.position().x);
            int y = worldToScreenY(report.position().z);
            guiGraphics.fill(x - 2, y - 2, x + 3, y + 3, COLOR_AIRCRAFT);
            if (i == selected) drawBorder(guiGraphics, x - 5, y - 5, 11, 11, 0xFFFFFFFF);
            guiGraphics.drawString(font, report.callsign(), x + 5, y + 3, COLOR_AIRCRAFT);
        }

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

    /** One airport's actual geometry, in the same colours the layout editor
     *  uses so the two read as the same drawing. */
    private void drawAirport(GuiGraphics guiGraphics, AirportLayout airport) {
        drawPath(guiGraphics, airport.waypoints(Waypoint.Type.RUNWAY), COLOR_RUNWAY);
        drawSegmentPairs(guiGraphics, airport.waypoints(Waypoint.Type.TAXIWAY), COLOR_TAXIWAY);
        drawPath(guiGraphics, airport.waypoints(Waypoint.Type.FINAL_LEG), COLOR_FINAL_LEG);
        drawLoop(guiGraphics, airport.waypoints(Waypoint.Type.HOLDING_PATTERN), COLOR_HOLDING);

        for (BlockPos gate : airport.gates().values()) {
            int x = worldToScreenX(gate.getX());
            int y = worldToScreenY(gate.getZ());
            guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, COLOR_GATE);
        }
        for (Waypoint hold : airport.waypoints(Waypoint.Type.HOLD_SHORT)) {
            int x = worldToScreenX(hold.pos().getX());
            int y = worldToScreenY(hold.pos().getZ());
            drawBorder(guiGraphics, x - 2, y - 2, 5, 5, COLOR_HOLD_SHORT);
        }

        List<Waypoint> runway = airport.waypoints(Waypoint.Type.RUNWAY);
        if (!runway.isEmpty()) {
            int x = worldToScreenX(runway.get(0).pos().getX());
            int y = worldToScreenY(runway.get(0).pos().getZ());
            guiGraphics.drawString(font, airport.displayName(), x + 5, y - 10, COLOR_LABEL);
        }
    }

    private void drawPath(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        for (int i = 1; i < points.size(); i++) {
            BlockPos a = points.get(i - 1).pos();
            BlockPos b = points.get(i).pos();
            line(guiGraphics, a, b, color);
        }
    }

    private void drawSegmentPairs(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        for (int i = 0; i + 1 < points.size(); i += 2) {
            line(guiGraphics, points.get(i).pos(), points.get(i + 1).pos(), color);
        }
    }

    private void drawLoop(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        drawPath(guiGraphics, points, color);
        if (points.size() > 2) {
            line(guiGraphics, points.get(points.size() - 1).pos(), points.get(0).pos(), color);
        }
    }

    private void line(GuiGraphics guiGraphics, BlockPos a, BlockPos b, int color) {
        drawPixelLine(guiGraphics,
                worldToScreenX(a.getX()), worldToScreenY(a.getZ()),
                worldToScreenX(b.getX()), worldToScreenY(b.getZ()), color);
    }

    private static void drawBorder(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void drawPixelLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = 0;
        while (guard++ < 4096) {
            guiGraphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
