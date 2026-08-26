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
listed separately and wake with a click. Point a Create Display Link at it for
a departures board.

### They stay out of each other's way

One aircraft per runway, one on the taxiway, one per pad, and altitude
separation in the air. Departures wait behind the hold line like a train at a
signal; arrivals circle rather than land on someone.

When one runway is not enough, add a **second for departures only** so nobody
queues behind a landing. When one taxiway is not enough, make segments
**one-way** — an inbound half and an outbound half, and traffic flows past
itself instead of nose to nose.

### Make it cost something

Flight is free by default, which is right for building. Two options are not:

- **Rotation** — Create rotational force must reach the Autopilot. Run a shaft
  to it from the powertrain your aircraft already carries.
- **Fuel** — furnace fuel, from any container aboard. Flying faster costs more
  per block travelled, not just per second, so speed is genuinely traded
  against range. Run dry mid-flight and it is an engine failure, not a pause:
  wings level, and down you go.

### Requirements

Minecraft 1.21.1 · NeoForge 21.1.0+ · Create 6.0.0+ · Create Aeronautics
1.3.0+ · Sable — all required, none bundled.

### It is a beta

Two-runway airports, both survival modes and dedicated servers have been flown
end to end, but hardly anyone outside the author has run this yet. Two limits
before you install: the Autopilot can be placed on any block rather than only
on an aircraft, and the engage packet is not yet validated, so an open server
with untrusted players is not a good home for it. Single-player and private
servers are fine.

No in-game Ponder guides this release — the old ones described an older editor,
and a guide that is confidently wrong is worse than none. The README has
everything.

Bugs and suggestions welcome on the issue tracker.

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
