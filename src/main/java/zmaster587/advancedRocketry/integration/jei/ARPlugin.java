package zmaster587.advancedRocketry.integration.jei;

import mezz.jei.api.*;
import mezz.jei.api.gui.IAdvancedGuiHandler;
import mezz.jei.api.ingredients.IIngredientBlacklist;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.block.BlockSmallPlatePress;
import zmaster587.advancedRocketry.integration.jei.arcFurnace.ArcFurnaceCategory;
import zmaster587.advancedRocketry.integration.jei.arcFurnace.ArcFurnaceRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.arcFurnace.ArcFurnaceRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.asteroids.AsteroidCategory;
import zmaster587.advancedRocketry.integration.jei.asteroids.AsteroidRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.asteroids.AsteroidRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.centrifuge.CentrifugeCategory;
import zmaster587.advancedRocketry.integration.jei.centrifuge.CentrifugeRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.centrifuge.CentrifugeRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.chemicalReactor.ChemicalReactorCategory;
import zmaster587.advancedRocketry.integration.jei.chemicalReactor.ChemicalReactorRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.chemicalReactor.ChemicalReactorRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.co2scrubber.Co2ScrubberCategory;
import zmaster587.advancedRocketry.integration.jei.co2scrubber.Co2ScrubberRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.co2scrubber.Co2ScrubberRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.crystallizer.CrystallizerCategory;
import zmaster587.advancedRocketry.integration.jei.crystallizer.CrystallizerRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.crystallizer.CrystallizerRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.electrolyser.ElectrolyzerCategory;
import zmaster587.advancedRocketry.integration.jei.electrolyser.ElectrolyzerRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.electrolyser.ElectrolyzerRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.fuelingStation.FuelingStationCategory;
import zmaster587.advancedRocketry.integration.jei.fuelingStation.FuelingStationRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.fuelingStation.FuelingStationRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.gasgiants.GasGiantCategory;
import zmaster587.advancedRocketry.integration.jei.gasgiants.GasGiantRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.gasgiants.GasGiantRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.lathe.LatheCategory;
import zmaster587.advancedRocketry.integration.jei.lathe.LatheRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.lathe.LatheRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill.OrbitalLaserDrillCategory;
import zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill.OrbitalLaserDrillRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill.OrbitalLaserDrillRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.platePresser.PlatePressCategory;
import zmaster587.advancedRocketry.integration.jei.platePresser.PlatePressRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.platePresser.PlatePressRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.precisionAssembler.PrecisionAssemblerCategory;
import zmaster587.advancedRocketry.integration.jei.precisionAssembler.PrecisionAssemblerRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.precisionAssembler.PrecisionAssemblerRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherCategory;
import zmaster587.advancedRocketry.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.rollingMachine.RollingMachineCategory;
import zmaster587.advancedRocketry.integration.jei.rollingMachine.RollingMachineRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.rollingMachine.RollingMachineRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.sawmill.SawMillCategory;
import zmaster587.advancedRocketry.integration.jei.sawmill.SawMillRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.sawmill.SawMillRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.satelliteBuilder.SatelliteBuilderCategory;
import zmaster587.advancedRocketry.integration.jei.satelliteBuilder.SatelliteBuilderRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.satelliteBuilder.SatelliteBuilderRecipeMaker;
import zmaster587.advancedRocketry.integration.jei.stationAssembler.StationAssemblerCategory;
import zmaster587.advancedRocketry.integration.jei.stationAssembler.StationAssemblerRecipeHandler;
import zmaster587.advancedRocketry.integration.jei.stationAssembler.StationAssemblerRecipeMaker;
import zmaster587.advancedRocketry.tile.infrastructure.TileFuelingStation;
import zmaster587.advancedRocketry.tile.multiblock.machine.*;
import zmaster587.advancedRocketry.tile.satellite.TileSatelliteBuilder;
import zmaster587.advancedRocketry.tile.TileStationAssembler;
import zmaster587.libVulpes.inventory.GuiModular;

import mezz.jei.api.IRecipeRegistry;
import net.minecraft.client.Minecraft;
import zmaster587.advancedRocketry.integration.jei.gasgiants.GasGiantWrapper;

import java.util.ArrayList;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.List;

@JEIPlugin
public class ARPlugin implements IModPlugin {
    public static final String rollingMachineUUID = "zmaster587.AR.rollingMachine";
    public static final String latheUUID = "zmaster587.AR.lathe";
    public static final String precisionAssemblerUUID = "zmaster587.AR.precisionAssembler";
    public static final String sawMillUUID = "zmaster587.AR.sawMill";
    public static final String chemicalReactorUUID = "zmaster587.AR.chemicalReactor";
    public static final String crystallizerUUID = "zmaster587.AR.crystallizer";
    public static final String electrolyzerUUID = "zmaster587.AR.electrolyzer";
    public static final String arcFurnaceUUID = "zmaster587.AR.arcFurnace";
    public static final String platePresser = "zmaster587.AR.platePresser";
    public static final String centrifugeUUID = "zmaster587.AR.centrifuge";
    public static final String precisionLaserEngraverUUID = "zmaster587.AR.precisionlaseretcher";
    public static final String satelliteBuilderUUID = "zmaster587.AR.satelliteBuilder";
    public static final String fuelingStationUUID = "zmaster587.AR.fuelingStation";
    public static final String co2ScrubberUUID = "zmaster587.AR.co2scrubber";
    public static final String stationAssemblerUUID = "zmaster587.AR.stationAssembler";
    public static final String orbitalLaserDrillUUID = "zmaster587.AR.orbitalLaserDrill";
    public static final String asteroidsUUID = "zmaster587.AR.asteroids";
    public static final String gasGiantsUUID = GasGiantCategory.UID;
    public static IJeiHelpers jeiHelpers;

    private static IJeiRuntime jeiRuntime;
    private static final List<GasGiantWrapper> currentGasGiantRecipes = new ArrayList<>();
    private static boolean gasRefreshQueued = false;



    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
        //debug
        //AdvancedRocketry.logger.info("[JEI][GasGiants] onRuntimeAvailable");
    }

    public static void requestGasGiantRefresh() {
        gasRefreshQueued = true;
    }
    public static boolean hasQueuedGasGiantRefresh() {
        return gasRefreshQueued;
    }
    public static void tryApplyQueuedGasGiantRefresh() {
        if (!gasRefreshQueued) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null) return;
        if (jeiRuntime == null) return;

        IRecipeRegistry recipeRegistry = jeiRuntime.getRecipeRegistry();
        if (recipeRegistry == null) return;

        //AdvancedRocketry.logger.info("[JEI][GasGiants] removing old recipes count=" + currentGasGiantRecipes.size());
        for (GasGiantWrapper recipe : currentGasGiantRecipes) {
            recipeRegistry.removeRecipe(recipe, gasGiantsUUID);
        }
        currentGasGiantRecipes.clear();

        List<GasGiantWrapper> rebuilt = GasGiantRecipeMaker.getRecipes(jeiHelpers);
        //AdvancedRocketry.logger.info("[JEI][GasGiants] rebuilt recipe count=" + rebuilt.size());

        for (GasGiantWrapper recipe : rebuilt) {
            //AdvancedRocketry.logger.info("[JEI][GasGiants] adding recipe dim=" + recipe.getDimId() + " planet=" + recipe.getPlanetName());
            recipeRegistry.addRecipe(recipe, gasGiantsUUID);
        }
        currentGasGiantRecipes.addAll(rebuilt);

        gasRefreshQueued = false;
        //AdvancedRocketry.logger.info("[JEI][GasGiants] applied runtime recipe refresh, count=" + currentGasGiantRecipes.size());
    }

    /* newer JEI doesnt have this
    //AR machines can reload recipes. We still need this for JEI to be up-to-date
    @SuppressWarnings("deprecation")
    public static void reload() {
        jeiHelpers.reload();
    }
    */
    private static boolean isVoidDrillJeiEnabled() {
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        return cfg.enableLaserDrill && !cfg.laserDrillPlanet;
    }


    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        jeiHelpers = registry.getJeiHelpers();
        IGuiHelper guiHelper = jeiHelpers.getGuiHelper();
        //debug
        //zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[JEI][GasGiants] registerCategories called");
        registry.addRecipeCategories(
            new RollingMachineCategory(guiHelper),
            new LatheCategory(guiHelper),
            new PrecisionAssemblerCategory(guiHelper),
            new SawMillCategory(guiHelper),
            new ChemicalReactorCategory(guiHelper),
            new CrystallizerCategory(guiHelper),
            new ElectrolyzerCategory(guiHelper),
            new ArcFurnaceCategory(guiHelper),
            new PlatePressCategory(guiHelper),
            new CentrifugeCategory(guiHelper),
            new PrecisionLaserEtcherCategory(guiHelper),
            new SatelliteBuilderCategory(guiHelper),
            new FuelingStationCategory(guiHelper),
            new Co2ScrubberCategory(guiHelper),
            new StationAssemblerCategory(guiHelper),
            new AsteroidCategory(guiHelper),
            new GasGiantCategory(guiHelper)
        );
        // ---- Orbital Laser Drill (VoidDrill mode only) ----
        final boolean voidDrillJei = isVoidDrillJeiEnabled();
        if (voidDrillJei) {
            registry.addRecipeCategories(new OrbitalLaserDrillCategory(guiHelper));
        }
    }



    @Override
    public void register(IModRegistry registry) {
        //debug
        //zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[JEI][GasGiants] register called");
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiModular>() {
            @Override
            @Nonnull
            public Class<GuiModular> getGuiContainerClass() {
                return GuiModular.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiModular guiContainer) {
                return guiContainer.getExtraAreasCovered();
            }

            @Override
            public Object getIngredientUnderMouse(GuiModular guiContainer,
                                                  int mouseX, int mouseY) {
                return null;
            }
        });

        IIngredientBlacklist blacklist = registry.getJeiHelpers().getIngredientBlacklist();
        //Hide problematic blocks
        blacklist.addIngredientToBlacklist(new ItemStack(AdvancedRocketryBlocks.blockForceField));
        blacklist.addIngredientToBlacklist(new ItemStack(AdvancedRocketryBlocks.blockLightSource));
        blacklist.addIngredientToBlacklist(new ItemStack(AdvancedRocketryBlocks.blockAirLock));
        //Hide problematic items
        blacklist.addIngredientToBlacklist(new ItemStack(AdvancedRocketryItems.itemSpaceStation));


        registry.addRecipeHandlers(new RollingMachineRecipeHandler(),
                new LatheRecipeHandler(),
                new PrecisionAssemblerRecipeHandler(),
                new SawMillRecipeHandler(),
                new ChemicalReactorRecipeHandler(),
                new CrystallizerRecipeHandler(),
                new ElectrolyzerRecipeHandler(),
                new ArcFurnaceRecipeHandler(),
                new PlatePressRecipeHandler(),
                new CentrifugeRecipeHandler(),
                new PrecisionLaserEtcherRecipeHandler(),
                new SatelliteBuilderRecipeHandler(),
                new FuelingStationRecipeHandler(),
                new Co2ScrubberRecipeHandler(),
                new StationAssemblerRecipeHandler(),
                new AsteroidRecipeHandler(),
                new GasGiantRecipeHandler()
            );

        registry.addRecipes(RollingMachineRecipeMaker.getMachineRecipes(jeiHelpers, TileRollingMachine.class), rollingMachineUUID);
        registry.addRecipes(LatheRecipeMaker.getMachineRecipes(jeiHelpers, TileLathe.class), latheUUID);
        registry.addRecipes(PrecisionAssemblerRecipeMaker.getMachineRecipes(jeiHelpers, TilePrecisionAssembler.class), precisionAssemblerUUID);
        registry.addRecipes(SawMillRecipeMaker.getMachineRecipes(jeiHelpers, TileCuttingMachine.class), sawMillUUID);
        registry.addRecipes(CrystallizerRecipeMaker.getMachineRecipes(jeiHelpers, TileCrystallizer.class), crystallizerUUID);
        registry.addRecipes(ArcFurnaceRecipeMaker.getMachineRecipes(jeiHelpers, TileElectricArcFurnace.class), arcFurnaceUUID);
        registry.addRecipes(PlatePressRecipeMaker.getMachineRecipes(jeiHelpers, BlockSmallPlatePress.class), platePresser);
        registry.addRecipes(ElectrolyzerRecipeMaker.getMachineRecipes(jeiHelpers, TileElectrolyser.class), electrolyzerUUID);
        registry.addRecipes(ChemicalReactorRecipeMaker.getMachineRecipes(jeiHelpers, TileChemicalReactor.class), chemicalReactorUUID);
        registry.addRecipes(CentrifugeRecipeMaker.getMachineRecipes(jeiHelpers, TileCentrifuge.class), centrifugeUUID);
        registry.addRecipes(PrecisionLaserEtcherRecipeMaker.getMachineRecipes(jeiHelpers, TilePrecisionLaserEtcher.class), precisionLaserEngraverUUID);
        registry.addRecipes(SatelliteBuilderRecipeMaker.getMachineRecipes(jeiHelpers, TileSatelliteBuilder.class), satelliteBuilderUUID);
        registry.addRecipes(FuelingStationRecipeMaker.getMachineRecipes(jeiHelpers, TileFuelingStation.class), fuelingStationUUID);
        registry.addRecipes(Co2ScrubberRecipeMaker.getRecipes(jeiHelpers), co2ScrubberUUID);
        registry.addRecipes(StationAssemblerRecipeMaker.getMachineRecipes(jeiHelpers, TileStationAssembler.class),stationAssemblerUUID);
        registry.addRecipes(AsteroidRecipeMaker.getRecipes(jeiHelpers), asteroidsUUID);
        /*//remove this?
        registry.addRecipes(
                GasGiantRecipeMaker.getMachineRecipes(jeiHelpers, TileUnmannedVehicleAssembler.class),
                gasGiantsUUID
        );
*/

        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockRollingMachine), rollingMachineUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockLathe), latheUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockPrecisionAssembler), precisionAssemblerUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockCuttingMachine), sawMillUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockCrystallizer), crystallizerUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockElectrolyser), electrolyzerUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockChemicalReactor), chemicalReactorUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockArcFurnace), arcFurnaceUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockPlatePress), platePresser);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockCentrifuge), centrifugeUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockPrecisionLaserEngraver), precisionLaserEngraverUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockSatelliteBuilder), satelliteBuilderUUID);
        // Station Assembler catalyst
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockStationBuilder), stationAssemblerUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryItems.itemSpaceStationChip), stationAssemblerUUID);
        // Co2 Scrubber catalysts
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockCO2Scrubber),  co2ScrubberUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockOxygenVent),   co2ScrubberUUID);

        // One tab: Fueling Station + Tank-type catalysts (mono / biprop fuel / oxidizer / working fluid)
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockFuelingStation), fuelingStationUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockFuelTank),             fuelingStationUUID); // mono
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockBipropellantFuelTank), fuelingStationUUID); // biprop fuel
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockOxidizerFuelTank),     fuelingStationUUID); // oxidizer
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockNuclearFuelTank),      fuelingStationUUID); // working fluid

        // Asteroids: observatory and asteroid chip are what players associate with this system
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockObservatory), asteroidsUUID);
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryItems.itemAsteroidChip), asteroidsUUID);

        // Gas missions use the Unmanned Vehicle Assembler / Deployable Rocket Builder
        registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockDeployableRocketBuilder), gasGiantsUUID);

        // ---- Orbital Laser Drill (VoidDrill mode only) ----
        // Voiddrill means laserdrillPlanet is false
        final boolean voidDrillJei = isVoidDrillJeiEnabled();
        if (voidDrillJei) {
            registry.addRecipeHandlers(new OrbitalLaserDrillRecipeHandler());
            registry.addRecipes(OrbitalLaserDrillRecipeMaker.getRecipes(jeiHelpers), orbitalLaserDrillUUID);
            registry.addRecipeCatalyst(new ItemStack(AdvancedRocketryBlocks.blockSpaceLaser), orbitalLaserDrillUUID);
        }
    }
}
