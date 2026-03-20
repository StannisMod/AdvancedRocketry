package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoEntityProvider;
import mcjty.theoneprobe.api.NumberFormat;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.StatsRocket;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.item.ItemPlanetIdentificationChip;
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.util.StationLandingLocation;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.Vector3F;

import java.util.Locale;

public class RocketEntityProbeProvider implements IProbeInfoEntityProvider {

    private static final int FUEL_BORDER_COLOR = 0xFF555555;
    private static final int FUEL_BACKGROUND_COLOR = 0xFF000000;
    private static final int FUEL_FILLED_COLOR = 0xFF284892;
    private static final int FUEL_ALT_FILLED_COLOR = 0xFF162F69;

    @Override
    public String getID() {
        return "advancedrocketry:rocket_entity";
    }

    @Override
    public void addProbeEntityInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, Entity entity, IProbeHitEntityData data) {
        if (!(entity instanceof EntityRocket)) {
            return;
        }

        EntityRocket rocket = (EntityRocket) entity;
        StatsRocket stats = rocket.getRocketStats();

        addGuidanceInfo(probeInfo, rocket);

        if (mode == ProbeMode.EXTENDED) {
            addFuelInfo(probeInfo, rocket, stats);
        }
    }

    private static String tr(String key) {
        return LibVulpes.proxy.getLocalizedString(key);
    }

    private static String trf(String key, Object... args) {
        return new TextComponentTranslation(key, args).getUnformattedText();
    }

    private static void addGuidanceInfo(IProbeInfo probeInfo, EntityRocket rocket) {
        if (rocket.storage == null) {
            return;
        }

        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc == null) {
            probeInfo.text(tr("msg.top.advancedrocketry.guidance.noComputer"));
            return;
        }

        ItemStack stack = gc.getStackInSlot(0);
        if (stack.isEmpty()) {
            probeInfo.text(tr("msg.top.advancedrocketry.guidance.noDestination"));
            return;
        }

        String primaryText = getGuidancePrimaryText(rocket, gc, stack);

        IProbeInfo row = probeInfo.horizontal();
        row.item(stack, probeInfo.defaultItemStyle().width(16).height(16));
        row.text(primaryText);
    }

    private static String getGuidancePrimaryText(EntityRocket rocket, TileGuidanceComputer gc, ItemStack stack) {
        if (stack.getItem() instanceof ItemAsteroidChip) {
            ItemAsteroidChip chip = (ItemAsteroidChip) stack.getItem();
            String type = chip.getType(stack);
            Long uuid = chip.getUUID(stack);

            if (uuid == null || type == null || type.isEmpty()) {
                return tr("msg.top.advancedrocketry.guidance.unprogrammed");
            }

            return trf(
                    "msg.top.advancedrocketry.guidance.asteroidWithId",
                    type,
                    ItemAsteroidChip.shortDisplayId(uuid, type)
            );
        }

        if (stack.getItem() instanceof ItemStationChip) {
            int stationId = ItemStationChip.getUUID(stack);
            if (stationId == 0) {
                return tr("msg.top.advancedrocketry.guidance.unprogrammed");
            }
            return trf("msg.top.advancedrocketry.guidance.station", stationId);
        }

        if (stack.getItem() instanceof ItemPlanetIdentificationChip) {
            ItemPlanetIdentificationChip chip = (ItemPlanetIdentificationChip) stack.getItem();

            if (!chip.hasValidDimension(stack)) {
                return tr("msg.top.advancedrocketry.guidance.unprogrammed");
            }

            if (chip.getDimensionProperties(stack) == null) {
                return tr("msg.top.advancedrocketry.guidance.unprogrammed");
            }

            return chip.getDimensionProperties(stack).getName();
        }

        if (isLinker(stack)) {
            if (isUnprogrammedLinker(stack)) {
                return tr("msg.top.advancedrocketry.guidance.unprogrammed");
            }
            return getCurrentLaunchDestinationText(rocket, gc, stack);
        }

        String resolved = getCurrentLaunchDestinationText(rocket, gc, stack);
        if (resolved.equals(tr("msg.top.advancedrocketry.guidance.unprogrammed"))) {
            return resolved;
        }

        return stripTrailingCoords(resolved);
    }

    private static String getCurrentLaunchDestinationText(EntityRocket rocket, TileGuidanceComputer gc, ItemStack stack) {
        int currentDim = rocket.world.provider.getDimension();
        int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);

        if (stack.isEmpty()
                && ARConfiguration.getCurrentConfig().experimentalSpaceFlight
                && destDim != Constants.INVALID_PLANET) {
            return tr("msg.top.advancedrocketry.guidance.orbit");
        }

        if (destDim == Constants.INVALID_PLANET || destDim == SpaceObjectManager.WARPDIMID) {
            return tr("msg.top.advancedrocketry.guidance.unprogrammed");
        }

        if (stack.getItem() instanceof ItemStationChip
                && destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
            int stationId = ItemStationChip.getUUID(stack);
            if (stationId != 0) {
                return trf("msg.top.advancedrocketry.guidance.station", stationId);
            }
            return tr("msg.top.advancedrocketry.guidance.unprogrammed");
        }

        Vector3F<Float> loc = rocket.storage.getDestinationCoordinates(destDim, false);

        if (destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
            if (loc != null) {
                ISpaceObject station = SpaceObjectManager.getSpaceManager()
                        .getSpaceStationFromBlockCoords(new BlockPos(loc.x, loc.y, loc.z));

                if (station != null) {
                    String text = trf("msg.top.advancedrocketry.guidance.station", station.getId());

                    StationLandingLocation pad = gc.getLandingLocation(station.getId());
                    if (pad != null) {
                        text += trf("msg.top.advancedrocketry.guidance.pad", pad);
                    }

                    return text;
                }
            }

            return tr("msg.top.advancedrocketry.guidance.space");
        }

        String text = DimensionManager.getInstance().getDimensionProperties(destDim).getName();

        String name = gc.getDestinationName(destDim);
        if (!name.isEmpty()) {
            text += " - " + name;
        }

        if (loc != null) {
            text += String.format(Locale.ROOT, " (%.0f, %.0f)", loc.x, loc.z);
        }

        return text;
    }



    private static boolean isLinker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemLinker;
    }

    private static boolean isUnprogrammedLinker(ItemStack stack) {
        return isLinker(stack) && !ItemLinker.isSet(stack);
    }

    private static String stripTrailingCoords(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)$", "");
    }
    private static void addFuelInfo(IProbeInfo probeInfo, EntityRocket rocket, StatsRocket stats) {
        FuelType mainFuel = rocket.getRocketFuelType();
        if (mainFuel == null) {
            return;
        }

        switch (mainFuel) {
            case LIQUID_MONOPROPELLANT:
                addFuelSection(
                        probeInfo,
                        tr("msg.top.advancedrocketry.fuel.label"),
                        stats.getFuelFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT)
                );
                break;

            case LIQUID_BIPROPELLANT:
                addFuelSection(
                        probeInfo,
                        tr("msg.top.advancedrocketry.fuel.label"),
                        stats.getFuelFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT)
                );

                addFuelSection(
                        probeInfo,
                        tr("msg.top.advancedrocketry.fuel.oxidizer"),
                        stats.getOxidizerFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_OXIDIZER),
                        rocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER)
                );
                break;

            case NUCLEAR_WORKING_FLUID:
                addFuelSection(
                        probeInfo,
                        tr("msg.top.advancedrocketry.fuel.workingFluid"),
                        stats.getWorkingFluid(),
                        rocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID),
                        rocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID)
                );
                break;
        }
    }

    private static void addFuelSection(IProbeInfo probeInfo, String label, String registryName, int amount, int capacity) {
        if (capacity <= 0) {
            return;
        }

        String fluidDisplayName = getPrettyFluidName(registryName);

        if (fluidDisplayName != null) {
            probeInfo.text(label + ": " + fluidDisplayName);
        } else if (amount > 0) {
            probeInfo.text(label + ": " + tr("msg.top.advancedrocketry.fuel.unknownFluid"));
        } else {
            probeInfo.text(label + ":");
        }

        probeInfo.progress(
                amount,
                capacity,
                probeInfo.defaultProgressStyle()
                        .borderColor(FUEL_BORDER_COLOR)
                        .backgroundColor(FUEL_BACKGROUND_COLOR)
                        .filledColor(FUEL_FILLED_COLOR)
                        .alternateFilledColor(FUEL_ALT_FILLED_COLOR)
                        .height(12)
                        .width(100)
                        .showText(true)
                        .suffix(" mB")
                        .numberFormat(NumberFormat.COMMAS)
        );
    }

    private static String getPrettyFluidName(String registryName) {
        if (registryName == null || registryName.isEmpty() || "null".equals(registryName)) {
            return null;
        }

        Fluid fluid = FluidRegistry.getFluid(registryName);
        if (fluid == null) {
            return registryName;
        }

        try {
            return fluid.getLocalizedName(new FluidStack(fluid, 1));
        } catch (Exception e) {
            return fluid.getName();
        }
    }
}