# TASK-45: `<oreGen>` clumpSize/chancePerChunk clamp to 1 makes "disable ore" impossible

## Ticket

- Source: discovered 2026-06-01 while adding the issue #73 oregen
  write/read round-trip regression test
  (`integration/XMLPlanetLoaderTest.oreGenPropertiesSurviveWriteReadRoundTrip`).
  Issue dercodeKoenig/AdvancedRocketry#73 reporter expected setting vein
  size / number of veins to **zero** to disable an ore on a planet.
- Status: 🟡 **Backlog — not started.** Analysis only; no production
  change made yet.
- Created: 2026-06-01.

## Context

`XMLOreLoader.loadOre` (src/main/java/zmaster587/advancedRocketry/util/XMLOreLoader.java)
clamps two `<ore>` attributes to a **minimum of 1**:

- `clumpSize`   → `MathHelper.clamp(parseInt(...), 1, 0xFF)` (line ~105)
- `chancePerChunk` → `MathHelper.clamp(parseInt(...), 1, 0xFF)` (line ~121)

Consequences for a player editing `planetDefs.xml`:

1. Writing `clumpSize="0"` or `chancePerChunk="0"` to switch an ore
   **off** silently becomes `1` — the ore still generates. This is very
   likely the behaviour the #73 reporter hit ("set all vein sizes and
   number of veins to zero ... continues to spawn them").
2. There is also no way to disable an ore by supplying an **empty**
   `<oreGen>`: `loadOre` returns `null` when it parses zero `<ore>`
   entries, and `DimensionProperties.getOreGenProperties` then falls
   back to the **global** pressure/temp default
   (`OreGenProperties.getOresForPressure(...)`, DimensionProperties.java
   ~414-416). So an empty/zeroed config does not suppress generation —
   it re-enables the global default.

Net: the only working way to "restrict" ores today is to list exactly
the ores you want per planet (a non-empty `<oreGen>` overrides the
global fallback). You cannot express "this ore: none" per-entry.

## Why it matters

The config surface implies per-ore tuning down to zero, but the floor
clamp + null-means-global fallback make "off" inexpressible. This is a
real usability/contract gap, not just impl trivia, because it produces
player-visible behaviour that contradicts the config.

## Approach options (pick at implement time)

1. **Allow 0 as "disabled" per entry.** Change the clamp floor to 0 for
   `clumpSize`/`chancePerChunk`; have the geode/ore generator skip
   entries with a 0 count/clump. Smallest change, but touches the
   generation loop (`MapGenGeode` / ore feature) to honour 0.
2. **Sentinel empty-but-present `<oreGen>` = "no ore on this planet".**
   Distinguish "no `<oreGen>` element" (use global default) from
   "present but empty `<oreGen>`" (generate nothing). Requires
   `readPlanetFromNode` to set a non-null empty `OreGenProperties`
   instead of leaving `oreProperties` null, and `getOreGenProperties`
   to return it rather than the global fallback.
3. **Document-only.** If the maintainer considers per-planet ore
   restriction to be "list what you want" by design, document that
   `clumpSize`/`chancePerChunk` floor at 1 and that empty `<oreGen>`
   falls through to global — and close as a non-goal.

## Dependencies

- Independent. Does NOT block the #73 round-trip regression test
  (already shipped + green) or the #76/#77 work.
- If implemented, add a coverage pin: an `<oreGen>` entry whose count is
  meant to disable generates nothing (server-tier worldgen probe).

## Notes

- The 2019-origin "oregen doesn't stick to worldsave" bug (#73) is a
  **separate** issue and is already fixed in the `1.12` base (kaduvill,
  fully merged). This task is only about the *clamp/disable* semantics
  surfaced alongside it.
