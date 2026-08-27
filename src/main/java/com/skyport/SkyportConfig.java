package com.skyport;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings, split into the half the server decides and the half each
 * player decides.
 *
 * Almost everything here is SERVER config, which is not the same thing as
 * "settings for servers" - it is NeoForge's word for settings the server
 * owns and pushes to every client that connects. That is what makes a
 * server's rules actually binding: a player who edits their own copy of
 * powerRequirement or protectBlocks changes nothing, because the values in
 * play come down the wire on join. This used to be one COMMON spec, which
 * loads on both sides and syncs neither, so the two could disagree and
 * nothing said which one was true.
 *
 * The price is where the file lives. SERVER config is per-world, not
 * global: look in <world>/serverconfig/skyport-server.toml, which for a
 * dedicated server means world/serverconfig/ and for single-player means
 * saves/<name>/serverconfig/. Settings no longer follow you from world to
 * world, and that is the trade for having them authoritative.
 *
 * Only terrainMemoryLimit is CLIENT, and only because it is a budget for
 * the player's own memory that no server has any business dictating.
 *
 * The flight model's own constants stay in code - they are tuning, not
 * policy, and exposing them would invite configurations where planes
 * simply do not work.
 */
public final class SkyportConfig {

    private static final ModConfigSpec.Builder SERVER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CHAT_MESSAGES = SERVER
            .comment("Send autopilot messages to chat (arrivals, refusals, schedule completion).",
                     "Turn off if you run several aircraft and would rather watch the ATC screen.",
                     "Default: true")
            .define("messages.chat", true);

    public static final ModConfigSpec.BooleanValue ACTION_BAR_MESSAGES = SERVER
            .comment("Show routine progress on the action bar (pushback, cleared to line up, etc.).",
                     "Default: true")
            .define("messages.actionBar", true);

    public static final ModConfigSpec.BooleanValue TELEMETRY = SERVER
            .comment("Show the once-a-second position/state readout on the action bar while flying.",
                     "Useful when debugging a route, noisy the rest of the time.",
                     "Default: false")
            .define("messages.telemetry", false);

    public static final ModConfigSpec.IntValue MESSAGE_RADIUS = SERVER
            .comment("How close you have to be to an aircraft to hear it, in blocks.",
                     "",
                     "An aircraft narrating its whole schedule to whoever last touched",
                     "it is fine with one aeroplane and unbearable with six, especially",
                     "when they are all somewhere else. The ATC screen is where you check",
                     "on an aircraft you are not standing next to.",
                     "",
                     "Refusals still always reach you - if Engage is rejected, you are",
                     "standing at the block anyway. 0 means no limit, as it was before.")
            .defineInRange("messages.radius", 5, 0, 256);

    public static final ModConfigSpec.BooleanValue CHUNK_LOADING = SERVER
            .comment("Let aircraft keep the world loaded around themselves while flying.",
                     "Turn this off only if something else on the server handles chunk",
                     "loading, or you accept aircraft freezing the moment they fly out of",
                     "a player's view - they resume where they stopped, but a schedule",
                     "will not run unattended.",
                     "Default: true")
            .define("performance.chunkLoading", true);

    public static final ModConfigSpec.IntValue CHUNK_RADIUS = SERVER
            .comment("Chunks kept loaded either side of a flying aircraft.",
                     "This is the single biggest cost the mod imposes on a server: each",
                     "aircraft pins (2r+1)^2 chunks open while airborne. 1 is cheapest and",
                     "risks a fast aircraft outrunning its own loaded area; 2 is a safe",
                     "default; higher only helps at very high cruise speeds.")
            .defineInRange("performance.chunkRadius", 2, 0, 4);

    public static final ModConfigSpec.BooleanValue KEEP_PARKED_LOADED = SERVER
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

    public static final ModConfigSpec.IntValue PARKED_CHUNK_RADIUS = SERVER
            .comment("Chunks either side of a parked or newly woken aircraft.",
                     "0 loads only the chunk it stands in. That is enough to tick down a",
                     "gate wait, but an aircraft is bigger than a block and can straddle a",
                     "chunk boundary, so 1 is the safer default and costs eight more chunks",
                     "for a few minutes.")
            .defineInRange("performance.parkedChunkRadius", 1, 0, 3);

    public static final ModConfigSpec.BooleanValue WAKE_ON_ATC_OPEN = SERVER
            .comment("Opening the ATC block wakes EVERY sleeping aircraft on the server.",
                     "",
                     "Off by default, and rarely what you want. The tower lists sleeping",
                     "aircraft and waking one is a click on its name, which costs a",
                     "fraction as much world and is almost always the aircraft you meant.",
                     "Blanket waking was how this worked before that list existed: every",
                     "visit to the tower held a patch of world open around every aircraft",
                     "on the server, whether or not you cared about any of them.")
            .define("performance.wakeOnAtcOpen", false);

    public static final ModConfigSpec.IntValue FLEET_WAKE_MINUTES = SERVER
            .comment("How long opening the ATC block keeps parked aircraft loaded, in minutes.",
                     "Parked aircraft release their chunks and stop ticking, so their gate",
                     "waits stall until something wakes them. This is that something: long",
                     "enough for a schedule to get moving, and it lapses on its own once",
                     "airborne aircraft are holding their own chunks. 0 disables waking.")
            .defineInRange("performance.fleetWakeMinutes", 10, 0, 30);

    /**
     * What an aircraft has to have aboard before the autopilot will fly it.
     *
     * The autopilot steers by applying velocity to the craft's rigid body,
     * which means it makes thrust out of nothing - an autopilot on a solid
     * cube of iron flies exactly as well as a real aeroplane. Fine while
     * building the thing; indefensible in survival, where every other way of
     * moving costs something.
     */
    public enum PowerRequirement {
        /** No requirement. What the mod did before this existed. */
        NONE,
        /** Create rotational force reaching the Autopilot block. */
        ROTATION,
        /** Furnace fuel, burned from a container on the aircraft. */
        FUEL
    }

    public static final ModConfigSpec.EnumValue<PowerRequirement> POWER_REQUIREMENT = SERVER
            .comment("What an aircraft needs aboard before the autopilot will fly it.",
                     "",
                     "NONE: the autopilot flies anything it is placed on, free. Right for",
                     "creative building and for testing a layout; a cheat in survival.",
                     "",
                     "ROTATION: Create rotational force must reach the Autopilot block -",
                     "put a shaft or cogwheel against it, driven by whatever powertrain the",
                     "aircraft already carries. Costs whatever that powertrain costs to run.",
                     "",
                     "FUEL: the autopilot burns furnace fuel from a container on the",
                     "aircraft, the way a furnace would. Self-contained, and readable at a",
                     "glance from the chest it is drawing on.",
                     "",
                     "Either way, losing power in flight is an engine failure, not a pause:",
                     "the autopilot keeps the wings level but stops driving the craft",
                     "forward, and it comes down.")
            .defineEnum("survival.powerRequirement", PowerRequirement.NONE);

    public static final ModConfigSpec.IntValue ROTATION_MINIMUM_RPM = SERVER
            .comment("ROTATION mode: rotation speed needed at the Autopilot block, in RPM.",
                     "Sign is ignored - either direction will do. 16 is one water wheel's",
                     "worth; raise it to demand a real powertrain rather than a hand crank.")
            .defineInRange("survival.rotationMinimumRpm", 16, 1, 256);

    public static final ModConfigSpec.DoubleValue FUEL_EFFICIENCY = SERVER
            .comment("FUEL mode: how far one item's burn time goes, as a multiplier.",
                     "1.0 means an item burns for exactly as long as it would in a furnace",
                     "- coal for 80 seconds of flight. Raise it for longer range on less",
                     "fuel; lower it to make range a real constraint on route planning.")
            .defineInRange("survival.fuelEfficiency", 1.0, 0.05, 20.0);

    public static final ModConfigSpec.DoubleValue FUEL_SPEED_EXPONENT = SERVER
            .comment("FUEL mode: how sharply fuel burn rises with cruise speed.",
                     "",
                     "Burn rate is (cruise speed / reference speed) raised to this power.",
                     "The exponent is what creates the trade-off, and it has to be above 1",
                     "for one to exist at all: at 1.0 the rate rises exactly in step with",
                     "speed, so a journey costs the same fuel however fast it is flown and",
                     "there is never a reason to fly slowly.",
                     "",
                     "0.0: flat rate. Speed is free; fuel is purely a function of time.",
                     "1.0: fuel per block is constant. Speed is still effectively free.",
                     "2.0 (default): fuel per block scales with speed - double the cruise",
                     "speed, double the fuel for the trip. Drag really does rise with the",
                     "square of speed, so this is also roughly the honest answer.",
                     "3.0+: punishing. Fast aircraft become a deliberate luxury.")
            .defineInRange("survival.fuelSpeedExponent", 2.0, 0.0, 4.0);

    public static final ModConfigSpec.IntValue FUEL_REFERENCE_SPEED = SERVER
            .comment("FUEL mode: the cruise speed that burns fuel at exactly the rate",
                     "fuelEfficiency describes - one coal for 80 seconds at 1.0.",
                     "Below this an aircraft is cheaper than that, above it dearer.",
                     "Defaults to 24, the default cruise speed, so an aircraft nobody has",
                     "retuned burns exactly what it always did.")
            .defineInRange("survival.fuelReferenceSpeed", 24, 4, 80);

    public static final ModConfigSpec.BooleanValue PROTECT_BLOCKS = SERVER
            .comment("Lock the Airport Station and Autopilot to the player who placed them.",
                     "",
                     "On (default): only the owner, a server operator, or someone who",
                     "knows the block's passcode can open it, change it, or break it.",
                     "Blocks placed before this setting existed have no owner recorded",
                     "and stay open to everyone - break and replace one to claim it.",
                     "",
                     "Off: anyone can use anything, as the mod behaved before. Reasonable",
                     "on a single-player world or a server where everyone is trusted.",
                     "",
                     "This is enforced on the server for every action, not just in the",
                     "screen, so it holds against a modified client. Turning it off also",
                     "turns off that enforcement.",
                     "Default: true")
            .define("protection.protectBlocks", true);

    public static final ModConfigSpec SERVER_SPEC = SERVER.build();

    // --- client ----------------------------------------------------------

    private static final ModConfigSpec.Builder CLIENT = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue TERRAIN_MEMORY_LIMIT = CLIENT
            .comment("How many terrain samples the ATC map remembers.",
                     "Each is a few bytes; this caps how far the remembered map can grow",
                     "over a long session. 0 disables remembering entirely.",
                     "",
                     "The one setting a server does not decide for you. It is a budget for",
                     "your own memory and it only ever affects your own map.")
            .defineInRange("client.terrainMemoryLimit", 200_000, 0, 2_000_000);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT.build();

    private SkyportConfig() { }

    /** Config is read on nearly every message; these avoid a map lookup per
     *  call on the hot path. Refreshed whenever the config (re)loads. */
    public static boolean chatMessages = true;
    public static boolean actionBarMessages = true;
    public static boolean telemetry = false;
    public static int messageRadius = 5;
    public static boolean chunkLoading = true;
    public static int chunkRadius = 2;
    public static boolean keepParkedLoaded = false;
    public static int parkedChunkRadius = 1;
    public static boolean wakeOnAtcOpen = false;
    public static int terrainMemoryLimit = 200_000;
    public static int fleetWakeMinutes = 10;
    public static PowerRequirement powerRequirement = PowerRequirement.NONE;
    public static int rotationMinimumRpm = 16;
    public static double fuelEfficiency = 1.0;
    public static double fuelSpeedExponent = 2.0;
    public static int fuelReferenceSpeed = 24;
    public static boolean protectBlocks = true;

    /**
     * Split from the client half deliberately. A dedicated server never
     * loads CLIENT_SPEC, and reading a value out of an unloaded spec throws
     * - so the two must not be refreshed together.
     */
    public static void refreshServer() {
        chatMessages = CHAT_MESSAGES.get();
        actionBarMessages = ACTION_BAR_MESSAGES.get();
        telemetry = TELEMETRY.get();
        messageRadius = MESSAGE_RADIUS.get();
        chunkLoading = CHUNK_LOADING.get();
        chunkRadius = CHUNK_RADIUS.get();
        keepParkedLoaded = KEEP_PARKED_LOADED.get();
        parkedChunkRadius = PARKED_CHUNK_RADIUS.get();
        wakeOnAtcOpen = WAKE_ON_ATC_OPEN.get();
        fleetWakeMinutes = FLEET_WAKE_MINUTES.get();
        powerRequirement = POWER_REQUIREMENT.get();
        rotationMinimumRpm = ROTATION_MINIMUM_RPM.get();
        fuelEfficiency = FUEL_EFFICIENCY.get();
        fuelSpeedExponent = FUEL_SPEED_EXPONENT.get();
        fuelReferenceSpeed = FUEL_REFERENCE_SPEED.get();
        protectBlocks = PROTECT_BLOCKS.get();
    }

    public static void refreshClient() {
        terrainMemoryLimit = TERRAIN_MEMORY_LIMIT.get();
    }
}
