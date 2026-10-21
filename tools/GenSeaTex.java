import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates SeaPort's 16x16 block textures.
 *
 * Pixel-exact, no anti-aliasing, the way vanilla and Create textures are
 * authored - see DESIGN.md. Kept as a generator rather than nine PNGs nobody
 * can regenerate: a palette change here is a diff, and a palette change to
 * binaries is an archaeology exercise.
 *
 * The models are Skyport's, so the textures have to be laid out the way those
 * models sample them. Two of them are not free-form pictures:
 *
 * The radar's side texture is split into three horizontal BANDS, because the
 * tower model stacks a plinth, a shaft and a cabin that each take a slice of
 * one texture rather than a copy of the whole - rows 0-6 are the cabin, 6-12
 * the shaft, 12-16 the plinth. Draw a picture across the whole square and it
 * comes out as three unrelated fragments.
 *
 * The station's side texture is sampled from row 2 down for the walls, with
 * rows 0-2 used for the bezel that turns the corner onto the top face. So the
 * top two rows have to be the brass edging, or the rim of the console does not
 * meet the line running up its wall.
 *
 * Run with a JDK 21, no build needed:
 *   java tools/GenSeaTex.java seaport/src/main/resources/assets/seaport/textures/block
 */
public class GenSeaTex {

    // Weathered steel rather than Skyport's andesite grey: this is a mod
    // about things that live in salt water.
    static final int HULL        = 0xFF46606F;
    static final int HULL_DARK   = 0xFF2E424E;
    static final int HULL_LITE   = 0xFF5E7C8C;
    static final int BRASS       = 0xFFC6A664;
    static final int BRASS_DARK  = 0xFF8A7440;
    static final int DEEP        = 0xFF14212E;
    // Oxidised copper, for the parts that have been out in the weather.
    static final int VERDIGRIS   = 0xFF4E9E8C;
    static final int CHART       = 0xFFD9CFB4;
    static final int CHART_LINE  = 0xFF8A7C5C;
    static final int WATER       = 0xFF2F6E8F;
    static final int WATER_DEEP  = 0xFF1E4A63;
    static final int SCOPE       = 0xFF0E2A20;
    static final int SCOPE_RING  = 0xFF2E7A4A;
    static final int SWEEP       = 0xFF7FE3A0;
    static final int BUOY_RED    = 0xFFB5362E;
    static final int LAMP        = 0xFFF2D06B;
    static final int FOAM        = 0xFFD9D7CC;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();

        ImageIO.write(hullPanel(), "png", new File(dir, "port_station_side.png"));
        ImageIO.write(chart(), "png", new File(dir, "port_station.png"));

        ImageIO.write(radarSide(), "png", new File(dir, "marine_radar_side.png"));
        ImageIO.write(radarScope(), "png", new File(dir, "marine_radar_top.png"));
        ImageIO.write(radarScope(), "png", new File(dir, "marine_radar.png"));

        ImageIO.write(instrumentCase(), "png", new File(dir, "marine_autopilot_side.png"));
        ImageIO.write(helm(), "png", new File(dir, "marine_autopilot_top.png"));
        ImageIO.write(helm(), "png", new File(dir, "marine_autopilot.png"));

        ImageIO.write(beaconMast(), "png", new File(dir, "marine_beacon.png"));

        System.out.println("wrote 9 textures to " + dir);
    }

    // ---- helpers ---------------------------------------------------------

    static BufferedImage blank() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    static void fill(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (x >= 0 && x < 16 && y >= 0 && y < 16) img.setRGB(x, y, argb);
            }
        }
    }

    static void rect(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        fill(img, x0, y0, x1, y0, argb);
        fill(img, x0, y1, x1, y1, argb);
        fill(img, x0, y0, x0, y1, argb);
        fill(img, x1, y0, x1, y1, argb);
    }

    /** A deterministic speckle, so plating does not read as flat colour.
     *  Seeded by position rather than random, so the file is identical on
     *  every run and a regeneration is an empty diff. */
    static void weather(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (((x * 7 + y * 13) % 11) == 0) img.setRGB(x, y, argb);
            }
        }
    }

    // ---- the textures ----------------------------------------------------

    /**
     * Hull plating inside a brass frame, on ALL FOUR sides.
     *
     * The frame has to go right round, and the first version of this got it
     * wrong by edging only the top and bottom. The console and instrument
     * models wrap the texture's outer bands onto their top faces as a bezel -
     * and not just the horizontal ones. They sample columns 0-3 and 13-16 in
     * plan for the left and right of the rim, so an edge that is hull grey
     * there puts a grey stripe across what should be a continuous brass lip,
     * with the frame visibly breaking at two corners.
     *
     * The interior is left plain where the models cut their recesses into it -
     * see KEEP_CLEAR. Detail inside those patches reads as dirt on a dial.
     */
    static BufferedImage hullPanel() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL);

        brassFrame(img);

        // Rivets, in the corners of the interior where nothing samples.
        for (int x : new int[] { 3, 12 }) {
            for (int y : new int[] { 10, 12 }) img.setRGB(x, y, HULL_LITE);
        }
        recess(img, 4, 6, 10, 8);        // the console's sunken screen surround
        return img;
    }

    /**
     * The autopilot's case: the same frame, a different interior.
     *
     * Its model cuts a different recess from the station's - rows 3 to 6
     * rather than 6 to 8 - so the two cannot share one texture without one
     * of them showing plating where its instrument should be.
     */
    static BufferedImage instrumentCase() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL);

        brassFrame(img);
        recess(img, 5, 3, 11, 6);        // the instrument face

        // A pair of gauges below the recess, clear of everything sampled.
        img.setRGB(5, 9, VERDIGRIS);
        img.setRGB(6, 9, BRASS);
        img.setRGB(10, 9, VERDIGRIS);
        img.setRGB(9, 9, BRASS);
        fill(img, 4, 11, 11, 11, HULL_DARK);
        return img;
    }

    /**
     * Two pixels of brass all the way round, lit from the top left.
     *
     * Two rather than one because the models sample three-pixel bands in
     * plan: one pixel of brass inside a three-pixel strip reads as a grey
     * lip with a thin line on it rather than as brass.
     */
    static void brassFrame(BufferedImage img) {
        fill(img, 0, 0, 15, 1, BRASS);
        fill(img, 0, 0, 1, 15, BRASS);
        fill(img, 0, 14, 15, 15, BRASS_DARK);
        fill(img, 14, 0, 15, 15, BRASS_DARK);
        // Row 0 stays the lighter brass across its full width: the rim's
        // outward face is sampled from it alone, and a darker corner there
        // shows up as a notch in the lip.
        fill(img, 0, 0, 15, 0, BRASS);
    }

    /** A sunken panel the model cuts into, left plain so it reads as a
     *  recess rather than as plating with something spilled on it. */
    static void recess(BufferedImage img, int x0, int y0, int x1, int y1) {
        fill(img, x0, y0, x1, y1, DEEP);
        fill(img, x0, y0, x1, y0, HULL_DARK);
    }

    /** The harbourmaster's chart: paper, a coastline, and a route. */
    static BufferedImage chart() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, BRASS_DARK);
        fill(img, 1, 1, 14, 14, CHART);

        // Water to the south-east, land to the north-west: a coastline that
        // reads as one at a glance rather than a blob.
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 13; x++) {
                if (x + y > 15) img.setRGB(x, y, WATER);
                if (x + y > 21) img.setRGB(x, y, WATER_DEEP);
            }
        }
        // Grid lines, the way a chart is ruled.
        for (int i = 2; i <= 13; i += 5) {
            for (int j = 2; j <= 13; j++) {
                if (((i + j) % 2) == 0) {
                    img.setRGB(i, j, CHART_LINE);
                    img.setRGB(j, i, CHART_LINE);
                }
            }
        }
        // A plotted course, running out to sea.
        img.setRGB(4, 11, BUOY_RED);
        img.setRGB(6, 10, BUOY_RED);
        img.setRGB(8, 10, BUOY_RED);
        img.setRGB(10, 11, BUOY_RED);
        img.setRGB(12, 12, BUOY_RED);
        return img;
    }

    /**
     * Three bands for the three pieces of the tower - see the note at the
     * top. Cabin rows 0-5 get the windows, shaft 6-11 is plain, plinth 12-15
     * is heavier.
     */
    static BufferedImage radarSide() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL);
        weather(img, 0, 0, 15, 15, HULL_DARK);

        // Cabin: a lit window band, brass sill under it.
        fill(img, 0, 0, 15, 0, BRASS_DARK);
        fill(img, 1, 1, 14, 4, DEEP);
        for (int x = 2; x <= 13; x += 3) fill(img, x, 2, x, 3, VERDIGRIS);
        fill(img, 0, 5, 15, 5, BRASS);

        // Shaft: plain plating with a seam, so the middle reads as structure.
        fill(img, 7, 6, 8, 11, HULL_DARK);

        // Plinth: darker, with a brass foot.
        fill(img, 0, 12, 15, 15, HULL_DARK);
        fill(img, 0, 12, 15, 12, BRASS_DARK);
        fill(img, 0, 15, 15, 15, DEEP);
        return img;
    }

    /** A radar scope: rings, a sweep, and a contact. */
    static BufferedImage radarScope() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL_DARK);
        rect(img, 0, 0, 15, 15, BRASS_DARK);
        fill(img, 2, 2, 13, 13, SCOPE);

        // Two range rings, drawn by distance from the centre so they are
        // round rather than square.
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 13; x++) {
                double dx = x - 7.5;
                double dy = y - 7.5;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > 5.2 && d < 6.0) img.setRGB(x, y, SCOPE_RING);
                if (d > 2.6 && d < 3.2) img.setRGB(x, y, SCOPE_RING);
            }
        }
        // The sweep, out to the north-east, and one contact on it.
        for (int i = 0; i < 5; i++) img.setRGB(8 + i, 7 - i, SWEEP);
        img.setRGB(7, 7, SWEEP);
        img.setRGB(5, 10, SWEEP);
        return img;
    }

    /** A ship's wheel, read from above. */
    static BufferedImage helm() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL);
        weather(img, 0, 0, 15, 15, HULL_DARK);
        rect(img, 0, 0, 15, 15, BRASS_DARK);

        // Rim.
        for (int y = 1; y <= 14; y++) {
            for (int x = 1; x <= 14; x++) {
                double dx = x - 7.5;
                double dy = y - 7.5;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > 5.0 && d < 6.3) img.setRGB(x, y, BRASS);
                if (d < 1.6) img.setRGB(x, y, BRASS);
            }
        }
        // Four spokes, and four handles beyond the rim.
        fill(img, 7, 2, 8, 13, BRASS_DARK);
        fill(img, 2, 7, 13, 8, BRASS_DARK);
        img.setRGB(7, 1, FOAM);
        img.setRGB(8, 14, FOAM);
        img.setRGB(1, 8, FOAM);
        img.setRGB(14, 7, FOAM);
        return img;
    }

    /** A channel buoy: banded, with a lamp on top. */
    static BufferedImage beaconMast() {
        BufferedImage img = blank();
        fill(img, 0, 0, 15, 15, HULL_DARK);

        // The buoy body, banded red and white the way a channel marker is.
        fill(img, 5, 3, 10, 14, FOAM);
        fill(img, 5, 5, 10, 7, BUOY_RED);
        fill(img, 5, 10, 10, 12, BUOY_RED);
        rect(img, 5, 3, 10, 14, BRASS_DARK);

        // The lamp, and its glow.
        fill(img, 6, 1, 9, 2, LAMP);
        img.setRGB(7, 0, LAMP);
        img.setRGB(8, 0, LAMP);
        img.setRGB(4, 1, BRASS);
        img.setRGB(11, 1, BRASS);

        // Water at the foot, so it reads as floating rather than planted.
        fill(img, 0, 13, 4, 15, WATER);
        fill(img, 11, 13, 15, 15, WATER);
        fill(img, 0, 15, 15, 15, WATER_DEEP);
        return img;
    }
}
