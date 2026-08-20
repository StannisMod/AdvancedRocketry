package zmaster587.advancedRocketry.api;

import net.minecraft.block.Block;

/**
 * Stores references to Advanced Rocketry's blocks
 */
public class AdvancedRocketryBlocks {
    public static Block blockTerraformingTerminal;
    public static Block blockNuclearEngine;
    public static Block blockSpaceLaser;
    public static Block blockPrecisionAssembler;
    public static Block blockArcFurnace;
    public static Block blockBlastBrick;
    public static Block blockQuartzCrucible;
    public static Block blockCrystallizer;
    public static Block blockLathe;
    public static Block blockCuttingMachine;
    public static Block blockObservatory;
    public static Block blockPlanetAnalyser;
    public static Block blockLaunchpad;
    public static Block blockStructureTower;
    /**
     * Mirror plating, one block per FILM. The tiers are the reflectances of the metals mirrors are
     * really made of, so the ladder is physics rather than invention: aluminium is the workhorse,
     * silver the best in the visible band, and gold the infrared mirror — which is the band a weapon
     * laser lives in, and the reason gold answers one weapon family and not another.
     */
    public static Block blockMirrorPlatingAluminium;
    public static Block blockMirrorPlatingSilver;
    public static Block blockMirrorPlatingGold;
    /** Reactive plating: a charge that spends itself. Two thicknesses, and layering is allowed. */
    public static Block blockReactivePlate;
    public static Block blockReactiveBlock;
    public static Block blockRocketBuilder;
    public static Block blockGenericSeat;
    public static Block blockPilotSeat;
    public static Block blockEngine;
    public static Block blockBipropellantEngine;
    public static Block blockFuelTank;
    public static Block blockBipropellantFuelTank;
    public static Block blockOxidizerFuelTank;
    public static Block blockFuelingStation;
    public static Block blockServiceStation;
    public static Block blockMonitoringStation, blockSatelliteBuilder, blockSatelliteControlCenter;
    public static Block blockNuclearFuelTank;
    public static Block blockMoonTurf, blockHotTurf;
    public static Block blockNuclearCore;
    public static Block blockLightSource;
    public static Block blockLightwoodWood, sblockLightwoodLeaves, blockLightwoodSapling;
    public static Block blockGuidanceComputer;
    public static Block blockAdvancedFlightComputer;
    public static Block blockNavigationComputer;
    /**
     * The gun family: a controller, and the parts a gun's numbers are derived from. A turret is
     * whatever was built around the controller, so these are placed rather than crafted into a
     * fixed shape.
     */
    public static Block blockTurret;
    public static Block blockGunBarrel;
    public static Block blockGunAmmoFeed;
    public static Block blockGunBeamEmitter;
	public static Block blockGunCooling;
    /** The one thing the weapons network adds: a place to point every gun at once. */
    public static Block blockWeaponConsole;
    /**
     * The eyes of a battery: it finds targets so that nobody has to name them, and hands its
     * network one contact at a time. Off a ship it is a planetary-defence radar; the block is the
     * same either way.
     */
    public static Block blockFireControlSensor;
    /** The hyperdrive family: the machines that make a jump possible. */
    public static Block blockHyperdriveGenerator;
    public static Block blockHyperdriveCoil;
    public static Block blockJumpFieldEmitter;
    public static Block blockJumpCapacitor;
    public static Block blockJumpCapacitorCell;
    public static Block blockJumpHeatSink;
    public static Block blockGravityDampener;
    public static Block blockPlanetSelector;
    public static Block blockSawBlade;
    public static Block blockConcrete;
    public static Block blockRollingMachine;
    public static Block blockPlatePress;
    public static Block blockPlatePressHead;
    public static Block blockStationBuilder;
    public static Block blockElectrolyser;
    public static Block blockOxygenFluid;
    public static Block blockHydrogenFluid;
    public static Block blockChemicalReactor;
    public static Block blockPrecisionLaserEngraver;
    public static Block blockFuelFluid;
    public static Block blockOxygenVent;
    public static Block blockCO2Scrubber;
    public static Block blockOxygenCharger;
    public static Block blockAirLock;
    public static Block blockLandingPad;
    public static Block blockWarpShipMonitor;
    public static Block blockOxygenDetection;
    public static Block blockUnlitTorch;
    public static Block blocksGeode;
    public static Block blockVitrifiedSand;
    public static Block blockCharcoalLog;
    public static Block blockElectricMushroom;
    public static Block blockCrystal;
    public static Block blockOrientationController;
    public static Block blockGravityController;
    public static Block blockDrill;
    public static Block blockMicrowaveReciever;
    public static Block blockSolarPanel;
    public static Block blockSuitWorkStation;
    public static Block blockLoader;
    public static Block blockBiomeScanner;
    public static Block blockAtmosphereTerraformer;
    public static Block blockDeployableRocketBuilder;
    public static Block blockPressureTank;
    public static Block blockIntake;
    public static Block blockNitrogenFluid;
    public static Block blockCircleLight;
    public static Block blockSolarGenerator;
    public static Block blockDockingPort;
    public static Block blockAltitudeController;
    public static Block blockRailgun;
    public static Block blockAdvEngine;
    public static Block blockAdvBipropellantEngine;
    public static Block blockPlanetHoloSelector;
    public static Block blockLens;
    public static Block blockVacuumLaser;
    public static Block blockGravityMachine;
    public static Block blockPipeSealer;
    public static Block blockSpaceElevatorController;
    public static Block blockBeacon;
    public static Block blockLightwoodPlanks;
    public static Block blockThermiteTorch;
    //FROZEN API symbol — the misspelling is deliberate. Dependent mods compile the field
    //name into their bytecode, so renaming it throws NoSuchFieldError at their runtime.
    //TODO(3.0.0): rename to blockTransceiver alongside the registry-name migration.
    public static Block blockTransciever;
    public static Block blockMoonTurfDark;
    public static Block blockBlackHoleGenerator;
    public static Block blockEnrichedLavaFluid;
    public static Block blockPump;
    public static Block blockCentrifuge;
    public static Block blockBasalt;
    public static Block blockLandingFloat;
    public static Block blockSolarArray;
    public static Block blockSolarArrayPanel;
    public static Block blockRocketFire;
    public static Block blockServiceMonitor;
    public static Block blockInvHatch;
    public static Block blockOrbitalRegistry;
    public static Block blockDataBusBig;
}
