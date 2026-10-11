--[[
  skyport-info - show what is actually there.

  Run this before writing anything that sets a schedule. It names no airport,
  no gate and no beacon of its own: everything it prints was read out of the
  world, so it tells you the real spellings to use rather than assuming the
  ones in a README.

  Works next to an Autopilot, an ATC block or an Airport Station, and tells
  you which it found. Put a computer against any of them and run it.
]]

local function rule(title)
  print("")
  print("== " .. title .. " ==")
end

local function pos(p)
  if not p or not p.x then return "?" end
  return ("%d, %d, %d"):format(p.x, p.y, p.z)
end

-- Names of the keys of a table, sorted, as "a, b, c". Gates and helipads come
-- back keyed by name with a position inside, so this is how you read off the
-- spellings a stop is allowed to use.
local function keyList(t)
  local names = {}
  if t then for name in pairs(t) do names[#names + 1] = name end end
  table.sort(names)
  if #names == 0 then return "(none drawn)" end
  return table.concat(names, ", ")
end

local function showAirports(dev)
  rule("Airports")
  local airports = dev.getAirports()
  if #airports == 0 then
    print("None. Draw one at an Airport Station first.")
    return
  end
  for _, a in ipairs(airports) do
    print(a.name)
    print("  gates:    " .. keyList(a.gates))
    print("  helipads: " .. keyList(a.helipads))
    print(("  runways:  %d   takeoff: %s"):format(a.runways, a.takeoff))
  end
end

local function showVors(dev)
  local vors = dev.getVors()
  if #vors == 0 then return end
  rule("VOR beacons")
  for _, v in ipairs(vors) do
    print(("%s  at %s"):format(v.name, pos(v.position)))
  end
end

local function showSchedule(ap)
  rule("This aircraft")
  print(("%s  -  %s"):format(ap.getCallsign(), ap.getState()))
  print("at " .. pos(ap.getPosition()))

  local fuel = ap.getFuel()
  if fuel then
    print(("fuel: %.0fs%s"):format(fuel.seconds, fuel.dry and "  (DRY)" or ""))
  end

  local sched = ap.getSchedule()
  print(("craft %s   alt Y%d   speed %d   loop %s   power %s"):format(
    sched.craft, sched.cruiseAltitude, sched.cruiseSpeed,
    tostring(sched.loop), sched.power))

  rule("Its schedule")
  if #sched.stops == 0 then
    print("No stops set.")
  else
    local current = ap.getCurrentStop()
    for i, stop in ipairs(sched.stops) do
      local marker = (i == current) and "->" or "  "
      if stop.kind == "vor" then
        print(("%s %d. via %s  [fly over]"):format(marker, i, stop.vor))
      else
        print(("%s %d. %s / %s  [%s %ds]"):format(
          marker, i, stop.airport, stop.gate, stop.wait, stop.waitSeconds))
      end
    end
  end
end

local function showTraffic(dev)
  rule("Traffic")
  local traffic = dev.getTraffic()
  if #traffic == 0 then
    print("Nothing moving.")
  end
  for _, ac in ipairs(traffic) do
    print(("%s  %s -> %s"):format(ac.callsign, ac.state, ac.destination))
  end
end

local function showRoster(tower)
  rule("Roster (includes sleeping)")
  local roster = tower.getRoster()
  if #roster == 0 then
    print("No aircraft have flown yet.")
  end
  for _, ac in ipairs(roster) do
    print(("%s  %s%s"):format(ac.callsign, ac.state, ac.awake and "" or "  (asleep)"))
    -- The id is what you pass to tower.setSchedule when two aircraft share a
    -- callsign, so it is worth having on screen.
    print("  id " .. ac.id)
  end
end

-- ---------------------------------------------------------------------------

local ap = peripheral.find("skyport_autopilot")
local tower = peripheral.find("skyport_tower")
local station = peripheral.find("skyport_station")

if not (ap or tower or station) then
  print("No Skyport peripheral attached.")
  print("")
  print("Put this computer against an Autopilot, an ATC block or an")
  print("Airport Station. What I can see right now:")
  local names = peripheral.getNames()
  if #names == 0 then
    print("  (nothing at all)")
  end
  for _, name in ipairs(names) do
    print(("  %s  (%s)"):format(name, peripheral.getType(name)))
  end
  return
end

if ap then
  print("Found: autopilot")
  showSchedule(ap)
  showAirports(ap)
  showVors(ap)
elseif tower then
  print("Found: tower")
  showAirports(tower)
  showVors(tower)
  showTraffic(tower)
  showRoster(tower)
else
  print("Found: station - " .. (station.getName() or "no layout drawn yet"))
  local airport = station.getAirport()
  if airport then
    rule("This airport")
    print("  gates:    " .. keyList(airport.gates))
    print("  helipads: " .. keyList(airport.helipads))
    print(("  runways:  %d   takeoff: %s"):format(airport.runways, airport.takeoff))
  end
  showTraffic(station)
end

print("")
print("Use the names above exactly as printed.")
