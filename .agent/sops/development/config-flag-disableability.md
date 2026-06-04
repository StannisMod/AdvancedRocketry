# SOP: Config flags must fully disable their mechanic

## Context

Read before adding or auditing any opt-in mechanic that ships behind a
config flag (weight, wear, weather, future systems). The project's stance
to players is that **every introduced mechanic can be turned off in the
config**. That stance was repeatedly false: the flag existed but the
mechanic leaked through some path the flag didn't guard. This SOP makes
"disableable" a real, tested contract.

## The contract

A mechanic's flag, when off, must return behaviour to the **classic /
vanilla baseline** — no residual effect, no path that still runs. "The
flag exists" is not the contract; "the flag fully disables the mechanic"
is.

## Rule 1 — Gate at a single source of truth

Put the gate in the one method every consumer already calls, not at each
call site. Re-deriving the check elsewhere is how leaks happen.

- *Example:* the TWR launch gate lives in `StatsRocket.canLaunch()`
  (returns `true` when `advancedWeightSystem` is off). Because
  `EntityRocket` calls `canLaunch()`, the launch path is fixed for free —
  no second check in `EntityRocket`.

See [`single-source-of-truth-gating.md`](./single-source-of-truth-gating.md).

## Rule 2 — Gate BOTH accrual and consequences

A mechanic usually has (a) state that accumulates and (b) consequences
read from that state. Gate **both**:

- *Wear:* consequences (thrust penalty, tank leak, seat block) were
  gated, but **accrual** (`StorageChunk.damageParts()`) was not — so a
  worn save kept advancing wear stages with the system "off". Gate the
  accrual entry point too.

## Rule 3 — Distinguish disabling the CALCULATION from the GATE

Turning off a *calculation* (e.g. fuel weight) is not the same as turning
off a *decision* that uses it. `getWeight()` was correctly gated, but the
**launch decision** built on it was not. Trace every consumer of the
mechanic, not just its core math.

## Rule 4 — Mixins are gated by NOT weaving them

A flag can't disable an already-woven mixin's bytecode. Gate the
**weave** via the host plugin so the mixin isn't applied when the flag is
off:

- `ARMixinPlugin.shouldApply(...)` (an `IMixinConfigPlugin`) skips the two
  weather mixins when `enableCustomPlanetWeather` is off. Read the flag
  directly from the `.cfg` (fail-open), because the config singleton isn't
  populated yet at coremod time. See
  [`mixin-coremod-dev-vs-prod.md`](./mixin-coremod-dev-vs-prod.md).
- A non-mixin class that mimics a mixin's effect still needs a normal
  runtime gate — e.g. `WorldProviderPlanet.updateWeather()` had to gate
  its custom cycle on the flag, not only on XML markers, or it kept
  clobbering the shared overworld weather while "disabled".

## Rule 5 — Test BOTH states; the off-test is a regression guard

Per [`testing-principles.md`](./testing-principles.md), pin the
**observable** contract in both modes:

- ON: the mechanic visibly acts (gate rejects, wear accrues, cycle
  suppresses).
- OFF: the mechanic is invisible (gate passes, wear stays 0, set weather
  survives a tick). **This assertion must fail if the fix is reverted** —
  that's what makes it a guard, not decoration.

### Harness gotcha: load-time vs runtime flags

Some effects are decided at **load time** and are sticky (e.g. the
`ARWeatherWorldInfo` wrapper is installed when a dimension is created;
flipping the flag at runtime later does not unwrap it). Order the test so
the dimension loads in the state you need, or set the flag in the config
file before boot. See
[`server-test-harness.md`](./server-test-harness.md).

## Prevention

- [ ] Gate at the single source of truth, not per call site.
- [ ] Both accrual and consequences gated.
- [ ] Every consumer of the mechanic traced, not just its core math.
- [ ] Mixin mechanics gated at weave (`IMixinConfigPlugin`), non-mixin
      mimics gated at runtime.
- [ ] A test pins OFF-behaviour and fails on revert.

## Related

- [`single-source-of-truth-gating.md`](./single-source-of-truth-gating.md),
  [`mixin-coremod-dev-vs-prod.md`](./mixin-coremod-dev-vs-prod.md),
  [`server-test-harness.md`](./server-test-harness.md),
  [`testing-principles.md`](./testing-principles.md).
