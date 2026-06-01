package zmaster587.advancedRocketry.item;

import com.google.common.collect.Sets;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import zmaster587.advancedRocketry.api.MaterialGeode;
import zmaster587.advancedRocketry.client.TooltipInjector;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import java.util.List;
import java.util.Set;

public class ItemJackHammer extends ItemTool {

    private static final Set<Block> items = Sets.newHashSet(Blocks.COBBLESTONE, Blocks.DOUBLE_STONE_SLAB, Blocks.STONE_SLAB, Blocks.STONE, Blocks.SANDSTONE, Blocks.MOSSY_COBBLESTONE, Blocks.IRON_ORE, Blocks.IRON_BLOCK, Blocks.COAL_ORE, Blocks.GOLD_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_ORE, Blocks.DIAMOND_BLOCK, Blocks.ICE, Blocks.NETHERRACK, Blocks.LAPIS_ORE, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_ORE, Blocks.LIT_REDSTONE_ORE, Blocks.RAIL, Blocks.DETECTOR_RAIL, Blocks.GOLDEN_RAIL, Blocks.ACTIVATOR_RAIL);

    public ItemJackHammer(ToolMaterial toolMaterial) {
        super(toolMaterial, items);

        efficiency = 50f;
    }

    @Override
    @ParametersAreNonnullByDefault
    public boolean getIsRepairable(@Nonnull ItemStack stackMe, ItemStack stackItem) {
        return OreDictionary.itemMatches(OreDictionary.getOres("stickTitanium").get(0), stackItem, false);//super.getIsRepairable(p_82789_1_, p_82789_2_);
    }

    @Override
    @ParametersAreNonnullByDefault
    public float getDestroySpeed(@Nonnull ItemStack stack, IBlockState state) {
        return state.getMaterial() == Material.IRON || state.getMaterial() == Material.ROCK || state.getMaterial() == MaterialGeode.geode ? this.efficiency : super.getDestroySpeed(stack, state);

    }

    public boolean canHarvestBlock(IBlockState blockIn) {
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.jackhammer", insertAt);
    }

}
