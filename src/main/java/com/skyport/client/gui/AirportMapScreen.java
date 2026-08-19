package com.skyport.client.gui;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import com.skyport.network.SaveAirportLayoutPayload;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The airport layout editor, opened from {@link AirportStationScreen}.
 *
 * The background is a real (if coarse) top-down terrain snapshot sampled
 * from the loaded world around the station - the same MapColor system
 * vanilla maps use, baked into a cached grid and re-sampled once a second
 * so chunks streaming in fill themselves out while the screen is open.
 * Deliberately not a full minimap: no player dot, no zoom - just "what
 * does the ground actually look like here". Only loaded chunks resolve to
 * real colors; unloaded ones stay a flat blue-gray, the same spirit as an
 * unexplored vanilla map.
 *
 * Drawing rules, per element:
 * - Runway and Final Leg: exactly 2 points each (one straight line). A 3rd
 *   click starts that line over - see {@link #isLineMode}.
 * - Taxiway: a set of independent 2-point segments. The first pair is the
 *   backbone (runway gate-end <-> holding pattern); every later pair is one
 *   gate's spur, drawn from the gate out to where it meets the backbone.
 * - Holding Pattern: a freeform closed loop, flown at the configured Y and
 *   in the configured direction.
 * - Gate: single named points.
 *
 * Layout is computed from the actual screen size in {@link #init()} rather
 * than fixed constants - at small GUI scales a fixed 320x200 map pushed the
 * footer (and its Save button) clean off the bottom of the screen.
 */
public class AirportMapScreen extends Screen {

    private enum EditMode {
        RUNWAY("Runway"), TAXIWAY("Taxiway"), HOLDING_PATTERN("Holding"), FINAL_LEG("Final"), GATE("Gate");

        final String label;
        EditMode(String label) { this.label = label; }
    }

    private static final int BLOCKS_PER_PIXEL = 4;
    private static final int TERRAIN_CELL_SIZE = 4; // screen px per sampled terrain cell
    private static final int SNAP_GRID = 8;         // world blocks a clicked point snaps to
    // Deliberately NOT a plausible ground color - real sampled terrain and
    // "not loaded yet" need to look obviously different, the way an
    // unexplored patch of a vanilla map is blank rather than guessing grass.
    private static final int FALLBACK_TERRAIN_COLOR = 0xFF32404A;
    private static final long TERRAIN_RESAMPLE_INTERVAL_MS = 1000;

    private static final int COLOR_RUNWAY = 0xFFF2F2EA;
    private static final int COLOR_TAXIWAY = 0xFFE8C34A;
    private static final int COLOR_HOLDING = 0xFF4F8FE0;
    private static final int COLOR_FINAL_LEG = 0xFF7FD1E0;
    private static final int COLOR_GATE = 0xFFE0812F;
    private static final int COLOR_PLAYER = 0xFFE33A3A;

    private final BlockPos stationPos;
    private final AirportLayout layout;

    private EditMode mode = EditMode.RUNWAY;
    private int mapX, mapY, mapW, mapH;
    private int[][] terrainColors = new int[0][0];
    private long lastTerrainSampleMs;

    private Button heightValueButton;
    private Button directionButton;

    public AirportMapScreen(BlockPos stationPos, AirportLayout layout) {
        super(Component.translatable("gui.skyport.airport_map.title"));
        this.stationPos = stationPos;
        this.layout = layout;
    }

    @Override
    protected void init() {
        // Reserve fixed vertical bands for the header/toolbars and the
        // footer, then give whatever's left to the map. Keeps the Save
        // button on-screen at every GUI scale.
        int titleH = 14;
        int rowH = 20;
        int gap = 4;
        int topBand = titleH + gap + rowH + gap + rowH + gap;   // title + 2 toolbar rows
        int bottomBand = 12 + rowH + gap;                        // coord readout + footer row

        mapW = Math.min(320, width - 20);
        mapH = Math.max(60, height - topBand - bottomBand - gap);
        mapX = (width - mapW) / 2;
        mapY = topBand;

        int row1Y = titleH + gap;
        int row2Y = row1Y + rowH + gap;

        // --- row 1: edit modes, split evenly across the map's width ---
        int modeCount = EditMode.values().length;
        int modeW = (mapW - (modeCount - 1) * 2) / modeCount;
        int btnX = mapX;
        for (EditMode candidate : EditMode.values()) {
            addRenderableWidget(Button.builder(Component.literal(candidate.label), b -> mode = candidate)
                    .bounds(btnX, row1Y, modeW, rowH)
                    .build());
            btnX += modeW + 2;
        }

        // --- row 2: undo + holding-pattern height/direction ---
        int undoW = Math.max(52, mapW / 5);
        int stepW = 20;
        int dirW = Math.max(58, mapW / 5);
        int heightW = Math.max(46, mapW - undoW - stepW * 2 - dirW - 8);

        btnX = mapX;
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_map.undo"), b -> undoLastPoint())
                .bounds(btnX, row2Y, undoW, rowH)
                .build());
        btnX += undoW + 2;

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustHoldingHeight(-5))
                .bounds(btnX, row2Y, stepW, rowH)
                .build());
        btnX += stepW + 2;

        heightValueButton = addRenderableWidget(Button.builder(heightLabel(), b -> { })
                .bounds(btnX, row2Y, heightW, rowH)
                .build());
        heightValueButton.active = false;
        btnX += heightW + 2;

        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustHoldingHeight(5))
                .bounds(btnX, row2Y, stepW, rowH)
                .build());
        btnX += stepW + 2;

        directionButton = addRenderableWidget(Button.builder(directionLabel(), b -> toggleDirection())
                .bounds(btnX, row2Y, dirW, rowH)
                .build());

        // --- footer: clear current element, and done ---
        int footerY = mapY + mapH + 12;
        int footerW = Math.max(70, mapW / 3 - 4);
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_map.clear"), b -> clearCurrent())
                .bounds(mapX, footerY, footerW, rowH)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_map.save"), b -> saveAndClose())
                .bounds(mapX + mapW - footerW, footerY, footerW, rowH)
                .build());

        sampleTerrain();
        lastTerrainSampleMs = System.currentTimeMillis();
    }

    private Component heightLabel() {
        return Component.literal("Hold Y " + layout.holdingPatternHeight());
    }

    private Component directionLabel() {
        return Component.literal(layout.holdingPatternClockwise() ? "Turn CW" : "Turn CCW");
    }

    private void adjustHoldingHeight(int delta) {
        int next = Math.max(-64, Math.min(320, layout.holdingPatternHeight() + delta));
        layout.setHoldingPatternHeight(next);
        heightValueButton.setMessage(heightLabel());
    }

    private void toggleDirection() {
        layout.setHoldingPatternClockwise(!layout.holdingPatternClockwise());
        directionButton.setMessage(directionLabel());
    }

    /** Runway and Final Leg are exactly one straight line each - a 3rd click
     *  starts a fresh line rather than extending a polygon. Taxiway is NOT
     *  capped like this: it's a set of segments (see class doc). */
    private static boolean isLineMode(EditMode mode) {
        return mode == EditMode.RUNWAY || mode == EditMode.FINAL_LEG;
    }

    private void undoLastPoint() {
        if (mode == EditMode.GATE) {
            List<String> names = new ArrayList<>(layout.gates().keySet());
            if (!names.isEmpty()) layout.gates().remove(names.get(names.size() - 1));
            return;
        }
        List<Waypoint> points = layout.waypoints(toWaypointType(mode));
        if (!points.isEmpty()) points.remove(points.size() - 1);
    }

    private void clearCurrent() {
        if (mode == EditMode.GATE) {
            layout.gates().clear();
        } else {
            layout.waypoints(toWaypointType(mode)).clear();
        }
    }

    private void saveAndClose() {
        PacketDistributor.sendToServer(new SaveAirportLayoutPayload(stationPos, layout));
        Minecraft.getInstance().setScreen(new AirportStationScreen(stationPos, layout));
    }

    /**
     * Escape (and any other close path) saves too, rather than silently
     * throwing the drawing away. There's deliberately no "cancel" here: the
     * layout object is mutated in place as you click, so by the time you'd
     * press cancel the in-memory copy is already changed - offering to
     * discard would be a lie unless we snapshotted and restored it.
     */
    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new SaveAirportLayoutPayload(stationPos, layout));
        super.onClose();
    }

    private static Waypoint.Type toWaypointType(EditMode mode) {
        return switch (mode) {
            case RUNWAY -> Waypoint.Type.RUNWAY;
            case TAXIWAY -> Waypoint.Type.TAXIWAY;
            case HOLDING_PATTERN -> Waypoint.Type.HOLDING_PATTERN;
            case FINAL_LEG -> Waypoint.Type.FINAL_LEG;
            case GATE -> throw new IllegalArgumentException("GATE is not a Waypoint.Type");
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInsideMap(mouseX, mouseY)) {
            BlockPos world = screenToWorld((int) mouseX, (int) mouseY);
            if (mode == EditMode.GATE) {
                layout.gates().put(layout.nextGateName(), world);
            } else {
                Waypoint.Type type = toWaypointType(mode);
                List<Waypoint> points = layout.waypoints(type);
                if (isLineMode(mode) && points.size() >= 2) points.clear();
                points.add(new Waypoint(world, type, points.size()));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInsideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    private BlockPos screenToWorld(int screenX, int screenY) {
        int dx = (screenX - mapX - mapW / 2) * BLOCKS_PER_PIXEL;
        int dz = (screenY - mapY - mapH / 2) * BLOCKS_PER_PIXEL;
        return new BlockPos(snap(stationPos.getX() + dx), stationPos.getY(), snap(stationPos.getZ() + dz));
    }

    /** Snaps a world coordinate to the nearest SNAP_GRID multiple, so points
     *  land cleanly instead of wherever the pixel math happened to fall. */
    private static int snap(int value) {
        return Math.round(value / (float) SNAP_GRID) * SNAP_GRID;
    }

    private int worldToScreenX(BlockPos pos) {
        return mapX + mapW / 2 + (pos.getX() - stationPos.getX()) / BLOCKS_PER_PIXEL;
    }

    private int worldToScreenY(BlockPos pos) {
        return mapY + mapH / 2 + (pos.getZ() - stationPos.getZ()) / BLOCKS_PER_PIXEL;
    }

    /** Samples real terrain colors into a coarse cached grid - called on open
     *  and once a second after, not per-frame. */
    private void sampleTerrain() {
        Level level = Minecraft.getInstance().level;
        int cols = Math.max(1, mapW / TERRAIN_CELL_SIZE);
        int rows = Math.max(1, mapH / TERRAIN_CELL_SIZE);
        int[][] sampled = new int[cols][rows];
        for (int cx = 0; cx < cols; cx++) {
            for (int cy = 0; cy < rows; cy++) {
                int screenX = mapX + cx * TERRAIN_CELL_SIZE + TERRAIN_CELL_SIZE / 2;
                int screenY = mapY + cy * TERRAIN_CELL_SIZE + TERRAIN_CELL_SIZE / 2;
                sampled[cx][cy] = sampleTerrainColor(level, screenToWorld(screenX, screenY));
            }
        }
        terrainColors = sampled;
    }

    private static int sampleTerrainColor(@Nullable Level level, BlockPos column) {
        if (level == null || !level.hasChunkAt(column)) return FALLBACK_TERRAIN_COLOR;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ());
        BlockPos surface = new BlockPos(column.getX(), surfaceY - 1, column.getZ());
        MapColor mapColor = level.getBlockState(surface).getMapColor(level, surface);
        if (mapColor == MapColor.NONE) return FALLBACK_TERRAIN_COLOR;
        return 0xFF000000 | (mapColor.calculateRGBColor(MapColor.Brightness.NORMAL) & 0xFFFFFF);
    }

    /**
     * Vanilla's default draws a blur pass plus a translucent menu texture -
     * layered over our own map that read as "blurry". A flat opaque fill
     * instead, so the terrain grid and drawn lines stay crisp.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF1A1A1A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // NB: Screen#render calls renderBackground itself before drawing
        // widgets, so the map has to be drawn AFTER super.render - drawing it
        // first meant the background pass wiped it out and left only buttons.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        long now = System.currentTimeMillis();
        if (now - lastTerrainSampleMs > TERRAIN_RESAMPLE_INTERVAL_MS) {
            sampleTerrain();
            lastTerrainSampleMs = now;
        }

        for (int cx = 0; cx < terrainColors.length; cx++) {
            for (int cy = 0; cy < terrainColors[cx].length; cy++) {
                int x = mapX + cx * TERRAIN_CELL_SIZE;
                int y = mapY + cy * TERRAIN_CELL_SIZE;
                guiGraphics.fill(x, y, Math.min(x + TERRAIN_CELL_SIZE, mapX + mapW),
                        Math.min(y + TERRAIN_CELL_SIZE, mapY + mapH), terrainColors[cx][cy]);
            }
        }
        drawBorder(guiGraphics, mapX, mapY, mapW, mapH, 0xFF2B2B2B);

        drawPath(guiGraphics, layout.waypoints(Waypoint.Type.RUNWAY), COLOR_RUNWAY);
        drawSegmentPairs(guiGraphics, layout.waypoints(Waypoint.Type.TAXIWAY), COLOR_TAXIWAY);
        drawPath(guiGraphics, layout.waypoints(Waypoint.Type.FINAL_LEG), COLOR_FINAL_LEG);
        drawLoop(guiGraphics, layout.waypoints(Waypoint.Type.HOLDING_PATTERN), COLOR_HOLDING);

        for (BlockPos gate : layout.gates().values()) {
            int gx = worldToScreenX(gate);
            int gy = worldToScreenY(gate);
            guiGraphics.fill(gx - 2, gy - 2, gx + 2, gy + 2, COLOR_GATE);
        }

        drawPlayerMarker(guiGraphics);

        // Crosshair on the snapped position the next click would land on.
        if (isInsideMap(mouseX, mouseY)) {
            BlockPos hovered = screenToWorld(mouseX, mouseY);
            int hx = worldToScreenX(hovered);
            int hy = worldToScreenY(hovered);
            guiGraphics.fill(hx - 4, hy, hx + 5, hy + 1, 0x80FFFFFF);
            guiGraphics.fill(hx, hy - 4, hx + 1, hy + 5, 0x80FFFFFF);
            guiGraphics.drawString(font, "X " + hovered.getX() + "  Z " + hovered.getZ(),
                    mapX + 2, mapY + mapH + 2, 0xFFD9D9D9);
        }

        guiGraphics.drawString(font, mode.label + " - " + hint(), mapX + 2, 3, 0xFFAAAAAA);
        guiGraphics.drawString(font, layout.displayName(),
                mapX + mapW - font.width(layout.displayName()) - 2, 3, 0xFFFFFFFF);
    }

    /**
     * Where the player is standing, as a red cross. Nothing marks any of the
     * drawn layout in the actual world, so without this there's no way to
     * relate a line on the map to somewhere you can walk to - which makes
     * "tow the plane onto the taxiway" impossible to act on. Drawn even when
     * off-map, clamped to the edge, so it still points the right way.
     */
    private void drawPlayerMarker(GuiGraphics guiGraphics) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        BlockPos at = player.blockPosition();
        int px = Math.max(mapX + 1, Math.min(mapX + mapW - 2, worldToScreenX(at)));
        int py = Math.max(mapY + 1, Math.min(mapY + mapH - 2, worldToScreenY(at)));
        guiGraphics.fill(px - 3, py, px + 4, py + 1, COLOR_PLAYER);
        guiGraphics.fill(px, py - 3, px + 1, py + 4, COLOR_PLAYER);
    }

    /** One line telling you what clicking actually does in the current mode -
     *  the rules differ per element and aren't guessable from the buttons. */
    private String hint() {
        return switch (mode) {
            case RUNWAY -> "click 2 points: gate end, then far end";
            case TAXIWAY -> "click pairs: backbone first, then one per gate";
            case HOLDING_PATTERN -> "click a loop of 3+ points";
            case FINAL_LEG -> "click 2 points: holding side, then runway far end";
            case GATE -> "click to place a gate";
        };
    }

    /** Draws consecutive PAIRS as independent segments (0-1, 2-3, ...) rather
     *  than one connected path - each pair is its own taxiway line. A lone
     *  trailing point (mid-way through the next segment) shows as a dot. */
    private void drawSegmentPairs(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        for (int i = 0; i + 1 < points.size(); i += 2) {
            BlockPos a = points.get(i).pos();
            BlockPos b = points.get(i + 1).pos();
            int ax = worldToScreenX(a), ay = worldToScreenY(a);
            int bx = worldToScreenX(b), by = worldToScreenY(b);
            guiGraphics.fill(ax - 2, ay - 2, ax + 2, ay + 2, color);
            guiGraphics.fill(bx - 2, by - 2, bx + 2, by + 2, color);
            drawPixelLine(guiGraphics, ax, ay, bx, by, color);
        }
        if (points.size() % 2 == 1) {
            BlockPos last = points.get(points.size() - 1).pos();
            int lx = worldToScreenX(last), ly = worldToScreenY(last);
            guiGraphics.fill(lx - 2, ly - 2, lx + 2, ly + 2, color);
        }
    }

    private void drawPath(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        for (int i = 0; i < points.size(); i++) {
            BlockPos pos = points.get(i).pos();
            int x = worldToScreenX(pos);
            int y = worldToScreenY(pos);
            guiGraphics.fill(x - 2, y - 2, x + 2, y + 2, color);
            if (i > 0) {
                BlockPos prev = points.get(i - 1).pos();
                drawPixelLine(guiGraphics, worldToScreenX(prev), worldToScreenY(prev), x, y, color);
            }
        }
    }

    /** Same as drawPath, but also connects the last point back to the first. */
    private void drawLoop(GuiGraphics guiGraphics, List<Waypoint> points, int color) {
        drawPath(guiGraphics, points, color);
        if (points.size() > 2) {
            BlockPos first = points.get(0).pos();
            BlockPos last = points.get(points.size() - 1).pos();
            drawPixelLine(guiGraphics, worldToScreenX(last), worldToScreenY(last), worldToScreenX(first), worldToScreenY(first), color);
        }
    }

    private static void drawBorder(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Bresenham, plotting single pixels - deliberately blocky/pixel-art rather than a smooth line. */
    private static void drawPixelLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
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
