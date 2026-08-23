# Skyport (working title)

A Create Aeronautics addon: draw an airport's runway, taxiways, gates and
holding pattern on a map at an **Airport Station** block, then set a
destination on an **Autopilot** block and it flies there - gate to gate,
no pilot required.

## What it does

Aircraft fly themselves, for real - this drives an assembled Create
Aeronautics craft through Sable's physics, not an animation.

- Place an **Airport Station**, name the airport, and draw its layout on a
  terrain-accurate map: runway, taxiways, holding pattern, final leg, gates,
  helipads and a hold-short line. It registers world-wide, so any autopilot
  anywhere can fly to it.
- Place an **Autopilot** on the craft, pointing along the fuselage. Name it,
  pick Plane, Heli or Blimp, and build a schedule of stops - each with a
  departure condition (a timer, a player boarding, or cargo being loaded or
  unloaded) and an optional loop. Engage from the screen or with redstone.
- Place an **ATC** block to see every airport and every aircraft on one
  live map, with a traffic strip listing what each one is doing.

Aircraft taxi, hold short, take off, climb out on the runway heading, cruise,
join a pattern or go straight in when the runway's clear, land, and taxi to
their gate. Rotorcraft skip all of that and go up, across and down onto a
pad. They keep out of each other's way: one aircraft on the runway at a
time, one on the taxiway, one per helipad, and altitude separation in the
air.

## Setting an airport up

The map is precise but abstract, so **hold an Airport Station** after opening
one and the layout is traced in the world in front of you - that's how you
build a runway where you drew one, or find the taxiway to tow an aircraft to.

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
   - **Hold Line** - one point on the taxiway where aircraft wait for the
     runway.
   - **Pad** - helipads for rotorcraft, which need no runway at all.
   - **Flow** - not a drawing mode. Click a taxiway segment to cycle it
     two-way -> one-way -> one-way reversed; one-way segments get an
     arrowhead. Aircraft route around them, so a loop with an inbound and an
     outbound half lets arrivals and departures pass instead of queueing on
     one shared strip. The editor refuses a change that would strand a gate.

   Clicks land on the exact block under the cursor, so zoom in (scroll or
   the -/+ buttons) for finer placement; the crosshair turns green when a
   click will join an existing point. Closing the screen saves.
5. Back on the station screen, the summary line shows what's actually
   registered (`Runway ok   Taxiway ok   Holding 0/3 ...`). Anything not
   "ok" doesn't have enough points to fly yet.
6. Place an Autopilot block, right-click it, pick the airport and gate,
   hit Engage Autopilot.
7. Watch it fly. Routine progress goes to the action bar and the ATC
   screen; chat keeps arrivals and anything needing a decision. Per-second
   telemetry is available but off by default - see the config.

Engaging is refused if the plane is sitting on the ground away from any
marked taxiway/runway/gate - it'll tell you to tow it onto one first.
Engaging while airborne skips the ground states and climbs to cruise
altitude instead.

## What's here

- `AirportStationBlock` / `AirportStationBlockEntity` - the station
  block. Owns one `AirportLayout` and keeps it registered in the world's
  `AirportRegistry` so any Autopilot block can look it up from anywhere.
- `AutopilotBlock` / `AutopilotBlockEntity` - the flight state machine and
  the steering that flies the craft, via Sable's BlockEntitySubLevelActor.
- `AtcBlock` / `AtcBlockEntity` - the tower overview.
- `world/` - FlightChunkLoader (a loaded bubble that follows an aircraft)
  and FleetWake (temporarily loading parked aircraft so stalled schedules
  can resume).
- `data/` - `Waypoint` (runway/taxiway/holding-pattern/final leg/hold line - the enum's javadoc is where each one's point-count and ordering
  rules are written down), `AirportLayout` (those waypoint lists, a named
  `gates` map, plus holding-pattern altitude and turn direction),
  `AirportSummary` (the lightweight version sent to the autopilot picker),
  `AirportRegistry` (world-level `SavedData`).
- `network/` - every C2S/S2C payload, registered in `ModNetworking`.
- `client/gui/` - `AirportStationScreen` (name the airport, see what's
  registered, open the editor), `AirportMapScreen` (the editor: terrain-
  sampled background, per-mode drawing rules),
  `AutopilotScreen` (the schedule editor) and `AtcScreen` (the tower map).
- `registry/ModCreativeTabs` - a Skyport creative tab holding all three
  blocks. They have crafting recipes too, built on Create's andesite alloy,
  brass and electron tubes.
- `client/LayoutProjector` - traces a layout in the world while you hold a
  station.

## Making it cost something in survival

The autopilot steers by writing velocity onto the craft, so out of the box it
makes thrust from nothing - an Autopilot on a solid cube of iron flies as well
as a real aeroplane. Fine while building; a cheat in survival. Set
`survival.powerRequirement` in `config/skyport-common.toml`:

- `NONE` (default) - flight is free. Right for creative and for testing a
  layout.
- `ROTATION` - Create rotational force has to reach the Autopilot block. Put a
  shaft or cogwheel against it, driven by whatever powertrain the aircraft
  already carries; `survival.rotationMinimumRpm` sets how much (16 by default,
  about one water wheel).
- `FUEL` - the autopilot burns furnace fuel out of any container on the
  aircraft, at furnace burn times scaled by `survival.fuelEfficiency`.
  Anything that burns in a furnace works, including other mods' fuels.

Either way, losing power in flight is an engine failure rather than a pause:
the autopilot holds the wings level but stops driving the craft, so it coasts
and descends. Aircraft parked at a gate burn nothing.

### Speed costs fuel

Burn rate rises with cruise speed raised to `survival.fuelSpeedExponent`,
measured against `survival.fuelReferenceSpeed` (24 by default - the default
cruise speed, so an aircraft nobody has retuned burns exactly what it always
did).

The exponent has to be above 1 for the choice to mean anything. At 1.0 the
rate rises exactly in step with speed, so a journey costs the same fuel
however fast it is flown and there is never a reason to fly slowly. The
default of 2.0 makes fuel per block scale with speed, and matches the fact
that drag really does rise with the square of speed. On one coal:

| Cruise speed | Endurance | Range |
| --- | --- | --- |
| 12 | 320s | 3840 blocks |
| 24 (default) | 80s | 1920 blocks |
| 48 | 20s | 960 blocks |
| 80 | 7s | 576 blocks |

There is a floor on the burn rate, because an aircraft holding with its engine
running is still burning something. One consequence of that is emergent rather
than designed: below roughly speed 8 the floor dominates, so range stops
improving and starts falling again. There is a genuine best-range cruise
speed, the way there is for a real aircraft.

With telemetry on (`messages.telemetry`) the readout shows seconds of fuel
remaining at the speed currently being flown.
## Known limits

Each of these is a reasonable next step rather than an oversight:

- **Ground routing pathfinds, but taxi order is per-airport, not per-gate.**
  Dijkstra picks the route across the taxiway graph, so aircraft no longer
  zigzag - but with several gates an aircraft may still pass spurs that
  aren't its own. See `AutopilotBlockEntity#groundTaxiPath`.
- **Terrain on the maps is client-side.** Sampled from whatever chunks the
  client has loaded, and remembered afterwards (`TerrainMemory`), so a
  corner of the world nobody has visited stays blank. That's honest, but it
  isn't a survey map.
- **"On the ground" is a heightmap guess.** `isOnGround` compares height
  against the world surface rather than reading a real flight state.
- **Waypoints have no altitude of their own** except the holding pattern.
  Everything else is placed at the station's Y.
- **No placement restriction on the Autopilot block** - it doesn't require
  being on an assembled craft (see the TODO on `AutopilotBlock`), which is
  convenient for testing a layout and wrong for a finished mod.
- **Aircraft hold the world open while flying.** A moving bubble of forced
  chunks is the blunt answer to unloaded-chunk freezing; Create's trains
  solve it properly by tracking position as data. All of it is configurable,
  including off - see `config/skyport-common.toml`.

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

`./gradlew runServer` runs a dedicated server, in its own `run-server/`
directory so it doesn't fight `runClient` over the world session lock. Worth
running after any change that touches networking or screens: it caught a
startup crash that the client never could, because a dedicated server refuses
to load client-only classes and the integrated one is happy to.
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

## License

LGPL-3.0-or-later. `LICENSE` is the LGPL text and `COPYING` is the GPL it
builds on; `NOTICE` summarises what that means in practice. The short version:
use it, ship it in a modpack, and depend on it from your own mod freely -
your mod does not become LGPL by depending on this one. Modify Skyport itself
and redistribute that, and those changes have to be published too.

Bundled third-party data is credited in `CREDITS.md`.
