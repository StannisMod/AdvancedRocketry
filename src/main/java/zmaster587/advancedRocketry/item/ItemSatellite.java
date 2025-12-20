package zmaster587.advancedRocketry.item;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.satellite.SatelliteData;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.EmbeddedInventory;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

public class ItemSatellite extends ItemIdWithName {

    private static final int CORE_SLOT = 0;
    private static final int FIRST_MOD_SLOT = 1;
    private static final int LAST_MOD_SLOT  = 6;

    /**
     * Math from SatelliteData:
     *   collectionTime = (int) (200 / Math.sqrt(0.1 * powerConsumption));
     * and fallback:
     *   if (collectionTime == 0) collectionTime = 200;
     */
    private static int calcCollectionTimeTicks(int powerGeneration) {
        if (powerGeneration <= 0) return 0;
        int ct = (int) (200.0 / Math.sqrt(0.1 * (double) powerGeneration));
        return (ct == 0) ? 200 : ct;
    }

    /** SatelliteData produces 1 data per collectionTime ticks; 20 ticks/sec. */
    private static double calcDataPerSecond(int powerGeneration) {
        int ct = calcCollectionTimeTicks(powerGeneration);
        if (ct <= 0) return 0.0;
        return 20.0 / (double) ct;
    }

    private static String makeDataGenLine(double dataPerSec) {
        // Stable decimal separator regardless of OS locale
        String val = String.format(Locale.ROOT, "%.3f", dataPerSec);

        // Preferred: vanilla I18n formatting (client-side tooltip)
        String localized = net.minecraft.client.resources.I18n.format("msg.itemsatellite.datagen", val);

        // If lang key is missing, I18n returns the key itself; degrade gracefully
        if ("msg.itemsatellite.datagen".equals(localized)) {
            return "Data gen: " + val + "/s";
        }
        return localized;
    }

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

            String display = getName(stack); // fallback (may be key)
            SatelliteBase base = SatelliteRegistry.getNewSatellite(props.getSatelliteType());
            if (base != null) display = base.getName();

            // translate if it’s a key; if not, returns input unchanged
            display = net.minecraft.client.resources.I18n.format(display);

            list.add(display);
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
            // Data gen line only meaningful when the satellite has BOTH power generation and data storage.
            int pg = props.getPowerGeneration();
            int maxData = props.getMaxDataStorage();

            if (base instanceof SatelliteData && pg > 0 && maxData > 0) {
                list.add(makeDataGenLine(calcDataPerSecond(pg)));
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
        boolean showDataGenPreview = false;

        // Core first: flags + preview type name (no weight from core)
        ItemStack core = inv.getStackInSlot(CORE_SLOT);

        String satType = "";
        SatelliteBase satBase = null;

        if (!core.isEmpty()) {
            SatelliteProperties cp = SatelliteRegistry.getSatelliteProperty(core);
            if (cp != null) {
                flags |= cp.getPropertyFlag();
                satType = cp.getSatelliteType() == null ? "" : cp.getSatelliteType();
                satBase = SatelliteRegistry.getNewSatellite(satType);

                if (satBase != null) {
                    // Show same display name users will see after assembly
                    list.add(satBase.getName());
                }
            }
        }

        // Preview: show for "type empty" OR data collectors
        showDataGenPreview = satType.isEmpty() || (satBase instanceof SatelliteData);


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
        // Preview data gen line (same semantics + same formula as runtime)
        if (showDataGenPreview && powerGen > 0 && dataMax > 0) {
            list.add(makeDataGenLine(calcDataPerSecond(powerGen)));
        }       
        if (weight > 0f) {
            list.add(LibVulpes.proxy.getLocalizedString("msg.itemsatellite.weight") + weight);
        }

        // Footer LAST
        list.add(ChatFormatting.RED + LibVulpes.proxy.getLocalizedString("msg.itemsatellite.unassembled"));
    }

}
