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

    public static final ModConfigSpec.BooleanValue CHUNK_LOADING = BUILDER
            .comment("Let aircraft keep the world loaded around themselves while flying.",
                     "Turn this off only if something else on the server handles chunk",
                     "loading, or you accept aircraft freezing the moment they fly out of",
                     "a player's view - they resume where they stopped, but a schedule",
                     "will not run unattended.")
            .define("performance.chunkLoading", true);

    public static final ModConfigSpec.IntValue CHUNK_RADIUS = BUILDER
            .comment("Chunks kept loaded either side of a flying aircraft.",
                     "This is the single biggest cost the mod imposes on a server: each",
                     "aircraft pins (2r+1)^2 chunks open while airborne. 1 is cheapest and",
                     "risks a fast aircraft outrunning its own loaded area; 2 is a safe",
                     "default; higher only helps at very high cruise speeds.")
            .defineInRange("performance.chunkRadius", 2, 0, 4);

    public static final ModConfigSpec.BooleanValue KEEP_PARKED_LOADED = BUILDER
            .comment("Keep aircraft parked at a gate loaded, instead of letting them sleep.",
                     "",
                     "Off (default): a parked aircraft releases its chunks and stops",
                     "ticking, so its gate wait pauses until a player is nearby or the ATC",
                     "block wakes it. Cheap, and matches how the rest of Minecraft treats",
                     "unattended corners of the world.",
                     "",
                     "On: schedules keep running with nobody watching, at the cost of every",
                     "parked aircraft permanently holding chunks open. Reasonable for a",
                     "small fleet on a server with headroom; expensive for a large one.")
            .define("performance.keepParkedLoaded", false);

    public static final ModConfigSpec.IntValue PARKED_CHUNK_RADIUS = BUILDER
            .comment("Chunks either side of a parked or newly woken aircraft.",
                     "0 loads only the chunk it stands in, which is all it needs to tick",
                     "down a gate wait. Raise it if aircraft park across a chunk boundary",
                     "from something they need.")
            .defineInRange("performance.parkedChunkRadius", 0, 0, 2);

    public static final ModConfigSpec.BooleanValue WAKE_ON_ATC_OPEN = BUILDER
            .comment("Opening the ATC block wakes every parked aircraft on the server.",
                     "Turn off if you would rather aircraft only run while a player is",
                     "actually at their airport.")
            .define("performance.wakeOnAtcOpen", true);

    public static final ModConfigSpec.IntValue FLEET_WAKE_MINUTES = BUILDER
            .comment("How long opening the ATC block keeps parked aircraft loaded, in minutes.",
                     "Parked aircraft release their chunks and stop ticking, so their gate",
                     "waits stall until something wakes them. This is that something: long",
                     "enough for a schedule to get moving, and it lapses on its own once",
                     "airborne aircraft are holding their own chunks. 0 disables waking.")
            .defineInRange("performance.fleetWakeMinutes", 5, 0, 30);

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
    public static boolean chunkLoading = true;
    public static int chunkRadius = 2;
    public static boolean keepParkedLoaded = false;
    public static int parkedChunkRadius = 0;
    public static boolean wakeOnAtcOpen = true;
    public static int terrainMemoryLimit = 200_000;
    public static int fleetWakeMinutes = 5;

    public static void refresh() {
        chatMessages = CHAT_MESSAGES.get();
        actionBarMessages = ACTION_BAR_MESSAGES.get();
        telemetry = TELEMETRY.get();
        chunkLoading = CHUNK_LOADING.get();
        chunkRadius = CHUNK_RADIUS.get();
        keepParkedLoaded = KEEP_PARKED_LOADED.get();
        parkedChunkRadius = PARKED_CHUNK_RADIUS.get();
        wakeOnAtcOpen = WAKE_ON_ATC_OPEN.get();
        terrainMemoryLimit = TERRAIN_MEMORY_LIMIT.get();
        fleetWakeMinutes = FLEET_WAKE_MINUTES.get();
    }
}
