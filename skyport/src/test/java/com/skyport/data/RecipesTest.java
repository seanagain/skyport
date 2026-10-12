package com.skyport.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block can be crafted.
 *
 * Like the loot tables, nothing generates these files, so nothing notices one
 * going missing - and a block with no recipe exists only in creative. What can
 * be checked without a running game is the shape: that the file sits where
 * 1.21 looks for it, that it makes the block it is named after, and that every
 * symbol in the pattern means something. Whether each ingredient actually
 * exists is the game's to say; a bad item id is logged when a server loads
 * recipes.
 */
class RecipesTest {

    private static final String[] BLOCKS = { "airport_station", "autopilot", "atc", "vor" };

    private static JsonObject recipe(String block) {
        String path = "/data/skyport/recipe/" + block + ".json";
        InputStream in = RecipesTest.class.getResourceAsStream(path);
        assertNotNull(in, path + " is missing, so " + block + " cannot be crafted");
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyBlockHasARecipeThatMakesIt() {
        for (String block : BLOCKS) {
            JsonObject result = recipe(block).getAsJsonObject("result");
            assertEquals("skyport:" + block, result.get("id").getAsString(), block + " crafts the wrong item");
            assertTrue(!result.has("count") || result.get("count").getAsInt() >= 1, block + " crafts nothing");
        }
    }

    /** A symbol in the pattern with no key is a recipe the game rejects; a
     *  key the pattern never uses is usually a typo in one or the other. */
    @Test
    void everyPatternSymbolIsDefinedAndUsed() {
        for (String block : BLOCKS) {
            JsonObject recipe = recipe(block);
            assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString(), block);

            JsonArray pattern = recipe.getAsJsonArray("pattern");
            JsonObject key = recipe.getAsJsonObject("key");
            Set<Character> used = new HashSet<>();
            int width = pattern.get(0).getAsString().length();
            for (JsonElement row : pattern) {
                String line = row.getAsString();
                assertEquals(width, line.length(), block + ": every pattern row must be the same width");
                for (char c : line.toCharArray()) {
                    if (c == ' ') continue;
                    used.add(c);
                    assertTrue(key.has(String.valueOf(c)), block + ": '" + c + "' is in the pattern but not the key");
                }
            }
            for (Map.Entry<String, JsonElement> entry : key.entrySet()) {
                assertTrue(used.contains(entry.getKey().charAt(0)), block + ": key '" + entry.getKey() + "' is never used");
            }
        }
    }

    /** Written out in full. A bare "brass_ingot" resolves to minecraft:, which
     *  has no such item. */
    @Test
    void everyIngredientNamesItsMod() {
        for (String block : BLOCKS) {
            for (Map.Entry<String, JsonElement> entry : recipe(block).getAsJsonObject("key").entrySet()) {
                String item = entry.getValue().getAsJsonObject().get("item").getAsString();
                assertTrue(item.contains(":"), block + ": ingredient '" + item + "' has no namespace");
            }
        }
    }
}
