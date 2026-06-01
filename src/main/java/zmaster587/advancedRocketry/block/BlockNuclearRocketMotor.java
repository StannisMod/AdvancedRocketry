package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.tile.TileBrokenPart;

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
    public int getFuelConsumptionRate(World world, int x, int y, int z) {
        return 1;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, 4 * (float) ARConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
}
