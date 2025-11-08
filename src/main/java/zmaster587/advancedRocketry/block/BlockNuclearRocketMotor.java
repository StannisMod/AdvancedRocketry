package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IRocketNuclearCore;
import zmaster587.advancedRocketry.tile.TileBrokenPart;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BlockNuclearRocketMotor extends BlockRocketMotor {

    public BlockNuclearRocketMotor(Material mat) {
        super(mat);
    }

    @Override
    public int getThrust(World world, BlockPos pos) {
        return 35;
    }
    @Override
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        // Prefer nuclear core adjacency over tanks
        if (world.getBlockState(pos.up()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.DOWN);
        if (world.getBlockState(pos.down()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.UP);
        if (world.getBlockState(pos.east()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.EAST);
        if (world.getBlockState(pos.west()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.WEST);
        if (world.getBlockState(pos.south()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.SOUTH);
        if (world.getBlockState(pos.north()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.NORTH);
        return state;
    }
    @Override
    public int getFuelConsumptionRate(World world, int x, int y, int z) {
        return 1;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, 4 * (float) ARConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearmotor"));

        final boolean shift = GuiScreen.isShiftKeyDown();
        final boolean alt   = isAltDown();

        if (alt) {
            // Advanced details
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.nuclearmotor.alt.1"));
        } else if (shift) {
            // More info
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearmotor.shift.1"));
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.nuclearmotor.shift.2"));
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

