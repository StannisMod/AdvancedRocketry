package zmaster587.advancedRocketry.mixin;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zmaster587.advancedRocketry.client.KeyBindings;

/**
 * Free Flight camera lock vs the vanilla riding echo (TASK-46 D1).
 *
 * While a player rides an entity, the server answers every client position
 * report with an SPacketPlayerPosLook carrying the rotation the client sent
 * ~1 RTT earlier. With the camera hard-locked to a turning craft this echo
 * yanks the view back by (turn rate × latency) — an 6–18° per-frame hiccup.
 * After the vanilla handler has applied the packet (and queued its confirm),
 * re-pin the camera to the craft. All FF gating lives in
 * {@link KeyBindings#repinCameraAfterTeleport()}; outside Free Flight this is
 * a no-op, and the netty-thread early-return invocation is filtered there too.
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerFFCameraRepin {

    @Inject(method = "handlePlayerPosLook", at = @At("HEAD"))
    private void advancedrocketry$captureMouseBeforeTeleport(SPacketPlayerPosLook packet, CallbackInfo ci) {
        // The echo overwrites the player rotation fields the pending mouse
        // delta lives in — capture it before the vanilla handler runs.
        KeyBindings.captureMouseBeforeTeleport();
    }

    @Inject(method = "handlePlayerPosLook", at = @At("RETURN"))
    private void advancedrocketry$repinFreeFlightCamera(SPacketPlayerPosLook packet, CallbackInfo ci) {
        KeyBindings.repinCameraAfterTeleport();
    }
}
