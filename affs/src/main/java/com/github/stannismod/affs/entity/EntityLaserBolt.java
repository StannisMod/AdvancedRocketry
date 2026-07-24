package com.github.stannismod.affs.entity;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.network.PacketFieldTouchEffect;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import com.github.stannismod.affs.world.projectile.IEnergyProjectile;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class EntityLaserBolt extends EntityThrowable implements IEnergyProjectile {

    private static final DamageSource LASER_DAMAGE = new DamageSource("affs.laser").setProjectile().setMagicDamage();

    public EntityLaserBolt(World worldIn) {
        super(worldIn);
        setSize(0.15F, 0.15F);
    }

    public EntityLaserBolt(World worldIn, EntityLivingBase throwerIn) {
        super(worldIn, throwerIn);
        setSize(0.15F, 0.15F);
    }

    @Override
    protected float getGravityVelocity() {
        return 0.0F;
    }

    @Override
    protected void onImpact(RayTraceResult result) {
        if (world.isRemote) {
            return;
        }

        if (result.entityHit != null && result.entityHit.isEntityAlive()) {
            result.entityHit.attackEntityFrom(LASER_DAMAGE, 4.0F);
        } else if (result.getBlockPos() != null) {
            BlockPos hitPos = result.getBlockPos();
            Block hitBlock = world.getBlockState(hitPos).getBlock();
            if (hitBlock == AdvancedForceFieldSystem.BLOCK_PROJECTED_FIELD) {
                for (TileEntityFieldGenerator generator : FieldSurfaceMath.getActiveGenerators(world)) {
                    if (generator != null && generator.protects(hitPos)) {
                        PacketFieldTouchEffect.send(world, generator.getPos(), result.hitVec == null ? new Vec3d(hitPos.getX() + 0.5D, hitPos.getY() + 0.5D, hitPos.getZ() + 0.5D) : result.hitVec);
                        break;
                    }
                }
            }
        }

        world.setEntityState(this, (byte) 3);
        setDead();
    }

    @Override
    public void handleStatusUpdate(byte id) {
        if (id == 3) {
            for (int i = 0; i < 8; i++) {
                world.spawnParticle(EnumParticleTypes.CRIT_MAGIC, posX, posY, posZ, 0.0D, 0.0D, 0.0D);
            }
            return;
        }
        super.handleStatusUpdate(id);
    }
}
