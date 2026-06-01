package zmaster587.advancedRocketry.item;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.network.PacketSatellite;
import zmaster587.advancedRocketry.satellite.SatelliteBiomeChanger;
import zmaster587.advancedRocketry.satellite.SatelliteWeatherController;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.GuiHandler;
import zmaster587.libVulpes.inventory.TextureResources;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.INetworkItem;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketItemModifcation;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class ItemWeatherController extends ItemSatelliteIdentificationChip implements IModularInventory, IButtonInventory, INetworkItem {

    private int floodlevel = 63;
    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> list = new LinkedList<>();

        SatelliteWeatherController sat = (SatelliteWeatherController) getSatellite(player.getHeldItem(EnumHand.MAIN_HAND));
        if (player.world.isRemote) {
            //list.add(new ModuleImage(24, 14, zmaster587.advancedRocketry.inventory.TextureResources.earthCandyIcon));
        }

        list.add(new ModuleButton(32, 16 + 24 * (1), 1, "dry", this, TextureResources.buttonBuild));
        list.add(new ModuleButton(32, 16 + 24 * (2), 0, "rain", this, TextureResources.buttonBuild));
        list.add(new ModuleButton(32, 16 + 24 * (3), 2, "flood", this, TextureResources.buttonBuild));
        list.add(new ModuleButton(90, 19+24*3, 3, "", this, zmaster587.libVulpes.inventory.TextureResources.buttonLeft, 5, 8));
        list.add(new ModuleText(100, 19+24*3, "y="+sat.getFloodlevel(),0x2d2d2d));
        list.add(new ModuleButton(130, 19+24*3, 4, "", this, TextureResources.buttonRight, 5, 8));
        list.add(new ModulePower(16, 48, sat.getBattery()));

        return list;
    }

    @Override
    public void addInformation(@Nonnull ItemStack stack, World world, List<String> list, ITooltipFlag flag) {

        // If unprogrammed, let the superclass handle the "unprogrammed" tooltip
        if (!stack.hasTagCompound()) {
            super.addInformation(stack, world, list, flag);
            return;
        }

        SatelliteBase sat = SatelliteRegistry.getSatellite(stack);
        SatelliteWeatherController mapping = null;

        if (sat instanceof SatelliteWeatherController)
            mapping = (SatelliteWeatherController) sat;

        if (mapping == null) {
            list.add(LibVulpes.proxy.getLocalizedString("msg.biomechanger.nosat"));
        } else if (mapping.getDimensionId() == world.provider.getDimension()) {
            list.add(LibVulpes.proxy.getLocalizedString("msg.connected"));
            if (mapping.mode_id == 0)
                list.add(LibVulpes.proxy.getLocalizedString("tooltip.advancedrocketry.weathercontrollerremote.mode.rain"));
            if (mapping.mode_id == 1)
                list.add(LibVulpes.proxy.getLocalizedString("tooltip.advancedrocketry.weathercontrollerremote.mode.dry"));
            if (mapping.mode_id == 2)
                list.add(LibVulpes.proxy.getLocalizedString("tooltip.advancedrocketry.weathercontrollerremote.mode.flood"));
        } else {
            list.add(LibVulpes.proxy.getLocalizedString("msg.notconnected"));
        }

        // Still let the parent add its usual info
        super.addInformation(stack, world, list, flag);
    }


    @Override
    @Nonnull
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);


        if (!world.isRemote) {
            SatelliteBase sat = SatelliteRegistry.getSatellite(stack);
            if (!(sat instanceof SatelliteWeatherController))
                return super.onItemRightClick(world, player, hand);

            if (sat != null) {
                if (player.isSneaking()) {
                        ((SatelliteWeatherController) sat).floodlevel = player.getPosition().getY();
                        PacketHandler.sendToPlayer(new PacketSatellite(sat), player);
                        player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(), world, -1, -1, 0);
                } else {
                    //Attempt to change weather only if player is in the same dimension
                    if (sat.getDimensionId() == world.provider.getDimension()) {
                        sat.performAction(player, world, player.getPosition());
                    }
                }
            }
        }
        player.swingArm(hand);
        return super.onItemRightClick(world, player, hand);
    }


    @Override
    public String getModularInventoryName() {
        return "item.weatherController.name";
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onInventoryButtonPressed(int buttonId) {

        ItemStack stack = Minecraft.getMinecraft().player.getHeldItem(EnumHand.MAIN_HAND);
        SatelliteBase sat = getSatellite(stack);
        if (sat instanceof SatelliteWeatherController) {
            if (buttonId == 0 || buttonId == 1 || buttonId == 2) {
                ((SatelliteWeatherController) sat).mode_id = buttonId;
                Minecraft.getMinecraft().player.closeScreen();
            }
            if (buttonId == 4)
                if (((SatelliteWeatherController) sat).floodlevel < 180) {
                    ((SatelliteWeatherController) sat).floodlevel += 1;
                    Minecraft.getMinecraft(). player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(), net.minecraftforge.common.DimensionManager.getWorld( sat.getDimensionId()), -1, -1, 0);
                }
            if (buttonId == 3)
                if (((SatelliteWeatherController) sat).floodlevel > 1){
                    ((SatelliteWeatherController) sat).floodlevel-=1;
                    Minecraft.getMinecraft().player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(), net.minecraftforge.common.DimensionManager.getWorld( sat.getDimensionId()), -1, -1, 0);
                }

            PacketHandler.sendToServer(new PacketItemModifcation(this, Minecraft.getMinecraft().player, (byte) buttonId));
            //Minecraft.getMinecraft().player.closeScreen();
            //
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf byteBuf, byte b, @Nonnull ItemStack itemStack) {
        SatelliteWeatherController sat = (SatelliteWeatherController) getSatellite(itemStack);
        byteBuf.writeInt(sat.mode_id);
        byteBuf.writeInt(sat.floodlevel);
        byteBuf.writeInt(sat.last_mode_id);
    }

    @Override
    public void readDataFromNetwork(ByteBuf byteBuf, byte b, NBTTagCompound nbtTagCompound, @Nonnull ItemStack itemStack) {
        nbtTagCompound.setInteger("mode_id", byteBuf.readInt());
        nbtTagCompound.setInteger("floodlevel", byteBuf.readInt());
        nbtTagCompound.setInteger("last_mode_id", byteBuf.readInt());
    }

    @Override
    public void useNetworkData(EntityPlayer entityPlayer, Side side, byte b, NBTTagCompound nbtTagCompound, @Nonnull ItemStack itemStack) {
        SatelliteWeatherController sat = (SatelliteWeatherController) getSatellite(itemStack);
        sat.mode_id = nbtTagCompound.getInteger("mode_id");
        sat.floodlevel = nbtTagCompound.getInteger("floodlevel");
        sat.last_mode_id = nbtTagCompound.getInteger("last_mode_id");
    }
}
