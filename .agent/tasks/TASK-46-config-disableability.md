# TASK-46: Make weight / wear / weather mechanics fully disableable in config

**Branch**: `feature/postponed`
**Opened**: 2026-06-02
**Status**: ✅ Completed 2026-06-03
**Driver**: user directive — "I told players every mechanic I added can be
turned off in the config, but that's not actually true and they don't like
it." Audit + close the leaks for the weight, wear, and weather systems
shipped on this branch (weight/TWR rework `8da5d223`, TASK-45 wear).

**Governing SOPs**:
- `.agent/sops/development/config-flag-disableability.md` (authored by this
  task) — single-source gate, gate accrual AND consequences, gate mixins at
  the weave, pin OFF-behaviour as a revert guard.
- `.agent/sops/development/testing-principles.md` — pin contracts, not impl.
- `.agent/sops/development/mixin-coremod-dev-vs-prod.md` — the coremod /
  MixinBooter rules behind the weather-mixin gating.

---

## Problem (what was actually leaking)

Each mechanic had a config flag, but the flag left a path the mechanic still
ran through — so "off" was not really off:

1. **Weight** — `advancedWeightSystem` gated the weight *calculation*, but
   the TWR launch gate (`StatsRocket.canLaunch` → `EntityRocket` launch path)
   ran regardless, so a player who disabled the weight system could still be
   refused launch with `error.rocket.tooHeavy`.
2. **Wear** — `partsWearSystem` gated the *consequences* (thrust loss, tank
   leak, seat block), but not *accrual* (`StorageChunk.damageParts()`), so
   parts kept advancing wear stages with the system "off".
3. **Weather** — `enableCustomPlanetWeather` gated the `WorldInfo` wrapping,
   but `WorldProviderPlanet.updateWeather()` kept running its custom cycle
   for any planet whose XML carried non-default markers — clobbering the
   shared overworld weather while "disabled".
4. **Weather mixins** — the two weather mixins were always woven; nothing
   tied them to the flag.

A sub-agent audit also produced two **wrong** findings that code-verification
caught (`forcePlanetWeatherWorldInfoWrapper` is subordinate to the main flag,
not a bypass; wear accrual was narrower than claimed) — recorded in
`verify-subagent-findings.md`.

## What shipped

**Production gates (single-source-of-truth):**
- `StatsRocket.canLaunch()` → returns `true` when `advancedWeightSystem` is
  off (fixes the launch gate for every caller at once).
- `StorageChunk.damageParts()` → early-return when `partsWearSystem` is off
  (no stage ever advances).
- `WorldProviderPlanet.updateWeather()` → gate the custom cycle on
  `enableCustomPlanetWeather`, not only on XML markers.
- `ARMixinPlugin` (`IMixinConfigPlugin`) → skips weaving the two weather
  mixins (`MixinWorldServerMulti`, `MixinPlayerList`) when custom weather is
  off; reads the `.cfg` directly, fail-open.
- `TileRocketAssemblingMachine.getNeededThrust()` → returns 0 when the weight
  system is off (no misleading TWR requirement in the GUI).

**Coremod hardening (separate but adjacent):**
- `AdvancedRocketryPlugin` now registers mixins via MixinBooter's
  `IEarlyMixinLoader.getMixinConfigs()` instead of calling
  `MixinBootstrap.init()` from the coremod. The old self-bootstrap crashed a
  packaged client under MixinBooter with a cross-classloader `LinkageError`;
  a `try/catch` (commit `0fd8a834`) was insufficient and was superseded by
  `22b70c56`. See `mixin-coremod-dev-vs-prod.md`.

**Tests (+6: 4 unit / 2 server), contract-level, OFF-state as revert guard:**
- `StatsRocketTest` — `canLaunchIgnoresTwrGateWhenWeightSystemDisabled` (new);
  `canLaunchRespectsMinLaunchTWR` + `accelerationOnWeightlessRocketIsZeroNotInfinite`
  realigned to the new contract (the TWR gate only exists when the system is on).
- `ARMixinPluginTest` (3 unit) — weather mixins weave iff the flag is on.
- `WearAccrualDisableTest` (1 server) — accrual happens only when on.
- `WeatherCycleDisableTest` (1 server) — the forced-clear cycle runs only when
  on; with it off the rain we set survives a weather tick.

**Test probe additions (test-only `/artest`):**
- `CONFIG_WHITELIST` += `advancedWeightSystem`, `minLaunchTWR`,
  `partsWearSystem`, `increaseWearIntensityProb`, `enableCustomPlanetWeather`.
- `wear damage-parts <id> [n]`, `weather set-marker <dim> <rain> <thunder>`,
  `weather tick-provider <dim> [n]`.

**SOP authored:** `config-flag-disableability.md` (+ this task seeded
`single-source-of-truth-gating.md`, `verify-subagent-findings.md`).

## Technical decisions

- **Gate at the single source of truth, not per call site.** The weight gate
  lives in `canLaunch()` (not duplicated in `EntityRocket`), so one edit fixes
  the launch path too.
- **Gate accrual separately from consequences** — they are distinct surfaces;
  the wear leak was purely on the accrual side.
- **Mixins are gated at the WEAVE** (`IMixinConfigPlugin`), because a flag
  cannot disable already-woven bytecode; non-mixin mimics (`updateWeather`)
  still need a normal runtime gate.
- **Harness gotcha pinned:** `ARWeatherWorldInfo` wrapping is decided at
  dimension load and is sticky; `WeatherCycleDisableTest` loads the planet
  wrapped first, then flips the flag, to isolate the `updateWeather` gate from
  the (separately tested) wrapping gate.

## NOT done / follow-ups

- The IEarlyMixinLoader coremod fix was applied to `feature/postponed` only.
  `fix/various` still carries the superseded `try/catch` (another agent owns
  that branch); `feature/solar-map-ff-rework` already has the correct fix.
- Assembler GUI still *displays* a dry-weight TWR number when the system is
  off (informational, not a gate) — left intentionally; only the misleading
  "needed thrust" requirement was zeroed.

## Result

Closed the disableability gap for all three opt-in mechanics: 5 production
gates + 1 coremod hardening fix, 6 new tests (4 unit / 2 server) that pin
OFF-behaviour as a revert guard, 8 probe additions, and the
`config-flag-disableability` SOP. No new production bugs found (only leaks
fixed), so the bug ledger is unchanged. Pyramid regenerated from source on
close (859: 273/82/443/61), correcting stale per-tier values.
Commits: `cff3bf68` (gates+tests+probe), `0fd8a834`→`22b70c56` (mixin
bootstrap), `e4054897` (assembler GUI), `ba264377` (SOPs).
