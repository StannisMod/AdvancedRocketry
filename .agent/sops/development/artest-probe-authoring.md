# SOP: Authoring `/artest` probes

## Context

Read before adding or modifying a verb in `TestProbeCommand` (the
test-only `/artest` command, active under
`-Dadvancedrocketry.tests=true`). Probes are how server/client tests
drive and observe production paths without a player. Done wrong they
introduce flake or test impl details; this SOP captures the patterns that
work.

## The probe response is a contract; class names are not

Every verb returns a single-line JSON envelope `{"ok":true, ...}` (or
`{"error":"...","...":...}`). Tests assert on the **envelope and its
named fields** — those are the contract. Do **not** make tests assert on
incidental strings like a tile's class name in the response; that breaks
the moment the probe stops emitting it (a real past flake). Before adding
`response.contains("XYZ")` to a wait loop, run the probe once and read an
actual response.

## Probes run on the server thread — budget wall time

Anything a probe does that takes wall time is time the natural tick loop
isn't running; anything that then releases is followed by a tick burst
that can race a state machine.

- **Bound every wait**: any `Thread.sleep`/poll has a documented ceiling
  and early-exit. **12 s is the absolute max** for one probe call.
- **Pre-load the minimum chunks**: a 5×5 pre-load (~25 chunk gens) caused
  a regression. Single `place` → 1 chunk; multiblock → 3×3; rocket-style
  multi-tile fixture → none (the tick-burst risk outweighs chunk-load
  risk).

(Full rationale in [`flake-diagnosis.md`](./flake-diagnosis.md), rules
P1–P4.)

## Driving gated work: expose, don't reflect into private

Production often runs work only every `N` ticks
(`worldTime % 20 == 0`), which a force-tick can't reliably satisfy.
**Refactor production** to extract the gated body into a public
`onIntermittentDoX()` and call THAT from the probe; `update()` keeps the
gate. This is an observable-behaviour-preserving refactor, not a test
hack (done for `TileForceFieldProjector`, the service-station
perform-function, weather `tick-provider`). Prefer this over reflecting
into private members; reflection is a last resort and brittle.

## Setting config from a server test

The test JVM and the server JVM are separate, so mutating
`ARConfiguration` fields from the test does nothing to the server. Three
ways, by flag type:

| Flag is read… | How to set it from a test |
|---|---|
| at runtime, every use | `/artest config set <key> <value>` — but the key must be in `CONFIG_WHITELIST` (add it there; whitelist-guarded so it's test-only) |
| at world/dimension load (sticky) | write the `.cfg` / `planetDefs.xml` into `workDir` **before** `RealDedicatedServerHarness.startWith(workDir)` |
| in a pure unit test | mutate `ARConfiguration.getCurrentConfig().<field>` directly in a `try/finally` that restores it |

See [`server-test-harness.md`](./server-test-harness.md) for which
harness and which method.

## Observability without new fields

Reuse existing un-gated getters as observables. e.g. wear accrual is
visible through `getBreakingProbability()` / `getWornTanks()` (which read
the real stage and aren't themselves flag-gated), so a `damage-parts`
probe + reading breaking probability pins accrual without a bespoke
stage-readout field.

## Reuse the fixture catalog

Don't reinvent rocket/machine construction — use
`/artest fixture rocket <variant>` and the assemble→list→info flow. See
[`test-fixtures-catalog.md`](./test-fixtures-catalog.md).

## Prevention

- [ ] Response asserts on named JSON fields, not incidental class names.
- [ ] Every wait bounded ≤ 12 s with an early-exit.
- [ ] Smallest necessary chunk pre-load.
- [ ] Gated work driven via a public `onIntermittentX()`, not private
      reflection.
- [ ] New config key added to `CONFIG_WHITELIST` if a server test sets
      it at runtime.

## Related

- [`flake-diagnosis.md`](./flake-diagnosis.md),
  [`server-test-harness.md`](./server-test-harness.md),
  [`test-fixtures-catalog.md`](./test-fixtures-catalog.md),
  [`testing-principles.md`](./testing-principles.md).
