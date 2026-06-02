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
3. **Only motors matter, everything wears.** `getBreakingProbability`
   weights only motors (nuclear 1.0, motor 0.2, else 0), but
   `damageParts()` transitions ALL `TileBrokenPart`s — tanks/seats accrue
   `stage` that does nothing yet inflates the "worn parts" counters. UI lies.
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

## Bug ledger (to log during this task)

- Tanks/seats accrue `stage` via `damageParts()` but `getBreakingProbability`
  ignores them → "Tanks worn: N" counter is meaningless today.
- Nuclear `additionalProb=1.0` makes a single stage-1 nuclear motor a 10%
  loss-everything roll with no warning.
(Both consequences change under this task; log as found, note the fix.)

---

## Phases (one commit each; user reviews at the end)

### Phase 0 — wear feeds stats (graduated)
- In `StorageChunk.recalculateStats`: when summing engine thrust, multiply
  by `1 − wearThrustPenaltyMax · stage/maxStage` using the block's
  `TileBrokenPart` at that position. Reduce worn fuel-tank capacity the same
  way (gives "Tanks worn" real meaning).
- Net effect composes with TASK-weight: worn rocket → lower thrust/capacity
  → lower TWR → may hit `minLaunchTWR` and be refused with a clear error.
- **Acceptance**: server probe sets a motor's stage, assembler stats show
  reduced thrust / TWR; unit test for the thrust factor formula.

### Phase 1 — explosion gating + pre-launch warning + config switch
- New config: `wearThrustPenaltyMax` (0.5), `wearCriticalBlocksLaunch`
  (bool), `wearWarnProbability` (e.g. 0.05), `serviceStationStandaloneRepairMultiplier`
  (3.0).
- `preLaunch`: compute breaking prob; ≥ warn threshold → message the pilot
  (% + which parts are critical) BEFORE any explode roll. If
  `wearCriticalBlocksLaunch` and prob ≥ critical → `setError` + abort
  (no explosion). Else keep stochastic explode, but only after the warning.
- **Acceptance**: server test — high-stage rocket either blocked (config on)
  or warned (config off); unit test for the gating predicate.

### Phase 2 — visibility
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
