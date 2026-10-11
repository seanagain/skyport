# Skyport ComputerCraft API

Every function Skyport exposes to CC: Tweaked. Install CC and the blocks
become peripherals; without it nothing here exists and nothing else changes.

Run `examples/skyport-info.lua` on a computer next to any of them to see your
own airports, gates and aircraft with their real names — **every name you pass
below is one you chose when you drew it**, so read them rather than typing
what this document happens to use.

| Peripheral | Attach to | What it is |
| --- | --- | --- |
| `skyport_autopilot` | Autopilot block | One aircraft. Works aboard the craft. |
| `skyport_tower` | ATC block | The whole network, including aircraft in the air. |
| `skyport_station` | Airport Station | One airport, read-only. |

Everything runs on the server thread, so a tight polling loop costs real
server time. Read what you need and sleep.

---

## `skyport_autopilot`

One aircraft — the one the computer is next to. Works on the ground and
mounted on the craft itself, so a computer bolted to an aeroplane can fly its
own route.

### Reading

| Function | Returns |
| --- | --- |
| `getCallsign()` | `string` — the aircraft's name, or its generated callsign |
| `getState()` | `string` — `idle`, `pushback`, `taxi_out`, `takeoff_roll`, `climb`, `cruise`, `holding`, `approach`, `taxi_in`, `waiting`, `vertical_climb`, `vertical_descent`, `hovering` |
| `isEngaged()` | `boolean` — anything but idle |
| `getPosition()` | `{x, y, z}` — the **craft's** position, not the block's |
| `getAircraftId()` | `string` or `nil` — nil until its first flight |
| `getSubLevelId()` | `string` or `nil` — Sable's id for the craft it rides, matching CC: Sable's `sublevel.getUniqueId()` |
| `getFuel()` | `{reserve, seconds, dry}` or `nil` where fuel is not burned |
| `getUnattendedSeconds()` | `number` or `nil` if unbounded |
| `getSchedule()` | the schedule table (below) |
| `getCurrentStop()` | `number` — 1-based, matching `getSchedule().stops` |
| `getAirports()` | list of airport tables (below) |
| `getVors()` | list of `{id, name, dimension, position}` |

### Writing

| Function | Notes |
| --- | --- |
| `setSchedule(table)` | **Partial** — see below |
| `addStop(table)` | Appends one stop; returns the new stop count |
| `removeStop(index)` | 1-based; refuses an index that does not exist |
| `moveStop(index, delta)` | Negative is earlier; returns where it ended up |
| `setName(string)` | |
| `setCruiseAltitude(n)` | 0–600 |
| `setCruiseSpeed(n)` | 4–80 blocks/second |
| `setLoop(boolean)` | |
| `setPowerSource("rotation"\|"fuel")` | Only meaningful where the server allows either |
| `engage()` | Starts the saved schedule. Errors with the reason if it cannot |
| `disengage()` | |

`engage()` is rate-limited to one attempt every five seconds, **sharing that
budget with the redstone input**, because engaging rescans the whole route for
terrain. A `while true do ap.engage() end` is the same mistake that once hung
a server, so it is refused and told so.

---

## `skyport_tower`

The whole network from the ground, including aircraft that are flying right
now — a control room without a computer on every aeroplane.

| Function | Returns / notes |
| --- | --- |
| `getAirports()` | list of airport tables |
| `getAirport(name)` | one airport table, or `nil` |
| `getVors()` | list of VOR tables |
| `getTraffic()` | what is moving now: `{id, callsign, state, destination, airborne, position}` |
| `getRoster()` | everything ever flown, sleeping included: as above plus `awake`, `loaded`, `dimension` |
| `getAircraft(which)` | `{callsign, state, engaged, position, currentStop, id, subLevelId, schedule}` |
| `setSchedule(which, table)` | Same rules as the autopilot's |
| `engage(which)` | |
| `disengage(which)` | |

`which` is a **callsign or an id**. Ids are tried first and exactly. Callsigns
are matched case-insensitively, and an ambiguous one is **refused by name**
rather than resolved to whichever came first — re-routing the wrong aeroplane
is not something a script could detect afterwards. Use the id from
`getRoster()` when two aircraft share a name.

The aircraft must be **loaded**. A flying one always is (it holds its own
chunks); a sleeping one is not, and `getRoster()`'s `loaded` field says so.

---

## `skyport_station`

One airport. Read-only: gates and runways are placed by eye against terrain,
and a script setting one to a computed coordinate would build an airport that
looks right in a table and taxis aircraft into a wall.

| Function | Returns |
| --- | --- |
| `getAirport()` | the airport table, or `nil` before a layout is drawn |
| `getName()` | `string` or `nil` |
| `getGates()` | list of gate names |
| `getHelipads()` | list of pad names |
| `getTraffic()` | traffic inbound to this airport |

---

## Tables

### Schedule

```lua
{
  name = "Cargo 1",
  craft = "plane",          -- plane | helicopter | blimp
  loop = true,
  cruiseAltitude = 150,     -- 0-600
  cruiseSpeed = 24,         -- 4-80
  power = "rotation",       -- rotation | fuel
  stops = {
    { kind = "airport", airport = "Heathrow", airportId = "...",
      gate = "Gate A", wait = "timer", waitSeconds = 30 },
    { kind = "vor", vor = "ALPHA", vorId = "..." },
  },
}
```

`wait` is `timer`, `player`, `cargo_loaded` or `cargo_empty`.

A stop names an airport **or** a VOR, never both. Give either the name or the
id — the id is there so a program can hand back exactly the stop it was given,
even on a server with two airports called "Main".

### Airport

```lua
{
  id = "...", name = "Heathrow", dimension = "minecraft:overworld",
  gates = { ["Gate A"] = {x=…, y=…, z=…} },   -- keyed by name
  helipads = { ["Pad 1"] = {x=…, y=…, z=…} },
  runways = 1,
  taxiSpeed = 10,
  holdingPatternHeight = 90,
  takeoff = "towards_final_leg",               -- or away_from_final_leg / undefined
  station = {x=…, y=…, z=…},
}
```

---

## setSchedule is a partial update

A table naming only `cruiseSpeed` changes the speed and **leaves the route
alone**. The alternative — every field you did not mention reverting — turns
the obvious one-line script into one that deletes a flight plan.

```lua
ap.setSchedule{ cruiseSpeed = 40 }            -- route untouched
ap.setSchedule{ stops = { ... } }             -- speed untouched
```

Nothing is applied unless the whole table validates, so a route that fails
half way through leaves the aircraft exactly as it was.

## Errors explain themselves

Every refusal is a Lua error with a reason, so `pcall` gets something worth
printing:

- an unknown gate **names the ones that exist**
- an unknown airport or VOR quotes what you asked for
- a misspelled field is **rejected, not ignored** — `cruisespeed = 40` would
  otherwise be accepted in full and change nothing, which is impossible to
  tell from a bug in the mod
- a helicopter is offered helipads, not gates
- out-of-range numbers name the range; a fractional altitude is refused
  rather than rounded, because a script computing 150.5 has a bug in it

```lua
local ok, err = pcall(function()
  ap.setSchedule{ stops = { { airport = "Heathrow", gate = "Gate Q" } } }
end)
-- err: no gate called "Gate Q" there - it has Gate A, Gate B
```

## Lists are 1-based

Everything returning a list returns a proper Lua array, so `#airports` and
`ipairs` work as you would expect. Gates and helipads are **keyed by name**
instead, so `airport.gates["Gate A"].x` reads directly — use `pairs` for those.

## Scripts

- `examples/skyport-info.lua` — prints everything in your world, with real names
- `examples/skyport-test.lua` — exercises the whole autopilot API and restores
  the schedule afterwards; safe on a working aircraft
- `examples/skyport-tower-test.lua` — the same for the tower
