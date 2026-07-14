# Advanced Rocketry - Reworked

A maintained fork of **Advanced Rocketry** for **Minecraft 1.12.2**.

This project continues development of the original mod with ongoing bug fixes, improvements, and quality-of-life updates for modern 1.12.2 modpacks.

> [!CAUTION]
> ## ⚠️ VERSION 3.0.0 IS NOT COMPATIBLE WITH OLD SAVES ⚠️
> **ADVANCED ROCKETRY 3.0.0 IS A CLEAN BREAK. WORLDS AND SAVES FROM ANY EARLIER AR VERSION (2.x AND BELOW) WILL NOT LOAD CORRECTLY AND MAY BE CORRUPTED.**
> The space-model rework changes NBT data, registry names, and world structure **WITHOUT BACKWARD COMPATIBILITY**.
> **BACK UP YOUR WORLD FIRST — DO NOT UPGRADE AN EXISTING SAVE IN PLACE.**

---

## Download

Download the mod on CurseForge:  
**[Advanced Rocketry - Reworked](https://www.curseforge.com/minecraft/mc-mods/advanced-rocketry-2)**

---

## About

**Advanced Rocketry - Reworked** exists to keep Advanced Rocketry alive and actively maintained for the community.

The goal of this fork is to improve stability, expand usability for both players and pack developers, and continue refining one of the most ambitious space and progression mods for Minecraft 1.12.2.

---

## Documentation

### Main Resources

- **CurseForge:** [Advanced Rocketry - Reworked](https://www.curseforge.com/minecraft/mc-mods/advanced-rocketry-2)
- **Wiki Documentation:** [Advanced Rocketry Wiki](http://arwiki.dmodoomsirius.me/)
- **Change Log:** [`CHANGELOG.md`](./CHANGELOG.md)


- **PlanetDefs Documentation**[`XML_PLANETDEFS_README.md`](docs/README_PLANETDEFS.md)
- **OreConfig Documentation**[`XML_ORECONFIG_README.md`](docs/README_ORECONFIG.md)
- **Templates** found `/docs/`

For pack makers and advanced users, this repository also includes a dedicated reference for configuring `planetDefs.xml`:

---

## Featured Modpacks

If you want to play Advanced Rocketry as part of a larger progression-focused experience, check out these modpacks:

### [Towards Rocket Science](https://www.curseforge.com/minecraft/modpacks/towardsrocketscience)

A modpack built around **Advanced Rocketry** and **Immersive Engineering**.  
Great for players who want quests, tech progression, and a more beginner-friendly route into rocket-based gameplay.

### [MeatballCraft, Dimensional Ascension](https://www.curseforge.com/minecraft/modpacks/meatballcraft)

A massive expert-style progression pack for players who want deep automation, long-term goals, and a huge endgame.

### [Enigmatica 2: Expert - Extended](https://www.curseforge.com/minecraft/modpacks/enigmatica-2-expert-extended)

An extended continuation of the classic expert experience, with heavier progression and plenty of room for Advanced Rocketry to shine.

---

## Compatibility Notes

- **PlusTiC Portly rocket compatibility removed.** Earlier builds shipped a
  narrow ASM patch that adjusted rocket yaw when PlusTiC "Portly" tools
  released an Advanced Rocketry rocket. The coremod was rewritten to Mixin,
  and this third-party patch could not be ported safely without the PlusTiC
  source on the build classpath, so it was dropped. Releasing AR rockets with
  PlusTiC Portly tools still works; only the cosmetic yaw-preservation tweak
  is gone. The `enablePlusTiCPortlyRocketCompat` config option no longer
  exists.

---

## Development Notes

Bug fixes, balance changes, and other improvements are tracked in:

- the repository commit history
- [`CHANGELOG.md`](./CHANGELOG.md)

---

## Credits

Full credit goes to the original [**Advanced Rocketry**](https://github.com/Advanced-Rocketry/AdvancedRocketry) developers, along with the maintainers of previous forks, for laying the foundation of this project.

This fork exists to continue development and keep the mod available and useful for the modded Minecraft community.

---

## Support

If you run into a bug, want to suggest an improvement, or would like to contribute, please use this repository’s issue tracker and pull requests.
