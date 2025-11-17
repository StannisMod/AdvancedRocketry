package zmaster587.advancedRocketry.item.components;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import zmaster587.advancedRocketry.capability.TankCapabilityItemStack;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.IArmorComponent;
import zmaster587.libVulpes.client.ResourceIcon;
import zmaster587.libVulpes.items.ItemIngredient;
import zmaster587.libVulpes.util.FluidUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;

public class ItemPressureTank extends ItemIngredient implements IArmorComponent {

    ResourceIcon icon;

    private int capacity;

    public ItemPressureTank(int number, int capacity) {
        super(number);
        this.capacity = capacity;
        this.maxStackSize = 8;
    }
    
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world,
                            List<String> list, ITooltipFlag flag) {
        super.addInformation(stack, world, list, flag);

        final int capMb = Math.max(0, getCapacity(stack));
        final net.minecraftforge.fluids.FluidStack fs = zmaster587.libVulpes.util.FluidUtils.getFluidForItem(stack);

        final String fluidName = (fs != null && fs.getFluid() != null) ? fs.getLocalizedName() : "Empty";
        final int amount = (fs != null) ? fs.amount : 0;

        // Match main tank style
        list.add(TextFormatting.DARK_PURPLE + "Space Suit Component");
        list.add("Fluid: " + fluidName);
        list.add("Level: " + amount + "/" + capMb + " mB");

        // SHIFT block
        if (GuiScreen.isShiftKeyDown()) {
            list.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.pressuretank.shift.1"));
        } else if (I18n.hasKey("tooltip.advancedrocketry.hold_shift")) {
            list.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC
                    + I18n.format("tooltip.advancedrocketry.hold_shift"));
        }

        // ALT block (independent of SHIFT)
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            list.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.pressuretank.alt.1"));
            list.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.pressuretank.alt.2"));
        } else if (I18n.hasKey("tooltip.advancedrocketry.hold_alt")) {
            list.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC
                    + I18n.format("tooltip.advancedrocketry.hold_alt"));
        }
    }

    @Override
    public void onTick(World world, EntityPlayer player, @Nonnull ItemStack armorStack, IInventory inv,
                       @Nonnull ItemStack componentStack) {
    }

    @Override
    public boolean onComponentAdded(World world, @Nonnull ItemStack armorStack) {
        return true;
    }

    @Override
    public void onComponentRemoved(World world, @Nonnull ItemStack armorStack) {

    }

    @Override
    public void onArmorDamaged(EntityLivingBase entity, @Nonnull ItemStack armorStack,
                               @Nonnull ItemStack componentStack, DamageSource source, int damage) {

    }

    public int getCapacity(@Nonnull ItemStack container) {
        return capacity * (int) Math.pow(2, container.getItemDamage());
    }

    @Override
    public ResourceIcon getComponentIcon(@Nonnull ItemStack armorStack) {
        return null;
    }

    @Override
    public boolean isAllowedInSlot(@Nonnull ItemStack stack, EntityEquipmentSlot slot) {
        return slot == EntityEquipmentSlot.CHEST;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderScreen(@Nonnull ItemStack componentStack, List<ItemStack> modules, RenderGameOverlayEvent event, Gui gui) {
        // TODO Auto-generated method stub
    }

   
    @Override
    public ICapabilityProvider initCapabilities(@Nonnull ItemStack stack, NBTTagCompound nbt) {
        return new TankCapabilityItemStack(stack, getCapacity(stack));
    }
}
