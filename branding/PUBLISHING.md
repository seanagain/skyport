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
| License | LGPL-3.0-or-later (choose "Custom" and link the repo if LGPL-3.0 is not listed) |
| Source | https://github.com/seanagain/skyport |
| Issues | https://github.com/seanagain/skyport/issues |
| Logo | `branding/logo.png` (512×512) |

## Relations — add all four

Every one is **Required Dependency**. The mod will not load without them, and
CurseForge uses these to fetch dependencies automatically for pack users.

- Create
- Create Aeronautics
- Sable
- (NeoForge and Minecraft versions are set by the file, not by relations)

## Summary

Short blurb, ~80 characters. CurseForge shows this under the title in search
results.

> Draw an airport on a map, give an aircraft a schedule, and it flies itself.

## Description

Paste as Markdown if the editor offers it; otherwise the headings become
plain bold lines.

---

**Skyport** is an airport and air-logistics addon for Create Aeronautics.

Draw an airport on a map — runway, taxiways, gates, helipads — then give an
aircraft a schedule. It flies itself, gate to gate, with traffic separation
and no pilot.

Aircraft are flown for real, through Sable's rigid-body physics. Nothing here
is an animation or a scripted path: they accelerate down the runway, rotate,
climb out on the runway heading, cruise, join a holding pattern or go straight
in when the runway is clear, descend the final leg, land, and taxi to a gate.

### Three blocks

**Airport Station** — defines one airport. Name it, set its taxi speed, and
draw its layout on a terrain-accurate map. The airport registers world-wide,
so any aircraft anywhere can be sent to it. Hold the block after drawing and
the layout is traced in the world in front of you, so you can build a runway
where you drew one.

**Autopilot** — goes on an assembled aircraft, arrow pointing along the
fuselage. Pick Plane, Heli or Blimp, set a cruise speed and altitude, and build
a schedule of stops — each with a departure condition: a timer, a player
boarding, or cargo being loaded or unloaded. Engage from its screen or with a
redstone signal.

**Air Traffic Control** — one live map of every airport and every aircraft,
with a strip listing what each one is doing. Point a Create Display Link at it,
or at a station, for a departures board.

### Traffic actually gets separated

One aircraft per runway, one on the taxiway, one per helipad, and altitude
separation in the air. Departures wait behind the hold line until the runway
ahead is clear, the way a train waits at a signal.

Airports can have a **second runway** used only by departures, so a departure
stops queueing behind a landing aircraft. Taxiway segments can be made
**one-way**, so a loop with an inbound and an outbound half lets arrivals and
departures pass instead of sharing one strip.

### Survival

Out of the box flight is free, which is right for building and a cheat in
survival. One config option changes that:

- **Rotation** — Create rotational force must reach the Autopilot block. Feed
  it from the powertrain your aircraft already carries.
- **Fuel** — the autopilot burns furnace fuel from any container aboard. Faster
  cruise speeds cost more fuel *per block travelled*, not just per second, so
  choosing a cruise speed is a real decision. Run out mid-flight and it is an
  engine failure, not a pause: the wings stay level and the aircraft comes down.

### Requirements

Minecraft 1.21.1, NeoForge 21.1.0+, Create 6.0.0+, Create Aeronautics 1.3.0+,
and Sable.

### Beta

This is a first public build. It has been flown end to end — two-runway
airports, both survival power modes, dedicated servers — but you are among the
first people other than the author to run it. Known limits are listed in the
README, including two worth knowing up front: the Autopilot can be placed on
any block rather than only an assembled craft, and the engage packet is not
validated, so a public server with untrusted players is not yet a good home
for it.

There are no in-game Ponder guides in this release. The old ones described an
earlier version of the editor, and a tutorial that is confidently wrong is
worse than none. The README covers everything.

---

## File upload

| Field | Value |
| --- | --- |
| File | `build/libs/skyport-1.0.0-beta.1.jar` |
| Display name | Skyport 1.0.0-beta.1 |
| Release type | **Beta** |
| Game version | 1.21.1 |
| Loader | NeoForge |
| Java | 21 |

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
- [ ] Release type is Beta, not Release
- [ ] Logo uploaded
