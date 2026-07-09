package zmaster587.advancedRocketry.entity;

import com.google.common.base.Optional;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

public class EntityDummy extends Entity {

    /**
     * The seat block this dummy belongs to, synced to the client. A tier-2 ship pilot must find
     * its seat's TileEntity from the CLIENT, but on a Valkyrien Skies ship the dummy is RENDERED at
     * world coordinates while the seat block lives at a distant ship-subspace position — so
     * {@code new BlockPos(this)} (the dummy's world pos) does NOT locate the seat tile. The seat's
     * BlockPos, however, is identical on client and server (the ship structure is mirrored), so we
     * carry it here and resolve the seat with it. Absent for ordinary (non-pilot) seats, where the
     * dummy sits at the seat block and its own position suffices.
     */
    private static final DataParameter<Optional<BlockPos>> SEAT_POS =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.OPTIONAL_BLOCK_POS);

    //Just a dummy so a player can sit on a chair
    public EntityDummy(World world) {
        super(world);
        this.noClip = true;
        this.height = 0f;

    }

    public EntityDummy(World world, double x, double y, double z) {
        this(world);
        setPosition(x, y, z);
    }

    /** Bind this dummy to the seat block it belongs to (see {@link #SEAT_POS}); server-side. */
    public void setSeatPos(BlockPos pos) {
        this.dataManager.set(SEAT_POS, Optional.fromNullable(pos));
    }

    /** The bound seat block position (client or server), or {@code null} if unbound. */
    public BlockPos getSeatPos() {
        return this.dataManager.get(SEAT_POS).orNull();
    }

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean isInvisibleToPlayer(EntityPlayer player) {
        return true;
    }

    /**
     * Checks if the entity is in range to render by using the past in distance and comparing it to its average edge
     * length * 64 * renderDistanceWeight Args: distance
     */
    @SideOnly(Side.CLIENT)
    @Override
    public boolean isInRangeToRenderDist(double p_70112_1_) {
        return false;
    }


    @Override
    protected void entityInit() {
        this.dataManager.register(SEAT_POS, Optional.absent());
    }

    @Override
    public boolean shouldRiderSit() {
        return true;
    }

    /**
     * Glue this dummy — and thus its seated rider — to its ship every tick.
     *
     * <p>A pilot-seat dummy is bound to its seat block ({@link #getSeatPos()}), which on a
     * Valkyrien Skies ship lives in a stationary shipyard subspace while the ship itself flies
     * around the world. A plain world entity is not part of the ship's rigid body, so without
     * this it would sit at its spawn point while the ship departs. We ask the integration for the
     * seat's live world position (the seat's subspace centre mapped through the ship transform)
     * and snap there. Runs on BOTH sides — each reads its own synced ship transform, so client and
     * server agree and the rider tracks the ship with no rubber-banding. A safe no-op for an
     * unbound (ordinary) seat or when the physics mod is absent, leaving vanilla behaviour intact.</p>
     */
    @Override
    public void onUpdate() {
        super.onUpdate();
        BlockPos seat = getSeatPos();
        if (seat == null) {
            return;
        }
        double[] worldSeat = VSIntegration.getSeatWorldPosition(world, seat);
        if (worldSeat != null) {
            setPosition(worldSeat[0], worldSeat[1], worldSeat[2]);
        }
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        if (compound.hasKey("seatX")) {
            setSeatPos(new BlockPos(compound.getInteger("seatX"),
                    compound.getInteger("seatY"), compound.getInteger("seatZ")));
        }
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        BlockPos seat = getSeatPos();
        if (seat != null) {
            compound.setInteger("seatX", seat.getX());
            compound.setInteger("seatY", seat.getY());
            compound.setInteger("seatZ", seat.getZ());
        }
    }


}
