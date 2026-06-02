# TASK-45: Maintenance-station / parts-wear rework

**Branch**: `feature/postponed`
**Opened**: 2026-06-02
**Driver**: user directive — "the maintenance station is half-finished and
annoys everyone; make wear readable and the repair loop bearable."
Follows the weight/TWR rework (`8da5d223`) on the same branch and composes
with it (worn parts feed thrust → TWR → launch gate).

**Governing SOPs**:
- `.agent/sops/development/testing-principles.md` — pin CONTRACTS
  (player-visible behaviour, wire/NBT formats), never impl details
  (exact RF, loop bounds, magic stages).
- `CLAUDE.md` bug-tracking rule — log discovered production bugs in
  `.agent/history/known-bugs-ledger.md`.

---

## Problem (what's actually broken today)

The wear loop technically closes (`TileBrokenPart.transition()` on landing
→ `StorageChunk.getBreakingProbability()` → `shouldBreak()` → `explode()` at
launch → service station resets `stage`). The frustration is in the
*presentation and ergonomics*:

1. **Invisible wear, silent death.** `hasServiceMonitor` is computed +
   synced but the GUI gate in `EntityRocket.getModules` (`//TODO Add check
   for the service monitor`) is commented out; servicemonitor tooltip
   promises "damage view" marked `WIP`. Rocket explodes on launch with no
   warning — player loses rocket + cargo with zero forewarning.
2. **Repair is a Rube-Goldberg contraption.** Station + RF + ItemLinker +
   adjacent PrecisionAssembler (≤5) + `*_repair_*` recipes + a fragile
   two-phase extract→craft→reinject handshake with a known "out of sync"
   path and no recovery.
3. **Dead tank/seat counters.** Only the 5 motor blocks create a
   `TileBrokenPart` (verified: `BlockRocketMotor`,
   `BlockBipropellantRocketMotor`, `BlockNuclearRocketMotor`,
   `BlockAdvanced{,Bipropellant}RocketMotor`). Tanks/seats have no wear
   state, so the service-station "Tanks: N / Seats: N worn" counters can
   never be non-zero — dead UI promising a feature that doesn't exist.
4. **Nuclear is a death-trap.** `additionalProb=1.0` → stage-1 nuclear motor
   already 10% explosion, stage-10 = 100%, with a 4× accrual multiplier.
5. **Opaque tuning.** Backwards stage loop, `(stage+1)·transitionProb/√(2i+1)`;
   the manual "right-click to wear" affordance is commented out.

## Decisions (locked with user 2026-06-02)

- **Consequences = graduated + visibility.** Wear first degrades stats
  (thrust / tank capacity → TWR), explosion only at high stage and ALWAYS
  after a pre-launch warning.
- **Critical-wear launch behaviour = config switch** (`wearCriticalBlocksLaunch`):
  block the launch (like too-heavy) OR warn-and-allow the stochastic explode.
- **Repair = two-tier.** Assembler-backed path stays the cheap/normal mode
  (assemblers are plentiful late-game). ADD a standalone station path that
  consumes the part's PrecisionAssembler repair-recipe **non-part**
  ingredients × `serviceStationStandaloneRepairMultiplier` (default 3.0,
  config) + RF + time. Fix the assembler handshake robustness regardless.

## Architecture revision (2026-06-02) — wear becomes a capability + extends to tanks/seats

User directives after Phase 0:
- Wear should be a **Forge capability** (`IPartWear` / `CapabilityWear`,
  mirroring `CapabilitySpaceArmor`), so it can ride on a block's existing
  TileEntity later. `TileBrokenPart` hosts the capability **and** does the
  breaking render. (The "foreign TE with its own custom render" case does
  not occur in AR today — noted, not solved.)
- **Tanks and seats now wear too.** Verified neither has its own
  TileEntity (capacity is blockstate-driven, fuel lives in `StatsRocket`),
  so both can take a `TileBrokenPart` + `IBrokenPartBlock` like motors.
- **Tank consequence**: at launch, per worn tank roll whether it LEAKS
  (chance from stage). If it leaks: lose some fuel AND, because the
  contents are flammable/oxidizer, roll an explosion risk. Tank capacity
  is NOT degraded.
- **Seat consequence**: a worn seat (≥ critical stage) **blocks a crewed
  launch** (refuse with error); uncrewed/automated rockets still fly.
  Seats wear slowly (low transition multiplier).
- Tanks/seats have no repair recipes → repaired by replacing the block
  (new block = stage 0). Station/assembler repair stays recipe-driven
  (motors). Noted; recipes for tanks/seats can be added later.

Revised phase order: Phase 0 (motor thrust, done) → **0b** (capability +
migrate consequence reads off `instanceof TileBrokenPart`) → **0c** (wear
on tank + seat blocks) → Phase 1 (consequences + gating: tank leak, seat
crewed-launch block, pre-launch warning, config switch) → 2/3/4.

## Bug ledger (to log during this task)

- Tanks/seats accrue `stage` via `damageParts()` but `getBreakingProbability`
  ignores them → "Tanks worn: N" counter is meaningless today.
- Nuclear `additionalProb=1.0` makes a single stage-1 nuclear motor a 10%
  loss-everything roll with no warning.
(Both consequences change under this task; log as found, note the fix.)

---

## Phases (one commit each; user reviews at the end)

### Phase 0 — wear feeds stats (graduated) ✅
- In `StorageChunk.recalculateStats`: when summing engine thrust, multiply
  each motor's rated thrust by `1 − wearThrustPenaltyMax · stage/maxStage`
  via its `TileBrokenPart` (`wearThrustFactor`). Added `getMaxStage()` to
  `TileBrokenPart` and `wearThrustPenaltyMax` config (default 0.5).
- Only motors wear (no `TileBrokenPart` on tanks/seats), so tank-capacity
  degradation was dropped — there is no wear data to act on. The dead
  tank/seat counters are a Phase-2 GUI cleanup + ledger item.
- Net effect composes with the weight rework: worn rocket → lower thrust
  → lower TWR → may hit `minLaunchTWR` and be refused with a clear error.
- **Acceptance**: server probe sets a motor's stage, assembler stats show
  reduced thrust / TWR; unit test for the thrust factor formula.

### Phase 1 — explosion gating + pre-launch warning + config switch ✅
(0b ✅ capability + migration; 0c ✅ tank/seat wear via TileWearable.)
- New config: `wearThrustPenaltyMax` (0.5), `wearCriticalBlocksLaunch`
  (bool), `wearWarnProbability` (e.g. 0.05), `serviceStationStandaloneRepairMultiplier`
  (3.0).
- `preLaunch`: compute breaking prob; ≥ warn threshold → message the pilot
  (% + which parts are critical) BEFORE any explode roll. If
  `wearCriticalBlocksLaunch` and prob ≥ critical → `setError` + abort
  (no explosion). Else keep stochastic explode, but only after the warning.
- **Acceptance**: server test — high-stage rocket either blocked (config on)
  or warned (config off); unit test for the gating predicate.

### Phase 2 — visibility ✅ (service-station GUI counters folded into Phase 3)
- Wire the `hasServiceMonitor` gate in `EntityRocket.getModules`
  (uncomment + implement) → show the `ModuleBrokenPart` panel.
- Service Station GUI: add max/critical stage + breaking-% readout.
- Drop "WIP" from servicemonitor/servicestation lang tooltips; add warning
  lang keys.
- **Acceptance**: client/e2e or server-readout check that the panel/readout
  reflects part stages.

### Phase 3 — standalone repair mode
- Add input item slots + GUI to `TileRocketServiceStation` (currently
  `MODULARNOINV`).
- Standalone repair: for each worn part, look up its PrecisionAssembler
  repair recipe, take the non-part `itemingredients`, multiply by
  `serviceStationStandaloneRepairMultiplier`, verify + consume from the
  station's slots, charge RF + time, reset `stage` to 0 in place.
- Keep assembler path as the ×1 default; harden the two-phase handshake
  (recover on out-of-sync / lost output instead of stalling).
- **Acceptance**: server test — load ingredients×3, run station without an
  assembler, assert ingredients consumed and stage reset; assembler path
  still works.

### Phase 4 — config + tests + ledger
- Finalise config keys (sync flags), `/artest wear` probe verbs
  (get/set stage, breaking-prob, trigger repair), unit + server coverage,
  ledger entries.

---

## Out of scope
- New wear *causes* (e.g. atmospheric/asteroid damage) — landing-only accrual
  stays.
- Rebalancing the per-stage transition curve beyond what graduation needs.
- Visual/particle effects for worn parts.
