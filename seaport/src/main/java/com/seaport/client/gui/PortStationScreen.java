package com.seaport.client.gui;

import com.seaport.data.PortLayout;
import com.seaport.data.Wharf;
import com.seaport.network.SavePortLayoutPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;

/**
 * The Port Station's editor: a chart of the harbour you draw berths on.
 *
 * This is Skyport's airport map, cut down to the one thing a harbour has to
 * be told. An airport is a network - runways, taxiways, holds, approach
 * legs - because an aeroplane cannot use ground nobody described. Water is
 * navigable by default, so the only thing missing is where the stone ends,
 * and a berth is exactly that: a line along a quay edge that ships come
 * alongside.
 *
 * Two differences from Skyport's map are worth knowing about:
 *
 * Water is shaded by DEPTH rather than drawn as one flat blue. On an airfield
 * map the water is scenery; here it is the thing you are navigating, and a
 * berth drawn against a bar a ship cannot reach looks exactly like a good one
 * until a vessel is sitting aground off it. {@link #waterColor} ramps pale to
 * dark over the first dozen blocks, which is the range that decides whether
 * anything can get in.
 *
 * And the whole layout is sent back on close rather than berth by berth, the
 * same as Skyport: a half-saved harbour is worse than an unsaved one.
 */
public class PortStationScreen extends Screen {

    /** Blocks per screen pixel. 1 is block-exact, which is what you want for
     *  the actual edge; 16 is for finding the far side of the bay. */
    private static final int[] ZOOM_LEVELS = { 1, 2, 4, 8, 16 };

    private static final int TERRAIN_CELL_SIZE = 2;        // screen px per sampled cell
    private static final long TERRAIN_RESAMPLE_INTERVAL_MS = 1000;
    private static final int FALLBACK_TERRAIN_COLOR = 0xFF32404A;

    /** How close a click has to be to count as grabbing an end, or as
     *  pointing at a berth. In pixels, so it stays the same size on screen
     *  however far the chart is zoomed out. */
    private static final int PICK_PIXELS = 5;

    private static final long NOTICE_VISIBLE_MS = 4000;

    private static final int TOOL_BUTTON_WIDTH = 58;

    // Water, pale shallows to dark deep. Not MapColor's water: vanilla gives
    // every ocean column the same blue, and "how deep is it here" is the one
    // question this chart exists to answer.
    private static final int WATER_SHALLOW = 0xFF5FB0CE;
    private static final int WATER_DEEP = 0xFF102C44;
    /** Depth, in blocks, at which the ramp bottoms out. Beyond this it is
     *  all deep enough that the difference stops mattering. */
    private static final int DEEP_WATER = 14;

    private static final int COLOR_BERTH = 0xFFE0812F;
    private static final int COLOR_BERTH_PICKED = 0xFFFFB45E;
    private static final int COLOR_BERTH_PENDING = 0xFFE8C34A;

    /** Pixels either side of a berth's centre line. Enough that it reads as
     *  a vessel lying alongside rather than as a line joining two places. */
    private static final int BERTH_HALF_WIDTH = 2;
    private static final int COLOR_PLAYER = 0xFFE33A3A;
    private static final int COLOR_STATION = 0xFF4E9E8C;
    private static final int COLOR_NOTICE = 0xFFE0603A;

    private enum Tool {
        DRAW("Draw", "Click where the bow goes, then where the stern goes."),
        MOVE("Move", "Drag the bow or the stern to somewhere better."),
        DELETE("Delete", "Click a berth to remove it.");

        final String label;
        final String hint;

        Tool(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    private final BlockPos stationPos;
    private final PortLayout layout;

    private int mapX, mapY, mapW, mapH;
    private int zoomIndex = 2;
    private int panBlocksX;
    private int panBlocksZ;

    private int[][] terrainColors = new int[0][0];
    private long lastTerrainSampleMs;

    private Tool tool = Tool.DRAW;
    private final EnumMap<Tool, Button> toolButtons = new EnumMap<>(Tool.class);

    private EditBox nameBox;
    private EditBox berthNameBox;
    private Button flipButton;
    private Button clearanceButton;
    private Button zoomButton;

    /**
     * The first end of a berth being drawn, or null when not part way through
     * one. Client-side and only until the second click - an abandoned
     * half-berth should disappear with the screen rather than become
     * something to clean up.
     */
    @Nullable
    private BlockPos pendingEnd;

    /** Which berth the name box and the highlight refer to. -1 for none. */
    private int selected = -1;

    /** The end currently being dragged, as an index into the berth list plus
     *  which of its two points. Null when nothing is being dragged. */
    @Nullable
    private Grab grabbed;

    private record Grab(int wharf, boolean fromEnd) { }

    /** Suppresses the berth name box's responder while its text is being set
     *  from the selection, which would otherwise rename the berth to whatever
     *  was already in the box. */
    private boolean loadingBerthName;

    @Nullable
    private String notice;
    private long noticeShownAtMs;

    public PortStationScreen(BlockPos stationPos, PortLayout layout) {
        super(Component.translatable("gui.seaport.port_station.title"));
        this.stationPos = stationPos;
        this.layout = layout;
        this.selected = layout.wharves().isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        // Fixed bands top and bottom, map gets the rest - the same shape as
        // Skyport's editor, and for the same reason: it keeps Save & Close
        // on screen at every GUI scale rather than only at the one it was
        // laid out on.
        int titleH = 14;
        int rowH = 20;
        int gap = 4;
        int topBand = titleH + gap + rowH + gap + rowH + gap;   // title + 2 rows
        int bottomBand = 12 + rowH + gap;                        // readout + footer

        int toolStripW = TOOL_BUTTON_WIDTH + gap;
        mapW = Math.min(320, width - 20 - toolStripW);
        mapH = Math.max(60, height - topBand - bottomBand - gap);
        mapX = (width - mapW + toolStripW) / 2;
        mapY = topBand;

        int row1Y = titleH + gap;
        int row2Y = row1Y + rowH + gap;

        // --- row 1: what this harbour is called, and how far out we can see
        int zoomW = 70;
        nameBox = addRenderableWidget(new EditBox(font, mapX, row1Y, mapW - zoomW - 4, rowH,
                Component.literal("Port name")));
        nameBox.setValue(layout.displayName());
        nameBox.setMaxLength(PortLayout.MAX_NAME_LENGTH);
        nameBox.setHint(Component.literal("Port name"));
        nameBox.setResponder(layout::setDisplayName);

        zoomButton = addRenderableWidget(Button.builder(zoomLabel(), b -> zoom(1))
                .bounds(mapX + mapW - zoomW, row1Y, zoomW, rowH).build());
        zoomButton.setTooltip(Tooltip.create(Component.literal(
                "Blocks per pixel. Scroll the chart to zoom; draw at 1 or 2 for an "
                        + "edge that lands on the block you mean.")));

        // --- row 2: how close ships stop. A stepper rather than free text:
        // it is a small whole number with real limits, and a text box invites
        // somebody to type 900 and wonder why nothing ever docks.
        int step = 20;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustClearance(-1))
                .bounds(mapX, row2Y, step, rowH).build());
        clearanceButton = addRenderableWidget(Button.builder(clearanceLabel(), b -> { })
                .bounds(mapX + step + 2, row2Y, mapW - step * 2 - 4, rowH).build());
        clearanceButton.active = false;
        clearanceButton.setTooltip(Tooltip.create(Component.literal(
                "How far a hull stops from the quay edge. Measured from whichever part of "
                        + "the ship is nearest, so it holds however the vessel is lying.")));
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustClearance(1))
                .bounds(mapX + mapW - step, row2Y, step, rowH).build());

        // --- tool strip, down the left of the chart.
        //
        // Beside the map rather than above it because these are verbs acting
        // on what the map shows, and because the rows above are already full.
        int toolY = mapY;
        for (Tool each : Tool.values()) {
            Button button = addRenderableWidget(Button.builder(Component.literal(each.label),
                            b -> selectTool(each))
                    .bounds(mapX - toolStripW, toolY, TOOL_BUTTON_WIDTH, rowH).build());
            button.setTooltip(Tooltip.create(Component.literal(each.hint)));
            toolButtons.put(each, button);
            toolY += rowH + gap;
        }

        // Marking from where you stand, kept from the first version of this
        // screen. The chart is block-exact when zoomed in, but walking to the
        // corner of a quay you have just built is still the surest way to hit
        // it, and it costs one button to keep.
        Button atFeet = addRenderableWidget(Button.builder(Component.literal("At feet"),
                        b -> markAtPlayer())
                .bounds(mapX - toolStripW, toolY, TOOL_BUTTON_WIDTH, rowH).build());
        atFeet.setTooltip(Tooltip.create(Component.literal(
                "Use where you are standing as the next end, instead of clicking the chart.")));
        toolY += rowH + gap;

        Button centre = addRenderableWidget(Button.builder(Component.literal("Centre"),
                        b -> { panBlocksX = 0; panBlocksZ = 0; sampleTerrain(); })
                .bounds(mapX - toolStripW, toolY, TOOL_BUTTON_WIDTH, rowH).build());
        centre.setTooltip(Tooltip.create(Component.literal(
                "Bring the chart back to the station. Right-drag the chart to pan.")));

        // --- footer: name the selected berth, turn it round, and leave.
        int footerY = height - rowH - gap;
        int saveW = 100;
        int flipW = 40;
        berthNameBox = addRenderableWidget(new EditBox(font, mapX, footerY,
                mapW - saveW - flipW - 8, rowH, Component.literal("Berth name")));
        berthNameBox.setMaxLength(Wharf.MAX_NAME_LENGTH);
        berthNameBox.setHint(Component.literal("Berth name"));
        berthNameBox.setResponder(this::renameSelected);

        flipButton = addRenderableWidget(Button.builder(Component.literal("Flip"), b -> flipSelected())
                .bounds(mapX + mapW - saveW - flipW - 4, footerY, flipW, rowH).build());
        flipButton.setTooltip(Tooltip.create(Component.literal(
                "Swap the bow and stern of the selected berth, so a ship lies the other way "
                        + "round. Quicker than redrawing it the right way.")));

        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> saveAndClose())
                .bounds(mapX + mapW - saveW, footerY, saveW, rowH).build());

        selectTool(tool);
        refresh();
        sampleTerrain();
    }

    // ---- controls --------------------------------------------------------

    private Component clearanceLabel() {
        int blocks = layout.berthClearance();
        return Component.literal("Berth clearance: " + blocks + (blocks == 1 ? " block" : " blocks"));
    }

    private Component zoomLabel() {
        return Component.literal(blocksPerPixel() + " blk/px");
    }

    private void adjustClearance(int delta) {
        layout.setBerthClearance(layout.berthClearance() + delta);
        refresh();
    }

    private void selectTool(Tool picked) {
        tool = picked;
        // Starting a different job abandons a half-drawn berth. Carrying the
        // first end across into Delete and back would mean a click somewhere
        // unrelated silently closed a line.
        pendingEnd = null;
        grabbed = null;
        toolButtons.forEach((each, button) -> button.active = each != picked);
        refresh();
    }

    private void zoom(int delta) {
        int next = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, zoomIndex + delta));
        // Wrap on the button so one control covers the whole range; the
        // scroll wheel below steps without wrapping, which is what a wheel
        // should do.
        if (next == zoomIndex && delta > 0) next = 0;
        zoomIndex = next;
        zoomButton.setMessage(zoomLabel());
        sampleTerrain();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInsideMap(mouseX, mouseY)) {
            int next = Math.max(0, Math.min(ZOOM_LEVELS.length - 1,
                    zoomIndex - (int) Math.signum(scrollY)));
            if (next != zoomIndex) {
                zoomIndex = next;
                zoomButton.setMessage(zoomLabel());
                sampleTerrain();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void refresh() {
        clearanceButton.setMessage(clearanceLabel());
        zoomButton.setMessage(zoomLabel());

        boolean hasSelection = selected >= 0 && selected < layout.wharves().size();
        berthNameBox.active = hasSelection;
        flipButton.active = hasSelection;
        loadingBerthName = true;
        berthNameBox.setValue(hasSelection ? layout.wharves().get(selected).name() : "");
        loadingBerthName = false;
    }

    /** Turn the selected berth round, so a ship lies bow the other way.
     *  Which way a vessel points decides which of its sides comes alongside,
     *  and getting it backwards should be one click to undo rather than a
     *  redraw. */
    private void flipSelected() {
        if (selected < 0 || selected >= layout.wharves().size()) return;
        Wharf wharf = layout.wharves().get(selected);
        layout.wharves().set(selected, new Wharf(wharf.name(), wharf.to(), wharf.from()));
    }

    private void renameSelected(String typed) {
        if (loadingBerthName) return;
        if (selected < 0 || selected >= layout.wharves().size()) return;
        Wharf current = layout.wharves().get(selected);
        // Cleaned rather than refused: refusing keystrokes in a text box
        // fights the player, and a berth has to end up with SOME name because
        // that name is what a schedule points at.
        layout.wharves().set(selected, current.withName(Wharf.clean(typed, current.name())));
    }

    private void say(String message) {
        notice = message;
        noticeShownAtMs = System.currentTimeMillis();
    }

    // ---- editing ---------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInsideMap(mouseX, mouseY)) {
            BlockPos at = screenToWorld((int) mouseX, (int) mouseY);
            switch (tool) {
                case DRAW -> placeEnd(at);
                case MOVE -> grabbed = findEnd(mouseX, mouseY);
                case DELETE -> deleteAt(mouseX, mouseY);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Right-drag pans, in every tool. Deliberately the right button: the
        // left one is already drawing, grabbing and deleting, and a modifier
        // key nobody is told about is not discoverable either.
        if (button == 1 && isInsideMap(mouseX, mouseY)) {
            panBlocksX -= (int) Math.round(dragX) * blocksPerPixel();
            panBlocksZ -= (int) Math.round(dragY) * blocksPerPixel();
            sampleTerrain();
            return true;
        }

        if (button == 0 && grabbed != null && grabbed.wharf() < layout.wharves().size()) {
            moveGrabbedTo(screenToWorld((int) mouseX, (int) mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && grabbed != null) {
            grabbed = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Take one end of a quay edge.
     *
     * Two clicks make a berth. A line with both ends in the same block is
     * refused rather than stored: a quay with no direction has no side for a
     * ship to come alongside, and every berthing calculation is measured
     * against that direction.
     */
    private void placeEnd(BlockPos at) {
        if (pendingEnd == null) {
            pendingEnd = at;
            return;
        }

        Wharf wharf = new Wharf(layout.nextWharfName(), pendingEnd, at);
        // Consumes the pending end either way: leaving it set would mean the
        // next click tried to close the same zero-length line again.
        pendingEnd = null;
        if (!wharf.isUsable()) {
            say("Too short - the bow and stern cannot be the same block");
            return;
        }
        layout.wharves().add(wharf);
        selected = layout.wharves().size() - 1;
        refresh();
    }

    /** The same thing from where the player is standing. Shares the pending
     *  end with the chart, so one end can be clicked and the other walked to. */
    private void markAtPlayer() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        placeEnd(player.blockPosition());
    }

    /** The berth end nearest the cursor, if one is close enough to have been
     *  aimed at. Selects that berth as a side effect - you are about to move
     *  it, so it is the one the name box should be describing. */
    @Nullable
    private Grab findEnd(double mouseX, double mouseY) {
        List<Wharf> wharves = layout.wharves();
        Grab best = null;
        double bestDistance = PICK_PIXELS;
        for (int i = 0; i < wharves.size(); i++) {
            Wharf wharf = wharves.get(i);
            double toFrom = pixelDistance(mouseX, mouseY, wharf.from());
            if (toFrom <= bestDistance) {
                bestDistance = toFrom;
                best = new Grab(i, true);
            }
            double toTo = pixelDistance(mouseX, mouseY, wharf.to());
            if (toTo <= bestDistance) {
                bestDistance = toTo;
                best = new Grab(i, false);
            }
        }
        if (best != null) {
            selected = best.wharf();
            refresh();
        }
        return best;
    }

    /**
     * Put the grabbed end somewhere else.
     *
     * A drag that would collapse the berth onto its other end is ignored
     * rather than applied, because a zero-length line is the one thing
     * {@link Wharf} refuses to exist as - and dropping the berth entirely
     * halfway through a drag would be a surprising way to lose it.
     */
    private void moveGrabbedTo(BlockPos to) {
        Wharf wharf = layout.wharves().get(grabbed.wharf());
        Wharf moved = grabbed.fromEnd()
                ? new Wharf(wharf.name(), to, wharf.to())
                : new Wharf(wharf.name(), wharf.from(), to);
        if (!moved.isUsable()) return;
        layout.wharves().set(grabbed.wharf(), moved);
    }

    /** Remove whichever berth the cursor is pointing at - judged against the
     *  whole line, not just its ends, because the line is what is drawn and
     *  so it is what somebody aims at. */
    private void deleteAt(double mouseX, double mouseY) {
        List<Wharf> wharves = layout.wharves();
        int hit = -1;
        double bestDistance = PICK_PIXELS;
        for (int i = 0; i < wharves.size(); i++) {
            Wharf wharf = wharves.get(i);
            double distance = pixelDistanceToSegment(mouseX, mouseY, wharf.from(), wharf.to());
            if (distance <= bestDistance) {
                bestDistance = distance;
                hit = i;
            }
        }
        if (hit < 0) {
            say("Nothing there - click on the berth line itself");
            return;
        }
        wharves.remove(hit);
        selected = wharves.isEmpty() ? -1 : Math.min(hit, wharves.size() - 1);
        refresh();
    }

    private double pixelDistance(double mouseX, double mouseY, BlockPos pos) {
        double dx = mouseX - worldToScreenX(pos);
        double dy = mouseY - worldToScreenY(pos);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Point-to-segment distance, in screen pixels. The projection is clamped
     *  to the segment so a click far off the end of a short berth is not
     *  treated as a hit on the infinite line through it. */
    private double pixelDistanceToSegment(double mouseX, double mouseY, BlockPos a, BlockPos b) {
        double ax = worldToScreenX(a), ay = worldToScreenY(a);
        double bx = worldToScreenX(b), by = worldToScreenY(b);
        double dx = bx - ax, dy = by - ay;
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1.0e-6) return Math.hypot(mouseX - ax, mouseY - ay);
        double t = Math.max(0, Math.min(1, ((mouseX - ax) * dx + (mouseY - ay) * dy) / lengthSq));
        return Math.hypot(mouseX - (ax + t * dx), mouseY - (ay + t * dy));
    }

    // ---- saving ----------------------------------------------------------

    private void saveAndClose() {
        layout.setDisplayName(nameBox.getValue());
        PacketDistributor.sendToServer(new SavePortLayoutPayload(stationPos, layout));
        super.onClose();
    }

    /** Closing without pressing Save still saves. Editing a harbour and
     *  walking away is not an instruction to discard it, and Skyport's
     *  airport map settled the same question the same way. */
    @Override
    public void onClose() {
        saveAndClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- the chart -------------------------------------------------------

    private boolean isInsideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    private int blocksPerPixel() {
        return ZOOM_LEVELS[zoomIndex];
    }

    private int centreX() {
        return stationPos.getX() + panBlocksX;
    }

    private int centreZ() {
        return stationPos.getZ() + panBlocksZ;
    }

    private BlockPos screenToWorld(int screenX, int screenY) {
        int dx = (screenX - mapX - mapW / 2) * blocksPerPixel();
        int dz = (screenY - mapY - mapH / 2) * blocksPerPixel();
        return new BlockPos(centreX() + dx, stationPos.getY(), centreZ() + dz);
    }

    private int worldToScreenX(BlockPos pos) {
        return mapX + mapW / 2 + (pos.getX() - centreX()) / blocksPerPixel();
    }

    private int worldToScreenY(BlockPos pos) {
        return mapY + mapH / 2 + (pos.getZ() - centreZ()) / blocksPerPixel();
    }

    /** Samples terrain into a coarse cached grid - on open, on every zoom and
     *  pan, and once a second after, so chunks streaming in fill themselves
     *  out while the chart is open. */
    private void sampleTerrain() {
        Level level = Minecraft.getInstance().level;
        int cols = Math.max(1, mapW / TERRAIN_CELL_SIZE);
        int rows = Math.max(1, mapH / TERRAIN_CELL_SIZE);
        int[][] sampled = new int[cols][rows];
        for (int cx = 0; cx < cols; cx++) {
            for (int cy = 0; cy < rows; cy++) {
                int screenX = mapX + cx * TERRAIN_CELL_SIZE + TERRAIN_CELL_SIZE / 2;
                int screenY = mapY + cy * TERRAIN_CELL_SIZE + TERRAIN_CELL_SIZE / 2;
                sampled[cx][cy] = sampleColor(level, screenToWorld(screenX, screenY));
            }
        }
        terrainColors = sampled;
        lastTerrainSampleMs = System.currentTimeMillis();
    }

    /**
     * One chart pixel.
     *
     * Water is shaded by how deep it is; everything else gets the vanilla
     * map treatment - the surface block's own MapColor, lit lighter or darker
     * depending on whether the ground steps up or down going north, which is
     * what turns a flat wash of green into something you can read a coastline
     * off.
     */
    private static int sampleColor(@Nullable Level level, BlockPos column) {
        if (level == null || !level.hasChunkAt(column)) return FALLBACK_TERRAIN_COLOR;

        int x = column.getX();
        int z = column.getZ();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

        BlockPos.MutableBlockPos surface = new BlockPos.MutableBlockPos(x, surfaceY - 1, z);
        if (level.getBlockState(surface).getFluidState().is(FluidTags.WATER)) {
            // Counted down block by block rather than read off the OCEAN_FLOOR
            // heightmap, because the server only ships MOTION_BLOCKING and
            // WORLD_SURFACE to clients - asking for OCEAN_FLOOR here makes the
            // client rebuild it for every chunk the chart touches. The count
            // stops at DEEP_WATER since everything past that shades the same.
            int depth = 1;
            while (depth <= DEEP_WATER) {
                surface.setY(surfaceY - 1 - depth);
                if (!level.getBlockState(surface).getFluidState().is(FluidTags.WATER)) break;
                depth++;
            }
            return waterColor(depth);
        }

        surface.setY(surfaceY - 1);
        MapColor mapColor = level.getBlockState(surface).getMapColor(level, surface);
        if (mapColor == MapColor.NONE) return FALLBACK_TERRAIN_COLOR;

        int northY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1);
        MapColor.Brightness brightness = switch (Integer.compare(surfaceY, northY)) {
            case 1 -> MapColor.Brightness.HIGH;   // rising away from us
            case -1 -> MapColor.Brightness.LOW;   // falling away
            default -> MapColor.Brightness.NORMAL;
        };
        return toArgb(mapColor.calculateRGBColor(brightness));
    }

    /** Pale in the shallows, dark offshore. The ramp is deliberately steep
     *  over the first few blocks: the difference between one block of water
     *  and four is the difference between a mud flat and somewhere a ship can
     *  actually sit, and that is the part of the chart you are reading when
     *  you place a berth. */
    private static int waterColor(int depth) {
        float t = Math.min(1f, (float) depth / DEEP_WATER);
        t = (float) Math.sqrt(t);   // steep near the shore, flat out at sea
        int r = lerp((WATER_SHALLOW >> 16) & 0xFF, (WATER_DEEP >> 16) & 0xFF, t);
        int g = lerp((WATER_SHALLOW >> 8) & 0xFF, (WATER_DEEP >> 8) & 0xFF, t);
        int b = lerp(WATER_SHALLOW & 0xFF, WATER_DEEP & 0xFF, t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /**
     * MapColor#calculateRGBColor returns ABGR, not ARGB - see its source: it
     * packs `blue << 16 | green << 8 | red`. GuiGraphics#fill wants ARGB, so
     * the channels have to be swapped or everything comes out inverted along
     * that axis. In Skyport this showed up as red water.
     */
    private static int toArgb(int abgr) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Vanilla's default draws a blur pass plus a translucent menu texture,
     *  which over a chart reads as "out of focus". A flat opaque fill keeps
     *  the terrain grid and the berth lines crisp. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF141A1F);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // NB: Screen#render calls renderBackground itself before drawing
        // widgets, so the chart has to come AFTER super.render - drawing it
        // first means the background pass wipes it out and leaves buttons on
        // a blank panel.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (System.currentTimeMillis() - lastTerrainSampleMs > TERRAIN_RESAMPLE_INTERVAL_MS) {
            sampleTerrain();
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

        // Everything below is drawn in world coordinates, which means a berth
        // off the edge of the view lands somewhere off the edge of the chart -
        // and without this would scribble its line across the toolbars.
        guiGraphics.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);

        drawBerths(guiGraphics);
        drawStationMarker(guiGraphics);
        drawPlayerMarker(guiGraphics);

        // The bow that has been placed, waiting for its stern. Deliberately
        // NOT joined to the cursor: a line that follows the mouse reads as
        // the berth itself being dragged out, when what is actually on the
        // chart so far is one point. The berth appears whole on the second
        // click or not at all.
        if (pendingEnd != null) {
            drawBow(guiGraphics, worldToScreenX(pendingEnd), worldToScreenY(pendingEnd),
                    COLOR_BERTH_PENDING);
        }

        guiGraphics.disableScissor();

        guiGraphics.drawString(font, title, mapX, 3, 0xFFFFFFFF, false);

        int readoutY = mapY + mapH + 2;
        if (isInsideMap(mouseX, mouseY)) {
            BlockPos under = screenToWorld((int) mouseX, (int) mouseY);
            drawCrosshair(guiGraphics, (int) mouseX, (int) mouseY);
            guiGraphics.drawString(font, "X " + under.getX() + "  Z " + under.getZ()
                    + "   " + hint(), mapX + 2, readoutY, 0xFFD9D9D9, false);
        } else {
            guiGraphics.drawString(font, hint(), mapX + 2, readoutY, 0xFFAAAAAA, false);
        }

        // Refusals fade on their own - a stale "you can't do that" sitting
        // next to a click that did work is worse than no message at all.
        if (notice != null && System.currentTimeMillis() - noticeShownAtMs < NOTICE_VISIBLE_MS) {
            guiGraphics.drawString(font, notice, mapX + 2, readoutY + 10, COLOR_NOTICE, false);
        }
    }

    /** What clicking actually does right now. Worth a line: with no berths
     *  drawn, an empty chart gives no clue that it is something you draw on. */
    private String hint() {
        if (tool == Tool.DRAW) {
            return pendingEnd == null
                    ? (layout.wharves().isEmpty() ? "click where the bow goes" : "click to start a berth - bow first")
                    : "now click where the stern goes";
        }
        return tool.hint;
    }

    /**
     * Berths, drawn as the vessel that will lie along them.
     *
     * A solid bar rather than a one-pixel stroke, with the two ends shaped
     * differently - a point for the bow, a square transom for the stern.
     * A thin line between two identical dots looks like something joining two
     * places together, which is the one thing a berth is not: it is a single
     * edge, with a ship on it, lying a particular way round.
     */
    private void drawBerths(GuiGraphics guiGraphics) {
        List<Wharf> wharves = layout.wharves();
        for (int i = 0; i < wharves.size(); i++) {
            Wharf wharf = wharves.get(i);
            int ax = worldToScreenX(wharf.from()), ay = worldToScreenY(wharf.from());
            int bx = worldToScreenX(wharf.to()), by = worldToScreenY(wharf.to());
            boolean picked = i == selected;
            int color = picked ? COLOR_BERTH_PICKED : COLOR_BERTH;

            drawThickLine(guiGraphics, ax, ay, bx, by, BERTH_HALF_WIDTH, color);
            drawBow(guiGraphics, ax, ay, color);
            drawStern(guiGraphics, bx, by, color);

            // Which end is which, and what it is called, on the selected one
            // only. Every berth labelled makes a busy harbour unreadable at
            // four blocks to the pixel.
            if (picked) {
                drawBorder(guiGraphics, ax - 5, ay - 5, 11, 11, 0xFFFFFFFF);
                drawBorder(guiGraphics, bx - 5, by - 5, 11, 11, 0xFFFFFFFF);
                guiGraphics.drawString(font, "bow", ax + 7, ay - 4, 0xFFFFFFFF, false);
                guiGraphics.drawString(font, "stern", bx + 7, by - 4, 0xFFFFFFFF, false);
                guiGraphics.drawString(font, wharf.name(),
                        (ax + bx) / 2 + 7, (ay + by) / 2 + 4, 0xFFFFFFFF, false);
            }
        }
    }

    /** A line with width, built from parallel strokes stepped across the
     *  perpendicular. Half-pixel steps because whole ones round onto each
     *  other on a diagonal and leave the bar full of holes. */
    private static void drawThickLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1,
                                      int half, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            guiGraphics.fill(x0 - half, y0 - half, x0 + half + 1, y0 + half + 1, color);
            return;
        }
        double nx = -dy / length, ny = dx / length;
        for (double offset = -half; offset <= half; offset += 0.5) {
            int ox = (int) Math.round(nx * offset);
            int oy = (int) Math.round(ny * offset);
            drawPixelLine(guiGraphics, x0 + ox, y0 + oy, x1 + ox, y1 + oy, color);
        }
    }

    /** The bow: a point. */
    private static void drawBow(GuiGraphics guiGraphics, int x, int y, int color) {
        for (int dy = -3; dy <= 3; dy++) {
            int w = 3 - Math.abs(dy);
            guiGraphics.fill(x - w, y + dy, x + w + 1, y + dy + 1, color);
        }
    }

    /** The stern: a square transom, so the two ends are told apart at a
     *  glance without reading anything. */
    private static void drawStern(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.fill(x - 3, y - 3, x + 4, y + 4, color);
    }

    /** The station itself, so there is something on the chart to relate the
     *  rest of it to even before anything is drawn. */
    private void drawStationMarker(GuiGraphics guiGraphics) {
        drawBorder(guiGraphics, worldToScreenX(stationPos) - 3, worldToScreenY(stationPos) - 3,
                7, 7, COLOR_STATION);
    }

    /** Where the player is standing, as a red cross. Nothing marks a berth in
     *  the actual world, so without this there is no way to relate a line on
     *  the chart to somewhere you can walk to. Clamped to the edge when off
     *  chart, so it still points the right way. */
    private void drawPlayerMarker(GuiGraphics guiGraphics) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        BlockPos at = player.blockPosition();
        int px = Math.max(mapX + 1, Math.min(mapX + mapW - 2, worldToScreenX(at)));
        int py = Math.max(mapY + 1, Math.min(mapY + mapH - 2, worldToScreenY(at)));
        guiGraphics.fill(px - 3, py, px + 4, py + 1, COLOR_PLAYER);
        guiGraphics.fill(px, py - 3, px + 1, py + 4, COLOR_PLAYER);
    }

    private static void drawCrosshair(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x - 4, y, x + 5, y + 1, 0x80FFFFFF);
        guiGraphics.fill(x, y - 4, x + 1, y + 5, 0x80FFFFFF);
    }

    private static void drawBorder(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Bresenham, plotting single pixels - deliberately blocky rather than a
     *  smooth line, to match everything else drawn in this game. */
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
}
