# Free Flight Mode — feature complete (2026-06-02)

**Branch**: `feature/true_rcs` (worktree at
`/workspace/AdvancedRocketry/.claude/worktrees/wt-from-1.12`).
Based on `origin/1.12` HEAD `4146bb1a` (after the feature/upstream merge PR #20
that brought RFG build + full test harness onto 1.12).

**All work pushed to `origin/feature/true_rcs`.** PR not yet opened.
Create via: https://github.com/StannisMod/AdvancedRocketry/pull/new/feature/true_rcs

## What was built

A complete "Free Flight Mode" for `EntityRocket` — a player-piloted arcade
vehicle mode alongside the untouched classic vertical-launch path. Plus
Elite-Dangerous-style flight assists. RCS toggle deprecated (Option B).

### Commits this session (all pushed)

1. `86154241` feat: add Free Flight Mode for EntityRocket
2. `aa41bbdf` feat: wire pitch into Free Flight forward vector
3. `f1200dae` deprecate: RCS toggle (Option B) — FF supersedes it
4. `bac298fd` feat: add Elite-style flight assists — Stop, FA toggle, Hover Hold

## Architecture (where things live)

**New pure-Java API (no MC types — unit-testable without booting MC):**
- `api/RocketFlightMode.java` — enum `CLASSIC_LAUNCH` / `FREE_FLIGHT`,
  default CLASSIC, NBT read/write tolerant of missing/unknown values.
- `api/FreeFlightInput.java` — 5 float channels [-1,1] (fwd/vert/yaw/pitch/brake)
  + 2 bool flags (stopActive/hoverActive) packed into a flag byte.
  Wire size **21 bytes**. Clamps on construct + read (NaN/Inf→0). Server is
  source of truth; client values are intent only.
- `api/FreeFlightPhysics.java` — `step(...)` pure arcade physics. Two overloads:
  10-arg (FA defaults ON, back-compat) and 11-arg (explicit `flightAssistOn`).
  Forward thrust projected through yaw AND pitch. Stop = counter-thrust along
  -motion (capped, snap-to-zero <0.01, no reverse overshoot). Hover = cancel
  gravity tick when fuel>0. FA-off skips idle-drag (Newtonian coast) but keeps
  brake/gravity/speed-cap. `shouldLand(onGround, motionY)` landing detector.

**EntityRocket.java integration (additive, classic path untouched):**
- Fields: `flightMode`, `currentFreeFlightInput`, `freeFlightPitch`,
  `freeFlightLandedLatched`, `flightAssistOn` (default true).
- NBT round-trip for all (legacy saves → CLASSIC + FA-on).
- `onUpdate()` early branch: `if (isFreeFlight() && isInFlight()) { tickFreeFlight(); return; }`
- `tickFreeFlight()` delegates to FreeFlightPhysics, applies motion via move(),
  drains fuel, detects landing (fires RocketLandedEvent once via latch).
- `startFreeFlight()` — bypasses classic countdown, gives +0.3 motionY kick so
  landing-detector doesn't instantly re-land on the launchpad, sets onGround=false.
- `prepareLaunch()` short-circuits to startFreeFlight when in FF mode.
- PacketType enum APPENDED (ordinal-stable wire): `SET_FLIGHT_MODE`,
  `FREE_FLIGHT_INPUT`, `SET_FLIGHT_ASSIST`. Server-side auth gates: passenger
  membership + mode + (for SET_FLIGHT_MODE) not-in-flight.

**KeyBindings.java (client):**
- M = toggle mode (only pre-launch), W/S = fwd, A/D = yaw, Z/X = vertical,
  Q/E = pitch (Q nose-up), Shift = brake, B = Stop, N = FA toggle, H = Hover.
- Sends FREE_FLIGHT_INPUT only when intent changes (delta-suppression).

**TestProbeCommand.java verbs (for e2e):**
- `set-flight-mode <id> MODE`, `start-free-flight <id> [fuelFill]`,
  `free-flight-input <id> fwd vert yaw pitch brake [stop] [hover]`,
  `free-flight-tick <id> [n]`, `set-flight-assist <id> on|off`.
- `rocket info` extended: flightMode, motionX/Y/Z, ffInput* (incl Stop/Hover),
  freeFlightPitch, flightAssistOn.

**RCS deprecation (Option B):**
- `toggleRCS()` no longer mutates RCS_MODE — shows `msg.entity.rocket.rcsDeprecated`
  redirecting to FF (M-key). RCS_MODE datawatcher + solar-map flight branch
  LEFT INTACT for save-compat + ongoing solar navigation use.

## Tests (all green)

- Unit (`test/unit/`): `RocketFlightModeNbtTest`, `FreeFlightInputTest`,
  `FreeFlightPhysicsTest`, `FreeFlightAssistsTest` — 59 methods.
- Server e2e (`test/server/`): `FreeFlightCycleTest` (10),
  `FreeFlightAssistsE2ETest` (7), `RcsDeprecationTest` (2).
- Client e2e (`test/client/`): `FreeFlightModeE2ETest` (3).
- Full regression sweeps passed: testUnit+testIntegration 379/379,
  full testServer 308/308, client FF 3/3.

## How to run tests (always JAVA_HOME + timeout)

```
export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9
# unit:
timeout 200 ./gradlew testUnit --no-daemon -Penable_junit_testing=true --tests "*FreeFlight*"
# server e2e:
timeout --signal=KILL 600 ./gradlew testServer --no-daemon -Penable_junit_testing=true --tests "*FreeFlight*"
# client e2e — DISPLAY :100 ONLY:
DISPLAY=:100 LIBGL_ALWAYS_SOFTWARE=1 timeout --signal=KILL 1500 \
  ./gradlew testClient --no-daemon -Penable_junit_testing=true -Ptest_harness_forks=1 --tests "*FreeFlightModeE2ETest*"
```

Gradle is now allowlisted in `.claude/settings.local.json` (no per-run prompts).

## Open follow-ups

- **TASK-45** (`.agent/tasks/TASK-45-solar-map-flight-redesign.md`) — DESIGN
  REQUIRED. Migrate solar-map deep-space navigation (`getInSpaceFlight()` branch,
  still uses legacy RCS turning-booleans) onto FF architecture, then remove
  RCS_MODE datawatcher entirely. 6 design questions + 5 gotchas documented.
- PR for `feature/true_rcs` not yet opened.

## Tuning constants (FreeFlightPhysics) — if balance feedback comes in

MAX_FORWARD_ACCEL 0.08, MAX_VERTICAL_ACCEL 0.10, MAX_YAW_RATE 6°, MAX_PITCH_RATE
4°, MAX_SPEED 3.0, BRAKE_RETENTION 0.85, IDLE_DRAG 0.99, PITCH_MAX 85°,
FUEL_PER_TICK_AT_FULL_THRUST 4. thrustScalar = thrust/(mass*10000), clamped
[1e-6, 2.0]. Gravity per tick = 0.04 * gravMult.

Note: the simple test fixture has tiny thrust (=100) vs mass 7.1, so per-tick
accel is ~1e-4 — real rockets with real engines feel very different. Don't
balance off the fixture.
