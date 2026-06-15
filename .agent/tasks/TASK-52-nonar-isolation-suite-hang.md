# TASK-52: `NonARDimensionIsolationTest.netherAndEndAreNotARPlanets` hangs at suite scale

## Ticket

- Source: full-`testServer` run on `feature/postponed` @ `1bb16f58` during the
  PR #23 merge-readiness gate (2026-06-15).
- Status: 🟡 **Backlog — not started.** Mitigated with `@Ignore` so the tier
  completes; root cause (harness deadlock) deferred.
- Created: 2026-06-15.

## Symptom

The **full** `testServer` tier deterministically HANGS (never completes; killed
by the wall-clock bound) at
`NonARDimensionIsolationTest.netherAndEndAreNotARPlanets`. Localised with a
per-class `beforeTest/afterTest` init-script log:

- 44 test classes complete green, then `netherAndEndAreNotARPlanets` emits
  `ARTEST_START` and never `ARTEST_END`.
- The sibling method `overworldAndVanillaDimsAreNotWrapped` runs first and
  PASSES; the hang is method #2.
- **In isolation the class passes 2/2** (`--tests "*NonARDimensionIsolationTest"`).
- Reproducible: three independent full runs froze at the identical point
  (`build/test-results/testServer/binary/output.bin` stuck at 12288 bytes each
  time). No orphaned MC server processes after the kill.

## Why it is NOT a correctness regression / not from PR #23

- The class passes in isolation; the contract it pins (Nether/End not AR
  planets, vanilla dims not wrapped) is satisfied by the production code.
- All 44 prior classes pass; the hang is purely the 44th-in-sequence context.
- The perDimWorldInfo work (`435ff7db`) and its tests run *after* NonAR in the
  order and never execute in the hung run — they cannot be the cause.
- `dim info` does not change behaviour under the perDimWorldInfo master flag.

## Likely root cause (hypothesis — needs confirmation)

`netherAndEndAreNotARPlanets` calls `artest dim info -1` and `dim info 1`, which
force-load the **Nether and End** on the long-lived shared
`AbstractHeadlessServerTest` server. After ~43 prior classes have churned dim
load/unload + chunkgen on that one server (−Xmx1g), the Nether/End load (or the
`isARPlanet` classification path it drives) deadlocks or stalls. Candidates to
investigate:

- shared-server state degradation (leaked `keepDimensionLoaded` refcounts,
  chunk-gen worker stuck, GC thrash at the 1g heap cap);
- End-specific init (dragon-fight / End-spike gen) hanging headless;
- an interaction between `dim info`'s `initDimension` and a dim another test
  left in a half-loaded state.

Note: the PR #23 body validated `testServer` via **targeted classes**, not the
full tier in one shot — so this full-suite hang likely pre-dates and is
orthogonal to PR #23.

## Mitigation (shipped)

`@Ignore` on `netherAndEndAreNotARPlanets` with a reason pointing here, matching
the project precedent (`InventoryBypassRedirectE2ETest`, TASK-42/43). The
wrapper-isolation half of the contract (Nether/End / overworld NOT
`ARDimensionWorldInfo`) stays pinned green by `overworldAndVanillaDimsAreNotWrapped`;
only the `isARPlanet:false` classification assertion is parked.

## Plan when promoted

1. Reproduce cheaply: find the minimal prior-class set that triggers it (bisect
   the ~43 predecessors), or run NonAR last after a scripted dim-churn warm-up.
2. Thread-dump the shared server at the hang (`jstack` the test JVM /
   harness subprocess) to see whether it's chunkgen, the main server thread, or
   the probe call.
3. Fix the harness (per-class server reset, or `dim info` not force-loading
   End), or split `dim info` so the classification check doesn't load the dim.
4. Un-`@Ignore`; confirm the full tier completes green.

## Related

- Flake lineage: TASK-16 (flake watch), TASK-27/28 (port-bind + tick races),
  TASK-43 (parallel-fork). Same family: suite-scale harness instability, not a
  production contract break.
