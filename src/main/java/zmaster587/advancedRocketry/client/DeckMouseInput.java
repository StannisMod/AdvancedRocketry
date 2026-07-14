package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * Deck-relative mouse look for a crew member walking a rolled deck.
 *
 * <p>The deck camera ({@link ShipFrameCamera#deckLevelledCameraEuler}) keeps the viewer's own world
 * yaw and pitch and adds only the roll that levels the horizon with the deck, so the crosshair still
 * points exactly where he aims - {@code getLook()}, and therefore block interaction, is untouched. But
 * on a deck rolled toward inverted the whole screen is turned with it, while the raw mouse delta is
 * still applied on world yaw/pitch, so the input feels rotated relative to the screen - at 180 degrees
 * of roll, fully inverted (the playtest report: "walking an upside-down ship, the look mouse is
 * inverted").</p>
 *
 * <p>Rotating the mouse delta by the same roll the camera applied makes a horizontal mouse move read
 * as horizontal on the rolled screen again, and at 180 degrees becomes a clean sign flip. It changes
 * only the FEEL: {@code getLook()}, the crosshair, the yaw/pitch sent to the server and the rendered
 * body all stay on world axes, because only the delta fed into the existing world yaw/pitch is turned.
 * A full deck-frame look - where the aim itself is deck-relative - is the deferred alternative if this
 * is not enough.</p>
 */
@SideOnly(Side.CLIENT)
public final class DeckMouseInput {

    private DeckMouseInput() {}

    /**
     * If {@code self} is the local player whose movement is being resolved on a deck with the deck
     * camera engaged, apply {@code (yawDelta, pitchDelta)} rotated by the camera roll and return true so
     * the caller cancels the vanilla turn. Otherwise return false and leave vanilla to run unchanged -
     * so this is a no-op off a deck, while piloting (not resolved as walking crew), and on a level deck
     * (roll ~0, the rotation is the identity).
     */
    public static boolean applyDeckRelativeTurn(Entity self, float yawDelta, float pitchDelta) {
        Minecraft mc = Minecraft.getMinecraft();
        if (self != mc.player || !ShipFrameCamera.shipCamActive
                || !ShipFrameTravel.isResolving(self)) {
            return false;
        }
        double roll = Math.toRadians(ShipFrameCamera.shipCamRoll);
        float c = (float) Math.cos(roll);
        float s = (float) Math.sin(roll);
        float rYaw = yawDelta * c - pitchDelta * s;
        float rPitch = pitchDelta * c + yawDelta * s;
        applyTurn(self, rYaw, rPitch);
        return true;
    }

    /** Vanilla {@code Entity.turn}, applied to the rotated deltas. No riding branch: a walking crew
     *  member is not a passenger, so {@code ridingEntity.applyOrientationToEntity} never applies. */
    private static void applyTurn(Entity self, float yawDelta, float pitchDelta) {
        float oldYaw = self.rotationYaw;
        float oldPitch = self.rotationPitch;
        self.rotationYaw = (float) (self.rotationYaw + (double) yawDelta * 0.15D);
        self.rotationPitch = (float) (self.rotationPitch - (double) pitchDelta * 0.15D);
        self.rotationPitch = MathHelper.clamp(self.rotationPitch, -90.0F, 90.0F);
        self.prevRotationPitch += self.rotationPitch - oldPitch;
        self.prevRotationYaw += self.rotationYaw - oldYaw;
    }
}
