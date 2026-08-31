package zmaster587.advancedRocketry.block.weapon;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.weapon.GunSpec;
import zmaster587.advancedRocketry.api.weapon.IGunPart;
import zmaster587.advancedRocketry.weapon.GunAssembly;

import java.util.function.Consumer;

/**
 * A block that is worth something to the gun it is built into.
 *
 * <h3>One class, many parts</h3>
 * <p>What distinguishes a barrel section from a cooling jacket is entirely what it contributes, so
 * the contribution is the constructor argument and there is one class rather than one class per
 * part. An addon that wants a part we did not think of implements {@link IGunPart} on its own block
 * instead; nothing here is privileged.</p>
 */
public class BlockGunPart extends Block implements IGunPart {

    private final Consumer<GunSpec.Builder> contribution;

    public BlockGunPart(Consumer<GunSpec.Builder> contribution) {
        super(Material.IRON);
        this.contribution = contribution;
        setHardness(3.0F);
        setResistance(10.0F);
    }

    @Override
    public void contributeTo(GunSpec.Builder builder, World world, BlockPos pos, IBlockState state) {
        if (contribution != null) {
            contribution.accept(builder);
        }
    }

    /**
     * A part cannot enter the world without this running, whoever placed it — a player, a filler
     * command, another mod's builder. That is why the guns around it are told here rather than from a
     * player-facing place-event: the world is the thing that knows, and it always knows.
     */
    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        super.onBlockAdded(world, pos, state);
        GunAssembly.markControllersDirty(world, pos);
    }

    /** The same, for a part leaving. By now the block is gone; the walk goes out through what is left. */
    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        super.breakBlock(world, pos, state);
        GunAssembly.markControllersDirty(world, pos);
    }
}
