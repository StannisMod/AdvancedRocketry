# Contributing

## Building

```bash
./gradlew build
```

Gradle itself runs on **JDK 25**, while the mod is compiled against **Java 8**. Point `JAVA_HOME` at a JDK 25
installation before invoking Gradle, or the build will not configure.

LibVulpes is consumed as a local jar from `libs/`. It is the fork at
[StannisMod/libVulpes-fork2](https://github.com/StannisMod/libVulpes-fork2), not the upstream release.

## Running a dev environment

```bash
./gradlew runClient    # or runServer
```

Valkyrien Skies is added to the dev runtime automatically for these two tasks, so tier-2 ship work can be
tested by hand without extra flags.

## Tests

| Task | Cost | What it covers |
| --- | --- | --- |
| `testUnit`, `testIntegration` | fast, no game | pure logic and wire formats |
| `testServer` | boots a dedicated server | server-side behaviour end to end |
| `testClient` | boots a real GL client | anything the client renders or drives |

Tests that need Valkyrien Skies opt in with `-PwithVS`; the default build and test run stay VS-free on
purpose, so the VS-absent fallback paths keep getting exercised.

`testClient` works directly on a Windows development machine (native OpenGL). On a headless Linux box it
needs a real X server — see the notes in the repository's development documentation.

Always cap harness runs with a wall-clock timeout and log to a file rather than piping through `tail`:

```bash
timeout --signal=KILL 360 ./gradlew testServer --no-daemon > logs/testServer.log 2>&1
```

## Branches

| Branch | Contents |
| --- | --- |
| `1.12` | Mainline — the 2.x release line |
| `feature/*` | 3.0.0 development: tier-2 ships, the space subsystem, the universe registry |

## Pull requests

Keep changes focused, and match the surrounding code style rather than introducing a new one. The mod targets
Java 8, so no `var`, records, or switch expressions. Registry names, NBT keys and packet formats are contracts
— changing one inside a release line breaks saves and servers.
