# SeaPort 0.1.0 — test procedure

**What exists in this build: the surveyor, and two commands to inspect it.**

There are no blocks, no items, no ships, no ports and no autopilot yet. SeaPort
0.1.0 does exactly one thing — work out how deep the water is, everywhere the
world has ever been generated, without hurting the server doing it. That has to
be trustworthy before anything floats on top of it, which is why it ships alone.

So "test all the features" here means testing one thing properly. It should take
about fifteen minutes.

Everything below is run from in-game chat. All commands need operator
permission (`/op <yourname>` from the server console, or play in single-player
with cheats on).

---

## 1. It loads at all

Launch the instance. Before anything else, open the mod list and confirm
**SeaPort 0.1.0** is there.

**Watch for:** the game loading normally. SeaPort depends on nothing but
NeoForge — not Create, not Sable, not Deep Seas — so if it breaks startup,
something is badly wrong rather than subtly wrong.

> ⚠️ **Before you blame SeaPort for any crash in this instance**, note that it
> has **two copies of Distant Horizons 3.3.3** (`-fabric-neoforge` and plain),
> both declaring `modId = "distanthorizons"`. That is a duplicate mod and wants
> removing regardless. Last time a DH problem looked like our bug, it cost an
> evening.

---

## 2. It finds what the world already has

Load **New World** (it has 16 region files — a real explored world, which is
what makes the rest of this worth doing).

Check the log — `logs/latest.log`, search for `[survey]`. At world load you
should see one line per dimension:

```
[survey] minecraft:overworld: N chunks charted (...), M waiting, 0 reading,
         16 regions on disk holding K chunks
```

**What it means:**

| Field | Should be |
| --- | --- |
| `16 regions on disk` | matches the file count in `saves/New World/region` |
| `holding K chunks` | **far less than 16 × 1024 = 16384** |
| `M waiting` | the work queued — roughly K on a first run |

**The thing to actually check is K.** A region file covers 1024 chunk slots and
almost never fills them. If `holding` comes back at or near 16384, the header
reader is broken and the mod is about to do sixteen thousand pointless disk
reads. On the dev world this read 49 out of a possible 4096.

---

## 3. It catches up, and says so

Stay in the world. Every 10 seconds while there is a backlog you get a progress
line, and one line when it finishes:

```
[survey] caught up - minecraft:overworld: K chunks charted (A as loaded, B from disk), 0 waiting
```

**What to check:**

- It reaches `caught up` rather than stalling with a number that stops moving.
- **`B from disk` is greater than zero.** This is the one path never yet proven
  — on the dev world every chunk was loaded by the game before the disk reader
  got to it, so `from disk` was always 0. This world is big enough that it
  shouldn't be. If `from disk` stays at 0 here, tell me: the NBT reader is
  heavily unit-tested but has never met a real region file.
- The server does not stutter while it works. It is capped at 4 reads in
  flight and 8 results filed per tick, but caps are a theory until somebody
  watches the tick rate.

---

## 4. The numbers are actually right

This is the important one, and the only one that catches a *subtly* wrong
survey. The failure mode I am most wary of is a seabed that is plausible
everywhere and correct nowhere.

**Stand in shallow water near a shore** and run:

```
/seaport depth
```

You get the cell's floor height, surface height, sea level, and either `land`
or `water N blocks deep`.

**Now check it by eye.** Press F3 and compare:

- `floor Y` should match the seabed you are floating over — within a block or
  two, because a cell is 4×4 blocks and reports its **shallowest** point.
- `water N blocks deep` should match what you can see.

Repeat in three places that disagree with each other:

1. **Deep ocean** — expect a large depth.
2. **A beach or shoreline** — expect `land` on one side and shallow water a few
   steps away.
3. **On a hill well inland** — expect `land`, with `floor Y` near your own
   altitude.

**What a broken reader looks like:** depths that are all wrong by roughly the
same amount (about 64) means the world-floor offset is being dropped. Depths
that start right and drift as you walk means the bit-stride is wrong. Both read
as "terrain", which is exactly why this step is done by eye rather than by
trusting the number.

---

## 5. It does not redo its work

```
/seaport survey
```

Note `charted`. Then **quit to title properly** (not Alt-F4 — the chart is
saved on world save, and killing the process skips it).

Re-enter the world and run `/seaport survey` again.

**Expected:**

- `charted` is the same number as before.
- `waiting` is **0** — enumeration found those chunks on disk and skipped every
  one as already known.
- The `caught up` line reports `0 as loaded, 0 from disk` for that session.

That combination is the proof the chart persisted and the startup cost is a
one-off rather than something paid at every boot. You can also check the file
exists: `saves/New World/data/portcore_survey.dat`.

---

## 6. It keeps up with somewhere new

Sail or fly a few hundred blocks into **unexplored** water, then:

```
/seaport depth
```

Freshly generated chunks are surveyed from memory as they load, so this should
report real numbers almost immediately rather than `Not surveyed yet`.

Then `/seaport survey` and check `surveyed N as they loaded` has gone up.

---

## 7. It handles a world that changes

Dig a hole in the seabed, or build a causeway across shallow water.

Currently **the chart will not notice.** Invalidation on block change is written
but not yet wired to a block-change event — `/seaport depth` will keep reporting
the old seabed for that cell.

This is a known gap rather than a bug to report, and it is listed here so you
don't spend time chasing it. It matters once ships are routing; it does not
matter yet.

---

## What to send back

If something is wrong, the useful things are:

- The `[survey]` lines from `logs/latest.log`.
- The output of `/seaport survey` and `/seaport depth`.
- For step 4, what the command said **and** what F3 said, in the same place.

## Known gaps in 0.1.0

- `from disk` surveying has never been observed working on a real world — see
  step 3.
- Block changes do not invalidate the chart — see step 7.
- Surveying runs in every dimension. That is right for ships, which could be
  sent anywhere, but it means the Nether gets charted too, for nothing.
- Nothing uses the chart yet. Routing exists and is unit-tested; it has no ship
  to steer.
