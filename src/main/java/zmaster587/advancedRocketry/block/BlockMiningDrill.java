package zmaster587.advancedRocketry.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.IMiningDrill;
import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.libVulpes.block.BlockFullyRotatable;

public class BlockMiningDrill extends BlockFullyRotatable implements IMiningDrill {

    public BlockMiningDrill() {
        super(Material.IRON);
        //super(TileDrill.class, zmaster587.libVulpes.inventory.GuiHandler.guiId.MODULAR.ordinal());
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return false;
    }

    @Override
    public float getMiningSpeed(World world, BlockPos pos) {
        return world.isAirBlock(pos.add(0, 1, 0)) && world.isAirBlock(pos.add(0, 2, 0)) ? 0.02f : 0.01f;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.drill", insertAt);
    }

    @Override
    public int powerConsumption() {
        return 0;
    }

}
