package zmaster587.advancedRocketry.mixin;

import java.util.LinkedHashSet;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.block_relocation.BlockFinder;
import org.valkyrienskies.mod.common.ships.block_relocation.SpatialDetector;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

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

    /** VS: UUID → loaded ship. A ship present here is already loaded. */
    @Shadow @Final private Map loadedShips;

    /** VS: UUIDs queued to load next physics tick. */
    @Shadow @Final private LinkedHashSet loadQueue;

    /** VS: (anchor, ShipData, finderType) triples queued to SPAWN next physics tick. */
    @Shadow @Final private LinkedHashSet spawnQueue;

    /** VS: the world this manager serves — used to read the queryable registry for diagnostics. */
    @Shadow @Final private WorldServer world;

    /**
     * Drop already-loaded ships from the load queue before VS's loop asserts on them,
     * turning the "already loaded" double-load crash into a no-op.
     */
    @Inject(method = "loadAndUnloadShips", at = @At("HEAD"))
    private void ar$dropAlreadyLoadedFromLoadQueue(CallbackInfo ci) {
        loadQueue.removeIf(uuid -> loadedShips.containsKey(uuid));
    }

    /**
     * DIAGNOSTIC (ledger #60), read-only: record how many spawns VS is about to process this tick.
     * A queued+named tier-2 ship that never enters the registry could be (a) never processed here,
     * (b) processed but dropped before addShip, or (c) added then destroyed — these three are
     * indistinguishable from outside the JVM. This notes (a) vs the rest.
     */
    @Inject(method = "spawnNewShips", at = @At("HEAD"), require = 0)
    private void ar$noteSpawnEntry(CallbackInfo ci) {
        VSIntegration.noteSpawnEntry(spawnQueue.size());
    }

    /**
     * DIAGNOSTIC (ledger #60), read-only: at spawn processing's end, sample the queryable registry.
     * If this ever reads >=1 for a ship the per-command poll later sees as 0, the ship registered
     * then was destroyed (the next tick's destroy sweep); if it stays 0 while spawnNewShipsRuns>0,
     * the spawn was processed but addShip was never reached.
     */
    @Inject(method = "spawnNewShips", at = @At("RETURN"), require = 0)
    private void ar$noteSpawnResult(CallbackInfo ci) {
        VSIntegration.noteSpawnReturn();
        VSIntegration.noteQueryableCount(ValkyrienUtils.getQueryableData(world).getShips().size());
    }

    /**
     * DIAGNOSTIC (ledger #60), behaviour-preserving: wrap the flood detector build so its result is
     * observable. Returns the SAME detector VS would have built (no change), after recording the
     * flood block count and the bedrock flag — the two inputs to VS's "Ship too big or bedrock
     * detected!" abort gate, which prints to System.err (not forwarded by the harness). This reads
     * WHY a queued ship is dropped: a huge foundSet = the flood escaped the craft into terrain; a
     * true cleanHouse = it reached bedrock; ~craft-size + false = the gate did not fire.
     */
    @Redirect(method = "spawnNewShips",
            at = @At(value = "INVOKE",
                    target = "Lorg/valkyrienskies/mod/common/ships/block_relocation/BlockFinder;"
                            + "getBlockFinderFor(Lorg/valkyrienskies/mod/common/ships/block_relocation/BlockFinder$BlockFinderType;"
                            + "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/World;IZ)"
                            + "Lorg/valkyrienskies/mod/common/ships/block_relocation/SpatialDetector;"),
            require = 0)
    private SpatialDetector ar$recordFloodResult(BlockFinder.BlockFinderType type, BlockPos pos,
                                                 World floodWorld, int maxSize, boolean corners) {
        SpatialDetector detector = BlockFinder.getBlockFinderFor(type, pos, floodWorld, maxSize, corners);
        if (detector != null) {
            VSIntegration.noteDetector(detector.foundSet.size(), detector.cleanHouse, ar$blacklistSize());
            if (detector.foundSet.size() > 500) {
                ar$recordFloodShape(detector, pos, floodWorld);
            }
        }
        return detector;
    }

    /** For an ESCAPED flood: compute the found-set bbox and sample the block at the corner farthest
     *  from the anchor, so the escape direction and the block type it floods through are named. */
    private static void ar$recordFloodShape(SpatialDetector detector, BlockPos anchor, World floodWorld) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos farthest = anchor;
        double bestD = -1;
        for (BlockPos p : detector.getBlockPosArrayList()) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
            double d = p.distanceSq(anchor);
            if (d > bestD) { bestD = d; farthest = p; }
        }
        net.minecraft.block.Block far = floodWorld.getBlockState(farthest).getBlock();
        VSIntegration.noteFloodShape("bbox=[" + minX + ".." + maxX + "," + minY + ".." + maxY + ","
                + minZ + ".." + maxZ + "] anchor=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " farthest=" + farthest.getX() + "," + farthest.getY() + "," + farthest.getZ()
                + "(" + far.getRegistryName() + ")");
    }

    /** VS's ShipSpawnDetector.blacklist is a private static Set rebuilt non-atomically by
     *  syncWithConfig (clear + repopulate). Read its size at flood time to catch it mid-rebuild. */
    private static int ar$blacklistSize() {
        try {
            java.lang.reflect.Field f = Class.forName(
                    "org.valkyrienskies.mod.common.ships.block_relocation.ShipSpawnDetector")
                    .getDeclaredField("blacklist");
            f.setAccessible(true);
            Object set = f.get(null);
            return set instanceof java.util.Collection ? ((java.util.Collection<?>) set).size() : -2;
        } catch (Throwable t) {
            return -3;
        }
    }
}
