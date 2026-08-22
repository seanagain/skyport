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
        // The station sits clear of the movement areas, beside its airfield.
        Structure station = airfield();
        station.set(1, 1, 9, "skyport:airport_station");
        write(new File(dir, "airport_station.nbt"), station);

        // An actual little aeroplane, parked at the threshold, with the
        // autopilot on its back. The scene flies it, so it has to read as an
        // aircraft rather than as a block on a slab.
        Structure autopilot = airfield();
        plane(autopilot, 2, 1, 5);
        write(new File(dir, "autopilot.nbt"), autopilot);

        // The tower, raised so it overlooks the field.
        Structure atc = airfield();
        atc.set(1, 1, 9, "minecraft:stone_bricks");
        atc.set(1, 2, 9, "minecraft:stone_bricks");
        atc.set(1, 3, 9, "skyport:atc");
        write(new File(dir, "atc.nbt"), atc);
        System.out.println("wrote 3 ponder structures to " + dir);
    }

    /**
     * An 11x11 airfield: runway with a dashed centreline, a taxiway to a
     * gate, a holding pattern marked out as a loop, and the final leg that
     * joins the two.
     *
     * The pattern and final leg are flown in the air, not driven on the
     * ground - painting them here is a cheat, but it is the only way to show
     * their shape in a scene that cannot depict altitude.
     */
    static Structure airfield() {
        Structure s = new Structure(11, 4, 11);
        for (int x = 0; x < 11; x++) {
            for (int z = 0; z < 11; z++) {
                s.set(x, 0, z, "minecraft:grass_block");
            }
        }
        // Runway across the middle, dashed centreline.
        for (int x = 1; x < 10; x++) {
            s.set(x, 1, 4, "minecraft:light_gray_concrete");
            s.set(x, 1, 5, x % 2 == 0 ? "minecraft:white_concrete" : "minecraft:light_gray_concrete");
            s.set(x, 1, 6, "minecraft:light_gray_concrete");
        }
        // Taxiway down from the runway to a gate apron.
        for (int z = 7; z < 10; z++) s.set(8, 1, z, "minecraft:yellow_concrete");
        s.set(7, 1, 9, "minecraft:orange_concrete");
        s.set(8, 1, 10, "minecraft:red_concrete"); // hold line
        // Holding pattern: a racetrack off the approach end.
        for (int x = 1; x < 5; x++) {
            s.set(x, 1, 0, "minecraft:blue_concrete");
            s.set(x, 1, 2, "minecraft:blue_concrete");
        }
        s.set(0, 1, 1, "minecraft:blue_concrete");
        s.set(4, 1, 1, "minecraft:blue_concrete");
        // Final leg: pattern down onto the runway threshold.
        s.set(3, 1, 3, "minecraft:light_blue_concrete");
        s.set(2, 1, 2, "minecraft:light_blue_concrete");
        return s;
    }

    /**
     * A small aeroplane: fuselage, swept wings, a tail fin, and the
     * Autopilot sitting on top pointing along it.
     *
     * Built from iron so it reads as a machine rather than scenery, and
     * kept to five blocks long so the whole thing fits in a scene and can be
     * flown across it without leaving the plate.
     */
    static void plane(Structure s, int x, int y, int z) {
        for (int i = 0; i < 4; i++) s.set(x + i, y + 1, z, "minecraft:iron_block");
        // Wings, one block back from the nose.
        s.set(x + 1, y + 1, z - 1, "minecraft:iron_block");
        s.set(x + 1, y + 1, z + 1, "minecraft:iron_block");
        // Tailplane and fin at the back.
        s.set(x, y + 1, z - 1, "minecraft:light_gray_concrete");
        s.set(x, y + 1, z + 1, "minecraft:light_gray_concrete");
        s.set(x, y + 2, z, "minecraft:light_gray_concrete");
        // The block being explained, facing along the fuselage - the scene
        // flies the plane east, so the nose arrow has to point east too.
        s.set(x + 2, y + 2, z, "skyport:autopilot", "facing=east");
        // A lever on the nose, so the scene can show redstone cutting the
        // autopilot out rather than only asserting that it does.
        s.set(x + 3, y + 2, z, "minecraft:lever", "face=floor", "facing=east", "powered=false");
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
            set(x, y, z, blockName, new String[0]);
        }

        /**
         * Place a block in a non-default state, e.g.
         * { set(4, 3, 5, "skyport:autopilot", "facing=east")}.
         *
         * Needed because the Autopilot has to point along the fuselage of
         * the aeroplane it sits on - left in its default state it faces
         * north while the plane flies east, which is exactly the mistake the
         * scene is meant to warn against.
         */
        void set(int x, int y, int z, String blockName, String... properties) {
            // Two states of the same block are two palette entries, so the
            // key has to carry the properties as well as the name.
            String key = properties.length == 0
                    ? blockName
                    : blockName + "|" + String.join(",", properties);
            int index = palette.indexOf(key);
            if (index < 0) {
                palette.add(key);
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
            for (String key : palette) {
                int bar = key.indexOf('|');
                out.writeByte(8);
                out.writeUTF("Name");
                out.writeUTF(bar < 0 ? key : key.substring(0, bar));
                if (bar >= 0) {
                    out.writeByte(10);
                    out.writeUTF("Properties");
                    for (String property : key.substring(bar + 1).split(",")) {
                        int equals = property.indexOf('=');
                        out.writeByte(8);
                        out.writeUTF(property.substring(0, equals));
                        out.writeUTF(property.substring(equals + 1));
                    }
                    out.writeByte(0);
                }
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
