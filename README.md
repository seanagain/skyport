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
to sleep are listed separately; select one and press Wake.

**VOR Beacon** — a named point on the map. Place it, right-click it to give it
a name, and any aircraft's schedule can be routed over it.

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
| | Click an existing gate to rename it or move it up/down the list |
| **Hold line** | 1 each, anywhere along a taxiway |
| **Helipad** | 1 each; rotorcraft need no runway |

**Tools.** The strip down the left of the map is what a click does, as opposed
to what it draws: **Draw** places points, **Move** drags a node (joined lines
follow it), **Add node** splits a line to make a junction, and **Delete**
removes what you click — a gate, a pad, a hold point, or a whole taxiway
segment, since deleting one end of a segment would leave the other stranded.

Right-drag pans, scroll zooms, and **Center** returns to the station.

**Two runways.** The toggle beside the dropdown adds a second runway, used by
departures and never by arrivals, so a departure stops queueing behind a
landing aircraft. With two selected the dropdown lists them separately. Leave
it off and one runway does both.

**Takeoff direction.** With one runway, the Airport Station screen has a
button that reads "Takeoff: towards final leg" or "Takeoff: away from final
leg". Towards means departures roll toward the outer end of the final leg —
head-on with the way landing aircraft come in, which is what an airport
drawn the way this README describes always did. Away means they roll the way landing aircraft fly:
the aircraft taxis up to the runway as usual, carries on down it to the far
end, turns, and takes off back the other way, the way a real runway backtrack
works. Landing and taxiing are unaffected either way.

It is measured against where your final leg actually points, so it means the
same thing however the runway was clicked. It needs a final leg drawn roughly
in line with the runway — the button says so and stays greyed out until there
is one — and it does nothing once a second runway is drawn, since that one's
direction is whatever you drew it as.

**One-way taxiways.** In Taxiway mode, click the *body* of a drawn segment to
cycle it two-way → one-way → reversed. One-way segments get an arrowhead, and
aircraft route around them — so a loop with an inbound and an outbound half
lets arrivals and departures pass instead of sharing one strip. The editor
refuses a change that would leave a gate unreachable.

**Joins are positional.** A taxiway meets a runway by having a point at the
same coordinates. That is why dragging a node moves everything sitting on it,
and why a line that merely crosses another is not connected until you add a
node where they meet.

## Routing over VORs

A VOR is a stop an aircraft flies over instead of landing at. On the Autopilot
screen the destination button cycles through every airport and then every VOR;
a VOR stop has no gate and no wait.

`Home → VOR North → Harbour` takes off from Home, crosses VOR North, and lands
at Harbour. Use one to route round terrain the straight line would hit, or to
bring arrivals in from the side that lines them up with the holding pattern —
the aircraft picks its pattern entry after its last VOR, not back at the
airport it left.

- **Crossed at cruise altitude.** Only the VOR's x and z matter. The block
  stands on the ground; nothing flies at it.
- **"Over" is loose on purpose:** within about 16 blocks, or near and moving
  away again, or circling without getting any closer. A VOR is for routing by,
  not a target to hit, and a wide-turning aircraft should not orbit one
  forever.
- **The terrain check follows the route.** Engage checks each leg through the
  VORs, so a route placed to go round a mountain is not refused for the
  mountain.
- **A schedule needs an airport.** VORs after the last airport of a schedule
  that does not loop lead nowhere and are not flown.
- **A broken VOR is skipped**, with a note, rather than bringing the aircraft
  down mid-route.

On the Autopilot screen the destination button opens a list of every airport
and VOR, and the ^ and v buttons move a stop up or down the route. The order of
the list is the route: a stop below the last airport is never flown, and the
editor marks it in amber when that happens.

VORs show on the ATC map as purple diamonds, and the traffic strip says which
one an aircraft is heading for. They lock to whoever placed them, like
stations and autopilots.

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
for building; a cheat in survival. Set `survival.powerRequirement` in the
config (see [Config](#config) for where it lives):

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

The tower lists sleeping aircraft; select one and press Wake to keep it loaded
for ten minutes — enough to finish a gate wait and get moving. Everything about
this is configurable, including off; `performance.chunkRadius` is the single
biggest cost this mod imposes on a server.

## Unattended flight

A parked aircraft releases its chunks and sleeps, so a route only ever
advanced as far as its first stop unless a player followed it round — a loop
never looped. `performance.unattendedMinutes` is how long a parked aircraft
may keep setting off regardless: fifteen minutes by default, and fifteen is
the cap.

**It bounds departures, not flights.** An aircraft already in the air always
finishes its leg and lands, however long that takes. There is nowhere to put
an aeroplane down half way, and stopping the clock mid-route left one frozen
over open country until somebody flew out to it. A gate is the one place an
aircraft can wait indefinitely and cost nothing, so that is where the limit
bites: out of time, it stays parked and waits to be found.

The clock refills whenever a player comes within about 128 blocks, on engage,
and when the tower wakes it. Set it to 0 for the old behaviour, where an
aircraft sleeps on arrival and goes nowhere until something wakes it.

Holding chunks is what all this costs — the same as `performance.chunkRadius`,
see Chunk loading above, for as long as an aircraft is flying or has time
left. `/skyport info` prints what an aircraft has left and how many chunks it
is holding, which is the quickest way to find out why one has stopped.

## Locks

An Airport Station or Autopilot belongs to whoever placed it. Nobody else can
open it, change it, engage it, or break it — the last one matters most, since
breaking a station deletes its airport and every aircraft heading there loses
its destination.

To let someone else in, open the block and press **Passcode**. Anyone who
enters it can use that block, and anything else
you locked with the same code, until the server restarts. Only the owner can
change or remove the code — knowing a code gets you into a block, not into its
lock.

Server operators always have access. Blocks placed before this existed have no
owner recorded and stay open to everyone; break and replace one to claim it.
The whole thing is `protection.protectBlocks` in the config, on by default.

The checks run on the server for every action, not in the screen — the screen
only produces packets, and a modified client can produce them without ever
opening anything. Turning the setting off turns off that enforcement too.

## Admin commands

Three, all at permission level 2 — the level a command block or an operator
has.

| Command | What it does |
| --- | --- |
| `/skyport list` | Every engaged aircraft: callsign, what it is doing, where, and where it is going |
| `/skyport info <aircraft>` | One aircraft in full — ids, position, and its flight plan with the current stop marked |
| `/skyport wake <aircraft>` | The tower Wake button, from anywhere — loads the world around it and reports back whether it actually started running |
| `/skyport forget <aircraft>` | Takes an aircraft off the tower, disengaging it first if its autopilot is loaded |

Name an aircraft by callsign or by id; tab-completion offers the callsigns.
An ambiguous callsign is refused with the ids rather than guessed at.

**`info`** prints the aircraft id the tower uses, the **Sable id** of the craft
it is riding on, its state and whether it is still ticking, and — when its
chunk is loaded — the whole schedule with a `>` against the stop it is working
on, plus unattended time and fuel where those apply. With the chunk unloaded
only the written-down half exists, and it says so rather than printing blanks.

**`forget`** is for an aircraft that is on the map but not really there: a
contraption picked up with a wrench or a container leaves its autopilot behind
holding clearances nothing will release, and that is what a ghost at a gate
is. If the autopilot is loaded it is disengaged first, which hands back the
runway and taxiway and stops it writing itself back onto the roster. If it is
not loaded and the aircraft does still exist, it will come back the next time
its chunk loads — break or disengage the block to be rid of it for good.

## Config

Two files, and which one a setting is in decides who gets to choose it.

**`config/skyport-server.toml`** — nearly everything: messages, chunk loading,
survival costs, block protection. This is *server* config in NeoForge's sense,
meaning the server owns it and pushes it to every client on connect. A player
editing their own copy changes nothing, which is what makes a server's rules
binding.

It sits in `config/` and applies to every world, the same as any other config
file. If you want one world to differ, drop a copy at
`<world>/serverconfig/skyport-server.toml` and it overrides the global one for
that world only — per-world is available but opt-in.

**`config/skyport-client.toml`** — one setting, `client.terrainMemoryLimit`,
which caps how much remembered terrain the ATC map keeps in your own memory.
No server has any business deciding that for you.

> **Updating from an earlier build:** this used to be a single
> `config/skyport-common.toml`, which loaded on both sides and synced neither.
> That file is now ignored. Anything you changed in it needs setting again in
> the new location; a fresh file with defaults is written the first time a
> world loads.

## Known limits

- **Terrain on the maps is client-side**, sampled from loaded chunks and
  remembered afterwards. A corner of the world nobody has visited stays blank.
- **Waypoints have no altitude of their own** except the holding pattern.
  Everything else sits at the station's height.
- **The Autopilot can be placed on anything**, not only an assembled craft.
- **Locks stop players, not explosions.** A creeper or a TNT cannon will still
  take out a locked block; the check runs on the break event, which a blast
  does not go through.
- **Redstone is not covered by the lock.** A signal reaching a locked Autopilot
  still engages and disengages it, because a redstone pulse arrives with no
  player attached and there is nothing to check it against. Someone who can
  place a lever next to your aircraft can still start or stop it — and stopping
  it mid-flight brings it down. Guarding it would mean breaking redstone
  control for the owner too, which is the worse trade.
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
