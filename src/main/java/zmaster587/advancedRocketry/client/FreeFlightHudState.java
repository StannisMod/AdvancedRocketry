package zmaster587.advancedRocketry.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.EntityRocket;
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
     * Whether {@link #bodyForward}/{@link #bodyRight}/{@link #bodyUp} and the FA setpoints carry
     * real client-side data. The rocket knows its own motion + setpoints; the tier-2 ship's
     * velocity lives on the physics thread and is not synced to the client, so its HUD omits the
     * velocity bars rather than showing zeros.
     */
    public final boolean hasVelocity;

    /** Actual body-frame velocity (blocks/tick): forward, right, up. Valid iff {@link #hasVelocity}. */
    public final double bodyForward, bodyRight, bodyUp;
    /** Flight-Assist setpoints (body frame, blocks/tick). Valid iff {@link #hasVelocity}. */
    public final double faForward, faRight, faUp;

    private FreeFlightHudState(int tier, boolean inFlight, boolean flightAssistOn, boolean hasVelocity,
                              double bodyForward, double bodyRight, double bodyUp,
                              double faForward, double faRight, double faUp) {
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
                    rocket.getFaSetpointForward(), rocket.getFaSetpointRight(), rocket.getFaSetpointUp());
        }
        TilePilotSeat seat = TilePilotSeat.forRider(riding, world);
        if (seat != null && seat.isLinked()) {
            // Tier-2 ship: seated = flying; its control law is always flight-assisted. The ship's
            // velocity is physics-thread state not synced to the client, so no bars.
            return new FreeFlightHudState(2, true, true, false, 0, 0, 0, 0, 0, 0);
        }
        return null;
    }
}
