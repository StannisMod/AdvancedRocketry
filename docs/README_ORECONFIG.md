# Advanced Rocketry `oreConfig.xml` Reference

This document explains how `oreConfig.xml` is structured and how it behaves.

Path:

`config/advRocketry/oreConfig.xml`


**Template** found here [`TEMPLATE_oreconfig.xml`](docs/TEMPLATE_oreconfig.xml)

---

## 1. Purpose

`oreConfig.xml` is a global fallback ore-definition file for AR planets.

It defines ore-generation presets by:
- pressure class
- temperature class
- exact pressure+temperature combination

These presets are used only when a planet does **not** define its own per-planet `<oreGen>` in `planetDefs.xml`.

---

## 2. Load Time and Scope

`oreConfig.xml` is loaded during server startup from:

`./config/advRocketry/oreConfig.xml`

If the file is missing, AR creates:

```xml
<OreConfig>
</OreConfig>
```

This is not a general overworld ore config. It is used for AR planetary world generation through `DimensionProperties.getOreGenProperties(...)` and planet chunk population.

---

## 3. Override Order / Precedence

Ore behavior priority is:

    planetDefs.xml <oreGen>
        > oreConfig.xml
        > normal fallback generation

Meaning:

1. A per-planet `<oreGen>` in `planetDefs.xml` wins.
2. Otherwise, AR tries `oreConfig.xml`.
3. Otherwise, worldgen falls back normally:
   - vanilla ores
   - plus AR config ores if `EnableOreGen=true`

If a planet gets ore properties from either `planetDefs.xml` or `oreConfig.xml`, AR treats that planet as custom-ore-controlled.

On such planets, `PlanetEventHandler.onWorldGen(...)` denies these `OreGenEvent.GenerateMinable` types:

- `COAL`- `DIAMOND`- `EMERALD`- `GOLD`- `IRON`- `LAPIS`- `QUARTZ`- `REDSTONE`- `CUSTOM`

Because AR’s own config ore generator posts `CUSTOM`, AR config ores are also suppressed there. In practice, custom ore properties replace AR normal config ore generation on that planet rather than adding on top.

Mods using other generation paths may still bypass this.

---

## 4. Basic File Structure

```xml
<OreConfig>
    <oreGen ...>
        <ore ... />
        <ore ... />
    </oreGen>
</OreConfig>
```

Each `<oreGen>` defines one preset. Each preset contains one or more `<ore>` entries.

---

## 5. `<oreGen>` Reference

### 5.1 Attributes

#### `pressure`
Pressure-class index.

Safe values:

- `0` = `SUPERHIGHPRESSURE`
- `1` = `HIGHPRESSURE`
- `2` = `NORMAL`
- `3` = `LOW`
- `4` = `NONE`

#### `temp`
Temperature-class index.

Safe values:

- `0` = `TOOHOT`
- `1` = `HOT`
- `2` = `NORMAL`
- `3` = `COLD`
- `4` = `FRIGID`
- `5` = `SNOWBALL`

Use only those safe ranges. The loader clamps against enum `length`, not `length - 1`, so values above the real max can still become invalid later.

Do **not** use:
- `pressure="5"`
- `temp="6"`

### 5.2 Matching

`oreConfig.xml` supports:

- pressure-only presets
- temp-only presets
- exact pressure+temp presets

Examples:

    <oreGen pressure="4"> ... </oreGen>
    <oreGen temp="0"> ... </oreGen>
    <oreGen pressure="2" temp="4"> ... </oreGen>

Selection is based on:

- pressure class from `originalAtmosphereDensity`
- temperature class from `getAverageTemp()`

### Internal matching priority inside `oreConfig.xml`

When more than one entry could match a planet, the effective priority is:

    exact pressure+temp
        > pressure-only
        > temp-only
        > no match = normal fallback generation

Practical consequence:

- an exact combined entry like `<oreGen pressure="1" temp="4">` overrides both the `pressure="1"` entry and the `temp="4"` entry
- When both a matching pressure-only entry and a matching temp-only entry exist, current matching behavior prefers the pressure-only entry.
- if all pressure classes are defined, temp-only entries will usually never be reached
- temp-only entries are most useful when a matching pressure-only entry does not exist

This matching priority is separate from the higher-level file precedence in **§3**:

    planetDefs.xml <oreGen>
        > oreConfig.xml
        > normal fallback generation
### 5.3 Notes

- At least one of `pressure` or `temp` must be present.
- If both are omitted, the entry is skipped.
- Exact pressure+temp mappings work with the fixed loader logic and were verified in fresh-world testing.

---

## 6. `<ore>` Reference

`<ore>` entries are read from **attributes**, not child tags.

Use:

```xml
<ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="20" />
```

Do not use:

```xml
<ore>
    <block>minecraft:iron_ore</block>
    <minHeight>1</minHeight>
</ore>
```

### Attributes

#### `block`
Required. Block registry name. Invalid names are skipped.

#### `meta`
Optional. Defaults to `0`.

#### `minHeight`
Required. Parsed as integer, clamped to at least `1`.

#### `maxHeight`
Required. Parsed as integer, clamped to `minHeight..255`.

#### `clumpSize`
Required. Parsed as integer, clamped to `1..255`.

#### `chancePerChunk`
Required. Parsed as integer, clamped to `1..255`.

If an `<ore>` entry is invalid, it is skipped. If an `<oreGen>` ends up with no valid `<ore>` entries, it becomes inactive.

---

## 7. Runtime Generation Behavior

If a planet uses `oreConfig.xml`, its ore entries are generated during planet chunk population through `CustomizableOreGen`.

If the planet does not define a custom filler block, AR uses normal `WorldGenMinable(...)`-style stone replacement.

If the planet does define a custom filler block, AR uses a custom predicate that allows replacement in:
- natural vanilla stone
- the configured filler block’s block type

That means stone-like filler blocks behave more naturally than non-stone filler blocks such as `minecraft:obsidian`.

`oreConfig.xml` does not disable biome terrain or surface generation by itself. It only suppresses the denied ore-event path described in **§3**.

---

## 8. Practical Examples

### Minimal file

```xml
<OreConfig>
</OreConfig>
```

### Pressure-only preset

```xml
<OreConfig>
    <oreGen pressure="4">
        <ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="18" />
        <ore block="minecraft:diamond_ore" minHeight="1" maxHeight="16" clumpSize="6" chancePerChunk="4" />
    </oreGen>
</OreConfig>
```

### Temperature-only preset

```xml
<OreConfig>
    <oreGen temp="0">
        <ore block="minecraft:gold_ore" minHeight="1" maxHeight="48" clumpSize="8" chancePerChunk="20" />
        <ore block="minecraft:redstone_ore" minHeight="1" maxHeight="20" clumpSize="7" chancePerChunk="10" />
    </oreGen>
</OreConfig>
```

### Exact pressure+temperature preset

```xml
<OreConfig>
    <oreGen pressure="2" temp="4">
        <ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="20" />
        <ore block="minecraft:emerald_ore" minHeight="1" maxHeight="32" clumpSize="4" chancePerChunk="6" />
    </oreGen>
</OreConfig>
```

---

## 9. Recommended Usage

For predictable behavior:

1. Use `<OreConfig>` as the root.
2. Use only `<oreGen>` children.
3. Use only attribute-based `<ore ... />` entries.
4. Use only safe enum indexes:
   - `pressure="0..4"`
   - `temp="0..5"`
5. Use exact pressure+temp mappings when you want one specific cell.
6. Use per-planet `<oreGen>` in `planetDefs.xml` for planet-specific behavior.
7. Use `oreConfig.xml` for shared fallback behavior across many planets.

---

## 10. Confirmed Behavior

Confirmed by code review plus fresh-world testing:

- per-planet `planetDefs.xml` `<oreGen>` overrides `oreConfig.xml`
- `oreConfig.xml` overrides normal fallback generation on matched planets
- combined pressure+temp mappings work with the fixed loader logic (2.2.5)
- temp-only mappings work
- pressure-only mappings work
- unmatched planets fall back normally
- missing `oreConfig.xml` also falls back normally
- with `EnableOreGen=true`, normal fallback includes AR config ores
- with `EnableOreGen=false`, normal fallback is vanilla-only