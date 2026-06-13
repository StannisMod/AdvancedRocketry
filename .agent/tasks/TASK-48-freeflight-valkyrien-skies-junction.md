# TASK-48: Free Flight ↔ Valkyrien Skies junction (rocket = controllable VS ship)

## Ticket
- **Status**: 🚧 In Progress — Design (seam proposed; integration not started)
- **Branch**: design + seam on `feature/true_spaceships`; implementation needs
  BOTH this and `feature/true_rcs` together (see Merge coordination).
- **Created**: 2026-06-13
- **Type**: Feature / cross-branch integration
- **Depends on**: [TASK-47](TASK-47-true-spaceships-valkyrien-skies.md) (VS soft-dep),
  Free Flight (the `feature/true_rcs` "TASK-46 Free Flight" line).

## Context & decision

Product decision (2026-06-13): **every assembled rocket is BOTH an `EntityRocket`
and a Valkyrien Skies ship.** The rocket you fly should also be the rigid body you
walk around inside. But "flying the rocket" is owned by **Free Flight** (FF, on
`feature/true_rcs`), and "the rocket is a walkable rigid body with physics" is
owned by **VS** (TASK-47, on `feature/true_spaceships`). Both want to own the
craft's movement — that junction is this task.

## The two sides (researched 2026-06-13)

### Free Flight (`feature/true_rcs`) — how it moves the rocket
- Server-authoritative. Clean split:
  - **Decision layer (pure, side-effect-free):** `api/FreeFlightPhysics` →
    returns a `Step{motionX,Y,Z, yaw, pitch, thrustApplied}` from a
    `FreeFlightInput` (pilot intent DTO). MC-free, reusable as-is.
  - **Application layer (hardcoded):** `EntityRocket.tickFreeFlight()` writes the
    Step to the entity — `motionX/Y/Z`, `rotationYaw`, `rotationPitch`, then
    `Entity.move(SELF, …)`. Plus client-side sync overrides
    (`setPositionAndRotationDirect`, `setVelocity`, dead-reckoning in `onUpdate`)
    and a camera-nose lock (`KeyBindings` + `MixinNetHandlerFFCameraRepin`) that
    assume FF owns `rotationYaw/Pitch`.
- Arcade kinematics (blocks/tick velocity, capped speed), NOT a force integrator.
  Thrust authority reuses `stats.getAcceleration()` (same TWR gate as classic).
- No existing movement abstraction — `RocketFlightMode{CLASSIC_LAUNCH,FREE_FLIGHT}`
  enum + fields on `EntityRocket`; the application is inline.

### Valkyrien Skies (TASK-47) — how it would move the rocket
- VS owns a ship's transform via a `ShipTransform` (matrix/quaternion) integrated
  by its own threaded rigid-body physics. Entities on a ship are carried by
  `EntityDraggable`; thrust is applied via `IPhysicsBlockController.onPhysicsTick`
  → `PhysicsCalculations.addForceAtPointNew`, or velocity directly via
  `ShipPhysicsData` (get/setLinear/AngularVelocity).

## The conflict, precisely

FF's `tickFreeFlight()` writes the entity transform every tick; VS wants to own
that transform. The exact FF write-points that fight VS (all in
`feature/true_rcs:EntityRocket.java`): `motion*` assignment, `rotationYaw`,
`rotationPitch`/`freeFlightPitch`, `Entity.move()`, the landing motion-zero, the
heading-resync `SPacketEntityTeleport`, and the client overrides
(`setPositionAndRotationDirect`, `setVelocity`, dead-reckon) + the camera lock.

## The seam (proposed)

FF's decision layer is already clean; the conflict is only in the application.
So: **abstract "apply the Step" behind a backend interface.** Proposed contract
landed on this branch: `entity/IRocketFlightBackend`
- `applyFlightStep(rocket, mX,mY,mZ, yaw, pitch, thrustApplied)` — realize one
  tick. Expressed in primitives (not FF's `Step`) so the seam compiles on the VS
  branch before FF merges.
- `ownsTransform()` — when true, FF must skip its own `motion*`/`move()`/client
  dead-reckon, and the camera lock reads orientation from the backend.

Two backends:
- **Legacy** (`ownsTransform()==false`): today's FF behaviour — write
  motion/rotation + `Entity.move()`. Used when VS is absent.
- **VS** (`ownsTransform()==true`, in `integration/vs/`, behind the
  `VSIntegration` gate): translate the desired motion/orientation into the
  rocket's VS ship and let VS own displacement.

FF's `tickFreeFlight()` then becomes: compute `Step` (unchanged) →
`backend.applyFlightStep(...)`; and its transform/sync/camera code is guarded on
`!backend.ownsTransform()`.

## Open design questions (the real work — resolve during integration)

1. **Setpoint vs force.** FF is arcade kinematics (desired velocity/orientation),
   not forces. The VS backend most likely feeds FF's desired linear+angular
   velocity as a **setpoint** into `ShipPhysicsData` (VS tracks it), rather than
   converting to `addForceAtPointNew`. Decide; setpoint preserves FF's arcade
   feel, force is more "physical" but re-tunes the whole feel.
2. **Rotation ownership + camera.** VS owns ship rotation (quaternion); FF's
   mouse-as-rate steering + hard camera-nose lock assume FF owns
   `rotationYaw/Pitch`. The VS backend must drive ship orientation from FF's
   desired yaw/pitch, and the camera lock must read VS's transform.
3. **Client sync handoff.** With `ownsTransform()==true`, disable FF's
   `setPositionAndRotationDirect`/`setVelocity`/dead-reckon overrides and use VS's
   own client transform replication. Avoid double-correction jitter.
4. **Walk-on while flying — the whole point.** FF currently seats the pilot
   (`startRiding`). VS carries entities via `EntityDraggable`. Reconcile: can the
   pilot/passengers walk the deck while the rocket flies? Who reads pilot input if
   the pilot isn't seated? (A control seat/console the pilot stands at, vs a
   mounted seat.)
5. **Liftoff / landing / terrain.** FF has liftoff/hover/land laws
   (`liftoffStep`, `shouldLand`). VS ships interact with terrain via physics
   collision. Define ground/landing semantics for a VS-backed rocket.
6. **Dual representation: EntityRocket + VS ship.** A rocket is currently an
   `EntityRocket` carrying a `StorageChunk` (captured blocks, frozen). A VS ship
   keeps **real blocks** in a shipyard region. Having both represent one rocket is
   the deepest question: does `EntityRocket` become a thin controller/anchor over
   a VS ship (blocks live in VS, not StorageChunk), or do they coexist? When does
   the VS ship get created — at assembly (always) or on entering free flight?
   (User intent: rocket is a ship from assembly.)
7. **Save/wire compat.** Two movement systems + VS ship-data in the save. Define
   what happens to existing rocket saves, and to a save made with VS that is later
   opened without VS (per save-and-wire-compat SOP).

## Merge coordination

FF (`feature/true_rcs`) and VS (`feature/true_spaceships`) are independent
unmerged branches; the integration needs both. Plan (to confirm): land VS
(TASK-47) and FF (true_rcs) onto a common base, then do this junction on the
merged tree. Until then, the seam interface here is a forward-declaration; FF
adopts it at merge.

## Acceptance (high level)

- [ ] FF's `tickFreeFlight()` routes through `IRocketFlightBackend`; legacy
      backend reproduces today's behaviour bit-for-bit (no VS) — regression-pinned.
- [ ] VS backend: a pilot flies a rocket whose displacement is owned by VS
      physics; no transform fighting / jitter.
- [ ] Pilot (and passengers) can walk the deck while the rocket flies.
- [ ] AR works in all four combos: neither / FF-only / VS-only / both.
- [ ] Save/wire compat defined and tested.

## Related
- [TASK-47](TASK-47-true-spaceships-valkyrien-skies.md) — VS soft-dep + scaffold.
- Seam: `src/main/java/zmaster587/advancedRocketry/entity/IRocketFlightBackend.java`.
- FF source (branch `feature/true_rcs`): `api/FreeFlightPhysics`,
  `api/FreeFlightInput`, `api/RocketFlightMode`, `entity/EntityRocket#tickFreeFlight`.
- SOPs: [save-and-wire-compat](../sops/development/save-and-wire-compat.md),
  [single-source-of-truth-gating](../sops/development/single-source-of-truth-gating.md),
  [mixin-coremod-dev-vs-prod](../sops/development/mixin-coremod-dev-vs-prod.md),
  [task-lifecycle](../sops/development/task-lifecycle.md).
