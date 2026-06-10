# TASK-47: Per-dimension time + working beds on planets (issue #66)

## Ticket

- Source: dercodeKoenig/AdvancedRocketry#66 ("Beds do not work on planets
  with modified day-night cycle") — sleeping on an AR planet skips no time.
- Status: ✅ **Shipped 2026-06-02.** Per-dim time + dawn-rounding mixin
  implemented; `ARWeatherWorldInfo` renamed to `ARDimensionWorldInfo`. unit +
  integration green; server weather/wiring suites green (mixin applies,
  decoupling no regression). Live "bot sleeps in a bed → time advances to
  dawn" e2e: ✅ covered 2026-06-10 by `PlanetBedSleepE2ETest` (framework
  `interact_block` capability landed with the vendored testframework/), with
  a red-proof against vanilla 24000-rounding.
- Created: 2026-06-02.

## Root cause (confirmed against decompiled MC 1.12.2)

- `WorldServer.tick()` performs the sleep skip as
  `long i = getWorldTime() + 24000L; setWorldTime(i - i % 24000L)`
  (lines 196-204), then `wakeAllPlayers()`.
- AR planets are `WorldServerMulti` whose `worldInfo` is a
  `DerivedWorldInfo` (or our `ARWeatherWorldInfo`). **`DerivedWorldInfo.setWorldTime`
  is an empty no-op** (lines 187-189), and `ARWeatherWorldInfo` does not
  override it while `getWorldTime` delegates to the overworld. So derived
  worlds do not own the clock — the sleep skip is silently swallowed and
  **time never advances** → exactly the reported "no time is skipped".
- Secondary: planets render day/night from `rotationalPeriod`
  (`WorldProviderPlanet.calculateCelestialAngle`), and
  `rotationalPeriod = (1/gravitationalMultiplier)^3 * 24000`
  (`DimensionManager:340`) ≠ 24000 for almost every planet. So even when
  time does advance, vanilla's 24000-rounding does not land on the planet's
  dawn (`worldTime % rotationalPeriod == 0`).

This is why the reporter could only repro with a modified day-night cycle,
and why removing SleepingOverhaul (which has its own bed path) exposed the
vanilla path where AR's gap lives.

## Design — per-dimension time, in the spirit of async weather

Each dimension owns its own clock and sleeps independently; nothing is
pushed into the overworld. Vanilla already supports this end-to-end:
`areAllPlayersAsleep()` is per-world (WorldServer:318, requires **all**
non-spectator players in that dim — note: the "percentage asleep" rule is
1.13+, not 1.12.2), and `MinecraftServer:821` sends `SPacketTimeUpdate`
**per dimension** every 20 ticks using that world's `getWorldTime()`. So a
per-dim clock renders and syncs correctly with no extra plumbing.

Two clean concerns on two layers:

1. **Per-dim time OWNERSHIP — in the custom WorldInfo.**
   `ARWeatherWorldInfo` (rename to `ARDimensionWorldInfo` — it is no longer
   weather-only) becomes the faithful owner of `worldTime` and
   `worldTotalTime`:
   - `getWorldTime`/`setWorldTime`/`getWorldTotalTime`/`setWorldTotalTime`
     read/write per-dim state in `PlanetWeatherState`/`PlanetWeatherSavedData`
     (mirror the existing weather fields, incl. NBT).
   - **No business logic in the setters** — `setWorldTime(long)` just stores
     the value. (We explicitly rejected detecting "is this a sleep skip"
     inside `setWorldTime`: that violates the method contract.)
   - The planet's own `WorldServer.tick` `+1` increment now advances its own
     clock; the sleep skip now actually writes per-dim time.
   - **Seed** the per-dim time from the delegate's current `getWorldTime()`
     on first wrap so existing saves don't visibly jump.

2. **Dawn rounding — at the sleep site, via a mixin.**
   The "24000" assumption and the knowledge that "this is a sleep skip" live
   in `WorldServer.tick`. New `MixinWorldServer` with an `@Redirect` on the
   `setWorldTime` invoke inside the sleep block: for `IPlanetaryProvider`
   dims, round to the dim's `rotationalPeriod` instead of 24000:
   `cur = getWorldTime(); next = cur + rp; setWorldTime(next - next % rp)`
   (→ `worldTime % rp == 0` = planetary dawn). Non-AR worlds keep vanilla
   behaviour. This is unambiguous (one call-site), so `/time` and the `+1`
   increment flow through untouched and are stored exactly.

### Wrapper installation (decoupled from weather)
Install the custom WorldInfo on **all** AR planets, independent of
`enableCustomPlanetWeather` (gate only the *weather* behaviour by that
config internally). Otherwise per-dim time / working beds would require
custom weather to be on. Touch `PlanetWeatherManager.shouldWrap` /
`wrapWorldInfoIfNeeded` (pass `dimId` into the ctor, currently line 168).

## Files to touch

- `world/weather/PlanetWeatherState.java` — add `worldTime` + `worldTotalTime`
  (long) fields, getters/setters, NBT read/write.
- `world/weather/ARWeatherWorldInfo.java` → rename `ARDimensionWorldInfo`;
  faithful per-dim time accessors; `dimId` ctor param; seed-from-delegate;
  static `computeSleepWakeTime(long current, int rotationalPeriod)` helper
  (pure, for unit tests).
- `world/weather/PlanetWeatherManager.java` — pass `dimId`; decouple
  `shouldWrap` from `enableCustomPlanetWeather`.
- `mixin/MixinWorldServer.java` (new) + `mixins.advancedrocketry.json` —
  `@Redirect` sleep-block `setWorldTime`, round to `rotationalPeriod` for AR
  dims. Refmap-in-dev already handled (`mixin.env.disableRefMap=true`,
  ledger #6).
- Rename references across the codebase + tests.

## Test plan (sleep AND weather)

- **unit**: `computeSleepWakeTime` (rp=24000 ≡ vanilla; rp=13888/46875/128000
  → `result % rp == 0`, `> current`, jump `< 2*rp`; already-at-dawn case);
  `PlanetWeatherState` worldTime/totalTime NBT round-trip.
- **integration** (`ARWeatherWorldInfoTest`, rename + invert): `getWorldTime`
  now returns per-dim state (was: delegate); `setWorldTime(+1)` advances
  per-dim, does not touch delegate; first wrap seeds from delegate; weather
  delegation unchanged (regression guard).
- **server** (testServer): independence — sleep/skip on AR dim A does not
  change dim B or overworld, and overworld sleep does not change planets;
  dawn rounding lands `worldTime % rp == 0`; per-dim time survives reload.
  Needs probe verbs (read per-dim worldTime; drive the sleep-skip path).
- Existing weather suites (`PerDimensionWeatherIsolationTest`,
  `WeatherBaselineTest`, `WeatherPersistenceTest`) must stay green.

## Decisions locked (2026-06-02)

1. Install wrapper on all AR planets, decoupled from the weather toggle. ✅
2. `worldTotalTime` is also per-dim (not just `worldTime`). ✅
3. Seed per-dim time from current shared time on first wrap. ✅
4. Rename `ARWeatherWorldInfo` → `ARDimensionWorldInfo`. ✅
5. Dawn rounding lives in a `WorldServer` sleep-site mixin, NOT in
   `setWorldTime` (keep the setter contract clean). ✅

## Out of scope / follow-up

- **Per-dim GameRules.** Both the `+1` increment and the sleep skip are still
  gated by `doDaylightCycle` read from the **shared** overworld GameRules
  (`getGameRulesInstance` delegates). So `/gamerule doDaylightCycle false`
  freezes every planet. Truly independent day/night/weather needs per-dim
  GameRules — a separate, larger task. See research note below.
- Per-dim **spawn point** and **difficulty** are likewise delegated to the
  overworld by `DerivedWorldInfo` (spawn setters are no-ops). Candidates for
  the same per-dim treatment; not in this task.
- Optional "percentage of players asleep" rule (1.13+ style) — a feature, not
  part of this fix.

## Research note — what else vanilla delegates to overworld but is ideologically per-dim

From a full read of `DerivedWorldInfo`: GameRules (sharpest — couples to this
fix), spawn point, difficulty, terrain type (`WorldProviderPlanet.init` even
calls `setTerrainType` which is a no-op on the derived info), and game type.
Weather is the precedent AR already fixed via the custom WorldInfo; time is
this task; the rest are deliberately deferred.
