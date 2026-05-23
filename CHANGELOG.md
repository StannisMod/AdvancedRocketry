Changelog 2.2.9
- Fixed crater worldgen crash from negative Y placement.
- Fixed enriched lava flow texture tiling.
- Fixed crash when launching without JEI installed.
- Fixed 3 GL.state.Leaks
- Fixed false yellow atmosphere warning when creating or joining a world.
  - Regression from gracetimerpatch (2.2.5).
- Increased Rocket TP grace timer from 60 to 100 ticks.
  - Gives slow servers a little more time to complete rocket passenger transfers.
- Aligned semantics for worldgen frequency multipliers (craters, volcanoes, and geodes).
  - 2.0 means double frequency, 0.5 means half.
  - Clamped between 0.01 and 10.0.
- Reduced memory leaks:
  - Clear rocket engine sound references when rockets unload/die.
  - Clear custom rocket particles when changing dimensions.
  - Clear AtmosphereHandler state onDisconnect
  - Clear an existing dimension AtmosphereHandler before registering a new one.
  - Unregister OxygenVent atmosphere blobs when broken or chunk-unloaded.


Changelog 2.2.8
- Nuclear rocket gating:
  - Fixed station-return softlock for stations orbiting gated planets.
    - Added `nuclearRocketsRequireArtifactForGatedStations` config.
      - Default: `false` — stations are exempt, under the notion that one would have to have used the artifact to move station there, and it's probably now sitting in the WarpController.artifact-tab..
      - `true` — strict multiplayer option: require artifacts for gated stations too.
- Langfile:
  - fixed 1 missing entry + small cleanups

Changelog 2.2.7
- AtmosphereDetector show and tell player selection. (overhaul, aligned with new holo-projector GUI (libvulpes 0.5.2))
- "Back to Rocket" button for GuidanceComputer/Satellite Bay (QoL)
- Missions:
  - Clear corrupt/stale missions cleanly on load. (fixes a rare case of satellite/station loss), also just cleaner for your save.
  - Fixed a disconnect when running mission on server and opening missiontab in rocket monitor.
  - Missiontab now correctly updates when rocket reaches orbit and mission is active.
- Commands:
  - added `/advancedrocketry fillData chip`,(/ar fd chip) to fill a Programmed Asteroidchip in hand with 1000 of each Datatype.
- Planet biome save handling:
  - Planet biomes are now saved by registry name instead of numeric ID, preventing biome drift after modpack changes.
  - Old numeric-ID `temp.dat` biomes still load and migrate on next save.
  - Biome lists are only saved/generated for native AR surface dimensions, skipping gas giants and stars.
- Some cleaning in config:
  - `resetPlanetsFromXML` now _only_ lives under `Planet {}`.
  - Existing `general.resetPlanetsFromXML` entries can be safely removed from configs.
  - `ResetOnlyOnce` now correctly controls the active XML reload flag.
  - Improved comments for geode, crater, volcano, and structure generation settings so their global override behavior is clearer.
- JEI:
  - GasGiants: added bucketversions as "hidden outputs"
- Compatibility:
  - Fixed Advanced Rocketry rockets being rotated when released from PlusTiC Portly tools.
    - Config boolean: `Compatibility.enablePlusTiCPortlyRocketCompat` default:true.
- Langfile :
  - Added Linker hints
  - Cleaned up ~10 typos in (en_US)
  - Updated Chinese

Changelog 2.2.6
- Void Drill
  - Fixed old copy-pasta logic causing the drill to load the planet below even in void-mining mode.
  - Improved performance during frequent on/off power cycling.
  
Changelog 2.2.5hotfix

- SmallPlatePress naming reverted. should restore compat

Changelog 2.2.5

- Wireless Transceiver *(warning: existing world instances will disappear)*
  - Network IDs now start at 1 and are server-authoritative
  - Added TOP integration for easier usage and sorting
  - (Removed legacy cable code for easier maintenance)
  - Priority

- Small Plate Press
  - Fixed texture and animation issue while operating
  - Can now safely support buttons and levers
  - Added tooltips

- Rocket
  - Fixed 1px GUI slot rendering issue
  - Players are now properly immune during rocket teleportation

- Warp Core
  - Fixed stale GUI when inserting dilithium into the input hatch

- Warp Controller
  - Fixed crash when using the Advanced Databus in the GUI
  - Fixed hotbar/inventory rendering issue caused by GL state leak

- JEI Integration
  - Added JEI pages for Gas Missions

- The One Probe (TOP) and Waila Integration
  - Rockets now show destination and fuel bars
  - Gas Mission rockets now show selected gas and fuel bars
  - Databus, Advanced Databus, and Satellite Terminal now show data type and amount bar
  - Wireless Transceiver now shows insert/extract mode, link status, and network ID

- Biome Scanner
  - Cleaned up GUI and tooltip

- Other
  - Leaky GLState fixed (revealed by 3-way incompat (https://github.com/dercodeKoenig/AdvancedRocketry/issues/74))
  - Restored support for `<volcanoFrequencyMultiplier>` in `planetDefs.xml`.
  - Fixed a first-load/save bug where `geodeFrequencyMultiplier` would be written from the volcano multiplier.

- Commands
  - Fixed `addsealant` and `addtorch`
  - Fixed `create station`
  - Added `d` and `dim` aliases for `/ar goto dimension`
  - Added `s` alias for `/ar goto station`
  - Added `fd` alias for `/ar fillData`
  - Added lowercase support for all subcommands
  - Removed legacy reloadJei command

- Save Paths and XML Handling
  - Improved saving to reduce risk of `temp.dat` corruption, (would lead to loss of spaceobject on bad crashes)
  - XML output now uses explicit UTF-8 instead of the system default charset.
  - Fixed `planetDefs.xml` saving so per-planet `<oreGen>` data is preserved in the world save. (bug from 2019)
  - OreConfig.xml (oreloader)
    - Overall oregen priority unchanged: `planetdefs > oreconfig > config + vanilla/modded`
    - Internal oreconfig priority changed: `p+t > p > t > config + vanilla/modded`
      - Added "Pressure + Temperature" exact match, otherwise same.
- Documentation updated:
  - Inside advancedrocketry.cfg
  - XML_PLANETDEFS_README.md
  - XML_ORECONFIG_README.md
  - TEMPLATE_planetdefs.xml
  - TEMPLATE_oreconfig.xml

Changelog 2.2.4

- Hovercraft: inverted steering fixed
- FuelRegistry: compatibility issue affecting some cross-mod fluids
- Classtransformer: strengthen EntityPlayer ASM anchor for J21+ environments

Changelog 2.2.3

- Asteroid Dimension: 
  - fixed corrupt chunks; 
  - removed black shadows/spots

- Planetdefs.xml: 
  - improved save behavior to reduce XML corruption

- aSync weather (2.1.5) now only applies to native planets (improves compatibility; avoids AR overriding world info/custom dimensions)
- Hovercraft: feels smoother
- Cleaned up commands (and made translatable)
- Updated Chinese localization

huge thanks to jchung
(and Thermo, ZY, Hades and all other feedback)

Changelog 2.2.2.1

- OreMissions
	- Adds support for more modded inventories
- Observatory
	- Correctly render items in Asteroids window
- JEI integration
	- Machinerecipe: Show Time in Ticks if it's less than 1 sec
	- Orbital Laser Drill: (only global list for now)
	- Asteroids

- New admin command:
	- /advancedrocketry create station <orbitdimid> <player> [tp]
	- Creates a SpaceStation (3x3 cobble) and saves it to orbit
		- Optional command: <playerid> tp
		- "/advancedrocketry create station 0 tp" will tp player to a New station orbiting Dim0

Changelog 2.2.2

- New Blocks
  - Orbital Registry
    - Scans existing stations/starships/satellites, shows info, prints new chips
    - Prevents losing the last chip / reduces need for backups
    - Only checks current Dimension (spacestation ->body below)
  - Advanced Databus
    - Works like DataUnit AND Databus
    - Capacity= 2000 * 4 = 8000 (default)
    - Keeps data when broken (NOT a "Satellite Component")

- ItemSatellite
  - Added to tooltip: "Data gen: x/s"

- Rocket
  - Added hint: "Press <Keybind> to open GUI" when riding rockets
  - Added more error messages for failed launches
  - Removed GUI header (fixes fullscreen overlap top left)
  - Planet stat bars fixed

- Warp Controller
  - Reduced GC churn
  - Removed GUI header (fixes fullscreen overlap top left)

- Terraforming Terminal
  - No Controller = true idle

- Orbital Laser Drill
  - laserDrillPlanet=false: simpler GUI + "void cobble" toggle (big performance boost)
  - Early-outs when not constructed / no redstone etc (idle = idle)

- Station Controllers
  - GUI shows if station is anchored

- Observatory
  - Databuses: type could become undefined; now keeps contents on deconstruction
  - Server scanning fixed
  - Stale lists fixed

- Area Gravity Controller
  - Added explanation for the 6 squares in GUI

- Rocket Loader/Unloader + Fluid Loader/Unloader
  - Accepts most modded tanks/inventories
  - Added explanation for the 6 squares in GUI

- Config
  - nuclearRocketsRespectArtifactGating=true
  - EnableOrbitalRegistry=true
  - dataBusBigMultiplier = 4

- Bugfix
  - Docking pads blocking rocket dismantle
  - Space-to-launch only triggers on "down" press (fixes heavy modpacks)
  - Negative/null weather timers crash
  - Rare NPE when corrupt / missing starID
  - Solar Satellites sending wrong values to receiver

- Tooltips
  - Further polished

- Translations
  - Chinese updated
  - English polished
  - Many hardcoded English strings fixed
 
thanks to (ZY, Hades21_21, Xonazeth, and all reports and feedback)
(RoughlyEnoughIDs 2.2.4 is now compatible with AR again) thanks to jchung
 
Changelog 2.2.1-1:

-Terraforming Terminal:
  - GUI: fixed header saying "Satellite Terminal" and polished text
  - Hide internal RF Storage since it uses the satellites Power anyway (avoids confusion)
- Other
  - Added more tooltips
  - Polished tooltips from last update (thanks to Xonazeth!)

Changelog 2.2.1:

- AsteroidChip
  - Hides 3 unused datatypes from tooltip.

- AtmosphereDetector
  - Fixed GUI-background overlapping hotbar

- Fuel Station
  - Fixed nuclear working fluid filling.
  - Smoother energy consumption while fueling.
  - JEI integration (respects config per rocket type).

- ItemSatellite
  - Removed false tooltip error; now shows live build preview.

- WorldServerNotMulti
  - Removed super.init() to avoid per-world manager duplication and broken custom data.

- WirelessTransceiver
  - GUI now shows internal buffer.
  - Auto-download support.
  - Fixed stale states on load.

- SatelliteTerminal
  - Proper, lightweight AutoDownload (With Wireless tranceiver).
  - Minor performance tweaks.
  - Fixed stale states from last update.

- Datastorage
  - Clears to "Some Random Data" at 0 to avoid locked/stale states.
  - Safer vs overriding/voiding types.

- Observatory
  - Each asteroid can only be printed once (no infinite asteroid chips).
  - Conditional tooltip explains limit.
  - Removed pointless data spending.

- Pressurized Fluid Tank
  - Better tower handling (fluids flow down when stacked).
  - Drops and saves correct amount when broken.

- Station Gravity Controller / Station Altitude Controller
  - Performance improvements (less GC, networking, tick spam).
  - Only calculates GUI info when open.
  - Throttled packets to every 5 ticks.

- Station Orientation Controller
  - Performance improvements as above.
  - Smoother rotation and fixed sync issues.

- Unmanned Vehicle Assembler
  - Behaves like Rocket Assembler:
    - Rescans rocket stats after build.
    - Uses same stat calculation.
    - Supports all engine/tank types (compat-guarded).
    - Advanced weight (respects config, falls back to block count).
  - Rejects invalid rockets with new status messages.
  - Updated status syncing.
  - Correctly rotates all engines.

- StationDeployedRocket
  - Adopted rocket logic from normal rockets:
    - GUI can show 2 fuel bars (biprop).
    - Supports all engine/tank types.

- StorageChunk
  - Also checks liquid capacity and gas intake for gas missions.

- Gas Missions
  - New config:
    - gasHarvestAmountMultiplier controls per-mission cap (64,000 mB × multiplier).
    - gasHarvestInfinite fills all attached tanks up to free space, capped at int max.
  - Duration now scales with harvested gas, storage and multipliers (no more multi-hour max runs).

- GasChargePad
  - Hides inherited 0-RF energy capability in Waila/OneProbe.
  - Skips scans/lookups if internal tank is empty.

- RocketMonitor
  - Split status/mission into tabs.
  - Mission tab shows useful mission details.
  - Added Error / status Messages from linked rocket
  - Stronger relink on load.

- Rockets
  - Stronger relink on load.
  - Failed launch reasons posted to mounted player’s chat. (and linked monitor)

- Engines
  - Nuclear engines auto-stick to nuclear cores.
  - Biprop engines stick to tanks (like monoprop).

- ItemPressureTank
  - Stack size increased to 8.

- MicrowaveReceiver
  - Uses same range/lookup logic as Satellite Terminal.
  - Fixed NPE.
  - Fixed voiding when assembling/disassembling multiblocks.

- Pump
  - Can pump water and lava.
  - Now operates every 20 ticks instead of every tick.
  - Can be turned off with redstone.

- Other
  - Small cleanups.
  - Tooltips added for ~98% of blocks/items.
  - JEI: CO2 Scrubber/Oxygen Vent, Fuel Station, Station Assembler.


Changelog 2.2.0

- JEI Integration
  - Satellite Builder: +Satellites
  - Satellite Builder: +ChipCopy

- Guidance Computer Access Hatch
  - Fixed render glitch when emitting redstone

- Satellite Builder
  - Rejects invalid items during assembly (soft-fixes crash with invalid core module)

- Rocket Assembler
  - GUI correctly updates error codes/messages to player
  - Idle GC craziness SHOULD be fixed; lowered overall GC

- Station Assembler
  - No more "rocket already assembled"; now shows specific failure (e.g., invalid launchpad)
  - Correctly updates error codes to client/GUI
  - Safer logic; fewer user errors

- Satellite Terminal
  - No more broadcasting UI updates to everyone in 16-block radius
  - Send UI data only to actual viewer (less network churn / DDOS-y behavior)
  - Downloading data requires power; one-time "Download" button

- Wireless Transceiver
  - Operations throttled to once per 20 ticks; multiple units are phased (don’t run same tick)
  - Enable/disable actually turns it off
  - GUI shows Network ID so you can verify which plug is connected
  - Plugs place on targeted face; top & bottom faces valid
  - Extract button toggles insert/extract
  - Extract button auto-pulls from satellite to Satellite Terminal internal storage
  - NOTE: Still has 100 internal data storage; not voided—stuck in transit if nowhere to go

- Observatory
  - Scrollbar won't reset when selecting an asteroid (may not work with modded container overrides)
  - Mousewheel asteroid scrolling
  - Process button tooltip explains why it’s not working (when observatory isn’t open)
  - Asteroid Chips:
    - Improved tooltips/names; choices closer to loot (kept old randomizer logic)
    - Fix: chips no longer share same name until “New scan”

- Rocket Monitor
  - Stopped 20x/second polling
  - Redstone now event-based (onNeighborChange)
  - Fuel/height via rocket entity (delays: fuel 5 ticks, height 3 ticks)

- Fuel Station
  - Stopped all 20x/second behavior
  - Early bailout logic to truly idle when idle
  - Fix: mono tank could be filled with H2/O2 for 0 burn → infinite free launches
  - Safe against overfilling/voiding

- Rocket Entity
  - GUI shows oxidizer bar only if oxidizer tank exists
  - On dimension change: preloads 3×3 chunks for 60s from Launch event (reduces desync)





solved bugs:
https://github.com/dercodeKoenig/AdvancedRocketry/issues/63
https://github.com/dercodeKoenig/AdvancedRocketry/issues/62
https://github.com/dercodeKoenig/AdvancedRocketry/issues/57
https://github.com/dercodeKoenig/AdvancedRocketry/issues/50
