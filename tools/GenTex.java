import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates Skyport's 16x16 block textures. Pixel-exact and no anti-aliasing,
 * the way vanilla and Create textures are authored - see DESIGN.md.
 */
public class GenTex {

    static final int ANDESITE      = 0xFF8D8D89;
    static final int ANDESITE_DARK = 0xFF6E6E6A;
    static final int ANDESITE_LITE = 0xFFA6A6A1;
    static final int BRASS         = 0xFFC6A664;
    static final int BRASS_DARK    = 0xFF8A7440;
    static final int NAVY          = 0xFF1E2A44;
    static final int CYAN          = 0xFF5AD7E0;
    static final int SCOPE         = 0xFF13301F;
    static final int SCOPE_RING    = 0xFF2E6B3A;
    static final int BLIP          = 0xFFB7E36A;
    static final int ARROW         = 0xFFF2EFE4;
    static final int ARROW_SHADOW  = 0xFF5A5A56;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        ImageIO.write(autopilotTop(), "png", new File(dir, "autopilot_top.png"));
        ImageIO.write(atc(), "png", new File(dir, "atc.png"));
        ImageIO.write(atcTop(), "png", new File(dir, "atc_top.png"));
        System.out.println("wrote 3 textures to " + dir);
    }

    /** Andesite panel with a brass frame - the shared Create-ish body. */
    static BufferedImage panel() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int c = ANDESITE;
                if (x == 0 || y == 0) c = ANDESITE_LITE;            // top-left highlight
                if (x == 15 || y == 15) c = ANDESITE_DARK;          // bottom-right shadow
                if (x == 1 || y == 1 || x == 14 || y == 14) c = BRASS;
                if ((x == 1 && y == 1)) c = BRASS;
                if ((x == 14 && y == 14) || (x == 14 && y == 1) || (x == 1 && y == 14)) c = BRASS_DARK;
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /**
     * The Autopilot's top face: the usual panel with a big arrow pointing
     * toward the top of the image, which is north at facing=north and rotates
     * with the blockstate - so it always shows which way the nose is.
     */
    static BufferedImage autopilotTop() {
        BufferedImage img = panel();
        // Inset a darker plate so the arrow reads against it.
        for (int x = 3; x <= 12; x++) {
            for (int y = 3; y <= 12; y++) img.setRGB(x, y, NAVY);
        }
        // Arrowhead: widening rows from the tip down.
        int[][] head = {
                {8, 8}, {7, 9}, {6, 10}, {5, 11}, {4, 12},
        };
        int y = 3;
        for (int[] span : head) {
            for (int x = span[0]; x <= span[1]; x++) img.setRGB(x, y, ARROW);
            y++;
        }
        // Shaft.
        for (int yy = 8; yy <= 12; yy++) {
            img.setRGB(7, yy, ARROW);
            img.setRGB(8, yy, ARROW);
        }
        // A one-pixel shadow on the trailing edge gives it some depth.
        for (int yy = 9; yy <= 12; yy++) img.setRGB(9, yy, ARROW_SHADOW);
        img.setRGB(9, 8, ARROW_SHADOW);
        return img;
    }

    /** ATC block sides: panel with a small radar scope. */
    static BufferedImage atc() {
        BufferedImage img = panel();
        for (int x = 4; x <= 11; x++) {
            for (int y = 4; y <= 11; y++) img.setRGB(x, y, SCOPE);
        }
        // Concentric range rings, drawn as a rough circle.
        int[][] ring = {
                {5, 6}, {5, 9}, {10, 6}, {10, 9},
                {6, 5}, {9, 5}, {6, 10}, {9, 10},
        };
        for (int[] p : ring) img.setRGB(p[0], p[1], SCOPE_RING);
        // Sweep line and a contact.
        img.setRGB(7, 7, SCOPE_RING);
        img.setRGB(8, 8, SCOPE_RING);
        img.setRGB(9, 9, SCOPE_RING);
        img.setRGB(9, 6, BLIP);
        return img;
    }

    /** ATC top: scope with a cyan cross, so it reads as a control desk. */
    static BufferedImage atcTop() {
        BufferedImage img = panel();
        for (int x = 3; x <= 12; x++) {
            for (int y = 3; y <= 12; y++) img.setRGB(x, y, SCOPE);
        }
        for (int i = 3; i <= 12; i++) {
            img.setRGB(i, 8, SCOPE_RING);
            img.setRGB(8, i, SCOPE_RING);
        }
        // Two contacts on the scope.
        img.setRGB(5, 6, CYAN);
        img.setRGB(10, 10, BLIP);
        return img;
    }
}
