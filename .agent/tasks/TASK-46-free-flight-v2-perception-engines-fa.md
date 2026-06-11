# TASK-46: Free Flight v2 — camera-nose lock, engine start ritual, velocity-setpoint Flight Assist

## Ticket
- Ticket: (internal — manual playtest feedback, 2026-06-10)
- Status: **In Progress** (approved by user 2026-06-10)
- Branch: `feature/true_rcs`

**Hard requirement from approval**: every phase's user-visible behaviour must
be pinned by a *client* e2e (real input injection + client-side readback)
**before** the phase is reported as done. No claim of "works" without the
corresponding perception-contract test green. This is the second time a green
pyramid diverged from manual playtest — it must not happen a third time.

## Context

Manual playtest (2026-06-10) found Free Flight v1 unplayable despite a fully
green test pyramid. Root causes, confirmed in code:

1. **Look is not bound to the nose.** Only yaw is camera-locked
   (`KeyBindings.onClientTick` pins `player.rotationYaw`); pitch is free and
   the nose *chases* the look pitch through a rate controller — so look and
   nose disagree most of the time.
2. **Body-frame thrust with invisible attitude.** Vertical thrust (R/F) acts
   along the craft's up axis. Because the nose chases look pitch, looking
   down at the ground pitches the nose down ~85°, and the craft "up" axis
   rotates into the horizontal — R/F then move the craft forward/back in
   world terms. Math is consistent; perception is broken because nothing
   on screen shows the craft attitude.
3. **Flight Assist is vacuous.** `flightAssistOn` defaults to `true` but its
   only effect is a 1%-per-tick horizontal drag when *all* input is idle.
   There is no velocity-setpoint control at all.
4. **No thrust/velocity indication.** The HUD shows a key legend only —
   no per-axis thrust or velocity, no FA state visibility worth the name.
5. **Takeoff is an instant kick** (0.3 motionY + 30-tick land grace), not an
   engine-start ritual; it reads as "the rocket hiccupped".

**Test-method lesson** (why the green pyramid missed this): unit/e2e pinned
the contract "key K accelerates along axis A", which held. The *choice* of
axis frame and the camera↔nose coupling — the perception contracts — were
never pinned. This task defines those contracts explicitly and tests them.

## Approved design decisions (user, 2026-06-10)

| # | Decision |
|---|----------|
| D1 | **Camera hard-locked to craft axes (yaw + pitch). Mouse = rotation-rate command** (Elite-style), with a turn-rate indicator on the HUD. "Where I look is where the nose points", always. |
| D2 | **All thrust channels in the craft body frame** (forward / right / up of the nose). Readable now because of D1. |
| D3 | **Engine start**: hold Space 3 s → "Engines started" on HUD → craft rises to ~1 block above ground and hovers. **Auto-shutdown on touchdown**; to fly again, hold Space 3 s again. |
| D4 | **New FA (velocity setpoint) is ON by default. X = zero the setpoint** (brake-to-hover). **N toggles raw-Newtonian mode. H (hover hold) and B (stop) keys are removed** — subsumed by X + FA. |

## Design

### 1. Orientation & camera (client) — D1

- While FF is active and in flight, the camera is pinned to the craft every
  client tick: `player.rotationYaw/Pitch (+prev) = rocket yaw / ffPitch`.
- **Mouse-as-rate**: each tick, the player's accumulated look delta since the
  pin (i.e. mouse movement this tick) is read, clamped to
  `MAX_YAW_RATE`/`MAX_PITCH_RATE`, normalised to [-1, 1] and sent as the
  existing `yawInput`/`pitchInput` channels; then the camera is re-pinned.
  Moving the mouse turns the ship (rate-capped); stopping the mouse stops
  rotation. No wire change for rotation.
- A/D remain an additive yaw input (keyboard fallback). Roll is out of scope.
- Server stays authoritative: it integrates the rate channels exactly as
  today (`MAX_YAW_RATE = 6°/tick`, `MAX_PITCH_RATE = 4°/tick`, pitch clamp ±85°).
- HUD shows a small turn-rate indicator (dot deflected from a center mark,
  proportional to the commanded rate).
- The v1 "nose chases look pitch" controller in `KeyBindings.onClientTick`
  is deleted.

**Perception contract (pinned by client e2e):** on every rendered tick in FF
flight, `player.rotationYaw == rocket.rotationYaw` and
`player.rotationPitch == rocket ffPitch`.

### 2. Engine start / shutdown (D3)

New FF-only engine state on `EntityRocket`: `OFF → STARTING → ON`.

- **OFF** (landed; default): steering inactive. Holding Space accumulates a
  client-side counter; the HUD shows a progress bar ("Starting engines…
  NN%"). Releasing early cancels and resets. At 60 held ticks (3 s) the
  client sends `ENGINE_START`; the server validates (FF mode, fuel present
  per `rocketRequireFuel`) and transitions to ON.
- **ON**: replaces today's `startFreeFlight` kick. The craft lifts off
  gently: a fixed small climb setpoint until it is ~1 block above the
  launch ground level, then FA zero-setpoint hover (D4 makes hover the
  natural rest state — gravity is compensated by FA). HUD shows
  **"Engines started"** (`msg.ff.engines.started`, en+ru) for ~3 s.
- **Touchdown** (pilot descends until ground contact, `shouldLand`):
  engines auto-OFF, HUD "Engines stopped". `FF_LAND_GRACE_TICKS` becomes
  obsolete (hover holds altitude; there is no decaying kick) — removed.
- Space while ON: unused/reserved. Mode toggle M and the classic-launch
  path are untouched (classic launch keeps its instant Space behaviour).

### 3. Velocity-setpoint Flight Assist (D4)

Authoritative server-side model per rocket (new pure logic in
`FreeFlightPhysics`, unit-testable):

- **State**: `faSetpoint` — a body-frame velocity vector
  (forward, right, up), blocks/tick, magnitude clamped to `MAX_SPEED` (3.0).
  Persisted to NBT; replicated to the client for the HUD.
- **Input semantics (FA on)**: W/S, Q/E, R/F **ramp** the corresponding
  setpoint component while held (`SETPOINT_RAMP` ≈ 0.05 blocks/tick per
  tick → ~3 s from 0 to max on one axis). Releasing a key leaves the
  setpoint where it is. **X instantly zeroes the whole setpoint.** Keys keep
  sending ±1 channels over the existing wire; ramp integration happens
  server-side (server authoritative; clients can't teleport the setpoint).
- **Control loop (every tick, FA on)**:
  `desiredWorld = bodyToWorld(faSetpoint, yaw, pitch)`;
  `error = desiredWorld − motion`;
  commanded accel = `error/tick + (0, +gravity, 0)` (gravity compensation),
  clamped in magnitude to the thrust budget
  (`thrustMag` from classic stats, capped `MAX_THRUST_ACCEL`).
  Under-powered craft (TWR < 1) honestly sag — the classic climb gate falls
  out naturally.
- **Rotation independence**: the setpoint is body-frame, so yawing/pitching
  rotates the *actual* world velocity with the craft (user-specified
  contract; pinned by server e2e).
- **Fuel**: any tick with non-zero commanded thrust drains classic fuel
  (`getFuelConsumptionRate`, oxidizer for bipropellant, `rocketRequireFuel`
  gating) — same binary-per-tick semantics as v1. Out of fuel → FA loses
  authority; craft becomes a Newtonian brick under gravity.
- **FA off (N)**: raw Newtonian — keys are direct body-frame thrust while
  held (v1 regular path), no setpoint, no gravity compensation, no idle
  drag (the vestigial `IDLE_DRAG` branch is removed). Shift-brake stays in
  this mode only, as a manual retro-thrust helper.
- **FA re-enable**: setpoint initialises to the *current* velocity projected
  into the body frame (no jerk on toggle — Elite behaviour).
- **Removed**: `stopActive`/`hoverActive` flags and branches
  (`STOP_SNAP`, `HOVER_RETENTION`), keys H and B, their lang and
  conflict-context entries. The FF wire/NBT formats are unreleased
  (branch-only), so the flag-byte repurposing is save/wire-safe.

### 4. HUD indication

Rendered in `RocketEventHandler` (existing FF HUD hook), published to
`lastFreeFlightHud` for e2e:

- Engine state line: `ENGINES OFF / STARTING NN% / ON`.
- FA state line: `FA: ON (N)` / `FA: OFF — Newtonian`.
- **Per-axis bars (body frame)** — FWD / LAT / VRT: each bar shows the
  setpoint marker vs the actual velocity fill on a ±MAX_SPEED scale
  (FA off: actual velocity only). Text fallback values next to bars.
- Turn-rate indicator (D1) + total speed readout `|v|`.
- Compact key legend retained (current lines minus H/B).

### 5. Tests — contracts to pin

*Unit (`FreeFlightPhysicsTest` + new `FlightAssistTest`)*
- Ramp/hold/zero setpoint semantics; clamps; NaN hygiene.
- bodyToWorld basis orthonormality; world-velocity rotates with yaw/pitch.
- Gravity-compensation budget; TWR<1 sag; out-of-fuel inert.
- FA-off path identical to v1 regular thrust; FA re-enable captures
  current velocity.

*Server e2e*
- Engine start: validated transition, hover ≈ launchY+1 with |v|→0;
  touchdown → OFF; fuel-gated start; fuel drains while FA thrusts.
- Setpoint persistence: tap-release W → craft keeps cruising; X → eases to
  hover; yaw 90° at cruise → world velocity vector rotates accordingly.

*Client e2e (real key injection + client-side readback — honest-client-e2e SOP)*
- **Camera lock contract**: inject mouse movement → craft yaw/pitch tracks,
  and player rotation == craft rotation on every sampled tick.
- Space held 3 s → HUD shows start progress then "Engines started", client
  pos rises ~1 block; early release cancels (no flight).
- R in a nose-down attitude climbs along body-up (D2 contract, the exact
  case that confused playtest v1 — now with the camera showing why).
- HUD bars reflect setpoint vs actual; N toggles FA line; X zeroes bars.

### 6. Phased plan

- [x] **Phase 1 — Camera-nose lock + mouse-as-rate** (client) + camera e2e.
      Shipped 2026-06-11. Beyond the planned scope, the perception-contract
      tests caught and fixed three real defects:
      (1) v1 never replicated the FF pitch to the client at all (rotationPitch
      now mirrors it; renderer/passenger seating unaffected);
      (2) the vanilla riding echo (server answers every passenger report with
      a PosLook carrying ~1-RTT-stale rotation) yanked the locked camera by
      6–18° per frame during turns — killed by `MixinNetHandlerFFCameraRepin`
      re-pinning after the vanilla handler (baseline moved together with the
      pin: no feedback);
      (3) client and server integrate turn-rate input over their own ticks, so
      headings drifted a few degrees per maneuver with the tracker silent
      after rotation stops — server now force-resyncs pose (SPacketEntityTeleport)
      on the turn→idle edge.
      Test infrastructure: frame-time camera-lock telemetry
      (`RocketEventHandler.maxCameraLockErrorDeg`/`lastCameraLockErrorDeg`),
      FF liveness + collision flags in the `rocket info` probe, full-column
      pre-clear in the e2e fixture (random per-run world seed could overhang
      terrain above the pad → `move()` zeroed motionY on the ceiling collision —
      the root of every "rocket never moves" flake). FF_LAND_GRACE_TICKS 30→60
      as an interim crutch until Phase 2 deletes the kick.
      Verified: unit 25/25, server FF 17/17, client e2e 20/20 twice in a row.
      *Manual playtest checkpoint: perception must feel right before
      anything is built on top.* ← **pending user check**
- [x] **Phase 2 — Engine start/shutdown** — shipped 2026-06-11 (commit
      `5c156c1b`, joint with Phase 3: the liftoff hover is a special case of
      the FA control law and both ride the same input-wire change, so the
      two phases are one commit). Hold-jump 3 s ritual (client accumulator,
      HUD progress, early-release cancel) → ENGINE_START packet → server
      validation (passenger/mode/fuel/TWR>1). No kick: liftoffStep eases to
      pad+1 and hovers; landing detector arms on first ground departure
      (grace window deleted); touchdown auto-stops. "Engines on" ≡
      isInFlight for FF — no new replicated state. New probe `fill-fuel`
      (ENGINE_START honestly rejects a dry rocket).
- [x] **Phase 3 — FA setpoint core** — shipped 2026-06-11 (`5c156c1b`).
      rampSetpoint/faStep pure laws + worldToBody/bodyToWorld shared basis;
      setpoint in DataParameters (HUD) + NBT; X = cut flag on the wire
      (stop/hover flags and H/B keys removed); FA re-enable captures the
      current velocity; FA off = raw Newtonian (idle-drag removed, Shift
      brake stays).
- [x] **Phase 4 — HUD indication** — shipped 2026-06-11 (`b3522844`).
      FWD/LAT/VRT setpoint-vs-actual text pairs + graphic bipolar bars,
      turn-rate dot, SPD readout, "Newtonian" FA-off label.
- [x] **Phase 5 — Cleanup** — shipped 2026-06-11. Lang sweep (orphaned
      msg.ff.hud.pitch dropped, X line reworded to "Brake to hover");
      `[FF-TRACE]/[FF-DEBUG]` KEPT — harness-only (test-mode gated),
      repeatedly earned their keep during the Phase 1 flake hunt.

### Defaults taken without asking (overridable)

- `SETPOINT_RAMP`: full scale in ~3 s of holding a key.
- Fuel drain stays binary-per-tick (classic mirror), not proportional to
  commanded accel.
- Mouse feel: rate from mouse *movement* (capped), not deflection-hold —
  deflection-hold needs cursor-capture UI; can be a later option.
- A/D stay additive yaw; no roll channel.

## Dependencies
- Requires: forge-test-framework ≥ 0.4.5 (mouse injection may need a new
  FTF capability — `moveMouse(dx, dy)`; if so, FTF change goes straight to
  its `master` per convention).
- Blocks: TASK-45 reconciliation (`feature/solar-map-ff-rework` carries the
  old FreeFlightPhysics; reconcile after v2 lands).

## Completion checklist
- [x] All phases implemented (manual playtest sign-off PENDING — the user
      tests all phases together)
- [x] Unit + server + client suites green (bounded runs)
- [x] Lang en+ru complete; no orphaned keys
- [x] tasks/README.md updated
