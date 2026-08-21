package com.skyport;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings, written to <code>config/skyport-common.toml</code>.
 *
 * Kept to things a server owner would actually want to change: how noisy the
 * mod is, and how much world it pins open. The flight model's own constants
 * stay in code - they're tuning, not policy, and exposing them would invite
 * configurations where planes simply don't work.
 */
public final class SkyportConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CHAT_MESSAGES = BUILDER
            .comment("Send autopilot messages to chat (arrivals, refusals, schedule completion).",
                     "Turn off if you run several aircraft and would rather watch the ATC screen.")
            .define("messages.chat", true);

    public static final ModConfigSpec.BooleanValue ACTION_BAR_MESSAGES = BUILDER
            .comment("Show routine progress on the action bar (pushback, cleared to line up, etc.).")
            .define("messages.actionBar", true);

    public static final ModConfigSpec.BooleanValue TELEMETRY = BUILDER
            .comment("Show the once-a-second position/state readout on the action bar while flying.",
                     "Useful when debugging a route, noisy the rest of the time.")
            .define("messages.telemetry", false);

    public static final ModConfigSpec.IntValue CHUNK_RADIUS = BUILDER
            .comment("Chunks kept loaded either side of a flying aircraft.",
                     "This is the single biggest cost the mod imposes on a server: each",
                     "aircraft pins (2r+1)^2 chunks open while airborne. 1 is cheapest and",
                     "risks a fast aircraft outrunning its own loaded area; 2 is a safe",
                     "default; higher only helps at very high cruise speeds.")
            .defineInRange("performance.chunkRadius", 2, 0, 4);

    public static final ModConfigSpec.IntValue TERRAIN_MEMORY_LIMIT = BUILDER
            .comment("How many terrain samples the ATC map remembers (client-side).",
                     "Each is a few bytes; this caps how far the remembered map can grow",
                     "over a long session. 0 disables remembering entirely.")
            .defineInRange("performance.terrainMemoryLimit", 200_000, 0, 2_000_000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SkyportConfig() { }

    /** Config is read on nearly every message; these avoid a map lookup per
     *  call on the hot path. Refreshed whenever the config (re)loads. */
    public static boolean chatMessages = true;
    public static boolean actionBarMessages = true;
    public static boolean telemetry = false;
    public static int chunkRadius = 2;
    public static int terrainMemoryLimit = 200_000;

    public static void refresh() {
        chatMessages = CHAT_MESSAGES.get();
        actionBarMessages = ACTION_BAR_MESSAGES.get();
        telemetry = TELEMETRY.get();
        chunkRadius = CHUNK_RADIUS.get();
        terrainMemoryLimit = TERRAIN_MEMORY_LIMIT.get();
    }
}
