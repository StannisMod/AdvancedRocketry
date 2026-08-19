package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.IContactResponder;

/**
 * A thin skin that clings to whichever face it was put on — what "armour is a COATING" has to mean in
 * a world made of cubes.
 *
 * <h3>Why a facing, and not a slab</h3>
 * <p>A slab is a half block that lives at the top or the bottom of its own, so cladding a hull with
 * slabs armours its deck and its keel and leaves every side bare. A ship has six sides and is mostly
 * sides. So plating carries the direction it was applied in, sits against that face, and can be put on
 * a wall, a ceiling or a floor with the same block and no variants to choose between.</p>
 *
 * <h3>What the facing does NOT change</h3>
 * <p>Nothing about armour. What decides whether a body meets structure is whether the VOXEL holds a
 * block, never what shape it holds — {@code StructureDamageEngine.isStructure} reads air and liquid and
 * nothing else. So a coating on a wall answers a contact exactly as one on a floor does, and layering
 * is layering because the layers are neighbouring voxels along the body's path, never because two of
 * them share one. The facing is where it LOOKS and where you can walk; the armour is the voxel.</p>
 */
public abstract class BlockPlating extends Block implements IContactResponder {

    /** The direction the plating is applied IN: it lies against the face on that side of its voxel. */
    public static final PropertyDirection FACING = PropertyDirection.create("facing");

    /** How thick a coating is, as a fraction of the block it clings to. */
    private static final double THICKNESS = 0.125D;

    private static final AxisAlignedBB DOWN = new AxisAlignedBB(0, 0, 0, 1, THICKNESS, 1);
    private static final AxisAlignedBB UP = new AxisAlignedBB(0, 1 - THICKNESS, 0, 1, 1, 1);
    private static final AxisAlignedBB NORTH = new AxisAlignedBB(0, 0, 0, 1, 1, THICKNESS);
    private static final AxisAlignedBB SOUTH = new AxisAlignedBB(0, 0, 1 - THICKNESS, 1, 1, 1);
    private static final AxisAlignedBB WEST = new AxisAlignedBB(0, 0, 0, THICKNESS, 1, 1);
    private static final AxisAlignedBB EAST = new AxisAlignedBB(1 - THICKNESS, 0, 0, 1, 1, 1);

    protected BlockPlating(Material material) {
        super(material);
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.DOWN));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getFront(meta & 7));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getIndex();
    }

    /**
     * Applied to the surface that was clicked. The face handed in is the side of the NEIGHBOUR that
     * was hit, so the coating lies against the opposite side of its own voxel — which is the side
     * touching what it is protecting.
     */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX,
                                            float hitY, float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, facing.getOpposite());
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        switch (state.getValue(FACING)) {
            case UP:
                return UP;
            case NORTH:
                return NORTH;
            case SOUTH:
                return SOUTH;
            case WEST:
                return WEST;
            case EAST:
                return EAST;
            case DOWN:
            default:
                return DOWN;
        }
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }
}
