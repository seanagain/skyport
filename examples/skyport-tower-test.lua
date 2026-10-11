--[[
  skyport-tower-test - exercise the ATC block from the ground.

  The tower is the half that can reach an aircraft you are nowhere near,
  including one that is flying. That is the useful part and the part most
  worth distrusting, so this checks it against a real aircraft and puts its
  schedule back afterwards.

  Put a computer against an ATC block and run it. It picks an aircraft off
  the roster by itself, or you can name one:

      towertest                 pick the first loaded aircraft
      towertest "Cargo 1"       use that one
      towertest "Cargo 1" engage   ... and start its flight too
]]

local args = { ... }
local wanted, doEngage = nil, false
for _, a in ipairs(args) do
  if a == "engage" then doEngage = true else wanted = a end
end

local passed, failed, skipped = 0, 0, 0
local function ok(n, d) passed = passed + 1; print("[ ok ] " .. n .. (d and ("  " .. d) or "")) end
local function bad(n, d) failed = failed + 1; print("[FAIL] " .. n .. (d and ("  " .. d) or "")) end
local function skip(n, d) skipped = skipped + 1; print("[skip] " .. n .. "  " .. d) end
local function check(n, c, d) if c then ok(n, d) else bad(n, d) end end

local function expectRefusal(name, fn)
  local success, err = pcall(fn)
  if success then
    bad(name, "ACCEPTED - should have been refused")
  else
    ok(name, "refused: " .. tostring(err):gsub("^.-:%d+:%s*", ""))
  end
end

local function count(t)
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

local tower = peripheral.find("skyport_tower")
if not tower then
  print("No tower peripheral found. Put this computer against an ATC block.")
  print("Visible now:")
  for _, name in ipairs(peripheral.getNames()) do
    print(("  %s  (%s)"):format(name, peripheral.getType(name)))
  end
  return
end

print("=== the network ===")

local airports = tower.getAirports()
check("getAirports", type(airports) == "table", #airports .. " found")
for _, a in ipairs(airports) do
  print(("       %s  %d gates, %d pads, %d runways"):format(
    a.name, count(a.gates), count(a.helipads), a.runways))
end

if #airports > 0 then
  local byName = tower.getAirport(airports[1].name)
  check("getAirport(name)", byName ~= nil and byName.name == airports[1].name, airports[1].name)
  -- A name nobody has used should come back nil rather than erroring: it is
  -- a question, not a demand.
  check("getAirport(nonsense) is nil", tower.getAirport("Not An Airport zzz") == nil)
end

check("getVors", type(tower.getVors()) == "table", #tower.getVors() .. " found")

local traffic = tower.getTraffic()
check("getTraffic", type(traffic) == "table", #traffic .. " moving")
for _, ac in ipairs(traffic) do
  print(("       %s  %s -> %s"):format(ac.callsign, ac.state, ac.destination))
end

local roster = tower.getRoster()
check("getRoster", type(roster) == "table", #roster .. " known")
local loadedOnes = {}
for _, ac in ipairs(roster) do
  print(("       %s  %s%s%s"):format(ac.callsign, ac.state,
    ac.awake and "" or "  asleep", ac.loaded and "" or "  (not loaded)"))
  if ac.loaded then loadedOnes[#loadedOnes + 1] = ac end
end

-- ---------------------------------------------------------------------------

local target = wanted
if not target then
  if #loadedOnes == 0 then
    print("")
    skip("everything below", "no loaded aircraft on the roster to work with")
    print(("%d passed, %d failed, %d skipped"):format(passed, failed, skipped))
    return
  end
  target = loadedOnes[1].callsign
end

print("")
print("=== one aircraft: " .. target .. " ===")

local info
local found, err = pcall(function() info = tower.getAircraft(target) end)
if not found then
  bad("getAircraft", tostring(err):gsub("^.-:%d+:%s*", ""))
  print("")
  print(("%d passed, %d failed, %d skipped"):format(passed, failed, skipped))
  return
end

check("getAircraft", info ~= nil and info.callsign ~= nil,
  ("%s, %s, stop %d"):format(info.callsign, info.state, info.currentStop))
check("  it carries a schedule", type(info.schedule) == "table",
  count(info.schedule.stops) .. " stops at speed " .. info.schedule.cruiseSpeed)
-- The id matters: it is how you address an aircraft when two share a name.
ok("  id", info.id or "nil (never flown)")
ok("  subLevelId", info.subLevelId or "nil (not on a craft)")

-- An aircraft that does not exist, and an id that does not: both should be
-- refused by name rather than resolving to whatever happened to be first.
expectRefusal("unknown callsign is refused", function()
  tower.getAircraft("Definitely Not A Plane zzz")
end)
expectRefusal("unknown id is refused", function()
  tower.getAircraft("00000000-0000-0000-0000-000000000000")
end)

print("")
print("=== editing it from here (restored afterwards) ===")

local saved = info.schedule

if info.engaged then
  skip("write checks", "it is flying (" .. info.state .. ") - land it first")
else
  local success, writeErr = pcall(function()
    local original = saved.cruiseAltitude
    local target2 = original == 160 and 150 or 160

    tower.setSchedule(target, { cruiseAltitude = target2 })
    check("setSchedule by callsign",
      tower.getAircraft(target).schedule.cruiseAltitude == target2,
      "altitude is now Y" .. target2)

    check("  the route was left alone",
      count(tower.getAircraft(target).schedule.stops) == count(saved.stops),
      "still " .. count(saved.stops) .. " stops")

    if info.id then
      tower.setSchedule(info.id, { cruiseAltitude = original })
      check("setSchedule by id",
        tower.getAircraft(target).schedule.cruiseAltitude == original,
        "back to Y" .. original)
    else
      skip("setSchedule by id", "this aircraft has no id yet")
    end

    expectRefusal("a bad gate is refused from here too", function()
      tower.setSchedule(target, { stops = { { airport = "Not An Airport zzz" } } })
    end)
  end)

  if not success then
    bad("an unexpected error stopped the write checks", tostring(writeErr))
  end

  local restored, restoreErr = pcall(function() tower.setSchedule(target, saved) end)
  if restored then
    local now = tower.getAircraft(target).schedule
    check("restored the original schedule",
      now.cruiseAltitude == saved.cruiseAltitude
        and now.cruiseSpeed == saved.cruiseSpeed
        and count(now.stops) == count(saved.stops),
      ("Y%d, speed %d, %d stops"):format(now.cruiseAltitude, now.cruiseSpeed, count(now.stops)))
  else
    bad("COULD NOT RESTORE the schedule", tostring(restoreErr))
  end
end

if doEngage then
  print("")
  print("=== engaging from the tower ===")
  local started, engageErr = pcall(function() tower.engage(target) end)
  if started then
    ok("engage", "now " .. tower.getAircraft(target).state)
    -- The cooldown is shared with the redstone input and exists because
    -- engaging rescans the route. A second attempt straight away must be
    -- refused, not served.
    expectRefusal("a second engage inside the cooldown is refused", function()
      tower.engage(target)
    end)
  else
    print("[ -- ] engage refused: " .. tostring(engageErr):gsub("^.-:%d+:%s*", ""))
  end
end

print("")
print(("%d passed, %d failed, %d skipped"):format(passed, failed, skipped))
if failed > 0 then print("Copy the FAIL lines back to me.") end
