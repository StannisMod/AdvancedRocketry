# `planetDefs.xml` — the universe catalogue

Everything Advanced Rocketry lets a pack author say about stars, planets and the procedural galaxy.
This file documents the format exhaustively: every element, every attribute, its unit, its default,
what happens when it is missing or malformed, and — the part that costs people days — what happens
when two of them are stated together.

---

## 1. Where the file is, and when it is read and written

| | path |
|---|---|
| **template** (what a pack ships) | `config/advancedRocketry/planetDefs.xml` |
| **live copy** (what the game reads) | `<save>/advRocketry/planetDefs.xml` |

1. On world load the game looks for the **live copy**. If it is absent, the **template** is copied
   there and that copy is loaded.
2. The config option `resetPlanetsFromXML` (section `Planet` of `advancedRocketry.cfg`) forces the
   copy to happen again, overwriting the live copy from the template. That is the only supported way
   to push a template edit into an existing world. It **resets itself to `false` after one load**
   unless `ResetOnlyOnce` is set to `false`, which is what a pack developer wants while iterating.
3. **On every world save the live copy is REWRITTEN** from the in-memory model.

Consequence of (3), and it surprises everyone exactly once:

- **Comments are lost.** The writer builds a new document; nothing in the file survives that the
  reader did not turn into model state.
- **Unknown elements and attributes are lost**, because they were never read (see §2).
- **`numPlanets` / `numGasGiants` are written back as `0`.** Random planets are generated once, at
  first load, and become ordinary `<planet>` entries. They are not regenerated on later loads.
- **A companion star loses its `name`.** The writer does not emit `name` for a nested `<star>`; it is
  regenerated as `<primary name>-<n>`.

So: edit the **template**, not the live copy, and keep the template under version control.

---

## 2. Parsing rules that apply everywhere

- **The root element must be `<galaxy>`.** No root, or unparseable XML → the world fails to load with
  a crash report naming the file. That is deliberate: a silently half-loaded catalogue is worse.
- **Anything unrecognised is ignored silently.** A misspelled element or attribute produces no
  warning at all. Check your spelling; the game will not.
- **A malformed `<planet>` is skipped, not fatal.** The rest of the catalogue loads and the reason is
  printed to the log. The guard sits at the top-level planet, so a malformed MOON takes its parent
  planet and that planet's other moons down with it — not the whole file.
- **A malformed number inside a recognised element is warned about and the field keeps its default**,
  unless stated otherwise below.
- **Booleans are `true` / `false`**, case-insensitive. Anything else reads as `false`.
- **Element ORDER never matters.** Attribute order never matters.
- **Colours** accept either three comma-separated floats in `0..1` (`0.5,0.5,1.0`) or one
  `0x`-prefixed hex triple (`0xRRGGBB`). Anything else warns and keeps the default.

---

## 3. Units — read this before anything else

| quantity | unit | notes |
|---|---|---|
| **orbital distance** | `100` = 1 AU | Same unit for a planet round its star and for a companion star round its primary. |
| **orbital angle** | DEGREES | `orbitalTheta` on a planet and on a companion alike. |
| **orbital inclination** | DEGREES | `orbitalPhi`. Tilts the orbit; it does not enlarge it. |
| **star temperature** | `100` = Sol | Multiply by 58 for Kelvin. |
| **star size** | solar radii | `1.0` = Sol. |
| **planet mass** | Earth masses | |
| **planet radius** | Earth radii | |
| **surface gravity** | percent of Earth's | `100` = 1 g. Clamped to `0..400`. |
| **atmosphere density** | `100` = 1 atm | Clamped to `0..1600`. |
| **planet temperature** | KELVIN | Computed, not authored — see `avgTemperature` in §7. |
| **rotational period** | ticks | `24000` = one Minecraft day. Must be `> 0`. |
| **star map position** | arbitrary map units | `x` / `y` on `<star>`; affects the star-selector GUI only. |
| **galactic anchor** | cell indices | `"sectorX,sectorY,sectorZ"`. One cell is 4 000 000 blocks. |

**The chart scale.** One orbital-distance unit is **5 983 914 blocks**, i.e. one AU is
149 597 870 700 m at 250 m per block. This is the one law that turns an orbit into a place, and it is
the same for authored and procedural systems. Every derived number — insolation, equilibrium
temperature, orbital period, flight time — comes from the orbital distance, so a body's stated
distance and where a ship actually finds it are the same statement.

---

## 4. Document structure

```xml
<galaxy>
  <galaxyGen …>            <!-- 0..1 — procedural galaxy; omit for an authored-only universe -->
    <starType …/>          <!-- 0..n -->
  </galaxyGen>

  <planetType …>…</planetType>   <!-- 0..n — replaces the built-in type table when present -->

  <star …>                 <!-- 0..n — one per authored system -->
    <star …/>              <!-- 0..n — companions, nestable -->
    <planet …>             <!-- 0..n -->
      <planet …>…</planet> <!-- 0..n — moons -->
    </planet>
  </star>
</galaxy>
```

Only `<star>`, `<galaxyGen>` and `<planetType>` are recognised directly under `<galaxy>`.

---

## 5. `<galaxyGen>` — the procedural galaxy

Present → procedural systems exist alongside the authored ones. Absent → the universe holds only what
this file names.

| attribute | unit | default | meaning |
|---|---|---|---|
| `density` | 0..1 | `0.35` | Chance that a given cube of space holds a system **at a galaxy's densest point**. Everywhere else the galaxy's own profile scales it down, and outside every galaxy it is zero. Clamped; `NaN` reads as `0`. |
| `minSpacing` | cells | `40018890` | Edge of the cube that holds **at most one** system, i.e. how far apart stars stand. The default is 4.23 light years. Floors at 1. |
| `galaxySpacing` | cells | `709554785444` | Edge of the cube that holds **at most one galaxy**. The default is 75 000 light years — twenty-five galaxy diameters. Floors at 1. |
| `galaxyDensity` | 0..1 | `0.5` | Fraction of those cubes that actually hold a galaxy. The rest is intergalactic void. Clamped; `NaN` reads as `0`. |

### Where the stars are: galaxies, not a fog

Space is laid out twice over, by the same scheme at two scales. `galaxySpacing`-cubes hold **at most
one galaxy each**, and a galaxy is a real object: a centre, a type, a radius, an orientation, a
central bulge and — if its type has them — spiral arms. Inside it, `minSpacing`-cubes hold at most
one system each, and whether a given cube holds one is `density` **scaled by the galaxy's own profile
at that point**. So the star field thins outwards, thins away from the disc's plane, and stops at the
galaxy's edge.

A galaxy's **type decides its size**, never the other way round: dwarf spheroidals and dwarf
irregulars outnumber spirals and ellipticals by roughly two orders, so finding a spiral is an event.
The archetype table is built in and is not authorable yet.

**The galaxy at the origin always exists.** Authored `<star galacticCoord="…">` anchors are absolute
coordinates, and a galaxy fills a ten-thousandth of its own cube — so without a reserved home the
system you write in this file would land in intergalactic space on virtually every seed. The home
galaxy is centred on the origin and is always drawn large enough (at least 800 light years) to hold
authored content; only its *existence* and its centre are fixed, so its type, size, orientation and
arms still differ from seed to seed.

### `<starType>` — the archetype table

Zero `<starType>` children → the built-in table stands. One or more → they **replace** it entirely.

| attribute | unit | default | meaning |
|---|---|---|---|
| `temp` | `100` = Sol | `100` | Temperature, and therefore colour. |
| `minSize` / `maxSize` | solar radii | `0.8` / `1.2` | Size range. `minSize` floors at `0.1`; `maxSize` is raised to `minSize` if smaller. |
| `weight` | relative | `1` | Draw weight. Floors at `1`. Weights are summed in 64-bit, so extreme values do not collapse the distribution. |

### What `minSpacing` does and does not do

**It moves the STARS apart and nothing else.** It does not decide how large a system is: a system's
extent follows its outermost orbit. Raising it does not inflate a single planet's orbit; lowering it
does not squash one.

What it does bound is **how much room a system has**. Every system is guaranteed a clear space of
**10 000 AU** around its star — no two stars ever stand closer than that — and its named bodies
(planets, moons, belts) stay inside **5 000 AU**, half of that clear space, which is what keeps two
systems' neighbourhoods from overlapping.

**A system that does not fit loses BODIES, never scale.** A world drawn past its system's room is
dropped; the worlds that remain stand exactly where their own orbits say. This is not a corner
anybody meets at the shipped numbers: the widest zone any built-in star archetype can draw is 569 AU
against 5 000 AU of room, a factor of nearly nine. It becomes reachable only if `minSpacing` is cut by
more than two orders of magnitude — below roughly 170 000 cells systems start losing outer worlds,
and below about 8 cells only the star survives.

### Changing a `<galaxyGen>` parameter mid-save is UNDEFINED

`density`, `minSpacing`, `galaxySpacing` and `galaxyDensity` are inputs to a **derived** universe:
nothing about a procedural system is stored, so changing any of them relocates every star, every
planet and every generated name. **You get a different universe, and anything a player recorded about
the old one — coordinates, memory crystals, a route — points at nothing.**

There is no migration and there cannot be one: there is no old universe on disk to migrate. If you
change these, start a new world.

---

## 6. `<planetType>` — the type table for procedural worlds

Present → **replaces** the built-in preset table wholesale. Absent → the built-in table stands.
Types are what a procedurally derived world is classified as, after its physics is computed; they are
never applied to an authored `<planet>`.

```xml
<planetType name="ice" weight="20" allowsOxygen="false">
  <pressure    min="0"  max="80"/>
  <temperature min="0"  max="175"/>
  <gravity     min="10" max="140"/>
  <terrain>
    <gen source="MOD_WORLDTYPE" worldType="RTG"       options="" weight="3"/>
    <gen source="NATIVE"        genType="0"                      weight="2"/>
    <gen source="TEMPLATE"      path="frozen_ruins"              weight="1"/>
  </terrain>
  <biomeIds>advancedrocketry:moondark;10,minecraft:ice_flats;30</biomeIds>
  <seaLevel>0</seaLevel>
  <oceanBlock>minecraft:water</oceanBlock>
  <oreGen>…</oreGen>
</planetType>
```

| attribute on `<planetType>` | default | meaning |
|---|---|---|
| `name` | `""` | Identifier, shown in scans. |
| `weight` | `10` | Draw weight among the types that ADMIT a given world. |
| `gasGiant` | `false` | This type has no surface. |
| `allowsOxygen` | `false` | Worlds of this type may roll breathable air. Only ~18 % of those that may, do. |
| `tidallyLockable` | `true` | Worlds of this type can keep one face to their star. |

| child | attributes | default range | meaning |
|---|---|---|---|
| `<pressure>` | `min`, `max` | `0..1600` | Atmosphere density band this type admits. |
| `<temperature>` | `min`, `max` | `0..5000` | Kelvin band. |
| `<gravity>` | `min`, `max` | `0..400` | Percent-of-Earth band. |
| `<terrain>` | — | — | Container for `<gen>` options; one is drawn by weight. |
| `<biomeIds>` | — | — | Biome palette, same format as a planet's (§7). |
| `<seaLevel>` | — | unset | Sea level for worlds of this type. |
| `<oceanBlock>` | — | unset | Registry name of the liquid. |
| `<oreGen>` | — | — | Ore table, same format as a planet's (§8). |

A world must satisfy **all three** ranges to be admitted by a type. Every attribute has a default, so
`<planetType name="x"/>` is valid and matches nearly everything — which makes it a very greedy entry.

### `<gen>` — one terrain option

| attribute | applies to | meaning |
|---|---|---|
| `source` | all | `NATIVE`, `MOD_WORLDTYPE` or `TEMPLATE`. Unknown names fall back to `NATIVE`. |
| `worldType` | `MOD_WORLDTYPE` | The world-type name another mod registered. |
| `path` | `TEMPLATE` | Template identifier. |
| `genType` | `NATIVE` | Built-in generator variant. |
| `options` | `MOD_WORLDTYPE` | Generator settings string, passed through verbatim — **not trimmed**, because whitespace can be significant to the receiving generator. |
| `weight` | all | Draw weight among this type's options. Default `1`. |

**A `MOD_WORLDTYPE` option whose mod is not installed is dropped BEFORE the draw**, and its weight is
redistributed among the remaining options. A type all of whose options are unavailable falls back to
`NATIVE`. This is why a type should always carry at least one `NATIVE` option.

---

## 7. `<star>` and `<planet>`

### `<star>` attributes

| attribute | unit | required | meaning |
|---|---|---|---|
| `name` | — | no | Display name. |
| `temp` | `100` = Sol | no (default `100`) | Temperature; drives colour and luminosity. A malformed value warns and falls back to `100`. |
| `size` | solar radii | no (default `1.0`) | Radius. |
| `x`, `y` | map units | no | Position on the star-selector map. `y` is the map's Z. |
| `galacticCoord` | `"sx,sy,sz"` | no | Explicit anchor cell. Malformed → warns and uses the origin. Absent → a deterministic fallback cell is assigned. |
| `numPlanets` | count | **yes** | How many random planets to generate for this star at FIRST load. Missing → warning and none. |
| `numGasGiants` | count | **yes** | The same for gas giants. |
| `blackHole` | boolean | no | This star is a black hole: a quarter of the light its size and temperature would otherwise give. |
| `diskAngle` | degrees | no (default `70`) | Accretion-disc tilt, render only. |

`numPlanets` / `numGasGiants` fire **once**, at the first load of a world. They are written back as
`0`, so the generated planets become ordinary entries and are not regenerated. Hand-written
`<planet>` children are additional to them, not instead of them.

### A nested `<star>` is a COMPANION

| attribute | unit | default | meaning |
|---|---|---|---|
| `name` | — | `<primary>-<n>` | Display name. **Not written back** on save. |
| `temp` | `100` = Sol | `100` | |
| `size` | solar radii | `1.0` | |
| `orbitalDistance` | `100` = 1 AU | `5` (0.05 AU) | How far this star orbits its primary. |
| `orbitalTheta` | degrees | spread automatically | Its angle on that orbit. Companions with no stated angle are spread apart rather than stacked. |
| `blackHole`, `diskAngle` | — | — | As above. |

Companions nest: a companion may itself carry companions, and the geometry composes. Consequences
that are easy to miss:

- **A companion is a star with its own identity.** It gets its own star id, so a `<planet>` can be
  bound to it and a world can orbit the companion rather than the primary.
- **Every star of a system lights every world in it.** Illumination is the sum of the flux each star
  delivers at its own distance, so a close pair nearly doubles a world's light and a companion 20 AU
  out adds only a little. This feeds temperature, solar panels and every derived climate number.
- **A companion's apparent place in the sky follows from its distance**, not from a fixed tilt: a
  close pair reads as two suns almost together, a wide one puts its companion elsewhere in the sky.
- `orbitalDistance` on a companion is the SAME unit as on a planet. It used to be an angle called
  `separation`; that attribute no longer exists and is ignored if present.

### `<planet>` attributes

| attribute | meaning |
|---|---|
| `name` | Display name. |
| `DIMID` | Explicit dimension id. Absent → the next free id is assigned. Malformed → **the whole planet is skipped**. |
| `dimMapping` | Presence alone (any value, including empty) marks this as a dimension another mod owns; Advanced Rocketry decorates it instead of creating it. |
| `customIcon` | Basename of the planet-selector texture. See the catalogue below. |

### Built-in `customIcon` values

Built-in planet icon basenames:

`src/main/resources/assets/advancedrocketry/textures/planets/`

#### Standard icons

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

#### Additional normal-only textures

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

#### Special case

- `customIcon="void"` is handled specially in the system map and renders the body at size `0`.

#### Adding your own `customIcon`

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
- Every built-in texture lives in this repository under
  [`src/main/resources/assets/advancedrocketry/textures/planets/`](../src/main/resources/assets/advancedrocketry/textures/planets/).

A `<planet>` nested inside a `<planet>` is a **moon** of it. Moons nest arbitrarily deep. A moon's
`orbitalDistance` is measured from its PARENT, not from the star.

### `<planet>` child elements

Physical:

| element | unit | notes |
|---|---|---|
| `orbitalDistance` | `100` = 1 AU | Clamped to `1 .. Integer.MAX_VALUE`. |
| `orbitalTheta` | degrees | Angle at time zero. Fractional degrees are kept. |
| `orbitalPhi` | degrees | Inclination. Taken modulo 360. |
| `retrograde` | boolean | Orbits the other way. |
| `rotationalPeriod` | ticks | Must be `> 0`; a non-positive value warns and is ignored. |
| `tidallyLocked` | boolean | Keeps one face to its star; overrides `rotationalPeriod` in effect. |
| `mass` | Earth masses | See the precedence rule below. |
| `radius` | Earth radii | See the precedence rule below. |
| `gravitationalMultiplier` | percent of Earth | Clamped to `0..400`. See below. |
| `atmosphereDensity` | `100` = 1 atm | Clamped to `0..1600`. |
| `hasOxygen` | boolean | Default `true`. Only `false` is written back. |
| `metallicity` | relative to Sol | Feeds ore richness. `1.0` is not written back. |
| `avgTemperature` | Kelvin | **Written, never read.** The temperature is recomputed at load from the star, the orbital distance and the atmosphere. Editing it does nothing. |

Appearance:

| element | notes |
|---|---|
| `fogColor`, `skyColor`, `ringColor` | Colour, see §2. |
| `hasRings` | boolean. |
| `ringAngle` | degrees. |
| `hasShading` | boolean; whether the world is decorated with shading. |
| `hasColorOverride` | boolean. |
| `skyRenderOverride` | boolean. |
| `customIcon` | attribute, not element — see above. |

World generation:

| element | notes |
|---|---|
| `genType` | Built-in generator variant. Only written when non-zero. |
| `terrainSource` | `NATIVE`, `MOD_WORLDTYPE` or `TEMPLATE`. Unknown → `NATIVE`. Only written when not `NATIVE`. |
| `terrainWorldType` | Name of another mod's world type. |
| `terrainTemplate` | Template identifier. |
| `terrainGeneratorOptions` | Passed through verbatim, **not trimmed**. |
| `seaLevel` | Block height. |
| `orbitHeight` | Block height at which a rocket leaves this world. Only written when overridden. |
| `oceanBlock` | Registry name. An unknown block warns and yields air. |
| `fillerBlock` | `mod:block` or `mod:block:meta`. Fewer than two parts warns and is ignored. |
| `forceRiverGeneration` | boolean. |
| `biomeIds` | See the format below. |
| `craterBiomeWeights` | See the format below. |
| `generateCraters`, `generateCaves`, `generateVolcanos`, `generateStructures`, `generateGeodes` | boolean. An empty value leaves the default. **Each is also a global config switch, and the global `false` wins.** |
| `craterFrequencyMultiplier`, `volcanoFrequencyMultiplier`, `geodeFrequencyMultiplier` | float. Only written when not `1` and when the matching feature is enabled. |
| `oreGen` | See §8. |
| `laserDrillOres` | See the format below. **Ignored entirely on a gas giant.** |
| `geodeOres`, `craterOres` | Comma-separated ore-dictionary names. Unknown names are dropped silently. |

Content and progression:

| element | notes |
|---|---|
| `GasGiant` | boolean, spelled with capitals. A gas giant has **no surface**: it cannot be landed on and is not offered as a descent target. |
| `gas` | Fluid name; a harvestable gas. Repeatable. Read on any planet but written back only for a gas giant, so a `<gas>` on a rocky world is lost at the first save. Unknown fluid warns and is skipped. |
| `isKnown` | boolean. **Writes into a GLOBAL list**, not into the planet: it marks this dimension as known to every player from the start. |
| `artifact` | An item stack required to unlock travel here. Repeatable. |
| `spawnable` | An entity that spawns here. See below. |

Weather:

| element | unit | notes |
|---|---|---|
| `rainStartLength`, `rainProlongationLength` | ticks | A malformed value throws and skips the whole planet — these are the only numeric fields without a `try`. |
| `thunderStartLength`, `thunderProlongationLength` | ticks | Same. |
| `rainMarker`, `thunderMarker` | ticks | Same. |
| `acidicRain` | boolean | Rain damages an unprotected player. |

### `<spawnable>` — mob spawns

```xml
<spawnable weight="10" groupMin="2" groupMax="4" nbt="{Health:20}">minecraft:zombie</spawnable>
```

The text content is a registry name (`minecraft:zombie`) or, failing that, a fully-qualified entity
class name. Neither resolving → a warning, and the entry is skipped.

| attribute | default | notes |
|---|---|---|
| `weight` | `100` | Spawn weight. Floors at 1. |
| `groupMin` | `1` | Floors at 1. |
| `groupMax` | `1` | Floors at 1; raised to `groupMin` if smaller. |
| `nbt` | — | JSON NBT applied to the spawned entity. Invalid JSON or NBT logs a loud configuration error and the entity spawns without it. |

### Biome list formats

`biomeIds` — comma-separated `biome` or `biome;weight`:

```xml
<biomeIds>minecraft:desert;40,advancedrocketry:moondark;10</biomeIds>
```

- `biome` is a registry name (preferred) or a raw numeric id (legacy, and dependent on the installed
  mod set).
- `weight` defaults to `30`. A weight of `0` warns and reverts to `30`.
- A malformed entry warns and is skipped; the rest of the list still applies.
- **An empty or absent list is not an empty palette**: a planet with no biomes is given every biome
  its climate admits.

`craterBiomeWeights` — the same shape, but the weight is a crater frequency and defaults to `100`,
and a missing `;weight` term warns. Numeric ids are **not** accepted here; only registry names.

### `laserDrillOres` format

Comma-separated entries, each `oreName` or `oreName;count` or `itemName;count;meta`:

```xml
<laserDrillOres>oreIron;2,oreGold;1,minecraft:diamond;1;0</laserDrillOres>
```

An ore-dictionary name that exists but has no registered items — the providing mod is not installed —
warns and is skipped. A name that is neither an ore-dictionary entry nor an item id warns and is
skipped. The raw string is stored and written back verbatim, so entries for absent mods survive a
round-trip.

### `artifact` syntax

An item stack a player must hold to be allowed to travel here. Format `item_or_block meta count`,
space separated:

```xml
<artifact>minecraft:diamond 0 1</artifact>
```

`meta` defaults to `0` and `count` to `1`. Repeat the element for several artifacts; an unresolvable
item yields an empty stack and is skipped.

---

## 8. `<oreGen>` — ore generation

```xml
<oreGen>
  <ore block="minecraft:iron_ore" meta="0" minHeight="4" maxHeight="64" clumpSize="8" chancePerChunk="20"/>
</oreGen>
```

Only `<ore>` children are read; anything else under `<oreGen>` is ignored.

| attribute | required | clamp | meaning |
|---|---|---|---|
| `block` | **yes** | — | Registry name. Missing → the entry is skipped with a warning. |
| `meta` | no (default `0`) | — | Block metadata. Malformed → the entry is skipped. |
| `minHeight` | **yes** | floors at 1 | Missing or malformed → the entry is skipped. |
| `maxHeight` | **yes** | `minHeight..255` | Missing or malformed → the entry is skipped. |
| `clumpSize` | **yes** | `1..255` | Blocks per vein. Missing or malformed → the entry is skipped. |
| `chancePerChunk` | **yes** | `1..255` | Veins attempted per chunk. Missing or malformed → the entry is skipped. |

Every clamp is silent. A `clumpSize` of `1000` becomes `255` with no warning.

---

## 9. Combinations — what wins when two fields disagree

**Gravity versus bulk.** A planet may state `gravitationalMultiplier`, or `mass` **and** `radius`, or
all three.

| stated | result |
|---|---|
| `gravitationalMultiplier` only | That gravity. No mass or radius; anything needing bulk falls back to gravity. |
| `mass` + `radius` only | Gravity is **derived**: `g = M / R²`, clamped to `0.05 .. 4.0` g. |
| all three | **The authored gravity wins.** Mass and radius are still stored and still used for orbital periods and for anything that needs a real bulk. |

The last row is the important one: adding `mass` and `radius` to a planet that already states a
gravity cannot change how that planet plays. It only gives the model the numbers it was missing.

**Mass and radius are order-independent** but each is applied against the other's current value, so
stating only one of them leaves the other at zero — and a zero radius means no bulk properties at all.
State both or neither.

**Gas giant versus surface.** `<GasGiant>true</GasGiant>` makes the world surfaceless. It is then not
a landing target however else it is configured, `laserDrillOres` on it is ignored, and only `<gas>`
entries can be harvested from it.

**Tidal locking versus rotation.** `tidallyLocked` makes the world's rotation equal its orbit. A
`rotationalPeriod` stated alongside it is stored but has no visible effect.

**`orbitalDistance` versus everything derived.** Insolation, equilibrium temperature, orbital period,
climate and the physical distance a ship flies all come from this one number. `avgTemperature` is
recomputed from it at every load — you cannot author a temperature that contradicts an orbit.

**Star temperature and size versus planet climate.** Changing a star's `temp` or `size` re-derives the
climate of every world around it on the next load, because temperature is computed and not stored.

**`DIMID` versus automatic ids.** Stating `DIMID` on some planets and not others is supported; the
automatic allocator skips ids already taken. Two planets stating the SAME `DIMID` is not detected —
the second silently replaces the first.

**`dimMapping` versus everything physical.** A mapped dimension is generated by whoever owns it.
Terrain elements on it are ignored; climate, gravity and atmosphere still apply.

**Global config switches versus per-planet flags.** `generateCraters`, `generateGeodes`,
`generateVolcanos` and `generateStructures` exist both here and in the mod config. **The global
`false` overrides a per-planet `true`.** The reverse is not true: a global `true` does not force a
planet that declined.

**`<planetType>` versus `<planet>`.** Types classify PROCEDURAL worlds only. They never modify an
authored `<planet>`, however well its numbers match a type's ranges.

**`<galaxyGen>` versus authored stars.** They coexist. An authored star occupies its anchor cell and
owns that whole neighbourhood; the procedural generator fills what is left. Two authored anchors in
one neighbourhood is a configuration error and is reported.

---

## 10. Worked minimal examples

A single authored system, no procedural galaxy:

```xml
<galaxy>
  <star name="Sol" temp="100" size="1.0" numPlanets="0" numGasGiants="0" galacticCoord="0,0,0">
    <planet name="Earth" DIMID="0">
      <orbitalDistance>100</orbitalDistance>
      <orbitalTheta>0</orbitalTheta>
      <gravitationalMultiplier>100</gravitationalMultiplier>
      <atmosphereDensity>100</atmosphereDensity>
      <hasOxygen>true</hasOxygen>
      <planet name="Luna" DIMID="1">
        <orbitalDistance>30</orbitalDistance>
        <gravitationalMultiplier>16</gravitationalMultiplier>
        <atmosphereDensity>0</atmosphereDensity>
        <hasOxygen>false</hasOxygen>
      </planet>
    </planet>
  </star>
</galaxy>
```

A wide binary whose companion carries a world of its own:

```xml
<star name="Alpha" temp="110" size="1.1" numPlanets="0" numGasGiants="0">
  <star name="Beta" temp="90" size="0.9" orbitalDistance="2300" orbitalTheta="45"/>
  <planet name="Alpha I">
    <orbitalDistance>120</orbitalDistance>
    <mass>1.0</mass>
    <radius>1.0</radius>
  </planet>
</star>
```

`Alpha I` is lit by both stars, with `Beta`'s contribution falling off over its own 23 AU.

A procedural galaxy with two archetypes and one type:

```xml
<galaxy>
  <galaxyGen density="0.4" minSpacing="40018890" galaxySpacing="709554785444" galaxyDensity="0.5">
    <starType temp="40"  minSize="0.6" maxSize="1.0" weight="40"/>
    <starType temp="220" minSize="1.4" maxSize="2.6" weight="5"/>
  </galaxyGen>

  <planetType name="rock" weight="20">
    <pressure    min="0"  max="120"/>
    <temperature min="150" max="400"/>
    <gravity     min="20" max="200"/>
    <terrain>
      <gen source="NATIVE" genType="0" weight="1"/>
    </terrain>
  </planetType>
</galaxy>
```

---

## 11. Pitfalls

- **`numPlanets`, not `numPlanet`.** An unrecognised attribute is ignored silently, and the star then
  generates nothing — with a warning about a missing entry rather than about a misspelling.
- **Editing the live copy.** It is rewritten on the next save. Edit the template and use
  `resetPlanetsFromXML`.
- **`avgTemperature` looks authorable and is not.** It is written by the exporter and recomputed on
  load. The same goes for anything else that appears in an exported file but is absent from §7 here:
  if the reader has no branch for it, writing it does nothing.
- **Two planets with the same `DIMID`.** Not detected; the second silently replaces the first.
- **A weight of `0`** in `biomeIds` warns and reverts to the default, because a zero-weight entry
  would silently never be drawn.

---

## 12. External tools

A community editor for building a catalogue visually:
<https://github.com/DaIsimsiz/planetDefs-Builder/releases>. It predates the fields introduced by the
3.0.0 line — `mass`, `radius`, `metallicity`, `terrainSource`, `<galaxyGen>` and `<planetType>` — so
check its output against §7 before shipping it.
