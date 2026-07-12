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
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

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

    /**
     * The piloted ship's body-frame velocity and Flight-Assist setpoint (blocks/tick), replicated so
     * the pilot's Free Flight HUD can draw the same three-axis panel a tier-1 rocket gets.
     *
     * <p>A ship's velocity lives on the physics thread and its setpoint on the flight computer, neither
     * of which the client can see. A rocket needs no such channel — it IS an entity, so vanilla motion
     * sync carries it. Hanging the six numbers off the seat's dummy reuses exactly that mechanism: the
     * dummy already ticks on both sides and is tracked by precisely the player riding it.</p>
     */
    private static final DataParameter<Float> VEL_FORWARD =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> VEL_RIGHT =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> VEL_UP =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> SETPOINT_FORWARD =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> SETPOINT_RIGHT =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> SETPOINT_UP =
            EntityDataManager.createKey(EntityDummy.class, DataSerializers.FLOAT);

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
        this.dataManager.register(VEL_FORWARD, 0f);
        this.dataManager.register(VEL_RIGHT, 0f);
        this.dataManager.register(VEL_UP, 0f);
        this.dataManager.register(SETPOINT_FORWARD, 0f);
        this.dataManager.register(SETPOINT_RIGHT, 0f);
        this.dataManager.register(SETPOINT_UP, 0f);
    }

    /** The piloted ship's body-frame velocity {forward, right, up} in blocks/tick, as last synced. */
    public double[] getShipBodyVelocity() {
        return new double[]{
                this.dataManager.get(VEL_FORWARD),
                this.dataManager.get(VEL_RIGHT),
                this.dataManager.get(VEL_UP)};
    }

    /** The ship's Flight-Assist setpoint {forward, right, up} in blocks/tick, as last synced. */
    public double[] getShipSetpoint() {
        return new double[]{
                this.dataManager.get(SETPOINT_FORWARD),
                this.dataManager.get(SETPOINT_RIGHT),
                this.dataManager.get(SETPOINT_UP)};
    }

    /** The pilot this dummy carried last server tick, so a dismount can be detected. */
    private Entity lastRider = null;
    /** The just-dismounted pilot being held onto the deck, or null. */
    private Entity dismountedPilot = null;
    /** Ticks left in the hold window during which the ex-pilot is kept on the deck. */
    private int dismountHoldTicks = 0;
    /** How long (ticks) to keep re-seating a just-dismounted pilot onto the deck. One snap is not enough:
     *  the seat block has no collision and the deck sits a fraction below the seat, so ShipFrameTravel's
     *  support probe only barely overlaps it - a single tick where it just misses (thin margin, or the
     *  client has not yet applied the server teleport) drops the pilot off a hovering ship. Re-seating
     *  across a short window gives the capture many attempts, so it reliably sticks. */
    private static final int DISMOUNT_HOLD_TICKS = 20;

    /**
     * When the pilot stands up, keep him ON the deck. Vanilla's dismount searches for a non-colliding
     * spot around this dummy, but the ship's deck lives in a subspace it cannot see (and the seat block
     * has no collision), so it can drop the pilot beside or below the hull - off a hovering ship he then
     * falls away entirely (the playtest: stood up, ended on the ground far below). This re-seats the
     * just-dismounted rider onto the seat's live world position, where this dummy sits on the deck, every
     * tick for a short window until {@link zmaster587.advancedRocketry.integration.vs.ShipFrameTravel}
     * has captured him there. Only for a linked pilot seat on a loaded ship - a plain ground seat keeps
     * vanilla's dismount untouched.
     */
    private void keepDismountedPilotOnDeck() {
        Entity current = getPassengers().isEmpty() ? null : getPassengers().get(0);
        if (current != null) {
            lastRider = current;
            dismountedPilot = null;
            dismountHoldTicks = 0;
            return;
        }
        if (lastRider != null) {
            // The seat emptied this tick: begin holding the ex-pilot on the deck.
            dismountedPilot = lastRider;
            lastRider = null;
            dismountHoldTicks = DISMOUNT_HOLD_TICKS;
        }
        if (dismountedPilot == null || dismountHoldTicks <= 0) {
            dismountedPilot = null;
            return;
        }
        dismountHoldTicks--;
        Entity exit = dismountedPilot;
        if (exit.isDead || exit.world != world) {
            dismountedPilot = null;
            return;
        }
        TilePilotSeat seat = TilePilotSeat.forRider(this, world);
        BlockPos seatPos = getSeatPos();
        if (seat == null || !seat.isLinked() || seatPos == null
                || VSIntegration.getSeatWorldPosition(world, seatPos) == null) {
            dismountedPilot = null; // a plain seat, or off a loaded ship: leave vanilla's dismount alone
            return;
        }
        // Captured on the deck by ShipFrameTravel already? Stop holding so he can walk freely.
        if (zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolving(exit)) {
            dismountedPilot = null;
            return;
        }
        // Not captured yet. Crew movement is client-authoritative, so we cannot capture him from the
        // server - a server teleport of his body reads on his own client as an external move and drops the
        // client-side capture that actually holds him (ShipFrameTravel's ~1mm external-move guard). Instead
        // ASK his client to capture him, handing it the deck point in ship SUBSPACE (the seat column's
        // centre at the deck top); the client maps that through its OWN ship transform, snaps there and
        // holds it. A server-computed world position would differ here by more than the guard and drop
        // instantly. Re-sent each tick of the window; the client seeds once and no-ops the rest.
        if (exit instanceof net.minecraft.entity.player.EntityPlayerMP) {
            zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                    new zmaster587.advancedRocketry.network.PacketDeckCapture(
                            seatPos.getX() + 0.5, seatPos.getY(), seatPos.getZ() + 0.5),
                    (net.minecraft.entity.player.EntityPlayerMP) exit);
        }
    }

    /** Server-side: publish the ship's flight telemetry to the rider. Only writes on a real change,
     *  so an idle ship costs no metadata packets. Also releases the controls when the pilot stands up:
     *  the computer holds the last input it was sent, so without this the ship would keep flying his
     *  final command after he left the seat. */
    private void syncFlightTelemetry() {
        TilePilotSeat seat = TilePilotSeat.forRider(this, world);
        if (seat == null || !seat.isLinked()) {
            return;
        }
        TileAdvancedFlightComputer afc = seat.getFlightComputer();
        if (afc == null) {
            return;
        }
        if (getPassengers().isEmpty() && afc.pilotInput != null) {
            afc.setPilotInput(null);
        }
        double[] velocity = afc.getHudBodyVelocity();
        double[] setpoint = afc.getHudSetpoint();
        setIfChanged(VEL_FORWARD, velocity[0]);
        setIfChanged(VEL_RIGHT, velocity[1]);
        setIfChanged(VEL_UP, velocity[2]);
        setIfChanged(SETPOINT_FORWARD, setpoint[0]);
        setIfChanged(SETPOINT_RIGHT, setpoint[1]);
        setIfChanged(SETPOINT_UP, setpoint[2]);
    }

    private void setIfChanged(DataParameter<Float> key, double value) {
        float next = (float) value;
        if (Math.abs(this.dataManager.get(key) - next) > 1.0e-4f) {
            this.dataManager.set(key, next);
        }
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
        if (!world.isRemote) {
            keepDismountedPilotOnDeck();
            syncFlightTelemetry();
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
