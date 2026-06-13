# TASK-47: True movable spaceships via Valkyrien Skies integration

## Ticket
- **Status**: 🚧 In Progress — Planning / Spike (Phase 0 not yet started)
- **Branch**: `feature/true_spaceships` (off `origin/1.12`, RFG-buildable)
- **Created**: 2026-06-13
- **Type**: Feature (not a test-coverage task)

## Context

Goal: a real "world within a world" — ships built from blocks that act as a
**single entity you can MOVE, WALK on, and that have their own gravity/physics**.

AR already implements *half* of this for rockets, but only the ride-only half:
- `StorageChunk implements IBlockAccess` (`util/StorageChunk.java:57`) +
  `WorldDummy extends World` (`world/util/WorldDummy.java:25`) = exactly the
  "Moving World" captured-blocks-in-a-fake-world pattern.
- `EntityRocket` carries the `StorageChunk`, renders it via a baked display
  list, rotates by yaw (`entity/EntityRocket.java:130,1241`).
- The scanner captures the structure (`tile/TileRocketAssemblingMachine.java:631,647`).

But everything needed for the stated goal is **missing**, and these are
*inherent* to the Moving World family, not bugs:
- **No block collision** — `StorageChunk` is never handed to MC's physics; the
  player is a passenger glued to a seat (`updatePassenger()`), not standing on
  the floor.
- **Tile entities frozen** — `WorldDummy.tick()`/`updateEntities()` are no-ops;
  no redstone/machines while moving.
- **Scalar gravity** — `GravityHandler.applyGravity()` edits only `motionY`
  (`util/GravityHandler.java:47`); no "down relative to the ship".

### Research conclusion (2026-06-13)

Two mod families exist (full report in session history / see Related):
1. **Moving World / Archimedes / DaVinci's Vessels** — captured blocks rendered
   by an entity; rider locked to a seat; only yaw collision; TEs misbehave.
   **You cannot freely walk on it.** This is the family AR's rockets already are.
2. **Valkyrien Skies (VS1 for 1.12.2)** — blocks stay **real blocks** relocated
   to a far-off "shipyard" region, projected by a `Matrix4d` subspace↔world
   transform; vanilla `Entity.move` is replaced by oriented-polygon (SAT)
   collision (`EntityCollisionInjector`) + a carry-velocity drag system
   (`EntityDraggable`); a real threaded rigid-body engine (MOI tensor, torque,
   angular velocity; VS2 uses the native Krunch solver). TEs tick normally
   (real chunks). **This is the only family that gives free-walking + rotation
   + working machines + real physics.**

The stated requirements (walk + local gravity + physics) ⇒ family 2.

### Decision

**Path B: integrate Valkyrien Skies as a dependency. Do NOT reimplement the
physics** (SAT collision, inertia tensors, threaded solver) — that is months of
work that VS already does maturely. Dependency mode (hard vs soft) is an open
decision — see Phase 0 / Decisions.

## Key open decisions & spikes (resolve BEFORE committing to integration)

These are the risk drivers. Phase 0 exists to answer them; do not start Phase 1+
until they are green.

1. **VS availability on 1.12.2.** VS1 is EOL. Need a buildable/Maven-available
   artifact (CurseMaven? a published dev jar? vendored source?) AND its public
   API surface for creating/controlling ships. *Risk: high — may require
   building VS1 from source against our toolchain.*
2. **License.** Confirm VS1's license and whether it permits a hard runtime
   dependency / shipping alongside / vendoring. Affects hard-vs-soft and
   distribution. *Must verify before any code lands.*
3. **Hard vs soft dependency.**
   - *Hard*: simpler integration, direct API use; but ties AR to VS presence,
     a specific VS version, and VS's very deep mixin set.
   - *Soft*: optional `@Optional`/reflection-guarded module; AR works without
     VS; more fragile, more boilerplate. The user prefers hard *unless* it
     proves too fragile.
4. **Mixin coexistence.** VS injects into `Entity.move`, chunk access, chunk
   load/save, rendering, frustum culling. AR has `MixinEntityGravity` + the two
   weather mixins, all under MixinBooter via `IEarlyMixinLoader`
   (base commit `36f19e22`). **Must prove both mixin sets coexist in dev AND
   reobf/prod** — this is the single most likely failure mode. See
   [`mixin-coremod-dev-vs-prod.md`](../sops/development/mixin-coremod-dev-vs-prod.md).
5. **AR-rocket → VS-ship bridge model.** How does an assembled AR structure
   become a VS ship? Reuse the scanner bounds capture
   (`TileRocketAssemblingMachine`/`StorageChunk.copyWorldBB`) and hand the real
   blocks to VS ship creation, or assemble directly as a VS ship. Decide whether
   `EntityRocket` is replaced, wrapped, or kept for sub-orbital and VS used for
   free-flight.
6. **Gravity semantics.** VS carries entities via drag but keeps world-down;
   it does *not* give a rotated local "down". Decide what "own gravity" means:
   (a) good-enough = carry-velocity + world-down (VS default), or (b) true
   local-down via extending `GravityHandler` to a vector (the
   `TileAreaGravityController` 6-direction offsets at
   `tile/multiblock/TileAreaGravityController.java:219` are a reference impl).

## Implementation phases (proposed — subject to Phase 0)

- **Phase 0 — Spike / de-risk (BLOCKING).** Get VS1 1.12.2 building in a dev
  workspace (Maven coords or vendored source); confirm license; smoke-test a
  hand-built VS ship in `runClient`; verify it coexists with AR's mixins +
  MixinBooter. **Deliverable: go/no-go + dependency-mode decision + the API
  notes.** No production AR code yet.
- **Phase 1 — Dependency wiring.** Add VS to `build.gradle` for dev + reobf/prod
  (hard, or soft via optional + runtime guard). Document per
  [`build-and-run-env.md`](../sops/development/build-and-run-env.md).
- **Phase 2 — Bridge AR → VS.** Convert an assembled structure into a VS ship
  (reuse scanner bounds + capture). Helm/controller block (new or repurposed
  assembler). Decide `EntityRocket` coexistence.
- **Phase 3 — Gravity & atmosphere on ships.** Integrate AR per-planet
  gravity/atmosphere with entities on VS ships; implement chosen local-down
  semantics; extend `GravityHandler`.
- **Phase 4 — Interop.** Launch / dimension transitions / stations / fuel +
  thrust driving VS physics forces. Save & wire compat
  ([`save-and-wire-compat.md`](../sops/development/save-and-wire-compat.md)).
- **Phase 5 — Tests.** Dev smoke + server-harness coverage where feasible. Be
  honest about limits: VS physics is threaded + client-heavy, so much is not
  verifiable headless — see
  [`harness-capabilities-and-limits.md`](../sops/development/harness-capabilities-and-limits.md).

## Phase 0 — findings (desk research done 2026-06-13)

**Preliminary verdict: GO**, with ONE live spike still blocking (mixin
coexistence — see ⚠️). Sources: three web-research passes over VS GitHub
(archived, tag `1.12.2-1.1.7`), CurseForge/CurseMaven, JitPack build logs.

### Availability ✅
- VS1 1.12.2 is **EOL / repo archived (read-only since 2023-01-18)**, but the
  prebuilt jars are obtainable. Three mods: **Core** (CF project `258371`),
  **World** (`404464`), **Control** (`404463`).
- Pull via **CurseMaven** (recommended):
  - `curse.maven:valkyrien-skies-258371:3286262` — Core `1.12.2-1.1.7` (compile + runtime)
  - `curse.maven:valkyrien-skies-world-404464:3061764` — World `1.0.0` (runtimeOnly)
  - `curse.maven:valkyrien-skies-control-404463:3160203` — Control `1.1.1` (runtimeOnly)
- VS built against **Forge 14.23.5.2838**, **Mixin 0.8.2 shaded into its jar**.
  JitPack only serves Core 1.1.6 thin jar (API-compile fallback only). VS-hosted
  maven is dead.

### License ✅
- **All `1.12.2-*` tags are Apache-2.0** (older "Valkyrien Warfare" ≤1.11.2 was a
  restrictive custom license — not relevant to us). Apache-2.0 permits hard
  dependency, redistribution, fork, AND vendoring — only attribution/NOTICE.
- VS team's endorsed pattern: **depend on VS as a separately-installed mod via
  Maven** (they ship an interop API repo for exactly this). VS2 is LGPL-3.0 (n/a).

### API ✅ (internal, but sufficient)
- Programmatic ship assembly: `org.valkyrienskies.mod.common.util.ValkyrienUtils`
  — `assembleShipAsOrderedByPlayer(world, creatorOrNull, physicsInfuserPos, blockFinderType)`
  (server-only, async/queued), or lower-level `createNewShip(world, pos)` +
  `WorldServerShipManager.queueShipSpawn(...)`. Assembly is keyed on a "physics
  infuser" `BlockPos` — for an AR rocket we'd place/anchor one at the ship root.
- Thrust/forces: implement `IPhysicsBlockController.onPhysicsTick(obj, calc, dt)`
  on a TileEntity → call `PhysicsCalculations.addForceAtPointNew(relPos, force, tmp)`.
  Direct velocity via `ShipData.getPhysicsData()` (`ShipPhysicsData`,
  get/setLinear/AngularVelocity). **Maps cleanly onto AR**: our scanner already
  finds the structure; engine thrust can drive `addForceAtPointNew`.
- Must compile against **VS Core internals** (`org.valkyrienskies.mod.common.*`),
  not the thin `valkyrienwarfare.api` jar (transform helpers only).

### Mixin coexistence ✅ CLEARED in dev (obf functional test still pending)
- **Injection-point conflict: LOW.** VS does NOT mix into `Entity#onUpdate` (only
  `EntityMinecart#onUpdate`). Our `MixinEntityGravity` injects `Entity#onUpdate`
  HEAD → different method, coexists. VS touches `Entity#move` (HEAD/RETURN inject,
  not @Overwrite), `getLook`, `getDistanceSq`, water/lava checks — none collide
  with ours. `EntityDraggable` is a `WorldTickEvent`, not a mixin.
- **Bootstrap conflict: TESTED — coexist.** Dev smoke (2026-06-13, `runServer`
  with `curse.maven:valkyrien-skies-258371:3286262` via CurseMaven, RFG
  auto-deobf to MCP): MixinBooter's `MixinBooterPlugin` AND VS's
  `MixinLoaderForge` both loaded ("Finished gathering 6 coremods"), VS ran its
  `MixinBootstrap.init()` ("Valkyrien Skies mixin init"/"searge"), **NO "No mixin
  host service" crash**, FML loaded **11 mods**, `ValkyrienSkiesMod` initialized
  its physics threads, and the server reached running/tick state with AR's
  planet dimensions loaded. So the historical self-bootstrap-under-host crash
  does NOT reproduce with VS + MixinBooter 10.7.
- **Non-fatal noise to handle in prod:** (1) `module-info.class … corrupt zip`
  warning — VS's multi-release jar's module descriptor; 1.12.2 FML/ASM can't
  parse it; mod still loads (strip module-info or ignore). (2) jar-signature
  mismatch WARN (CurseMaven/RFG repackaged → signature stripped). (3)
  `mixins.valkyrienskies-sponge-compat.json` fails without SpongeForge —
  expected, `required:false`, non-fatal; the main `mixins.valkyrienskies.json`
  did not fatal-fail.
- **Still open (needs the obf run):** whether VS's *main* mixins (Entity.move
  ship-collision, etc.) actually WEAVE and ships FUNCTION. VS set obf context
  `searge`; dev is MCP. The dev boot proves coexistence + no crash, not full VS
  functionality. The authoritative functional test is `runObfServer`/packaged
  with VS in the obf mods folder (per mixin-coremod-dev-vs-prod SOP).

### Dependency-mode decision → SOFT / optional (compile against VS, do NOT require it)
Decided 2026-06-13 after weighing "does depending on VS impose physics on
unwilling users?":
- VS physics acts **only on assembled ships** — normal blocks/building/gameplay
  are untouched. A user who installs VS but never assembles a ship sees ~vanilla.
  So no, ship physics doesn't "leak" onto normal AR play.
- BUT VS is a **heavy, invasive, EOL coremod**: once installed it always weaves
  into `Entity.move`/`World`/`Chunk`/rendering, adds a physics thread + shipyard
  region + ship save-data, and carries **known mod incompatibilities** (Thicc
  Entities, OpenComputers, LittleTiles, CubicChunks crash on assembly, …). A
  **hard** dep would force all of that onto every AR install — including the
  large slice of AR's audience that only wants rockets/planets — and would graft
  VS's whole compat-conflict surface onto AR's, while chaining AR to an archived
  dependency.
- Therefore: **compileOnly against VS Core, never bundle/require it.** All
  VS-touching code lives in one isolated integration module behind a runtime
  presence check (`Loader.isModLoaded("valkyrienskies")`) + Forge
  `@Optional.Interface`/`@Optional.Method`. Without VS: the true-spaceship feature
  is simply absent and AR behaves exactly as today (zero VS overhead, zero VS
  conflicts). With VS: it lights up. This is the standard Forge soft-dep pattern;
  the dev smoke already proved the two load cleanly together, so making VS
  *optional* (the easy direction) is low-risk. Effort: MODERATE — mostly module-
  boundary discipline + a "boots without VS" CI smoke to catch any leaked VS
  class reference (→ NoClassDefFoundError).

## Technical decisions

*(Locked as spikes resolve. So far: dependency via CurseMaven Core 1.1.7;
license Apache-2.0 clears all modes; thrust→`addForceAtPointNew`; hard-compile +
runtime-guard. Open: mixin-loader coexistence — pending the live smoke test.)*

## Dependencies & risks

- **VS1 EOL on 1.12.2** — artifact/build availability is the top risk.
- **License** — gates hard-dep / shipping.
- **Mixin conflict** with AR's coremod (highest technical risk).
- **Threading / determinism** — VS runs physics off-thread; affects testing.
- **Save-format coupling** — VS ships persist as real chunks + ship data;
  uninstalling VS or AR could strand saves. Document the compat contract.
- **Soft-dep fragility** if that mode is chosen.

## Completion checklist

- [ ] Phase 0 spike done; go/no-go recorded; dependency mode decided.
- [ ] VS license verified and compatible with the chosen mode.
- [ ] VS + AR mixins proven to coexist in dev AND reobf/prod.
- [ ] AR structure → VS ship bridge working in `runClient`.
- [ ] Gravity semantics implemented and demonstrated.
- [ ] Launch/dimension/station interop defined and working.
- [ ] Save/wire compat documented; behaviour with VS absent defined.
- [ ] Tests written to the honest limit of the harness.
- [ ] System docs updated; this task closed per task-lifecycle SOP.

## Related

- Research families & sources: Valkyrien Skies 1 (1.12.2)
  <https://github.com/ValkyrienSkies/Valkyrien-Skies> (`collision/`,
  `ships/ship_transform/`, `ships/entity_interaction/`, `physics/`); VS2/Krunch
  <https://github.com/ValkyrienSkies/Valkyrien-Skies-2>; VS Wiki
  <https://wiki.valkyrienskies.org/wiki/Valkyrien_Skies>; Moving World
  <https://github.com/TridentMC/MovingWorld> (branch `1.12`); DaVinci's Vessels
  <https://github.com/TridentMC/DavincisVessels>.
- AR code touchpoints: `util/StorageChunk.java`, `world/util/WorldDummy.java`,
  `entity/EntityRocket.java`, `tile/TileRocketAssemblingMachine.java`,
  `util/GravityHandler.java`, `mixin/MixinEntityGravity.java`,
  `tile/multiblock/TileAreaGravityController.java`,
  `stations/SpaceObjectManager.java`.
- SOPs: [mixin-coremod-dev-vs-prod](../sops/development/mixin-coremod-dev-vs-prod.md),
  [build-and-run-env](../sops/development/build-and-run-env.md),
  [save-and-wire-compat](../sops/development/save-and-wire-compat.md),
  [harness-capabilities-and-limits](../sops/development/harness-capabilities-and-limits.md),
  [task-lifecycle](../sops/development/task-lifecycle.md).
