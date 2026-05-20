# Advanced Rocketry `planetDefs.xml` Reference

This document explains how `planetDefs.xml` is structured and which tags and attributes are supported.

Place the file at:

`config/advancedRocketry/planetDefs.xml`


**Template** found here [`TEMPLATE_planetdefs.xml`](TEMPLATE_planetdefs.xml)


This reference tries to document all fields that are loaded from planetdefs.

---

## 1. Purpose

`planetDefs.xml` lets you define stars, planets, moons, and planet-specific configuration manually.

Place the file as:

`config/advancedRocketry/planetDefs.xml`

This document is intended as a reference-first replacement for the old XML readme.

---

## 2. Basic File Structure

### Root structure

The root element is:

```xml
<galaxy>
```

A galaxy contains one or more `<star>` entries.

A `<star>` can contain:
- one or more `<planet>` entries
- one or more nested `<star>` entries (sub-stars / multi-star systems)

A `<planet>` can contain:
- property tags such as `<atmosphereDensity>`, `<skyColor>`, etc.
- nested `<planet>` entries, which are treated as moons / child bodies

### 2.1 Basic examples

```xml
<galaxy>
    <star name="Sol" temp="100" x="0" y="0" numPlanets="0" numGasGiants="0">
        <planet name="Earth">
        ...
        </planet>
    </star>
</galaxy>
```
```xml
<galaxy>
    <star name="Sol" temp="100" x="0" y="0" numPlanets="0" numGasGiants="0">
        <planet name="Earth">
        ...
        </planet>
    </star>
</galaxy>
```

---

## 3. Rules and Conventions

### 3.1 Nesting rules

- A `<planet>` inside a `<star>` defines a planet orbiting that star.
- A `<planet>` inside another `<planet>` defines a moon / child body.
- A `<star>` inside another `<star>` defines a sub-star.

### 3.2 Parser behavior

The loader is tolerant in some places and strict in others.

Examples:
- Some numeric fields are clamped
- Some invalid values are ignored with warnings
- Some fields use direct `Integer.parseInt(...)` without a `try/catch`; malformed values there may break loading

### 3.3 Scope of this document

This document intentionally excludes fields that are only exported/written but not meaningfully loaded from XML.

Example:
- `avgTemperature` is written by XML export code, but it is not a meaningful author-controlled XML input because temperature is recomputed after load

---

## 4. Star Reference

### 4.1 `<star>` overview

Defines a star system entry.

A top-level `<star>` may contain:
- planets
- sub-stars

A nested `<star>` is treated as a sub-star.

### 4.2 `<star>` attributes

#### `name`
Display name of the star.

```xml
<star name="Sol" ...>
```

#### `temp`
Star temperature integer.

```xml
<star temp="100" ...>
```

Notes:
- Parsed as an integer
- If malformed, the loader falls back to `100` for sub-star parsing

#### `x`
Galaxy map X position.

```xml
<star x="0" ...>
```

#### `y`
Galaxy map Y position.

```xml
<star y="0" ...>
```

Notes:
- Internally this is used as the star's Z/map Y position

#### `size`
Star size multiplier.

```xml
<star size="1.0" ...>
```

Notes:
- Parsed as float

#### `numPlanets`
Maximum number of randomly generated planets for the star.

```xml
<star numPlanets="6" ...>
```

#### `numGasGiants`
Maximum number of randomly generated gas giants for the star.

```xml
<star numGasGiants="1" ...>
```

Notes:
- These values apply to random planet generation for the star
- Manually defined `<planet>` entries can still be added regardless
- For a fully manual system with no extra random planets, use `numPlanets="0"` and `numGasGiants="0"`

#### `blackHole`
Marks the star as a black hole.

```xml
<star blackHole="true" ...>
```

Accepted values:
- `true`
- `false`

#### `diskAngle`
Black hole disk angle / star disk angle.

```xml
<star diskAngle="45.0" ...>
```

Notes:
- Parsed as float

#### `separation`
Only meaningful on nested `<star>` entries.

```xml
<star name="Companion" separation="20.0" ... />
```

Notes:
- Parsed as float
- Used for sub-star separation in multi-star systems

### 4.3 Star examples

#### Single star

```xml
<star name="Sol" temp="100" x="0" y="0" numPlanets="0" numGasGiants="0">
    ...
</star>
```

#### Binary star

```xml
<star name="Alpha" temp="120" x="0" y="0" numPlanets="0" numGasGiants="0">
    <star name="Beta" temp="90" size="0.8" separation="30" />
    ...
</star>
```

#### Black hole

```xml
<star name="Cygnus X" temp="100" x="200" y="-100" blackHole="true" diskAngle="35">
    ...
</star>
```

---

## 5 Planet Reference

### 5.1 `<planet>` overview

Defines a planet or moon.

- A `<planet>` directly inside a `<star>` is a planet.
- A `<planet>` inside another `<planet>` is a moon / child body.
- A `<planet>` could also be defined as `<GasGiant>`
  - GasGiants:
    - Has no surface to land on
    - Intended for Gas Collection or cosmetics

### 5.2 `<planet>` attributes

#### `name`
Planet name.

```xml
<planet name="Earth">
```

#### `DIMID`
Explicit dimension ID.

```xml
<planet name="Earth" DIMID="99">
```
Note:
- Case sensitive, canonical "DIMID"
#### `dimMapping`
Makes a planet out of a non-native dimension.

```xml
<planet name="Twilight" DIMID="7" dimMapping="">
```
The presence of the attribute is what matters.

Notes:
- This should be paired with a correct `DIMID`
- AR will not enforce weather non-native dimension (2.2.3+)
- As with note above not all entries might apply to other mods dimensions.

#### 5.3 `customIcon`
Planet icon basename.

```xml
<planet name="Oceanus" customIcon="waterworld">
```


## Built-in `customIcon` values

Built-in planet icon basenames:

`src/main/resources/assets/advancedrocketry/textures/planets/`

### Standard icons

<table>
  <tr>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/asteroid.png" width="96"><br>
      <code>asteroid</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/carbonworld.png" width="96"><br>
      <code>carbonworld</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/desertworld.png" width="96"><br>
      <code>desertworld</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/earthlike.png" width="96"><br>
      <code>earthlike</code>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/gasgiantblue.png" width="96"><br>
      <code>gasgiantblue</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/gasgiantbrown.png" width="96"><br>
      <code>gasgiantbrown</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/gasgiantred.png" width="96"><br>
      <code>gasgiantred</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/iceworld.png" width="96"><br>
      <code>iceworld</code>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/lava.png" width="96"><br>
      <code>lava</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/marslike.png" width="96"><br>
      <code>marslike</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/moon.png" width="96"><br>
      <code>moon</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/venusian.png" width="96"><br>
      <code>venusian</code>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/waterworld.png" width="96"><br>
      <code>waterworld</code>
    </td>
    <td></td>
    <td></td>
  </tr>
</table>

### Additional normal-only textures

<table>
  <tr>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/asteroid_a.png" width="96"><br>
      <code>asteroid_a</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/asteroid_b.png" width="96"><br>
      <code>asteroid_b</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/asteroid_c.png" width="96"><br>
      <code>asteroid_c</code>
    </td>
    <td align="center">
      <img src="../src/main/resources/assets/advancedrocketry/textures/planets/spoopy.png" width="96"><br>
      <code>spoopy</code>
    </td>
  </tr>
</table>

### Special case

- `customIcon="void"` is handled specially in the system map and renders the body at size `0`.

### 5.3.1 Adding your own `customIcon`

Resource pack should provide:

```text
assets/advancedrocketry/textures/planets/myplanet.jpg
assets/advancedrocketry/textures/planets/myplanetleo.jpg
```

Then reference the basename in `planetDefs.xml`:

```xml
<planet name="Whatever" customIcon="myplanet">
```

Notes:
- The value is lowercased during lookup
- Custom icons are loaded as `<name>.png` for the normal planet texture and `<name>leo.jpg` for the LEO/orbit texture.
- The LEO texture is used for orbit views
- Built-in examples can be found in the mod resources under:
  https://github.com/kaduvill/AdvancedRocketry/tree/1.12/src/main/resources/assets/advancedrocketry/textures/planets


---

## 6. Planet Property Tags

### 6.1 Visual and sky settings

#### `<fogColor>`
Planet fog color.

Accepted formats:
- comma-separated floats: `r,g,b`
- hex prefixed with `0x`

Examples:

```xml
<fogColor>0.5,0.2,1</fogColor>
or
<fogColor>0x87FFFF</fogColor>
```

Notes:
- RGB float components are expected in the range `0` to `1`
- Hex is parsed as an integer after removing the `0x` prefix

#### `<skyColor>`
Planet sky color.

Accepted formats:
- comma-separated floats: `r,g,b`
- hex prefixed with `0x`

Examples:

```xml
<skyColor>0.3,0.6,1</skyColor>
or
<skyColor>0x4C99FF</skyColor>
```

#### `<hasColorOverride>`
Controls color override behavior for sky/fog rendering.

```xml
<hasColorOverride>true</hasColorOverride>
```

Accepted values:
- `true`
- `false`

Notes:
- Used by world provider sky/fog color calculation

#### `<skyRenderOverride>`
Overrides AR's custom sky renderer for that world.

```xml
<skyRenderOverride>true</skyRenderOverride>
```

Accepted values:
- `true`
- `false`

Notes:
- This tag only disables AR's custom planet sky for this planet
- Also affected by the global client config option `planetSkyOverride`
  - If `planetSkyOverride=false` in the config, AR's custom planet sky is already disabled globally and this tag has no additional effect

#### `<hasShading>`
Controls planet decoration rendering override.

```xml
<hasShading>false</hasShading>
```

Accepted values:
- `true`
- `false`

Notes:
- Overrides whether decorators such as shadows / atmosphere-style planet rendering details should be shown

### 6.2 Atmosphere, gravity, orbit, and rotation

#### `<atmosphereDensity>`

Atmosphere density / pressure value.

Example:

    <atmosphereDensity>100</atmosphereDensity>

Meaning:
- `100` is Earthlike.
- Clamped to `[0 - 1600]`
- Atmosphere pressure category is selected with strict `>` thresholds:
  - `0–25`: no atmosphere / vacuum
  - `26–75`: low atmosphere / low oxygen pressure
  - `76–200`: normal pressure (Breathable)
  - `201–800`: high pressure
  - `801–1600`: super-high pressure
- Temperature can still override the result into hot or superheated atmosphere types.

Notes:
- World provider uses atmosphere density for rain/snow/ice behavior and cloud rendering.

#### `<hasOxygen>`

Used to disable `breathable` for normal pressure planets

Example:

    <hasOxygen>true</hasOxygen>

Accepted values:
- `true`
- `false`

Default:
- `true` if omitted.

Meaning:
- This tag is mainly useful for disabling oxygen on breathable planets.
- If the planet has no atmosphere, this tag has no practical breathing effect.

#### `<gravitationalMultiplier>`
Gravity value, using `100 = Earthlike`.

```xml
<gravitationalMultiplier>100</gravitationalMultiplier>
```

Meaning:
- `100` = `1.0`
- `50` = `0.5`
- `150` = `1.5`

Loader clamp:
- Min XML value: `0`
- Max XML value: `400`

Internal conversion:
- Stored as `value / 100f`

Notes:
- World provider uses this value directly for planetary gravity queries

#### `<orbitalDistance>`
Distance from the parent body.

```xml
<orbitalDistance>100</orbitalDistance>
```

Meaning:
- For planets, this is distance from the star
- For moons, this is distance from the parent planet

Loader clamp:
- Min: `1`
- Max: `2147483647`

Notes:
- For planets orbiting stars, this affects temperature
- For moons, code uses parent-star distance for solar temperature

#### `<orbitalTheta>`
Starting angular displacement in degrees.

```xml
<orbitalTheta>180</orbitalTheta>
```

Notes:
- Parsed as integer degrees
- Converted internally to radians
- The parser stores the value modulo `360`

#### `<orbitalPhi>`
Orbital plane angle in degrees.

```xml
<orbitalPhi>90</orbitalPhi>
```

Notes:
- Parsed as integer
- Stored modulo `360`

#### `<retrograde>`
Whether the body orbits in retrograde.

```xml
<retrograde>true</retrograde>
```

Accepted values:
- `true`
- `false`

#### `<rotationalPeriod>`
Length of the day/night cycle in ticks.

```xml
<rotationalPeriod>24000</rotationalPeriod>
```

Meaning:
- `24000` ticks = 20 minutes

Loader rule:
- Must be greater than `0`

Notes:
- Used by `WorldProviderPlanet.calculateCelestialAngle()`

#### `<seaLevel>`
Sea level value.

```xml
<seaLevel>63</seaLevel>
```

Notes:
- Runtime setter clamps to `0..255`


#### `<forceRiverGeneration>`
Controls the `hasRivers` flag.

```xml
<forceRiverGeneration>true</forceRiverGeneration>
```

Accepted values:
- `true`
- `false`

Notes:
- This sets `properties.hasRivers`
- The final `hasRivers()` runtime behavior may also depend on atmosphere and temperature if this is not explicitly forced

### 7.3 Rings and gas giants

#### `<hasRings>`
Whether the body has rings.

```xml
<hasRings>true</hasRings>
```

Accepted values:
- `true`
- `false`

#### `<ringAngle>`
Ring angle integer.

```xml
<ringAngle>70</ringAngle>
```

Notes:
- XML loader uses direct `Integer.parseInt(...)` here
- Use a valid integer

#### `<ringColor>`
Ring color.

Accepted formats:
- comma-separated floats: `r,g,b`
- hex prefixed with `0x`

```xml
<ringColor>0.4,0.4,0.7</ringColor>
```

#### `<GasGiant>`
Marks the body as a gas giant.

```xml
<GasGiant>true</GasGiant>
```

Accepted values:
- `true`
- `false`

Notes:
- Intended for use with gas giants and gas missions
- Canonically saved/exported as `GasGiant`

#### `<gas>`
Adds a harvestable gas/fluid name.

```xml
<gas>hydrogen</gas>
<gas>helium</gas>
```

Notes:
- The value must resolve through the fluid registry
- Intended for use with gas giants and gas missions

### 6.4 Biomes

#### `<biomeIds>`
Biome list for the planet. Overrides the automatic biome-selection

Accepted entry formats:
- numeric biome ID
- biome resource location
- weighted biome entry using `biome;weight`

Examples:

```xml
<biomeIds>0,12</biomeIds>
<biomeIds>minecraft:plains,minecraft:forest</biomeIds>
<biomeIds>minecraft:plains;30,biomesoplenty:alps;15</biomeIds>
```

Notes:
- If a weight is omitted or `0`, default weight is `30`
- Resource locations are preferred over old numeric IDs
- If `<biomeIds>` is omitted, the planet falls back to automatic biome selection
  - Automatic biome selection is affected by global biome-related config and biome lists, including logic such as blacklist handling and `maxBiomesPerPlanet`
- If `<biomeIds>` is provided, the loader uses that explicit biome list instead of automatic biome selection

#### `<craterBiomeWeights>`
Controls which biomes can be used as crater origin biomes, and how likely craters are to generate in each biome.

Accepted format:
- Comma-separated entries
- Each entry uses `biome;weight`
- 

Example:

```xml
<craterBiomeWeights>minecraft:desert;100,minecraft:mesa;60</craterBiomeWeights>
```

  Behavior:

- If `<craterBiomeWeights>` is omitted or empty, craters may originate in any biome.
- If present, only listed biomes are valid crater origin biomes.
- The weight is a percentage-like chance from `0` to `100`.
  - `100` = crater origins in this biome are always allowed when the generator attempts one.
  - `50` = about half of crater origin attempts in this biome are allowed.
  - `1` = very rare crater origin attempts in this biome.
  - `0` = effectively disables crater origins in this biome.
- The biome check is done at the crater origin chunk, not every block touched by the crater.
  - Large craters may still extend into neighboring biomes.
- If frequency is omitted, the loader warns and defaults that biome weight to `100`.
- Invalid biome resource locations are ignored with a warning.

Notes:

- The loader expects biome resource locations such as `minecraft:desert` or `biomesoplenty:volcanic_island`.
- This setting controls where craters may originate; it does not change crater shape, size, block palette, or crater ores.
- Crater generation must still be enabled by both `<generateCraters>true</generateCraters>` and the global `generateCraters` config option.
- Actual crater generation also depends on atmosphere conditions.

### 6.5 Generation type and worldgen switches

#### `<genType>`
Generation type integer.

```xml
<genType>1</genType>
```

- `0` or omitted:
    - normal planet generation
- `1`:
    - cave planet generation (based on vanilla nether)
    
- `2`:
    - Asteroid-belt world

#### `<generateCraters>`
Enable/disable crater generation.

```xml
<generateCraters>true</generateCraters>
```

Accepted values:
- `true`
- `false`


Notes:
- This flag is also gated by the global config option `generateCraters`
  - If the global config is `false`, crater generation is disabled globally regardless of this XML value
  - If the global config is `true`, this tag can still disable craters for an individual planet
- Actual crater generation also depends on atmospheric conditions

#### `<generateGeodes>`
Enable/disable geode generation.

```xml
<generateGeodes>true</generateGeodes>
```

Accepted values:
- `true`
- `false`

Notes:
- This flag is also gated by the global config option `generateGeodes`
  - If the global config is `false`, geode generation is disabled globally regardless of this XML value
  - If the global config is `true`, this tag can still disable geodes for an individual planet

#### `<generateVolcanos>`
Enable/disable volcano generation.

```xml
<generateVolcanos>true</generateVolcanos>
```

Accepted values:
- `true`
- `false`

Notes:
- Canonical spelling is `generateVolcanos`
- This flag is also gated by the global config option `generateVolcanos`
  - If the global config is `false`, volcano generation is disabled globally regardless of this XML value
  - If the global config is `true`, this tag can still disable volcanos for an individual planet

#### `<generateStructures>`
Enable/disable structure generation.

```xml
<generateStructures>true</generateStructures>
```

Accepted values:
- `true`
- `false`

Notes:
- This flag is also gated by the global config option `generateVanillaStructures`
  - If the global config is `false`, vanilla/map-feature structures are disabled on all planets regardless of this XML value
  - If the global config is `true`, this tag can still disable structures for an individual planet
- Structure generation also requires the planet to be habitable/breathable
#### `<generateCaves>`
Enable/disable cave generation.

```xml
<generateCaves>true</generateCaves>
```

Accepted values:
- `true`
- `false`

#### `<craterFrequencyMultiplier>`
Crater frequency multiplier.

```xml
<craterFrequencyMultiplier>1.5</craterFrequencyMultiplier>
```

Behavior:

- `1.0` = default
- `2.0` = double
- `0.5` = half
- Values are clamped to `0.01` - `10.0`

#### `<volcanoFrequencyMultiplier>`
Volcano frequency multiplier.

```xml
<volcanoFrequencyMultiplier>0.5</volcanoFrequencyMultiplier>
```

Behavior:

- `1.0` = default
- `2.0` = double
- `0.5` = half
- Values are clamped to `0.01` - `10.0`

#### `<geodefrequencyMultiplier>`
Geode frequency multiplier.

```xml
<geodefrequencyMultiplier>2.0</geodefrequencyMultiplier>
```

Behavior:

- `1.0` = default
- `2.0` = double
- `0.5` = half
- Values are clamped to `0.01` - `10.0`

### 6.6 Blocks, ores, and loot

#### `<oreGen>`
Per-planet custom ore generation.

Example:

```xml
<oreGen>
  <ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="20" />
  <ore block="minecraft:gold_ore" minHeight="1" maxHeight="32" clumpSize="6" chancePerChunk="8" />
</oreGen>
```

Important:
- The loader reads ore data from `<ore>` attributes
- Do not use nested child tags inside `<ore>`
- Per-planet `<oreGen>` overrides the fallback ore mapping from `oreConfig.xml`

Behavior:
- A non-empty per-planet `<oreGen>` gives that planet custom AR ore properties
  - `oreConfig.xml` is only used if the planet does not define its own `<oreGen>`
- If a planet has ore properties from either per-planet `<oreGen>` or matching `oreConfig.xml`, AR denies these `OreGenEvent.GenerateMinable` types on that planet:
  - `COAL`  - `DIAMOND`  - `EMERALD`  - `GOLD`  - `IRON`  - `LAPIS`  - `QUARTZ`  - `REDSTONE`  - `CUSTOM`
- Because AR’s own config-driven ore generator (`Copper`, `Tin`, `Rutile`, `Aluminum`, `Iridium`, `Dilithium`) uses `CUSTOM`, those ores are also suppressed on such planets
- In practice, this means per-planet ore properties replace AR’s normal config ore generation on that planet rather than adding to it
- An empty `<oreGen>` does not count; at least one valid `<ore>` entry is required for this behavior
- Mods that generate ores through other paths may still bypass this

Precedence:
- Per-planet `<oreGen>` in `planetDefs.xml` has highest priority
- If `<oreGen>` is absent on that planet, AR falls back to matching entries from `oreConfig.xml`
- If either of those supplies ore properties for the planet, AR’s normal config-driven ore generation is suppressed on that planet
- If neither per-planet `<oreGen>` nor `oreConfig.xml` provides ore properties, AR falls back to its normal global config-driven ore generation
- `<fillerblock>` also has a way of disabling normal oregen


##### `block`
Block registry name. Required.

```xml
block="minecraft:iron_ore"
```

##### `meta`
Block metadata. Optional.

```xml
meta="0"
```

##### `minHeight`
Minimum generation height. Required.

```xml
minHeight="1"
```

##### `maxHeight`
Maximum generation height. Required.

```xml
maxHeight="64"
```

##### `clumpSize`
Vein size. Required.

```xml
clumpSize="8"
```

##### `chancePerChunk`
Attempts per chunk. Required.

```xml
chancePerChunk="20"
```

Notes:
- Invalid ore entries are skipped with warnings
- `block` must resolve through `Block.getBlockFromName(...)`

#### `<fillerBlock>`
Base terrain block override.

Accepted formats:
- `modid:block`
- `modid:block:meta`

Examples:

```xml
<fillerBlock>minecraft:stone</fillerBlock>
or
<fillerBlock>minecraft:stone:3</fillerBlock>
```

Notes:
- Only one filler block is stored; if multiple are present, the last valid one wins
- If omitted, terrain defaults to `minecraft:stone`
- If set, the planet’s solid terrain mass uses this block instead of stone
- Natural `minecraft:stone` variants preserve more normal biome-style behavior
- Non-stone filler blocks can suppress normal biome/ore generation
- `<fillerBlock>` does not disable AR custom ore generation from `<oreGen>`

#### `<laserDrillOres>`
Laser drill ore list.

Accepted entry formats:
- OreDictionary name, optionally with count
- item registry name, optionally with count and damage

Examples:

```xml
<laserDrillOres>oreIron;3,oreGold;1</laserDrillOres>
or
<laserDrillOres>minecraft:diamond;1;0,minecraft:redstone;8;0</laserDrillOres>
```

Rules:
- Entries are comma-separated
- Each entry uses semicolon-separated parts

For OreDictionary entries:
- `oreName`
- `oreName;count`

For item entries:
- `modid:item`
- `modid:item;count`
- `modid:item;count;damage`

Notes:
- Invalid ore names or item ids are ignored with warnings
- The raw string is preserved internally as `laserDrillOresRaw`
- This is not tested vs JEI-integration

#### `<geodeOres>`
Geode ore whitelist.

```xml
<geodeOres>oreDiamond,oreEmerald</geodeOres>
```

Notes:
- Comma-separated
- Entries must exist in OreDictionary
- Invalid names are filtered out

#### `<craterOres>`
Crater ore whitelist.

```xml
<craterOres>oreIron,oreGold</craterOres>
```

Notes:
- Comma-separated
- Entries must exist in OreDictionary
- Invalid names are filtered out

#### `<oceanBlock>`
Ocean block override. (sea block)

```xml
<oceanBlock>minecraft:water</oceanBlock>
```

Notes:
- Value is a block resource location
- No metadata is supported here in the XML loader


This setting is a full terrain base-material override, not a decorative or secondary filler
#### `<artifact>`
Required artifact entry.

Accepted format:
- `item_or_block meta count`

Examples:

```xml
<artifact>minecraft:diamond 0 1</artifact>
<artifact>minecraft:stone 3 16</artifact>
```

Notes:
- The first token is resolved first as block, then as item
- `meta` defaults to `0`
- `count` defaults to `1`

### 7.7 Spawn entries

#### `<spawnable>`
Custom spawn entry.

Example:

```xml
<spawnable weight="100" groupMin="1">minecraft:zombie</spawnable>
```

Loader behavior:

- element text content:
    - entity registry name, e.g. `minecraft:zombie`
- supported attributes:
    - `weight`
    - `groupMin`
    - `nbt`

##### `weight`
Spawn weight.

```xml
weight="100"
```

##### `groupMin`
Minimum group size.

```xml
groupMin="1"
```

##### `nbt`
NBT string passed to the spawn entry.

```xml
nbt="{CustomName:\"Bob\"}"
```

Important parser note:
- The current loader has a bug:
    - it reads `groupMin` correctly
    - but it also mistakenly reads `groupMax` from the `groupMin` attribute
- As a result, `groupMax` is not actually loaded correctly by the current parser
- For current-code documentation purposes, `groupMax` should not be treated as a reliable working XML input

Notes:
- If `groupMax` ends up below `groupMin`, it is corrected upward
- Entity lookup first tries registry name, then tries class name
- Invalid NBT can produce fatal configuration errors

### 7.8 Discovery and progression

#### `<isKnown>`
Marks the planet as initially known.

```xml
<isKnown>true</isKnown>
```

Accepted values:
- `true`
- `false`

Notes:
- If true, the planet ID is added to `ARConfiguration.getCurrentConfig().initiallyKnownPlanets`

### 7.9 Custom weather

These are used by `WorldProviderPlanet.updateWeather()` when the planet is using custom world info.

#### `<rainStartLength>`
Base interval for starting rain.

```xml
<rainStartLength>168000</rainStartLength>
```

#### `<thunderStartLength>`
Base interval for starting thunder.

```xml
<thunderStartLength>168000</thunderStartLength>
```

#### `<rainProlongationLength>`
Extension interval while rain is active.

```xml
<rainProlongationLength>12000</rainProlongationLength>
```

#### `<thunderProlongationLength>`
Extension interval while thunder is active.

```xml
<thunderProlongationLength>12000</thunderProlongationLength>
```

#### `<rainMarker>`
Rain mode control.

```xml
<rainMarker>0</rainMarker>
```

Meaningful values:
- `-1` = never rain
- `0` = normal cycle
- `1` = always rain

#### `<thunderMarker>`
Thunder mode control.

```xml
<thunderMarker>0</thunderMarker>
```

Meaningful values:
- `-1` = never thunder
- `0` = normal cycle
- `1` = always thunder

Important notes for all weather fields:
- The XML loader uses direct integer parsing here
- Use valid integers
- At runtime, world weather code treats non-positive intervals defensively, but the XML parser itself is not forgiving of malformed values

---

## 7. Value Formats

### 7.1 Color formats

Supported by:
- `<fogColor>`
- `<skyColor>`
- `<ringColor>`

Accepted forms:

#### RGB floats
```xml
0.5,1,1
```

#### Hex with `0x`
```xml
0x87FFFF
```

Notes:
- RGB float input is expected as three comma-separated components
- Hex is parsed after removing `0x`

### 8.2 Boolean values

Use:

```xml
true
false
```

Tags using boolean-style values include:
- `<hasOxygen>`
- `<hasColorOverride>`
- `<skyRenderOverride>`
- `<hasShading>`
- `<forceRiverGeneration>`
- `<hasRings>`
- `<GasGiant>`
- `<retrograde>`
- `<isKnown>`
- all `generate...` tags

### 7.3 Resource-location-like values

Examples:
- blocks: `minecraft:stone`
- items: `minecraft:diamond`
- biomes: `minecraft:plains`
- entities: `minecraft:zombie`

Fluids for `<gas>` use fluid registry names, such as:
- `hydrogen`
- `oxygen`

### 7.4 Numeric conventions

- `100` atmosphere density = Earthlike atmosphere scale
- `100` gravitational multiplier = Earthlike gravity scale
- angles are provided in degrees in XML
- rotational period uses ticks
- sea level uses block Y coordinates

---

## 8. Special Syntax Reference

### 8.1 `biomeIds` syntax

Allowed forms:
- `0`
- `minecraft:plains`
- `minecraft:plains;30`

Combined example:

```xml
<biomeIds>minecraft:plains;30,minecraft:forest;20,12</biomeIds>
```

### 8.2 `craterBiomeWeights` syntax

Allowed form:
- `biome;frequency`

Example:

```xml
<craterBiomeWeights>minecraft:desert;100,minecraft:mesa;60</craterBiomeWeights>
```

### 8.3 `artifact` syntax

Format:

`item_or_block meta count`

Example:

```xml
<artifact>minecraft:diamond 0 1</artifact>
```

Defaults:
- meta: `0`
- count: `1`

### 8.4 `fillerBlock` syntax

Accepted forms:

```xml
<fillerBlock>minecraft:stone</fillerBlock>
or
<fillerBlock>minecraft:stone:3</fillerBlock>
```

### 8.5 `spawnable` syntax

Current reliable format:

```xml
<spawnable weight="100" groupMin="1">minecraft:zombie</spawnable>
```

With NBT:

```xml
<spawnable weight="20" groupMin="1" nbt="{CustomName:\"Watcher\"}">minecraft:skeleton</spawnable>
```

Current parser caveat:
- `groupMax` is not reliably read due to a loader bug

### 8.6 `oreGen` syntax

Use attribute-based `<ore />` entries:

```xml
<oreGen>
    <ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="20" />
</oreGen>
```

Do not rely on nested child tags inside `<ore>` for loading behavior.



## 9. Practical Examples

### 9.1 Basic terrestrial planet

```xml
<planet name="Earth">
    <fogColor>0.7,0.8,1</fogColor>
    <skyColor>0.4,0.6,1</skyColor>
    <atmosphereDensity>100</atmosphereDensity>
    <hasOxygen>true</hasOxygen>
    <gravitationalMultiplier>100</gravitationalMultiplier>
    <orbitalDistance>100</orbitalDistance>
    <orbitalTheta>0</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>
</planet>
```

### 9.2 Planet with a moon

```xml
<planet name="Earth">
    <atmosphereDensity>100</atmosphereDensity>
    <gravitationalMultiplier>100</gravitationalMultiplier>
    <orbitalDistance>100</orbitalDistance>
    <orbitalTheta>0</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>

    <planet name="Luna">
        <atmosphereDensity>0</atmosphereDensity>
        <hasOxygen>false</hasOxygen>
        <gravitationalMultiplier>16</gravitationalMultiplier>
        <orbitalDistance>150</orbitalDistance>
        <orbitalTheta>180</orbitalTheta>
        <rotationalPeriod>24000</rotationalPeriod>
    </planet>
</planet>
```

### 9.3 Gas giant with harvestable gases

```xml
<planet name="Zephyrus">
    <GasGiant>true</GasGiant>
    <gravitationalMultiplier>180</gravitationalMultiplier>
    <orbitalDistance>220</orbitalDistance>
    <orbitalTheta>90</orbitalTheta>
    <rotationalPeriod>18000</rotationalPeriod>
    <gas>hydrogen</gas>
</planet>
```

### 9.4 Binary star system

```xml
<star name="Alpha" temp="120" x="0" y="0" numPlanets="0" numGasGiants="0">
    <star name="Beta" temp="90" size="0.8" separation="30" />
    <planet name="World A">
        <atmosphereDensity>100</atmosphereDensity>
        <gravitationalMultiplier>100</gravitationalMultiplier>
        <orbitalDistance>100</orbitalDistance>
        <orbitalTheta>0</orbitalTheta>
        <rotationalPeriod>24000</rotationalPeriod>
    </planet>
</star>
```

### 9.5 External dimension mapping

```xml
<planet name="Twilight" DIMID="7" dimMapping="">
    <atmosphereDensity>100</atmosphereDensity>
    <gravitationalMultiplier>100</gravitationalMultiplier>
    <orbitalDistance>140</orbitalDistance>
    <orbitalTheta>45</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>
</planet>
```

### 9.6 Planet with custom icon

```xml
<planet name="Oceanus" customIcon="waterworld">
    <atmosphereDensity>120</atmosphereDensity>
    <gravitationalMultiplier>95</gravitationalMultiplier>
    <orbitalDistance>110</orbitalDistance>
    <orbitalTheta>270</orbitalTheta>
    <rotationalPeriod>22000</rotationalPeriod>
</planet>
```

### 9.7 Planet with custom ore generation

```xml
<planet name="Mineralia">
    <atmosphereDensity>30</atmosphereDensity>
    <gravitationalMultiplier>90</gravitationalMultiplier>
    <orbitalDistance>80</orbitalDistance>
    <orbitalTheta>120</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>

    <oreGen>
        <ore block="minecraft:iron_ore" minHeight="1" maxHeight="64" clumpSize="8" chancePerChunk="20" />
        <ore block="minecraft:gold_ore" minHeight="1" maxHeight="32" clumpSize="6" chancePerChunk="8" />
    </oreGen>
</planet>
```

### 9.8 Planet with custom weather

```xml
<planet name="Stormhold">
    <atmosphereDensity>130</atmosphereDensity>
    <gravitationalMultiplier>100</gravitationalMultiplier>
    <orbitalDistance>95</orbitalDistance>
    <orbitalTheta>60</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>

    <rainStartLength>6000</rainStartLength>
    <rainProlongationLength>12000</rainProlongationLength>
    <thunderStartLength>9000</thunderStartLength>
    <thunderProlongationLength>6000</thunderProlongationLength>
    <rainMarker>0</rainMarker>
    <thunderMarker>0</thunderMarker>
</planet>
```

### 9.9 Planet with custom spawn entries

```xml
<planet name="Infested">
    <atmosphereDensity>80</atmosphereDensity>
    <gravitationalMultiplier>100</gravitationalMultiplier>
    <orbitalDistance>130</orbitalDistance>
    <orbitalTheta>180</orbitalTheta>
    <rotationalPeriod>24000</rotationalPeriod>

    <spawnable weight="100" groupMin="2">minecraft:zombie</spawnable>
    <spawnable weight="40" groupMin="1">minecraft:skeleton</spawnable>
</planet>
```

---

## 10. Common Pitfalls

### 10.1 `numPlanets`, not `numPlanet`
Attribute name is:

```xml
numPlanets="..."
```

### 10.2 `groupMax` is currently not reliable
Current parser bug:
- `groupMax` is not read correctly
- `groupMin` is mistakenly used for both min and max group size


### 10.3 Some author-facing fields from old exports are not real XML inputs
Do not treat exported values such as `avgTemperature` as reliable author-controlled XML settings unless separately confirmed in code.

---

## 11. Fields Intentionally Not Documented Here

This document intentionally excludes fields that were not confirmed as meaningful current XML inputs.

Examples:
- fields only written by export code
- fields not meaningfully loaded back
- fields whose behavior was not confirmed when writing this document

---

## 12. Full Example

```xml
<galaxy>
    <star name="Sol" temp="100" x="0" y="0" numPlanets="0" numGasGiants="0">
        <planet name="Earth" customIcon="earthlike">
            <fogColor>0.7,0.8,1</fogColor>
            <skyColor>0.4,0.6,1</skyColor>
            <atmosphereDensity>100</atmosphereDensity>
            <hasOxygen>true</hasOxygen>
            <gravitationalMultiplier>100</gravitationalMultiplier>
            <orbitalDistance>100</orbitalDistance>
            <orbitalTheta>0</orbitalTheta>
            <orbitalPhi>0</orbitalPhi>
            <rotationalPeriod>24000</rotationalPeriod>
            <seaLevel>63</seaLevel>
            <biomeIds>minecraft:plains;30,minecraft:forest;20</biomeIds>
            <forceRiverGeneration>true</forceRiverGeneration>
            <generateStructures>true</generateStructures>
            <generateCaves>true</generateCaves>
            <isKnown>true</isKnown>

            <planet name="Luna" customIcon="moon">
                <fogColor>0.9,0.9,0.9</fogColor>
                <skyColor>0.1,0.1,0.1</skyColor>
                <atmosphereDensity>0</atmosphereDensity>
                <hasOxygen>false</hasOxygen>
                <gravitationalMultiplier>16</gravitationalMultiplier>
                <orbitalDistance>150</orbitalDistance>
                <orbitalTheta>180</orbitalTheta>
                <rotationalPeriod>24000</rotationalPeriod>
                <generateCraters>true</generateCraters>
            </planet>
        </planet>

        <planet name="Zephyrus" customIcon="gasgiantblue">
            <GasGiant>true</GasGiant>
            <gravitationalMultiplier>180</gravitationalMultiplier>
            <orbitalDistance>220</orbitalDistance>
            <orbitalTheta>90</orbitalTheta>
            <rotationalPeriod>18000</rotationalPeriod>
            <gas>hydrogen</gas>
            <gas>oxygen</gas>
            <hasRings>true</hasRings>
            <ringAngle>70</ringAngle>
            <ringColor>0.6,0.5,0.7</ringColor>
        </planet>
    </star>
</galaxy>
```

---

## 13. Resources
App to help build universe. https://github.com/DaIsimsiz/planetDefs-Builder/releases

)
