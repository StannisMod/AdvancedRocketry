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

Changelog 2.2.1-1:

-Terraforming Terminal:
  - GUI: fixed header saying "Satellite Terminal" and polished text
  - Hide internal RF Storage since it uses the satellites Power anyway (avoids confusion)
-Other
  - Added more tooltips
  - Polished tooltips from last update (thanks to Xonazeth!)


Changelog 2.2.2

- New Blocks
  - Orbital Registry
    - Scans existing stations/starships/satellites, shows info, prints new chips
    - Prevents losing the last chip / reduces need for backups
    - Only checks current dimension
  - Advanced Databus
    - Works like DataUnit AND Databus
    - Stores more data (less transceiver spaghetti / better performance for buffers)
    - Keeps data when broken (NOT a "Satellite Component")

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
  - laserDrillPlanet=false: simpler GUI + "void cobble" toggle (huge performance boost)
  - Early-outs when not constructed / no redstone etc (idle = idle)

- Station Controllers
  - GUI shows if station is anchored

- Rocket Loader/Unloader + Fluid Loader/Unloader
  - Accepts most modded tanks/inventories
  - Added explanation for the 6 squares in GUI

- Config
  - nuclearRocketsRespectArtifactGating=true
  - EnableOrbitalRegistry=true

- Bugfix
  - Docking pads blocking rocket dismantle
  - Space-to-launch only triggers on "down" press (fixes heavy modpacks)
  - Negative/null weather timers crash
  - Observatory databuses: type could become undefined; now keeps contents on deconstruction
  - Observatory server scan + stale asteroid list fixes
  - Rare NPE when starID changes / missing

- Tooltips
  - Further polished

- Translations
  - Chinese updated
  - English polished
  - Many hardcoded English strings fixed
