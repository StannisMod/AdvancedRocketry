package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.item.ItemBlockFluidTank;
import zmaster587.advancedRocketry.tile.TileFluidTank;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.GuiHandler.guiId;
import zmaster587.libVulpes.tile.multiblock.hatch.TileFluidHatch;
import zmaster587.libVulpes.util.FluidUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.ParametersAreNullableByDefault;
import java.util.LinkedList;
import java.util.List;

public class BlockPressurizedFluidTank extends Block {

    private static final AxisAlignedBB bb = new AxisAlignedBB(.0625, 0, 0.0625, 0.9375, 1, 0.9375);

    public BlockPressurizedFluidTank(Material material) {
        super(material);
        hasTileEntity = true;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
                                    EnumFacing side, float hitX, float hitY, float hitZ) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileFluidTank)) return false;

        // Client: consume the click (let server do the actual transfer)
        if (world.isRemote) return true;

        // Try to interact via the tile's FLUID CAPABILITY (column-aware path),
        // NOT the raw internal tank.
        IFluidHandler handler = te.getCapability(
                net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,
                side
        );
        if (handler == null) {
            handler = te.getCapability(
                    net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,
                    null
            );
        }

        boolean acted = false;
        if (handler != null) {
            // Server-side inventory mutation
            acted = net.minecraftforge.fluids.FluidUtil.interactWithFluidHandler(player, hand, handler);
            if (acted) {
                TileFluidTank tank = (TileFluidTank) te;
                // Persist + sync
                tank.markDirty();
                tank.onAdjacentBlockUpdated(EnumFacing.DOWN);
                tank.onAdjacentBlockUpdated(EnumFacing.UP);
            }
        }

        // If we didn't perform a fluid interaction, open the GUI
        if (!acted) {
            player.openGui(zmaster587.libVulpes.LibVulpes.instance,
                    zmaster587.libVulpes.inventory.GuiHandler.guiId.MODULAR.ordinal(),
                    world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }



    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        if (world.isRemote) return;
        TileEntity teAbove = world.getTileEntity(pos.up());
        if (teAbove instanceof TileFluidTank) {
            ((TileFluidTank) teAbove).onAdjacentBlockUpdated(EnumFacing.DOWN);
        }
    }

    
    @Override
    @ParametersAreNullableByDefault
    public TileEntity createTileEntity(World world, IBlockState state) {
        long computed = Math.round(64000d * ARConfiguration.getCurrentConfig().blockTankCapacity);
        int capMb = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, computed));
        return new TileFluidTank(capMb);
    }

    @Override
    @Nonnull
    @ParametersAreNullableByDefault
    public List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        List<ItemStack> drops = new LinkedList<>();
        TileEntity te = world.getTileEntity(pos);

        ItemStack out = new ItemStack(AdvancedRocketryBlocks.blockPressureTank);

        if (te instanceof TileFluidTank) {
            net.minecraftforge.fluids.FluidStack own = ((TileFluidTank) te).getOwnContentsCopy();
            if (own != null && own.amount > 0) {
                ((ItemBlockFluidTank) out.getItem()).fill(out, own);
            }
        }

        drops.add(out);
        return drops;
    }


    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        if (!world.isRemote) {
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileFluidTank) {
                ((TileFluidTank) te).setRemoving(true); 
            }
        }
        // Let vanilla handle removal; we’ll control drops in harvestBlock
        return super.removedByPlayer(state, world, pos, player, willHarvest);
    }    

    @Override
    public void harvestBlock(World world, EntityPlayer player, BlockPos pos, IBlockState state,
                            @Nullable TileEntity te, @Nonnull ItemStack tool) {
        if (world.isRemote) return;

        // Creative: no drop, just remove
        if (player.capabilities.isCreativeMode) {
            world.setBlockToAir(pos);
            return;
        }

        // Build ONE drop item from the authoritative server tile we received
        ItemStack drop = new ItemStack(AdvancedRocketryBlocks.blockPressureTank);
        if (te instanceof TileFluidTank) {
            // Make sure the tile knows it's in teardown; block cross-tile moves
            ((TileFluidTank) te).setRemoving(true);

            net.minecraftforge.fluids.FluidStack own = ((TileFluidTank) te).getOwnContentsCopy();
            if (own != null && own.amount > 0) {
                ((ItemBlockFluidTank) drop.getItem()).fill(drop, own);
            }
        }

        EntityItem ei = new EntityItem(world,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
        world.spawnEntity(ei);

        world.setBlockToAir(pos);
    }




    @Override
    @ParametersAreNonnullByDefault
    public boolean shouldSideBeRendered(IBlockState blockState,
                                        IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {

        if (side.getFrontOffsetY() != 0) {
            if (blockAccess.getBlockState(pos).getBlock() == this)
                return true;
        }

        return super.shouldSideBeRendered(blockState, blockAccess, pos, side);
    }

    @Override
    @Nonnull
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source,
                                        BlockPos pos) {
        return bb;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    private void notifyTankOfNeighborChange(World world, BlockPos pos, BlockPos fromPos) {
        // Only act for strictly adjacent vertical neighbors (no diagonals, no sides)
        int dx = fromPos.getX() - pos.getX();
        int dy = fromPos.getY() - pos.getY();
        int dz = fromPos.getZ() - pos.getZ();

        // Must be exactly one block away on Y, and same X/Z
        if (dx != 0 || dz != 0) return;
        if (dy != 1 && dy != -1) return;

        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof zmaster587.advancedRocketry.tile.TileFluidTank)) return;

        EnumFacing dir = (dy == 1) ? EnumFacing.UP : EnumFacing.DOWN;
        ((zmaster587.advancedRocketry.tile.TileFluidTank) te).onAdjacentBlockUpdated(dir);
    }



    // Reliable for block state changes (place/break)
    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos fromPos) {
        super.neighborChanged(state, world, pos, blockIn, fromPos);
        if (!world.isRemote) notifyTankOfNeighborChange(world, pos, fromPos);
    }

    // TE-only neighbor updates (no block state change)
    @Override
    public void onNeighborChange(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        if (world instanceof World) {
            World w = (World) world;
            if (!w.isRemote) notifyTankOfNeighborChange(w, pos, neighbor);
        }
    }

    @Override
    @Nonnull
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isBlockNormalCube(IBlockState state) {
        return false;
    }
}
