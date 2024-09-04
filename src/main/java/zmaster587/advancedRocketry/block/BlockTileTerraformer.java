package zmaster587.advancedRocketry.block;//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.lwjgl.Sys;
import scala.tools.nsc.doc.base.comment.EntityLink;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.tile.satellite.TileTerraformingTerminal;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.block.RotatableBlock;
import zmaster587.libVulpes.util.IAdjBlockUpdate;

import javax.annotation.Nonnull;

public class BlockTileTerraformer extends RotatableBlock {
    protected Class<? extends TileEntity> tileClass;
    protected int guiId;
    public static final PropertyBool STATE = PropertyBool.create("state");

    public BlockTileTerraformer(Class<? extends TileEntity> tileClass, int guiId) {
        super(Material.IRON);
        this.tileClass = tileClass;
        this.guiId = guiId;
        this.setDefaultState(this.blockState.getBaseState().withProperty(STATE, false));
    }

    public BlockTileTerraformer(Class<? extends TileEntity> tileClass, int guiId, Material material) {
        super(material);
        this.tileClass = tileClass;
        this.guiId = guiId;
        this.setDefaultState(this.blockState.getBaseState().withProperty(STATE, false));
    }

    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, new IProperty[]{FACING, STATE});
    }

    public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return super.getActualState(state, worldIn, pos);
    }

    public int getMetaFromState(IBlockState state) {
        return ((EnumFacing)state.getValue(FACING)).getIndex() | ((Boolean)state.getValue(STATE) ? 8 : 0);
    }

    public void setBlockState(World world, IBlockState state, BlockPos pos, boolean newState) {
        world.setBlockState(pos, state.withProperty(STATE, newState), 2);
        world.markBlockRangeForRenderUpdate(pos, pos);
    }

    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    public TileEntity createTileEntity(World world, IBlockState state) {
        try {
            return (TileEntity)this.tileClass.newInstance();
        } catch (Exception var4) {
            Exception e = var4;
            e.printStackTrace();
            return null;
        }
    }

    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            player.openGui(LibVulpes.instance, this.guiId, world, pos.getX(), pos.getY(), pos.getZ());
        }

        return true;
    }

    public void onNeighborChange(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(world, pos, neighbor);
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof IAdjBlockUpdate) {
            ((IAdjBlockUpdate)tile).onAdjacentBlockUpdated();
        }
    }

    public boolean isBlockNormalCube(IBlockState state) {
        return false;
    }

    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase player, @Nonnull ItemStack itemstack) {
        super.onBlockPlacedBy(world, pos, state, player, itemstack);
        if (!world.isRemote) {
            DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).registerProtectingBlock(pos);
            System.out.println("terminal placed");
        }
    }
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote)
            DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).unregisterProtectingBlock(pos);

        TileEntity tile = world.getTileEntity(pos);


        if (!world.isRemote && tile instanceof IInventory) {
            IInventory inventory = (IInventory)tile;

            for(int i1 = 0; i1 < inventory.getSizeInventory(); ++i1) {
                ItemStack itemstack = inventory.getStackInSlot(i1);
                if (!itemstack.isEmpty()) {
                    float f = world.rand.nextFloat() * 0.8F + 0.1F;
                    float f1 = world.rand.nextFloat() * 0.8F + 0.1F;
                    float f2 = world.rand.nextFloat() * 0.8F + 0.1F;

                    while(itemstack.getCount() > 0) {
                        int j1 = world.rand.nextInt(21) + 10;
                        if (j1 > itemstack.getCount()) {
                            j1 = itemstack.getCount();
                        }

                        Item oldItem = itemstack.getItem();
                        ItemStack oldStack = itemstack.copy();
                        itemstack.setCount(itemstack.getCount() - j1);
                        EntityItem entityitem = new EntityItem(world, (double)((float)pos.getX() + f), (double)((float)pos.getY() + f1), (double)((float)pos.getZ() + f2), new ItemStack(oldItem, j1, itemstack.getItemDamage()));
                        float f3 = 0.05F;
                        entityitem.motionX = (double)((float)world.rand.nextGaussian() * f3);
                        entityitem.motionY = (double)((float)world.rand.nextGaussian() * f3 + 0.2F);
                        entityitem.motionZ = (double)((float)world.rand.nextGaussian() * f3);
                        if (oldStack.hasTagCompound()) {
                            NBTTagCompound tag = oldStack.getTagCompound();
                            entityitem.getItem().setTagCompound(tag == null ? null : tag.copy());
                        }

                        // why double calling this??
                        world.spawnEntity(entityitem);
                        world.spawnEntity(entityitem);
                    }
                }
            }
        }

        super.breakBlock(world, pos, state);
    }
}
