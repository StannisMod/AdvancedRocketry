# Stellurgy

**Stellurgy is still in development. There is no public build yet.**

Stellurgy is my continuation and large-scale rework of [Advanced Rocketry](https://github.com/Advanced-Rocketry/AdvancedRocketry)
for **Minecraft 1.12.2**.

It originally started as an attempt to fix and modernize Advanced Rocketry. At some point that stopped being
an accurate description of the project. A lot of the old mod is still there, and rockets, planets, stations
and the rest of the AR progression are not going away, but there is now a second layer being built on top of
it.

The main difference is that I want space to actually exist as a place.

In most of the old AR gameplay, a rocket takes you from one destination to another. Stellurgy keeps that kind
of rocket for early spaceflight, but later ships are supposed to work very differently: large block-built
vessels that physically exist, can be flown, and remain usable while travelling. You should be able to get out
of the pilot seat, walk through the ship, use its machines, work on it and live on it while it is going
somewhere.

---

## The universe

Stellurgy has a procedurally generated universe rather than a fixed catalogue of planets.

Stars and planetary systems are generated deterministically and exist whether the player knows about them or
not. Finding them is a separate part of the game. Telescopes and ship sensors reveal information gradually,
and that information can then be used for navigation.

The basic idea is that these are different questions:

- Does something exist there?
- Do you know that it exists?
- Do you know what is there?
- Can your ship reach it?
- Can you survive there?
- Is going there actually worth it?

I don't want research to work as a simple unlock tree where discovering a planet makes it available in a menu.
If you know the coordinates, you can try to go there. Having better information just makes that decision less
stupid.

The universe is laid out at more than one scale. Galaxies are real objects with a centre, a type, a radius and
a disc — spirals, ellipticals and the far more numerous dwarfs — and stars are distributed by that galaxy's
own profile rather than as uniform fog, so the star field thins outwards, thins away from the plane, and stops
at the edge. Inside a galaxy, star clusters are genuinely denser than the field around them, and the molecular
cloud a cluster condensed out of is derived from the cluster itself rather than sprinkled independently.
Distances are honest: one astronomical unit is a real number of blocks, and every derived quantity —
insolation, equilibrium temperature, orbital period, flight time — follows from it.

Planets are also generated from physical properties rather than choosing "desert planet" or "ice planet" first
and filling in the numbers afterwards. Their orbit, star, mass, radius, temperature, atmosphere and other
properties are used to determine what sort of planet they are.

The surface generator is deliberately extensible. Planet types can define weighted lists of terrain generators
in XML — the built-in generator, a world type registered by another mod, or a pre-generated template — and an
option whose mod is not installed is dropped before the draw instead of breaking the world. Modpack authors
should be able to use the large number of existing Minecraft worldgen mods instead of every planet ultimately
being another variation of the same Stellurgy terrain generator.

## Ships

The later spacecraft are physical [Valkyrien Skies](https://github.com/ValkyrienSkies/Valkyrien-Skies) ships.

Their capabilities come from what was actually built into them. Mass, engines, power generation, maneuvering
hardware and other systems are properties of the vessel, not just values selected in a GUI.

A ship is also meant to remain a working base during travel. Long flights only make sense if there is still a
Minecraft game happening aboard the ship.

This is why a lot of seemingly unrelated systems are part of the spacecraft design.

There is power generation and distribution, but also waste heat. Heat has to be moved and eventually rejected
through radiators. Radiator performance uses radiative heat transfer rather than treating a radiator as an
arbitrary number of "cooling units".

There is life support, and the longer-term progression goes beyond carrying tanks of oxygen. Water recovery,
gas processing, food production and greenhouses are intended to make increasingly self-sufficient ships
possible.

There are separate navigation, astronomical and tactical sensors because a telescope looking for a star and a
sensor trying to calculate a firing solution are not doing the same job.

Combat is also being built around the ship itself: physical weapons, shields, damage to actual parts of the
vessel and systems that can fail instead of one health bar representing an entire cruiser.

None of this is supposed to mean that every ship needs twenty people and a spreadsheet to leave orbit. Small
ships should still be practical. Larger ships simply have enough things going on that multiple crew members
can have useful jobs.

## What happened to Advanced Rocketry?

Quite a lot of it is still here.

Stellurgy is a derivative of Advanced Rocketry, not an unrelated mod using its name for nostalgia. The
existing planetary and rocket gameplay is the starting point, and keeping compatibility with useful parts of
the old ecosystem is important to me.

At the same time, I don't want to preserve old behaviour purely because that is how AR happened to work in
1.12.

The rough progression I am aiming for is:

**rockets → orbital infrastructure → astronomy → discovered systems → large spacecraft → interstellar
expeditions**

Rockets get you into space. Eventually you start building things that can stay there.

---

## Development status

There is no public Stellurgy release yet.

Some parts already have working implementations, some are prototypes, some exist as detailed designs, and some
are still only planned. Development happens across several branches, so a system listed as working is not
necessarily working on the same branch as the one next to it.

**Working, in the development tree**

- The inherited Advanced Rocketry layer: built rockets, planets with their own gravity, day length and
  weather, oxygen and sealing, suits, space stations, satellites, the ore-processing chain and terraforming.
- Tier-2 ships as real Valkyrien Skies vessels: assembly from a built craft, pilot seat, manual and automatic
  ascent, the crossing from a planet into its system's space, and terrain-aware descent back down.
- Interstellar transit itself: the ship parks in hyperspace while it travels, the crew can walk around aboard
  it, and a jump survives a server restart. A measured long leg — several hundred sectors — takes minutes,
  not an instant.
- The hyperdrive as machinery rather than a button: field generator, coils, hull emitters, capacitor, cell,
  heat sink and dampeners, with jump speed derived from drive power against ship mass, plus spool-up and
  abort.
- Navigation computer and memory crystals: addresses are data you carry, copy and trade, and the information
  a target reveals is tiered by how close you have been to it.
- Telescope region scans that cost time, survive an unload, and write a real address into a crystal.
- Shields, built on a vendored force-field system rather than a second parallel shield mechanic.
- The procedural universe layer: galaxies, clusters, nebulae, multi-star systems, planets derived from their
  physics, and XML-declared terrain options. This is the newest work and is not merged everywhere yet.

**Being built now**

- Closed-loop life support: sealed zones with real gas contents, crew that breathe them down, recirculators
  and separators on a shared ventilation network, with GregTech CEu supplying the chemistry where it fits.
  Isolation, breach venting and the fallback to suits are still outstanding.

**Designed, not built**

- Ship sensor suites — arrival scans that tell you what is actually in a system, and tactical fire control.
- Hyperspace hazards, misjumps and the computed safe exit point that makes an unscanned address dangerous.
- Ship block damage: losing a fight costing you parts of the vessel rather than a health bar.
- Turrets and the rest of the weapon layer.
- Ship heat rejection and radiators.
- Stations reconciled into the new universe grid.
- GregTech integration, the research economy and the quest interface that gates it.

**Planned**

- The closed food and biomass loop.
- The life-support console.
- Encounters — there is currently nothing out there to meet.

Everything above describes the direction of the project. It should not be read as a list of features already
available in a downloadable build.

I don't currently have a release date. The first public version will appear when there is enough of the new
gameplay working together for it to make sense as a release.

---

## Building it yourself

There is no download, so the only way to run Stellurgy today is to build it.

```bash
./gradlew build
```

| | |
| --- | --- |
| Minecraft | 1.12.2 with Forge |
| Required at runtime | **[LibVulpes — this fork](https://github.com/StannisMod/libVulpes-fork2)** · [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) |
| Bundled | Valkyrien Skies (vendored under `valkyrienskies/`, compiled in — no separate install) |
| Optional | JEI · TheOneProbe / Waila · GregTech CEu · Galacticraft Legacy and Matter Overdrive compat |

> [!IMPORTANT]
> Stellurgy is built against the **forked LibVulpes** linked above and will not run correctly on the upstream
> release. Installing stock LibVulpes is the most common way to get a broken setup.

Gradle runs on JDK 25 while the mod compiles against Java 8; `./gradlew runClient` and `runServer` give you a
dev environment with the ship physics already present. Build details, the test layout and the branch map are
in [`CONTRIBUTING.md`](./CONTRIBUTING.md).

> [!CAUTION]
> **This line will not load worlds created by the 2.x Advanced Rocketry fork.** The save format changed with
> no migration path. Procedural-universe parameters are the same story: they are inputs to a derived
> universe, so changing one relocates every star and every coordinate a player wrote down. Start a new world.

## For pack developers

The mod brings its own dimensions, worldgen and ore-processing chain, so it takes up room in a pack. Each
major system has a config switch, including worldgen, planet weather and the whole space subsystem.

Stars, planets, planet types, the procedural galaxy and ores are configured in XML, with references and
templates in [`docs/`](docs/):

- planetDefs — [reference](docs/README_PLANETDEFS.md) · [template](docs/TEMPLATE_planetdefs.xml)
  — every element and attribute, its unit, and what wins when two of them disagree
- oreConfig — [reference](docs/README_ORECONFIG.md) · [template](docs/TEMPLATE_oreconfig.xml)

Coming from an older 2.x build: commands moved into subcommands, so **command scripts and quest-book command
rewards need updating**, and power and data cabling was replaced by a wireless system.

A pack built around the new ships is planned — Stellurgy, Valkyrien Skies for the physics, GregTech CEu for
the tech tree, and a quest mod to guide the route. The older 2.x Advanced Rocketry line stays maintained
separately for [Towards Rocket Science](https://www.curseforge.com/minecraft/modpacks/towardsrocketscience).

## Contributing

Bugs and pull requests go through this repository's issue tracker. Since there is no release yet, the most
useful reports are the ones that come with the branch and commit you built.

## Credits and licence

Built on the original [Advanced Rocketry](https://github.com/Advanced-Rocketry/AdvancedRocketry) by
zmaster587, and on the forks maintained since. The mod is released under the **GNU General Public License v3**
with a **linking exception** ([`LICENSE`](./LICENSE) + the full GPL text in [`COPYING`](./COPYING)): the mod
itself is copyleft — you may redistribute and modify it, and packs may bundle it, as long as source stays
available — while the linking exception lets dependent and addon mods build against the
`advancedRocketry.api` package **without themselves becoming GPL**. Portions derived from the original are
retained under the MIT licence ([`LICENSE-MIT`](./LICENSE-MIT)); the bundled
[Valkyrien Skies](https://github.com/ValkyrienSkies/Valkyrien-Skies) physics engine (`valkyrienskies/`) is
under Apache-2.0 ([`valkyrienskies/LICENSE`](./valkyrienskies/LICENSE)).

Version history is in [`CHANGELOG.md`](./CHANGELOG.md). The older
[community wiki](http://arwiki.dmodoomsirius.me/) covers the 2.x basics and predates everything described
above.

---

**Minecraft:** 1.12.2  
**Status:** In development  
**Public build:** Not available yet
