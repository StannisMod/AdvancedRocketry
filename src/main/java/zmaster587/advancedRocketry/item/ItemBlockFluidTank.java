package zmaster587.advancedRocketry.item;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.tile.TileFluidTank;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

public class ItemBlockFluidTank extends ItemBlock {

    public ItemBlockFluidTank(Block block) {
        super(block);
    }

    /** Capacity of the item tank in mB, using the same base as the block (64_000 mB),
     *  preserving fractional multipliers and clamping to int range. */
    private static int getCapMb() {
        // Math.round(double) -> long; keep it in long, then clamp to int range
        long computed = Math.round(64000d * ARConfiguration.getCurrentConfig().blockTankCapacity);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, computed));
    }

    @Override
    @SideOnly(Side.CLIENT)
    @ParametersAreNonnullByDefault
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world, List<String> list, ITooltipFlag flag) {
        super.addInformation(stack, world, list, flag);

        final int capMb = getCapMb();
        final FluidStack fs = getFluid(stack);

        final String fluidName = (fs != null && fs.getFluid() != null) ? fs.getLocalizedName() : "Empty";
        final int amount = (fs != null) ? fs.amount : 0;

        list.add("Fluid: " + fluidName);
        list.add("Level: " + amount + "/" + capMb + " mB");
    }

    @Override
    @ParametersAreNonnullByDefault
    public boolean placeBlockAt(@Nonnull ItemStack stack, EntityPlayer player, World world, BlockPos pos,
                                EnumFacing side, float hitX, float hitY, float hitZ, IBlockState newState) {
        if (!super.placeBlockAt(stack, player, world, pos, side, hitX, hitY, hitZ, newState)) {
            return false;
        }
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileFluidTank) {
            IFluidHandler handler = tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.DOWN);
            if (handler != null) {
                ItemStack one = stack.copy();
                one.setCount(1);
                FluidStack drained = drain(one, Integer.MAX_VALUE);
                if (drained != null && drained.amount > 0) { 
                    handler.fill(drained, true);
                }
            }
        }
        return true;
    }


    public void fill(@Nonnull ItemStack stack, FluidStack fluid) {
        NBTTagCompound nbt;
        FluidTank tank = new FluidTank(getCapMb());
        if (stack.hasTagCompound()) {
            nbt = stack.getTagCompound();
            tank.readFromNBT(nbt);
        } else {
            nbt = new NBTTagCompound();
        }

        if (fluid != null) {tank.fill(fluid, true);
        }

        tank.writeToNBT(nbt);
        stack.setTagCompound(nbt);
    }

    public FluidStack drain(@Nonnull ItemStack stack, int amt) {
        NBTTagCompound nbt;
        FluidTank tank = new FluidTank(getCapMb());
        if (stack.hasTagCompound()) {
            nbt = stack.getTagCompound();
            tank.readFromNBT(nbt);
        } else {
            nbt = new NBTTagCompound();
        }

        FluidStack drained = tank.drain(amt, true);

        tank.writeToNBT(nbt);
        stack.setTagCompound(nbt);

        return drained;
    }

    public FluidStack getFluid(@Nonnull ItemStack stack) {
        NBTTagCompound nbt;
        FluidTank tank = new FluidTank(getCapMb());
        if (stack.hasTagCompound()) {
            nbt = stack.getTagCompound();
            tank.readFromNBT(nbt);
        }
        return tank.getFluid();
    }
}
