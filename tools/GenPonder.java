import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the structure NBT files Ponder scenes are built on.
 *
 * Ponder plays a scene inside a saved structure, and those are normally
 * produced by building something in game and saving it with a structure
 * block. Generating them instead keeps the scenes in version control as
 * code rather than as binaries nobody can review or regenerate.
 *
 * Only the subset of NBT that StructureTemplate actually reads is
 * implemented: compounds, lists, strings and ints.
 */
public class GenPonder {

    // 1.21.1
    static final int DATA_VERSION = 3953;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();

        // Each scene is a small apron of andesite with the block being
        // explained sitting on it, plus a strip of concrete standing in for
        // a runway so the scenes read as an airfield.
        // The station sits beside its airfield, overlooking the apron.
        Structure station = airfield();
        station.set(2, 1, 6, "skyport:airport_station");
        write(new File(dir, "airport_station.nbt"), station);

        // The autopilot sits on a stub of aircraft parked at the gate.
        Structure autopilot = airfield();
        autopilot.set(5, 1, 6, "minecraft:iron_block");
        autopilot.set(6, 1, 6, "minecraft:iron_block");
        autopilot.set(5, 2, 6, "skyport:autopilot");
        write(new File(dir, "autopilot.nbt"), autopilot);

        // The tower stands clear of the movement areas, raised to look over.
        Structure atc = airfield();
        atc.set(1, 1, 7, "minecraft:stone_bricks");
        atc.set(1, 2, 7, "skyport:atc");
        write(new File(dir, "atc.nbt"), atc);
        System.out.println("wrote 3 ponder structures to " + dir);
    }

    /**
     * A 9x9 patch of airfield: a concrete runway with a dashed centreline, a
     * yellow taxiway leading off it, and an orange gate pad.
     *
     * The scenes previously showed an identical bare plate for all three
     * blocks, which taught nothing - the point of a Ponder scene is to look
     * like the thing being explained.
     */
    static Structure airfield() {
        Structure s = new Structure(9, 3, 9);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                s.set(x, 0, z, "minecraft:grass_block");
            }
        }
        // Runway along the far edge, centreline dashed down the middle.
        for (int x = 0; x < 9; x++) {
            s.set(x, 1, 1, "minecraft:light_gray_concrete");
            s.set(x, 1, 2, x % 2 == 0 ? "minecraft:white_concrete" : "minecraft:light_gray_concrete");
            s.set(x, 1, 3, "minecraft:light_gray_concrete");
        }
        // Taxiway down to a gate apron.
        for (int z = 4; z < 7; z++) s.set(6, 1, z, "minecraft:yellow_concrete");
        s.set(6, 1, 7, "minecraft:orange_concrete");
        s.set(5, 1, 7, "minecraft:orange_concrete");
        return s;
    }

    static void write(File file, Structure structure) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(file)))) {
            // Root: an unnamed compound, per NBT's file convention.
            out.writeByte(10);
            out.writeUTF("");
            structure.writeBody(out);
            out.writeByte(0);
        }
    }

    /** Blocks laid out in a box, ready to be written as a StructureTemplate. */
    static class Structure {
        final int sx, sy, sz;
        final List<String> palette = new ArrayList<>();
        final List<int[]> blocks = new ArrayList<>(); // x, y, z, paletteIndex

        Structure(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        void set(int x, int y, int z, String blockName) {
            int index = palette.indexOf(blockName);
            if (index < 0) {
                palette.add(blockName);
                index = palette.size() - 1;
            }
            blocks.add(new int[] { x, y, z, index });
        }

        void writeBody(DataOutputStream out) throws IOException {
            writeIntList(out, "size", new int[] { sx, sy, sz });

            // palette: list of compounds, each just a block name. No block
            // states are needed - every block here is in its default state.
            out.writeByte(9);
            out.writeUTF("palette");
            out.writeByte(10);
            out.writeInt(palette.size());
            for (String name : palette) {
                out.writeByte(8);
                out.writeUTF("Name");
                out.writeUTF(name);
                out.writeByte(0);
            }

            out.writeByte(9);
            out.writeUTF("blocks");
            out.writeByte(10);
            out.writeInt(blocks.size());
            for (int[] block : blocks) {
                writeIntList(out, "pos", new int[] { block[0], block[1], block[2] });
                out.writeByte(3);
                out.writeUTF("state");
                out.writeInt(block[3]);
                out.writeByte(0);
            }

            // entities: present but empty; StructureTemplate expects the key.
            out.writeByte(9);
            out.writeUTF("entities");
            out.writeByte(10);
            out.writeInt(0);

            out.writeByte(3);
            out.writeUTF("DataVersion");
            out.writeInt(DATA_VERSION);
        }

        private void writeIntList(DataOutputStream out, String name, int[] values) throws IOException {
            out.writeByte(9);
            out.writeUTF(name);
            out.writeByte(3);
            out.writeInt(values.length);
            for (int value : values) out.writeInt(value);
        }
    }
}
