package com.skyport.client.gui;

import com.skyport.data.AirportLayout;
import com.skyport.data.Waypoint;
import com.skyport.logic.GroundNetwork;
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
 * The background is a top-down terrain snapshot sampled from the loaded
 * world around the station, using the same MapColor system vanilla maps do,
 * with relief shading from the height step to the north. It's baked into a
 * cached grid and re-sampled once a second, so chunks streaming in fill
 * themselves out while the screen is open. Only loaded chunks resolve to
 * real colors; unloaded ones stay a flat blue-gray, the same spirit as an
 * unexplored vanilla map. Zoom runs 1-16 blocks per pixel, by button or
 * scroll wheel.
 *
 * Sampling deliberately uses {@link #screenToWorldRaw} rather than the
 * snapped {@link #screenToWorld}: snapping is for placing waypoints on a
 * tidy grid, and applying it to sample positions collapsed neighbouring
 * pixels onto the same block, which is what made the map bear no
 * resemblance to the actual ground.
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
        RUNWAY("Runway"), TAXIWAY("Taxiway"), HOLDING_PATTERN("Pattern"),
        FINAL_LEG("Final"), GATE("Gate"), HOLD_SHORT("Hold Line"), HELIPAD("Pad");

        final String label;
        EditMode(String label) { this.label = label; }
    }

    /** Zoom levels, in world blocks per screen pixel. */
    private static final int[] ZOOM_LEVELS = { 1, 2, 4, 8, 16 };
    private static final int TERRAIN_CELL_SIZE = 2; // screen px per sampled terrain cell
    // Placement is per-block: at 1 blk/px you can put a node on an exact
    // block, and zooming out only coarsens it as far as the pixels do.
    // Joining to an existing node is a fixed few PIXELS rather than a fixed
    // number of blocks - a 24-block radius swallowed everything nearby at
    // close zoom, making precise placement impossible.
    private static final int NODE_SNAP_PIXELS = 4;
    /** How near a click has to land to count as "on" a taxiway segment. */
    private static final int FLOW_PICK_PIXELS = 6;
    /** Two runways at two points each. The second pair is optional. */
    private static final int MAX_RUNWAY_POINTS = 4;
    private static final int COLOR_FLOW_ARROW = 0xFF2B2B22;
    private static final long REJECTION_VISIBLE_MS = 4000;
    // Deliberately NOT a plausible ground color - real sampled terrain and
    // "not loaded yet" need to look obviously different, the way an
    // unexplored patch of a vanilla map is blank rather than guessing grass.
    private static final int FALLBACK_TERRAIN_COLOR = 0xFF32404A;
    private static final long TERRAIN_RESAMPLE_INTERVAL_MS = 1000;

    private static final int COLOR_RUNWAY = 0xFFF2F2EA;
    /** The departures-only strip, if one is drawn - distinct from the arrival
     *  runway so the map says which is which. */
    private static final int COLOR_RUNWAY_DEPARTURE = 0xFF9FD8B0;
    private static final int COLOR_TAXIWAY = 0xFFE8C34A;
    private static final int COLOR_HOLDING = 0xFF4F8FE0;
    private static final int COLOR_FINAL_LEG = 0xFF7FD1E0;
    private static final int COLOR_GATE = 0xFFE0812F;
    private static final int COLOR_PLAYER = 0xFFE33A3A;
    private static final int COLOR_HOLD_SHORT = 0xFFD64550;
    private static final int COLOR_HELIPAD = 0xFF63D66B;

    private final BlockPos stationPos;
    private final AirportLayout layout;

    private EditMode mode = EditMode.RUNWAY;
    private int mapX, mapY, mapW, mapH;
    private int[][] terrainColors = new int[0][0];
    private long lastTerrainSampleMs;

    /** Index into ZOOM_LEVELS; starts at 4 blocks/pixel. */
    private int zoomIndex = 2;

    private Button heightValueButton;
    private Button directionButton;
    private Button zoomButton;

    @Nullable
    private String rejection;
    private long rejectionShownAtMs;
    /** Confirmation of the last direction change, so a click that only
     *  alters an arrowhead still visibly did something. */
    @Nullable
    private String flowNotice;
    private long flowNoticeShownAtMs;

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
        // The mode row spans the whole screen rather than just the map:
        // eight buttons across 320px leaves 38px each, which is narrower
        // than the words "Hold Line" and "Pattern" actually are.
        int modeCount = EditMode.values().length;
        int modeRowX = 10;
        int modeRowW = width - 20;
        int modeW = (modeRowW - (modeCount - 1) * 2) / modeCount;
        int btnX = modeRowX;
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

        // --- footer: clear, zoom, and done ---
        int footerY = mapY + mapH + 12;
        int footerW = Math.max(60, mapW / 4 - 4);
        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_map.clear"), b -> clearCurrent())
                .bounds(mapX, footerY, footerW, rowH)
                .build());

        int zoomX = mapX + footerW + 6;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoom(1))
                .bounds(zoomX, footerY, 20, rowH)
                .build());
        zoomButton = addRenderableWidget(Button.builder(zoomLabel(), b -> { })
                .bounds(zoomX + 22, footerY, 62, rowH)
                .build());
        zoomButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom(-1))
                .bounds(zoomX + 86, footerY, 20, rowH)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.skyport.airport_map.save"), b -> saveAndClose())
                .bounds(mapX + mapW - footerW, footerY, footerW, rowH)
                .build());

        sampleTerrain();
        lastTerrainSampleMs = System.currentTimeMillis();
    }

    private Component zoomLabel() {
        return Component.literal(blocksPerPixel() + " blk/px");
    }

    /** Positive `delta` zooms out (more blocks per pixel). */
    private void zoom(int delta) {
        int next = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, zoomIndex + delta));
        if (next == zoomIndex) return;
        zoomIndex = next;
        zoomButton.setMessage(zoomLabel());
        sampleTerrain(); // the whole grid means something different now
        lastTerrainSampleMs = System.currentTimeMillis();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInsideMap(mouseX, mouseY)) {
            zoom(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        if (mode == EditMode.HELIPAD) {
            List<String> names = new ArrayList<>(layout.helipads().keySet());
            if (!names.isEmpty()) layout.helipads().remove(names.get(names.size() - 1));
            return;
        }
        List<Waypoint> points = layout.waypoints(toWaypointType(mode));
        if (!points.isEmpty()) points.remove(points.size() - 1);
    }

    private void clearCurrent() {
        if (mode == EditMode.GATE) {
            layout.gates().clear();
        } else if (mode == EditMode.HELIPAD) {
            layout.helipads().clear();
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
            case HOLD_SHORT -> Waypoint.Type.HOLD_SHORT;
            case GATE, HELIPAD -> throw new IllegalArgumentException(mode + " is not a Waypoint.Type");
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInsideMap(mouseX, mouseY)) {
            BlockPos world = snapToExistingNode(screenToWorld((int) mouseX, (int) mouseY));

            // In Taxiway mode, a click on the BODY of an existing segment
            // changes its direction instead of placing a point.
            //
            // Two guards keep that from stealing clicks meant for drawing.
            // Near a node it always places, because joining to an existing
            // point is how every segment has to start - that is the one place
            // a legitimate click lands on top of existing geometry. And
            // mid-pair it always places, because you are half way through
            // drawing a segment and the second click is not optional.
            //
            // Without this, clicking a segment to redirect it silently drew a
            // new one instead, which is how a layout ends up with stray
            // taxiways going nowhere.
            if (mode == EditMode.TAXIWAY
                    && layout.waypoints(Waypoint.Type.TAXIWAY).size() % 2 == 0
                    && !isOnExistingNode(mouseX, mouseY)
                    && cycleFlowAt(world)) {
                return true;
            }

            String refusal = whyCantPlace(world);
            if (refusal != null) {
                rejection = refusal;
                rejectionShownAtMs = System.currentTimeMillis();
                return true;
            }
            rejection = null;

            if (mode == EditMode.GATE) {
                layout.gates().put(layout.nextGateName(), world);
            } else if (mode == EditMode.HELIPAD) {
                layout.helipads().put(layout.nextHelipadName(), world);
            } else {
                Waypoint.Type type = toWaypointType(mode);
                List<Waypoint> points = layout.waypoints(type);
                // Runway allows a second pair: the first is what arrivals
                // land on, an optional second is where departures roll, so
                // they stop queueing behind each other. A fifth click starts
                // the whole thing over, the same way a third used to.
                if (mode == EditMode.RUNWAY && points.size() >= MAX_RUNWAY_POINTS) points.clear();
                else if (isLineMode(mode) && mode != EditMode.RUNWAY && points.size() >= 2) points.clear();
                // Only one hold point per airport - a second click moves it.
                if (mode == EditMode.HOLD_SHORT) points.clear();
                points.add(new Waypoint(world, type, points.size()));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Pulls a click exactly onto a nearby existing node so lines actually
     * join up instead of "nearly" touching. Grid snapping alone isn't enough:
     * two points can both be on the 8-block grid and still be a grid step
     * apart, which leaves a gap the autopilot's connectivity check would
     * treat as a broken network.
     */
    private BlockPos snapToExistingNode(BlockPos candidate) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos node : allNodes()) {
            double d = horizontalDistance(node, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        // Scaled to the zoom, so joining is always the same few pixels of
        // slack on screen however far in or out you are.
        double radius = (double) NODE_SNAP_PIXELS * blocksPerPixel();
        return best != null && bestDist <= radius ? best : candidate;
    }

    private List<BlockPos> allNodes() {
        List<BlockPos> nodes = new ArrayList<>();
        for (Waypoint.Type type : Waypoint.Type.values()) {
            for (Waypoint w : layout.waypoints(type)) nodes.add(w.pos());
        }
        nodes.addAll(layout.gates().values());
        return nodes;
    }

    private List<BlockPos> nodesOf(Waypoint.Type... types) {
        List<BlockPos> nodes = new ArrayList<>();
        for (Waypoint.Type type : types) {
            for (Waypoint w : layout.waypoints(type)) nodes.add(w.pos());
        }
        return nodes;
    }

    private static boolean touches(List<BlockPos> nodes, BlockPos p) {
        for (BlockPos n : nodes) {
            if (n.getX() == p.getX() && n.getZ() == p.getZ()) return true;
        }
        return false;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Enforces that what gets drawn is a connected network the autopilot can
     * actually route over, rather than a pile of unrelated lines. Returns the
     * reason a click is refused, or null to allow it.
     */
    @Nullable
    /**
     * Cycle the direction of whichever taxiway segment was clicked.
     *
     * Picks by distance to the segment itself rather than to its endpoints:
     * the natural thing to click is the middle of the line you want to make
     * one-way, not one of the two dots at its ends.
     */
    private boolean cycleFlowAt(BlockPos clicked) {
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i + 1 < taxiway.size(); i += 2) {
            double distance = distanceToSegment(clicked,
                    taxiway.get(i).pos(), taxiway.get(i + 1).pos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }

        // In blocks, but scaled by zoom, so "near enough to mean that one"
        // stays the same apparent distance however far out you are.
        double tolerance = Math.max(4, FLOW_PICK_PIXELS * (double) blocksPerPixel());
        // Not on a segment - the caller treats this as an ordinary placing
        // click rather than an error, which is what lets one mode both draw
        // taxiways and redirect them.
        if (best < 0 || bestDistance > tolerance) return false;

        rejection = null;
        Waypoint start = taxiway.get(best);
        Waypoint.Flow next = start.flow().next();
        // The direction lives on the pair's first point; the second is
        // rewritten too so the two never disagree if this ever changes.
        taxiway.set(best, start.withFlow(next));
        taxiway.set(best + 1, taxiway.get(best + 1).withFlow(next));
        // One-way segments make it genuinely easy to draw a network where an
        // aircraft can reach a gate but never leave it, and that is not
        // visible by looking at the arrows. Say so straight away rather than
        // letting it be discovered by a stuck aircraft later.
        List<String> stranded = GroundNetwork.strandedGates(layout);
        if (stranded.isEmpty()) {
            flowNotice = "Segment " + (best / 2 + 1) + ": " + next.label();
            flowNoticeShownAtMs = System.currentTimeMillis();
        } else {
            rejection = "One-way as drawn strands " + String.join(", ", stranded)
                    + " - aircraft can't get there and back.";
            rejectionShownAtMs = System.currentTimeMillis();
        }
        return true;
    }

    /** Is the cursor sitting on a waypoint someone might be trying to join? */
    private boolean isOnExistingNode(double mouseX, double mouseY) {
        BlockPos raw = screenToWorld((int) mouseX, (int) mouseY);
        return !snapToExistingNode(raw).equals(raw);
    }

    /** Perpendicular distance from a point to a line segment, in blocks. */
    private static double distanceToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0
                : ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double ddx = p.getX() - (a.getX() + t * dx);
        double ddz = p.getZ() - (a.getZ() + t * dz);
        return Math.sqrt(ddx * ddx + ddz * ddz);
    }

    private String whyCantPlace(BlockPos p) {
        boolean hasRunway = layout.waypoints(Waypoint.Type.RUNWAY).size() >= 2;
        List<Waypoint> taxiway = layout.waypoints(Waypoint.Type.TAXIWAY);

        return switch (mode) {
            // The runway is the spine everything else hangs off, so it goes
            // down first and needs no connection of its own.
            case RUNWAY -> null;

            case TAXIWAY -> {
                if (!hasRunway) yield "Draw the runway first.";
                // Only the START of each segment must join the network; its
                // far end is the new ground being claimed.
                boolean startingSegment = taxiway.size() % 2 == 0;
                if (startingSegment && !touches(nodesOf(Waypoint.Type.RUNWAY, Waypoint.Type.TAXIWAY), p)) {
                    yield "Start a taxiway on the runway or an existing taxiway point.";
                }
                yield null;
            }

            case GATE -> {
                if (!hasRunway) yield "Draw the runway first.";
                if (!touches(nodesOf(Waypoint.Type.RUNWAY, Waypoint.Type.TAXIWAY), p)) {
                    yield "Gates go on the end of a runway or taxiway line.";
                }
                yield null;
            }

            case HOLDING_PATTERN -> null;

            // Pads stand alone - rotorcraft arrive vertically, so a helipad
            // needs no taxiway, no runway and nothing to connect to.
            case HELIPAD -> null;

            // One per airport, on the taxiway - it marks where the taxiway
            // stops being safe and the runway's protected area begins.
            case HOLD_SHORT -> {
                if (!hasRunway) yield "Draw the runway first.";
                if (taxiway.isEmpty()) yield "Draw a taxiway first.";
                if (!touches(nodesOf(Waypoint.Type.TAXIWAY), p)) {
                    yield "Put the hold point on a taxiway point.";
                }
                yield null;
            }

            case FINAL_LEG -> {
                if (!hasRunway) yield "Draw the runway first.";
                if (layout.waypoints(Waypoint.Type.HOLDING_PATTERN).size() < 3) {
                    yield "Draw the holding pattern first.";
                }
                // Point 0 leaves the holding pattern, point 1 meets the runway.
                boolean first = layout.waypoints(Waypoint.Type.FINAL_LEG).size() % 2 == 0;
                if (first && !touches(nodesOf(Waypoint.Type.HOLDING_PATTERN), p)) {
                    yield "Start the final leg on a holding pattern point.";
                }
                if (!first && !touches(nodesOf(Waypoint.Type.RUNWAY), p)) {
                    yield "End the final leg on a runway point.";
                }
                yield null;
            }
        };
    }

    private boolean isInsideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    /** Where a screen pixel actually is in the world - no snapping. Terrain
     *  sampling has to use this: snapping sample points to the placement grid
     *  collapses neighbouring pixels onto the same block, which is what made
     *  the map bear no resemblance to the ground. */
    private BlockPos screenToWorldRaw(int screenX, int screenY) {
        int dx = (screenX - mapX - mapW / 2) * blocksPerPixel();
        int dz = (screenY - mapY - mapH / 2) * blocksPerPixel();
        return new BlockPos(stationPos.getX() + dx, stationPos.getY(), stationPos.getZ() + dz);
    }

    /** Clicks land on the exact block under the cursor - the pixel-to-block
     *  ratio is the only thing limiting precision, so zoom in for finer
     *  placement. */
    private BlockPos screenToWorld(int screenX, int screenY) {
        return screenToWorldRaw(screenX, screenY);
    }

    private int blocksPerPixel() {
        return ZOOM_LEVELS[zoomIndex];
    }

    private int worldToScreenX(BlockPos pos) {
        return mapX + mapW / 2 + (pos.getX() - stationPos.getX()) / blocksPerPixel();
    }

    private int worldToScreenY(BlockPos pos) {
        return mapY + mapH / 2 + (pos.getZ() - stationPos.getZ()) / blocksPerPixel();
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
                sampled[cx][cy] = sampleTerrainColor(level, screenToWorldRaw(screenX, screenY));
            }
        }
        terrainColors = sampled;
    }

    /**
     * One terrain pixel, coloured the way a vanilla map does it: the surface
     * block's own MapColor, shaded lighter or darker depending on whether the
     * ground steps up or down going north.
     *
     * That relief shading is what turns a flat wash of green into something
     * you can actually read hills and valleys off - which matters here, since
     * the whole point is picking somewhere flat enough for a runway.
     */
    private static int sampleTerrainColor(@Nullable Level level, BlockPos column) {
        if (level == null || !level.hasChunkAt(column)) return FALLBACK_TERRAIN_COLOR;

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ());
        BlockPos surface = new BlockPos(column.getX(), surfaceY - 1, column.getZ());
        MapColor mapColor = level.getBlockState(surface).getMapColor(level, surface);
        if (mapColor == MapColor.NONE) return FALLBACK_TERRAIN_COLOR;

        int northY = level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ() - 1);
        int step = Integer.compare(surfaceY, northY);
        MapColor.Brightness brightness = switch (step) {
            case 1 -> MapColor.Brightness.HIGH;   // rising away from us
            case -1 -> MapColor.Brightness.LOW;   // falling away
            default -> MapColor.Brightness.NORMAL;
        };
        return toArgb(mapColor.calculateRGBColor(brightness));
    }

    /**
     * MapColor#calculateRGBColor returns ABGR, not ARGB - see its source:
     * it packs `blue << 16 | green << 8 | red`. GuiGraphics#fill wants ARGB,
     * so the red and blue channels have to be swapped or every colour comes
     * out inverted along that axis. Water was rendering red.
     */
    private static int toArgb(int abgr) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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

        drawRunways(guiGraphics, layout.waypoints(Waypoint.Type.RUNWAY));
        drawSegmentPairs(guiGraphics, layout.waypoints(Waypoint.Type.TAXIWAY), COLOR_TAXIWAY);
        drawPath(guiGraphics, layout.waypoints(Waypoint.Type.FINAL_LEG), COLOR_FINAL_LEG);
        drawLoop(guiGraphics, layout.waypoints(Waypoint.Type.HOLDING_PATTERN), COLOR_HOLDING);

        for (BlockPos gate : layout.gates().values()) {
            int gx = worldToScreenX(gate);
            int gy = worldToScreenY(gate);
            guiGraphics.fill(gx - 2, gy - 2, gx + 2, gy + 2, COLOR_GATE);
        }

        // Pads: a square with a dot, distinct from a gate's solid marker.
        for (BlockPos pad : layout.helipads().values()) {
            int px = worldToScreenX(pad);
            int py = worldToScreenY(pad);
            drawBorder(guiGraphics, px - 4, py - 4, 9, 9, COLOR_HELIPAD);
            guiGraphics.fill(px - 1, py - 1, px + 2, py + 2, COLOR_HELIPAD);
        }

        for (Waypoint hold : layout.waypoints(Waypoint.Type.HOLD_SHORT)) {
            int hx = worldToScreenX(hold.pos());
            int hy = worldToScreenY(hold.pos());
            drawBorder(guiGraphics, hx - 3, hy - 3, 7, 7, COLOR_HOLD_SHORT);
            guiGraphics.fill(hx - 1, hy - 1, hx + 2, hy + 2, COLOR_HOLD_SHORT);
        }

        drawPlayerMarker(guiGraphics);

        // Crosshair on the position the next click would actually land on -
        // including the pull onto a nearby node, so joining up is visible
        // before committing rather than a surprise afterwards.
        if (isInsideMap(mouseX, mouseY)) {
            BlockPos raw = screenToWorld(mouseX, mouseY);
            BlockPos hovered = snapToExistingNode(raw);
            boolean snapped = !hovered.equals(raw);
            int hx = worldToScreenX(hovered);
            int hy = worldToScreenY(hovered);
            int color = snapped ? 0xFF63D66B : 0x80FFFFFF;
            guiGraphics.fill(hx - 4, hy, hx + 5, hy + 1, color);
            guiGraphics.fill(hx, hy - 4, hx + 1, hy + 5, color);
            if (snapped) drawBorder(guiGraphics, hx - 4, hy - 4, 9, 9, color);
            guiGraphics.drawString(font,
                    "X " + hovered.getX() + "  Z " + hovered.getZ() + (snapped ? "  (join)" : ""),
                    mapX + 2, mapY + mapH + 2, 0xFFD9D9D9);
        }

        // Refusals fade out on their own - a stale "you can't do that" next to
        // a click that did work would be more confusing than no message.
        if (rejection != null && System.currentTimeMillis() - rejectionShownAtMs < REJECTION_VISIBLE_MS) {
            guiGraphics.drawString(font, rejection, mapX + 2, mapY + mapH + 12, 0xFFE0603A);
        } else if (flowNotice != null
                && System.currentTimeMillis() - flowNoticeShownAtMs < REJECTION_VISIBLE_MS) {
            guiGraphics.drawString(font, flowNotice, mapX + 2, mapY + mapH + 12, 0xFFE8C34A);
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
    /** Runway mode does two things now, so it has to say which one it is on. */
    private String runwayHint() {
        int drawn = layout.waypoints(Waypoint.Type.RUNWAY).size();
        if (drawn < 2) return "draw this first - 2 points: gate end, then far end";
        if (drawn == 2) return "optional: 2 more points for a departures-only runway";
        if (drawn == 3) return "one more point to finish the departure runway";
        return "both runways drawn - clicking again starts over";
    }

    private String hint() {
        return switch (mode) {
            case RUNWAY -> runwayHint();
            case TAXIWAY -> "pairs; click a drawn segment to flip its direction";
            case HOLDING_PATTERN -> "the airborne racetrack - click a loop of 3+ points";
            case FINAL_LEG -> "2 points: from holding pattern, to runway";
            case GATE -> "click the end of a runway or taxiway line";
            case HOLD_SHORT -> "the ground stop line - planes wait here for the runway";
            case HELIPAD -> "click anywhere - helicopters and blimps land here";
        };
    }

    /**
     * Runways, as pairs, with the departure strip drawn in its own colour.
     *
     * They have to look different or the map is ambiguous: two white lines
     * say nothing about which one arrivals use, and getting that backwards
     * means drawing a final leg to the wrong strip.
     */
    private void drawRunways(GuiGraphics guiGraphics, List<Waypoint> points) {
        for (int i = 0; i + 1 < points.size(); i += 2) {
            int color = i == 0 ? COLOR_RUNWAY : COLOR_RUNWAY_DEPARTURE;
            BlockPos a = points.get(i).pos();
            BlockPos b = points.get(i + 1).pos();
            int ax = worldToScreenX(a), ay = worldToScreenY(a);
            int bx = worldToScreenX(b), by = worldToScreenY(b);
            guiGraphics.fill(ax - 2, ay - 2, ax + 2, ay + 2, color);
            guiGraphics.fill(bx - 2, by - 2, bx + 2, by + 2, color);
            drawPixelLine(guiGraphics, ax, ay, bx, by, color);
        }
        // A lone trailing point - half way through drawing the second one.
        if (points.size() % 2 == 1) {
            BlockPos last = points.get(points.size() - 1).pos();
            int lx = worldToScreenX(last), ly = worldToScreenY(last);
            guiGraphics.fill(lx - 2, ly - 2, lx + 2, ly + 2, COLOR_RUNWAY_DEPARTURE);
        }
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

            // A one-way segment gets an arrowhead at its midpoint. Drawn on
            // top of the line in a dark colour rather than beside it, so the
            // direction is legible without a key and without moving the line
            // itself off where it actually is.
            Waypoint.Flow flow = points.get(i).flow();
            if (flow == Waypoint.Flow.FORWARD) {
                drawFlowArrow(guiGraphics, ax, ay, bx, by);
            } else if (flow == Waypoint.Flow.REVERSE) {
                drawFlowArrow(guiGraphics, bx, by, ax, ay);
            }
        }
        if (points.size() % 2 == 1) {
            BlockPos last = points.get(points.size() - 1).pos();
            int lx = worldToScreenX(last), ly = worldToScreenY(last);
            guiGraphics.fill(lx - 2, ly - 2, lx + 2, ly + 2, color);
        }
    }

    /**
     * An arrowhead at the middle of a segment, pointing from -> to.
     *
     * Built from two short lines swept back off the direction of travel,
     * which is the cheapest thing that still reads as an arrow at the two or
     * three pixels of width these lines actually get.
     */
    private void drawFlowArrow(GuiGraphics guiGraphics, int fromX, int fromY, int toX, int toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 6) return; // too short to draw anything legible on

        dx /= length;
        dy /= length;
        int midX = (fromX + toX) / 2;
        int midY = (fromY + toY) / 2;

        int size = 4;
        // Rotate the reversed direction by +/- 40 degrees for the two barbs.
        double angle = Math.toRadians(40);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double backX = -dx * size, backY = -dy * size;

        int leftX = midX + (int) Math.round(backX * cos - backY * sin);
        int leftY = midY + (int) Math.round(backX * sin + backY * cos);
        int rightX = midX + (int) Math.round(backX * cos + backY * sin);
        int rightY = midY + (int) Math.round(-backX * sin + backY * cos);

        drawPixelLine(guiGraphics, midX, midY, leftX, leftY, COLOR_FLOW_ARROW);
        drawPixelLine(guiGraphics, midX, midY, rightX, rightY, COLOR_FLOW_ARROW);
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
