package zmaster587.advancedRocketry.item;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.item.ItemSatellite.SatelliteModuleInventory;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.EmbeddedInventory;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;
import java.util.List;

public class ItemSatellite extends ItemIdWithName {

    private static final int CORE_SLOT = 0;
    private static final int FIRST_MOD_SLOT = 1;
    private static final int LAST_MOD_SLOT  = 6;

    //Guarding inventory to ensure only valid items are placed in slots.
    public static class SatelliteModuleInventory extends EmbeddedInventory {
        public SatelliteModuleInventory() { super(7); } // slots 0-6 embedded from chassis

        @Override
        public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
            if (stack.isEmpty()) return false;

            SatelliteProperties p = SatelliteRegistry.getSatelliteProperty(stack);
            if (p == null) return false;
            int f = p.getPropertyFlag();
            // only allow appropriate items in appropriate slots
            if (slot == CORE_SLOT) {
                return SatelliteProperties.Property.MAIN.isOfType(f);
            }

            if (slot >= FIRST_MOD_SLOT && slot <= LAST_MOD_SLOT) {
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
    public void addInformation(@Nonnull ItemStack stack, World world, List<String> list, ITooltipFlag flag) {
        // Assembled = has properties AND a real ID (>0)
        SatelliteProperties props = SatelliteRegistry.getSatelliteProperties(stack);
        final boolean isAssembled = (props != null && props.getId() > 0);

        if (isAssembled) {
            int dataStorage, powerGeneration, powerStorage;
            float weight;

            list.add(getName(stack));
            list.add("ID: " + props.getId());

            if (SatelliteProperties.Property.BATTERY.isOfType(props.getPropertyFlag())) {
                powerStorage = props.getPowerStorage();
                list.add((powerStorage > 0)
                    ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwr") + powerStorage
                    : ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nopwr"));
            }

            if (SatelliteProperties.Property.POWER_GEN.isOfType(props.getPropertyFlag())) {
                powerGeneration = props.getPowerGeneration();
                list.add((powerGeneration > 0)
                    ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwrgen") + powerGeneration
                    : ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nopwrgen"));
            }

            if (SatelliteProperties.Property.DATA.isOfType(props.getPropertyFlag())) {
                dataStorage = props.getMaxDataStorage();
                list.add((dataStorage > 0)
                    ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.data") + ZUtils.formatNumber(dataStorage)
                    : ChatFormatting.YELLOW + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nodata"));
            }

            weight = props.getWeight();
            list.add((weight > 0f)
                ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.weight") + weight
                : ChatFormatting.YELLOW + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.noweight"));
            return;
        }

        // --- Preview for unassembled chassis ---
        EmbeddedInventory inv = readInvFromNBT(stack);

        boolean hasParts = false;
        for (int i = CORE_SLOT; i <= LAST_MOD_SLOT; i++) {
            if (!inv.getStackInSlot(i).isEmpty()) { hasParts = true; break; }
        }
        if (!hasParts) {
            list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.empty"));
            return;
        }

        int flags = 0;
        int powerGen = 0, powerStor = 0, dataMax = 0;
        float weight = 0f;

        // Core first: flags + preview type name (no weight from core)
        ItemStack core = inv.getStackInSlot(CORE_SLOT);
        if (!core.isEmpty()) {
            SatelliteProperties cp = SatelliteRegistry.getSatelliteProperty(core);
            if (cp != null) {
                flags |= cp.getPropertyFlag();
                String satType = cp.getSatelliteType();
                SatelliteBase satBase = SatelliteRegistry.getNewSatellite(satType);
                if (satBase != null) {
                    // Show same display name users will see after assembly
                    list.add(satBase.getName());
                }
            }
        }

        // Modules: stats + weight
        for (int i = FIRST_MOD_SLOT; i <= LAST_MOD_SLOT; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) continue;

            SatelliteProperties p = SatelliteRegistry.getSatelliteProperty(s);
            if (p != null) {
                flags |= p.getPropertyFlag();
                int f = p.getPropertyFlag();
                if (f == SatelliteProperties.Property.POWER_GEN.getFlag())
                    powerGen += p.getPowerGeneration();
                else if (f == SatelliteProperties.Property.BATTERY.getFlag())
                    powerStor += p.getPowerStorage();
                else if (f == SatelliteProperties.Property.DATA.getFlag())
                    dataMax += p.getMaxDataStorage();
            }
            weight += zmaster587.advancedRocketry.util.WeightEngine.INSTANCE.getWeight(s);
        }

        // Match assembly semantics: base buffer is always present
        powerStor += 720;

        // Always show power storage in preview (even if no battery modules are installed)
        list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwr") + powerStor);
        
        if (SatelliteProperties.Property.POWER_GEN.isOfType(flags)) {
            list.add((powerGen > 0)
                ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.pwrgen") + powerGen
                : ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nopwrgen"));
        }
        if (SatelliteProperties.Property.DATA.isOfType(flags)) {
            list.add((dataMax > 0)
                ? LibVulpes.proxy.getLocalizedString("msg.itemsatellite.data") + ZUtils.formatNumber(dataMax)
                : ChatFormatting.YELLOW + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.nodata"));
        }
        if (weight > 0f) {
            list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.weight") + weight);
        }

        // Footer LAST
        list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.unassembled"));
    }

}
