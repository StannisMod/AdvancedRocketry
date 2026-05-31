package zmaster587.advancedRocketry.integration.dataloaders;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
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
import zmaster587.advancedRocketry.entity.EntityStationDeployedRocket;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.item.ItemPlanetIdentificationChip;
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.util.StationLandingLocation;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.util.Vector3F;

/**
 * Used to load various description strings from objects of type T.
 * This type must be able to access the following data:
 * - Guidance Computer stack. If this is null, there is no guidance computer.
 * - Rocket Entity
 * - Guidance Computer Destination
 */
public abstract class RocketDataLoader {
    abstract protected EntityRocket getRocket();
    abstract public @Nullable ItemStack getGuidanceComputer();
    abstract public @Nullable StationLandingLocation getLandingLocation();
    abstract public @Nullable String getDestinationName();

    private static final TextFormatting GUIDANCE_UNSET_COLOR = TextFormatting.GRAY;
    private static final TextFormatting GUIDANCE_RESOLVED_COLOR = TextFormatting.YELLOW;
    private static final int FUEL_BORDER_COLOR = 0xFF555555;
    private static final int FUEL_BACKGROUND_COLOR = 0xFF000000;
    private static final int FUEL_FILLED_COLOR = 0xFF284892;
    private static final int FUEL_ALT_FILLED_COLOR = 0xFF162F69;

    public void addGuidanceInfo(AbstractDataContext context) {
        EntityRocket rocket = getRocket();
        if (rocket instanceof EntityStationDeployedRocket) {
            addHarvestInfo(context, (EntityStationDeployedRocket) rocket);
            return;
        }

        ItemStack gcStack = getGuidanceComputer();
        if (gcStack == null) {
            context.addMessage(context.translate("msg.top.advancedrocketry.guidance.noComputer"), GUIDANCE_UNSET_COLOR);
            return;
        }

        if (gcStack.isEmpty()) {
            context.addMessage(context.translate("msg.top.advancedrocketry.guidance.noDestination"), GUIDANCE_UNSET_COLOR);
            return;
        }

        context.pushStack(gcStack);
        addGuidancePrimaryText(context, gcStack);
        context.popStack();
    }

    public void addFuelInfo(AbstractDataContext context) {
        EntityRocket rocket = getRocket();
        StatsRocket stats = rocket.getRocketStats();
        FuelType mainFuel = rocket.getRocketFuelType();
        if (mainFuel == null) {
            return;
        }

        switch (mainFuel) {
            case LIQUID_MONOPROPELLANT:
                addFuelSection(
                        context,
                        context.translate("msg.top.advancedrocketry.fuel.label"),
                        stats.getFuelFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT)
                );
                break;

            case LIQUID_BIPROPELLANT:
                addFuelSection(
                        context,
                        context.translate("msg.top.advancedrocketry.fuel.label"),
                        stats.getFuelFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT)
                );

                addFuelSection(
                        context,
                        context.translate("msg.top.advancedrocketry.fuel.oxidizer"),
                        stats.getOxidizerFluid(),
                        rocket.getFuelAmount(FuelType.LIQUID_OXIDIZER),
                        rocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER)
                );
                break;

            case NUCLEAR_WORKING_FLUID:
                addFuelSection(
                        context,
                        context.translate("msg.top.advancedrocketry.fuel.workingFluid"),
                        stats.getWorkingFluid(),
                        rocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID),
                        rocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID)
                );
                break;
            
            default:
                // NYI
                break;
        }
    }

    private void addHarvestInfo(AbstractDataContext context, EntityStationDeployedRocket rocket) {
        Fluid gas = rocket.getSelectedHarvestGas();
        if (gas == null) {
            return;
        }

        

        context.addMessage(
                context.translate("msg.top.advancedrocketry.harvest.gas")
                        + ": "
                        + net.minecraft.util.text.TextFormatting.AQUA
                        + getFluidDisplayName(context, gas)
        );
    }

    private String getPrettyFluidName(AbstractDataContext context, String registryName) {
        if (registryName == null || registryName.isEmpty() || "null".equals(registryName)) {
            return null;
        }

        Fluid fluid = FluidRegistry.getFluid(registryName);
        if (fluid == null) {
            return registryName;
        }

        return getFluidDisplayName(context, fluid);
    }

    private String getFluidDisplayName(AbstractDataContext context, Fluid fluid) {
        try {
            return context.translate(fluid.getUnlocalizedName(new FluidStack(fluid, 1)));
        } catch (Exception e) {
            try {
                return context.translate(fluid.getUnlocalizedName());
            } catch (Exception ignored) {
                return fluid.getName();
            }
        }
    }

    private void addFuelSection(AbstractDataContext context, String label, String registryName, int amount, int capacity) {
        if (capacity <= 0) {
            return;
        }

        String fluidDisplayName = getPrettyFluidName(context, registryName);

        String message;
        if (fluidDisplayName != null) {
            message = label + ": " + fluidDisplayName;
        } else if (amount > 0) {
            message = label + ": " + context.translate("msg.top.advancedrocketry.fuel.unknownFluid");
        } else {
            message = label + ": " + context.translate("msg.top.advancedrocketry.fuel.noFuel");
        }

        context.addProgressBar(message, amount, capacity,
            FUEL_BORDER_COLOR, FUEL_BACKGROUND_COLOR, FUEL_FILLED_COLOR, FUEL_ALT_FILLED_COLOR,
            "mB");
    }

    private String wrapDestination(AbstractDataContext context, String text) {
        return context.supportsRichData()
            ? text
            : context.translate("msg.top.advancedrocketry.guidance.destination") + " " + text;
    }

    private void addGuidancePrimaryText(AbstractDataContext context, ItemStack stack) {
        if (stack.getItem() instanceof ItemAsteroidChip) {
            ItemAsteroidChip chip = (ItemAsteroidChip) stack.getItem();
            String type = chip.getType(stack);
            Long uuid = chip.getUUID(stack);

            if (uuid == null || type == null || type.isEmpty()) {
                context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            } else {
                context.addMessage(wrapDestination(context, type + " (" + ItemAsteroidChip.shortDisplayId(uuid, type) + ")"), 
                        GUIDANCE_RESOLVED_COLOR);
            }

        }

        else if (stack.getItem() instanceof ItemStationChip) {
            int stationId = ItemStationChip.getUUID(stack);
            if (stationId == 0) {
                context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            } else {
                context.addMessage(wrapDestination(context, context.translate("msg.top.advancedrocketry.guidance.station") + " " + stationId),
                        GUIDANCE_RESOLVED_COLOR);
            }
        }

        else if (stack.getItem() instanceof ItemPlanetIdentificationChip) {
            ItemPlanetIdentificationChip chip = (ItemPlanetIdentificationChip) stack.getItem();

            if (!chip.hasValidDimension(stack) || chip.getDimensionProperties(stack) == null) {
                context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            } else {
                context.addMessage(wrapDestination(context, chip.getDimensionProperties(stack).getName()), GUIDANCE_RESOLVED_COLOR);
            }

        }

        else if (isLinker(stack)) {
            if (isUnprogrammedLinker(stack)) {
                context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            } else {
                addCurrentLaunchDestinationText(context, stack, true);
            }
        }

        else {
            addCurrentLaunchDestinationText(context, stack, false);
        }
    }

    private void addCurrentLaunchDestinationText(AbstractDataContext context, ItemStack stack, boolean showTrailingCoords) {
        EntityRocket rocket = getRocket();
        int currentDim = rocket.world.provider.getDimension();
        int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);

        if (stack.isEmpty()
                && ARConfiguration.getCurrentConfig().experimentalSpaceFlight
                && destDim != Constants.INVALID_PLANET) {
            context.addMessage(wrapDestination(context, context.translate("msg.top.advancedrocketry.guidance.orbit")), GUIDANCE_RESOLVED_COLOR);
            return;
        }

        if (destDim == Constants.INVALID_PLANET || destDim == SpaceObjectManager.WARPDIMID) {
            context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            return;
        }

        if (stack.getItem() instanceof ItemStationChip
                && destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
            int stationId = ItemStationChip.getUUID(stack);
            if (stationId != 0) {
                context.addMessage(wrapDestination(context, context.translate("msg.top.advancedrocketry.guidance.station") + " " + stationId), GUIDANCE_RESOLVED_COLOR);
            } else {
                context.addMessage(context.translate("msg.top.advancedrocketry.guidance.unprogrammed"), GUIDANCE_UNSET_COLOR);
            }
            return;
        }

        Vector3F<Float> loc = rocket.storage.getDestinationCoordinates(destDim, false);

        if (destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject station = loc == null
                ? null
                : SpaceObjectManager.getSpaceManager()
                        .getSpaceStationFromBlockCoords(new BlockPos(loc.x, loc.y, loc.z));

            if (station != null) {
                String text = context.translate("msg.top.advancedrocketry.guidance.station") + " " + station.getId();

                StationLandingLocation pad = getLandingLocation();
                if (pad != null) {
                    text += " " + context.translate("msg.top.advancedrocketry.guidance.pad") + " " + pad;
                }

                context.addMessage(wrapDestination(context, text), GUIDANCE_RESOLVED_COLOR);
            } else {
                context.addMessage(wrapDestination(context, context.translate("msg.top.advancedrocketry.guidance.space")), GUIDANCE_RESOLVED_COLOR);
            }
            return;
        }

        String text = DimensionManager.getInstance().getDimensionProperties(destDim).getName();

        String name = getDestinationName();
        if (!name.isEmpty()) {
            text += " - " + name;
        }

        if (loc != null && showTrailingCoords) {
            text += String.format(Locale.ROOT, " (%.0f, %.0f)", loc.x, loc.z);
        }

        context.addMessage(text, GUIDANCE_RESOLVED_COLOR);
    }

    private static boolean isLinker(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemLinker;
    }

    private static boolean isUnprogrammedLinker(ItemStack stack) {
        return isLinker(stack) && !ItemLinker.isSet(stack);
    }

}
