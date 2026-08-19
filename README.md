# Skyport (working title)

A Create Aeronautics addon: draw an airport's runway, taxiways, gates and
holding pattern on a map at an **Airport Station** block, then set a
destination on an **Autopilot** block and it flies there - gate to gate,
no pilot required.

## What's real right now (the prototype)

This is now an actual, testable loop - not just a scaffold:

- Place an **Airport Station**, right-click it, draw a runway/taxiway/
  holding pattern/gate by clicking a plain map screen, hit Save. It's
  registered world-wide (see `AirportRegistry`), not just in loaded chunks.
- Place an **Autopilot** block, right-click it, pick that airport and gate
  from a simple cycle-button picker, hit Engage.
- The autopilot's flight state machine (`TAXI_OUT -> TAKEOFF_ROLL ->
  CRUISE -> HOLDING -> APPROACH -> TAXI_IN -> IDLE`) actually runs,
  advancing a **simulated position** waypoint to waypoint at a fixed
  speed, and narrates itself to you in chat/action bar as it goes.

That last part is the one deliberate shortcut: **nothing visibly moves in
the world yet.** `AutopilotBlockEntity` tracks a phantom `Vec3` position
and reports it, rather than actually moving a Create Aeronautics
contraption - see the big comment on `applyMotionTowards` for exactly
why (this scaffold was written without access to Create Aeronautics'
contraption-movement API) and what to change once you're ready to move a
real plane. Every other line around it - state transitions, waypoint
sequencing, arrival detection - is the real logic that would drive real
movement, not a stub.

## How to test it

1. Build and run (`runClient`) with Create + Create Aeronautics + Sable
   also on the mod list (see "Before your first real build" below for
   the dependency versions to double check first).
2. Creative inventory -> Skyport tab -> grab an Airport Station and an
   Autopilot block.
3. Place the Airport Station, right-click it. Click "Gate" mode, click
   once on the map to drop a gate. Click "Runway" mode, click twice for
   a short runway. Click "Taxiway", click twice to connect gate to
   runway. Optionally "Holding Pattern", click 3-4 times for a loop.
   Hit Save Layout.
4. Place an Autopilot block anywhere nearby, right-click it. It should
   show your airport's name; click the gate button until it shows your
   gate; hit Engage Autopilot.
5. Watch chat - you'll get a message on every state change, and an
   action-bar position/state update once a second, cycling through the
   whole state machine down to "Arrived at gate".

## What's here

- `AirportStationBlock` / `AirportStationBlockEntity` - the station
  block. Owns one `AirportLayout` and keeps it registered in the world's
  `AirportRegistry` so any Autopilot block can look it up from anywhere.
- `AutopilotBlock` / `AutopilotBlockEntity` - the flight state machine
  and the simulated-movement prototype described above.
- `data/` - `Waypoint`, `AirportLayout` (runway/taxiway/holding-pattern
  waypoint lists + a named `gates` map), `AirportSummary` (the lightweight
  version sent to the autopilot picker), `AirportRegistry` (world-level
  `SavedData`).
- `network/` - all five payloads: `EngageAutopilotPayload` /
  `DisengageAutopilotPayload` / `SaveAirportLayoutPayload` (C2S) and
  `OpenAirportMapPayload` / `OpenAutopilotPayload` (S2C), registered in
  `ModNetworking`.
- `client/gui/` - `AirportMapScreen` (click-to-place waypoints/gates over
  a flat map-colored background, deliberately no fancy rendering - see
  its class doc) and `AutopilotScreen` (cycle-button destination picker).
- `registry/ModCreativeTabs` - a Skyport creative tab so both blocks are
  reachable in your inventory.

## Simplifications worth knowing about

Kept deliberately simple so the prototype was buildable in one pass -
each of these is a reasonable next step, not an oversight:

- **No real movement yet** - see above. This is the big one.
- **Map editor has no real terrain** - flat green background, not
  sampled from `MapItemSavedData`. Waypoints are placed at a fixed
  scale (4 blocks/pixel) and the station's own Y level; no altitude.
- **Directionality is ignored** - taxi-out and taxi-in reuse the same
  taxiway point order, takeoff and approach reuse the same runway point
  order. A real airport would traverse some of these in reverse.
- **No ATC/queueing** - only one plane's state is tracked per Autopilot
  block; nothing stops two planes converging on the same runway. Holding
  is a fixed one-lap wait, not a real clearance system.
- **No placement restriction on the Autopilot block** - it doesn't yet
  require being placed on an assembled contraption (see the TODO on
  `AutopilotBlock`), so you can test the whole loop without a real plane.

## Setting up the dev environment

1. **Install a JDK 21.** Minecraft 1.21.1 needs exactly Java 21 (not 17,
   not 24) - this matches the Java version your existing NeoForge server
   already runs, so if you're setting this up on the same machine you may
   already have it. [Microsoft's OpenJDK 21 builds](https://learn.microsoft.com/en-us/java/openjdk/download#openjdk-21)
   are what NeoForge's own docs recommend.
2. **Install IntelliJ IDEA Community Edition** (free). It's the de facto
   standard for Forge/NeoForge modding - most tutorials and troubleshooting
   assume it. VS Code with the Java extensions works too if you already
   know it well, but expect rougher edges with Gradle run configurations.
3. **Open this folder as a Gradle project** in IntelliJ (File -> Open,
   select this folder, let it detect `build.gradle`). First import will
   download Gradle itself, then NeoForge's toolchain, then decompile
   Minecraft - this can take 20-60 minutes and several GB of downloads the
   very first time. Subsequent imports are fast.
4. Once import finishes, IntelliJ should show Gradle-generated run
   configurations named `client` and `server` (or run `./gradlew runClient`
   from a terminal). `runClient` launches a real Minecraft client with
   this mod (and whatever's on the classpath) loaded - that's how you
   playtest as you go.

## Before your first real build

I don't have a way to run a full NeoForge/Create toolchain in the
environment this was written in (its network access doesn't reach
`maven.neoforged.net` or `maven.createmod.net`) - I did run every `.java`
file here through `javac` with no classpath to catch real syntax errors
(none found; all remaining errors were the expected "can't find
NeoForge/Minecraft classes"), but that's not the same as a real compile.
Treat these as "check this before you rely on it":

- **Mod version numbers** in `gradle.properties`
  (`create_version`, `create_aeronautics_version`, `sable_version`) are
  whatever was current on Modrinth in August 2026. Check
  [Create Aeronautics](https://modrinth.com/mod/create-aeronautics/versions),
  [Sable](https://modrinth.com/mod/sable/versions), and
  [Create](https://modrinth.com/mod/create/versions) for NeoForge
  1.21.1 builds and bump these if newer ones exist. `sable_version` is a
  placeholder you need to fill in.
- **The `modId` strings** for Create Aeronautics and Sable in
  `neoforge.mods.toml` (`create_aeronautics`, `sable`) are my best guess.
  Since your server already runs both, the fastest way to confirm is to
  unzip those jars from your server's `mods` folder and check their own
  `META-INF/neoforge.mods.toml`.
- **`AirportRegistry`'s `SavedData` save/load signature** - this API has
  changed across Minecraft versions; double-check against
  `net.minecraft.world.level.saveddata.SavedData` in your IDE (it'll show
  you the real method signatures once the project's indexed) before
  assuming this compiles as-is.
- **`PacketDistributor` / `StreamCodec.of` calls** in `network/` and the
  block entities - written to match current NeoForge 1.21.1 networking
  API as I understand it, but this is exactly the kind of API that's
  worth a quick diff against NeoForged's own docs/examples once your IDE
  is indexed and can tell you immediately if a method doesn't exist.
- **Gradle plugin/dependency versions** (`net.neoforged.moddev` version,
  Create/Ponder/Flywheel artifact versions) - correct as of when this was
  written, but these move; if the build fails resolving a dependency,
  that's the first place to look.

## Where to go from here

1. Get it building and confirm the loop in "How to test it" above
   actually works end to end - that validates everything except real
   plane movement.
2. Add the placement restriction on `AutopilotBlock` (must be on an
   assembled contraption) once you're looking at Create Aeronautics'
   actual plane/contraption classes.
3. Replace `applyMotionTowards` with real contraption movement for one
   state first (`TAXI_OUT` - short distance, no altitude change), the
   smallest possible case to learn that API against.
4. Fill in the rest of the states once one works end to end.
5. Real map terrain (`MapItemSavedData` sampling) and directionality
   (reversed taxi-in/approach paths) - cosmetic/correctness polish once
   the core loop is proven.
6. Only then, the unloaded-chunk simulation - see the big TODO comment
   in `AutopilotBlockEntity#serverTick`... actually check `DESIGN.md`
   and the original design discussion for that one, it's the hardest
   piece and deserves its own pass once everything else is solid.

## Reference material

- [NeoForged docs - Getting Started](https://docs.neoforged.net/docs/gettingstarted/)
- [NeoForged docs - Mod Files (1.21.1)](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/) -
  confirms the `@Mod` constructor-injection pattern used in `Skyport.java`
- [Create Wiki - Depending on Create (NeoForge 1.21.1)](https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1) -
  source for the Create/Ponder/Flywheel dependency coordinates in
  `build.gradle`
- [NeoForge MDK template (1.21.1, ModDevGradle)](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle) -
  the official starting point this scaffold was adapted from; worth diffing
  against for anything that looks off
- Existing Create Aeronautics addons, useful as real-world examples of
  this exact kind of project:
  [Create: Lift n' Load](https://modrinth.com/mod/create-lift-n-load),
  [Create Aeronautics: Gadgets & Gizmos](https://modrinth.com/mod/create-aeronautics-gadgets-and-gizmos),
  [buoyancy-tweaks source](https://github.com/jreynolds72/create-aeronautics-buoyancy-tweaks)
