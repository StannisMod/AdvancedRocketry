package zmaster587.advancedRocketry.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * A backend-agnostic snapshot of the Free Flight craft the client is piloting, so the FF HUD is
 * rendered by ONE code path for both flight backends: a tier-1 {@link EntityRocket} and a tier-2
 * Valkyrien Skies ship (piloted from a {@link TilePilotSeat}). {@link #forView} decides which
 * backend the player is driving and fills what that backend can supply — the renderer degrades
 * gracefully for a backend that cannot provide velocity ({@link #hasVelocity}).
 */
@SideOnly(Side.CLIENT)
public final class FreeFlightHudState {

    /** 1 = tier-1 rocket, 2 = tier-2 ship. Shown on the HUD. */
    public final int tier;
    /** Whether the craft is actively flying (rocket in flight / seated on a ship). */
    public final boolean inFlight;
    public final boolean flightAssistOn;
    /**
     * Whether {@link #bodyForward}/{@link #bodyRight}/{@link #bodyUp} and the FA setpoints carry real
     * client-side data. The rocket knows its own motion + setpoints because it IS an entity; the ship's
     * ride the seat dummy's tracked data. False only when a backend cannot supply them at all.
     */
    public final boolean hasVelocity;

    /** Actual body-frame velocity (blocks/tick): forward, right, up. Valid iff {@link #hasVelocity}. */
    public final double bodyForward, bodyRight, bodyUp;
    /** Flight-Assist setpoints (body frame, blocks/tick). Valid iff {@link #hasVelocity}. */
    public final double faForward, faRight, faUp;
    /**
     * Full-scale deflection of the HUD's velocity bars (blocks/tick).
     *
     * <p>It is a <b>reference</b> cruise speed, NOT a maximum: free flight has no top speed with the
     * assist off, so a bar drawn against a fixed full scale pegs and then tells the pilot nothing for
     * the rest of the burn. The scale therefore starts at the craft's cruise reference — so ordinary
     * flying looks exactly as it always did — and GROWS to the fastest axis whenever the craft is
     * quicker than that. The bars stay a readable picture of the velocity vector's shape at any
     * speed; the exact numbers are in the text readout beside them.</p>
     */
    public final double barScale;

    /**
     * The drive readout for a seated tier-2 pilot: 0 = no drive aboard, 1 = a drive but not armed,
     * 2 = armed ({@code TileAdvancedFlightComputer.DriveReadout} ordinals). A rocket has no drive,
     * so it reads 0 and the HUD says nothing about jumping.
     */
    public final int driveState;
    /** Capacitor charge as a fraction of capacity (0..1). Meaningless while {@link #driveState} is 0. */
    public final float driveCharge;
    /** Ticks left in the jump wind-up, 0 when not spooling. The one moment the pilot must act. */
    public final int spoolTicks;
    /** The coarse jump phase ({@code ShipTransitManager.Phase} ordinal); 0 = not in flight. */
    public final int transitPhase;

    /**
     * @param cruiseReference the speed the bars are scaled against while the craft is no faster than
     *                        it (blocks/tick); above it the scale follows the craft — see
     *                        {@link #barScale}
     */
    private FreeFlightHudState(int tier, boolean inFlight, boolean flightAssistOn, boolean hasVelocity,
                              double bodyForward, double bodyRight, double bodyUp,
                              double faForward, double faRight, double faUp, double cruiseReference,
                              int driveState, float driveCharge, int spoolTicks, int transitPhase) {
        this.driveState = driveState;
        this.driveCharge = driveCharge;
        this.spoolTicks = spoolTicks;
        this.transitPhase = transitPhase;
        this.tier = tier;
        this.inFlight = inFlight;
        this.flightAssistOn = flightAssistOn;
        this.hasVelocity = hasVelocity;
        this.bodyForward = bodyForward;
        this.bodyRight = bodyRight;
        this.bodyUp = bodyUp;
        this.faForward = faForward;
        this.faRight = faRight;
        this.faUp = faUp;
        // Grow the scale to whatever the craft is actually doing, per axis and per setpoint, so no
        // bar can peg. Both are included because with the assist on the pilot can dial a setpoint the
        // craft has not reached yet, and a notch outside the bar is worse than no notch.
        double widest = cruiseReference;
        if (hasVelocity) {
            widest = Math.max(widest, Math.abs(bodyForward));
            widest = Math.max(widest, Math.abs(bodyRight));
            widest = Math.max(widest, Math.abs(bodyUp));
            widest = Math.max(widest, Math.abs(faForward));
            widest = Math.max(widest, Math.abs(faRight));
            widest = Math.max(widest, Math.abs(faUp));
        }
        // A NaN velocity (an un-synced backend) must not take the scale to NaN and blank the bars.
        this.barScale = (Double.isNaN(widest) || widest <= 0.0) ? cruiseReference : widest;
    }

    /** Speed magnitude (blocks/tick) from the body-frame velocity; 0 when velocity is unknown. */
    public double speed() {
        if (!hasVelocity) {
            return 0.0;
        }
        return Math.sqrt(bodyForward * bodyForward + bodyRight * bodyRight + bodyUp * bodyUp);
    }

    /**
     * The FF HUD state for the craft {@code player} is piloting, or {@code null} if the player is
     * not piloting a Free Flight craft (so the HUD should not draw).
     */
    public static FreeFlightHudState forView(EntityPlayer player, World world) {
        if (player == null) {
            return null;
        }
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityRocket) {
            EntityRocket rocket = (EntityRocket) riding;
            if (!rocket.isFreeFlight()) {
                return null;
            }
            double[] act = FreeFlightPhysics.worldToBody(
                    rocket.motionX, rocket.motionY, rocket.motionZ,
                    rocket.rotationYaw, rocket.rotationPitch);
            return new FreeFlightHudState(1, rocket.isInFlight(), rocket.isFlightAssistOn(), true,
                    act[0], act[1], act[2],
                    rocket.getFaSetpointForward(), rocket.getFaSetpointRight(), rocket.getFaSetpointUp(),
                    FreeFlightPhysics.FA_SETPOINT_MAX_SPEED,
                    0, 0f, 0, 0);
        }
        // The link alone is NOT evidence that a ship exists — it is a build-time intention that
        // survives a rejected assembly, so on its own it lit this entire panel (velocity readout,
        // Flight-Assist indicator, "in flight") for a craft that was an inert pile of blocks.
        // The shared pilot oracle requires a ship; see TilePilotSeat#forShipPilot.
        TilePilotSeat seat = TilePilotSeat.forShipPilot(riding, world);
        if (seat != null) {
            // Tier-2 ship: seated = flying. Flight-Assist state is synced from the ship's computer to
            // the seat; the ship's body-frame velocity and cruise setpoint ride the seat dummy's
            // tracked data, both already in blocks/tick, so the panel reads exactly as the rocket's.
            EntityDummy dummy = (EntityDummy) riding;
            double[] velocity = dummy.getShipBodyVelocity();
            double[] setpoint = dummy.getShipSetpoint();
            return new FreeFlightHudState(2, true, seat.isFlightAssistOn(), true,
                    velocity[0], velocity[1], velocity[2],
                    setpoint[0], setpoint[1], setpoint[2],
                    TileAdvancedFlightComputer.SHIP_MAX_SPEED / 20.0,
                    dummy.getDriveState(), dummy.getDriveCharge(),
                    dummy.getSpoolTicks(), dummy.getTransitPhase());
        }
        return null;
    }
}
