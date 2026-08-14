package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.IFlightBackend;

/**
 * Soft-dependency gate for Valkyrien Skies.
 *
 * <p>AR depends on VS <em>optionally</em>: we compile against the VS API
 * ({@code compileOnly}) but never bundle or require it. The whole true-spaceship
 * feature lights up only when the user also installs VS; without VS, AR must boot
 * and behave exactly as before.</p>
 *
 * <p><b>Boundary rule — do not break:</b> this class MUST NOT import or reference
 * any {@code org.valkyrienskies.*} type, so it is always safe for the JVM to
 * load. Every VS-touching call goes through {@link VSBridge}, which is reached
 * only behind {@link #isAvailable()} — so a VS-importing class is never loaded on
 * an AR install without VS, and there is no {@code NoClassDefFoundError}. The
 * unit test {@code VSIntegrationTest} pins this contract. AR compiles against VS
 * but never requires it (a soft, optional dependency).</p>
 */
public final class VSIntegration {

    /** Valkyrien Skies Core mod id (the 1.12.2 line). */
    public static final String MODID = "valkyrienskies";

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/vs");

    private static Boolean available;

    private VSIntegration() {}

    /**
     * Whether Valkyrien Skies is present. VS is vendored into Advanced Rocketry — compiled into
     * this jar rather than loaded as a separate mod — so its presence is a classpath fact, not a
     * modid registration ({@code Loader.isModLoaded("valkyrienskies")} would now be false). Probe a
     * VS core class instead; any failure (e.g. a stripped classpath) is treated as "VS absent"
     * rather than propagating. Cached after the first query.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            try {
                Class.forName("org.valkyrienskies.mod.common.ValkyrienSkiesMod", false,
                        VSIntegration.class.getClassLoader());
                cached = Boolean.TRUE;
            } catch (Throwable t) {
                cached = Boolean.FALSE;
            }
            available = cached;
        }
        return cached;
    }

    /**
     * Initialise the VS integration. A safe no-op when VS is absent. Call once
     * during AR init.
     */
    public static void init() {
        if (!isAvailable()) {
            LOGGER.info("Valkyrien Skies not present — true-spaceship features disabled.");
            return;
        }
        // Only here, behind the gate, do we touch a VS-importing class.
        VSBridge.onValkyrienSkiesPresent(LOGGER);
    }

    /**
     * Assemble the structure anchored at {@code anchorPos} into a movable ship.
     * A safe no-op when Valkyrien Skies is absent. Only vanilla/AR types appear in
     * this signature — every VS-importing call stays inside {@link VSBridge}, which
     * is reached only past the {@link #isAvailable()} gate, so no VS class is
     * loaded on an AR install without VS.
     */
    public static java.util.UUID assembleTier2Ship(World world, BlockPos anchorPos) {
        return assembleTier2Ship(world, anchorPos, null);
    }

    /**
     * The same, KEEPING the identity {@code keepUuid} the caller already holds for this ship, so a
     * craft that is cut out of one world and re-assembled in another stays the same ship to every
     * lookup instead of becoming a stranger that has to be found by position. {@code null} mints a
     * fresh identity, which is what a new build wants.
     *
     * <p>The identity is kept only when nothing live holds it in {@code world}; this ship's own
     * blockless remnant is adopted, a live ship is refused with a loud log and the assembly falls
     * back to a fresh identity. The returned uuid is the one the ship actually got, which is not
     * necessarily the one that was asked for.</p>
     */
    public static java.util.UUID assembleTier2Ship(World world, BlockPos anchorPos,
                                                   java.util.UUID keepUuid) {
        return assembleTier2Ship(world, anchorPos, keepUuid, null);
    }

    /**
     * The same, also carrying the craft's DURABLE name onto the record it creates. See
     * {@link #shipUuidOfDurableId} for what that name is for; {@code null} leaves the ship unnamed,
     * which is what a genuinely new build wants until its flight computer names it.
     */
    public static java.util.UUID assembleTier2Ship(World world, BlockPos anchorPos,
                                                   java.util.UUID keepUuid,
                                                   java.util.UUID keepDurableId) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.assembleTier2Ship(world, anchorPos, LOGGER, keepUuid, keepDurableId);
    }

    /**
     * The subspace shipyard box of the ship NAMED by {@code shipUuid}, or {@code null} when the
     * physics mod is absent or this world holds no such ship.
     *
     * <p>Prefer this over {@link #shipyardBoundsAt} wherever the caller KNOWS which ship it means.
     * The position-keyed form answers for whatever craft is nearest — with no distance bound — so in
     * a world holding more than one ship it can hand back a stranger's shipyard, and every scan
     * built on that box then searches the wrong craft.</p>
     */
    public static AxisAlignedBB shipyardBoundsOf(World world, java.util.UUID shipUuid) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipyardBoundsOf(world, shipUuid);
    }

    /**
     * RIGID-TELEPORT the ship NAMED by {@code shipUuid} to {@code (x,y,z)} — the identity-keyed twin
     * of {@link #teleportShipTo}. {@code false} when the physics mod is absent or no such ship is
     * registered here.
     */
    public static boolean teleportShipToByUuid(World world, java.util.UUID shipUuid,
                                               double x, double y, double z) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.teleportShipToByUuid(world, shipUuid, x, y, z);
    }

    /** UNPARK (re-enable physics on) the ship NAMED by {@code shipUuid}. */
    public static boolean unparkShip(World world, java.util.UUID shipUuid) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.unparkShip(world, shipUuid);
    }

    /**
     * One line naming the ship a POSITION lookup resolves to at {@code (x,y,z)} — uuid, name, its
     * pose and its shipyard — or {@code "none"}. Diagnostics only: a give-up report that prints a
     * shipyard box without saying WHOSE it is cannot distinguish "the ship is broken" from "we asked
     * about the wrong ship", and that distinction is the whole finding.
     */
    public static String describeShipAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return "vs-absent";
        }
        return VSBridge.describeNearestShip(world, x, y, z);
    }

    /**
     * The physics mod's hard ceiling for ship altitude (world Y), or
     * {@code Double.POSITIVE_INFINITY} when the physics mod is absent (nothing clamps, so nothing
     * caps a trigger line). Any gate that fires on "the ship climbed past altitude H" must derive
     * its H BELOW this value: the clamp is applied every physics step, so a trigger line at or
     * above it is physically unreachable and the gate silently never fires.
     */
    public static double shipYPositionMaximum() {
        if (!isAvailable()) {
            return Double.POSITIVE_INFINITY;
        }
        return VSBridge.shipYPositionMaximum();
    }

    /**
     * Raise the physics mod's ship altitude ceiling to at least {@code required} (no-op when the
     * physics mod is absent, or when the configured/current value is already higher). Called once
     * at space-subsystem registration so every slot cell's pose band is flyable from the first
     * tick - see {@link VSBridge#raiseShipCeilingTo} for why this must be deterministic rather
     * than teleport-ratcheted.
     */
    public static void raiseShipCeilingTo(double required) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.raiseShipCeilingTo(required, LOGGER);
    }

    /**
     * The subspace shipyard bounding box (world coordinates) of the loaded VS ship whose world BB
     * contains {@code (x,y,z)}, or {@code null} when VS is absent or no ship is there. A ship's blocks
     * live in this far-off shipyard region, not at the rendered position — the per-ship "crossing"
     * snapshots THIS box. Only vanilla/AR types appear in the signature.
     */
    public static AxisAlignedBB shipyardBoundsAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipyardBoundsAt(world, x, y, z);
    }

    /**
     * PARK the VS ship whose world BB contains {@code (x,y,z)}: disable its physics so it holds position
     * (used while a ship is in transit — {@code ShipTransit} advances its coordinate logically, not by
     * physically flying). Returns false when VS is absent or no ship is there. A safe no-op when VS absent.
     */
    public static boolean parkShipAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.parkShipAt(world, x, y, z);
    }

    /** UNPARK (re-enable physics on) the VS ship at {@code (x,y,z)}. See {@link #parkShipAt}. */
    public static boolean unparkShipAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.unparkShipAt(world, x, y, z);
    }

    /**
     * RIGID-TELEPORT the VS ship nearest to {@code (x,y,z)} to {@code (dstX,dstY,dstZ)}: the world-frame
     * pose moves (rotation kept, VS Y-limits widened as needed), the subspace blocks stay put. Entities
     * are not capped by the 256 build height, so extreme-Y poses are legal — this is the realization
     * lever for honest galactic local-Y and the arrange step of the extreme-coordinate spikes. Park the
     * ship first ({@link #parkShipAt}), teleport, then unpark. Safe no-op (false) when VS is absent.
     */
    public static boolean teleportShipTo(World world, double x, double y, double z,
                                         double dstX, double dstY, double dstZ) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.teleportShipTo(world, x, y, z, dstX, dstY, dstZ);
    }

    /**
     * Result of a {@link #crossShip} per-ship crossing: the destination anchor block the re-assembly was
     * seeded on ({@code null} = the crossing failed and no ship was created), and the ship's actual Y band
     * in the source shipyard (diagnostics).
     */
    public static final class CrossResult {
        /** The destination anchor the ship was re-assembled on, or {@code null} if the crossing failed. */
        public final BlockPos anchor;
        /**
         * The DESTINATION ship's own identity, minted when the re-assembly was queued, or
         * {@code null} if the crossing failed. Everything the settle does afterwards — the pose
         * teleport, the shipyard the re-seat scans, the unpark — must be keyed on this and not on a
         * position: the destination may already hold other ships, and a nearest-ship lookup at the
         * arrival point will happily answer for one of them.
         */
        public final java.util.UUID shipUuid;
        public final int minShipY;
        public final int maxShipY;

        CrossResult(BlockPos anchor, java.util.UUID shipUuid, int minShipY, int maxShipY) {
            this.anchor = anchor;
            this.shipUuid = shipUuid;
            this.minShipY = minShipY;
            this.maxShipY = maxShipY;
        }

        /** {@code true} iff a ship was re-assembled at the destination. */
        public boolean ok() {
            return anchor != null;
        }
    }

    /**
     * The per-ship "crossing": move ONE VS ship's blocks + TEs from a source world region
     * into a destination world (which may differ — origin cell &harr; hyperspace &harr; target cell), and
     * re-assemble it there. This is the recipe proven by {@code VSShipCrossingSpikeTest}, factored out so
     * production transit and the {@code /artest vs ship-repack} probe share one code path:
     * <ol>
     *   <li>find the ship's subspace shipyard bounds (XZ claim, full column) at the source;</li>
     *   <li>scan the shipyard for the ship's actual Y band (the full 256 column would clip on paste) —
     *       the region is void but for this ship, so any non-air block is a ship block;</li>
     *   <li>snapshot a TIGHT box of its blocks/TEs by CUTTING them, and paste it into clear sky at the
     *       destination (so the flood-fill grabs only the ship, never the destination terrain);</li>
     *   <li>anchor by scanning the pasted sky band (no cut&rarr;paste offset math — claims span multiple
     *       chunks) and re-assemble the ship there.</li>
     * </ol>
     * Riders are NOT moved here (they differ per caller: same-world reposition vs. cross-world transfer);
     * the caller enumerates and moves them around this call. Returns a {@link CrossResult}; a failed
     * crossing leaves {@code anchor == null}. Requires the destination sky at {@code (dstX,dstY,dstZ)} to
     * be clear. A safe no-op ({@code anchor == null}) when VS is absent.
     */
    public static CrossResult crossShip(World srcWorld, double sx, double sy, double sz,
                                        World dstWorld, int dstX, int dstY, int dstZ) {
        return crossShip(srcWorld, sx, sy, sz, null, dstWorld, dstX, dstY, dstZ);
    }

    /**
     * The same crossing, naming the ship to cross by IDENTITY.
     *
     * <p>{@code srcShipUuid} decides which craft is cut. Given one, the shipyard comes from
     * {@link #shipyardBoundsOf} and the identity to re-assemble under is that uuid — no position is
     * consulted for either, and {@code (sx,sy,sz)} is kept only to say WHERE in the logs.</p>
     *
     * <p><b>Why the positional form is not enough.</b> It resolves the yard through
     * {@link #shipyardBoundsAt}, whose own contract is that it "answers for whatever craft is nearest
     * — with no distance bound", so on a world holding a second craft (or a blockless remnant of one)
     * it hands back a stranger's box. What the caller then sees is a shipyard that "holds no blocks",
     * because the ship whose blocks are really there has a different claim — and its jump silently
     * does not happen while the source sits untouched. Measured on the jump departure.</p>
     *
     * <p>{@code null} means "resolve by position", which the overload above passes: a caller that
     * genuinely has no identity yet is asking a different, weaker question and should say so here.</p>
     */
    public static CrossResult crossShip(World srcWorld, double sx, double sy, double sz,
                                        java.util.UUID srcShipUuid,
                                        World dstWorld, int dstX, int dstY, int dstZ) {
        // Four different ways this returns "no ship", each with its own cause and its own cost. They
        // used to be one silent null, so a caller could only report that a crossing failed - never
        // which half of it, and never that the ship had already been cut.
        if (!isAvailable()) {
            LOGGER.warn("[SPACE] crossShip: Valkyrien Skies absent - nothing crossed (src dim {})",
                    srcWorld == null ? "null" : srcWorld.provider.getDimension());
            return new CrossResult(null, null, 0, 0);
        }
        AxisAlignedBB yard = srcShipUuid != null
                ? shipyardBoundsOf(srcWorld, srcShipUuid) : shipyardBoundsAt(srcWorld, sx, sy, sz);
        if (yard == null) {
            LOGGER.warn("[SPACE] crossShip: {} in dim {} - nothing to cross, the source is untouched",
                    srcShipUuid != null
                            ? "ship " + srcShipUuid + " is not registered here"
                            : "no ship claims (" + sx + "," + sy + "," + sz + ")",
                    srcWorld.provider.getDimension());
            return new CrossResult(null, null, 0, 0);
        }
        int yMinX = (int) yard.minX, yMinZ = (int) yard.minZ;
        int yMaxX = (int) yard.maxX, yMaxZ = (int) yard.maxZ;
        // The shipyard region holds only this ship, so any non-air block in it is a ship block. Find the
        // ship's real Y band (the claim gives only XZ; the full-height column would clip on paste).
        int[] band = scanShipBlockYBand(srcWorld, yMinX, yMaxX, yMinZ, yMaxZ);
        if (band == null) {
            LOGGER.warn("[SPACE] crossShip: the subspace shipyard [{}..{}]x[{}..{}] of the ship at "
                            + "({},{},{}) in dim {} holds no blocks - nothing to cross, the source is "
                            + "untouched",
                    yMinX, yMaxX, yMinZ, yMaxZ, sx, sy, sz, srcWorld.provider.getDimension());
            return new CrossResult(null, null, 0, 0); // source shipyard empty
        }
        int minShipY = band[0], maxShipY = band[1];
        // The source ship is deliberately NOT deregistered before the cut, and the order is the whole
        // point. Valkyrien Skies maintains a ship's block set from a chunk hook that resolves the ship
        // THROUGH the per-world registry, so a ship taken out of that registry first is a ship whose
        // block set the cut below no longer updates: it never looks empty, VS's own destroy pass
        // therefore never fires for it, and its physics object is stranded in the loaded set for the
        // life of the world - one left behind per crossing, and the load/unload pass cannot see it
        // either because that one iterates the registry it was just removed from.
        // Cut while it is still registered and the accounting runs: the block set empties, the destroy
        // pass collects it on the next world tick and performs the deregistration itself. Its
        // copy-blocks-back step is guarded on the block set being non-empty, so nothing is resurrected.
        //
        // That pass walks the LOADED ships, though, so it never runs for a source nothing was holding
        // loaded - a crewless or offline departure. Name the ship before the cut and release it by hand
        // afterwards in exactly that case (below); after the cut it is registered but blockless, and a
        // position lookup can no longer tell it from any other ship in the world.
        //
        // This name is also the ship's IDENTITY, and the re-assembly at the destination keeps it (see
        // the assemble call below): the craft that lands is the same ship it was before the cut, so
        // nothing downstream has to re-find it by position. It is resolved by the SAME lookup that
        // chose the shipyard box being cut, so it can never name a different ship than the one this
        // crossing is moving - if that lookup is wrong the crossing is already moving the wrong craft,
        // and keeping the identity adds no risk of its own.
        // Named by the caller when it knows which ship it means; otherwise recovered from the same
        // point the yard above came from, so the two can never disagree with each other (they can
        // still both be about the wrong ship — that is what the identity-keyed form removes).
        java.util.UUID srcShipId = srcShipUuid != null
                ? srcShipUuid : VSBridge.queryableShipUuidAt(srcWorld, sx, sy, sz);
        // The ship's DURABLE name, read off the source record while it still exists, so it can be put
        // on the record that replaces it. Nothing else carries it across: the re-assembly below mints
        // a record whose name is empty, and the only other writer is the craft's own flight computer
        // on a tick - which a hull that arrives with nobody near it does not get. The name is then
        // gone for good, and every lookup keyed by it silently degrades to "whichever craft is
        // nearest" in a world that may hold several. Null propagates as null: a craft that was never
        // named crosses exactly as it did before.
        java.util.UUID srcDurableName = VSBridge.durableIdOf(srcWorld, srcShipId);
        // Cut a TIGHT box (not the 256-tall column) and paste into clear sky at dstY (above the
        // destination terrain), so FIND_ALL_BLOCKS grabs only the ship.
        AxisAlignedBB tight = new AxisAlignedBB(yMinX, minShipY, yMinZ, yMaxX, maxShipY + 1, yMaxZ);
        zmaster587.advancedRocketry.util.StorageChunk snap =
                zmaster587.advancedRocketry.util.StorageChunk.cutWorldBB(srcWorld, tight);
        // No-op whenever a physics object is still loaded for the source - there VS's destroy pass owns
        // the collection and taking the ship out of the registry here would be the very bug this order
        // exists to avoid.
        VSBridge.releaseShipIfNothingLoaded(srcWorld, srcShipId);
        // The cut took the seat BLOCKS; the dummies bound to them are entities and survive it. On a
        // crossing they must not: the ship is re-assembled in ANOTHER world and its riders are re-seated
        // there on fresh dummies, so a source-side dummy is a chair whose ship no longer exists - and
        // one that clears the flight computer's pilot input every tick if a rider is ever put back near
        // it. Deliberately NOT in the generic cut: an assembly relocation re-pastes the same seat and
        // must leave its pilot seated, which is exactly what BlockPilotSeat.breakBlock's
        // isRelocationInProgress guard buys. A crossing is the case where nothing comes back.
        //
        // Matched by the SEAT BINDING, not by position: a dummy is glued to its ship's world position,
        // which for a managed ship is nowhere near the subspace shipyard box, so an AABB query over the
        // cut box would find none of them.
        for (zmaster587.advancedRocketry.entity.EntityDummy dummy
                : srcWorld.getEntities(zmaster587.advancedRocketry.entity.EntityDummy.class,
                        d -> boundToCutBlocks(d == null ? null : d.getSeatPos(), tight))) {
            dummy.removePassengers();
            dummy.setDead();
        }
        // Force-load the destination paste footprint's chunks first: a freshly-materialized cell world
        // may not have them loaded, in which case setBlockState/isAirBlock see an unloaded (all-air)
        // region and the anchor scan below finds nothing.
        int dstCxMin = dstX >> 4, dstCxMax = (dstX + (yMaxX - yMinX)) >> 4;
        int dstCzMin = dstZ >> 4, dstCzMax = (dstZ + (yMaxZ - yMinZ)) >> 4;
        for (int cx = dstCxMin; cx <= dstCxMax; cx++) {
            for (int cz = dstCzMin; cz <= dstCzMax; cz++) {
                dstWorld.getChunkProvider().provideChunk(cx, cz);
            }
        }
        snap.pasteInWorld(dstWorld, dstX, dstY, dstZ);
        // The paste landed in clear sky, so the first non-air block in the footprint IS a ship block —
        // no offset arithmetic, no risk of anchoring on the destination's terrain.
        int width = yMaxX - yMinX, depth = yMaxZ - yMinZ, height = (maxShipY - minShipY) + 3;
        BlockPos anchor = null;
        outer:
        for (int ey = 0; ey < height; ey++) {
            for (int ex = 0; ex < width; ex++) {
                for (int ez = 0; ez < depth; ez++) {
                    BlockPos p = new BlockPos(dstX + ex, dstY + ey, dstZ + ez);
                    if (!dstWorld.isAirBlock(p)) {
                        anchor = p;
                        break outer;
                    }
                }
            }
        }
        java.util.UUID shipUuid = null;
        if (anchor != null) {
            // Re-assemble under the identity the ship crossed with. Same world as the source on a
            // same-world reposition, where this ship's own blockless remnant is what holds the
            // identity - it is adopted rather than collided with.
            shipUuid = assembleTier2Ship(dstWorld, anchor, srcShipId, srcDurableName);
        } else {
            // The only DESTRUCTIVE failure of the four: the source has already been cut by this point,
            // so the ship exists as loose blocks at the paste site and nowhere else. Logged at ERROR
            // with the exact box that was searched, because a silent null here reads downstream as
            // "the jump did not happen" while a ship has in fact been taken apart.
            LOGGER.error("[SPACE] crossShip: pasted the ship from dim {} into dim {} at ({},{},{}) but "
                            + "the anchor scan of the {}x{}x{} paste box found only air - the source was "
                            + "ALREADY cut, so the ship is now loose blocks at the paste site and is "
                            + "registered nowhere",
                    srcWorld.provider.getDimension(), dstWorld.provider.getDimension(),
                    dstX, dstY, dstZ, width, height, depth);
        }
        return new CrossResult(anchor, shipUuid, minShipY, maxShipY);
    }

    /**
     * Non-destructively snapshot the VS ship whose world BB contains {@code (x,y,z)} as a
     * {@code StorageChunk} NBT: the same subspace shipyard + Y-band scan {@link #crossShip} cuts, but via a
     * non-destructive {@code copyWorldBB} (the ship stays parked, unlike the {@code cutWorldBB} in a
     * crossing). The transit persistence re-cuts a parked hyperspace ship this way at each save point so an
     * in-flight jump survives a restart. Returns {@code null} when VS is absent or the shipyard is empty.
     */
    public static net.minecraft.nbt.NBTTagCompound snapshotShipAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        AxisAlignedBB yard = shipyardBoundsAt(world, x, y, z);
        if (yard == null) {
            return null;
        }
        int yMinX = (int) yard.minX, yMinZ = (int) yard.minZ;
        int yMaxX = (int) yard.maxX, yMaxZ = (int) yard.maxZ;
        // Scan (and force-load) the shipyard for the ship's actual Y band, then copy a TIGHT box - a
        // full-height column would carry 256 layers of void into the snapshot NBT.
        int[] band = scanShipBlockYBand(world, yMinX, yMaxX, yMinZ, yMaxZ);
        if (band == null) {
            return null; // shipyard empty (no ship there)
        }
        AxisAlignedBB tight = new AxisAlignedBB(yMinX, band[0], yMinZ, yMaxX, band[1] + 1, yMaxZ);
        zmaster587.advancedRocketry.util.StorageChunk snap =
                zmaster587.advancedRocketry.util.StorageChunk.copyWorldBB(world, tight);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        snap.writeToNBT(nbt);
        return nbt;
    }

    /**
     * Paste a {@code StorageChunk} snapshot NBT (from {@link #snapshotShipAt}) into clear sky at
     * {@code (dstX,dstY,dstZ)} in {@code dstWorld} and re-assemble it into a VS ship - the block half of a
     * RESTORED transit's arrival, which has no live source ship to {@link #crossShip}. Mirrors crossShip's
     * paste tail (force-load the footprint, paste, anchor on the first non-air block, assemble); keep the
     * two in step. Returns the ship's anchor and the identity it was assembled under, or {@code null}
     * when VS is absent or the snapshot is empty.
     *
     * <p>Unlike {@link #crossShip} this one has no ship to take an identity FROM - it builds a ship out
     * of stored blocks - so the identity it returns is always a fresh one, and its caller must adopt it
     * rather than keep whatever the ship was called before the restart.</p>
     */
    public static CrossResult pasteAndAssemble(World dstWorld, net.minecraft.nbt.NBTTagCompound snapshot,
                                               int dstX, int dstY, int dstZ) {
        if (!isAvailable() || snapshot == null) {
            return new CrossResult(null, null, 0, 0);
        }
        zmaster587.advancedRocketry.util.StorageChunk snap =
                new zmaster587.advancedRocketry.util.StorageChunk();
        snap.readFromNBT(snapshot);
        int width = snap.getSizeX(), depth = snap.getSizeZ(), height = snap.getSizeY() + 2;
        if (width <= 0 || snap.getSizeY() <= 0 || depth <= 0) {
            return new CrossResult(null, null, 0, 0); // empty snapshot
        }
        // Force-load the destination paste footprint's chunks first (a freshly-materialized cell world may
        // not have them loaded, in which case setBlockState/isAirBlock see an unloaded all-air region).
        int dstCxMin = dstX >> 4, dstCxMax = (dstX + width) >> 4;
        int dstCzMin = dstZ >> 4, dstCzMax = (dstZ + depth) >> 4;
        for (int cx = dstCxMin; cx <= dstCxMax; cx++) {
            for (int cz = dstCzMin; cz <= dstCzMax; cz++) {
                dstWorld.getChunkProvider().provideChunk(cx, cz);
            }
        }
        snap.pasteInWorld(dstWorld, dstX, dstY, dstZ);
        // The paste landed in clear sky, so the first non-air block in the footprint IS a ship block.
        BlockPos anchor = null;
        outer:
        for (int ey = 0; ey < height; ey++) {
            for (int ex = 0; ex < width; ex++) {
                for (int ez = 0; ez < depth; ez++) {
                    BlockPos p = new BlockPos(dstX + ex, dstY + ey, dstZ + ez);
                    if (!dstWorld.isAirBlock(p)) {
                        anchor = p;
                        break outer;
                    }
                }
            }
        }
        java.util.UUID shipUuid = anchor == null ? null : assembleTier2Ship(dstWorld, anchor);
        return new CrossResult(anchor, shipUuid, dstY, dstY + snap.getSizeY());
    }

    /**
     * The block-space height (Y span, in blocks) of the VS ship whose world BB contains {@code (x,y,z)},
     * or {@code -1} when VS is absent, no ship is there, or its shipyard is empty. A descent paste-height
     * finder needs this BEFORE the crossing to keep the pasted ship under the destination build height
     * (a paste that clips at Y=256 breaks the {@code FIND_ALL_BLOCKS} flood-fill). Same subspace scan
     * {@link #crossShip} runs internally. A safe no-op ({@code -1}) when VS is absent.
     */
    public static int shipBlockHeight(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return -1;
        }
        AxisAlignedBB yard = shipyardBoundsAt(world, x, y, z);
        if (yard == null) {
            return -1;
        }
        int[] band = scanShipBlockYBand(world, (int) yard.minX, (int) yard.maxX,
                (int) yard.minZ, (int) yard.maxZ);
        return band == null ? -1 : (band[1] - band[0]) + 1;
    }

    /**
     * Scan the subspace shipyard XZ footprint over the full {@code 0..256} column for the ship's actual
     * Y band ({@code {minY, maxY}}), or {@code null} when the region holds no blocks. The shipyard holds
     * only this one ship, so any non-air block is a ship block. Shared by {@link #crossShip} (tight cut)
     * and {@link #shipBlockHeight}.
     */
    /**
     * A single subspace block position of the VS ship whose world BB contains {@code (x,y,z)}, or
     * {@code null} when VS is absent / no ship is there / its shipyard is empty. Located through the
     * queryable ship registry (headless-reliable, unlike a loaded-TE scan), so a test harness can hand
     * a ship-managing block to a controller that addresses a ship by one of its blocks.
     */
    public static net.minecraft.util.math.BlockPos shipBlockAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        AxisAlignedBB yard = shipyardBoundsAt(world, x, y, z);
        if (yard == null) {
            return null;
        }
        int minX = (int) yard.minX, maxX = (int) yard.maxX;
        int minZ = (int) yard.minZ, maxZ = (int) yard.maxZ;
        // Force-load the shipyard chunks first: a headless server holds the ship's physo loaded but its
        // far subspace chunks may not be chunk-loaded, so an un-loaded scan reads all-air.
        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
                for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                    ws.getChunkProvider().provideChunk(cx, cz);
                }
            }
        }
        for (int wx = minX; wx < maxX; wx++) {
            for (int wy = 0; wy < 256; wy++) {
                for (int wz = minZ; wz < maxZ; wz++) {
                    BlockPos p = new BlockPos(wx, wy, wz);
                    if (!world.isAirBlock(p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The SUBSPACE {@link BlockPos} of the {@code TileAdvancedFlightComputer} on the VS ship whose world BB
     * contains {@code (x,y,z)}, or {@code null} when VS is absent / no ship is there / it carries no flight
     * computer. Same queryable, force-loaded subspace scan as {@link #shipBlockAt}, but matched by tile type
     * instead of "first non-air". The transit depart path holds only a world-frame ship anchor (unlike entry
     * and descent, which run from the AFC tile itself and get its position for free), so it must recover the
     * AFC block - which {@code CrewTransfer.capture} filters the ship's seats against - by scanning the
     * shipyard. Force-loading the far subspace chunks first also makes a subsequent per-seat
     * {@code getTileEntity(seatPos)} resolve, so a depart-time crew capture works even though the nearby
     * pilot only chunk-loaded the ship's RENDER region, not its subspace shipyard.
     */
    public static BlockPos flightComputerAt(net.minecraft.world.WorldServer world,
            double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return flightComputerInYard(world, shipyardBoundsAt(world, x, y, z));
    }

    /**
     * The SUBSPACE {@link BlockPos} of the flight computer on the ship NAMED by {@code shipUuid} — the
     * identity-keyed twin of {@link #flightComputerAt}, and the one to reach for whenever the caller
     * knows which ship it means.
     *
     * <p>{@link #flightComputerAt} is a scan built on {@link #shipyardBoundsAt}, whose own contract
     * warns that the position-keyed box "answers for whatever craft is nearest — with no distance
     * bound", so in a world holding more than one ship that scan searches a stranger's craft and
     * happily returns a real, wrong flight computer. A caller that then writes to it gets a successful
     * call and no effect on the ship it meant.</p>
     */
    public static BlockPos flightComputerOf(net.minecraft.world.WorldServer world,
            java.util.UUID shipUuid) {
        if (!isAvailable() || shipUuid == null) {
            return null;
        }
        return flightComputerInYard(world, shipyardBoundsOf(world, shipUuid));
    }

    /** The flight-computer scan both resolvers share, over an already-chosen subspace shipyard box. */
    private static BlockPos flightComputerInYard(net.minecraft.world.WorldServer world,
            AxisAlignedBB yard) {
        if (yard == null) {
            return null;
        }
        int minX = (int) yard.minX, maxX = (int) yard.maxX;
        int minZ = (int) yard.minZ, maxZ = (int) yard.maxZ;
        // Force-load the shipyard chunks first (a headless server holds the physo loaded but may not
        // chunk-load its far subspace region, so an un-loaded scan finds no tile) - same reason as shipBlockAt.
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                world.getChunkProvider().provideChunk(cx, cz);
            }
        }
        for (int wx = minX; wx < maxX; wx++) {
            for (int wy = 0; wy < 256; wy++) {
                for (int wz = minZ; wz < maxZ; wz++) {
                    BlockPos p = new BlockPos(wx, wy, wz);
                    net.minecraft.tileentity.TileEntity te = world.getTileEntity(p);
                    if (te instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private static int[] scanShipBlockYBand(World world, int minX, int maxX, int minZ, int maxZ) {
        // Force-load the shipyard chunks first so the scan reads the ship's real blocks, not an
        // unloaded all-air region (a headless server may hold the physo loaded but not chunk-load its
        // far subspace shipyard). A no-op for chunks already loaded (e.g. an active planet-side ship).
        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
                for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                    ws.getChunkProvider().provideChunk(cx, cz);
                }
            }
        }
        int minShipY = Integer.MAX_VALUE, maxShipY = Integer.MIN_VALUE;
        for (int wx = minX; wx < maxX; wx++) {
            for (int wy = 0; wy < 256; wy++) {
                for (int wz = minZ; wz < maxZ; wz++) {
                    if (!world.isAirBlock(new BlockPos(wx, wy, wz))) {
                        if (wy < minShipY) minShipY = wy;
                        if (wy > maxShipY) maxShipY = wy;
                    }
                }
            }
        }
        return minShipY == Integer.MAX_VALUE ? null : new int[]{minShipY, maxShipY};
    }

    /**
     * TEST/HEADLESS: keep VS ships permanently loaded (the {@code permanentlyLoaded} loading setting) so
     * a player-less server test can observe a freshly assembled ship across probe calls instead of it
     * auto-unloading. A safe no-op when VS is absent.
     */
    public static void setShipsPermanentlyLoaded(boolean value) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.setShipsPermanentlyLoaded(value);
    }

    /**
     * Create a flight backend that drives the Valkyrien Skies ship anchored at
     * {@code anchorPos} as a velocity setpoint (model A), or {@code null} when VS is
     * absent. The return type is the AR-core {@link IFlightBackend}, so a caller in
     * AR core (e.g. the Advanced Flight Computer tile) never references a VS type —
     * the VS-importing {@code VSFlightBackend} is loaded only past this gate.
     */
    public static IFlightBackend createShipFlightBackend(World world, BlockPos anchorPos) {
        if (!isAvailable()) {
            return null;
        }
        return new VSFlightBackend(world, anchorPos);
    }

    /**
     * The body&rarr;world attitude of the Valkyrien Skies ship managing the block at
     * {@code pos}, or {@code null} when VS is absent or no ship manages it. Returns
     * the AR-core {@link FreeFlightPhysics.Quat} so a caller in AR core never sees a
     * VS type. Free Flight integrates the pilot's body rates over this each tick.
     */
    public static FreeFlightPhysics.Quat getShipAttitude(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.getShipAttitude(world, pos);
    }

    /**
     * Move a point or a direction between the world frame and the frame of the ship {@code entity}
     * is aboard. In the ship's own frame the deck is axis-aligned and "down" is plain {@code -Y}, so
     * an aboard entity's movement can be resolved there with ordinary rules and mapped back. Each
     * returns {@code null} when VS is absent or the entity is aboard no loaded ship, so callers fall
     * back to vanilla movement. Only AR-core/MC types cross the gate.
     */
    public static double[] toShipFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.toShipFrame(e, x, y, z);
    }

    /** Ship-frame point to world point. See {@link #toShipFrame}. */
    public static double[] toWorldFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.toWorldFrame(e, x, y, z);
    }

    /** World direction to ship-frame direction (rotation only). See {@link #toShipFrame}. */
    public static double[] rotateToShipFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.rotateToShipFrame(e, x, y, z);
    }

    /** Ship-frame direction to world direction (rotation only). See {@link #toShipFrame}. */
    public static double[] rotateToWorldFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.rotateToWorldFrame(e, x, y, z);
    }

    // ---- Anchored (by-ship-id) frame access. A capture episode resolves every transform through
    // the ship it was captured on (its ShipData UUID string), never by re-picking a ship from
    // world-AABB containment mid-episode. Each returns null when VS is absent or THAT ship is not
    // loaded, so callers release/fall back to vanilla. Only AR-core/MC types cross the gate.

    /** UUID string of the ship whose SUBSPACE claim manages {@code pos} (unambiguous — claims of
     *  distinct ships never overlap), or {@code null}. The anchor resolver for a seat-based seed. */
    public static String shipIdManagingBlock(World world, BlockPos pos) {
        return (!isAvailable() || world == null || pos == null)
                ? null : VSBridge.shipIdManagingBlock(world, pos);
    }

    /** UUID string of the ship whose subspace claim manages {@code pos} as the REGISTRY knows it —
     *  answered whether or not that ship is currently simulated. Use this for questions about a
     *  ship's IDENTITY; {@link #shipIdManagingBlock} answers about its live physics and is null for
     *  every ship nobody is standing near. */
    public static String registeredShipIdManagingBlock(World world, BlockPos pos) {
        return (!isAvailable() || world == null || pos == null)
                ? null : VSBridge.registeredShipIdManagingBlock(world, pos);
    }

    /** UUID strings of every loaded ship whose grown world AABB contains {@code (x,y,z)} — the
     *  first-contact candidate list (possibly empty; never null). */
    public static java.util.List<String> shipIdsAt(World world, double x, double y, double z) {
        return (!isAvailable() || world == null)
                ? java.util.Collections.<String>emptyList() : VSBridge.shipIdsAt(world, x, y, z);
    }

    /** World point to ship-frame point, for the anchored ship. See the anchored-access note. */
    public static double[] toShipFrameFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null) ? null : VSBridge.toShipFrameFor(world, shipId, x, y, z);
    }

    /** Ship-frame point to world point, for the anchored ship. */
    public static double[] toWorldFrameFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null) ? null : VSBridge.toWorldFrameFor(world, shipId, x, y, z);
    }

    /** Ship-frame point to world point through the ship's RENDER pose — where the renderer draws
     *  that point this frame, as opposed to where the game-tick transform places it. Client-side
     *  observable (null on a dedicated server or when the ship is not loaded). */
    public static double[] renderToWorldFrameFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null)
                ? null : VSBridge.renderToWorldFrameFor(world, shipId, x, y, z);
    }

    /** World direction to ship-frame direction (rotation only), for the anchored ship. */
    public static double[] rotateToShipFrameFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null) ? null : VSBridge.rotateToShipFrameFor(world, shipId, x, y, z);
    }

    /** Ship-frame direction to world direction (rotation only), for the anchored ship. */
    public static double[] rotateToWorldFrameFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null) ? null : VSBridge.rotateToWorldFrameFor(world, shipId, x, y, z);
    }

    /** {@link #shipVelocityAtPoint} for the anchored ship — the deck-carry widening of an anchored
     *  capture's external-move guard must come from ITS ship. */
    public static double[] shipVelocityAtPointFor(World world, String shipId, double x, double y, double z) {
        return (!isAvailable() || world == null)
                ? null : VSBridge.shipVelocityAtPointFor(world, shipId, x, y, z);
    }

    /** Clear the physics mod's own entity-to-ship association (its {@code EntityDraggable} drag
     *  anchor) for a body AR resolves ship-locally — the drag is a second mover that fights the
     *  ship-frame resolution from a stale anchor the suppressed collision injector can never
     *  refresh. Called every resolved tick; a no-op (false) when VS is absent, the entity is not
     *  draggable, or the association is already clear. */
    public static boolean suppressShipDrag(net.minecraft.entity.Entity entity) {
        return isAvailable() && entity != null && VSBridge.clearEntityShipAssociation(entity);
    }

    /** The anchored ship's stay region in SUBSPACE, grown by {@code margin} — the release-hysteresis
     *  bound for an aboard body (attitude-invariant; boundary at least {@code margin} from every hull
     *  block). Null when VS is absent or the ship is not loaded. */
    public static net.minecraft.util.math.AxisAlignedBB subspaceStayRegion(World world, String shipId, double margin) {
        return (!isAvailable() || world == null)
                ? null : VSBridge.subspaceStayRegion(world, shipId, margin);
    }

    /** How many blocks the ship's own data says it owns ({@code ShipData.blockPositions}), or -1
     *  when VS is absent / the ship is not loaded on this side. The authoritative assembled-block
     *  count — a fixture that should have N blocks but reports fewer lost them at assembly. */
    public static int shipBlockCount(World world, String shipId) {
        return (!isAvailable() || world == null) ? -1 : VSBridge.shipBlockCount(world, shipId);
    }

    /**
     * Read-only diagnostic of what Valkyrien Skies already knows about {@code entity}'s relationship
     * to a ship: its last-touched ship, whether VS counts it as standing on that ship
     * ({@code ticksPartOfGround}), the motion VS imparts to it, whether VS considers it mounted, and
     * its position mapped into the ship's subspace. Returns a plain JDK map, or {@code null} when VS
     * is absent or cannot be consulted. Used to decide how much of a ship-local movement frame VS
     * already supplies before AR builds its own. Only AR-core/MC types cross the gate.
     */
    public static java.util.Map<String, Object> getEntityShipMovementData(net.minecraft.entity.Entity entity) {
        if (!isAvailable() || entity == null) {
            return null;
        }
        return VSBridge.entityShipMovementData(entity);
    }

    /**
     * Read-only transform-consistency diagnostic for the ship {@code entity} is aboard: whether the VS
     * vector rotate (the MOVEMENT frame) and the attitude quaternion (the CAMERA/gravity frame) agree,
     * plus the position/rotation round-trip errors. A plain JDK map, or {@code null} when VS is absent or
     * the entity is aboard no loaded ship. Only AR-core/MC types cross the gate.
     */
    public static java.util.Map<String, Object> transformConsistency(net.minecraft.entity.Entity entity) {
        if (!isAvailable() || entity == null) {
            return null;
        }
        return VSBridge.transformConsistency(entity);
    }

    /**
     * The world-frame position {@code [x,y,z]} of the ship managing the block at {@code pos} (its
     * transform position), or {@code null} when VS is absent or no ship manages it. Managed-block
     * keyed like {@link #getShipAttitude} — on a shared server each flight computer reads its OWN
     * ship, never a neighbour's. The tier-2 entry ceiling check reads this each pilot tick. Only
     * AR-core/MC types cross the gate.
     */
    public static double[] getShipWorldPosition(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipWorldPosition(world, pos);
    }

    /**
     * The world-frame linear velocity {@code [x,y,z]} (blocks/second) of the ship managing the block
     * at {@code pos}, or {@code null} when VS is absent or no ship manages it. Used to capture the
     * live velocity as a Flight-Assist setpoint on re-enable. Only AR-core/MC types cross the gate.
     */
    public static double[] getShipVelocity(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipLinearVelocity(world, pos);
    }

    /**
     * The world-frame velocity {@code [x,y,z]} (blocks/second) of the ship AT {@code (x,y,z)} - its
     * linear velocity plus the tangential velocity of its rotation there - or {@code null} when VS is
     * absent or the point is aboard no loaded ship. How fast the DECK carries an aboard body at that
     * point; the ship-frame movement guard widens by one tick of it so a rotating deck is not read as a
     * teleport. Only AR-core/MC types cross the gate.
     */
    public static double[] shipVelocityAtPoint(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipVelocityAtPoint(world, x, y, z);
    }

    /**
     * The unit world-frame direction toward the floor of the loaded ship the point {@code (x,y,z)}
     * is aboard, or {@code null} when VS is absent or the point is aboard no ship. Lets AR apply
     * gravity toward a ship's deck (the ship's local down, rotated by its attitude) for entities
     * standing on it; on an upright ship this is {@code (0,-1,0)}, so gravity is unchanged. Only
     * AR-core/MC types cross the gate.
     */
    public static double[] shipDownDirectionFor(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipDownDirection(world, x, y, z);
    }

    /**
     * The body&rarr;world attitude of the loaded ship the point {@code (x,y,z)} is aboard, or
     * {@code null} when VS is absent or the point is aboard no ship. Located by containment, so it
     * answers for a crew member standing anywhere on the deck, not only for a block on the ship.
     * Resolves on both sides. Only AR-core/MC types cross the gate.
     */
    public static FreeFlightPhysics.Quat shipAttitudeAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        double[] q = VSBridge.shipAttitudeAt(world, x, y, z);
        return q == null ? null : new FreeFlightPhysics.Quat(q[0], q[1], q[2], q[3]);
    }

    /**
     * The body&rarr;world attitude of the ship {@code shipId}, or {@code null} when VS is absent or
     * that ship is not loaded on this side. Use this - not {@link #shipAttitudeAt} - whenever the
     * ship is already known by id: containment answers for whatever box a point falls inside, which
     * is a different question and a large air volume around the hull.
     */
    public static FreeFlightPhysics.Quat shipAttitudeForId(World world, String shipId) {
        if (!isAvailable() || shipId == null) {
            return null;
        }
        double[] q = VSBridge.shipAttitudeForId(world, shipId);
        return q == null ? null : new FreeFlightPhysics.Quat(q[0], q[1], q[2], q[3]);
    }

    /** The attitude of the ship {@code entity} is aboard, or {@code null}. See {@link #shipAttitudeAt}. */
    public static FreeFlightPhysics.Quat shipAttitudeFor(net.minecraft.entity.Entity entity) {
        if (entity == null || entity.world == null) {
            return null;
        }
        return shipAttitudeAt(entity.world, entity.posX, entity.posY, entity.posZ);
    }

    /**
     * The world-frame angular velocity {@code [x,y,z]} (rad/s) of the loaded ship nearest to
     * {@code (x,y,z)}, or {@code null} when VS is absent or no ship is loaded. Only AR-core/MC types
     * cross the gate.
     */
    public static double[] nearestShipAngularVelocity(World world, double x, double y, double z) {
        return nearestShipAngularVelocity(world, x, y, z, Double.POSITIVE_INFINITY);
    }

    /** Distance-bounded {@link #nearestShipAngularVelocity(World, double, double, double)}. */
    public static double[] nearestShipAngularVelocity(World world, double x, double y, double z,
                                                      double maxDist) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.nearestShipAngularVelocity(world, x, y, z, maxDist);
    }

    /**
     * The world position {@code [x, y, z]} of the pilot seat at ship-subspace {@code seatPos},
     * or {@code null} when VS is absent or no ship manages the seat. Lets a seated rider be glued
     * to its ship's live world location every tick (the seat block itself lives in a distant,
     * stationary shipyard subspace). Only AR-core/MC types cross the gate.
     */
    public static double[] getSeatWorldPosition(World world, BlockPos seatPos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.seatWorldPosition(world, seatPos);
    }

    /**
     * The world position {@code [x, y, z]} of the pilot seat at ship-subspace {@code seatPos} on a ship the
     * registry knows — <b>loaded or not</b> — or {@code null} when VS is absent or no registered ship owns
     * that block. Use this wherever the question is "where is this seat" rather than "is this rider still
     * on a live ship": a ship's loaded state is decided by player proximity and re-decided every tick, so a
     * step that has nobody near it yet (a crew re-seat on arrival carries the crew there itself) must not
     * be gated on it. See {@link #getSeatWorldPosition} for the liveness-sensitive variant.
     */
    public static double[] getRegisteredSeatWorldPosition(World world, BlockPos seatPos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.registeredSeatWorldPosition(world, seatPos);
    }

    /**
     * The world position {@code [x, y, z]} of the SUBSPACE point {@code (sx, sy, sz)} on the ship
     * that manages {@code managedBlock}, asked of the registry so it answers for an UNLOADED ship
     * too; {@code null} when VS is absent or no registered ship owns that block.
     *
     * <p>The continuous counterpart of {@link #getRegisteredSeatWorldPosition}: where that one puts
     * a rider on a seat BLOCK, this one puts a crew member on his feet back at the deck point he
     * was standing on. Both are keyed on a block the ship owns, so the frame the caller must supply
     * is the same one — a managed SUBSPACE block, never a world anchor.</p>
     */
    public static double[] getRegisteredSubspacePointWorldPosition(World world, BlockPos managedBlock,
                                                                   double sx, double sy, double sz) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.registeredSubspacePointToWorld(world, managedBlock, sx, sy, sz);
    }

    /**
     * Every ship the registry knows in {@code world}, as uuid -> world position; empty when the
     * physics mod is absent. Registry-keyed, so it answers for ships nobody is near.
     */
    public static java.util.Map<java.util.UUID, double[]> registeredShipPoses(World world) {
        if (!isAvailable() || world == null) {
            return java.util.Collections.emptyMap();
        }
        return VSBridge.registeredShipPoses(world);
    }

    /**
     * The physics mod's ship uuid for the craft carrying AR's DURABLE ship id {@code durableId}, or
     * {@code null} when {@code world} holds no such craft.
     *
     * <p>This is the translation between the two identities a tier-2 ship has, and the reason a
     * caller that KNOWS which ship it means no longer has to ask which one is nearest a point. The
     * durable id is minted by the ship's flight computer and persisted because it outlives a
     * re-assembly; everything in the physics mod is keyed by its own uuid. Indexed on that side, so
     * this costs one probe.</p>
     *
     * <p>Answers {@code null} for a craft that was never bound — which is every craft AR does not own,
     * and any of its own whose binding has not happened yet. A caller must treat that as "could not
     * establish", never as "not this ship".</p>
     */
    public static java.util.UUID shipUuidOfDurableId(World world, String durableId) {
        if (!isAvailable() || world == null || durableId == null) {
            return null;
        }
        try {
            return VSBridge.shipUuidOfDurableId(world, java.util.UUID.fromString(durableId));
        } catch (IllegalArgumentException notAnIdentity) {
            return null; // a synthetic id names no ship; the caller falls back as before
        }
    }

    /**
     * The durable id carried on the RECORD of the craft {@code vsShipUuid}, or {@code null}. The same
     * question {@link #shipUuidOfDurableId} answers from the other end, and reading both separates a
     * craft that was never bound from a binding the lookup cannot find.
     */
    public static java.util.UUID durableIdOfShip(World world, java.util.UUID vsShipUuid) {
        return (!isAvailable() || world == null || vsShipUuid == null)
                ? null : VSBridge.durableIdOf(world, vsShipUuid);
    }

    /**
     * Bind AR's durable ship id {@code durableId} to the craft {@code vsShipUuid}, so
     * {@link #shipUuidOfDurableId} can translate between them.
     *
     * <p>Called wherever a tier-2 craft becomes (or becomes again) the ship a durable id names: at
     * assembly, and after a crossing re-assembles it at the far end. Cheap and idempotent, so binding
     * again on a ship that already carries it costs nothing.</p>
     *
     * @return {@code true} when the ship was found and bound
     */
    public static boolean bindDurableShipId(World world, java.util.UUID vsShipUuid,
                                            java.util.UUID durableId) {
        return isAvailable() && world != null && vsShipUuid != null
                && VSBridge.bindDurableId(world, vsShipUuid, durableId);
    }

    /**
     * The uuid of the registered ship parked within {@code HyperspaceTiles.SPACING_BLOCKS / 4} of
     * {@code (x,y,z)}, or {@code null}. For a PARKED ship, whose transform does not move and whose
     * neighbours are a lane apart; never for a lookup where "nearest" could mean anything.
     */
    public static java.util.UUID shipUuidAt(World world, double x, double y, double z) {
        if (!isAvailable() || world == null) {
            return null;
        }
        return VSBridge.shipUuidNear(world, x, y, z,
                zmaster587.advancedRocketry.space.HyperspaceTiles.SPACING_BLOCKS / 4.0);
    }

    /**
     * Deregister {@code uuid} in {@code world} when nothing holds it loaded — the disposal of a hull
     * no record claims. {@code false} when the physics mod is absent or that ship is loaded.
     */
    public static boolean releaseShipIfNothingLoaded(World world, java.util.UUID uuid) {
        return isAvailable() && world != null && VSBridge.releaseShipIfNothingLoaded(world, uuid);
    }

    /**
     * Whether Valkyrien Skies ship support (its per-world ship manager) is attached to
     * {@code world}. Used by the space slot-pool spike to confirm VS lights up on a
     * dynamically-created pool world, not just the vanilla/AR dimensions. {@code false} when VS
     * is absent or its manager is not present. Only AR-core/MC types cross the gate.
     */
    public static boolean hasShipSupport(World world) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.hasShipSupport(world);
    }

    /**
     * Enable physics on the ship managing the block at {@code pos} (a safe no-op when VS is
     * absent or no ship manages it). Only AR-core/MC types cross the gate.
     */
    public static void ensureShipPhysicsEnabled(World world, BlockPos pos) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.ensureShipPhysicsEnabled(world, pos);
    }

    /** Number of Valkyrien Skies ships loaded in {@code world}, or -1 when VS is absent. */
    public static int loadedShipCount(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.loadedShipCount(world);
    }

    // ---- Spawn diagnostics (ledger #60). Ungated statics written by MixinWorldServerShipManager
    // from inside VS's own spawnNewShips, so an e2e can read WHERE a queued+named tier-2 ship dies:
    // never-processed vs processed-but-never-registered vs registered-then-destroyed. Harness child
    // JVMs have no test mode, so these must be ungated (isTestMode gates log lines only). --------
    /** Times spawnNewShips ran with a non-empty spawnQueue since the last reset. */
    public static volatile long spawnNewShipsRuns = 0L;
    /** Times spawnNewShips RETURNED NORMALLY since reset. runs>returns ⇒ it exited by THROW (one of
     *  VS's pre-addShip athrows: "already loaded"@98, "Incorrect block copy!"@568/1012). */
    public static volatile long spawnNewShipsReturns = 0L;
    /** spawnQueue size seen at the last spawnNewShips entry (how many spawns VS tried to process). */
    public static volatile int lastSpawnQueueSize = 0;
    /** Max queryable-ship count observed at spawnNewShips RETURN since reset. >=1 means the ship DID
     *  enter the registry at least momentarily (register-then-destroy); 0 with runs>0 means the
     *  spawn was processed but addShip was never reached (gate skip or a pre-addShip throw). */
    public static volatile int spawnDiagMaxShips = 0;

    /** Reset the spawn diagnostics (call before an assembly under test). */
    public static void resetSpawnDiag() {
        spawnNewShipsRuns = 0L;
        spawnNewShipsReturns = 0L;
        lastSpawnQueueSize = 0;
        spawnDiagMaxShips = 0;
    }

    /** Called by the mixin at spawnNewShips NORMAL return (never on a throw). */
    public static void noteSpawnReturn() {
        spawnNewShipsReturns++;
    }

    /** Called by the mixin at spawnNewShips entry with the current spawnQueue size. */
    public static void noteSpawnEntry(int queueSize) {
        if (queueSize > 0) {
            spawnNewShipsRuns++;
            lastSpawnQueueSize = queueSize;
        }
    }

    /** Called by the mixin at spawnNewShips return with the current queryable-ship count. */
    public static void noteQueryableCount(int count) {
        if (count > spawnDiagMaxShips) {
            spawnDiagMaxShips = count;
        }
    }

    /** foundSet size of the flood at the last spawn attempt (the block count VS's abort gate tests
     *  against maxDetectedShipSize=15000): ~craft size on a healthy spawn, huge if the flood escaped
     *  into terrain. */
    public static volatile int lastFoundSetSize = -1;
    /** cleanHouse at the last spawn attempt: true iff the flood reached bedrock (the other abort leg). */
    public static volatile boolean lastCleanHouse = false;

    /** Size of VS's ShipSpawnDetector spawn-blacklist Set at the last flood. 21 = fully populated
     *  (air+terrain excluded); 0/small = the blacklist was mid-rebuild (syncWithConfig clears then
     *  repopulates non-atomically), so AIR was floodable and the flood escaped. -1 = unread. */
    public static volatile int lastBlacklistSize = -1;

    /** WHERE the flood went: bbox of the found set + the block sampled at its farthest corner from
     *  the anchor. On an escaped flood this names the escape direction and what it floods through. */
    public static volatile String lastFloodShape = "";

    /** Called by the mixin right after the flood detector is built, with the flood result. */
    public static void noteDetector(int foundSetSize, boolean cleanHouse, int blacklistSize) {
        lastFoundSetSize = foundSetSize;
        lastCleanHouse = cleanHouse;
        lastBlacklistSize = blacklistSize;
    }

    /** Called by the mixin (huge floods only) with the found-set geometry, pre-formatted by the
     *  bridge (which may touch VS/MC types); this class only stores the string. */
    public static void noteFloodShape(String shape) {
        lastFloodShape = shape;
    }

    /** Total ships in {@code world} loaded or not (queryable registry), or -1 when VS absent. */
    public static int queryableShipCount(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.queryableShipCount(world);
    }

    /**
     * DIAGNOSTIC: identity of the ship registry {@code world} answers with, matching the hex the
     * physics mod prints when it serialises that world. {@code "?"} when VS is absent.
     */
    public static String queryableIdentity(World world) {
        if (!isAvailable()) {
            return "?";
        }
        return VSBridge.queryableIdentity(world);
    }

    /**
     * DIAGNOSTIC: the transform positions of every queryable ship in {@code world}, as
     * {@code "x,y,z;x,y,z"}. Asks about no point, so a caller can find out WHERE a ship is rather than
     * only whether one answers for a place it guessed. Empty when VS is absent or holds no ships.
     */
    public static String queryableShipPositions(World world) {
        if (!isAvailable()) {
            return "";
        }
        return VSBridge.queryableShipPositions(world);
    }

    /**
     * Force every known ship in {@code world} loaded and physics-enabled (headless/no-observer
     * equivalent of a nearby player loading it); returns the number requested, or -1 when VS
     * is absent.
     */
    public static int loadAllShips(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.loadAllShips(world);
    }

    /**
     * State of the loaded ship nearest to {@code (x,y,z)} as
     * {@code [posX,posY,posZ, qw,qx,qy,qz, velX,velY,velZ]}, or {@code null} when VS is
     * absent or no ship is loaded. Only AR-core/MC types cross the gate.
     */
    public static double[] nearestShipState(World world, double x, double y, double z) {
        return nearestShipState(world, x, y, z, Double.POSITIVE_INFINITY);
    }

    /**
     * As {@link #nearestShipState(World, double, double, double)}, but answering {@code null} when
     * the nearest loaded ship is farther than {@code maxDist} from the query point. A caller that
     * means ONE ship needs this: an unbounded nearest lookup on a world holding several ships
     * starts describing a neighbour the moment the intended ship unloads or flies off, and says
     * nothing about having done so.
     */
    public static double[] nearestShipState(World world, double x, double y, double z,
                                            double maxDist) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.nearestShipState(world, x, y, z, maxDist);
    }

    /**
     * The identity (VS ship uuid, as a string) of the loaded ship nearest to {@code (x,y,z)} within
     * {@code maxDist}, or {@code null} when VS is absent or there is no such ship.
     *
     * <p>Captured once, at a moment the caller can defend — its own ship freshly assembled at a
     * spot nothing else occupies — it turns every later question into
     * {@link #shipStateById(World, String)}, which cannot answer about a different ship however far
     * this one travels. The distance bound is only how the FIRST answer is attributed; it is not an
     * identity, and it is a full 3-D distance, so it says nothing about a ship that then climbs.</p>
     */
    public static String nearestShipId(World world, double x, double y, double z, double maxDist) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.nearestShipId(world, x, y, z, maxDist);
    }

    /**
     * State of the loaded ship named by {@code shipId}, in the layout of
     * {@link #nearestShipState(World, double, double, double)}, or {@code null} when VS is absent or
     * that ship is not loaded here. Position-independent.
     */
    public static double[] shipStateById(World world, String shipId) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipStateById(world, shipId);
    }

    /**
     * The world-frame angular velocity {@code [x,y,z]} (rad/s) of the ship named by
     * {@code shipId}, or {@code null} when VS is absent or that ship is not loaded here.
     */
    public static double[] shipAngularVelocityById(World world, String shipId) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipAngularVelocityById(world, shipId);
    }

    /**
     * Set the linear-velocity setpoint (blocks/second, world frame) of the loaded ship named by
     * {@code shipId}; a safe no-op returning false when VS is absent or that id names no ship
     * loaded here.
     */
    public static boolean pushShipById(World world, String shipId,
                                       double vx, double vy, double vz) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.pushShipById(world, shipId, vx, vy, vz);
    }

    /**
     * TEST-ONLY: directly set the angular velocity (rad/s, world frame) of the ship named by
     * {@code shipId}, bypassing the flight controller, so a test can spin a ship to a fully
     * inverted attitude via free physics. A safe no-op returning false when VS is absent or that
     * id names no ship loaded here.
     */
    public static boolean spinShipById(World world, String shipId,
                                       double wx, double wy, double wz) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.spinShipById(world, shipId, wx, wy, wz);
    }

    /**
     * The gates VS applies before ticking a ship's physics, plus its controller count — see
     * {@code VSBridge.shipPhysicsGatesById}. {@code null} when VS is absent or the id names no ship
     * loaded here.
     */
    public static int[] shipPhysicsGatesById(World world, String shipId) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipPhysicsGatesById(world, shipId);
    }

    /**
     * Enable physics on the ship named by {@code shipId} — what a bare assembled ship needs before
     * its flight computer's controller is stepped at all. A safe no-op returning false when VS is
     * absent or that id names no ship loaded here.
     */
    public static boolean enableShipPhysicsById(World world, String shipId) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.enableShipPhysicsById(world, shipId);
    }

    /**
     * Is a seat dummy bound to blocks a crossing is taking away — i.e. does the SEAT it is glued to
     * lie inside {@code cutBox}?
     *
     * <p>This is the whole of "nothing comes back bound to what was removed", and it is a decision
     * about the seat, never about the dummy. A dummy rides at its ship's WORLD position, which for a
     * managed ship is megablocks from the subspace shipyard the cut box covers, so asking where the
     * dummy is finds none of them and leaves every rider mounted on a chair whose ship has gone.</p>
     *
     * <p>A dummy with no seat binding is never matched: it is bound to nothing, so nothing the cut
     * removes can be what it is bound to.</p>
     */
    public static boolean boundToCutBlocks(net.minecraft.util.math.BlockPos seatPos,
                                           net.minecraft.util.math.AxisAlignedBB cutBox) {
        if (seatPos == null || cutBox == null) {
            return false;
        }
        return cutBox.contains(new net.minecraft.util.math.Vec3d(
                seatPos.getX() + 0.5D, seatPos.getY() + 0.5D, seatPos.getZ() + 0.5D));
    }
}
