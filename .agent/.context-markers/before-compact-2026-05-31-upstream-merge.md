# Context marker — pre-compact 2026-05-31 (upstream PR merge)

**Slug**: before-compact-2026-05-31-upstream-merge
**Branch**: `feature/upstream` (NEW — branched from clean `1.12` @ 280dd59b)
**Trigger**: `/navigator:nav-compact` after completing selective upstream merge
**Pushed**: yes — `origin/feature/upstream` (18 commits)

---

## What this session did

Selectively merged upstream PR `kaduvill/1.12` (kaduvill→dercodeKoenig, 584
commits) onto a NEW branch `feature/upstream` (ours, off clean `1.12`), then
re-applied our test/mixin/weather/bugfix work on top. Source of analysis:
the uploaded audit `upstreampr70analysisru.md`.

**Key user decisions (locked):**
- Branch `feature/upstream` from our `1.12` (NOT from kaduvill); selectively
  merge PR features by **cluster**, taking **all "good"** (no exclusions).
- Build: migrate to **RetroFuturaGradle** (RFG) — confirmed much better than
  FG6/FancyGradle for 1.12.2.
- Coremod: keep **our Mixin platform**; PlusTiC compat → **dropped entirely**
  (not @Pseudo) + documented in README. No pure ASM remains.
- Weather: keep **our `world/weather/**` platform**, port PR's
  `usesCustomWorldInfo()` flag + nextInt clamps onto it.
- Commits: **autonomous** (I commit each phase myself, agreed message format).
- Tests: wire **unit + integration only** now; server/client harness deferred
  (see R5). Framework via **mavenLocal** (composite build incompatible w/ RFG).
- `runClient` can't run in sandbox (no OpenGL); verified via `runServer`.

---

## Commit chain on feature/upstream (280dd59b..HEAD, 18 commits)

P0 `127b1272` build: migrate to RetroFuturaGradle 2.0.2
P1 `ffbb6cb1` drop HookLib coremod, adopt PR ASM transformers
P2 `78aa409a` replace WorldCommand monolith with ARCommandRoot tree
P3 `00930e18` remove cable subsystem, add wireless network backend
P4 `78966342` adopt PR tile/block/inventory rewrites
P5 `504282bf` adopt PR item rewrites
P6 `e348dc96` adopt PR entity/mission/satellite/atmosphere rewrites
P7 `086ea82e` adopt PR world/dimension/worldgen rewrites
P8 `d7312ead` adopt PR integrations (JEI/TheOneProbe/Waila)
P9 `4eab83d2` adopt PR client/render changes
P10 `b5b2551f` adopt PR API/config/main class changes
P11 `72a2ca58` adopt PR resources, lang, models, docs
R1 `877d1495` restore Mixin platform over PR ASM coremod
R2 `ae99f63e` per-dimension weather on Mixin platform, port PR flag
R3 `c980c4ff` reapply NBT key/attach bugs not fixed upstream (#4/#5/#8)
PlusTiC `6a0dd09b` drop PlusTiC Portly rocket ASM compat
R4 `03cde8b6` import unit + integration suites on RFG

Production tree (src/main/java) is IDENTICAL to PR except our `gradle.properties`
(mixin_package + use_mixins=true). Verify with:
`git diff --name-only feature/upstream..kaduvill/1.12 -- src/main/java` → empty.

---

## CRITICAL environment facts (needed to build/test)

- **RFG 2.0.2 requires JDK 25 to RUN Gradle** (gradle 9.2.1). Downloaded to
  `~/jdks/jdk-25.0.3+9`. The mod itself COMPILES on Java 8 toolchain (auto).
  → ALWAYS build/test with `export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9`.
- **After editing the access transformer** (`src/main/resources/advancedrocketry_at.cfg`)
  RFG caches the AT-applied decompiled MC in `build/rfg`. Must run
  `./gradlew clean` (or wipe build/rfg) for AT changes to take effect — task
  chain shows `applyJST/deobfuscateMergedJarToSrg SKIPPED` otherwise.
- AR's own AT now = worldInfo only; base wideners (Entity.*, NBTTagCompound.*)
  come from libVulpes dep AT via RFG `useDependencyAccessTransformers=true`.
- `kaduvill` remote added: `git fetch kaduvill 1.12`. merge-base = 280dd59b.
- ForgeTestFramework: `/workspace/ForgeTestFramework` (RFG 1.4.0 + gradle 8.8,
  runs on JDK 17). Published to mavenLocal via its own gradlew + JDK17:
  `cd /workspace/ForgeTestFramework && JAVA_HOME=temurin-17 ./gradlew publishToMavenLocal`.
  Coordinate: `com.github.stannismod.forge:forge-test-framework:0.4.2:dev`.

## Build / test commands (with JDK 25 launcher)
```
export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileJava --console=plain --no-daemon          # main compile
./gradlew testUnit testIntegration --console=plain --no-daemon   # tests (green)
./gradlew runServer --console=plain --no-daemon            # headless verify (eula set in run/)
```

---

## Verification done
- compileJava: GREEN (full PR tree + our platform).
- runServer: `Done (7.668s)`, 10 mods, ZERO mixin/AR errors. (4 ERROR lines = benign FML dev noise.)
- runClient: crashes at `LWJGLException: No modes available` — Xvfb :99 has no
  OpenGL. Mod/coremod/MixinBooter load fine before the GL crash. NOT a mod bug.
- testUnit (35 classes) + testIntegration (9 classes): GREEN.

## Test reconciliation done in R4 (tests adapted to PR API)
- DimensionPropertiesTest / XMLPlanetLoaderTest: weather fields now private →
  use getters/setters.
- FuelRegistryTest: PR fixed inverted add return (now true on new) — flipped assert.
- MissionResourceCollectionContractTest: default mission now NBT-serialises (no throw).
- geode getter test: PR fixed getGeodeMultiplier (was returning volcano) — assert 2.0.
- Satellite display-name contract moved unit→integration (getName uses
  LibVulpes.proxy); added `ScanningSatelliteNameContractTest`; bootstrap sets
  `LibVulpes.proxy` in MinecraftBootstrap.
- Dropped 25 orphaned cable tests (2 files).

## Bug ledger status (production)
- #4 SpaceStationObject autoLand/occupied — FIXED (R3).
- #5 ItemSpaceElevatorChip removeTag positions→list — FIXED (R3).
- #8 ItemPlanetIdentificationChip INVALID_PLANET NBT attach — FIXED (R3).
- #9 SatelliteRegistry.getNewSatellite returns null (not SatelliteDefunct) —
  STILL LIVE, documented; pinned by `SatelliteRegistryFallbackTest`
  (_documentsKnownBug, unit, passing).

---

## OPEN WORK — task R5 (harness under RFG)

`testServer` (132 tests) + `testClient` (26 tests) NOT yet brought/wired.
Blocker: feature/tests harness is FG6-internals-coupled via reflection
(`resolveFg6RunConfig` → `RunConfigGenerator`/`MinecraftRunTask`, launcher
`net.minecraftforge.legacydev.MainServer/MainClient`, force legacydev 0.2.4.1,
`runServer.getClasspath()` reflection). RFG has NONE of these — uses GradleStart.
Must rewrite the harness env/classpath/launcher resolution for RFG run-tasks.
Also under R5: switch 29 WorldCommand tests (string `exec("ar ...")` surface) to
ARCommandRoot, bring `command/test/TestProbeCommand*` + register
`TestProbeCommandRegistration.registerIfTestMode` in AdvancedRocketry.java,
add #4 server-tier contract test (`SpaceStationPadPersistenceTest`).
testClient can't run headless (no GL) but should run on GPU CI.

## Immediate next-step options offered to user (not yet chosen)
1. Open PR (https://github.com/dercodeKoenig/AdvancedRocketry/pull/new/feature/upstream)
2. Start R5 (harness rewrite under RFG)

## Housekeeping
- Config noise (`.agent`, `.claude`) from feature/tests is STASHED:
  `git stash list` → "config-noise feature/tests". Restore when back on feature/tests.
- `.agent/.nav-config.json` escalate_threshold was bumped 20→60 mid earlier
  session (uncommitted, in that stash).
