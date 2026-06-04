# SOP: What the headless harness can and cannot verify

## Context

Read before claiming a change is "verified" by tests, and before
deferring work as "covered". The dedicated-server / headless-client
harness is powerful but has hard blind spots. Calling something green
when the harness physically cannot observe it is how a broken GUI or an
unfired consequence ships.

## What the harness CAN verify

- Server-side logic, tile/entity ticks, NBT round-trips, registry/recipe
  wiring, packet payloads, capability data — via `/artest` probes.
- Client *behaviour* that isn't pixel-level: GUI container slot wiring,
  key-bound actions, packet sync on join/teleport — under
  `DISPLAY=:100` testClient.

## What the harness CANNOT verify (needs a human eyeball)

- **GUI pixel layout / rendering.** There is no GPU; you can assert a slot
  is reachable and a capability is exposed, but not that the background,
  positions, or overlaps look right. The TASK-45 service-station GUI
  relayout (MODULARNOINV→MODULAR) was done **blind** and is explicitly
  flagged as needing a `runClient` look. Label such work "logic verified,
  visual unverified", never just "done".
- **Stochastic / player-gated launch consequences.** A real
  explosion-from-leak or a crewed-seat block needs a launched rocket with
  a passenger and a random roll — not harness-feasible. Pin the **data**
  feeding the decision instead (e.g. `getWornTanks()` /
  `hasCriticallyWornSeat()` are pinned; the actual KABOOM is not).
- **Cross-session worldgen determinism.** Within-session determinism is
  tested; same-seed-across-reboot histograms are a conscious non-goal
  (see `tasks/README.md`). Don't assert it.
- **Anything requiring a real GPU/driver path** — LWJGL features beyond
  what software GL on Xvfb provides.

## How to be honest about a blind spot

1. Pin everything the harness *can* see (the data, the wiring, the
   contract surface).
2. Explicitly record what remains visually/behaviourally unverified — in
   the TASK file's "NOT done" section and the EOD marker, not as a silent
   gap.
3. If it matters, do the eyeball pass in `runClient` /
   `DISPLAY=:100 testClient` and say so.

## Litmus

> "Can a probe or a non-pixel client assertion actually observe this?"
> If no, it is not verified by the harness — say so.

## Related

- [`build-and-run-env.md`](./build-and-run-env.md),
  [`artest-probe-authoring.md`](./artest-probe-authoring.md),
  [`testing-principles.md`](./testing-principles.md).
