# TASK-55 — Solar-map deep-space flight: redesign on top of Free Flight Mode

> Renumbered from TASK-45 on the 2026-06-15 `origin/1.12` merge — TASK-45 stays
> with the (completed) maintenance-station rework. Pre-merge notes call this TASK-45.

**Status:** 📐 Feature request — DESIGN required before implementation
**Type:** Architecture / Refactor
**Branch target:** TBD (probably a `feature/solar-map-ff-rework` after design lock)
**Depends on:** `feature/true_rcs` (Free Flight Mode landed in commits `86154241`,
`aa41bbdf`, and the RCS-deprecation commit at the head of that branch).
**Created:** 2026-06-02

---

## Why this exists

Free Flight Mode (FF) shipped on `feature/true_rcs` supersedes the asteroid-dim
RCS use case. But the **other** consumer of the RCS state — solar-map "deep
space" navigation when `EntityRocket.getInSpaceFlight() == true` — was
**deliberately not touched**. That branch still uses the legacy boolean
`turningLeft/Right/Up/Down` + `getPassengerMovingForward()` mechanism for
between-planet steering in the solar-system view.

This task is the design + implementation work to migrate solar-map flight to
the FF architecture (or replace it with something better) so the legacy
RCS_MODE datawatcher can finally be removed.

## Current state (read before designing)

See `EntityRocket.java` for the legacy solar-map flight branch
(`if (getInSpaceFlight())` ~ line 1517). Specifically:

- Yaw step `±5°/tick` hardcoded (no rocket stat dependency).
- Forward acceleration `10 * getPassengerMovingForward() * 0.2` — pulled from
  vanilla `player.moveForward`, not from a dedicated input channel. Conflicts
  with the FF input channel design.
- Vertical thrust via `turningUp/turningDownforWhat` booleans → ±0.02 per tick.
- Drag `* 0.98` when no forward input.
- No fuel accounting at all in this branch.
- Planet-encounter detection (`distanceToSpacePosition2` checks) is co-mingled
  with the steering loop — these need to be separated before either can be
  migrated.
- `rcs_mode = false` gets force-set in multiple `reachSpaceManned()` exit paths
  (see lines ~1571, 1587, 1604, 1623). The semantics of "RCS is on whenever
  we're in deep space" are baked into the codebase.

## Why this is not a simple grep+replace

1. **Three coordinate systems intersect in this branch.** `SpacePosition`
   (solar coords, parsecs-like), entity world coords (Minecraft blocks), and
   `DimensionProperties.getRenderSizeSolarView/PlanetView()` (render scale).
   FF physics works in entity world coords; solar-map works in `SpacePosition`.
   You can't just call `tickFreeFlight()` here — units differ.

2. **No collision detection in solar-map.** Today's branch sums `motionX/Y/Z`
   into `spacePosition` directly, bypassing `move()`. FF assumes `move()` is
   called for collision + landing-detection. A solar-map FF variant needs a
   `tickFreeFlightSolar()` that updates `spacePosition` instead.

3. **Planet-encounter logic must survive.** The current branch detects when
   the rocket is "close to a world" and teleports it to that dimension at a
   specific entry point. This is the actual *purpose* of solar-map flight, not
   incidental. The redesign must preserve it.

4. **Multiplayer sync.** Solar-map sends `SENDSPACEPOS` packet every 20 ticks
   from client → server. FF sends FREE_FLIGHT_INPUT per-change client → server
   and applies on server. Solar-map's pattern needs to be re-evaluated against
   FF's authority model.

5. **`acc` is scaled 50× larger than in asteroid-RCS** (`10 * fwd * 0.2 = 2.0 * fwd`
   vs asteroid `fwd * 0.02 = 0.02 * fwd`). The solar-map distances are
   astronomical compared to in-world flight. FF's `MAX_FORWARD_ACCEL=0.08`
   would feel like a crawl across the solar system unless re-tuned per scale.

## Design questions to answer first

Pick one or sketch alternatives:

- **Q1.** Should solar-map flight be FF mode with a sub-mode flag (`isSolar`)?
  Or a separate `RocketFlightMode.SOLAR_FLIGHT` enum constant? Or remain
  outside the FF system entirely as `SOLAR_NAVIGATION` mode handled by its
  own physics class `SolarMapPhysics`?

- **Q2.** Per-tick acceleration cap: do we scale by render-size of solar
  map? Or expose a `getSolarFlightThrust()` stat on the rocket? Or a config
  multiplier?

- **Q3.** Input channels: solar-map needs the same five FF channels (fwd/vert/yaw/
  pitch/brake). Do we reuse `FreeFlightInput` and the same packet? Or define
  a sibling `SolarFlightInput`? Reusing reduces wire bloat; sub-classing
  could carry solar-specific extras (e.g., warp engage).

- **Q4.** Fuel model: solar-map currently doesn't drain fuel. Should
  inter-planetary travel cost fuel proportional to distance traversed?
  Per-tick like FF? Both? (Player UX consideration: long solar transits
  could brick a rocket if running out mid-way.)

- **Q5.** Removal of `RCS_MODE` datawatcher: once solar-map no longer needs
  it, can we delete the field? Save-compat — old NBT will silently ignore an
  unknown key, but live datawatcher unsubscription needs care. Migration
  guard test required.

- **Q6.** UI: solar-map renderer currently reads `rcs_mode_counter` for the
  visual "lying down" animation in `updatePassenger`. FF rocket doesn't tilt
  the passenger model. Do we preserve the visual? If yes, who drives the
  counter when there's no RCS toggle? If no, what's the visual indicator
  that the rocket is in solar-flight vs ground-level FF?

## Out of scope here

- Touching the FF physics class (`FreeFlightPhysics`) — it's stable.
- Changing client keybinds — FF keybinds (M / W / S / A / D / Z / X / Q / E /
  Shift) stay as-is.
- Touching `EntityRocket.toggleRCS()` further — it already routes to the
  deprecation message.

## Acceptance criteria (when implemented)

1. Legacy solar-map flight branch in `onUpdate` (~line 1517 today) is gone or
   delegates to a new FF-derived path.
2. `RCS_MODE` datawatcher field is either removed (with NBT migration test)
   OR documented as "internal — do not depend".
3. Solar-map navigation still teleports rockets to planet dimensions at the
   correct entry points. Existing dimension-transition tests stay green.
4. Solar-map flight consumes fuel (if Q4 says yes) — pinned by a server test.
5. Input goes through `FreeFlightInput` (or its solar sibling), validated on
   server with the same authority gates (passenger + mode).
6. `RcsDeprecationTest` continues to pass, plus a new
   `SolarFlightFreeFlightUnificationTest` pins the new behaviour.

## Estimate

Design phase: 0.5–1 day of architecture sketching + discussion.
Implementation: 2–3 days once design is locked, including tests + a
dimension-transition regression sweep across the existing `testServer` suite.
