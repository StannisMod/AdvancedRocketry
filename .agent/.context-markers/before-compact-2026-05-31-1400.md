# Context marker — pre-compact 2026-05-31 14:00

**Slug**: before-compact-2026-05-31-1400
**Branch**: `feature/tests`
**Trigger**: `/navigator:nav-compact` after mixin-verification + TASK-44 batch
**Predecessor**: `before-compact-2026-05-30-1410.md`

---

## Session arc

Three phases, all complete + pushed to origin:

### 1. Mixin verification (no code changes — confirmation only)
User asked: "did we actually fix the mixins? verify in testClient,
runClient, AND a clean production Forge env." Verified all three:
- **runClient** (`-Dmixin.debug=true`): `mixin.env.disableRefMap=true`
  on CLI; 4 mixins applied at boot (`MixinWorldSetBlockState`,
  `MixinEntityPlayerInventoryAccess`, `MixinEntityGravity` + 3 entity
  subclasses, `MixinEntityPlayerMPInventoryAccess`), 0 FATAL.
- **testClient** full suite (49 classes / 62 methods): 59 PASSED /
  1 sparse flake (`vacuumDrainsOxygenFromChestSubInventoryTank`,
  2/3 isolated — AtmosphereHandler tick race, not mixin) / 2 @Ignore.
- **Production**: built reobf jar, installed clean Forge
  1.12.2-14.23.5.2860 server (via installer) at `/tmp/prod-server2/`
  with AR + libVulpes 0.5.0 + `_mixinbooter-7.0.jar` (underscore
  prefix forces it to load BEFORE AR's coremod). **All 6 mixins
  applied** incl. `MixinWorldServerMulti`, `Done (5.829s)!`, 0 FATAL.
  `disableRefMap=false` in prod (refmap DOES the MCP→SRG translation
  there — exactly inverse of dev). Built libVulpes via init-script
  `/tmp/libvulpes-init.gradle` (works around dead ImmersiveEngineering
  `0.12-92-+` maven; pins cached `0.12-92-559`).

**Verdict: mixins work in all envs.** The TASK-43 fix
(`disableRefMap=true` in FG6 `runs.{client,server}`) is dev-only and
does NOT touch the reobf jar.

### 2. SOP — bash exit codes (commit `8fcb5d77`)
`.agent/sops/development/bash-exit-codes.md` — documents that
pgrep/pkill/grep/diff exit-1 = "empty result" not failure, + the
spurious exit-1 from the broken `nav_commit_reminder.py` PostToolUse
hook (missing file — fires after every Bash command all session).

### 3. TASK-44 — "convert all shallow → deep, one batch" (commit `a90ae0e3`)
**Shipped 4 real contracts + 1 mixin-CI gap** (all green + reruns):
- **F.4** pump drains Forge IFluidBlock (vanilla water never worked —
  not an IFluidBlock; old @Ignore misdiagnosed it). Ledger #7 added.
- **B** laser-drill MINING dispatch breaks column + yields drop
  (`infra laserdrill-mine` probe). Terraforming-mode deferred
  (duplicate of TASK-36 BiomeHandler + heavy planet-dim fixture).
- **C** area-gravity resets fallDistance IN-radius only — moved
  client→server, discriminating via 2 no-gravity armor stands;
  **found controller isn't machine-enabled by default** (old
  grounded-bot test was non-discriminating — vanilla masked it).
- **N** asteroid worldprovider generates fill blocks
  (`worldgen create-asteroid-dim` probe clones a planet's
  DimensionProperties → asteroid genType + explicit Forge
  registerDimension; registerDim's internal guard skipped it).
- **U** (mixin-CI) un-@Ignore'd `InventoryBypassRedirectE2ETest` via
  server-side `player open-chest` probe (`displayGUIChest` direct on
  TileEntity, bypassing both `bot.rightClickBlock` packet-flake AND
  vanilla `BlockChest.isBlocked`). 4/4 reruns green. Ledger #6 line
  resolved.

**New probe verbs** (test-only): `infra laserdrill-mine`,
`entity set-fall-distance`, `entity set-no-gravity` (+ fallDistance in
`entity info`), `player open-chest`, `worldgen create-asteroid-dim`.

**Dropped per SOP** (impl-only/unwired/wrong-framing): G, H, I, K, M,
and **T** (MixinWorldServerMulti — impl-only; weather isolation already
pinned by `WeatherClientSyncE2ETest`, mixin-vs-fallback is which-code-path).
**Already covered**: A/D/E/F.1/F.2/J/L (TASK-40), F.3
(`AtmosphereOxygenSmokeTest`).

## Meta-lesson (IMPORTANT for future audits)
The `2026-05-29-coverage-delta.md` audit was **stale** — written the
morning of 2026-05-29, BEFORE the same-day TASK-40a-e sweep that closed
most gaps. It inflated "17 gaps / 8 shallow subsystems" into a phantom.
Ground-truth reconciliation against the test tree + TASK-40 close-outs
reduced it to 4 real contracts. **Always reconcile a frozen audit
against current code before planning from it.** New audit:
`.agent/audits/2026-05-31-mixin-coverage-nuance.md`.

## Test landscape after batch
Full `testUnit + testIntegration + testServer`: **429/430 pass**. The
1 failure (`StationControllersTickContractTest.altitudeController...`)
**passes 3/3 isolated** → parallel-fork contention flake (same shape as
TASK-43 Shape A / TASK-16), NOT a regression. The new asteroid-worldgen
test is CPU-heavy and may aggravate fork contention — if flake frequency
rises, tag `AsteroidDimensionContainsAsteroidsTest` to lower fork concurrency.

## Bug ledger (current)
- #1 SatelliteRegistry.getNewSatellite null-instead-of-fallback (open, pinned).
- #2 EntityElevatorCapsule.setStandTime ignores arg (open, ledger-only).
- #3 TileStationGravityController redstoneControl not OFF on init (open, workaround pin).
- #4 ✅ FIXED (TASK-41 AccessorWorld).
- #5 pre-existing test failures (open, TASK-43).
- #6 ✅ FIXED (TASK-43 Phase 3) — InventoryBypass line ALSO resolved by TASK-44 (un-ignored).
- #7 pump doesn't drain vanilla water (IFluidBlock-only); open, ledger-only.

## Commits this session (pushed to dercodeKoenig/AdvancedRocketry feature/tests)
```
a90ae0e3 test: TASK-44 shallow→deep batch — 4 contracts + 5 probe verbs
8fcb5d77 docs: add SOP on bash exit codes that look like failures
```

## Open follow-ups
- **Broken hook**: `.claude/settings.json` PostToolUse references
  missing `nav_commit_reminder.py` → spurious "Exit code 1" after every
  Bash command. Offered to fix (remove/repoint); user hasn't decided.
- `/tmp/prod-server2/` (clean Forge server + world), `/tmp/libvulpes-init.gradle`,
  `/tmp/prod-server-mixin.log` left on disk — disposable.
- Uncommitted Navigator/harness auto-churn (graph.json, settings*, .nav-config,
  scheduled_tasks.lock) intentionally NOT committed.
