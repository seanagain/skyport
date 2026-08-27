import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the casing-side textures for the three blocks.
 *
 * The blocks all wore a screen on every face, so a station, a tower and an
 * avionics box read as the same object three times. Tops stay as they are -
 * each already says something specific. These are the sides: machinery rather
 * than another display, which is what the side of a cabinet actually looks
 * like.
 *
 * The palette is sampled from airport_station.png rather than guessed, so
 * these sit beside the existing art instead of near it:
 *
 *   #8C8C89 andesite    #6C6C69 andesite shadow   #A2A29E andesite highlight
 *   #DEB060 brass       #A37A3A brass shadow
 *
 * Written as code because the shapes here are geometric - frames, bolts,
 * louvres, panel seams - which is the part of pixel art that survives being
 * generated. The screens on the top faces are hand-drawn and should stay that
 * way; nothing generated will match a person drawing a radar sweep.
 *
 *   java -Djava.awt.headless=true tools/GenSideTextures.java <textures-dir>
 */
public class GenSideTextures {

    static final int ANDESITE = 0xFF8C8C89;
    static final int ANDESITE_DARK = 0xFF6C6C69;
    static final int ANDESITE_LIGHT = 0xFFA2A29E;
    static final int BRASS = 0xFFDEB060;
    static final int BRASS_DARK = 0xFFA37A3A;

    /**
     * The outer three pixels of every face are reserved; detail belongs
     * inside FEATURE_MIN..FEATURE_MAX.
     *
     * Not an aesthetic margin. The station and the autopilot both have a
     * raised rim around a sunken screen, and that rim's TOP surface samples
     * this texture in plan - the north rim takes rows 0-3, the west rim takes
     * columns 0-3, and so on, so that the brass edging carries round the top
     * of the block and meets the brass line on the wall below it. Anything
     * drawn in that ring lands on the rim as well, which is how a nameplate
     * ends up smeared across the top of the console.
     */
    static final int FEATURE_MIN = 3;
    static final int FEATURE_MAX = 12;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();

        ImageIO.write(stationSide(), "png", new File(dir, "airport_station_side.png"));
        ImageIO.write(autopilotSide(), "png", new File(dir, "autopilot_side.png"));
        ImageIO.write(atcSide(), "png", new File(dir, "atc_side.png"));
        System.out.println("wrote 3 side textures to " + dir);
    }

    /** The station is a cabinet you stand at: braced corners and a vented
     *  lower half, like something with a machine inside it. */
    static BufferedImage stationSide() {
        BufferedImage t = base();
        brassFrame(t);

        // A brass nameplate across the upper third, and one band of vents
        // below it. Two features, not four: at sixteen pixels a face holds
        // very little before it turns to noise, and the first attempt read
        // as a radiator because the vents filled half of it.
        for (int x = FEATURE_MIN; x <= FEATURE_MAX; x++) {
            set(t, x, 4, BRASS);
            set(t, x, 5, BRASS_DARK);
        }

        for (int y = 9; y <= 11; y += 2) {
            for (int x = 4; x <= 11; x++) {
                set(t, x, y, ANDESITE_DARK);
                set(t, x, y + 1, ANDESITE_LIGHT);
            }
        }
        return t;
    }

    /** The autopilot is an instrument box: a seam down the middle and bolts
     *  at the corners, the way a bolted-together avionics case looks. */
    static BufferedImage autopilotSide() {
        BufferedImage t = base();
        brassFrame(t);

        // Horizontal seam where the two halves of the case meet.
        for (int x = FEATURE_MIN; x <= FEATURE_MAX; x++) {
            set(t, x, 7, ANDESITE_DARK);
            set(t, x, 8, ANDESITE_LIGHT);
        }

        // Bolts at each corner of the panel. The lower pair sit at y=11, not
        // y=12, because each bolt is two pixels tall and the shadow pixel
        // would otherwise fall in the reserved ring.
        int[][] bolts = { { 3, 3 }, { 12, 3 }, { 3, 11 }, { 12, 11 } };
        for (int[] b : bolts) {
            set(t, b[0], b[1], BRASS);
            set(t, b[0], b[1] + 1, BRASS_DARK);
        }

        // A shallow recess, so the flat middle is not just empty grey.
        for (int x = 6; x <= 9; x++) {
            for (int y = 10; y <= 12; y++) set(t, x, y, ANDESITE_DARK);
        }
        return t;
    }

    /**
     * The tower: fluted ribs running the full height of the face.
     *
     * The ribs run edge to edge and not part way. atc.json slices this one
     * texture across three stacked pieces - cabin, shaft, plinth - so
     * anything that stops short lands as a band on one piece and reads as a
     * seam between them rather than as one tower. A band of windows drawn
     * here did exactly that: it sat on the cabin alone and made it look like
     * a separate object balanced on top. Continuous ribs make the slicing
     * invisible, which is the whole point of slicing it.
     *
     * The reserved ring does not apply here - the tower has no rim, and its
     * top face is atc_top.
     */
    static BufferedImage atcSide() {
        BufferedImage t = base();
        brassFrame(t);

        for (int x : new int[] { 4, 7, 10 }) {
            for (int y = 1; y <= 14; y++) {
                set(t, x, y, ANDESITE_DARK);
                set(t, x + 1, y, ANDESITE_LIGHT);
            }
        }
        return t;
    }

    // --- helpers ---------------------------------------------------------

    static BufferedImage base() {
        BufferedImage t = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // Andesite with a scatter of darker and lighter blocks, matching how
        // the existing textures break up their flat areas.
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) t.setRGB(x, y, ANDESITE);
        }
        int[][] speckle = { { 3, 6 }, { 11, 4 }, { 6, 12 }, { 12, 9 }, { 4, 10 }, { 9, 3 } };
        for (int i = 0; i < speckle.length; i++) {
            set(t, speckle[i][0], speckle[i][1], i % 2 == 0 ? ANDESITE_DARK : ANDESITE_LIGHT);
        }
        return t;
    }

    /**
     * The brass edging every face in this mod shares - one pixel, lit from
     * the top left.
     *
     * One pixel and not two. A second ring in the shadow colour reads as a
     * thick brown band rather than as depth, which is what the first attempt
     * did: it swamped the face and made every block look like it was framed
     * in mud. The existing textures use a single edge for the same reason.
     */
    static void brassFrame(BufferedImage t) {
        for (int i = 0; i < 16; i++) {
            set(t, i, 0, BRASS);
            set(t, 0, i, BRASS);
            set(t, i, 15, BRASS_DARK);
            set(t, 15, i, BRASS_DARK);
        }
    }

    static void set(BufferedImage t, int x, int y, int argb) {
        if (x >= 0 && x < 16 && y >= 0 && y < 16) t.setRGB(x, y, argb);
    }
}
