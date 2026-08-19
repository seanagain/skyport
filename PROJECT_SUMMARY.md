# Skyport - Conversation Summary (for picking this up in Claude Code)

Context for continuing this project in a new session: what it is, what's
been decided, what's built, and what's still open. Read this first, then
`README.md` (setup + how to test) and `DESIGN.md` (visual/UX decisions).

## The idea

Sean runs a Minecraft NeoForge 1.21.1 server (~145 mods, heavily
Create-focused: Create Aeronautics, Steam 'n' Rails, TFMG). He wanted to
build his first Minecraft mod, is into aeronautics, and has made
modpacks before but has no modding experience yet.

We landed on an addon for **Create Aeronautics**: an airport/logistics
system where you draw an airport layout on a map (runway, taxiways,
holding pattern, gates) and planes fly it on autopilot with no pilot
required - including holding in a pattern near the airport while waiting
to land. The explicit goal was to avoid the lag/crash problems Sean has
already hit with existing ship/contraption mods on his server (an
AeroPortals ship got stuck in an infinite nether-teleport retry loop
during a real crash incident) by using lightweight waypoint-following
instead of full physics simulation, closer to how Create's own trains
work.

Two blocks:
- **Airport Station** - right-click opens a map editor; draw the layout.
- **Autopilot** - right-click, pick a destination airport + gate, engage.

## Key design decisions

- **NeoForge 1.21.1, Java 21** - matches Sean's existing server exactly.
- **Addon to Create Aeronautics**, not standalone - depends on Create +
  Create Aeronautics + Sable (all three already run on Sean's server).
- **Visual identity**: both blocks use Create's own andesite+brass
  control-panel look so they feel native. Airport Station has a green
  radar-screen accent, Autopilot has a blue compass-dial accent - the
  only visual difference between them.
- **Map color coding follows real aeronautical chart convention**:
  white/cream runway, yellow taxiway, blue dashed holding pattern,
  orange gates. Chosen because Sean already knows what a real runway
  diagram looks like.
- **GUI style**: after a feedback round, deliberately "more minecrafty" -
  vanilla-style beveled panels (dark border, bright highlight, dark
  shadow, dithered fill), item-slot-style fields, drop-shadowed pixel
  font on short labels, dirt-menu-style background. See
  `skyport-gui-mockup.html` (sent separately in chat, not part of the
  project files) for the reference mockup.
- **Map rendering is deliberately plain per feedback**: "just lines
  drawn on a Minecraft map, nothing too fancy" - no filled shapes, no
  icons, no compass rose. Just small square waypoint markers connected
  by straight pixel lines, which is also literally what the data model
  stores (a `Waypoint` per click).
- **The hardest unsolved technical problem, by design, deferred**:
  flying a plane through *unloaded* chunks without keeping them
  force-loaded the whole way, the way Create's trains do (they track
  position as lightweight data independent of chunk loading and only
  reassemble the real structure when observed). Intentionally not
  tackled yet - get everything else working in loaded chunks first.

## What's actually built (a working prototype, not just a scaffold)

Full NeoForge Gradle project scaffolded from the official 1.21.1
ModDevGradle MDK, with Create/Ponder/Flywheel and Create
Aeronautics/Sable (via Modrinth's maven) wired as compile-only
dependencies. On top of that:

- Complete data model (`Waypoint`, `AirportLayout` with named gates,
  `AirportSummary`, world-level `AirportRegistry` via `SavedData`).
- All 5 network payloads (engage/disengage/save-layout C2S,
  open-map/open-autopilot S2C), registered and wired end to end.
- `AirportMapScreen` - real click-to-place waypoint/gate editor.
- `AutopilotScreen` - real cycle-button airport/gate picker.
- `AutopilotBlockEntity` - the full flight state machine (`TAXI_OUT ->
  TAKEOFF_ROLL -> CRUISE -> HOLDING -> APPROACH -> TAXI_IN -> IDLE`)
  actually runs, driving a *simulated* position with chat/action-bar
  telemetry.
- Hand-drawn 16x16 pixel-art textures for both blocks, a creative tab,
  full lang file.

**The one deliberate stand-in**: nothing visibly moves in the world yet.
`AutopilotBlockEntity#applyMotionTowards` advances a phantom `Vec3`
rather than a real Create Aeronautics contraption, because building this
scaffold happened without access to that mod's actual contraption-
movement API. Everything calling into that one method (state machine,
waypoint sequencing, arrival detection) is real, working logic - only
that one method needs to change to move a real plane. That's the
natural first task in a Code session: find Create Aeronautics' actual
plane/contraption classes and swap this in.

## Known simplifications (see README for the full list)

No real terrain on the map (flat color, not `MapItemSavedData`
sampling); no altitude; taxi/approach reuse the same waypoint order in
both directions rather than reversing; no ATC/queueing (one plane's
state per Autopilot block, holding is a fixed one-lap wait); Autopilot
block has no placement restriction yet (should require an assembled
contraption once that API is in view).

## Unverified / worth double-checking early in the new session

This was all written without a working NeoForge/Create toolchain
available (sandboxed network, couldn't reach `maven.neoforged.net` /
`maven.createmod.net`). Ran every `.java` file through `javac` with no
classpath to catch real syntax errors (none found), but that's not the
same as a real compile. Specifically flagged as best-effort in the
README: exact mod version numbers in `gradle.properties`
(`sable_version` is a placeholder), the `modId` strings for
`create_aeronautics`/`sable` in `neoforge.mods.toml`, `AirportRegistry`'s
`SavedData` save/load method signature, and the `PacketDistributor`/
`StreamCodec.of` networking calls throughout `network/` and the block
entities. First real build in Claude Code, with real internet access
and IDE indexing, is the actual test of all of this.

## Suggested first message in the new Claude Code session

Something like: "Read PROJECT_SUMMARY.md, README.md, and DESIGN.md in
this folder for context, then get this Create Aeronautics addon
building - start with the items flagged in README's 'Before your first
real build' section."
