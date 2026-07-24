package com.github.stannismod.affs.te;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.network.PacketFieldTouchEffect;
import com.github.stannismod.affs.util.CodeUtils;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import com.github.stannismod.affs.world.contour.ContourFrameGeometry;
import com.github.stannismod.affs.world.projectile.IEnergyProjectile;
import com.github.stannismod.affs.world.shield.IShieldSink;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import com.github.stannismod.affs.world.shield.ShieldNetworkRegistry;
import com.github.stannismod.affs.world.shield.ShieldNetworkState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;

import javax.annotation.Nullable;

public class TileEntityContourInjector extends TileEntity implements ITickable, IShieldSink {

    public static final int MAX_SCAN_RADIUS = 16;
    public static final int MAX_SHIELD_BUFFER = 200_000;
    private static final DamageSource SHIELD_COLLISION_DAMAGE = new DamageSource("affs.contour_collision");

    private String contourCode = "";
    private int shieldBuffer = 0;
    private int requestedShieldEnergy = 0;
    private int shieldReceivedThisTick = 0;
    private int shieldConsumedThisTick = 0;
    private int frameCount = 0;
    private int interiorCount = 0;
    private boolean fieldActive = false;
    private int status = 0;
    @Nullable
    private ContourFrameGeometry currentGeometry;

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }

        int oldFrameCount = frameCount;
        int oldInteriorCount = interiorCount;
        boolean oldFieldActive = fieldActive;
        int oldStatus = status;
        int oldShieldBuffer = shieldBuffer;

        shieldReceivedThisTick = 0;
        shieldConsumedThisTick = 0;

        ContourFrameGeometry geometry = ContourFrameGeometry.find(
                world,
                pos,
                AdvancedForceFieldSystem.BLOCK_CONTOUR_FRAME,
                MAX_SCAN_RADIUS
        );
        currentGeometry = geometry;

        if (geometry == null) {
            requestedShieldEnergy = 0;
            frameCount = 0;
            interiorCount = 0;
            status = 1;
            fieldActive = false;
            if (oldFrameCount != frameCount || oldInteriorCount != interiorCount || oldFieldActive != fieldActive || oldStatus != status) {
                syncToClient();
            }
            markDirty();
            return;
        }

        frameCount = geometry.getFrameCount();
        interiorCount = geometry.getInteriorCount();
        requestedShieldEnergy = getFreeShieldCapacity();

        refreshFieldActiveState(true);

        if (fieldActive) {
            int maintenanceCost = getMaintenanceCost();
            int drained = consumeShieldEnergy(Math.min(maintenanceCost, shieldBuffer));
            if (drained > 0) {
                shieldConsumedThisTick = drained;
            }
        }

        refreshFieldActiveState(true);

        if (fieldActive) {
            status = 3;
            containUnauthorizedEntities();
        } else if (shieldBuffer > 0) {
            status = 2;
        } else {
            status = 2;
        }

        if (oldFrameCount != frameCount
                || oldInteriorCount != interiorCount
                || oldFieldActive != fieldActive
                || oldStatus != status
                || oldShieldBuffer != shieldBuffer) {
            syncToClient();
        }
        markDirty();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.register(this);
            ShieldNetworkManager.markDirty(world);
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.onChunkUnload();
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public net.minecraft.world.World getNodeWorld() {
        return world;
    }

    @Override
    public int getRequestedShieldEnergy() {
        return currentGeometry == null ? 0 : getFreeShieldCapacity();
    }

    @Override
    public int getFreeShieldCapacity() {
        return Math.max(0, MAX_SHIELD_BUFFER - shieldBuffer);
    }

    @Override
    public int receiveShieldEnergy(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }
        int accepted = Math.min(amount, getFreeShieldCapacity());
        if (accepted > 0) {
            shieldBuffer += accepted;
            shieldReceivedThisTick += accepted;
            markDirty();
        }
        return accepted;
    }

    public int consumeShieldEnergy(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }

        int extracted = Math.min(amount, shieldBuffer);
        if (extracted > 0) {
            shieldBuffer -= extracted;
            shieldConsumedThisTick += extracted;
            markDirty();
        }
        return extracted;
    }

    public void applyContourCode(String code) {
        String normalized = CodeUtils.normalize(code);
        if (normalized.equals(contourCode)) {
            return;
        }
        contourCode = normalized;
        markDirty();
    }

    public String getContourCode() {
        return contourCode;
    }

    public boolean isAuthorized(Entity entity) {
        return CodeUtils.entityHasMatchingCode(entity, contourCode);
    }

    public boolean isFieldActive() {
        return fieldActive;
    }

    @Nullable
    public ContourFrameGeometry getCurrentGeometry() {
        return currentGeometry;
    }

    public boolean tryAbsorbExplosionImpact(net.minecraft.world.World world, Explosion explosion, Iterable<BlockPos> affectedBlocks) {
        if (world == null || world.isRemote || explosion == null || affectedBlocks == null || !isFieldActive()) {
            return false;
        }

        int impactEnergy = estimateExplosionImpactEnergy(world, explosion, affectedBlocks);
        return impactEnergy > 0 && consumeShieldEnergy(impactEnergy) >= impactEnergy;
    }

    private void containUnauthorizedEntities() {
        if (world == null || world.isRemote || currentGeometry == null || !isFieldActive()) {
            return;
        }

        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, getInfluenceBox())) {
            if (entity == null || entity.isDead || (!isEnergyProjectile(entity) && isAuthorized(entity))) {
                continue;
            }
            tryAbsorbEntityImpact(entity);
        }
    }

    private boolean shouldRepelEntity(Entity entity) {
        if (entity == null || world == null || world.isRemote || currentGeometry == null || !isFieldActive()) {
            return false;
        }
        if (isEnergyProjectile(entity)) {
            AxisAlignedBB currentBox = entity.getEntityBoundingBox();
            AxisAlignedBB previousBox = currentBox.offset(entity.prevPosX - entity.posX, entity.prevPosY - entity.posY, entity.prevPosZ - entity.posZ);
            AxisAlignedBB sweptBox = currentBox.union(previousBox);
            return currentGeometry.intersects(sweptBox);
        }
        if (isAuthorized(entity)) {
            return false;
        }

        AxisAlignedBB currentBox = entity.getEntityBoundingBox();
        AxisAlignedBB previousBox = currentBox.offset(entity.prevPosX - entity.posX, entity.prevPosY - entity.posY, entity.prevPosZ - entity.posZ);
        AxisAlignedBB sweptBox = currentBox.union(previousBox);
        return currentGeometry.intersects(sweptBox);
    }

    public boolean tryAbsorbEntityImpact(Entity entity) {
        if (entity == null || world == null || world.isRemote || !isFieldActive()) {
            return false;
        }
        boolean energyProjectile = isEnergyProjectile(entity);
        if (!energyProjectile && !shouldRepelEntity(entity)) {
            return false;
        }

        int impactEnergy = estimateEntityImpactEnergy(entity);
        int spentEnergy = consumeShieldEnergy(impactEnergy);
        if (spentEnergy < impactEnergy) {
            return false;
        }

        PacketFieldTouchEffect.send(world, pos, getImpactTouchPoint(entity));

        if (energyProjectile) {
            entity.setDead();
            return true;
        }

        applyCollisionDamage(entity, impactEnergy);
        pushEntityBack(entity);
        return true;
    }

    public String getStatusText() {
        switch (status) {
            case 1:
                return "invalid-frame";
            case 2:
                return "insufficient-energy";
            case 3:
                return "active";
            default:
                return "idle";
        }
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getInteriorCount() {
        return interiorCount;
    }

    public int getEnergyStored() {
        return shieldBuffer;
    }

    public int getMaxEnergyStored() {
        return MAX_SHIELD_BUFFER;
    }

    public int getRequestedEnergyThisTick() {
        return requestedShieldEnergy;
    }

    public int getShieldReceivedThisTick() {
        return shieldReceivedThisTick;
    }

    public int getShieldConsumedThisTick() {
        return shieldConsumedThisTick;
    }

    public boolean ownsFieldBlock(BlockPos target) {
        return currentGeometry != null && currentGeometry.containsInterior(target);
    }

    public boolean intersectsField(AxisAlignedBB box) {
        return box != null && currentGeometry != null && currentGeometry.intersects(box);
    }

    public double getImpactEfficiencyMultiplier() {
        return 1.0D;
    }

    private boolean refreshFieldActiveState(boolean syncSnapshot) {
        if (world == null || world.isRemote) {
            return false;
        }

        int stored = shieldBuffer;
        boolean newActive = fieldActive ? stored > 0 : stored >= getActivationEnergy();
        boolean changed = fieldActive != newActive;
        fieldActive = newActive;
        if (changed) {
            markDirty();
            if (syncSnapshot) {
                syncToClient();
            }
        }
        return changed;
    }

    private int getMaintenanceCost() {
        return Math.max(1, (int) Math.ceil(interiorCount * ModConfig.contourMaintenanceEnergyPerFieldBlock));
    }

    private int getActivationEnergy() {
        return Math.max(1, (int) Math.ceil(MAX_SHIELD_BUFFER * ModConfig.shieldActivationThreshold));
    }

    private int estimateExplosionImpactEnergy(net.minecraft.world.World world, Explosion explosion, Iterable<BlockPos> affectedBlocks) {
        int total = 0;
        for (BlockPos target : affectedBlocks) {
            if (target == null || !ownsFieldBlock(target)) {
                continue;
            }
            net.minecraft.block.state.IBlockState state = world.getBlockState(target);
            if (state == null || state.getBlock().isAir(state, world, target)) {
                continue;
            }
            float resistance = state.getBlock().getExplosionResistance(world, target, explosion.getExplosivePlacedBy(), explosion);
            double baseCost = Math.max(ModConfig.minimumImpactEnergyCost, resistance * ModConfig.explosionImpactEnergyPerResistance);
            int cost = (int) Math.ceil(baseCost / Math.max(1.0D, getImpactEfficiencyMultiplier()));
            total += Math.max(1, cost);
        }
        return total;
    }

    private void pushEntityBack(Entity entity) {
        if (entity == null || entity.world == null || entity.world.isRemote) {
            return;
        }

        AxisAlignedBB fieldBox = currentGeometry.getFieldBox();
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.posX - entity.prevPosX, entity.posY - entity.prevPosY, entity.posZ - entity.prevPosZ);
        Vec3d normal = FieldSurfaceMath.boxOutwardNormal(fieldBox, currentCenter, motion);
        Vec3d reflectedMotion = FieldSurfaceMath.reflect(motion, normal);
        if (FieldSurfaceMath.vectorLength(reflectedMotion) <= 1.0E-8D) {
            reflectedMotion = FieldSurfaceMath.scale(normal, Math.max(0.08D, FieldSurfaceMath.vectorLength(motion)));
        }

        double halfExtent = Math.max(entity.width, entity.height) * 0.5D;
        Vec3d targetCenter;
        if (Math.abs(normal.x) > 0.0D) {
            double targetX = normal.x > 0.0D ? fieldBox.maxX + halfExtent + 0.05D : fieldBox.minX - halfExtent - 0.05D;
            targetCenter = new Vec3d(targetX, currentCenter.y, currentCenter.z);
        } else if (Math.abs(normal.y) > 0.0D) {
            double targetY = normal.y > 0.0D ? fieldBox.maxY + halfExtent + 0.05D : fieldBox.minY - halfExtent - 0.05D;
            targetCenter = new Vec3d(currentCenter.x, targetY, currentCenter.z);
        } else {
            double targetZ = normal.z > 0.0D ? fieldBox.maxZ + halfExtent + 0.05D : fieldBox.minZ - halfExtent - 0.05D;
            targetCenter = new Vec3d(currentCenter.x, currentCenter.y, targetZ);
        }

        setEntityCenter(entity, targetCenter);
        entity.motionX = reflectedMotion.x;
        entity.motionY = reflectedMotion.y;
        entity.motionZ = reflectedMotion.z;
        entity.prevPosX = entity.posX;
        entity.prevPosY = entity.posY;
        entity.prevPosZ = entity.posZ;
        entity.lastTickPosX = entity.posX;
        entity.lastTickPosY = entity.posY;
        entity.lastTickPosZ = entity.posZ;
        entity.velocityChanged = true;
        entity.fallDistance = 0.0F;
        if (entity instanceof EntityPlayerMP) {
            EntityPlayerMP mp = (EntityPlayerMP) entity;
            mp.connection.setPlayerLocation(entity.posX, entity.posY, entity.posZ, mp.rotationYaw, mp.rotationPitch);
        }
    }

    private Vec3d getEntityCenter(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        return new Vec3d(
                (box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private void applyCollisionDamage(Entity entity, int impactEnergy) {
        if (entity == null || entity.isDead || impactEnergy <= 0) {
            return;
        }

        double excessEnergy = impactEnergy - ModConfig.shieldCollisionMinDamageEnergy;
        if (excessEnergy <= 0.0D) {
            return;
        }

        float damage = (float) (excessEnergy * ModConfig.shieldCollisionDamagePerEnergy);
        if (damage <= 0.0F) {
            return;
        }

        entity.attackEntityFrom(SHIELD_COLLISION_DAMAGE, damage);
    }

    private int estimateEntityImpactEnergy(Entity entity) {
        AxisAlignedBB fieldBox = currentGeometry.getFieldBox();
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.motionX, entity.motionY, entity.motionZ);
        Vec3d normal = FieldSurfaceMath.boxOutwardNormal(fieldBox, currentCenter, motion);
        double baseCost;
        if (isEnergyProjectile(entity)) {
            baseCost = Math.max(ModConfig.shieldCollisionBaseEnergyCost, ModConfig.energyProjectileImpactEnergy * getImpactTypeMultiplier(entity));
        } else {
            double speedSq = FieldSurfaceMath.inwardNormalSpeedSq(motion, normal);
            double multiplier = entity instanceof net.minecraft.entity.projectile.EntityArrow
                    || entity instanceof net.minecraft.entity.projectile.EntityThrowable
                    || entity instanceof net.minecraft.entity.projectile.EntityFireball
                    ? ModConfig.projectileImpactEnergyPerVelocitySq
                    : ModConfig.entityImpactEnergyPerVelocitySq;
            baseCost = Math.max(ModConfig.shieldCollisionBaseEnergyCost, speedSq * multiplier * getImpactTypeMultiplier(entity));
        }
        int cost = (int) Math.ceil(baseCost / Math.max(1.0D, getImpactEfficiencyMultiplier()));
        return Math.max(1, cost);
    }

    private boolean isEnergyProjectile(Entity entity) {
        return entity instanceof IEnergyProjectile;
    }

    private double getImpactTypeMultiplier(Entity entity) {
        double bias = ModConfig.shieldEnergyResistanceBias;
        ShieldNetworkState state = ShieldNetworkManager.getState(world, pos);
        if (state != null) {
            bias = state.getShieldEnergyResistanceBias();
        }
        bias = Math.max(0.0D, Math.min(1.0D, bias));
        if (isEnergyProjectile(entity)) {
            return 1.5D - bias;
        }
        return 0.5D + bias;
    }

    private Vec3d getImpactTouchPoint(Entity entity) {
        AxisAlignedBB fieldBox = currentGeometry.getFieldBox();
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.posX - entity.prevPosX, entity.posY - entity.prevPosY, entity.posZ - entity.prevPosZ);
        Vec3d normal = FieldSurfaceMath.boxOutwardNormal(fieldBox, currentCenter, motion);
        double x = currentCenter.x;
        double y = currentCenter.y;
        double z = currentCenter.z;
        if (Math.abs(normal.x) > 0.0D) {
            x = normal.x > 0.0D ? fieldBox.maxX : fieldBox.minX;
        } else if (Math.abs(normal.y) > 0.0D) {
            y = normal.y > 0.0D ? fieldBox.maxY : fieldBox.minY;
        } else if (Math.abs(normal.z) > 0.0D) {
            z = normal.z > 0.0D ? fieldBox.maxZ : fieldBox.minZ;
        }
        return new Vec3d(x, y, z);
    }

    private AxisAlignedBB getInfluenceBox() {
        return currentGeometry == null ? new AxisAlignedBB(pos).grow(MAX_SCAN_RADIUS) : currentGeometry.getFieldBox().grow(1.0D);
    }

    private void setEntityCenter(Entity entity, Vec3d center) {
        double halfWidth = entity.width * 0.5D;
        double halfHeight = entity.height * 0.5D;
        entity.setPosition(center.x - halfWidth, center.y - halfHeight, center.z - halfWidth);
    }

    private void syncToClient() {
        if (world == null || world.isRemote) {
            return;
        }
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("contourCode", contourCode);
        compound.setInteger("shieldBuffer", shieldBuffer);
        compound.setInteger("requestedShieldEnergy", requestedShieldEnergy);
        compound.setInteger("shieldReceivedThisTick", shieldReceivedThisTick);
        compound.setInteger("shieldConsumedThisTick", shieldConsumedThisTick);
        compound.setInteger("frameCount", frameCount);
        compound.setInteger("interiorCount", interiorCount);
        compound.setBoolean("fieldActive", fieldActive);
        compound.setInteger("status", status);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        contourCode = CodeUtils.normalize(compound.getString("contourCode"));
        shieldBuffer = Math.max(0, Math.min(MAX_SHIELD_BUFFER, compound.getInteger("shieldBuffer")));
        requestedShieldEnergy = Math.max(0, compound.getInteger("requestedShieldEnergy"));
        shieldReceivedThisTick = Math.max(0, compound.getInteger("shieldReceivedThisTick"));
        shieldConsumedThisTick = Math.max(0, compound.getInteger("shieldConsumedThisTick"));
        frameCount = Math.max(0, compound.getInteger("frameCount"));
        interiorCount = Math.max(0, compound.getInteger("interiorCount"));
        fieldActive = compound.getBoolean("fieldActive");
        status = compound.getInteger("status");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

}
