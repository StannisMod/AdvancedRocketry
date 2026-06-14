# TASK-51: `enablePerDimensionTime` config toggle — make per-dim time fully disableable

## Ticket

- Source: PR #22 (`fix/various` → `1.12`) merge-safety review on 2026-06-14.
  The review flagged that [[TASK-47]] shipped per-dimension time with **no
  off-switch**, a gap against
  [`config-flag-disableability.md`](../sops/development/config-flag-disableability.md).
- Status: ✅ **SUPERSEDED 2026-06-14 by the `perDimWorldInfo` master flag.**
  Rather than a granular per-mechanic `enablePerDimensionTime` toggle, the user
  chose a single MASTER switch — `perDimWorldInfo` (default true) — that gates
  the WHOLE per-dimension WorldInfo subsystem (weather + time + wrapper install).
  Shipped on `feature/postponed` after the `1.12 → feature/postponed` merge:
  `ARConfiguration.perDimWorldInfo`; `ARMixinPlugin` weave-gates all three
  WorldInfo mixins (`MixinWorldServerMulti` / `MixinWorldServer` / `MixinPlayerList`)
  on it; `PlanetWeatherManager.shouldWrap` + `isWeatherManaged` gate on it;
  `MixinWorldServer` runtime-gates on it; `WorldProviderPlanet.updateWeather`
  gates on it. `enableCustomPlanetWeather` is retained as a weather SUB-toggle
  (weather managed vs delegated, only when the master is on). Pinned by the
  updated `ARMixinPluginTest` (all three mixins gated; off-state regression
  guard). This closes the config-flag-disableability gap AND fixes the leak
  where `enableCustomPlanetWeather=false` accidentally un-wove the per-dim TIME
  mixin. **Conscious non-goal**: per-dim weather WITHOUT per-dim time (the
  granular split TASK-51 originally proposed) is not supported — the master is
  all-or-nothing for the subsystem.
- Created: 2026-06-14.
- Original activation trigger (now moot): when `feature/postponed` merges into
  `1.12`. The merge happened in the *reverse* direction first (1.12 →
  feature/postponed), which brought `MixinWorldServer` onto the same branch as
  `ARMixinPlugin`, enabling the master-flag implementation directly.

## Context

[[TASK-47]] gave AR planets a per-dimension clock (custom `ARDimensionWorldInfo`
owns `worldTime`/`worldTotalTime`; `MixinWorldServer` rounds the sleep-skip to
the planet's `rotationalPeriod`). To make beds work even when custom weather is
off, `PlanetWeatherManager.shouldWrap` **dropped** its old
`enableCustomPlanetWeather` gate:

```java
// shouldWrap — current (fix/various)
// NOTE: deliberately NOT gated by enableCustomPlanetWeather...
if (cfg == null) return false;
```

Consequence: the WorldInfo wrapper (and therefore per-dim time) is now installed
on **every** AR planet for **every** user, with no config to turn it off.
`enableCustomPlanetWeather` was the master switch before; it now governs
**weather only** (via `isWeatherManaged()` → the wrapper's `weatherManaged`
flag). The per-dim **time** mechanic has no equivalent flag.

Per the config-flag-disableability SOP, every introduced mechanic must return to
the vanilla baseline when turned off. Today per-dim time cannot be turned off.

## Why this is a real (not cosmetic) gap

- A user who hits a problem with planet clocks diverging from the overworld
  (time-based redstone, daylight sensors, mob-spawn cadence, mod-compat reading
  `WorldInfo.getWorldTime()` on a planet) has **no recourse** short of removing
  AR.
- The classic baseline (pre-TASK-47) was reachable via
  `enableCustomPlanetWeather=false` — that capability was silently narrowed.

## Approach (single source of truth + both gates per SOP)

1. **Config flag.** Add `ARConfiguration.enablePerDimensionTime` (default
   `true`, `PLANET` section) with a description mirroring
   `enableCustomPlanetWeather`. Default-true preserves the #66 fix out of the box.
2. **SSOT decision method.** `PlanetWeatherManager.isTimeManaged(world)` →
   `cfg != null && cfg.enablePerDimensionTime`, parallel to the existing
   `isWeatherManaged(world)`.
3. **Wrapper gate (consequences).** `ARDimensionWorldInfo` gains a `timeManaged`
   ctor flag (alongside `weatherManaged`). The four time methods
   (`get/setWorldTime`, `get/setWorldTotalTime`) serve from `PlanetWeatherState`
   when `true`, else delegate to the underlying `WorldInfo` (restores the
   classic overworld-shared, no-op-setter behaviour). Update both call sites
   (`PlanetWeatherManager.wrapWorldInfoIfNeeded` + the integration test helper).
4. **Wrap gate.** `shouldWrap` returns `false` when **neither** weather nor time
   is managed (`!isWeatherManaged(world) && !cfg.enablePerDimensionTime &&
   !cfg.forcePlanetWeatherWorldInfoWrapper`), so with both off no wrapper is
   installed at all — true vanilla `DerivedWorldInfo`.
5. **Mixin gate (Rule 4 — gate the weave, not the bytecode).** Once
   `ARMixinPlugin` lands from `feature/postponed`, add `MixinWorldServer` to its
   `shouldApply` list keyed on `enablePerDimensionTime` (read straight from the
   `.cfg`, fail-open, exactly as it already does for the weather mixins). With
   the flag off the sleep-rounding mixin is **never woven**. Keep a defensive
   runtime fall-through in the `@Redirect` (`setWorldTime(vanillaRounded)`) as
   belt-and-suspenders.

## Why wait for the merge (recap)

- `fix/various` has **no** `IMixinConfigPlugin`; the weather mixins there are
  woven unconditionally and gated at runtime via `weatherManaged`.
- `feature/postponed` has `ARMixinPlugin` gating `MixinWorldServerMulti` +
  `MixinPlayerList` on `enableCustomPlanetWeather` at weave time.
- The merge will already have to reconcile these two gating philosophies
  (runtime `weatherManaged` vs weave `ARMixinPlugin`). Folding the new
  `enablePerDimensionTime` weave-gate into that same reconciliation is one pass
  instead of two.

## Tests (both states; off-test is the regression guard)

- **ON** (default): existing `SleepWakeTimeTest`, `ARDimensionWorldInfoTest`
  (per-dim time owned by state), `PlanetBedSleepE2ETest` keep passing.
- **OFF** (new, must fail on revert):
  - Unit/integration: `ARDimensionWorldInfo` built with `timeManaged=false` →
    `get/setWorldTime` and `get/setWorldTotalTime` route to the delegate (mirror
    the existing `unmanagedWeatherDelegatesToVanilla` case).
  - `shouldWrap` returns `false` when both weather and time are disabled.
  - Mixin weave-skip: `ARMixinPlugin.shouldApply(false, "…MixinWorldServer")`
    is `false` (pure-function unit test, same shape as the weather-mixin pins).

## Dependencies

- **Requires:** `feature/postponed` → `1.12` merge (for `ARMixinPlugin`).
- **Relates to:** [[TASK-47]] (the feature being gated), [[TASK-48]] (per-dim
  GameRules — `doDaylightCycle`/`doWeatherCycle` are the natural companion
  flags; if that lands, reconcile the off-semantics so "time off" and
  "daylight-cycle off" don't fight).

## Completion checklist (when promoted)

- [ ] Flag added + documented in `ARConfiguration` (field + `config.get`).
- [ ] `isTimeManaged` SSOT; both wrapper call sites updated.
- [ ] `shouldWrap` skips wrapping when both mechanics are off.
- [ ] `MixinWorldServer` weave-gated via `ARMixinPlugin` + runtime fall-through.
- [ ] OFF-state pins added (wrapper delegation, shouldWrap, mixin weave-skip).
- [ ] All four suites green; pyramid counter regenerated per task-lifecycle.
