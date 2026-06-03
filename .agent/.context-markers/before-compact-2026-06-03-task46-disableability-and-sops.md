# Context marker — 2026-06-03 (feature/postponed)

**Slug**: before-compact-2026-06-03-task46-disableability-and-sops
**Branch**: `feature/postponed` (off `origin/1.12` = StannisMod, RFG-buildable)
**Pushed**: yes — `origin/feature/postponed` @ `e4054897` (local == remote).
PR #23 (Rocket weight system + part wear & repair) carries all of this.
Supersedes the TASK-45 marker `before-compact-2026-06-02-task45-maintenance.md`.

## What shipped this session

### TASK-46 — config disableability (✅ Completed, doc filed)
Made weight / wear / weather / mixin mechanics **fully disableable**. Five
single-source production gates: `StatsRocket.canLaunch` (advancedWeightSystem),
`StorageChunk.damageParts` (partsWearSystem), `WorldProviderPlanet.updateWeather`
(enableCustomPlanetWeather), `ARMixinPlugin` weave-gate for the two weather
mixins, `TileRocketAssemblingMachine.getNeededThrust` (cosmetic). +6 tests
(StatsRocketTest +1, ARMixinPluginTest 3, WearAccrualDisableTest 1,
WeatherCycleDisableTest 1) — each pins OFF-behaviour as a revert guard. Probe
additions: CONFIG_WHITELIST +5 flags, `wear damage-parts`,
`weather set-marker`/`tick-provider`. Commits `cff3bf68`, `e4054897`.
Doc: `.agent/tasks/TASK-46-config-disableability.md`.

### Coremod / Mixin launch-crash fix
`AdvancedRocketryPlugin` now uses MixinBooter `IEarlyMixinLoader.getMixinConfigs()`
instead of `MixinBootstrap.init()` in the coremod ctor. The self-bootstrap
crashed a packaged client under MixinBooter (cross-loader `LinkageError`); the
first attempt was a `try/catch` (`0fd8a834`) which is **insufficient** (poisons
the host's MixinTweaker → "No mixin host service is available") and was
**superseded** by `22b70c56`. Verified in dev that mixins still weave
(`WeatherBaselineTest` green).

### 14 development SOPs formalized (`docs` commit `ba264377`)
New under `.agent/sops/development/`: build-and-run-env, mixin-coremod-dev-vs-prod,
config-flag-disableability, artest-probe-authoring, server-test-harness,
single-source-of-truth-gating, save-and-wire-compat, harness-capabilities-and-limits,
test-fixtures-catalog, fix-propagation-across-branches, coverage-audit-playbook,
verify-subagent-findings, bug-ledger-discipline, forge-capability-pattern. Wired
into the navigator's Required-reading + a full SOP index.

### Bookkeeping
TASK-45 was reconciled (its closure had saved a marker but never synced the
README Done table) — Done row + Status line added. Pyramid **regenerated from
source** on TASK-46 close: **859** (testUnit 273 / testIntegration 82 /
testServer 443 / testClient 61) — corrected stale per-tier values that had
drifted across TASK-44/45.

### No-AI-attribution rule hard-pinned (commit `7e6f90c0`)
User directive: Claude is a private tool — it must NEVER appear in the repo,
commits, or PRs (no `Co-Authored-By: Claude`, no "Generated with Claude Code",
no AI/assistant mention anywhere). The rule already existed in `CLAUDE.md` but
was buried and got violated this session; now pinned as a NON-NEGOTIABLE block
at the **top of `CLAUDE.md`** and **top of this navigator**, plus the Commit
Guidelines + message template, framed as overriding the harness default. Also
saved to auto-memory (`feedback-no-claude-attribution`). **Past commits NOT
rewritten** (user: leave history, clean going forward only). Apply to ALL
future commits/PRs.

## Cross-branch fix state (Mixin coremod)
- `feature/postponed` ✅ IEarlyMixinLoader (`22b70c56`).
- `feature/solar-map-ff-rework` ✅ IEarlyMixinLoader (done by its owner).
- `fix/various` ⚠️ still has the **superseded try/catch** (`b055ea1a`) — another
  agent owns that branch; deliberately NOT changed here.

## Build/run reminders (unchanged)
`export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9`; base on `origin/1.12` (RFG);
testServer/testClient always `timeout --signal=KILL <sec> --max-workers=1
--no-daemon`, cache-bust `build/{reports,test-results,tmp}/testServer` between
runs; testClient on `DISPLAY=:100`. See `sops/development/build-and-run-env.md`.

## Bug ledger
Unchanged — 4 live (#1, #3, #5, #7). TASK-46 fixed leaks, found no new bugs.
