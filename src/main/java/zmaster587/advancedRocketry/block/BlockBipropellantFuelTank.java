package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.IFuelTank;

import java.util.Locale;

public class BlockBipropellantFuelTank extends BlockFuelTank implements IFuelTank {
    public BlockBipropellantFuelTank(Material mat) {
        super(mat);
    }

    @Override
    public int getMaxFill(World world, BlockPos pos, IBlockState state) {
        return 1000;
    }


}
