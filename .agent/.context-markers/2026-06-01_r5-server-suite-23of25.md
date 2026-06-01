# R5 progress marker — server suite 23/25 green (2026-06-01)

**Branch**: `feature/upstream`. Continues the R5 (harness-under-RFG) work from
`before-compact-2026-05-31-upstream-merge.md`.

## Done this session (all committed)

- **Phase A** — RFG server/client harness wiring (testServer/testClient). No FG6
  reflection: RunMinecraftTask extends JavaExec; forge-test-framework already
  defaults to RFG GradleStartServer. Proven by HarnessDiagnosticTest.
  - Critical wiring lessons baked into build.gradle comments: take the run task's
    classpath EAGERLY (a `runTask.map{}` provider makes Gradle EXECUTE runServer →
    foreground MC server hangs the build); exclude sourceSets.main.output (else
    FML DuplicateModsFoundException: mod present as both classes dir + jar).
- **Phase B** — ported TestProbeCommand (12.9k lines) + registration to the PR
  API; compiles + runs.
- **.agent import** (#8) — feature/tests Navigator history/rules restored.
- **Phase C** — 132 server tests imported (compile-green). Full `testServer`
  harvest: **431 tests, was 25 failing → now 3 failing** (428 green).
- **Phase D** (command surface) folded into C: WorldCommandGuard/StarMisc/
  CommandsSmoke reconciled to ARCommandRoot; reapplied reload bug #7 production
  fix (createAutoGennedRecipes hit Forge's frozen registry).

Fixed + verified groups: 6 mission completion (probe: backdate vs dim-0 universal
time + prime completionCheckTimer), wireless probe port, 10 command-surface,
forcefield-tick (advance world clock past the %5 gate), zero-fuel gate, wireless
default-enabled, UvAssembler (intake + liquidTank in fixture), SatelliteChip
(server-side useNetworkData id=101 instead of client onInventoryButtonPressed).

## REMAINING — 3 server tests still failing (need diagnostic runs)

Run with `export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9` and ALWAYS a timeout, e.g.
`timeout --signal=KILL 300 ./gradlew testServer --tests "*X" --no-daemon`.

1. **PrecisionAssemblerRecipeEndToEndTest.precisionAssemblerRunsFirstRegisteredRecipe**
   — `{"error":"slot out of range","slot":4,"size":4}`. MachineRecipeEndToEndKit
   .fillItemIngredients fills ALL recipe input slots into `firstInput()` (one input
   hatch), but the new machine's input hatch has 4 slots while the recipe declares
   an ingredient at slot 4. Likely the recipe's input slots now span MULTIPLE input
   hatches (slot 4 = slot 0 of the 2nd hatch). Next step: run `artest machine
   recipes-summary` + inspect the precision-assembler fixture's input-hatch count
   vs the first recipe's slot indices; teach the kit to map slot→hatch.

2. **MachineRecipeIntegrationTest.cuttingMachineRunsFirstRegisteredRecipe**
   — output hatch `{"size":4,"slots":[]}`: ingredient fill likely succeeds but the
   recipe never completes (no output). Different root cause from #1 — check whether
   the cutting machine now needs power/a different tick cadence, or the recipe-match
   condition changed. Diagnose with `artest machine info` + force-tick counts.

3. **FuelingStationFuelsAdjacentRocketTest.stationDrainsTankAndRocketFuelRisesAfterLinkAndTick**
   — after `infra link` + fluid inject 8000 + energy 100k + `tile force-tick 200`,
   the station tank stays at 5000 (no transfer to rocket). Check the new
   TileFuelingStation link/transfer path: does `artest infra link` establish the
   station→rocket link the new code reads, and does performFunction transfer on
   force-tick? Likely a linking-model or transfer-condition change.

## Also still pending — Phase E (26 client tests)

Not yet imported/wired. Plan to integrate:
1. Bring `src/test/.../test/client/**` (26 files) from `feature/tests`:
   `for f in $(git ls-tree -r --name-only feature/tests -- src/test/java/zmaster587/advancedRocketry/test/client/); do git show "feature/tests:$f" > "$f"; done`
   (mkdir the dir first). Includes 3 WorldCommandFetch*/PlayerEquipped client tests.
2. `compileTestJava` → reconcile API drift like the server layer did (most drive
   the client via the framework `bot()`/probe surface, so expect few direct-API hits).
3. **testClient task is already wired** in build.gradle (configureHarnessTest with
   enableClient=true): sets forge.test.client.enabled, nativesDir=build/natives,
   depends on extractNatives, and forwards DISPLAY/XAUTHORITY/LIBGL_ALWAYS_SOFTWARE
   from the env into `forge.test.client.env.*`. The framework's RealClientHarness
   launches GradleStart with LWJGL natives.

**RUN CLIENT TESTS ON DISPLAY :100** (NOT :99 — that Xvfb had no OpenGL). The
testClient task forwards the parent env's DISPLAY to the client JVM, so launch as:
```
export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9
DISPLAY=:100 timeout --signal=KILL 1200 ./gradlew testClient -Ptest_harness_forks=1 --no-daemon > logs/client.log 2>&1
```
(ensure something is serving :100 with GL before running; build/natives is
populated by `./gradlew extractNatives`). Auto-skips if it still detects headless.

## Bug ledger note

- reload bug #7 (frozen recipe registry) — FIXED in production this session
  (ReloadRecipesCommand). Update `.agent/tasks/README.md` ledger counter.
- Possible production smell: TileSatelliteTerminal.onInventoryButtonPressed sends
  TOSERVER unconditionally (no isRemote guard) — fine in real client use, but worth
  noting. Not changed (probe drives the server half directly instead).
