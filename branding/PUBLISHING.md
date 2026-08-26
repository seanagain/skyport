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

**Skyport** adds airports to Create Aeronautics, and aircraft that use them
without a pilot.

You draw an airport on a map — runway, taxiways, gates, helipads, holding
pattern — and give an aircraft a list of stops. It then flies that schedule on
its own: pushes back from the gate, taxis out, holds short of the runway until
it is clear, accelerates, rotates, climbs out on the runway heading, cruises to
the next airport, joins a holding pattern or goes straight in, descends the
final approach, lands, and taxis to its gate.

The aircraft is flown through Sable's rigid-body physics, the same physics that
moves it when you fly it yourself. It is steered by force, not teleported along
a path, so it banks into turns, takes a runway's length to get airborne, and
can be knocked about on the way.

### The three blocks

**Airport Station** — one airport. Name it, set how fast aircraft taxi there,
and draw its layout on a map that shows the real terrain around it. The airport
registers world-wide, so any aircraft anywhere can be sent to it. Hold the
block after drawing and the layout is projected onto the ground in front of
you, so you can build a runway exactly where you drew one.

**Autopilot** — placed on an assembled aircraft, its arrow along the fuselage.
Choose Plane, Heli or Blimp, set cruise speed and altitude, and build a
schedule. Each stop waits for a condition before departing: a timer, a player
boarding, cargo being loaded, or cargo being unloaded. Engage from its screen,
or wire it to a redstone signal.

**Air Traffic Control** — every airport and every aircraft on one live map,
with a list of what each aircraft is doing right now. Aircraft in unloaded
chunks are listed separately and can be woken from here. Point a Create Display
Link at this block, or at a station, to drive a departures board.

### Aircraft keep out of each other's way

One aircraft on a runway at a time, one on the taxiway, one per helipad, and
altitude separation in the air. A departure waits behind the hold line until
the runway ahead is free, the way a train waits at a signal, and an arrival
holds in the pattern rather than landing on top of someone.

An airport can have a **second runway** that only departures use, so a
departing aircraft stops queueing behind one that is landing. Taxiway segments
can be made **one-way**, so a loop with an inbound and an outbound half lets
arrivals and departures pass instead of sharing a single strip.

### Optional survival cost

By default flight costs nothing, which suits creative building. Two config
options make it cost something:

- **Rotation** — Create rotational force has to reach the Autopilot block. Run
  a shaft or cogwheel to it from the powertrain your aircraft already carries.
- **Fuel** — the autopilot burns furnace fuel from any container aboard.
  Flying faster costs more fuel *per block travelled*, not merely per second,
  so cruise speed becomes a real trade between range and speed. Running dry in
  flight is an engine failure rather than a pause: the wings stay level, but
  the aircraft comes down.

### Requirements

Minecraft 1.21.1 · NeoForge 21.1.0 or newer · Create 6.0.0+ · Create
Aeronautics 1.3.0+ · Sable

All four are required. Skyport does not bundle them.

### This is a beta

A first public build. Two-runway airports, both survival modes, and dedicated
servers have all been flown end to end, but few people other than the author
have run it yet.

Two limits worth knowing before you install:

- The Autopilot can currently be placed on any block, not only on an assembled
  aircraft.
- The engage packet is not yet validated, so on a public server with untrusted
  players a crafted packet could start an aircraft that is not theirs. Fine for
  single-player and private servers; not yet suited to an open one.

There are no in-game Ponder guides in this release. The previous ones described
an older version of the layout editor, and a guide that is confidently wrong is
worse than none — the README covers everything instead.

Bug reports and suggestions are welcome on the issue tracker.

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
