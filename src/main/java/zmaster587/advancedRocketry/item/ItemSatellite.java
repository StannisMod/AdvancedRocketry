package zmaster587.advancedRocketry.item;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.EmbeddedInventory;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;
import java.util.List;

public class ItemSatellite extends ItemIdWithName {

    //Guarding inventory to ensure only valid items are placed in slots.
    public static class SatelliteModuleInventory extends EmbeddedInventory {
        public SatelliteModuleInventory() { super(7); } // slots 0-6 embedded from chassis

        @Override
        public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
            if (stack.isEmpty()) return false;

            // Registry-driven: only accept items that have SatelliteProperties
            SatelliteProperties p = SatelliteRegistry.getSatelliteProperty(stack);
            if (p == null) return false;
            int f = p.getPropertyFlag();

            // Slot 0: ONLY primary function meta 0-6
            if (slot == 0) {
                return SatelliteProperties.Property.MAIN.isOfType(f);
            }

            // Slots 1–6: power gen, battery, or data modules
            if (slot >= 1 && slot <= 6) {
                return  SatelliteProperties.Property.POWER_GEN.isOfType(f) ||
                        SatelliteProperties.Property.BATTERY.isOfType(f)   ||
                        SatelliteProperties.Property.DATA.isOfType(f);
            }

            return false;
        }

        @Override
        public void setInventorySlotContents(int index, ItemStack stack) {
            if (!stack.isEmpty() && !isItemValidForSlot(index, stack)) return;
            super.setInventorySlotContents(index, stack);
        }
    }


    public EmbeddedInventory readInvFromNBT(@Nonnull ItemStack stackIn) {
        EmbeddedInventory inv = new SatelliteModuleInventory(); // slots 0-6 embedded from chassis, guarded by class above
        if (!stackIn.hasTagCompound() || !stackIn.getTagCompound().hasKey("inv"))
            return inv;

        inv.readFromNBT(stackIn.getTagCompound().getCompoundTag("inv"));
        return inv;
    }

    public void writeInvToNBT(@Nonnull ItemStack stackIn, EmbeddedInventory inv) {
        NBTTagCompound nbt = new NBTTagCompound();
        if (!stackIn.hasTagCompound())
            stackIn.setTagCompound(nbt);
        else
            nbt = stackIn.getTagCompound();

        NBTTagCompound tag = new NBTTagCompound();
        inv.writeToNBT(tag);
        nbt.setTag("inv", tag);
    }

    public void setSatellite(@Nonnull ItemStack stack, SatelliteProperties properties) {

        SatelliteBase testSatellite = SatelliteRegistry.getNewSatellite(properties.getSatelliteType());
        if (testSatellite != null) {
            //Check to see if we have some NBT already, if so, add to it
            NBTTagCompound nbt;
            if (stack.hasTagCompound())
                nbt = stack.getTagCompound();
            else
                nbt = new NBTTagCompound();

            //Stick the properties into the NBT of the stack
            properties.writeToNBT(nbt);
            stack.setTagCompound(nbt);

            setName(stack, testSatellite.getName());
        } else
            stack.setTagCompound(null);

    }


    @Override
    public void addInformation(@Nonnull ItemStack stack, World player, List<String> list, ITooltipFlag bool) {
        if (stack.getItem() instanceof ItemSatellite && SatelliteRegistry.getSatelliteProperties(stack) != null) {
            SatelliteProperties properties = SatelliteRegistry.getSatelliteProperties(stack);

            int dataStorage, powerGeneration, powerStorage;
            float weight;

            list.add(getName(stack));
            list.add("ID: " + properties.getId());

            if (SatelliteProperties.Property.BATTERY.isOfType(properties.getPropertyFlag())) {
                if ((powerStorage = properties.getPowerStorage()) > 0)
                    list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwr") + powerStorage);
                else
                    list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nopwr"));
            }

            if (SatelliteProperties.Property.POWER_GEN.isOfType(properties.getPropertyFlag())) {
                if ((powerGeneration = properties.getPowerGeneration()) > 0)
                    list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwrgen") + powerGeneration);
                else
                    list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nopwrgen"));
            }

            if (SatelliteProperties.Property.DATA.isOfType(properties.getPropertyFlag())) {
                if ((dataStorage = properties.getMaxDataStorage()) > 0)
                    list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.data") + ZUtils.formatNumber(dataStorage));
                else
                    list.add(ChatFormatting.YELLOW + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nodata"));
            }

            if ((weight = properties.getWeight()) > 0)
                list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.weight") + weight);
            else
                list.add(ChatFormatting.YELLOW + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.noweight"));

        } else {
            list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.empty"));
        }

    }
}
