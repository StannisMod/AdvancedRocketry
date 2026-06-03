# TASK-49: Railgun silent fire-failure (issue #61)

## Ticket

- Source: dercodeKoenig/AdvancedRocketry#61 ("[BUG] Railgun does not work" —
  "Railgun just does not fire with a linker that has the cords of another
  railgun"). Reported 2025-07-15 against AR 1.12.2-2.1.8 / LibVulpes
  ARLIB-17-09-2024. No comments, no repro detail, no stacktrace.
- Status: 🟡 **In Progress — repro shipped 2026-06-02, fix pending.**
  Root cause isolated and characterized by tests; production fix not yet
  written.
- Created: 2026-06-02.

## Context

The railgun is **not a weapon** — it is a paired-railgun item TELEPORT: a
source railgun pulls a stack from its input port, dispatches it to a linked
destination railgun (same or another dim), and the destination's
`onReceiveCargo` deposits it in its output port. The `EntityItemAbducted`
that spawns is the in-flight visual, not a projectile.

Firing happens in `TileRailgun.attemptCargoTransfer`
(`src/main/java/zmaster587/advancedRocketry/tile/multiblock/TileRailgun.java:309`),
gated by `useEnergy` (`:290`). It returns `false` (no fire) unless ALL hold:

1. source input port has a stack ≥ `minStackTransferSize` (`:319`);
2. linker is set → valid dest pos + `dimId != INVALID_PLANET` (`:333`,`:221`);
3. destination dimension is loaded —
   `net.minecraftforge.common.DimensionManager.getWorld(dimId)` non-null (`:340`);
4. destination tile is a `TileRailgun` AND `canReceiveCargo` (dest has an
   output hatch with a free slot) (`:343`,`:366`);
5. planetary-system gate: same effective dim or
   `isTravelAnywhereInPlanetarySystem` (`:344`).

Every failure branch returns `false` **with zero player feedback** — the
defining defect. The reporter can't tell which gate failed.

## Root cause (confirmed by repro, 2026-06-02)

- **Same-dimension firing WORKS.** Two assembled railguns in one dim, linker
  programmed at the destination, item in the source input → fires; cargo
  leaves the source input and lands in the destination output. So the firing
  gate logic is NOT broken for the basic case.
- **The field failure is environmental + silent.** The most likely real cause
  is gate (3): the destination railgun is in a dimension that is not currently
  loaded (sender on planet A, receiver on planet B, player standing on A).
  Production resolves the destination via Forge's
  `DimensionManager.getWorld(destDim)`, which returns `null` for an unloaded
  dim. The railgun only chunk-loads its OWN chunk (`onLoad:252`), never the
  destination's → silent no-op. Confirmed: firing at an unloaded dim returns
  `fired=false`, `destLoaded=false`, and **cargo is preserved** (not lost).
- Other real failure modes (all silent): destination lacks an output hatch
  (or it is full) → `canReceiveCargo` false; redstone state not satisfied;
  insufficient RF/t (cross-planet shots are expensive); the linker cannot be
  re-targeted without a sneak-`resetPosition` first
  (`ItemLinker.applySettings` → `onLinkComplete` returns `false` on the
  railgun, a no-op).

Upstream: #61 is open, 0 comments, untouched; `TileRailgun` is byte-identical
across dercodeKoenig `1.12` and zmaster587 — no fix to pull.

## Shipped this task (repro / characterization)

- **Probe verb** `artest infra railgun-fire <srcDim> <sx sy sz>
  <destDim> <dx dy dz> <itemId> [count]` in `TestProbeCommand.java`:
  programs a libVulpes Linker at the destination, drops it in the source
  controller slot, loads the cargo into the source's first input port,
  reflectively invokes the private `attemptCargoTransfer()`, and reports
  `fired` / `linkerSet` / `srcInputRemaining` / `destLoaded` / `destIsRailgun`
  / `destMatched`. Dest resolution uses Forge `DimensionManager.getWorld`
  (not `server.getWorld`, which auto-inits the dim and would mask the
  unloaded-dest mode). New helper `countItemsInPortList`.
- **2 server tests** (`RailgunFiringContractTest`):
  - `railgunFiresCargoToLinkedRailgunInSameDimension` — same-dim shot fires;
    cargo moves input→output (positive contract / regression guard).
  - `railgunSilentlyFailsWhenDestinationDimensionUnloaded` — unloaded dest →
    silent no-op, cargo preserved (characterizes the #61 root-cause mode).
- **2 client e2e tests** (`RailgunCargoTransitE2ETest`, the mandatory
  player-truth guard per `bug-report-workflow.md`) — the same two contracts
  re-pinned with a REAL client connected (catches a teleport client/server
  desync the dedicated-server test is blind to): `cargoTransitsBetweenLinked
  RailgunsClientSide` + `railgunDoesNotFireToUnloadedDestinationClientSide`.
  Run on a dedicated `DISPLAY`/xvfb (`:100` on this box); `skipped=0` confirms
  the client actually connected.

All four green; testServer + testClient cache-busted per flake-diagnosis SOP.

## Fix plan (not yet implemented)

1. **Resolve/load the destination dimension on fire** so Station→Planet and
   Planet→Planet work regardless of player presence — either
   `server.getWorld(destDim)` (Forge auto-inits) plus a transient chunk-load
   of the destination, or a kept ticket. Then flip the cross-dim test's
   expectation to "fires".
2. **Player feedback** on each failure cause (other dim / unloaded / no output
   hatch / redstone / power) — turn the silent `false` into a clear message.

## Out of scope / notes

- Linker re-target UX (sneak-reset requirement) — separate, minor.
- A live in-world e2e (real `useEnergy` tick with power + redstone) is not
  covered; the probe drives `attemptCargoTransfer` directly to isolate the
  cargo/linker/planetary gate from the power/enabled/redstone gating.

## Dependencies

- Independent. Touches only `TestProbeCommand.java` + a new test file (repro);
  the fix (when done) will touch `TileRailgun.attemptCargoTransfer`.

## Bug ledger

Logged as Batch #2 entry #8 in `.agent/history/known-bugs-ledger.md`.
