# Free Flight Mode — now playable end-to-end (HUD + smooth render) (2026-06-04)

**Branch**: `feature/true_rcs` (worktree `/workspace/AdvancedRocketry/.claude/worktrees/wt-from-1.12`).
All work this session pushed to `origin/feature/true_rcs`. HEAD `6fb63913`.

**Sibling repo**: `/workspace/ForgeTestFramework` (FTF) — the user owns it
(github.com/StannisMod). `master` @ `c97a7e8`, published to mavenLocal as
**0.4.4**. AR now depends on `forge-test-framework:0.4.4:dev`.

## What this session did
Started from a working-but-unflyable Free Flight Mode (FF) and made it actually
playable in a real client, with tests that exercise the REAL client path.

### Commits on feature/true_rcs (this session, newest first)
- `6fb63913` smooth FF client render via **dead-reckoning** (the final render fix)
- `51d4ced7` FF **HUD** — mode indicator + control legend
- `f653ba4e` FF takeoff **auto-land grace** (30 ticks) + real client e2e via key injection
- `66c93ecb` FF client-responsive: snap position + per-tick input (superseded in part by 6fb63913)
- `dd84991b` FF thrust on **classic TWR** + **classic fuel** burn
- `2f1b3b6c` mixins via **MixinBooter IEarlyMixinLoader** (drop self-bootstrap)
- `642c8664` (earlier) guard AR self-bootstrap of Mixin — superseded by 2f1b3b6c

### The bug chain (root causes, all fixed)
1. **Couldn't take off**: `FreeFlightPhysics` scaled thrust as `thrust/(weight*10000)`
   → climb gate was TWR>4000 (no rocket meets it). Fixed: thrust now derived from
   `stats.getAcceleration(g)+gravity` → climb gate == classic **TWR>1**; respects
   `rocketThrustMultiplier`/`advancedWeightSystem`/`gravityAffectsFuel` (they live
   inside getAcceleration/get*). Arcade cap `MAX_THRUST_ACCEL`. `FreeFlightPhysics.step`
   now takes a precomputed `thrustMag` + `canThrust` (pure, no fuel).
2. **Fuel**: classic semantics now — drain `getFuelConsumptionRate` per thrusting
   tick (+ oxidizer for bipropellant, null-out empty tanks), gated by `rocketRequireFuel`.
   Done in `EntityRocket.tickFreeFlight`, not the pure class.
3. **Auto-land too eager**: the 0.3 startFreeFlight kick decayed in ~15 ticks,
   beating the keypress→packet round-trip → rocket re-landed before thrust arrived.
   Fixed: `FF_LAND_GRACE_TICKS=30` suppresses shouldLand right after takeoff.
4. **Input not delivered**: FF input was sampled only on KeyInputEvent (key
   transitions). Moved to per-tick `KeyBindings.onClientTick` (ClientTickEvent),
   sends on change; dropped the `inGameHasFocus` gate.
5. **Render jitter**: first I snapped client→server pos (replaced 150-block lag
   with freeze-then-jump). Final fix = **dead-reckoning**: client advances by its
   synced velocity every tick and bleeds the small server-pos error over
   `FF_CLIENT_CORRECT_TICKS=3` (== entity updateFrequency=3).
   See `setPositionAndRotationDirect` (records error, no snap), `setVelocity`
   (FF: take server velocity), and the FF branch in `onUpdate` (client dead-reckons).
6. **HUD**: `RocketEventHandler` renders a FF mode indicator + control legend
   (actual bound keys) bottom-left while riding a FF rocket; pre-launch shows
   launch/classic hint. Lines built by `KeyBindings.freeFlightHudLines(...)`.
   Lang keys `msg.ff.hud.*` in en_US.lang + ru_RU.lang. Rendered text published to
   `RocketEventHandler.lastFreeFlightHud` (static) for e2e assertion.

### Diagnostics still in code (harness-only, gated on -Dadvancedrocketry.tests=true)
`[FF-DEBUG]` (land reason: TWR/getAcceleration/canClimb) and `[FF-TRACE/S|C|K]`
(lifecycle: prepareLaunch, startFreeFlight, tick, input applied, gate transitions).
Optional cleanup later — harmless, behind the flag.

## ForgeTestFramework additions (the real reason client tests now mean something)
Lesson learned (see memory): client behaviour must be tested via the REAL path,
not server probes. Added to FTF `ClientBot` + bridge:
- `holdKey/releaseKey/setKey(keyCode,pressed)` — inject real KeyBinding state →
  drives the actual onClientTick → packet path.
- `reportRidingEntity()` — CLIENT-side pos/motion of the ridden entity (catches
  render-sync regressions).
- `readStaticField(className, fieldName)` — reflective client static reader
  (asserts arbitrary client state, e.g. HUD text), no mod dependency.
FTF build: **JDK 21** (Gradle 8.8 can't run on 25), `publishToMavenLocal`, bump AR dep.

## Tests (all green)
- Unit: `FreeFlightPhysicsTest` (47 incl. climb-gate/thrust-cap), `FreeFlightAssistsTest`,
  `FreeFlightHudLangTest`. testUnit+testIntegration ~400/400 earlier.
- Server e2e: `FreeFlightCycleTest`+`FreeFlightAssistsE2ETest` 17/17; full testServer
  449/449 (ran in two bounded halves — suite >50 min).
- **Client e2e** `FreeFlightModeE2ETest` **11/11** on `DISPLAY :100`, incl.:
  `realZKeyThrustClimbsServerAndClientTracks` (real Z key → packet → server climb +
  client tracks), `freeFlightClientRenderAdvancesEveryTickNoStutter` (smoothness,
  probe-driven), HUD in-flight + pre-launch (reads real rendered text).

## How to run tests (always JAVA_HOME + timeout; FTF on JDK 21)
```
export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9              # AR
# FTF publish (only when FTF changed):
( cd /workspace/ForgeTestFramework && JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew publishToMavenLocal --no-daemon )
timeout 600 ./gradlew testUnit testIntegration --no-daemon -Penable_junit_testing=true
timeout --signal=KILL 1200 ./gradlew testServer --no-daemon -Penable_junit_testing=true --tests "*FreeFlight*"
DISPLAY=:100 LIBGL_ALWAYS_SOFTWARE=1 timeout --signal=KILL 1500 \
  ./gradlew testClient --no-daemon -Penable_junit_testing=true -Ptest_harness_forks=1 --tests "*FreeFlightModeE2ETest*"
```
Full testServer exceeds ~50 min → run in two alphabetical halves (it gets KILLed
at the cap otherwise; aggregate the per-class XMLs).

## Conventions to keep (memories)
- **No Co-Authored-By / AI attribution** in commits/PRs (scrubbed this session).
- Respond to user in **Russian**.
- **FTF functional changes go straight to `master`** (no feature branch); AR uses
  feature branches.
- Test client behaviour via real key injection + client-side readback, never probes.
- Bound every MC gradle run with a wall-clock `timeout`.

## Open follow-ups
- **IKeyConflictContext**: FF keys conflict with vanilla (A/D strafe, Q drop,
  E inventory; internal dup X = jetpack & turnRocketDown). Add a custom
  KeyConflictContext active only in FF so they don't conflict outside FF, without
  rebinding. (User's explicit ask, not yet started.)
- Optionally strip `[FF-TRACE]`/`[FF-DEBUG]` logs.
- PR for `feature/true_rcs` not opened.
- `feature/solar-map-ff-rework` (separate branch) has an OLDER FreeFlightPhysics +
  its own SolarMapPhysics (TASK-45) — needs reconciling with the new FF physics
  when that work resumes.
