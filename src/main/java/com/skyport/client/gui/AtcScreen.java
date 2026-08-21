package com.skyport.client.gui;

import com.skyport.data.AirportLayout;
import com.skyport.data.TrafficReport;
import com.skyport.data.Waypoint;
import com.skyport.client.TerrainMemory;
import com.skyport.network.AtcTrafficPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ATC overview: every registered airport - drawn as its actual runway,
 * taxiways and pattern rather than a dot - plus every aircraft currently
 * under autopilot, on one map centred on the ATC block.
 *
 * The view auto-fits whatever exists rather than using a fixed scale, since
 * two airports might be 200 blocks apart or 20,000 and there's no sensible
 * default zoom for both - though it can be zoomed and re-fitted by hand.
 * Coarse terrain sits behind the layout from whatever chunks the client has
 * loaded, dimmed so it reads as a backdrop rather than competing with the
 * runways and traffic, which are the actual subject. A strip down the right
 * lists every aircraft and what it is doing, since a plane on the ground is
 * easy to miss among an airport's own lines.
 */
public class AtcScreen extends Screen {

    private static final int COLOR_RUNWAY = 0xFFF2F2EA;
    private static final int COLOR_TAXIWAY = 0xFFE8C34A;
    private static final int COLOR_HOLDING = 0xFF4F8FE0;
    private static final int COLOR_FINAL_LEG = 0xFF7FD1E0;
    private static final int COLOR_GATE = 0xFFE0812F;
    private static final int COLOR_HOLD_SHORT = 0xFFD64550;
    private static final int COLOR_HELIPAD = 0xFF63D66B;
    private static final int COLOR_LABEL = 0xFF5AD7E0;
    private static final int COLOR_AIRCRAFT = 0xFFE0812F;
    /** Taxiing aircraft, dimmer so they read as "on the ground" against the
     *  airport lines they sit on top of. */
    private static final int COLOR_AIRCRAFT_GROUND = 0xFF9E6636;
    private static final int COLOR_ATC = 0xFFE33A3A;
    private static final int COLOR_GRID = 0xFF2A2A2A;
    private static final int MARGIN_BLOCKS = 120;
    private static final int TERRAIN_CELL = 3;
    /** How often to ask the server for fresh aircraft positions. */
    private static final long REFRESH_INTERVAL_MS = 500;

    private final BlockPos atcPos;
    private final List<AirportLayout> airports;
    /** Replaced wholesale by each refresh, so aircraft move while you watch. */
    private List<TrafficReport> traffic;
    private long lastRefreshMs;
    /** Set once the view has been dragged, so refreshes stop re-fitting it. */
    private boolean panned;

    private int mapX, mapY, mapW, mapH;
    private int listX, listW;
    private int[][] terrain = new int[0][0];
    private net.minecraft.client.gui.components.Button zoomButton;
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
        // Reserve a strip on the right for the aircraft list, so the map
        // isn't the only way to see what's flying.
        listW = Math.min(120, Math.max(90, width / 4));
        int totalW = Math.min(width - 20, 340 + listW);
        int left = (width - totalW) / 2;

        mapW = totalW - listW - 6;
        mapH = Math.max(80, height - 40 - rowH - 26);
        mapX = left;
        mapY = 30;
        listX = mapX + mapW + 6;

        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoom(1.6))
                .bounds(mapX, mapY + mapH + 16, 20, rowH).build());
        zoomButton = addRenderableWidget(Button.builder(zoomLabel(), b -> { })
                .bounds(mapX + 22, mapY + mapH + 16, 70, rowH).build());
        zoomButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom(1 / 1.6))
                .bounds(mapX + 94, mapY + mapH + 16, 20, rowH).build());
        addRenderableWidget(Button.builder(Component.literal("Fit"),
                        b -> { panned = false; fitToContents(); refreshZoom(); })
                .bounds(mapX + 118, mapY + mapH + 16, 34, rowH).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(listX + listW - 60, mapY + mapH + 16, 60, rowH)
                .build());

        // init() runs again on window resize, so don't yank a view the player
        // has deliberately dragged somewhere.
        if (!panned) fitToContents();
        sampleTerrain();
    }

    /**
     * Swap in fresh traffic from the server.
     *
     * Selection follows the aircraft rather than the row index: the list is
     * rebuilt each refresh and planes drop out of it when they park, so a
     * remembered index would quietly start describing a different aeroplane.
     */
    public void updateTraffic(List<TrafficReport> updated) {
        java.util.UUID selectedId = (selected >= 0 && selected < traffic.size())
                ? traffic.get(selected).planeId() : null;
        this.traffic = updated;
        selected = -1;
        if (selectedId != null) {
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).planeId().equals(selectedId)) {
                    selected = i;
                    break;
                }
            }
        }
    }

    private Component zoomLabel() {
        return Component.literal(Math.round(blocksPerPixel) + " blk/px");
    }

    private void refreshZoom() {
        if (zoomButton != null) zoomButton.setMessage(zoomLabel());
        sampleTerrain();
    }

    /** Zoom about the centre. Factors rather than a fixed ladder, because the
     *  usable range here spans a couple of orders of magnitude. */
    private void zoom(double factor) {
        blocksPerPixel = Math.max(1, Math.min(512, blocksPerPixel * factor));
        refreshZoom();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overMap(mouseX, mouseY)) {
            zoom(scrollY > 0 ? 1 / 1.6 : 1.6);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean overMap(double x, double y) {
        return x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH;
    }

    /** Drag the map around. Panning turns off auto-fit, or the next refresh
     *  would snap the view straight back and undo the drag. */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && overMap(mouseX, mouseY)) {
            centreX -= (int) Math.round(dragX * blocksPerPixel);
            centreZ -= (int) Math.round(dragY * blocksPerPixel);
            panned = true;
            sampleTerrain();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * Coarse terrain behind the layout, from the client's loaded chunks -
     * same MapColor approach as the layout editor, including its ABGR fix.
     *
     * Only what the client has loaded resolves; at ATC scale that is usually
     * a patch around the player rather than the whole map, which is honest -
     * a control tower can't see terrain nobody has visited either.
     */
    private void sampleTerrain() {
        Level level = Minecraft.getInstance().level;
        int cols = Math.max(1, mapW / TERRAIN_CELL);
        int rows = Math.max(1, mapH / TERRAIN_CELL);
        int[][] sampled = new int[cols][rows];
        for (int cx = 0; cx < cols; cx++) {
            for (int cy = 0; cy < rows; cy++) {
                int worldX = centreX + (int) Math.round((cx * TERRAIN_CELL + TERRAIN_CELL / 2.0 - mapW / 2.0) * blocksPerPixel);
                int worldZ = centreZ + (int) Math.round((cy * TERRAIN_CELL + TERRAIN_CELL / 2.0 - mapH / 2.0) * blocksPerPixel);
                sampled[cx][cy] = sampleTerrainColor(level, new BlockPos(worldX, 0, worldZ));
            }
        }
        terrain = sampled;
    }

    private static int sampleTerrainColor(@Nullable Level level, BlockPos column) {
        int color = TerrainMemory.colorAt(level, column.getX(), column.getZ());
        if (color == 0) return 0;
        // Dimmed: this is a backdrop for the layout, not the subject.
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return 0xFF000000 | ((r * 2 / 3) << 16) | ((g * 2 / 3) << 8) | (b * 2 / 3);
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
        // A row in the traffic strip selects the same aircraft as its marker.
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= mapY + 16) {
            int row = (int) ((mouseY - (mapY + 16)) / 22);
            if (row >= 0 && row < traffic.size()) {
                selected = row;
                return true;
            }
        }

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

        // Poll for moving traffic while the screen is open. Only while open -
        // a flight nobody is watching costs nothing.
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs > REFRESH_INTERVAL_MS) {
            lastRefreshMs = now;
            PacketDistributor.sendToServer(new AtcTrafficPayload.Request());
            // Terrain fills in as chunks load around the player, so re-sample
            // on the same beat rather than only when the view changes.
            sampleTerrain();
        }

        guiGraphics.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF111820);
        for (int cx = 0; cx < terrain.length; cx++) {
            for (int cy = 0; cy < terrain[cx].length; cy++) {
                int color = terrain[cx][cy];
                if (color == 0) continue; // unloaded - leave the dark backdrop
                int x = mapX + cx * TERRAIN_CELL;
                int y = mapY + cy * TERRAIN_CELL;
                guiGraphics.fill(x, y, Math.min(x + TERRAIN_CELL, mapX + mapW),
                        Math.min(y + TERRAIN_CELL, mapY + mapH), color);
            }
        }
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
            int color = report.airborne() ? COLOR_AIRCRAFT : COLOR_AIRCRAFT_GROUND;
            guiGraphics.fill(x - 2, y - 2, x + 3, y + 3, color);
            if (i == selected) drawBorder(guiGraphics, x - 5, y - 5, 11, 11, 0xFFFFFFFF);
            guiGraphics.drawString(font, report.callsign(), x + 5, y + 3, color);
        }

        drawAircraftList(guiGraphics);

        String status;
        if (selected >= 0 && selected < traffic.size()) {
            TrafficReport report = traffic.get(selected);
            status = String.format("%s  %s  ->  %s   at %.0f, %.0f, %.0f",
                    report.callsign(), report.state(), report.destination(),
                    report.position().x, report.position().y, report.position().z);
        } else if (traffic.isEmpty()) {
            status = airports.size() + " airport(s), nothing flying";
        } else {
            status = airports.size() + " airport(s), " + traffic.size() + " flying";
        }
        guiGraphics.drawString(font, status, mapX, mapY + mapH + 4, 0xFFAAAAAA);
    }

    /**
     * The traffic strip: every aircraft and what it's doing right now.
     *
     * The map answers "where", but not "what is that one up to" without
     * hunting for its marker - and a plane on the ground sits underneath its
     * airport's lines where it's easy to miss entirely. Clicking a row
     * selects the same aircraft as clicking its marker.
     */
    private void drawAircraftList(GuiGraphics guiGraphics) {
        guiGraphics.fill(listX, mapY, listX + listW, mapY + mapH, 0xFF141414);
        drawBorder(guiGraphics, listX, mapY, listW, mapH, 0xFF2B2B2B);
        guiGraphics.drawString(font, "Traffic", listX + 4, mapY + 4, 0xFFFFFFFF);

        if (traffic.isEmpty()) {
            guiGraphics.drawString(font, "none", listX + 4, mapY + 18, 0xFF777777);
            return;
        }

        int y = mapY + 16;
        for (int i = 0; i < traffic.size() && y < mapY + mapH - 20; i++) {
            TrafficReport report = traffic.get(i);
            if (i == selected) {
                guiGraphics.fill(listX + 1, y - 1, listX + listW - 1, y + 19, 0xFF303030);
            }
            guiGraphics.drawString(font, report.callsign(), listX + 4, y,
                    i == selected ? 0xFFFFFFFF : COLOR_AIRCRAFT);
            guiGraphics.drawString(font, prettyState(report.state()), listX + 4, y + 10, 0xFF9A9A9A);
            y += 22;
        }
    }

    /** TAKEOFF_ROLL -> "Takeoff roll" - the enum name is for code, not a
     *  status board. */
    private static String prettyState(String state) {
        String spaced = state.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
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
        for (BlockPos pad : airport.helipads().values()) {
            int x = worldToScreenX(pad.getX());
            int y = worldToScreenY(pad.getZ());
            drawBorder(guiGraphics, x - 3, y - 3, 7, 7, COLOR_HELIPAD);
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
