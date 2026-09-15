# CurseForge listing

Everything the upload form asks for. Not part of the mod — this file exists so
the listing can be recreated or corrected without reconstructing it from memory.

## Project settings

| Field | Value |
| --- | --- |
| Name | Skyport |
| Slug / URL | `skyport` (fall back to `skyport-aeronautics` if taken) |
| Primary category | Technology |
| Secondary | Automation, Create-related if the pack offers it |
| Mod loader | NeoForge |
| Game version | 1.21.1 |
| License | LGPL-3.0-or-later — if the dropdown does not list it, choose "Custom" and paste the block under [Custom license text](#custom-license-text) |
| Source | https://github.com/seanagain/skyport |
| Issues | https://github.com/seanagain/skyport/issues |
| Logo | `branding/logo.png` (512×512) |

## Custom license text

If CurseForge's dropdown has no LGPL-3.0 entry, pick "Custom" and paste this
verbatim. Do not paste the full LGPL and GPL texts — they run to some 43,000
characters together, and the box is not the place for them. Naming the licence
by its SPDX identifier and linking the canonical text is what actually
identifies it; the summary is explicitly marked informal so it cannot be read
as altering the terms.

---

Skyport is licensed under the **GNU Lesser General Public License, version 3
or (at your option) any later version** (SPDX: `LGPL-3.0-or-later`).

Full licence text:
- LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
- GPL-3.0, which the LGPL extends: https://www.gnu.org/licenses/gpl-3.0.txt

Both are included in the mod's source repository as `LICENSE` and `COPYING`:
https://github.com/seanagain/skyport

**In plain terms** — informal summary only; the licence text above governs:

- Play with it, and include it in any modpack, public or private, free or
  paid. No permission needed and none need be asked for.
- Write a mod that depends on Skyport, and keep your own mod under whatever
  licence you like. Depending on it does not make your code LGPL.
- Modify Skyport itself and distribute that, and those modifications must be
  released under the LGPL as well, with source available.
- No warranty is given.

---

## Relations — add all four

Every one is **Required Dependency**. The mod will not load without them, and
CurseForge uses these to fetch dependencies automatically for pack users.

- Create
- Create Aeronautics
- Sable
- (NeoForge and Minecraft versions are set by the file, not by relations)

## Checked against CurseForge moderation policy

The policy is at
`support.curseforge.com/support/solutions/articles/9000197279-moderation-policies`.
What it demands of a listing, and how this one answers:

- **Descriptions must be clear, informative and specific.** Vague claims like
  "changes core game" are grounds for rejection. The description below leads
  with concrete mechanics — what the blocks do, how traffic is separated, what
  the survival options cost — rather than adjectives.
- **Do not copy descriptions from other projects.** Nothing here is lifted
  from Create or Create Aeronautics. Say what *this* adds, and link the
  dependencies rather than describing them.
- **Name must be English and carry no version or technical detail.**
  "Skyport" qualifies.
- **AI-modified showcase images that could misrepresent the mod need a visible
  disclaimer.** The logo is the Airport Station block texture scaled up by
  whole-number nearest-neighbour — the mod's own art, not a generated image,
  and not a misrepresentation, so no disclaimer applies. Keep it that way for
  screenshots: use real gameplay, uncomposited. If a promotional image is ever
  made that does not reflect what the mod looks like in game, it needs the
  disclaimer.
- **Third-party content needs licensing.** Skyport bundles nothing. Its
  dependencies are declared, not shipped, and are credited in `CREDITS.md`.
- **Loader compatibility must match across dependencies.** Everything here is
  NeoForge 1.21.1.
- **Donation and promotional links belong at the page bottom.** There are none
  in the copy below; if any are added later, put them last.

## Summary

Short blurb, ~80 characters. CurseForge shows this under the title in search
results.

> Draw an airport on a map, give an aircraft a schedule, and it flies itself.

## Description

Paste as Markdown if the editor offers it; otherwise the headings become
plain bold lines.

---

Draw a runway on a map. Go and build it. Then stand at the fence and watch an
aircraft you are not flying push back from its gate, taxi out, hold short until
the runway is clear, and leave without you.

**Skyport** gives Create Aeronautics airports — and aircraft with the sense to
use them.

Set a schedule and an aircraft flies it: pushback, taxi, takeoff roll, climb
out on the runway heading, cruise, a holding pattern if the field is busy,
final approach, landing, and a taxi to its gate. All of it through Sable's
rigid-body physics — the same physics as when you fly it yourself. It is pushed
by force rather than dragged along a path, so it banks into its turns and needs
a runway's length to get off the ground.

### Three blocks

**Airport Station** — one airport. Draw its runway, taxiways, gates, helipads
and holding pattern on a map of the real terrain around it, then hold the block
and watch the layout projected onto the ground so you can build exactly what
you drew. Airports register world-wide; any aircraft can be sent to any of them.

**Autopilot** — goes on the aircraft, arrow along the fuselage. Plane, heli or
blimp; a cruise speed, an altitude, and a list of stops. Each stop waits for
something before it leaves: a timer, a passenger, cargo loaded, or cargo gone.
Start it from its screen or with redstone.

**Air Traffic Control** — every airport and every aircraft on one live map,
with a strip down the side saying what each one is doing. Sleeping aircraft are
listed separately; select one and press Wake. Point a Create Display Link at it
for a departures board.

### They stay out of each other's way

One aircraft per runway, one on the taxiway, one per pad, and altitude
separation in the air. Departures wait behind the hold line like a train at a
signal; arrivals circle rather than land on someone.

When one runway is not enough, add a **second for departures only** so nobody
queues behind a landing. When one taxiway is not enough, make segments
**one-way** — an inbound half and an outbound half, and traffic flows past
itself instead of nose to nose.

And underneath all of that, an aircraft simply stops for another that is
physically in its way, booked or not — so a badly drawn airport, or one whose
hold lines you have not placed yet, jams rather than crashes.

### Make it cost something

Flight is free by default, which is right for building. Two options are not:

- **Rotation** — Create rotational force must reach the Autopilot. Run a shaft
  to it from the powertrain your aircraft already carries.
- **Fuel** — furnace fuel, from any container aboard. Flying faster costs more
  per block travelled, not just per second, so speed is genuinely traded
  against range. Run dry mid-flight and it is an engine failure, not a pause:
  wings level, and down you go.

### On a shared server

Airport Stations and Autopilots belong to whoever placed them. Nobody else can
open one, engage it, or break it — and breaking a station would take its airport
down with every flight routed there. Open one and press Passcode to let
somebody else in. Operators always have access, and the whole thing switches
off in the config for a server where everyone is trusted.

Schedules keep running for a while with nobody watching —
`performance.unattendedMinutes`, fifteen by default — so a route completes a
lap instead of stopping at its first gate the moment you walk away. It holds
chunks open to do it, which is why the server decides how long, not each
aircraft.

### Requirements

Minecraft 1.21.1 · NeoForge 21.1.0+ · Create 6.0.0+ · Create Aeronautics
1.3.0+ · Sable — all required, none bundled.

### What 1.0 means

Everything described above works: two-runway airports, one-way taxiways,
rotorcraft, both survival modes, and dedicated servers have all been flown end
to end, aircraft complete their schedules unattended, and the traffic
separation keeps them out of each other's way.

Two things it does not have. The Autopilot can be placed on any block rather
than only on an assembled aircraft — it will happily fly a cube of iron, which
is useful for testing and a cheat if you would rather it were not. And there
are no in-game Ponder guides: the old ones described an earlier version of the
editor, and a guide that is confidently wrong is worse than none. The README
covers the editor properly.

It is 1.0 because it does what it says and has been flown enough to trust with
an airport you built. It is a first 1.0, so bugs and suggestions are very
welcome on the issue tracker.

### Where it is going

Nothing below is promised or dated. It is the order things are likely to
happen in, so you can tell whether the mod is heading somewhere you want.

**Next**

- Autopilot restricted to actual aircraft, instead of any block
- Trusted access lists, so a group can share an airport without sharing a code
- In-game guides rewritten for the current editor
- Terrain on the ATC map for players who have not flown there themselves
- Engine noise, and radio chatter at the tower
- Advancements, and a log of completed flights

**More aviation**

- Performance taken from the propellers you actually built, so a heavier
  aircraft needs a longer runway and a bigger powertrain
- Wind: a headwind that costs you fuel, a storm that closes a field
- Villagers as passengers, boarding and disembarking
- Progressive taxi clearance, so a long taxiway can hold several aircraft in
  sequence instead of one at a time
- Diversion to an alternate when the destination is unusable
- Schedules that keep running without holding chunks open

**Land and sea**

The longer-term direction, and less of a leap than it sounds. The routing
graph underneath the taxiways is already a road network — directed segments,
one-way rules, shortest path, junction reservations. Schedules, cargo
conditions, fuel, the live map and the dispatch system never cared what kind
of vehicle they were moving.

- Depots and roads for ground vehicles, using that same routing
- Right of way at junctions, and level crossings with Steam 'n' Rails
- Harbours, berths and sea lanes for ships, with depth kept under the keel
- One schedule spanning several vehicles: flown in, trucked to a depot,
  shipped onward
- A single dispatcher screen for everything moving, not only what is flying

If a particular one of these matters to you, say so on the issue tracker -
that is largely how the order gets decided.

---

## File upload

| Field | Value |
| --- | --- |
| File | `build/libs/skyport-1.0.1.jar` |
| Display name | Skyport 1.0.1 |
| Release type | **Release** |
| Game version | 1.21.1 |
| Loader | NeoForge |
| Java | 21 |

### Changelog for 1.0.1

> **VOR beacons.** A new block for routing aircraft over a point on the way
> somewhere, instead of straight from airport to airport. Place one, right-click
> it to name it, and on the Autopilot screen the destination button now cycles
> through the airports and then the VORs. A VOR stop is flown over, not landed
> at — no gate, no wait.
>
> Use one to take a route round a mountain, or to bring arrivals in from the
> side that lines them up with the holding pattern: an aircraft picks its
> pattern entry after its last VOR rather than back at the airport it left.
> The terrain check at Engage follows the route through the VORs, so a route
> built to avoid a mountain is not refused because of the mountain.
>
> VORs are crossed at the schedule's cruise altitude, show on the ATC map as
> purple diamonds, and lock to whoever placed them. A broken VOR is skipped
> rather than ending the flight, and existing schedules load unchanged.
>
> The Autopilot screen now picks a destination from a list rather than cycling
> through it, and stops can be moved up and down the route. That order is the
> route, and a VOR only counts when it sits above the airport it routes toward.
>
> **Admin commands.** `/skyport list`, `/skyport info <aircraft>` and
> `/skyport forget <aircraft>`, all at permission level 2. `info` prints an
> aircraft's ids — including Sable's own id for the craft — its state, and its
> flight plan with the stop it is working on marked. `forget` takes one off
> the tower, disengaging it first if its autopilot is loaded, which is the
> cure for an aircraft that is on the map but was picked up with a container
> and left its clearances behind.
>
> **Aircraft no longer sleep in the air.** performance.unattendedMinutes used
> to bound flying itself, so an aircraft whose time ran out mid-leg froze
> wherever it happened to be - over open country, or over a VOR. It bounds
> departures now: an airborne aircraft always finishes its leg and lands, and
> a parked one waits at its gate once the clock is spent.
>
> Crafted from a lightning rod over brass, an electron tube and brass, over
> three andesite alloy. Mine it with a pickaxe to get it back.

### Changelog for 1.0.0

> **1.0.** The first public build could draw an airport and fly a schedule.
> This one is about aircraft that arrive where they were sent — without
> spinning on the way, landing two blocks above the runway, or parking through
> the terminal wall — on airports that belong to whoever built them.
>
> **Aircraft no longer spin at random.** Two causes, both real. The autopilot
> steered from the Autopilot block rather than the craft's centre of mass, so
> anything with the block mounted off-centre fought its own corrections. And
> the level-flight correction was handed the craft's own nose as its target,
> which made the yaw error exactly zero and left no authority to hold a
> heading with. Turning on the ground is gentler, stops once on heading, and
> is lag-neutral, so a stutter no longer becomes a pirouette.
>
> **Landing.** Aircraft touch down on the runway that is built rather than the
> height it was drawn at, roll out at that same corrected height instead of
> rising a couple of blocks and dropping, and are required to actually be down
> before anything calls them landed. Rotorcraft land on the pad and hold their
> heading through a vertical descent instead of chasing noise near the ground.
> The glide slope is flown properly, the circuit sits at a height measured
> from the ground below it, and the holding-pattern entry is flown at cruise
> speed rather than slowing unnaturally on the way in.
>
> **Parking at a gate.** Aircraft park on the gate node instead of four or
> five blocks to one side of it, which is what used to put them into each
> other and into buildings. They roll onto the stand and slow down gradually
> rather than driving at it and stopping dead, and they stop rocking once
> parked. Taxiway nodes are loose enough to cut corners naturally; the stand
> itself stays tight.
>
> **Hold lines are points, and they work.** Place one anywhere along a taxiway
> rather than only on a node. The whole aircraft waits behind the line, not
> its centre, and the segment behind is only released once the aircraft has
> completely crossed it. Two clicks used to make a line, which joined
> unrelated taxiways into diagonals across the airport.
>
> **A collision failsafe that does not need hold lines.** An aircraft stops
> for another that is physically in its way, whether or not that one booked
> the route. It gives way for at most eight seconds, so a stale or broken
> aircraft slows traffic briefly instead of blocking an airport permanently —
> which a "ghost" left behind by picking a craft up used to do.
>
> **Editor.** A tool strip beside the map: draw, move, add node, and delete,
> so a single gate, pad, hold point or taxiway segment can be removed without
> starting again. Gates can be renamed and reordered.
>
> **Unattended flight.** A parked aircraft released its chunks and slept, so a
> route only ever advanced as far as its first stop unless you followed it
> round — a loop never looped. `performance.unattendedMinutes` is how long an
> aircraft may carry on with nobody near it: fifteen minutes by default, and
> fifteen is the cap. Server config, because it is asking the server to hold
> chunks open.
>
> **The tower.** The ATC block works while mounted on an aircraft, aircraft
> markers move live on its map, and clicking a row in the traffic strip now
> selects that aircraft instead of waking it — waking is the Wake button.
>
> **Airports and aircraft belong to whoever placed them.** Nobody else can
> open, engage, or break your Airport Station or Autopilot — and breaking a
> station would have taken its airport down with every flight routed there.
> Open a block and press Passcode to let someone else in; operators always
> have access, and it all switches off in the config.
>
> The important part is where that is checked. Five packets changed state and
> none of them verified who sent it, so a modified client could engage any
> autopilot or overwrite any airport layout in the world without ever opening
> a screen. Every one is now checked on the server.
>
> **Server settings are now binding.** Config was a single COMMON file that
> loaded on both sides and synced neither, so a server and a client could
> disagree about the rules. It is now server config, pushed to clients on
> connect. Still `config/skyport-server.toml`; a per-world override in
> `<world>/serverconfig/` is optional. One client-only setting moved to
> `config/skyport-client.toml`.
>
> Note: the old `config/skyport-common.toml` is ignored, so anything you
> changed in it needs setting again.
>
> **Better block models.** All three blocks got real geometry and their own
> side textures instead of wearing the same screen on every face. Fixes a
> hole straight through to the sky when two of them sat side by side, which
> came from all three claiming to be solid cubes when none of them is.
>
> **Also:** an engaged aircraft's physics body is kept awake, so it no longer
> stalls mid-taxi; a pushback that cannot finish gives up and says so instead
> of stalling silently; and the autopilot no longer holds taxiing aircraft
> very slightly off the ground, which was a side effect of asking for zero
> vertical velocity and thereby cancelling gravity. And all three blocks now
> drop themselves when mined with a pickaxe — they had no loot tables, and
> required a correct tool without anything saying what that was, so a broken
> block simply vanished.
>
> **Known:** if a runway's drawn waypoints were placed at the wrong height in
> an existing save, redraw that runway — the landing fix reads the built
> ground, but a stored waypoint height is still what it was saved as.

### Changelog for the first file

> First public build.
>
> Airports drawn on a terrain map, aircraft that fly themselves gate to gate
> through Sable physics, and traffic separation that keeps them out of each
> other's way. Optional second runway for departures, one-way taxiways,
> helipads for rotorcraft, cargo- and player-triggered schedules, a live ATC
> map, and Create Display Link support for departure boards.
>
> Optional survival costs: Create rotation, or furnace fuel where cruise speed
> changes what a journey costs.
>
> See the README for known limits.

## Before publishing — check

- [ ] `gradle.properties` version matches the file being uploaded
- [ ] Built from a clean tree (`./gradlew clean build`), tests green
- [ ] The jar in `build/libs` is the one being uploaded, not a stale sibling
- [ ] All four dependency relations added, all marked Required
- [ ] Release type is Release, not Beta
- [ ] Logo uploaded
