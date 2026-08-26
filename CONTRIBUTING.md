# Working on Skyport

Needs JDK 21. The Gradle wrapper fetches Gradle, and ModDevGradle fetches and
decompiles Minecraft on the first build — expect several GB and a long wait
once, then fast builds.

```
./gradlew build        # jar into build/libs
./gradlew test         # unit tests
./gradlew runClient    # dev client
./gradlew runServer    # dedicated server, in its own run-server/
```

## Traps worth knowing

**`compileOnly` dependencies are not on the dev-run classpath.** Create,
Create Aeronautics and Sable are `compileOnly`, which is correct for an addon
that must not bundle them — but it means `runClient` would have none of them
installed and mod loading would fail. `build.gradle` carries a parallel set of
`runtimeOnly` lines purely so the dev environment behaves like a real install.
They do not affect the shipped jar.

**Create Aeronautics' modId is `aeronautics`**, not its Modrinth slug.

**Create Aeronautics and Sable ship as bundle jars**, with their real code
nested under `META-INF/jarjar/`. Depending on the published artifact alone
puts none of their classes on the compile classpath, which is why
`build.gradle` has an `extractBundledMods` task.

**Run `runServer` after touching networking or screens.** A dedicated server
refuses to load client-only classes and the integrated one does not, so a
whole category of crash is invisible until you start one. This caught a
startup crash that the client could never have found.

**`BlockEntity#setRemoved` fires when a chunk unloads**, not only when a block
is broken. Anything that deletes persistent state there will erase it the
moment a player walks away. Use the block's `onRemove` instead — both
`AutopilotBlock` and `AirportStationBlock` do, and both have comments saying
why.

## Where the tricky parts live

`AutopilotBlockEntity` is the flight state machine and the steering. It is by
far the largest class here, and the one place where a change is most likely to
have consequences somewhere else — it steers a real rigid body through Sable's
`sable$physicsTick`, by velocity correction rather than by setting a transform,
because overwriting the transform each tick fights the physics engine.

`logic/GroundNetwork` and `logic/FuelBurn` are pure logic with no game state,
which is why they carry most of the test coverage. If you are adding something
that could be expressed without a running game, putting it here means it can
be tested.

`AirportRegistry` holds both persisted data (airports, the aircraft roster) and
transient runtime state (clearances, live traffic). The distinction matters:
a clearance surviving a restart would lock an airport forever with no aircraft
left to release it, and every transient field says so at its declaration.

## Tests

`./gradlew test` covers the ground network, serialization round-trips
(including backward compatibility with older save formats), clearances, and
the fuel curve. The flight state machine is not covered — it needs a live
physics body — so "tests pass" says less than it sounds like.

Serialization tests matter most. A layout that fails to round-trip comes back
wrong after a restart with nothing thrown and nothing logged, and the player
concludes the editor never saved.
