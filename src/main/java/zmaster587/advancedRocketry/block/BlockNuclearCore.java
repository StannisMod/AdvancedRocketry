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
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearcore"));

        final boolean shift = GuiScreen.isShiftKeyDown();
        final boolean alt   = isAltDown();

        if (alt) {
            // Advanced details
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearcore.alt.1"));
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearcore.alt.2"));
        } else if (shift) {
            // More info
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearcore.shift.1"));
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearcore.shift.2"));
            if (I18n.hasKey("tooltip.advancedrocketry.hold_alt"))
                tooltip.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC + I18n.format("tooltip.advancedrocketry.hold_alt"));
        } else {
            // Hints
            if (I18n.hasKey("tooltip.advancedrocketry.hold_shift"))
                tooltip.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC + I18n.format("tooltip.advancedrocketry.hold_shift"));
            if (I18n.hasKey("tooltip.advancedrocketry.hold_alt"))
                tooltip.add(TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC + I18n.format("tooltip.advancedrocketry.hold_alt"));
        }
    }

    @SideOnly(Side.CLIENT)
    private static boolean isAltDown() {
        try {
            // Works on Forge 1.12.x; LWJGL fallback for safety
            return GuiScreen.isAltKeyDown()
                || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LMENU)
                || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RMENU);
        } catch (Throwable t) {
            return false;
        }
    }
}
