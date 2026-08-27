# Skyport

An airport and air-logistics addon for [Create Aeronautics](https://modrinth.com/mod/create-aeronautics).

Draw an airport on a map — runway, taxiways, gates, helipads — then give an
aircraft a schedule. It flies itself, gate to gate, with traffic separation
and no pilot.

Aircraft are flown for real, through Sable's rigid-body physics. Nothing here
is an animation or a scripted path.

## Requirements

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.0+ |
| Create | 6.0.0+ |
| Create Aeronautics | 1.3.0+ |
| Sable | any |

## The blocks

**Airport Station** — defines one airport. Name it, set its taxi speed, and
draw its layout on a terrain-accurate map. The airport registers world-wide,
so any aircraft anywhere can be sent to it. Hold the block after drawing and
the layout is traced in the world in front of you, which is how you build a
runway where you drew one.

**Autopilot** — placed on an assembled aircraft, arrow pointing along the
fuselage. Pick Plane, Heli or Blimp, set a cruise speed and altitude, and
build a schedule of stops. Engage from its screen or with a redstone signal.

**Air Traffic Control** — one live map of every airport and every aircraft,
with a traffic strip listing what each one is doing. Aircraft that have gone
to sleep are listed separately and can be woken by clicking their name.

## Drawing an airport

Open the station, hit **Edit Map**, and pick what to draw from the dropdown.
The hint line above the map spells out the click rule for the current mode,
because they differ.

| Mode | Clicks |
| --- | --- |
| **Runway** | 2: gate end, then far end |
| **Taxiway** | In pairs — each pair is one segment |
| **Holding pattern** | 3+, forming the loop aircraft circle in |
| **Final leg** | 2: pattern side first, then the runway threshold |
| **Gate** | 1 each, auto-named Gate A, Gate B … |
| **Hold line** | In pairs — a line across the taxiway |
| **Helipad** | 1 each; rotorcraft need no runway |
| **Move nodes** | Drag a node. Joined lines follow it |
| **Add node on a line** | Click a line to split it, creating a junction |

Right-drag pans, scroll zooms, and **Center** returns to the station.

**Two runways.** The toggle beside the dropdown adds a second runway, used by
departures and never by arrivals, so a departure stops queueing behind a
landing aircraft. With two selected the dropdown lists them separately. Leave
it off and one runway does both.

**One-way taxiways.** In Taxiway mode, click the *body* of a drawn segment to
cycle it two-way → one-way → reversed. One-way segments get an arrowhead, and
aircraft route around them — so a loop with an inbound and an outbound half
lets arrivals and departures pass instead of sharing one strip. The editor
refuses a change that would leave a gate unreachable.

**Joins are positional.** A taxiway meets a runway by having a point at the
same coordinates. That is why dragging a node moves everything sitting on it,
and why a line that merely crosses another is not connected until you add a
node where they meet.

## Traffic separation

One aircraft per runway, one on the taxiway, one per helipad, and altitude
separation in the air. Departures hold behind the hold line until the runway
ahead is clear, the way a train waits at a signal.

Clearances are leases, not locks: a holder that stops reporting loses its
claim after ten seconds, so an aircraft broken mid-flight or unloaded cannot
lock an airport permanently.

## Survival

By default flight is free — the autopilot writes velocity onto the craft, so
an Autopilot on a solid cube of iron flies as well as a real aeroplane. Fine
for building; a cheat in survival. Set `survival.powerRequirement` in
`config/skyport-common.toml`:

- **`NONE`** (default) — flight costs nothing.
- **`ROTATION`** — Create rotational force must reach the Autopilot block. Put
  a shaft or cogwheel against it, driven by the craft's own powertrain.
- **`FUEL`** — burns furnace fuel from any container on the aircraft. Anything
  that burns in a furnace works, including other mods' fuels.

In `FUEL` mode, **cruise speed costs fuel**, and faster costs more per block
travelled, not merely more per second. On one coal at default settings:

| Cruise speed | Endurance | Range |
| --- | --- | --- |
| 12 | 320s | 3840 blocks |
| 24 (default) | 80s | 1920 blocks |
| 48 | 20s | 960 blocks |

Losing power in flight is an engine failure rather than a pause: the autopilot
holds the wings level but stops driving the craft, and it comes down.

## Chunk loading

A flying aircraft holds a small bubble of loaded chunks so it doesn't fly into
unloaded world and freeze. Parked aircraft release theirs and sleep, which is
how the rest of Minecraft treats unattended corners of the world.

The tower lists sleeping aircraft, and clicking one wakes it for ten minutes —
enough to finish a gate wait and get moving. Everything about this is
configurable, including off; `performance.chunkRadius` is the single biggest
cost this mod imposes on a server.

## Locks

An Airport Station or Autopilot belongs to whoever placed it. Nobody else can
open it, change it, engage it, or break it — the last one matters most, since
breaking a station deletes its airport and every aircraft heading there loses
its destination.

To let someone else in, **sneak + right-click** the block with both hands empty
and set a passcode. Anyone who enters it can use that block, and anything else
you locked with the same code, until the server restarts. Only the owner can
change or remove the code — knowing a code gets you into a block, not into its
lock.

Server operators always have access. Blocks placed before this existed have no
owner recorded and stay open to everyone; break and replace one to claim it.
The whole thing is `protection.protectBlocks` in the config, on by default.

The checks run on the server for every action, not in the screen — the screen
only produces packets, and a modified client can produce them without ever
opening anything. Turning the setting off turns off that enforcement too.

## Known limits

- **Terrain on the maps is client-side**, sampled from loaded chunks and
  remembered afterwards. A corner of the world nobody has visited stays blank.
- **Waypoints have no altitude of their own** except the holding pattern.
  Everything else sits at the station's height.
- **The Autopilot can be placed on anything**, not only an assembled craft.
- **Locks stop players, not explosions.** A creeper or a TNT cannon will still
  take out a locked block; the check runs on the break event, which a blast
  does not go through.
- **Ponder scenes are switched off** in this release. They described an older
  version of the editor, and a tutorial that is confidently wrong is worse
  than none.

## Building from source

Needs JDK 21 and nothing else — the Gradle wrapper fetches the rest.

```
./gradlew build        # jar into build/libs
./gradlew test         # unit tests
./gradlew runClient    # dev client
./gradlew runServer    # dedicated server, in its own run-server/
```

See `CONTRIBUTING.md` for the traps worth knowing before changing the build.

## License

LGPL-3.0-or-later. `LICENSE` is the LGPL text, `COPYING` the GPL it builds on,
and `NOTICE` summarises what that means in practice: use it, ship it in a
modpack, and depend on it from your own mod freely — your mod does not become
LGPL by depending on this one. Modify Skyport itself and redistribute that,
and those changes have to be published too.

Dependencies and their licenses are listed in `CREDITS.md`. Design notes are
in `DESIGN.md`.
