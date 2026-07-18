package zmaster587.advancedRocketry.mixin;

import java.util.LinkedHashSet;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;

/**
 * Guards Valkyrien Skies' ship-load loop against the "already loaded" double-load crash.
 *
 * <p>VS's {@code loadAndUnloadShips} iterates {@code loadQueue} and, for each UUID, throws
 * {@code IllegalStateException("Tried loading a ShipData that was already loaded?")} if that
 * ship is already in {@code loadedShips}. AR's tier-2 assembly spawns a ship in place
 * ({@code queueShipSpawn}), which loads it immediately; if a player is standing near the pad
 * when it spawns, VS's proximity loader also queues the very same ship for a load, so next
 * physics tick the load loop finds it already loaded and crashes the whole server thread.
 * The automated client e2e dodges this by keeping its observer far during spawn — a human who
 * builds and assembles in place cannot.</p>
 *
 * <p>Fix: at the head of the load loop, drop from {@code loadQueue} every ship that is already
 * loaded. This is exactly the pre-condition VS asserts on; enforcing it before the loop turns
 * the illegal double-load into a harmless no-op, changing nothing else about VS's behaviour
 * (a ship queued for load that is genuinely not loaded still loads normally).</p>
 *
 * <p>Applied ONLY when Valkyrien Skies is on the classpath (gated by {@link ARMixinPlugin});
 * without it the {@code WorldServerShipManager} target would not resolve. VS's own class and
 * field names are stable across dev and reobf (they are not vanilla-MC names), so no refmap
 * translation is involved.</p>
 */
// remap = false: the target is a Valkyrien Skies class whose names are identical in dev and
// reobf (not vanilla-MC names), so the mixin AP/runtime must NOT try to SRG-remap the target
// method or the shadowed fields — there is no obfuscation mapping for them.
@Mixin(value = WorldServerShipManager.class, remap = false)
public abstract class MixinWorldServerShipManager {

    /** VS: UUID &rarr; loaded ship. A ship present here is already loaded. */
    @Shadow @Final private Map loadedShips;

    /** VS: UUIDs queued to load next physics tick. */
    @Shadow @Final private LinkedHashSet loadQueue;

    /**
     * Drop already-loaded ships from the load queue before VS's loop asserts on them,
     * turning the "already loaded" double-load crash into a no-op.
     */
    @Inject(method = "loadAndUnloadShips", at = @At("HEAD"))
    private void ar$dropAlreadyLoadedFromLoadQueue(CallbackInfo ci) {
        loadQueue.removeIf(uuid -> loadedShips.containsKey(uuid));
    }
}
