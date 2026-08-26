import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the Skyport logo from the Airport Station block texture.
 *
 * The logo is the block itself rather than an illustration of what the mod
 * does. It is what a player sees in the creative tab and in their hand, so a
 * listing that shows the same thing is recognisable in a way a bespoke
 * graphic would not be - and it cannot drift out of date, because it is
 * generated from the texture the game actually uses.
 *
 * Scaled by whole-number nearest-neighbour on purpose. Any smoothing turns
 * 16x16 pixel art into mush; Minecraft textures want hard edges, and the
 * chunky result reads as deliberate rather than as a low-resolution image
 * somebody stretched.
 *
 *   java -Djava.awt.headless=true tools/GenLogo.java &lt;output-dir&gt;
 */
public class GenLogo {

    private static final String SOURCE =
            "src/main/resources/assets/skyport/textures/block/airport_station.png";

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();

        BufferedImage texture = ImageIO.read(new File(SOURCE));
        if (texture == null) throw new IllegalStateException("could not read " + SOURCE);

        // 512 for CurseForge, which wants at least 400. 128 is a sensible
        // in-game mod-list size. Both are exact multiples of 16, so every
        // source pixel stays a perfect square.
        ImageIO.write(scale(texture, 512 / texture.getWidth()), "png", new File(dir, "logo.png"));
        ImageIO.write(scale(texture, 128 / texture.getWidth()), "png", new File(dir, "logo_128.png"));

        System.out.println("wrote logo.png (512) and logo_128.png to " + dir
                + ", from a " + texture.getWidth() + "x" + texture.getHeight() + " source");
    }

    private static BufferedImage scale(BufferedImage source, int factor) {
        if (factor < 1) throw new IllegalArgumentException("source is larger than the target size");
        BufferedImage out = new BufferedImage(
                source.getWidth() * factor, source.getHeight() * factor, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < out.getWidth(); x++) {
            for (int y = 0; y < out.getHeight(); y++) {
                out.setRGB(x, y, source.getRGB(x / factor, y / factor));
            }
        }
        return out;
    }
}
