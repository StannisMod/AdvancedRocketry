package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

public class BlockProjectedField extends Block implements IHasItemBlock {

    public BlockProjectedField(final String name, final Material material) {
        super(material);
        this.setUnlocalizedName(name);
        this.setRegistryName(AdvancedForceFieldSystem.MODID, name);
        this.setHardness(-1.0F);
        this.setResistance(Float.MAX_VALUE);
        this.setSoundType(SoundType.GLASS);
        this.disableStats();
        this.setLightOpacity(0);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean doesSideBlockRendering(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing face) {
        return false;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {
        return layer == BlockRenderLayer.TRANSLUCENT;
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    public int quantityDropped(java.util.Random random) {
        return 0;
    }

    @Override
    public boolean removedByPlayer(IBlockState state, World world, BlockPos pos, net.minecraft.entity.player.EntityPlayer player, boolean willHarvest) {
        return false;
    }

    @Override
    public Item createItemBlock() {
        return null;
    }

    @Override
    public boolean canEntityDestroy(IBlockState state, IBlockAccess world, BlockPos pos, Entity entity) {
        return false;
    }

    @Override
    public float getExplosionResistance(World world, BlockPos pos, @Nullable Entity exploder, Explosion explosion) {
        return Float.MAX_VALUE;
    }

    @Override
    public boolean canDropFromExplosion(Explosion explosionIn) {
        return false;
    }

    @Override
    public void onBlockExploded(World world, BlockPos pos, Explosion explosion) {
        // Keep the field in place. It should absorb blast effects instead of disappearing.
    }

    @Override
    public boolean canCollideCheck(IBlockState state, boolean hitIfLiquid) {
        // Ray traces, projectiles, and explosion traces should see the field as a solid barrier.
        return true;
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean isActualState) {
        TileEntityFieldGenerator generator = findOwner(worldIn, pos);
        if (generator == null) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, FULL_BLOCK_AABB);
            return;
        }
        if (entityIn != null && generator.isAuthorized(entityIn)) {
            return;
        }
        addCollisionBoxToList(pos, entityBox, collidingBoxes, FULL_BLOCK_AABB);
    }

    public void onEntityCollision(IBlockState state, World worldIn, BlockPos pos, Entity entityIn) {
        if (worldIn.isRemote) {
            return;
        }
        TileEntityFieldGenerator generator = findOwner(worldIn, pos);
        if (generator == null || generator.isAuthorized(entityIn)) {
            return;
        }
        generator.onFieldTouched(new Vec3d(entityIn.posX, entityIn.posY, entityIn.posZ), entityIn);
    }

    @Nullable
    private TileEntityFieldGenerator findOwner(World world, BlockPos pos) {
        final int searchRadius = TileEntityFieldGenerator.MAX_RADIUS + 1;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos maybeGenerator = pos.add(dx, dy, dz);
                    if (!world.isBlockLoaded(maybeGenerator)) {
                        continue;
                    }
                    if (!(world.getTileEntity(maybeGenerator) instanceof TileEntityFieldGenerator)) {
                        continue;
                    }
                    TileEntityFieldGenerator generator = (TileEntityFieldGenerator) world.getTileEntity(maybeGenerator);
                    if (generator != null && generator.ownsFieldBlock(pos)) {
                        return generator;
                    }
                }
            }
        }
        return null;
    }
}
