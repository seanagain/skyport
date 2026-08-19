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
3. Place the Airport Station and right-click it. Name the airport, then
   hit **Edit Map**.
4. Draw the layout. Each mode's click rule is spelled out in the hint line
   above the map, because they differ:
   - **Runway** - 2 clicks: the gate end first, then the far end.
   - **Taxiway** - clicks are read in *pairs*, each pair its own segment.
     First pair is the backbone (runway gate end <-> holding pattern);
     each later pair is one gate's spur.
   - **Holding** - 3+ clicks forming a loop. Set its altitude with `-`/`+`
     and its turn direction with the CW/CCW button.
   - **Final** - 2 clicks: holding-pattern side first, then the runway's
     far end. This is the descent path.
   - **Gate** - one click per gate, auto-named Gate A, Gate B, ...

   Points snap to an 8-block grid; the hovered world X/Z shows under the
   map. Closing the screen saves - there's no way to lose a drawing by
   pressing Escape.
5. Back on the station screen, the summary line shows what's actually
   registered (`Runway ok   Taxiway ok   Holding 0/3 ...`). Anything not
   "ok" doesn't have enough points to fly yet.
6. Place an Autopilot block, right-click it, pick the airport and gate,
   hit Engage Autopilot.
7. Watch chat - a message on every state change, plus an action-bar
   position/state update once a second, through the whole state machine
   down to "Arrived at gate".

Engaging is refused if the plane is sitting on the ground away from any
marked taxiway/runway/gate - it'll tell you to tow it onto one first.
Engaging while airborne skips the ground states and climbs to cruise
altitude instead.

## What's here

- `AirportStationBlock` / `AirportStationBlockEntity` - the station
  block. Owns one `AirportLayout` and keeps it registered in the world's
  `AirportRegistry` so any Autopilot block can look it up from anywhere.
- `AutopilotBlock` / `AutopilotBlockEntity` - the flight state machine
  and the simulated-movement prototype described above.
- `data/` - `Waypoint` (four types: runway/taxiway/holding-pattern/final
  leg - the enum's javadoc is where each one's point-count and ordering
  rules are written down), `AirportLayout` (those waypoint lists, a named
  `gates` map, plus holding-pattern altitude and turn direction),
  `AirportSummary` (the lightweight version sent to the autopilot picker),
  `AirportRegistry` (world-level `SavedData`).
- `network/` - all five payloads: `EngageAutopilotPayload` /
  `DisengageAutopilotPayload` / `SaveAirportLayoutPayload` (C2S) and
  `OpenAirportMapPayload` / `OpenAutopilotPayload` (S2C), registered in
  `ModNetworking`.
- `client/gui/` - `AirportStationScreen` (name the airport, see what's
  registered, open the editor), `AirportMapScreen` (the editor: terrain-
  sampled background, snap-to-grid click-to-place, per-mode drawing rules)
  and `AutopilotScreen` (cycle-button destination picker).
- `registry/ModCreativeTabs` - a Skyport creative tab so both blocks are
  reachable in your inventory.

## Simplifications worth knowing about

Kept deliberately simple so the prototype was buildable in one pass -
each of these is a reasonable next step, not an oversight:

- **No real movement yet** - see above. This is the big one.
- **Ground routing doesn't pathfind** - `TAXI_OUT`/`TAXI_IN` walk *every*
  taxiway point in the order they were drawn (reversed when arriving),
  rather than following the specific spur belonging to the target gate.
  Fine with one or two gates; with more, planes will visit spurs that
  aren't theirs. Wants a real graph search - see
  `AutopilotBlockEntity#groundTaxiPath`.
- **Terrain sampling is coarse and client-side** - real `MapColor` values
  now, but sampled per 4px cell from the *client's* loaded chunks via the
  heightmap, not from `MapItemSavedData`. Unloaded chunks stay blue-gray.
  Waypoints are still placed at a fixed 4 blocks/pixel and the station's
  own Y; only the holding pattern has a real altitude.
- **"On the ground" is a heightmap guess** - `isOnGround` compares the
  block's Y against the world surface, because there's no real contraption
  flight state to read yet. Replace it when the movement API is wired up.
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

## Build status

This **builds and runs** (`./gradlew build`, `./gradlew runClient`) against
a real NeoForge/Create toolchain, and both blocks show up and work in game.
The scaffold's original "I couldn't compile this, check it yourself" list
has been worked through - what it flagged, and what turned out to be true:

- **`sable_version` was a literal placeholder string**
  (`REPLACE_WITH_LATEST_SABLE_VERSION_ID`) that would have failed
  dependency resolution outright. Now a real Modrinth version id. The
  other two (`create_version`, `create_aeronautics_version`) were already
  valid - verified against Create's maven and Modrinth's API.
- **Create Aeronautics' modId is `aeronautics`, not `create_aeronautics`**
  - the Modrinth slug and the in-game modId genuinely differ, and NeoForge
  refused to load the mod until `neoforge.mods.toml` was corrected. `sable`
  was already right. (Confirmed from the mod list a `runClient` prints at
  startup - easier than unzipping jars.)
- **`AirportRegistry`'s `SavedData` signature, and the `PacketDistributor`
  / `StreamCodec.of` calls** were all correct as written. No changes needed.
- **Gradle plugin/dependency versions** were fine, but two things were
  missing rather than wrong: there was no Gradle wrapper in the project at
  all (added from the official 1.21.1 MDK), and Registrate - which Create's
  `slim` jar deliberately strips - needs both its own dependency line and
  its own maven repo (`maven.ithundxr.dev/snapshots`).

The one non-obvious trap, worth knowing if you add more dependencies:
**`compileOnly` deps are not on the dev-run classpath.** Create,
Create Aeronautics and Sable are all `compileOnly` (correct - this is an
addon and shouldn't bundle them), but that means `runClient` had none of
them installed, and `neoforge.mods.toml` requires all three, so mod loading
failed. `build.gradle` now has a parallel set of `runtimeOnly` lines purely
so the dev environment behaves like a real install. Those don't affect the
shipped jar.

You need **JDK 21** installed (nothing else - the wrapper fetches Gradle,
and ModDevGradle fetches/decompiles Minecraft on first build, which takes a
while and several GB).

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
