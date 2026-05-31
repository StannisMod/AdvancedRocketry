package zmaster587.advancedRocketry.block.multiblock;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.tile.hatch.TileDataBusBig;

public class BlockDataBusBig extends BlockARHatch {

    public BlockDataBusBig(Material material) {
        super(material);
    }

    @Override
    public void getSubBlocks(CreativeTabs tab, NonNullList<ItemStack> list) {
        list.add(new ItemStack(this, 1, 0));
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileDataBusBig(2);
    }

    /**
     * Builds the dropped ItemStack.
     * IMPORTANT:
     * - Only attach NBT if we actually have stored data.
     *   This allows empty blocks to stack normally.
     */
    private ItemStack makeDataStack(IBlockAccess world, BlockPos pos) {
        ItemStack stack = new ItemStack(this, 1, 0);

        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileDataBusBig) {
            TileDataBusBig bus = (TileDataBusBig) te;

            DataStorage storage = bus.getDataObject();
            if (storage != null && storage.getData() > 0) {
                NBTTagCompound tag = new NBTTagCompound();
                storage.writeToNBT(tag);
                stack.setTagCompound(tag);
            }
        }

        return stack;
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos,
                         IBlockState state, int fortune) {
        drops.add(makeDataStack(world, pos));
    }

    @Override
    public ItemStack getItem(World worldIn, BlockPos pos, IBlockState state) {
        return makeDataStack(worldIn, pos);
    }

    /**
     * Robust TE harvest pattern for 1.12:
     * - If willHarvest, delay removal so harvestBlock can run with TE intact.
     */
    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos,
                                   EntityPlayer player, boolean willHarvest) {
        if (willHarvest) {
            return true; // harvestBlock will handle drops, then we remove block there
        }
        return super.removedByPlayer(state, world, pos, player, false);
    }

    /**
     * Force our custom drop and then remove the block.
     * This sidesteps parent hatch drop logic.
     */
    @Override
    public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos,
                             IBlockState state, TileEntity te, ItemStack tool) {

        player.addStat(StatList.getBlockStats(this));
        player.addExhaustion(0.005F);

        if (!worldIn.isRemote) {
            spawnAsEntity(worldIn, pos, makeDataStack(worldIn, pos));
        }

        // Now actually remove the block
        worldIn.setBlockToAir(pos);
    }
}
