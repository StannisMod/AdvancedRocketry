package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.IFuelTank;
import zmaster587.advancedRocketry.api.IRocketEngine;
import zmaster587.libVulpes.block.BlockFullyRotatable;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class BlockFuelTank extends BlockFullyRotatable implements IFuelTank {

    public final static PropertyEnum<TankStates> TANKSTATES = PropertyEnum.create("tankstates", TankStates.class);

    public BlockFuelTank(Material mat) {
        super(mat);
        this.setDefaultState(this.getDefaultState().withProperty(TANKSTATES, TankStates.MIDDLE).withProperty(FACING, EnumFacing.DOWN));
    }

    @Override
    @Nonnull
    public IBlockState getStateFromMeta(int meta) {
        int tankstate = meta%3;
        int rotationstate = meta/3;

        IBlockState state;
        state = this.getDefaultState().withProperty(TANKSTATES, TankStates.values()[tankstate]);
        if (rotationstate == 0) {state = state.withProperty(FACING, EnumFacing.UP);}
        if (rotationstate == 1) {state = state.withProperty(FACING, EnumFacing.SOUTH);}
        if (rotationstate == 2) {state = state.withProperty(FACING, EnumFacing.EAST);}
        //System.out.println("####");
        //System.out.println("rotation:"+rotationstate);
        //System.out.println("meta"+meta);
        //System.out.println("----");
        return state;
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int i = 0;
        if (state.getValue(FACING) == EnumFacing.UP) {i=0;}
        if (state.getValue(FACING) == EnumFacing.SOUTH) {i=1;}
        if (state.getValue(FACING) == EnumFacing.EAST) {i=2;}

        int v = state.getValue(TANKSTATES).ordinal() + i*3;
                //System.out.println("v:"+v);

        return v;
    }

    @Override
    @Nonnull
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TANKSTATES, FACING);
    }

    @Override
    @Nonnull
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world,
                                      BlockPos pos) {





        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.DOWN)
            state = state.withProperty(FACING, EnumFacing.UP);
        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.NORTH)
            state = state.withProperty(FACING, EnumFacing.SOUTH);
        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.WEST)
            state = state.withProperty(FACING, EnumFacing.EAST);

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.DOWN || world.getBlockState(pos).getValue(FACING) == EnumFacing.UP) {
            int i = (world.getBlockState(pos.add(0, 1, 0)).getBlock() == this && world.getBlockState(pos.add(0, 1, 0)).getValue(FACING) == EnumFacing.UP) ? 1 : 0;
            i += (world.getBlockState(pos.add(0, -1, 0)).getBlock() == this && world.getBlockState(pos.add(0, -1, 0)).getValue(FACING) == EnumFacing.UP) || world.getBlockState(pos.add(0, -1, 0)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.EAST || world.getBlockState(pos).getValue(FACING) == EnumFacing.WEST) {
            int i = (world.getBlockState(pos.add(1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(1, 0, 0)).getValue(FACING) == EnumFacing.EAST) || world.getBlockState(pos.add(1, 0, 0)).getBlock() instanceof IRocketEngine ? 1 : 0;
            i += (world.getBlockState(pos.add(-1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING) == EnumFacing.EAST) || world.getBlockState(pos.add(-1, 0, 0)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.NORTH || world.getBlockState(pos).getValue(FACING) == EnumFacing.SOUTH) {
            int i = (world.getBlockState(pos.add(0, 0, 1)).getBlock() == this && world.getBlockState(pos.add(0, 0, 1)).getValue(FACING) == EnumFacing.SOUTH) || world.getBlockState(pos.add(0, 0, 1)).getBlock() instanceof IRocketEngine ? 1 : 0;
            i += (world.getBlockState(pos.add(0, 0, -1)).getBlock() == this && world.getBlockState(pos.add(0, 0, -1)).getValue(FACING) == EnumFacing.SOUTH) || world.getBlockState(pos.add(0, 0, -1)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        return state;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, @Nonnull ItemStack stack) {
        if (Math.abs(placer.rotationPitch) > 50.0F) {
            world.setBlockState(pos, state.withProperty(FACING, placer.rotationPitch > 0.0F ? EnumFacing.UP : EnumFacing.DOWN), 2);
        } else {
            world.setBlockState(pos, state.withProperty(FACING, placer.getHorizontalFacing().getOpposite()), 2);
        }
        state = world.getBlockState(pos);

        if (state.getValue(FACING) == EnumFacing.DOWN)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.UP));
        if (state.getValue(FACING) == EnumFacing.NORTH)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.SOUTH));
        if (state.getValue(FACING) == EnumFacing.WEST)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.EAST));


        List<EnumFacing> nextblocks = new LinkedList<>();
        if (world.getBlockState(pos.add(0, 1, 0)).getBlock() == this && world.getBlockState(pos.add(0, 1, 0)).getValue(FACING) == EnumFacing.UP)
            nextblocks.add (world.getBlockState(pos.add(0, 1, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(0, -1, 0)).getBlock() == this  && world.getBlockState(pos.add(0, -1, 0)).getValue(FACING) == EnumFacing.UP)
            nextblocks.add (world.getBlockState(pos.add(0, -1, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(-1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING) == EnumFacing.EAST)
            nextblocks.add (world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(1, 0, 0)).getValue(FACING) == EnumFacing.EAST)
            nextblocks.add (world.getBlockState(pos.add(1, 0, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(0, 0, 1)).getBlock() == this && world.getBlockState(pos.add(0, 0, 1)).getValue(FACING) == EnumFacing.SOUTH)
            nextblocks.add (world.getBlockState(pos.add(0, 0, 1)).getValue(FACING));
        if (world.getBlockState(pos.add(0, 0, -1)).getBlock() == this && world.getBlockState(pos.add(0, 0, -1)).getValue(FACING) == EnumFacing.SOUTH)
            nextblocks.add (world.getBlockState(pos.add(0, 0, -1)).getValue(FACING));


        if (!nextblocks.isEmpty()){
            boolean rotate = true;
            EnumFacing targetfacing =null;
            targetfacing = nextblocks.get(0);

            for (int i = 0; i < nextblocks.size(); i++) {
                if (nextblocks.get(i) != targetfacing)
                    rotate = false;
            }
            if (rotate)
                world.setBlockState(pos,state.withProperty(FACING, targetfacing));
        }

    }


    @Override
    public int getMaxFill(World world, BlockPos pos, IBlockState state) {
        return 1000;
    }

    public enum TankStates implements IStringSerializable {
        TOP,
        BOTTOM,
        MIDDLE;

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

    }
}
