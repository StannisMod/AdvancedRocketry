package com.github.stannismod.affs.te;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.block.BlockFieldGenerator;
import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.network.PacketFieldTouchEffect;
import com.github.stannismod.affs.network.PacketSyncActiveGenerators;
import com.github.stannismod.affs.util.CodeUtils;
import com.github.stannismod.affs.world.FieldFrame;
import com.github.stannismod.affs.world.FieldFrames;
import com.github.stannismod.affs.world.FieldSource;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import com.github.stannismod.affs.world.WorldFieldFrame;
import com.github.stannismod.affs.world.projectile.IEnergyProjectile;
import com.github.stannismod.affs.world.shield.IShieldSink;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import com.github.stannismod.affs.world.shield.ShieldNetworkRegistry;
import com.github.stannismod.affs.world.shield.ShieldNetworkState;
import com.github.stannismod.affs.world.shield.ShieldStrikeKind;
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
    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;
    private static final DamageSource SHIELD_COLLISION_DAMAGE = new DamageSource("affs.shield_collision");
    private static final Set<TileEntityFieldGenerator> ACTIVE_GENERATORS = new HashSet<>();
    private static final Map<UUID, PlayerLastSafePosition> PLAYER_LAST_SAFE_POSITIONS = new HashMap<>();

    // Coil capacity is read from config at construction (config is loaded in preInit, before any tile
    // is built). Small and fast: the field activates at shieldActivationThreshold of this capacity.
    // Both intake and extraction are UNTHROTTLED at the storage (maxReceive == maxExtract == capacity):
    //   - the per-tick recharge-throughput cap (D134-3) is tier-dependent (getRechargeThroughput()) and
    //     read from the world block state at runtime, so it cannot live on this construction-time field;
    //     it is enforced instead as the coil's advertised network demand (getRequestedShieldEnergy),
    //     which is the single source of truth for the throttle;
    //   - extraction is unthrottled because absorbing one hit may need to spend far more than a tick's
    //     intake, so a per-tick extract cap would make the coil unable to block any impact above it.
    private final ShieldEnergyStorage energy = new ShieldEnergyStorage(ModConfig.emitterCoilBuffer, ModConfig.emitterCoilBuffer, ModConfig.emitterCoilBuffer);
    private int radius = DEFAULT_RADIUS;
    // The frame this emitter's field lives in (§4.3): identity standalone, ship-frame on a VS hull.
    // Resolved from the block's position (a network is entirely on one ship or standalone) and refreshed
    // each tick, so an emitter assembled into a ship after placement picks up its ship frame.
    private FieldFrame fieldFrame = WorldFieldFrame.INSTANCE;
    private String accessCode = "";
    // Redistribution priority (D134-5): higher = fed first under a deficit. The setting is emitter-owned
    // (survives losing the console); a priority group is a domain-level named selection that pushes this
    // value down into its members. Default 0 = normal.
    private int priority = 0;
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

        resolveFieldFrame();

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
            refreshFieldPowerState(true);
            queueClientSync(true);
        }
    }

    public String getAccessCode() {
        return accessCode;
    }

    @Override
    public int getShieldPriority() {
        return priority;
    }

    public void setPriority(int value) {
        if (value == priority) {
            return;
        }
        priority = value;
        if (world != null && !world.isRemote) {
            markDirty();
            ShieldNetworkManager.markDirty(world);
            queueClientSync(false);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        resolveFieldFrame();
        if (world != null && !world.isRemote) {
            ACTIVE_GENERATORS.add(this);
            ShieldNetworkRegistry.register(this);
            ShieldNetworkManager.markDirty(world);
            refreshFieldPowerState(true);
        }
    }

    /** Re-resolve this emitter's frame from its position (cheap; a map lookup when VS is present). */
    private void resolveFieldFrame() {
        fieldFrame = FieldFrames.forBlock(world, pos);
    }

    @Override
    public Vec3d getWorldCenter() {
        // The field centre mapped into world space through this emitter's frame (§4.3). Identity
        // standalone; ship-transformed on a VS hull so the shell tracks the flying ship. Falls back to
        // the raw block centre only if the frame momentarily cannot resolve — isFrameReady() gates the
        // emitter out of the active set in that case, so this fallback is not used for a live shell.
        Vec3d c = fieldFrame.fieldToWorld(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return c != null ? c : new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** True when this emitter's frame resolves — always so standalone, and only while the ship is
     *  loaded on a VS hull. A not-ready emitter contributes no shell (Q4 fail-open). */
    public boolean isFrameReady() {
        return fieldFrame.isReady();
    }

    /** The shell's own world velocity at a world point (zero standalone; the hull's motion on a ship),
     *  which impact cost and deflection are taken relative to so a cruising ship does not bill its crew. */
    private Vec3d shellVelocityAt(Vec3d worldPoint) {
        return fieldFrame.surfaceVelocityAt(worldPoint.x, worldPoint.y, worldPoint.z);
    }

    /** True when this emitter's field lives in a VS ship frame (its world centre is the hull-transformed
     *  subspace centre, not the raw block centre). Test/diagnostic observability of the §4.3 seam. */
    public boolean isShipFramed() {
        return fieldFrame instanceof com.github.stannismod.affs.world.ShipFieldFrame;
    }

    /** The shell's current world velocity at its own centre — the relative-velocity input the ship-frame
     *  deflection subtracts. Zero standalone; the hull's motion on a moving ship. Test observability. */
    public Vec3d getShellVelocity() {
        return shellVelocityAt(getWorldCenter());
    }

    /**
     * Mirror a DECLARED travelling body's velocity off this shell at a world point, with the same law
     * {@link #pushEntityBack} uses for a travelling entity: take the velocity relative to the shell,
     * reflect it about the outward normal, then add the shell's own motion back so the deflected body
     * still rides a moving ship. The two populations share one reflection law rather than two
     * implementations free to disagree.
     *
     * <p>Two deliberate differences from the entity path. The bounce is scaled by the restitution
     * tunable (default 1.0 — a perfect mirror, i.e. identical to the entity path); and there is no
     * minimum-kick fallback for a degenerate mirror. An entity must end up somewhere, so it is nudged
     * outward; a shot has the better option of ceasing to exist, and the caller ends it at the crossing
     * point rather than leaving a near-motionless record alive.</p>
     */
    public Vec3d reflectBodyVelocity(Vec3d worldPoint, Vec3d velocity) {
        if (worldPoint == null || velocity == null) {
            return null;
        }
        Vec3d shellVelocity = shellVelocityAt(worldPoint);
        Vec3d relative = FieldSurfaceMath.subtract(velocity, shellVelocity);
        Vec3d normal = FieldSurfaceMath.sphereOutwardNormal(getWorldCenter(), worldPoint, relative);
        Vec3d reflected = FieldSurfaceMath.reflect(relative, normal);
        double restitution = Math.max(0.0D, Math.min(1.0D, ModConfig.shieldStrikeReflectionRestitution));
        return FieldSurfaceMath.scale(reflected, restitution).add(shellVelocity);
    }

    /** TEST ONLY: set the coil's stored shield energy directly and refresh the powered state. Lets an
     *  e2e power a shield on an assembled VS ship without wiring a generator/FE feed into the subspace
     *  structure — the ship-frame geometry, not the energy economy, is what that test exercises. */
    public void setShieldEnergyForTest(int amount) {
        if (world == null || world.isRemote) {
            return;
        }
        energy.setEnergyStored(Math.max(0, Math.min(energy.getMaxEnergyStored(), amount)));
        markDirty();
        refreshFieldPowerState(true);
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
        // Advertise only what the coil can physically intake this tick (min of free space and this
        // emitter's tier-scaled recharge throughput). The network solver uses this as the coil's
        // demand-edge capacity, so (a) a large source (e.g. an accumulator) can never have more energy
        // extracted from it than the coil actually receives — keeping the network energy-conserving —
        // and (b) regeneration is capped at the emitter's throughput (D134-3), the per-zone bottleneck.
        return Math.min(getFreeShieldCapacity(), getRechargeThroughput());
    }

    /**
     * This emitter's per-tick shield-energy recharge throughput (D134-3), the per-zone regeneration
     * bottleneck. Tier-scaled from the config base: a higher-tier emitter pours energy into its zone
     * faster. This is the cap the network never exceeds when refilling the coil, so a big generator or
     * accumulator behind a small emitter cannot over-regenerate.
     */
    public int getRechargeThroughput() {
        double scaled = ModConfig.emitterRechargeThroughputBase
                * (1.0D + ModConfig.emitterThroughputTierStep * getTier());
        return Math.max(1, (int) Math.round(scaled));
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
        Vec3d c = getWorldCenter();
        double dx = x - c.x;
        double dy = y - c.y;
        double dz = z - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public void applyAccessCode(String code) {
        String normalized = CodeUtils.normalize(code);
        if (!normalized.equals(accessCode)) {
            accessCode = normalized;
            markDirty();
            queueClientSync(false);
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

        Vec3d worldCenter = getWorldCenter();
        double outwardX = currentCenterX - worldCenter.x;
        double outwardY = currentCenterY - worldCenter.y;
        double outwardZ = currentCenterZ - worldCenter.z;
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

        Vec3d fieldCenter = getWorldCenter();
        Vec3d currentCenter = getEntityCenter(entity);
        // Reflect the entity's motion RELATIVE to the shell (§4.3 pt 3): subtract the hull's own
        // per-tick motion, reflect off the sphere, then add the hull motion back so the deflected body
        // still rides the moving ship. Standalone the shell velocity is zero and this is unchanged.
        Vec3d shellVelocity = shellVelocityAt(currentCenter);
        Vec3d motion = FieldSurfaceMath.subtract(
                new Vec3d(entity.posX - entity.prevPosX, entity.posY - entity.prevPosY, entity.posZ - entity.prevPosZ),
                shellVelocity);
        Vec3d normal = FieldSurfaceMath.sphereOutwardNormal(fieldCenter, currentCenter, motion);
        Vec3d reflectedMotion = FieldSurfaceMath.reflect(motion, normal);
        if (FieldSurfaceMath.vectorLength(reflectedMotion) <= 1.0E-8D) {
            reflectedMotion = FieldSurfaceMath.scale(normal, Math.max(0.08D, FieldSurfaceMath.vectorLength(motion)));
        }
        Vec3d committedMotion = reflectedMotion.add(shellVelocity);

        double entityRadius = Math.max(entity.width, entity.height) * 0.5D;
        Vec3d targetCenter = fieldCenter.add(FieldSurfaceMath.scale(normal, radius + FieldSurfaceMath.FIELD_HALF_THICKNESS + entityRadius + 0.05D));
        setEntityCenter(entity, targetCenter);

        entity.motionX = committedMotion.x;
        entity.motionY = committedMotion.y;
        entity.motionZ = committedMotion.z;
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

        // Absorb fully or not at all. Both callers treat a short extract as "not blocked", so a hit the
        // coil cannot fully cover must pass through WITHOUT wasting the partial charge it could spend.
        if (energy.getEnergyStored() < amount) {
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

    /**
     * Graceful partial spend for a declared strike / residual ray (D134-2): extract up to {@code cost}
     * from the coil, returning what was actually spent ({@code min(stored, cost)}). Distinct from
     * {@link #consumeShieldEnergy} (all-or-nothing, the entity / explosion path): a beam the coil cannot
     * fully cover is partly absorbed and its remainder passes, draining the shield toward zero — the
     * "shields fall" degrade.
     */
    public int absorbShieldEnergy(int cost) {
        if (world == null || world.isRemote || cost <= 0) {
            return 0;
        }
        int spent = energy.extractEnergy(cost, false);
        if (spent > 0) {
            shieldConsumedThisTick += spent;
            markDirty();
            refreshFieldPowerState(true);
            queueClientSync(false);
        }
        return spent;
    }

    /**
     * Resistance-bias multiplier for a declared strike of the given kind (D134-2): RADIANT (energy)
     * strikes are billed at {@code 1.5 - bias}, KINETIC (physical) at {@code 0.5 + bias}, where the bias
     * is the network's energy/physical resistance balance. Same formula the entity scan uses per impact.
     */
    public double getStrikeKindMultiplier(ShieldStrikeKind kind) {
        double bias = ModConfig.shieldEnergyResistanceBias;
        ShieldNetworkState state = ShieldNetworkManager.getState(world, pos);
        if (state != null) {
            bias = state.getShieldEnergyResistanceBias();
        }
        bias = Math.max(0.0D, Math.min(1.0D, bias));
        return kind == ShieldStrikeKind.RADIANT ? (1.5D - bias) : (0.5D + bias);
    }

    private int estimateShieldCost(int r) {
        // Passive-maintenance draw (D134-4, the small draw): proportional to the field's surface area
        // (~r^2), tunable via the config coefficient, and spread across a 20-tick cycle by the caller.
        return Math.max(1, (int) Math.round(ModConfig.emitterMaintenanceEnergyPerSurfaceArea * Math.PI * r * r)) * 20;
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

    /**
     * Cheap global short-circuit for the strike / residual-ray paths: true iff any emitter is loaded and
     * active anywhere. Lets a raytrace-layer hook bail in O(1) in the common no-shields-present case
     * before touching per-generator geometry.
     */
    public static boolean hasActiveGenerators() {
        return !ACTIVE_GENERATORS.isEmpty();
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
        Vec3d fieldCenter = getWorldCenter();
        Vec3d currentCenter = getEntityCenter(entity);
        // Velocity RELATIVE to the shell (§4.3 pt 3): on a moving ship, subtract the hull's own motion
        // so the impact energy is what the entity carries toward the shell, not the whole world's speed.
        Vec3d motion = FieldSurfaceMath.subtract(
                new Vec3d(entity.motionX, entity.motionY, entity.motionZ), shellVelocityAt(currentCenter));
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
        return getStrikeKindMultiplier(isEnergyProjectile(entity)
                ? ShieldStrikeKind.RADIANT : ShieldStrikeKind.KINETIC);
    }

    private Vec3d getImpactTouchPoint(Entity entity) {
        Vec3d fieldCenter = getWorldCenter();
        Vec3d currentCenter = getEntityCenter(entity);
        Vec3d motion = FieldSurfaceMath.subtract(
                new Vec3d(entity.motionX, entity.motionY, entity.motionZ), shellVelocityAt(currentCenter));
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
        compound.setInteger("priority", priority);
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
        priority = compound.getInteger("priority");
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
