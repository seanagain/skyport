package com.seaport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every texture a model asks for has to exist.
 *
 * This is here because it already went wrong. The models were mirrored from
 * Skyport's with a rename, and the rename used a word boundary - which does
 * not fall between "atc" and "_side", because an underscore is a word
 * character. So two references kept their old names, pointed at textures that
 * were never generated under those names, and the Marine Radar shipped as a
 * magenta and black checkerboard.
 *
 * Nothing caught it. The code compiled, the jar contained every file it was
 * supposed to, the server booted without a word, and the tests passed: a
 * missing texture is a client-side nothing until somebody places the block and
 * looks at it. That is a long way to carry a one-character mistake.
 *
 * So the check is mechanical, and it runs both ways. A reference with no file
 * is a broken block. A file with no reference is either dead weight or - more
 * likely - the other half of the same renaming mistake, pointing at the thing
 * that should have been used.
 */
class ResourcesTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/seaport");
    private static final Pattern TEXTURE_REFERENCE =
            Pattern.compile("\"seaport:block/([a-z0-9_]+)\"");

    /** Model files whose "parent" points at another MODEL rather than a
     *  texture - an item model inheriting its block's shape. Those are
     *  resolved against models, not textures. */
    private static final Pattern PARENT_REFERENCE =
            Pattern.compile("\"parent\"\\s*:\\s*\"seaport:block/([a-z0-9_]+)\"");

    private static List<Path> jsonUnder(String directory) throws IOException {
        Path dir = ASSETS.resolve(directory);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Set<String> existingTextures() throws IOException {
        Set<String> names = new TreeSet<>();
        Path dir = ASSETS.resolve("textures/block");
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".png"))
                    .forEach(p -> names.add(p.getFileName().toString().replace(".png", "")));
        }
        return names;
    }

    private static Set<String> existingBlockModels() throws IOException {
        Set<String> names = new TreeSet<>();
        for (Path model : jsonUnder("models/block")) {
            names.add(model.getFileName().toString().replace(".json", ""));
        }
        return names;
    }

    /** Resources are read from the project directory, which Gradle sets as
     *  the working directory for tests. If that ever stops being true this
     *  says so rather than passing on an empty set and proving nothing. */
    @Test
    void theAssetsAreWhereThisTestExpectsThem() {
        assertTrue(Files.isDirectory(ASSETS),
                "expected assets at " + ASSETS.toAbsolutePath() + " - this test proves nothing without them");
    }

    @Test
    void everyTextureAModelAsksForExists() throws IOException {
        Set<String> textures = existingTextures();
        Set<String> models = existingBlockModels();
        List<String> broken = new ArrayList<>();

        for (Path model : jsonUnder("models/block")) {
            String body = Files.readString(model);
            Matcher matcher = TEXTURE_REFERENCE.matcher(body);
            while (matcher.find()) {
                String name = matcher.group(1);
                // A block model can only name textures, so anything here that
                // is not a texture is broken even if a model shares the name.
                if (!textures.contains(name)) {
                    broken.add(model.getFileName() + " wants texture " + name);
                }
            }
        }

        for (Path model : jsonUnder("models/item")) {
            String body = Files.readString(model);
            Matcher parent = PARENT_REFERENCE.matcher(body);
            while (parent.find()) {
                String name = parent.group(1);
                // An item model usually inherits its block's model; a flat
                // icon would name a texture instead. Either resolves.
                if (!models.contains(name) && !textures.contains(name)) {
                    broken.add(model.getFileName() + " wants model or texture " + name);
                }
            }
        }

        assertTrue(broken.isEmpty(), "these would render as missing textures in game:\n  "
                + String.join("\n  ", broken));
    }

    /**
     * The other direction. A texture nobody uses is usually not spare art -
     * it is the file a broken reference was supposed to be pointing at.
     */
    @Test
    void everyTextureIsUsedBySomething() throws IOException {
        Set<String> referenced = new TreeSet<>();
        for (String directory : new String[] { "models/block", "models/item" }) {
            for (Path model : jsonUnder(directory)) {
                Matcher matcher = TEXTURE_REFERENCE.matcher(Files.readString(model));
                while (matcher.find()) referenced.add(matcher.group(1));
            }
        }

        Set<String> unused = new TreeSet<>(existingTextures());
        unused.removeAll(referenced);
        assertTrue(unused.isEmpty(), "generated but never referenced: " + unused);
    }

    /** Each block needs a blockstate, or it renders as nothing at all. */
    @Test
    void everyBlockModelHasABlockstate() throws IOException {
        Set<String> blockstates = new TreeSet<>();
        for (Path state : jsonUnder("blockstates")) {
            blockstates.add(state.getFileName().toString().replace(".json", ""));
        }
        assertEquals(existingBlockModels(), blockstates,
                "every block model needs a blockstate of the same name, and vice versa");
    }
}
