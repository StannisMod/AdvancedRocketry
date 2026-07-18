# Advanced Rocketry — Reworked

A space and progression mod for **Minecraft 1.12.2**. Rockets here are built, not crafted: you lay out a
launch pad, stack engines and fuel tanks around a guidance computer, and the Rocket Assembling Machine scans
that structure and turns it into a rocket you can fly. Where it can reach depends on its mass and thrust, so
a heavier target means going back and rebuilding rather than just refuelling.

Everything runs on Forge Energy (RF), and the mod brings its own generation — solar panels and arrays up
through a black hole generator — so it works on its own and still accepts power from any tech mod you already
run.

This is a maintained fork. **Current release: 2.3.0**, stable, last updated July 2026. A 3.0.0 with
ship-based flight is [in development](#coming-in-300).

## Screenshots

<!-- TODO: add screenshots. Suggested set:
       1. A finished rocket on its launch pad
       2. Interior of a sealed base on an airless world (oxygen vent + scrubber visible)
       3. A space station in orbit with a rocket docked
       4. A tier-2 ship in flight, once 3.0.0 is closer
     Put the files in docs/img/ and reference them here. -->

---

## What you do

### Rocket assembly

Engines come in three families: monopropellant, bipropellant and nuclear thermal. Parts pick up wear across
flights and are repaired at a Service Station, so a fleet needs maintenance rather than just fuel.

Rockets normally fly themselves. **Free Flight** is the alternative: six-axis manual control, switched on per
rocket, leaving the rest on autopilot.

### Life support

Landing somewhere airless turns the game into a sealing problem. You build an airtight room, run an Oxygen
Vent into it, scrub the CO2 back out, and check the seal before taking the helmet off. Step outside and you
are on suit oxygen until you get back.

Planets each have their own gravity, day length and weather, and the mod handles the awkward cases: sleeping
works on a world whose day is not 24 000 ticks, and acid rain will corrode a base left open to the sky.

### Surveying

An Observatory finds new worlds. Satellites you launch into orbit survey them properly and then keep working
— orbital scans, and a laser drill that mines a body from above.

### Orbital construction

Space stations are assembled in orbit from a station container, docked to with a Docking Pad, and given
artificial gravity. A Warp Core moves a finished station between planets.

### Industry and terraforming

Ore processing runs through its own machine chain: precision assembler, crystallizer, cutting machine, arc
furnace. Terraforming sits at the far end, raising a world's pressure and oxygen until a suit is no longer
needed there.

---

## Requirements

| | |
| --- | --- |
| Minecraft | 1.12.2 with Forge |
| Required | **[LibVulpes — this fork](https://github.com/StannisMod/libVulpes-fork2)** · [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) |
| Optional | JEI · TheOneProbe / Waila · Galacticraft Legacy and Matter Overdrive compat |

> [!IMPORTANT]
> Advanced Rocketry is built against the **forked LibVulpes** linked above and will not run correctly on the
> upstream release. Installing stock LibVulpes is the most common way to get a broken setup.

Download from [GitHub Releases](../../releases). Runs on dedicated servers; client and server must match.

You can add the mod to a world you are already playing, though its ores and structures only appear in land
that generates after you install it, so you may need to travel to find them.

---

## Coming in 3.0.0

3.0.0 replaces the rocket-you-sit-in with a ship you walk around on. Building a craft around an Advanced
Flight Computer produces a physics-driven vessel; you fly it from the Pilot Seat, and anyone seated aboard
travels with it. Fly high enough and the ship leaves for space by itself, and it lands you back down when you
return to a world. Packs can also have their star systems placed across galactic coordinates procedurally
rather than by hand.

It is not released, and while it is in development:

- Ships need **Valkyrien Skies**. Without it, nothing changes and the mod stays on classic rockets.
- The Advanced Flight Computer has **no crafting recipe**, so a pack shipping ships has to add one.
- Interstellar jumps do not work yet.

> [!CAUTION]
> **3.0.0 will not load worlds created in 2.x.** The save format changes with no migration path. Back up
> first and start a new world to try it.

---

## For pack developers

The mod adds its own dimensions, worldgen and ore-processing chain, so it takes up room in a pack. Each major
system has a config switch, including worldgen, planet weather and the whole 3.0.0 space subsystem.

Planets, stars and ores are configured in XML, with references and templates in [`docs/`](docs/):

- planetDefs — [reference](docs/README_PLANETDEFS.md) · [template](docs/TEMPLATE_planetdefs.xml)
- oreConfig — [reference](docs/README_ORECONFIG.md) · [template](docs/TEMPLATE_oreconfig.xml)

Coming from an older 2.x build: commands moved into subcommands, so **command scripts and quest-book command
rewards need updating**. Power and data cabling was replaced by a wireless system.

A pack built around 3.0.0's ships is planned — Advanced Rocketry, Valkyrien Skies for the physics, GregTech
CEu for the tech tree, and a quest mod to guide the route. The 2.x line is also maintained for
[Towards Rocket Science](https://www.curseforge.com/minecraft/modpacks/towardsrocketscience).

---

## Contributing

Build instructions, the test layout and the branch map are in [`CONTRIBUTING.md`](./CONTRIBUTING.md). Bugs and
pull requests go through this repository's issue tracker.

## Credits and licence

Built on the original [Advanced Rocketry](https://github.com/Advanced-Rocketry/AdvancedRocketry) by
zmaster587, and on the forks maintained since. Released under the MIT licence
([`LICENSE`](./LICENSE)), so packs may redistribute it freely.

Version history is in [`CHANGELOG.md`](./CHANGELOG.md). The older
[community wiki](http://arwiki.dmodoomsirius.me/) still covers the basics but predates Free Flight and the
3.0.0 work.
