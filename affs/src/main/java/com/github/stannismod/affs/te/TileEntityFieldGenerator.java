package com.github.stannismod.affs.te;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.block.BlockFieldGenerator;
import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.network.PacketFieldTouchEffect;
import com.github.stannismod.affs.network.PacketSyncActiveGenerators;
import com.github.stannismod.affs.util.CodeUtils;
import com.github.stannismod.affs.world.FieldSource;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import com.github.stannismod.affs.world.projectile.IEnergyProjectile;
import com.github.stannismod.affs.world.shield.IShieldSink;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import com.github.stannismod.affs.world.shield.ShieldNetworkRegistry;
import com.github.stannismod.affs.world.shield.ShieldNetworkState;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityThrowable;
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
import net.minecraft.world.World;
import net.minecraftforge.energy.EnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class TileEntityFieldGenerator extends TileEntity implements ITickable, FieldSource, IShieldSink {

    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 16;
    public static final int DEFAULT_RADIUS = 4;
    // Per-tick shield intake rate of the emitter coil (D134-3 "per-emitter recharge throughput" seed).
    // The coil advertises no more demand than this per tick, so the network never routes flow the coil
    // cannot physically accept — energy that is extracted from a source is always received by the coil.
    public static final int SHIELD_RECEIVE_PER_TICK = 4_000;
    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;
    private static final DamageSource SHIELD_COLLISION_DAMAGE = new DamageSource("affs.shield_collision");
    private static final Set<TileEntityFieldGenerator> ACTIVE_GENERATORS = new HashSet<>();
    private static final Map<UUID, PlayerLastSafePosition> PLAYER_LAST_SAFE_POSITIONS = new HashMap<>();

    // Coil capacity is read from config at construction (config is loaded in preInit, before any tile
    // is built). Small and fast: the field activates at shieldActivationThreshold of this capacity.
    private final ShieldEnergyStorage energy = new ShieldEnergyStorage(ModConfig.emitterCoilBuffer, SHIELD_RECEIVE_PER_TICK, SHIELD_RECEIVE_PER_TICK);
    private int radius = DEFAULT_RADIUS;
    private String accessCode = "";
    private int shieldDrainPhase = 0;
    private int clientSyncCountdown = -1;
    private boolean clientSyncQueued = false;
    private boolean clientSnapshotQueued = false;
    private boolean fieldPowered = false;
    private int shieldReceivedThisTick = 0;
    private int shieldConsumedThisTick = 0;

    @Override
    public void update() {
        if (world == null) {
            return;
        }

        if (world.isRemote) {
            updateClientPrediction();
            return;
        }

        shieldReceivedThisTick = 0;
        shieldConsumedThisTick = 0;

        refreshFieldPowerState(true);
        if (fieldPowered) {
            int requiredEnergy = getShieldDrainThisTick();
            int drained = energy.extractEnergy(requiredEnergy, false);
            if (drained > 0) {
                shieldConsumedThisTick += drained;
                markDirty();
            }
        }

        shieldDrainPhase = (shieldDrainPhase + 1) % 20;
        refreshFieldPowerState(true);

        if (fieldPowered) {
            containUnauthorizedEntities();
        }

        queueClientSync(false);
        tickClientSync();
    }

    @Override
    public int getRadius() {
        return radius;
    }

    public void setRadius(int requestedRadius) {
        int clamped = clampRadius(requestedRadius);
        if (clamped == this.radius) {
            return;
        }

        this.radius = clamped;
        markDirty();

        if (world != null && !world.isRemote) {
            cleanupLegacyProjectedFieldBlocks(this.radius + 2);
            refreshFieldPowerState(true);
            queueClientSync(true);
        }
    }

    public String getAccessCode() {
        return accessCode;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            ACTIVE_GENERATORS.add(this);
            ShieldNetworkRegistry.register(this);
            ShieldNetworkManager.markDirty(world);
            cleanupLegacyProjectedFieldBlocks(radius + 2);
            refreshFieldPowerState(true);
        }
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public net.minecraft.world.World getNodeWorld() {
        return world;
    }

    public boolean isAuthorized(Entity entity) {
        return CodeUtils.entityHasMatchingCode(entity, getAccessCode());
    }

    public boolean isFieldPowered() {
        return fieldPowered;
    }

    public boolean protects(BlockPos target) {
        if (target == null || !isFieldPowered()) {
            return false;
        }
        return distanceSqToCenter(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= getFieldRadiusSq();
    }

    @Override
    public BlockPos getPos() {
        return pos;
    }

    @Override
    public int getRequestedShieldEnergy() {
        // Advertise only what the coil can physically intake this tick (min of free space and the
        // per-tick receive rate). The network solver uses this as the coil's demand-edge capacity, so a
        // large source (e.g. an accumulator) can never have more energy extracted from it than the coil
        // actually receives — keeping the network energy-conserving.
        return Math.min(getFreeShieldCapacity(), SHIELD_RECEIVE_PER_TICK);
    }

    @Override
    public int getFreeShieldCapacity() {
        return Math.max(0, energy.getMaxEnergyStored() - energy.getEnergyStored());
    }

    @Override
    public int receiveShieldEnergy(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }
        int accepted = energy.receiveEnergy(amount, false);
        if (accepted > 0) {
            shieldReceivedThisTick += accepted;
            markDirty();
            refreshFieldPowerState(true);
            queueClientSync(false);
        }
        return accepted;
    }

    public boolean ownsFieldBlock(BlockPos target) {
        return false;
    }

    private double getFieldRadiusSq() {
        double fieldRadius = radius + 0.5D;
        return fieldRadius * fieldRadius;
    }

    private double distanceSqToCenter(double x, double y, double z) {
        double cx = pos.getX() + 0.5D;
        double cy = pos.getY() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    public void clearProjectedField() {
        // Sphere-based field no longer places blocks in the world.
    }

    public void applyAccessCode(String code) {
        String normalized = CodeUtils.normalize(code);
        if (!normalized.equals(accessCode)) {
            accessCode = normalized;
            markDirty();
            queueClientSync(false);
        }
    }

    private void cleanupLegacyProjectedFieldBlocks(int scanRadius) {
        if (world == null || world.isRemote) {
            return;
        }

        Block legacyBlock = AdvancedForceFieldSystem.BLOCK_PROJECTED_FIELD;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    cursor.setPos(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (world.getBlockState(cursor).getBlock() == legacyBlock) {
                        world.setBlockToAir(cursor);
                    }
                }
            }
        }
    }

    private void containUnauthorizedEntities() {
        if (!isFieldPowered()) {
            return;
        }

        List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, getInfluenceBox());
        for (Entity entity : entities) {
            if (entity == null || entity.isDead) {
                continue;
            }
            tryAbsorbEntityImpact(entity);
        }
    }

    public void onFieldTouched(Vec3d touchPoint, Entity entity) {
        if (world == null || world.isRemote) {
            return;
        }
        PacketFieldTouchEffect.send(world, pos, touchPoint);
    }

    public boolean shouldRepelEntity(Entity entity) {
        if (entity == null || world == null || world.isRemote) {
            return false;
        }
        if (!isFieldPowered()) {
            return false;
        }
        AxisAlignedBB currentBox = entity.getEntityBoundingBox();
        AxisAlignedBB previousBox = currentBox.offset(entity.prevPosX - entity.posX, entity.prevPosY - entity.posY, entity.prevPosZ - entity.posZ);
        AxisAlignedBB sweptBox = currentBox.union(previousBox);
        boolean intersectsShell = FieldSurfaceMath.intersectsCompositeShell(world, sweptBox);

        if (isEnergyProjectile(entity)) {
            return intersectsShell;
        }
        if (isAuthorized(entity)) {
            return false;
        }

        double currentCenterX = (currentBox.minX + currentBox.maxX) * 0.5D;
        double currentCenterY = (currentBox.minY + currentBox.maxY) * 0.5D;
        double currentCenterZ = (currentBox.minZ + currentBox.maxZ) * 0.5D;
        double currentDistSq = distanceSqToCenter(currentCenterX, currentCenterY, currentCenterZ);
        double innerRadius = Math.max(0.0D, radius - FieldSurfaceMath.FIELD_HALF_THICKNESS);
        double outerRadius = radius + FieldSurfaceMath.FIELD_HALF_THICKNESS;
        double innerRadiusSq = innerRadius * innerRadius;
        double outerRadiusSq = outerRadius * outerRadius;

        if (entity instanceof EntityPlayer) {
            PlayerLastSafePosition safePosition = PLAYER_LAST_SAFE_POSITIONS.get(entity.getUniqueID());
            if (!intersectsShell) {
                if (currentDistSq >= outerRadiusSq) {
                    rememberPlayerSafePosition(entity, true);
                } else if (currentDistSq <= innerRadiusSq) {
                    rememberPlayerSafePosition(entity, false);
                }
                return false;
            }

            if (safePosition != null && safePosition.dimension == world.provider.getDimension()) {
                return safePosition.outside;
            }
            return true;
        }

        if (!intersectsShell) {
            return false;
        }

        double outwardX = currentCenterX - (pos.getX() + 0.5D);
        double outwardY = currentCenterY - (pos.getY() + 0.5D);
        double outwardZ = currentCenterZ - (pos.getZ() + 0.5D);
        double motionX = currentCenterX - (previousBox.minX + previousBox.maxX) * 0.5D;
        double motionY = currentCenterY - (previousBox.minY + previousBox.maxY) * 0.5D;
        double motionZ = currentCenterZ - (previousBox.minZ + previousBox.maxZ) * 0.5D;
        double radialMotion = motionX * outwardX + motionY * outwardY + motionZ * outwardZ;
        return radialMotion < 0.0D;
    }

    public boolean tryAbsorbEntityImpact(Entity entity) {
        if (entity == null || world == null || world.isRemote || !isFieldPowered()) {
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

        onFieldTouched(getImpactTouchPoint(entity), entity);

        if (energyProjectile) {
            entity.setDead();
            return true;
        }

        applyCollisionDamage(entity, impactEnergy);
        pushEntityBack(entity);
        return true;
    }

    public boolean tryAbsorbExplosionImpact(World world, Explosion explosion, Iterable<BlockPos> affectedBlocks) {
        if (world == null || world.isRemote || explosion == null || affectedBlocks == null || !isFieldPowered()) {
            return false;
        }

        int impactEnergy = estimateExplosionImpactEnergy(world, explosion, affectedBlocks);
        return impactEnergy > 0 && consumeShieldEnergy(impactEnergy) >= impactEnergy;
    }

    public int getShieldDrainThisTick() {
        return getShieldDrainForPhase(shieldDrainPhase);
    }

    private void pushEntityBack(Entity entity) {
        if (entity == null || entity.world == null || entity.world.isRemote) {
            return;
        }

        Vec3d fieldCenter = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.posX - entity.prevPosX, entity.posY - entity.prevPosY, entity.posZ - entity.prevPosZ);
        Vec3d normal = FieldSurfaceMath.sphereOutwardNormal(fieldCenter, currentCenter, motion);
        Vec3d reflectedMotion = FieldSurfaceMath.reflect(motion, normal);
        if (FieldSurfaceMath.vectorLength(reflectedMotion) <= 1.0E-8D) {
            reflectedMotion = FieldSurfaceMath.scale(normal, Math.max(0.08D, FieldSurfaceMath.vectorLength(motion)));
        }

        double entityRadius = Math.max(entity.width, entity.height) * 0.5D;
        Vec3d targetCenter = fieldCenter.add(FieldSurfaceMath.scale(normal, radius + FieldSurfaceMath.FIELD_HALF_THICKNESS + entityRadius + 0.05D));
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
            EntityPlayerMP player = (EntityPlayerMP) entity;
            player.connection.setPlayerLocation(entity.posX, entity.posY, entity.posZ, player.rotationYaw, player.rotationPitch);
        }

        if (entity instanceof EntityPlayer) {
            rememberPlayerSafePosition(entity, true);
        }

        Vec3d touchPoint = fieldCenter.add(FieldSurfaceMath.scale(normal, radius + FieldSurfaceMath.FIELD_HALF_THICKNESS));
        onFieldTouched(touchPoint, entity);
    }

    private Vec3d getEntityCenter(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        return new Vec3d(
                (box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private void setEntityCenter(Entity entity, Vec3d center) {
        double halfWidth = entity.width * 0.5D;
        double halfHeight = entity.height * 0.5D;
        entity.setPosition(center.x - halfWidth, center.y - halfHeight, center.z - halfWidth);
    }

    private AxisAlignedBB getInfluenceBox() {
        return FieldSurfaceMath.influenceBox(this);
    }

    public int consumeShieldEnergy(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }

        int extracted = energy.extractEnergy(amount, false);
        if (extracted > 0) {
            shieldConsumedThisTick += extracted;
            markDirty();
            refreshFieldPowerState(true);
            queueClientSync(false);
        }
        return extracted;
    }

    private int estimateShieldCost(int r) {
        return Math.max(1, (int) Math.round(12.0D * Math.PI * r * r)) * 20;
    }

    private int clampRadius(int requestedRadius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, requestedRadius));
    }

    public int getShieldCycleCost() {
        return estimateShieldCost(radius);
    }

    public int getTier() {
        if (world == null) {
            return 0;
        }
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockFieldGenerator) {
            return clampTier(state.getValue(BlockFieldGenerator.TIER));
        }
        return 0;
    }

    public double getImpactEfficiencyMultiplier() {
        return 1.0D + ModConfig.shieldTierEfficiencyStep * getTier();
    }

    private boolean refreshFieldPowerState(boolean syncSnapshot) {
        if (world == null || world.isRemote) {
            return false;
        }

        int stored = energy.getEnergyStored();
        boolean newPowered = fieldPowered ? stored > 0 : stored >= getShieldActivationEnergy();
        boolean changed = fieldPowered != newPowered;
        fieldPowered = newPowered;
        if (changed) {
            markDirty();
            queueClientSync(syncSnapshot);
        }
        return changed;
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            ACTIVE_GENERATORS.remove(this);
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            ACTIVE_GENERATORS.remove(this);
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.onChunkUnload();
    }

    public static Set<TileEntityFieldGenerator> getActiveGenerators() {
        return ACTIVE_GENERATORS;
    }

    private int getShieldDrainForPhase(int phase) {
        int cycleCost = getShieldCycleCost();
        int baseDrain = cycleCost / 20;
        int remainder = cycleCost % 20;
        return baseDrain + (phase < remainder ? 1 : 0);
    }

    private int getShieldActivationEnergy() {
        return Math.max(1, (int) Math.ceil(energy.getMaxEnergyStored() * ModConfig.shieldActivationThreshold));
    }

    private int estimateEntityImpactEnergy(Entity entity) {
        Vec3d fieldCenter = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.motionX, entity.motionY, entity.motionZ);
        Vec3d normal = FieldSurfaceMath.sphereOutwardNormal(fieldCenter, currentCenter, motion);
        double baseCost;
        if (isEnergyProjectile(entity)) {
            baseCost = Math.max(ModConfig.shieldCollisionBaseEnergyCost, ModConfig.energyProjectileImpactEnergy * getImpactTypeMultiplier(entity));
        } else {
            double speedSq = FieldSurfaceMath.inwardNormalSpeedSq(motion, normal);
            double multiplier = isProjectile(entity) ? ModConfig.projectileImpactEnergyPerVelocitySq : ModConfig.entityImpactEnergyPerVelocitySq;
            baseCost = Math.max(ModConfig.shieldCollisionBaseEnergyCost, speedSq * multiplier * getImpactTypeMultiplier(entity));
        }
        int cost = (int) Math.ceil(baseCost / Math.max(1.0D, getImpactEfficiencyMultiplier()));
        return Math.max(1, cost);
    }

    private int estimateExplosionImpactEnergy(World world, Explosion explosion, Iterable<BlockPos> affectedBlocks) {
        int total = 0;
        for (BlockPos target : affectedBlocks) {
            if (target == null || !protects(target)) {
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

    private boolean isProjectile(Entity entity) {
        return entity instanceof EntityArrow || entity instanceof EntityThrowable || entity instanceof EntityFireball || entity instanceof IEnergyProjectile;
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
        Vec3d fieldCenter = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = new Vec3d(entity.motionX, entity.motionY, entity.motionZ);
        Vec3d normal = FieldSurfaceMath.sphereOutwardNormal(fieldCenter, currentCenter, motion);
        return fieldCenter.add(FieldSurfaceMath.scale(normal, radius + FieldSurfaceMath.FIELD_HALF_THICKNESS));
    }

    private void rememberPlayerSafePosition(Entity entity, boolean outside) {
        if (!(entity instanceof EntityPlayer) || entity.world == null || entity.world.isRemote) {
            return;
        }
        UUID uuid = entity.getUniqueID();
        PlayerLastSafePosition safePosition = PLAYER_LAST_SAFE_POSITIONS.get(uuid);
        if (safePosition == null) {
            safePosition = new PlayerLastSafePosition();
            PLAYER_LAST_SAFE_POSITIONS.put(uuid, safePosition);
        }
        safePosition.dimension = entity.world.provider.getDimension();
        safePosition.outside = outside;
        safePosition.x = entity.posX;
        safePosition.y = entity.posY;
        safePosition.z = entity.posZ;
    }

    private void queueClientSync(boolean includeSnapshot) {
        if (world == null || world.isRemote) {
            return;
        }
        if (includeSnapshot) {
            clientSnapshotQueued = true;
        }
        if (!clientSyncQueued) {
            clientSyncQueued = true;
            clientSyncCountdown = CLIENT_SYNC_BASE_INTERVAL_TICKS - 1 + world.rand.nextInt(CLIENT_SYNC_JITTER_TICKS + 1);
        }
    }

    private void tickClientSync() {
        if (world == null || world.isRemote || !clientSyncQueued) {
            return;
        }
        if (clientSyncCountdown > 0) {
            clientSyncCountdown--;
            return;
        }
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        if (clientSnapshotQueued) {
            PacketSyncActiveGenerators.sendFullSnapshot(world);
        }
        clientSyncQueued = false;
        clientSnapshotQueued = false;
        clientSyncCountdown = -1;
    }

    private void updateClientPrediction() {
        if (shieldDrainPhase < 0 || shieldDrainPhase > 19) {
            shieldDrainPhase = 0;
        }

        int requiredEnergy = getShieldDrainForPhase(shieldDrainPhase);
        shieldConsumedThisTick = 0;

        int storedBeforeDrain = energy.getEnergyStored();
        fieldPowered = fieldPowered ? storedBeforeDrain > 0 : storedBeforeDrain >= getShieldActivationEnergy();
        if (fieldPowered) {
            int drained = energy.extractEnergy(requiredEnergy, false);
            shieldConsumedThisTick = drained;
        }

        shieldDrainPhase = (shieldDrainPhase + 1) % 20;
        int stored = energy.getEnergyStored();
        fieldPowered = fieldPowered ? stored > 0 : stored >= getShieldActivationEnergy();
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("radius", radius);
        compound.setInteger("energy", energy.getEnergyStored());
        compound.setString("accessCode", accessCode);
        compound.setBoolean("fieldPowered", fieldPowered);
        compound.setInteger("shieldDrainPhase", shieldDrainPhase);
        compound.setInteger("shieldReceivedThisTick", shieldReceivedThisTick);
        compound.setInteger("shieldConsumedThisTick", shieldConsumedThisTick);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        radius = clampRadius(compound.getInteger("radius"));
        energy.setEnergyStored(Math.max(0, Math.min(energy.getMaxEnergyStored(), compound.getInteger("energy"))));
        shieldReceivedThisTick = Math.max(0, compound.getInteger("shieldReceivedThisTick"));
        shieldConsumedThisTick = Math.max(0, compound.getInteger("shieldConsumedThisTick"));
        accessCode = CodeUtils.normalize(compound.getString("accessCode"));
        fieldPowered = compound.getBoolean("fieldPowered");
        shieldDrainPhase = Math.max(0, Math.min(19, compound.getInteger("shieldDrainPhase")));
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

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    public int getMaxEnergyStored() {
        return energy.getMaxEnergyStored();
    }

    public int getShieldReceivedThisTick() {
        return shieldReceivedThisTick;
    }

    public int getShieldConsumedThisTick() {
        return shieldConsumedThisTick;
    }

    private class ShieldEnergyStorage extends EnergyStorage {
        ShieldEnergyStorage(int capacity, int maxReceive, int maxExtract) {
            super(capacity, maxReceive, maxExtract);
        }

        void setEnergyStored(int value) {
            this.energy = value;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = super.receiveEnergy(maxReceive, simulate);
            if (accepted > 0 && !simulate) {
                markDirty();
                queueClientSync(false);
            }
            return accepted;
        }
    }

    private static int clampTier(int tier) {
        return Math.max(0, Math.min(BlockFieldGenerator.TIER_COUNT - 1, tier));
    }

    private static final class PlayerLastSafePosition {
        private int dimension;
        private boolean outside;
        private double x;
        private double y;
        private double z;
    }
}
