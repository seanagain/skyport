# Skyport - Design Notes

Visual and UX decisions for the addon, so the "why" behind the assets in
`src/main/resources` and the GUI mockup isn't just implicit in the files.

## Visual identity

Both blocks use Create's own material language - andesite gray body,
brass border - so they read as if Create Aeronautics could have shipped
them itself, rather than looking like a bolted-on third-party block.
Each has a small circular "instrument" element in the middle that's the
only thing distinguishing the two at a glance:

- **Airport Station** - a dark green radar screen with a pale
  yellow-green contact blip and a faint sweep line. Reads as "ground
  control / surveillance".
- **Autopilot** - a navy compass/attitude dial with a cyan needle cross.
  Reads as "navigation / in the cockpit".

Textures are hand-placed 16x16 pixel art (`textures/block/*.png`),
generated procedurally but pixel-exact, no anti-aliasing - matches how
vanilla and Create textures are actually authored. Both block item icons
reuse the block model rather than needing separate item textures.

## Map color coding

Chosen to match real-world aeronautical chart convention where it doesn't
fight Minecraft's terrain colors, since the person building this already
knows what a runway diagram looks like and there's no reason to invent a
new visual language:

| Element | Color | Line style |
|---|---|---|
| Runway | white/cream fill, dark outline | solid strip + dashed yellow centerline |
| Taxiway | brass/yellow | solid line |
| Holding pattern | blue | dashed racetrack loop |
| Gate | orange | filled square, letter label |

## GUI layout

See `skyport-gui-mockup.html` (sent alongside this project) for the full
visual mockup of both screens. Summary of the decisions it encodes:

- Both screens use a vanilla-style beveled gray panel (light gray fill,
  dark border, inset highlight/shadow) rather than inventing a new chrome
  style - keeps it feeling native rather than like a web app dropped into
  the game.
- **Airport Map Editor**: a mode toolbar (Runway / Taxiway / Holding
  Pattern / Gate) above the map, one active mode at a time, clicks append
  points to whichever list is active. Undo Point removes the last click
  without discarding the whole layout. Legend always visible so the
  color coding doesn't need to be memorized.
- **Autopilot Console**: deliberately minimal - two dropdowns (airport,
  then that airport's gates), a status line reflecting the block entity's
  current `FlightState`, and a single Engage/Disengage action. No reason
  to expose more than that to the player; the interesting complexity is
  all server-side.

## Resolved since

- **Terrain background.** The editor and the tower both sample real
  `MapColor` values from loaded chunks, with relief shading from the height
  step to the north, dimmed so it reads as backdrop rather than competing
  with the drawn lines. Unloaded ground stays a flat blue-grey on purpose:
  "not surveyed" and "flat terrain" must not look alike.
- **Holding-pattern direction** is player-chosen, via the CW/CCW button. It
  turned out to matter — which way a racetrack turns decides whether the
  pattern sits over a mountain or a valley.
- **A second runway** needed a colour of its own. Two white lines say nothing
  about which one arrivals use, and getting that backwards means drawing the
  final leg to the wrong strip.

## Open questions

- Gate icons could carry a small glyph per cargo type rather than a bare
  letter, if that ends up mattering at a busy airport.
- One-way arrowheads are drawn dark, as markings *on* the taxiway rather than
  as a second line beside it. That reads well at close zoom and is nearly
  invisible when zoomed out; a scale-aware size might be better.
