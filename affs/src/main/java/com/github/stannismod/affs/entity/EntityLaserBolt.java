package com.github.stannismod.affs.entity;

import com.github.stannismod.affs.world.projectile.IEnergyProjectile;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.RayTraceResult;
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
        }
        // The modern shield is scan-based and places no blocks: a bolt aimed at a shielded volume is
        // absorbed by the emitter's entity scan (containUnauthorizedEntities) before it reaches a block,
        // so there is no field block to react to on block impact.

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
