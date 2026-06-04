# SOP: Test fixtures catalog (`/artest fixture`)

## Context

Read before building a rocket or machine in a server test. The probe
already provides reusable fixtures; reverse-engineering the build flow
each time wastes effort and produces inconsistent, flaky setups. This is
the catalog and the canonical flow. (Verbs evolve — confirm against
`TestProbeCommand` `handleFixture` if something here is missing.)

## Canonical rocket flow

```
/artest fixture rocket <dim> <x> <y> <z> [variant]   → {"ok":true,"builderPos":[bx,by,bz]}
/artest rocket assemble <dim> <bx> <by> <bz>         → {"ok":true,"entityId":…}
/artest rocket list <dim>                            → {"rockets":[{"id":…}]}   (take the last)
/artest rocket info <id>                             → weight_no_fuel, thrust, breakingProb,
                                                        fuelTankCount, seatCount, drillingPower,
                                                        flightMode, motionX/Y/Z, errorMessage, …
```

Pre-clear the build area first under parallel load: `chunk warmup` the
region then `fill … minecraft:air` (see `WearSystemTest.preClear`). Use a
distinct `BASE_X/Z` per test for position isolation
([`server-test-harness.md`](./server-test-harness.md)).

## Rocket variants

| Variant | What it gives |
|---|---|
| `simple` | full valid rocket: 2 engines, 6 fuel tanks, guidance, seat |
| `invalid-no-engine` / `invalid-no-fuel-tank` / `invalid-no-seat` / `invalid-no-guidance` | negative-path scans (assembly rejects with a reason) |
| `with-cargo` / `with-fluid-cargo` | cargo item / fluid storage tiles |
| `with-nuclear-stack` / `with-nuclear-misplaced` | nuclear core cohesion (thrust>0 vs NOENGINES) |
| `with-mining-drill` | `stats.drillingPower > 0` chain |
| `uv-rocket` | UV-assembler output entity (`EntityStationDeployedRocket`) |

## Machines

- `/artest fixture machine <type>` builds the multiblock; wildcard
  structures (e.g. `precision-assembler`, `arc-furnace`) use a
  hatch/overlay + structure-block filler for `'*'` cells (TASK-26).
- Recipe-cycle helpers live in `MachineRecipeEndToEndKit` (input-drain
  pin + adaptive force-tick budget).

## Driving wear / repair / weather on a fixture

- Wear: `wear set <dim> x y z <stage>` on a placed part, or
  `infra inject-broken-part <id> <stage>` into rocket storage; read back
  via `wear rocket-status <id>` / `rocket info` `breakingProb`. Drive
  accrual with `wear damage-parts <id> [n]`.
- Weather: `weather set-marker <dim> <rain> <thunder>` +
  `weather tick-provider <dim> [n]` + `weather get <dim>`.

## Prevention

- [ ] Reused an existing variant instead of hand-placing blocks.
- [ ] Pre-cleared + position-isolated the build site.
- [ ] Read IDs fresh from `rocket list`, not hard-coded.

## Related

- [`artest-probe-authoring.md`](./artest-probe-authoring.md),
  [`server-test-harness.md`](./server-test-harness.md).
