package zmaster587.advancedRocketry.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;


public class BlockLinkedHorizontalTexture extends Block {

    public static final PropertyEnum<IconNames> TYPE = PropertyEnum.create("type", IconNames.class);

    //Mapping of side to names
    //Order is such that the side with a block can be represented as as bitmask where a side with a block is represented by a 0

    public BlockLinkedHorizontalTexture(Material material) {
        super(material);
        this.setDefaultState(this.getDefaultState().withProperty(TYPE, IconNames.ALLEDGE));
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(TYPE, IconNames.values()[meta]);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(TYPE).ordinal();
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TYPE);
    }

    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world,
                                      BlockPos pos) {

        int offset = 0;

        if (world.getBlockState(pos.add(1, 0, 0)).getBlock() == this)
            offset |= 0x1;
        if (world.getBlockState(pos.add(0, 0, -1)).getBlock() == this)
            offset |= 0x2;
        if (world.getBlockState(pos.add(-1, 0, 0)).getBlock() == this)
            offset |= 0x4;
        if (world.getBlockState(pos.add(0, 0, 1)).getBlock() == this)
            offset |= 0x8;

        return state.withProperty(TYPE, IconNames.values()[offset]);
    }

    enum IconNames implements IStringSerializable {
        ALLEDGE("all"),
        NOTRIGHTEDGE("nredge"),
        NOTTOPEDGE("ntedge"),
        TRCORNOR("trcorner"),
        NOTLEFTEDGE("nledge"),
        XCROSS("xcross"),
        TLCORNER("tlcorner"),
        BOTTOMEDGE("bottomedge"),
        NOTBOTTOMEDGE("nbedge"),
        BRCORNER("brcorner"),
        YCROSS("ycross"),
        LEFTEDGE("leftedge"),
        BLCORNER("blcorner"),
        TOPEDGE("topedge"),
        RIGHTEDGE("rightedge"),
        NOEDGE("noedge");

        private String suffix;

        IconNames(String suffix) {
            this.suffix = suffix;
        }

        @Override
        public String getName() {
            return suffix;
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.launchpad"));

        final boolean shift = GuiScreen.isShiftKeyDown();
        final boolean alt   = isAltDown();

        if (alt) {
            // Advanced details
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.launchpad.alt.1"));
            tooltip.add(TextFormatting.DARK_GRAY + I18n.format("tooltip.advancedrocketry.launchpad.alt.2"));
        } else if (shift) {
            // More info
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.launchpad.shift.1"));
            tooltip.add(TextFormatting.GRAY + I18n.format("tooltip.advancedrocketry.launchpad.shift.2"));
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
