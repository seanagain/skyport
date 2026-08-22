# Credits

Skyport itself is LGPL-3.0-or-later — see `LICENSE`, `COPYING` and `NOTICE`.

Nothing third-party is bundled into the jar. Everything below is a dependency
loaded alongside it at runtime, listed because an LGPL release should be clear
about what it links against.

## Required at runtime

- **[Create](https://github.com/Creators-of-Create/Create)** — MIT. Skyport
  uses its display-source API for departure boards, its Ponder system for the
  in-game guides, and its materials in the recipes.
- **[Create Aeronautics](https://github.com/Eriksonn-Continued/CreateAeronautics)**
  — the aircraft this mod flies. Ships its real code as a nested jar-in-jar,
  which is why `build.gradle` has an `extractBundledMods` task.
- **[Sable](https://github.com/ryanhcode/sable)** — the rigid-body physics
  under those aircraft. Skyport steers through
  `BlockEntitySubLevelActor#sable$physicsTick` and applies velocity, never
  setting a transform directly.
- **[NeoForge](https://github.com/neoforged/NeoForge)** — LGPL-2.1. The mod
  loader.
- **[Ponder](https://github.com/Creators-of-Create/Ponder)** — MIT. The scene
  system behind the three in-game guides.

## Development only

- **[WorldEdit](https://github.com/EngineHub/WorldEdit)** — LGPL-3.0. Used in
  the dev environment to build test airports quickly. Not a dependency of the
  released mod.

## Note on LGPL and dependencies

Depending on Skyport does not make your mod LGPL. The copyleft applies to
Skyport's own source: modify these files and redistribute that, and those
changes have to be published under the LGPL too. Using it, shipping it in a
modpack, or writing an addon against it are all unrestricted.
