--[[
  skyport-test - exercise the whole peripheral and put everything back.

  Safe to run on a real aircraft. It saves the schedule before it touches
  anything and restores it at the end, including when a check fails part way
  through, so a working flight plan survives being tested on.

  It will NOT engage the aircraft unless you ask:   test engage
  And it skips every write check on an aircraft that is already flying,
  because rewriting a route mid-approach is not a test, it is an incident.

  Usage:  test          read checks, write checks, restore
          test engage   the above, then actually start the flight
]]

local args = { ... }
local doEngage = false
for _, a in ipairs(args) do
  if a == "engage" then doEngage = true end
end

local passed, failed, skipped = 0, 0, 0

local function ok(name, detail)
  passed = passed + 1
  print("[ ok ] " .. name .. (detail and ("  " .. detail) or ""))
end

local function bad(name, detail)
  failed = failed + 1
  print("[FAIL] " .. name .. (detail and ("  " .. detail) or ""))
end

local function skip(name, why)
  skipped = skipped + 1
  print("[skip] " .. name .. "  " .. why)
end

local function check(name, condition, detail)
  if condition then ok(name, detail) else bad(name, detail) end
end

-- Runs fn and expects it to blow up. The message matters as much as the
-- failure: a refusal that does not say what was wrong is barely better than
-- silence, so the text is printed for you to read.
local function expectRefusal(name, fn)
  local success, err = pcall(fn)
  if success then
    bad(name, "it was ACCEPTED - it should have been refused")
  else
    ok(name, "refused: " .. tostring(err):gsub("^.-:%d+:%s*", ""))
  end
end

local function pos(p)
  if not p or not p.x then return "?" end
  return ("%d, %d, %d"):format(p.x, p.y, p.z)
end

local function stopCount(sched)
  local n = 0
  for _ in pairs(sched.stops) do n = n + 1 end
  return n
end

-- ---------------------------------------------------------------------------

local ap = peripheral.find("skyport_autopilot")
if not ap then
  print("No autopilot peripheral found.")
  print("Put this computer against an Autopilot block. Visible now:")
  local names = peripheral.getNames()
  if #names == 0 then print("  (nothing)") end
  for _, name in ipairs(names) do
    print(("  %s  (%s)"):format(name, peripheral.getType(name)))
  end
  return
end

print("=== reading ===")

local callsign = ap.getCallsign()
check("getCallsign", type(callsign) == "string" and #callsign > 0, callsign)

local state = ap.getState()
check("getState", type(state) == "string" and #state > 0, state)

local engaged = ap.isEngaged()
check("isEngaged", type(engaged) == "boolean", tostring(engaged))

check("getPosition", type(ap.getPosition().x) == "number", pos(ap.getPosition()))

local aircraftId = ap.getAircraftId()
ok("getAircraftId", aircraftId or "nil (never flown)")

local subLevel = ap.getSubLevelId()
ok("getSubLevelId", subLevel or "nil (not on a craft)")

-- The one that tells us whether a mounted computer sees its own aeroplane.
if subLevel then
  print("       ^ mounted on a craft - this is the aboard case working")
end

local fuel = ap.getFuel()
if fuel == nil then
  ok("getFuel", "nil - this server does not burn fuel for this craft")
else
  check("getFuel", type(fuel.seconds) == "number",
    ("%.0fs%s"):format(fuel.seconds, fuel.dry and " DRY" or ""))
end

local unattended = ap.getUnattendedSeconds()
ok("getUnattendedSeconds", unattended and (unattended .. "s") or "nil (unbounded)")

local saved = ap.getSchedule()
check("getSchedule", type(saved) == "table" and saved.craft ~= nil,
  ("craft %s, alt %d, speed %d, %d stops")
    :format(saved.craft, saved.cruiseAltitude, saved.cruiseSpeed, stopCount(saved)))

check("getCurrentStop", type(ap.getCurrentStop()) == "number", tostring(ap.getCurrentStop()))

local airports = ap.getAirports()
check("getAirports", type(airports) == "table", #airports .. " found")
for _, a in ipairs(airports) do
  local gates, pads = {}, {}
  for g in pairs(a.gates) do gates[#gates + 1] = g end
  for p in pairs(a.helipads) do pads[#pads + 1] = p end
  table.sort(gates); table.sort(pads)
  print(("       %s  gates[%s]  pads[%s]"):format(
    a.name,
    #gates > 0 and table.concat(gates, ",") or "-",
    #pads > 0 and table.concat(pads, ",") or "-"))
end

local vors = ap.getVors()
check("getVors", type(vors) == "table", #vors .. " found")

-- ---------------------------------------------------------------------------

if engaged then
  print("")
  print("=== writing: SKIPPED ===")
  print("This aircraft is flying (" .. state .. ").")
  print("Land it or run ap.disengage() first, then re-run.")
  skipped = skipped + 1
else
  print("")
  print("=== writing (everything is restored afterwards) ===")

  -- Anything past here may have changed the schedule, so the restore has to
  -- happen whether these checks pass, fail or error outright.
  local success, err = pcall(function()
    local original = saved.cruiseSpeed
    local target = original == 40 and 32 or 40
    ap.setCruiseSpeed(target)
    check("setCruiseSpeed", ap.getSchedule().cruiseSpeed == target, target .. " read back")

    ap.setLoop(not saved.loop)
    check("setLoop", ap.getSchedule().loop == (not saved.loop))

    ap.setName("Test " .. os.clock())
    check("setName", ap.getSchedule().name:sub(1, 5) == "Test ", ap.getSchedule().name)

    -- A route built from names read out of the world, which is the whole
    -- point: nothing here is spelled by hand.
    local vertical = saved.craft ~= "plane"
    local useAirport, useStop
    for _, a in ipairs(airports) do
      for name in pairs(vertical and a.helipads or a.gates) do
        useAirport, useStop = a.name, name
        break
      end
      if useAirport then break end
    end

    if not useAirport then
      skip("setSchedule", "no airport has a " .. (vertical and "helipad" or "gate") .. " drawn yet")
    else
      ap.setSchedule { stops = { { airport = useAirport, gate = useStop, wait = "timer",
                                   waitSeconds = 15 } } }
      local after = ap.getSchedule()
      check("setSchedule", stopCount(after) == 1 and after.stops[1].airport == useAirport,
        useAirport .. " / " .. useStop)
      check("setSchedule kept cruise settings", after.cruiseSpeed == target,
        "partial update did not reset the rest")

      local n = ap.addStop { airport = useAirport, gate = useStop }
      check("addStop", n == 2, "route is now " .. n .. " stops")

      local moved = ap.moveStop(2, -1)
      check("moveStop", moved == 1, "stop 2 moved to " .. moved)

      ap.removeStop(2)
      check("removeStop", stopCount(ap.getSchedule()) == 1)

      -- The refusals. Each of these must leave the schedule exactly as it
      -- was, which is checked straight afterwards.
      expectRefusal("bad gate is refused", function()
        ap.setSchedule { stops = { { airport = useAirport, gate = "Gate Nowhere" } } }
      end)
      expectRefusal("bad airport is refused", function()
        ap.setSchedule { stops = { { airport = "Not An Airport" } } }
      end)
      expectRefusal("misspelled field is refused", function()
        ap.setSchedule { cruisespeed = 40 }
      end)
      expectRefusal("out-of-range speed is refused", function()
        ap.setCruiseSpeed(500)
      end)
      expectRefusal("stop 99 is refused", function()
        ap.removeStop(99)
      end)

      local stillThere = ap.getSchedule()
      check("a refused change altered nothing",
        stopCount(stillThere) == 1 and stillThere.cruiseSpeed == target,
        "still 1 stop at speed " .. stillThere.cruiseSpeed)
    end
  end)

  if not success then
    bad("an unexpected error stopped the write checks", tostring(err))
  end

  -- Restore. Done outside the pcall above so it runs either way.
  local restored, restoreErr = pcall(function() ap.setSchedule(saved) end)
  if restored then
    local now = ap.getSchedule()
    check("restored the original schedule",
      now.cruiseSpeed == saved.cruiseSpeed
        and now.name == saved.name
        and stopCount(now) == stopCount(saved),
      ("speed %d, %d stops, name %q"):format(now.cruiseSpeed, stopCount(now), now.name))
  else
    bad("COULD NOT RESTORE the original schedule", tostring(restoreErr))
    print("       Your route may need setting again by hand - sorry.")
  end
end

-- ---------------------------------------------------------------------------

if doEngage then
  print("")
  print("=== engaging ===")
  local started, err = pcall(function() ap.engage() end)
  if started then
    ok("engage", "flight started - state is now " .. ap.getState())
  else
    -- Not necessarily a failure: no fuel, no power, blocked, or the
    -- five-second cooldown shared with the redstone input.
    print("[ -- ] engage refused: " .. tostring(err):gsub("^.-:%d+:%s*", ""))
  end
else
  print("")
  print("(not engaging - run `test engage` to start the flight too)")
end

print("")
print(("%d passed, %d failed, %d skipped"):format(passed, failed, skipped))
if failed > 0 then
  print("Copy the FAIL lines back to me.")
end
