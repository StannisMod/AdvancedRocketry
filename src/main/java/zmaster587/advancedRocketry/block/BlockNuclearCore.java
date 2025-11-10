package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IRocketNuclearCore;
import zmaster587.advancedRocketry.client.TooltipInjector;

import javax.annotation.Nullable;
import java.util.List;

public class BlockNuclearCore extends Block implements IRocketNuclearCore {

    public BlockNuclearCore(Material mat) {
        super(mat);
    }

    @Override
    public int getMaxThrust(World world, BlockPos pos) {
        return (int) (1000 * ARConfiguration.getCurrentConfig().nuclearCoreThrustRatio);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.nuclearcore", insertAt);
    }
}
