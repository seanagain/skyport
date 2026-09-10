package com.skyport.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block drops itself when broken.
 *
 * Nothing generates these files - there is no datagen in this project - so
 * nothing notices when one is missing either. All three blocks shipped with no
 * loot table at all, and on top of that asked for the correct tool without any
 * tag saying what the correct tool was, which meant no tool counted: broken
 * blocks simply vanished. Two separate omissions that each produce the same
 * silence in game, so both are pinned here.
 *
 * The paths are the 1.21 ones. 1.21 renamed loot_tables to loot_table and
 * tags/blocks to tags/block, and a file under the old name is ignored without
 * a word in the log.
 */
class BlockDropsTest {

    private static final String[] BLOCKS = { "airport_station", "autopilot", "atc" };

    private static JsonObject read(String path) {
        InputStream in = BlockDropsTest.class.getResourceAsStream(path);
        assertNotNull(in, path + " is missing");
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyBlockHasALootTableThatDropsItself() {
        for (String block : BLOCKS) {
            JsonObject table = read("/data/skyport/loot_table/blocks/" + block + ".json");
            String dropped = table.getAsJsonArray("pools").get(0).getAsJsonObject()
                    .getAsJsonArray("entries").get(0).getAsJsonObject()
                    .get("name").getAsString();
            assertEquals("skyport:" + block, dropped, block + " drops the wrong item");
        }
    }

    /** The blocks require the correct tool, so something has to say what that
     *  tool is - without this, a pickaxe does not count and nothing drops. */
    @Test
    void everyBlockIsMineableWithAPickaxe() {
        String values = read("/data/minecraft/tags/block/mineable/pickaxe.json")
                .getAsJsonArray("values").toString();
        for (String block : BLOCKS) {
            assertTrue(values.contains("\"skyport:" + block + "\""),
                    block + " is not in mineable/pickaxe, so no tool is correct for it");
        }
    }
}
