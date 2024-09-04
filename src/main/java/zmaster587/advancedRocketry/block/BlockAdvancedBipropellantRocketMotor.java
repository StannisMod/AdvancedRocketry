package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.tile.TileBrokenPart;

import javax.annotation.Nullable;

public class BlockAdvancedBipropellantRocketMotor extends BlockBipropellantRocketMotor {

    public BlockAdvancedBipropellantRocketMotor(Material mat) {
        super(mat);
    }

    @Override
    public int getThrust(World world, BlockPos pos) {
        return 50;
    }

    @Override
    public int getFuelConsumptionRate(World world, int x, int y, int z) {
        return 3;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, (float) ARConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
}
