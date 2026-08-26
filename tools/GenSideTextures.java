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
        for (int x = 3; x <= 12; x++) {
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
        for (int x = 2; x <= 13; x++) {
            set(t, x, 7, ANDESITE_DARK);
            set(t, x, 8, ANDESITE_LIGHT);
        }

        // Bolts at each corner of the panel.
        int[][] bolts = { { 3, 3 }, { 12, 3 }, { 3, 12 }, { 12, 12 } };
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

    /** The tower shaft: vertical ribs, and a band of windows near the top
     *  where the cabin sits. */
    static BufferedImage atcSide() {
        BufferedImage t = base();
        brassFrame(t);

        // Structural ribs up the shaft.
        for (int x : new int[] { 4, 7, 10 }) {
            for (int y = 6; y <= 13; y++) {
                set(t, x, y, ANDESITE_DARK);
                set(t, x + 1, y, ANDESITE_LIGHT);
            }
        }

        // Window band - dark glass between brass sills.
        for (int x = 2; x <= 13; x++) {
            set(t, x, 2, BRASS_DARK);
            set(t, x, 5, BRASS_DARK);
            for (int y = 3; y <= 4; y++) set(t, x, y, 0xFF1D2A33);
        }
        // Two lit panes, so the tower looks occupied.
        set(t, 4, 3, 0xFF6FD3E0);
        set(t, 10, 4, 0xFF6FD3E0);
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
        int[][] speckle = { { 3, 6 }, { 11, 4 }, { 6, 13 }, { 13, 9 }, { 2, 10 }, { 9, 2 } };
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
