package zmaster587.advancedRocketry.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.tile.station.TileLandingPad;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.GuiHandler;

public class BlockLandingPad extends Block {

    public BlockLandingPad(Material mat) {
        super(mat);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileLandingPad();
    }


    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        super.onBlockAdded(worldIn, pos, state);
        TileEntity tile = worldIn.getTileEntity(pos);
        if (tile instanceof TileLandingPad) {
            ((TileLandingPad) tile).registerTileWithStation(worldIn, pos);
        }
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos,
                                    IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing side, float hitX, float hitY,
                                    float hitZ) {
        if (!world.isRemote)
            player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULAR.ordinal(), world, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.landingpad"));

        final boolean shift = GuiScreen.isShiftKeyDown();
        final boolean alt   = isAltDown();

        if (alt) {
            // Advanced details
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.landingpad.alt.1"));
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.landingpad.alt.2"));
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.landingpad.alt.3"));
        } else if (shift) {
            // More info
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.landingpad.shift.1"));
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.landingpad.shift.2"));
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

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileLandingPad) {
            ((TileLandingPad) tile).unregisterTileWithStation(world, pos);
        }
        super.breakBlock(world, pos, state);
    }
}
