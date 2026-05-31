package zmaster587.advancedRocketry.integration.waila;

import java.util.List;

import javax.annotation.Nullable;

import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaEntityAccessor;
import mcp.mobius.waila.api.IWailaEntityProvider;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.integration.dataloaders.AbstractDataContext;
import zmaster587.advancedRocketry.integration.dataloaders.RocketDataLoader;
import zmaster587.advancedRocketry.integration.dataloaders.RocketDataLoaderServer;
import zmaster587.advancedRocketry.util.StationLandingLocation;
import zmaster587.libVulpes.util.HashedBlockPosition;

public class RocketEntityProvider implements IWailaEntityProvider {
    static class WailaRocketDataLoader extends RocketDataLoader {
        EntityRocket rocket;
        ItemStack stack;
        NBTTagCompound stuff;

        WailaRocketDataLoader(EntityRocket rocket, ItemStack stack, NBTTagCompound tag) {
            this.rocket = rocket;
            this.stack = stack;
            this.stuff = tag;
        }

		@Override
		protected EntityRocket getRocket() {
			return rocket;
		}

		@Override
		public ItemStack getGuidanceComputer() {
			return stack;
		}

		@Override
		public @Nullable StationLandingLocation getLandingLocation() {
            NBTTagCompound landing = stuff.getCompoundTag("landing");
			int x = landing.getInteger("x");
            short y = landing.getShort("y");
            int z = landing.getInteger("z");
            String name = landing.getString("name");
            return new StationLandingLocation(new HashedBlockPosition(x, y, z), name);
		}

		@Override
		public String getDestinationName() {
			return stuff.getString("dest");
		}
    }

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, Entity entity, NBTTagCompound tag, World world) {
        if (entity instanceof EntityRocket) {
            EntityRocket rocket = (EntityRocket) entity;
            RocketDataLoader loader = new RocketDataLoaderServer(rocket);

            StationLandingLocation pad = loader.getLandingLocation();
            if (pad != null) {
                NBTTagCompound landing = new NBTTagCompound();
                landing.setInteger("x", pad.getPos().x);
                landing.setShort("y", pad.getPos().y);
                landing.setInteger("z", pad.getPos().z);
                landing.setString("name", pad.getName());
                tag.setTag("landing", landing);
            }

            ItemStack stack = loader.getGuidanceComputer();
            if (stack != null) {
                tag.setTag("stack", stack.writeToNBT(new NBTTagCompound()));
            }

            String name = loader.getDestinationName();
            if (name != null) {
                tag.setString("dest", name);
            }

            tag.setInteger("fuelType", rocket.getRocketFuelType().id);
        }
        return tag;
    }

    @Override
    public List<String> getWailaBody(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        if (entity instanceof EntityRocket) {
            EntityRocket rocket = (EntityRocket) entity;
            ItemStack computer = null;
            NBTTagCompound nbt = accessor.getNBTData();
            if (nbt.hasKey("stack")) {
                computer = new ItemStack(nbt.getCompoundTag("stack"));
            }
            WailaRocketDataLoader loader = new WailaRocketDataLoader(rocket, computer, nbt);

            AbstractDataContext ctx = new WailaDataContext(currenttip);
            loader.addGuidanceInfo(ctx);
            loader.addFuelInfo(ctx);
        }

        return currenttip;
    }

    @Override
    public List<String> getWailaHead(Entity entity, List<String> currenttip, IWailaEntityAccessor accessor, IWailaConfigHandler config) {
        NBTTagCompound nbt = accessor.getNBTData();
        if (nbt.hasKey("fuelType")) {
            currenttip.remove(0);
            currenttip.add(TextFormatting.WHITE + getRocketDisplayName(FuelType.getById(nbt.getInteger("fuelType"))) + TextFormatting.RESET);
        }
        return currenttip;
    }

    private static String getRocketDisplayName(FuelType mainFuel) {
        if (mainFuel == FuelType.LIQUID_MONOPROPELLANT) {
            return I18n.format("msg.top.advancedrocketry.rocket.monopropellant");
        }
        if (mainFuel == FuelType.LIQUID_BIPROPELLANT) {
            return I18n.format("msg.top.advancedrocketry.rocket.bipropellant");
        }
        if (mainFuel == FuelType.NUCLEAR_WORKING_FLUID) {
            return I18n.format("msg.top.advancedrocketry.rocket.nuclear");
        }
        return I18n.format("entity.advancedrocketry.rocket.name");
    }

}
