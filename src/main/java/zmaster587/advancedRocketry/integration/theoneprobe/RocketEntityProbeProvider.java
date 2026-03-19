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
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.util.StationLandingLocation;
import zmaster587.libVulpes.util.Vector3F;

public class RocketEntityProbeProvider implements IProbeInfoEntityProvider {

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

        FuelType mainFuel = rocket.getRocketFuelType();
        if (mainFuel != null) {
            switch (mainFuel) {
                case LIQUID_MONOPROPELLANT:
                    addFuelSection(probeInfo, "Fuel", stats.getFuelFluid(), "Monopropellant",
                            rocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT),
                            rocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT));
                    break;

                case LIQUID_BIPROPELLANT:
                    addFuelSection(probeInfo, "Fuel", stats.getFuelFluid(), "Bipropellant Fuel",
                            rocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT),
                            rocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT));

                    addFuelSection(probeInfo, "Oxidizer", stats.getOxidizerFluid(), "Oxidizer",
                            rocket.getFuelAmount(FuelType.LIQUID_OXIDIZER),
                            rocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER));
                    break;

                case NUCLEAR_WORKING_FLUID:
                    addFuelSection(probeInfo, "Working Fluid", stats.getWorkingFluid(), "Working Fluid",
                            rocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID),
                            rocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID));
                    break;
            }
        }

        if (mode == ProbeMode.EXTENDED) {
            addGuidanceInfo(probeInfo, rocket);
        }
    }

    private static void addGuidanceInfo(IProbeInfo probeInfo, EntityRocket rocket) {
        if (rocket.storage == null) {
            return;
        }

        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc == null) {
            probeInfo.text("Guidance Computer: Missing");
            return;
        }

        ItemStack stack = gc.getStackInSlot(0);

        IProbeInfo row = probeInfo.horizontal();
        if (stack.isEmpty()) {
            row.text("Guidance Computer: Empty");
        } else {
            row.item(stack, probeInfo.defaultItemStyle().width(16).height(16));
            row.itemLabel(stack);
        }

        probeInfo.text("Destination: " + getCurrentLaunchDestinationText(rocket, gc, stack));
    }

    private static String getCurrentLaunchDestinationText(EntityRocket rocket, TileGuidanceComputer gc, ItemStack stack) {
        int currentDim = rocket.world.provider.getDimension();

        int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);

        if (stack.isEmpty()
                && ARConfiguration.getCurrentConfig().experimentalSpaceFlight
                && destDim != Constants.INVALID_PLANET) {
            return "Orbit";
        }

        if (destDim == Constants.INVALID_PLANET || destDim == SpaceObjectManager.WARPDIMID) {
            return "Not programmed";
        }

        // Special-case station chips:
        // if the rocket would launch to the space dimension, the actual target station
        // is stored on the chip UUID, not in destDim.
        if (stack.getItem() instanceof ItemStationChip
                && destDim == ARConfiguration.getCurrentConfig().spaceDimId) {

            int stationId = ItemStationChip.getUUID(stack);
            if (stationId != 0) {
                return "Station " + stationId;
            }

            return "Unprogrammed Station Chip";
        }

        // Must happen after getDestinationDimId() for side-effect-sensitive cases.
        Vector3F<Float> loc = rocket.storage.getDestinationCoordinates(destDim, false);

        if (destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
            if (loc != null) {
                ISpaceObject station = SpaceObjectManager.getSpaceManager()
                        .getSpaceStationFromBlockCoords(new BlockPos(loc.x, loc.y, loc.z));

                if (station != null) {
                    String text = "Station " + station.getId();

                    StationLandingLocation pad = gc.getLandingLocation(station.getId());
                    if (pad != null) {
                        text += " / Pad " + pad;
                    }

                    return text;
                }
            }
            return "Space";
        }

        String text = DimensionManager.getInstance().getDimensionProperties(destDim).getName();

        String name = gc.getDestinationName(destDim);
        if (!name.isEmpty()) {
            text += " - " + name;
        }

        if (loc != null) {
            text += String.format(" (%.0f, %.0f)", loc.x, loc.z);
        } else {
            text += " (coords unknown)";
        }

        return text;
    }

    private static void addFuelSection(IProbeInfo probeInfo, String label, String registryName, String fallbackName, int amount, int capacity) {
        if (capacity <= 0) {
            return;
        }

        probeInfo.text(label + ": " + getPrettyFluidName(registryName, fallbackName));
        probeInfo.progress(amount, capacity,
                probeInfo.defaultProgressStyle()
                        .suffix(" mB")
                        .numberFormat(NumberFormat.COMMAS));
    }

    private static String getPrettyFluidName(String registryName, String fallbackName) {
        if (registryName == null || registryName.isEmpty() || "null".equals(registryName)) {
            return fallbackName;
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

    private static String getDestinationFromChip(ItemStack stack, TileGuidanceComputer gc) {
        if (stack.isEmpty()) {
            return null;
        }

        // Preferred:
        // if your station-chip item class has a real getter for station id/name, use that here.
        // Example idea:
        // if (stack.getItem() instanceof ItemStationChip) {
        //     int stationId = ((ItemStationChip) stack.getItem()).getStationId(stack);
        //     String text = "Station " + stationId;
        //     StationLandingLocation pad = gc.getLandingLocation(stationId);
        //     if (pad != null) text += " / Pad " + pad;
        //     return text;
        // }

        // Fallback:
        String displayName = stack.getDisplayName();
        if (displayName != null && displayName.toLowerCase().contains("station")) {
            String text = simplifyStationChipName(displayName);

            Integer stationId = extractStationId(displayName);
            if (stationId != null) {
                StationLandingLocation pad = gc.getLandingLocation(stationId);
                if (pad != null) {
                    text += " / Pad " + pad;
                }
            }

            return text;
        }

        return null;
    }

    private static String simplifyStationChipName(String displayName) {
        String text = displayName;

        text = text.replace("Space Station #", "Station ");
        text = text.replace("Space Station ", "Station ");
        text = text.replace(" ID Chip", "");
        text = text.trim();

        return text;
    }

    private static Integer extractStationId(String displayName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(displayName);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}