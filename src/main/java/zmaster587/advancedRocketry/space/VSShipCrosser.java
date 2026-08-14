package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link ShipTransitManager.Crosser}: carries the transit state machine's depart/arrive
 * decisions out against live worlds, using the proven per-ship crossing ({@link VSIntegration#crossShip})
 * plus {@link VSIntegration#parkShipAt}/{@link VSIntegration#unparkShipAt}. Both crossings paste into a
 * clear void column so the flood-fill re-assembly grabs only the ship. A safe no-op
 * (returns {@code null} - the transit aborts cleanly) when VS is absent or a world is missing.
 */
public final class VSShipCrosser implements ShipTransitManager.Crosser {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    /** Clear-sky Y the target-cell arrival pastes at (cells are void; a high column avoids any floor). */
    private static final int ARRIVAL_Y = 200;
    /** Per-lane X offset for arrivals, so ships arriving into one cell from different lanes never overlap. */
    private static final int ARRIVAL_LANE_STRIDE = 64;

    /** The shared crossing primitives (readiness-gated pose teleport, rider carry, unpark) the entry
     *  on-ramp and the descent already settle through. Stateless. */
    private final VSShipCrossingOps ops = new VSShipCrossingOps();

    /** Monotonic per-boot counter for RESTORED arrivals (imported only at server start), spreading them
     *  across the NEGATIVE-X paste band so they never overlap each other or a live arrival. */
    private int restoredLane;

    /** The full crew captured at depart, keyed by ship id, held until the arrival reseat succeeds. In-memory
     *  only (the transit record persists the crew UUIDs, not the AFC-link offsets a reseat needs), so a
     *  restored transit's stash is empty after a restart - its reseat is a no-op, deferred to login-restore. */
    private final Map<String, List<CrewTransfer.Crew>> crewStash = new HashMap<>();

    /**
     * The non-crew bodies stowed out of the ship at each cut, keyed by ship id, until the far side is
     * ready to put them back. In-memory only, and that is the right lifetime: unlike the crew, whose
     * UUIDs the transit record persists, a stowed mob exists ONLY here between the two moments, so a
     * restart while a jump is in flight loses it — hyperspace does not survive one either,
     * and a body that came back without the ship it was standing on would be worse than one that did
     * not come back at all.
     */
    private final Map<String, List<AboardBodies.Stowed>> bodyStash = new HashMap<>();

    /**
     * Target slot dim &rarr; the arrival-guard cause last reported for it. An arrival is retried every
     * tick, so an un-deduplicated line would be 200 copies of itself; keeping the last cause still
     * reports a SECOND, different cause for the same slot, which is the case where repetition carries
     * information. Cleared when that slot's arrival gets past the guard.
     */
    private final Map<Integer, String> arrivalGuardWarned = new HashMap<>();

    /**
     * What the last arrival cut was about to take, in the two vocabularies a jump holds at once: the
     * craft its hyperspace anchor resolves by POSITION, the craft its durable id names, and what the
     * computer standing at that anchor calls itself. The cut happens once, hundreds of ticks before
     * an arrival that stalls reports anything, so nothing downstream can reconstruct it — and the
     * question "did this jump deliver the hull it meant" has no other witness. Deliberately not
     * test-gated: a harness child JVM has no test mode.
     */
    private static volatile String lastArrivalCut = "";

    /** @see #lastArrivalCut */
    public static String lastArrivalCut() {
        return lastArrivalCut;
    }

    /**
     * What was already parked in the lane the last departure took. The arrival end can only observe
     * that a lane holds two ships; it cannot say which of them arrived first, and therefore cannot
     * say whether a lane was handed out occupied or became occupied later.
     */
    private static volatile String lastDepartLane = "";

    /** @see #lastDepartLane */
    public static String lastDepartLane() {
        return lastDepartLane;
    }

    /** Owned by {@link SpaceDiagnostics#reset()} — see there for why a diagnostic needs an owner. */
    static void resetDiagnostics() {
        lastArrivalCut = "";
        lastDepartLane = "";
    }

    /**
     * Every registered ship whose transform sits within half a lane of {@code tile} — the same
     * margin {@link HyperspaceTiles#laneIndexAt} calls unambiguous — as {@code uuid@x,y,z}.
     */
    private static String describeShipsNearLane(WorldServer hyper, HyperspaceTiles.Tile tile) {
        double margin = HyperspaceTiles.SPACING_BLOCKS / 2.0;
        StringBuilder sb = new StringBuilder(120);
        for (java.util.Map.Entry<UUID, double[]> e
                : VSIntegration.registeredShipPoses(hyper).entrySet()) {
            double[] p = e.getValue();
            double dx = p[0] - tile.pos.getX(), dz = p[2] - tile.pos.getZ();
            if (dx * dx + dz * dz > margin * margin) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : " ").append(e.getKey()).append('@')
                    .append((int) p[0]).append(',').append((int) p[1]).append(',').append((int) p[2]);
        }
        return sb.toString();
    }

    /** Report an arrival that stopped at its own guard - once per target slot, per distinct cause. */
    private void warnArrivalGuardOnce(int targetSlotDim, String cause) {
        if (cause.equals(arrivalGuardWarned.put(targetSlotDim, cause))) {
            return;
        }
        LOGGER.warn("[SPACE] arrival into slot dim {} stopped BEFORE the crossing was attempted: {}. "
                        + "Nothing has moved; the arrival retries next tick. If the transit later gives "
                        + "up on this ship, this is the cause of it.",
                targetSlotDim, cause);
    }

    /**
     * The VS uuid a jump leg should cut at its anchor, out of the identities the world offers for it:
     * {@code byDurableId} (the craft whose own record carries this jump's durable id),
     * {@code byPosition} (whatever craft the anchor reaches), and {@code afcNames} (what the flight
     * computer standing at that anchor calls itself). {@link #REFUSED} when the anchor POSITIVELY
     * names another ship; {@code null} when nothing can be resolved at all, which is the same "cross
     * as before" the positional resolution always gave.
     *
     * <p><b>Both legs go through here</b>, and that is the point: a jump acts on the ship it NAMES,
     * on the way out and on the way back. The two used to differ — the departure resolved by identity
     * while the arrival cut whatever the anchor reached — and hyperspace is a shared parking world by
     * construction, so the leg that resolved by position is the one that could deliver a stranger.</p>
     *
     * <p><b>It may never turn a leg that would have worked into a failure.</b> Only a POSITIVE
     * mismatch refuses — the craft at the anchor carries a durable id and it is somebody else's. Every
     * other outcome (no flight computer resolvable there, no durable id minted on it, the physics mod
     * not naming the craft) proceeds exactly as before and SAYS that it could not verify. The
     * defect being closed is "the anchor silently selected a stranger's craft"; a check that also
     * blocks the cases it cannot judge trades one silent failure for a loud one and is not an
     * improvement.</p>
     *
     * <p>Requiring the flight computer here was tried and reverted: the capture path has warned
     * "found no flight computer at anchor" on these departures for as long as it has existed, without
     * stopping them, because the crossing needs only a shipyard box.</p>
     */
    public static java.util.UUID identifyShipToCut(String leg, BlockPos anchor, String shipId,
                                                   int dim, java.util.UUID byDurableId,
                                                   java.util.UUID byPosition,
                                                   java.util.UUID afcNames) {
        // THE JUMP'S OWN SHIP, resolved rather than merely compared against. The fallback below
        // answers by PROXIMITY; the identity the caller supplied used to reach it only as a tripwire
        // and was then discarded, so a leg that named its ship perfectly still crossed whatever craft
        // the anchor reached. That is not a small gap: the check compares two DURABLE ids while the
        // answer is a VS uuid found by position, so the two live in different identity spaces and the
        // check can refuse but can never aim.
        //
        // The lookup behind byDurableId is an INDEX, not a search: the durable id is carried on the
        // ship's own record and indexed beside its uuid, so it is one hash probe however many craft
        // the world holds. Null for everything it cannot settle - a synthetic fixture id, a ship whose
        // durable id was never bound - and the leg then proceeds exactly as before.
        if (byDurableId != null) {
            return byDurableId;
        }
        // The comparison is only meaningful when the caller named a REAL ship. Some legs are driven
        // under a synthetic id, and a synthetic id is not an identity claim — it cannot be compared,
        // so there is nothing to refuse. Checking it anyway is a false positive that blocks a jump
        // which would have worked, which is the one thing this method must never do.
        java.util.UUID expected = toUuid(shipId);
        if (expected != null && afcNames != null && !afcNames.equals(expected)) {
            LOGGER.error("[SPACE] {} REFUSED: the craft at anchor {} in dim {} is ship {}, not the "
                            + "ship this jump is about ({}) - the anchor selected somebody else's "
                            + "craft and cutting it would move the wrong ship. Nothing is cut.",
                    leg, anchor, dim, afcNames, shipId);
            return REFUSED;
        }
        return byPosition;
    }

    /** What the flight computer at {@code anchor} calls its ship, or {@code null} if there is no
     *  computer there (or it has no durable id yet). */
    private static java.util.UUID afcShipIdAt(WorldServer world, double ax, double ay, double az) {
        BlockPos afcPos = VSIntegration.flightComputerAt(world, ax, ay, az);
        net.minecraft.tileentity.TileEntity te = afcPos == null ? null : world.getTileEntity(afcPos);
        return te instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer
                ? ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) te).shipIdOrNull()
                : null;
    }

    /** Returned by {@link #identifyShipToCut} when the anchor provably names a DIFFERENT ship —
     *  distinct from {@code null}, which only means "could not verify, cross as before". */
    private static final java.util.UUID REFUSED =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Override
    public ShipCrossingService.Crossed departToHyperspace(int srcSlotDim, BlockPos srcAnchor,
                                                          String shipId,
                                                          HyperspaceTiles.Tile tile) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        // Three different reasons a departure never even starts, told apart. Rolled into one null they
        // are indistinguishable from a crossing that ran and failed, and the caller's log then blames
        // the cut for something that happened before it.
        if (src == null) {
            LOGGER.warn("[SPACE] depart aborted: no world for origin slot dim {} (the ship's cell is "
                    + "not bound to a live slot, or the id is from another session)", srcSlotDim);
            return null;
        }
        if (hyper == null) {
            LOGGER.warn("[SPACE] depart aborted: the shared hyperspace world could not be created "
                    + "(origin slot dim {})", srcSlotDim);
            return null;
        }
        if (srcAnchor == null) {
            LOGGER.warn("[SPACE] depart aborted: no origin anchor for the ship in slot dim {}",
                    srcSlotDim);
            return null;
        }
        // WHICH ship is departing, established before anything is cut. The anchor selects a craft by
        // PROXIMITY, and a cell can hold a second one — or a blockless remnant of one, which a
        // crossing is documented to leave behind. Resolving that box and cutting it is how a jump
        // came back "the shipyard holds no blocks": the box belonged to a stranger while the ship
        // that should have jumped sat untouched with its blocks elsewhere.
        double sax = srcAnchor.getX() + 0.5, say = srcAnchor.getY() + 0.5, saz = srcAnchor.getZ() + 0.5;
        java.util.UUID srcByDurable = VSIntegration.shipUuidOfDurableId(src, shipId);
        // Only asked when the durable id could not answer: locating the computer force-loads the
        // ship's far subspace yard, and the resolved case does not need it.
        java.util.UUID srcAfcNames = srcByDurable != null ? null : afcShipIdAt(src, sax, say, saz);
        java.util.UUID departing = identifyShipToCut("depart", srcAnchor, shipId, srcSlotDim,
                srcByDurable, VSIntegration.shipUuidAt(src, sax, say, saz), srcAfcNames);
        if (REFUSED.equals(departing)) {
            return null; // a different ship is at this anchor; identifyShipToCut said so
        }
        // WHO IS ALREADY IN THE LANE THIS DEPARTURE IS ABOUT TO PARK IN. The paste below writes to
        // tile.pos with no check that the lane is physically empty; the allocator only promises that
        // no OTHER LIVE TRANSIT holds the index, which is a statement about bookkeeping, not about
        // the world. Two ships sharing a lane makes every later position lookup at that anchor
        // ambiguous - including the one the arrival cuts by - so the moment a lane stops being empty
        // is the moment worth recording, and it is invisible from the arrival end.
        lastDepartLane = "jump=" + shipId + " lane=" + tile.index + " at=" + tile.pos
                + " alreadyThere=[" + describeShipsNearLane(hyper, tile) + "]";
        LOGGER.info("[SPACE] depart lane census: {}", lastDepartLane);
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                src, sax, say, saz,
                departing, hyper, tile.pos.getX(), tile.pos.getY(), tile.pos.getZ());
        if (!res.ok()) {
            LOGGER.warn("[SPACE] depart aborted: the crossing out of slot dim {} at anchor {} produced "
                    + "no ship in hyperspace",
                    srcSlotDim, srcAnchor);
            return null;
        }
        // Park the just-assembled ship so it holds its lane while ShipTransit advances its coord logically.
        VSIntegration.parkShipAt(hyper, res.anchor.getX() + 0.5, res.anchor.getY() + 0.5, res.anchor.getZ() + 0.5);
        // The crossing kept the ship's identity, so this uuid is the one it had in its origin cell and
        // the one it will still have at the far end - one name for the whole jump.
        return new ShipCrossingService.Crossed(res.anchor, res.shipUuid);
    }

    @Override
    public ShipCrossingService.Crossed arriveFromHyperspace(String shipId, HyperspaceTiles.Tile tile,
                                                            BlockPos hyperAnchor, int targetSlotDim) {
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (hyper == null || dst == null || hyperAnchor == null) {
            // An arrival that never even reaches the crossing used to be a bare null, repeated once per
            // tick until the state machine gave up. crossShip logs each of ITS four failures, so the
            // absence of any line meant the crossing had not been attempted - but nothing said so, and
            // the only surviving evidence was "arrival never succeeded" 200 ticks later, which names no
            // cause at all. Three separate causes hid behind that silence; say which.
            warnArrivalGuardOnce(targetSlotDim,
                    hyper == null ? "the shared hyperspace world could not be created"
                            : dst == null ? "the target cell is bound to this slot but the slot has no "
                                    + "world - nothing was crossed and nothing was lost"
                            : "the ship has no anchor in hyperspace");
            return null;
        }
        arrivalGuardWarned.remove(targetSlotDim);
        // The SECOND cut of the jump, so the second stow: whatever is loose on the deck in hyperspace
        // has to come out before the blocks under it do. The departure's stow was released here when
        // the crew boarded, so this is the same bodies, one leg on.
        BlockPos hyperAfc = VSIntegration.flightComputerAt(hyper, hyperAnchor.getX() + 0.5,
                hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5);
        if (hyperAfc != null) {
            List<AboardBodies.Stowed> bodies = AboardBodies.capture(hyper, hyperAfc);
            if (!bodies.isEmpty()) {
                bodyStash.put(shipId, bodies);
            }
            // The same second cut re-reads the CREW's posture, for the reason the bodies above are
            // re-stowed: the deck they are on has been livable for the whole flight. Without this the
            // arrival re-establishes everyone as he was when the jump FIRED, so a crew member who
            // stood up in the corridor is put back in the chair on arrival — a posture he left a
            // flight ago. Read-only: it changes the records, not the world.
            List<CrewTransfer.Crew> stashed = crewStash.get(shipId);
            double[] hyperShipPos = stashed == null || stashed.isEmpty()
                    ? null : VSIntegration.getShipWorldPosition(hyper, hyperAfc);
            if (hyperShipPos != null) {
                crewStash.put(shipId, CrewTransfer.refreshPostures(
                        hyper, hyperAfc, hyperShipPos, stashed));
            }
        }
        // Redundant since the pool took to holding every slot a cell is bound to, and kept anyway: this is
        // the call site that can least afford to lose the world, because VS is still assembling the ship
        // here and an unload would discard it mid-flight. Stating the hold locally costs nothing and does
        // not rely on the caller having materialized the cell through the pool.
        DimensionManager.keepDimensionLoaded(targetSlotDim, true);
        // Which craft this cut is ABOUT to take, against the one this jump is about. The cut below
        // resolves its source by POSITION, and hyperspace is the one world that provably holds many
        // parked hulls plus the blockless remnant of every ship that has ever left it - so the two
        // can differ, and when they do the arrival lands a stranger in the target cell under this
        // jump's name. Said here rather than inferred later: afterwards the ship this jump meant is
        // still parked in hyperspace and nothing at the destination records that it was never cut.
        // Said UNCONDITIONALLY, and that is the point: a line that only speaks on a mismatch cannot
        // report "they agree", and cannot report "neither could be established" either - so its
        // silence covers the answer, its opposite and its absence alike. Each field is stated so the
        // reading is falsifiable: which craft the position picks, which craft the durable id names,
        // and what the computer standing at that anchor calls itself.
        java.util.UUID meantToCut = VSIntegration.shipUuidOfDurableId(hyper, shipId);
        java.util.UUID aboutToCut = VSIntegration.shipUuidAt(hyper, hyperAnchor.getX() + 0.5,
                hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5);
        net.minecraft.tileentity.TileEntity hyperAfcTe =
                hyperAfc == null ? null : hyper.getTileEntity(hyperAfc);
        // ...and WHO ELSE is parked in this world, by lane. A lane is freed only when an arrival
        // finishes, and a crossing deliberately leaves the source ship registered (blockless) rather
        // than deregistering it before the cut - so a lane can hold more than one registered craft,
        // and "the ship at this anchor" stops being a question with one answer. Which craft sits in
        // WHICH lane is the thing no downstream reading can reconstruct.
        StringBuilder parked = new StringBuilder(200);
        int shown = 0;
        for (java.util.Map.Entry<UUID, double[]> e
                : VSIntegration.registeredShipPoses(hyper).entrySet()) {
            if (shown++ == 12) {
                parked.append(" ...more");
                break;
            }
            double[] p = e.getValue();
            parked.append(' ').append(e.getKey()).append('@')
                    .append((int) p[0]).append(',').append((int) p[1]).append(',').append((int) p[2])
                    .append("/lane").append(HyperspaceTiles.laneIndexAt(p[0], p[2]));
        }
        boolean hyperAfcIsComputer =
                hyperAfcTe instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
        java.util.UUID hyperAfcNames = hyperAfcIsComputer
                ? ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) hyperAfcTe)
                        .shipIdOrNull()
                : null;
        // WHICH craft is cut, decided by the same rule the departure uses: the ship this jump NAMES,
        // falling back to the anchor only where no identity can be established. Cutting by position
        // here is what delivered a stranger into the target cell under this jump's name while the
        // ship that jumped stayed parked in hyperspace — and hyperspace holds every ship in flight
        // at once, so "the ship at this anchor" is a question with more than one answer by design.
        java.util.UUID arriving = identifyShipToCut("arrival", hyperAnchor, shipId,
                hyper.provider.getDimension(), meantToCut, aboutToCut, hyperAfcNames);
        // The same question from the other end: what the craft at the anchor carries on its own
        // RECORD, read off the field rather than through the index. One null means "nobody ever
        // named this hull"; a name here with nothing in the index above would mean the naming
        // happened and the lookup cannot see it. They are different defects and they look identical
        // from a single reading.
        java.util.UUID anchorRecordName = VSIntegration.durableIdOfShip(hyper, aboutToCut);
        lastArrivalCut = "jump=" + shipId + " anchor=" + hyperAnchor + " ourLane=" + tile.index
                + " byPosition=" + aboutToCut + " byDurableId=" + meantToCut
                + " recordName=" + anchorRecordName
                // Ticks this computer has been GIVEN, and naming attempts inside them. A parked hull's
                // computer sits in its world's ticking set and is never ticked (0/0 through a whole
                // jump), so nothing here may be explained by "its tick will sort it out" - the reason
                // the name is carried onto the record by the crossing instead.
                + " afcTicksNamed=" + (hyperAfcIsComputer
                        ? ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) hyperAfcTe)
                                .tickCensus()
                        : "no-computer-tile")
                + " afcAtAnchor=" + hyperAfc + " afcNames="
                + (hyperAfcIsComputer ? hyperAfcNames : "no-computer-tile")
                + " cutting=" + (REFUSED.equals(arriving) ? "REFUSED" : arriving)
                + " parked=[" + parked.toString().trim() + "]";
        LOGGER.info("[SPACE] arrival cut census: {}", lastArrivalCut);
        if (REFUSED.equals(arriving)) {
            // Nothing is cut and nothing is lost: the ship this jump is about is still parked, and
            // the arrival retries next tick. The transit gives up eventually, which is the right
            // outcome — a jump that cannot find its own hull must not deliver somebody else's.
            warnArrivalGuardOnce(targetSlotDim, "the craft at this jump's hyperspace anchor names a "
                    + "DIFFERENT ship, so cutting it would deliver a stranger into the target cell");
            return null;
        }
        int dstX = tile.index * ARRIVAL_LANE_STRIDE;
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                hyper, hyperAnchor.getX() + 0.5, hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5,
                arriving, dst, dstX, ARRIVAL_Y, 0);
        // The paste lands in the destination's BLOCK band; the ship is moved onto its real pose (and
        // unparked there) by the settle step, once the asynchronous re-assembly is queryable.
        return res.ok() ? new ShipCrossingService.Crossed(res.anchor, res.shipUuid) : null;
    }

    @Override
    public BlockPos settleArrivedPose(int targetSlotDim, BlockPos pasteAnchor, UUID vsShipUuid,
                                      double px, double py, double pz) {
        // NOTE: no load pump here on purpose. This used to force-load every ship in the target cell
        // each retry, because the pose teleport's readiness gate asked whether a ship was LOADED —
        // and a jump arrives with nobody aboard, which is the one case the physics mod never loads
        // for. So AR queued a load every tick while the physics mod queued an unload every tick, and
        // the settle went through only when the two happened to interleave in its favour. The gate now
        // asks about the crossing's own progress instead, which needs no load at all; the crew re-seat
        // still pumps the queue for itself (it genuinely needs a live ship to resolve seat positions).
        // The same recipe the entry/descent settle uses (readiness gate, rider carry, unpark last), and
        // now with the same identity discipline: both calls are told WHICH ship arrived, so a target
        // cell holding a second craft cannot have its arrival move a stranger. The uuid is the one the
        // ship crossed with; a crossing keeps a ship's identity, so it is the same value the jump
        // departed under, and it has exactly the lifetime of the paste anchor beside it - a restart
        // mid-arrival re-derives both by completing the jump from its snapshot.
        if (!ops.teleportPoseWithRiders(targetSlotDim, pasteAnchor, vsShipUuid, px, py, pz)) {
            return null; // re-assembly not queryable yet: retry next tick, the ship stays pasted
        }
        ops.unpark(targetSlotDim, vsShipUuid, px, py, pz);
        return new BlockPos(px, py, pz);
    }

    @Override
    public net.minecraft.nbt.NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        if (hyper == null || hyperAnchor == null) {
            return null;
        }
        // Non-destructive re-cut of the parked ship from its subspace shipyard (the ship stays in flight).
        return VSIntegration.snapshotShipAt(hyper,
                hyperAnchor.getX() + 0.5, hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5);
    }

    @Override
    public net.minecraft.nbt.NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        if (src == null || srcAnchor == null) {
            return null;
        }
        // The depart-time floor: snapshot the ship in its origin cell, non-destructively, BEFORE the depart
        // crossing cuts it. Same subspace-shipyard cut as snapshotParked, just against the source world.
        return VSIntegration.snapshotShipAt(src,
                srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5);
    }

    @Override
    public ShipCrossingService.Crossed completeRestored(net.minecraft.nbt.NBTTagCompound snapshot,
                                                        int targetSlotDim) {
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (dst == null || snapshot == null) {
            // Same silence as the live arrival's guard, and the same retry-per-tick shape: discriminate,
            // once. A restored transit has no hyperspace ship to blame, so the two causes are the target
            // world and the snapshot the restore was supposed to carry.
            warnArrivalGuardOnce(targetSlotDim,
                    dst == null ? "the target cell is bound to this slot but the slot has no world"
                            : "the restored transit carries no block snapshot, so there is no ship to "
                                    + "paste");
            return null;
        }
        arrivalGuardWarned.remove(targetSlotDim);
        // Same local hold, same reason, as the live arrival above.
        DimensionManager.keepDimensionLoaded(targetSlotDim, true);
        // A restored transit holds no hyperspace lane. Paste it in the NEGATIVE-X band, DISJOINT from live
        // arrivals (which use tile.index*STRIDE, always >= 0), so a restored ship can never collide with a
        // live-crossing ship pasting into the same cell. Monotonic per boot (restored transits are imported
        // only at server start, a small set) so restored ships never overlap each other either - no wrap.
        // The snapshot source is always present (no async wait), so this pastes exactly once - a non-null
        // anchor on the first call, no retry - never a duplicate paste.
        int dstX = -ARRIVAL_LANE_STRIDE * (restoredLane++ + 1);
        VSIntegration.CrossResult res = VSIntegration.pasteAndAssemble(dst, snapshot, dstX, ARRIVAL_Y, 0);
        // A restored arrival is the one that CANNOT keep the ship's identity: the ship it names died
        // with the hyperspace world on the restart this transit survived, and what lands here is a
        // rebuild from stored blocks. The identity it comes back with is fresh, and the transit adopts
        // it - the settle and the re-seat that follow must name the ship that actually exists.
        return res.ok() ? new ShipCrossingService.Crossed(res.anchor, res.shipUuid) : null;
    }

    @Override
    public List<UUID> captureCrew(int srcSlotDim, BlockPos srcAnchor, String shipId) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        if (src == null || srcAnchor == null) {
            return Collections.emptyList();
        }
        // Locate the AFC FIRST: flightComputerAt force-loads the ship's far subspace shipyard, which is what
        // also makes CrewTransfer.capture's per-seat getTileEntity(seatPos) resolve (the aboard pilot only
        // chunk-loaded the ship's RENDER region, not its subspace shipyard). The AFC block is what capture
        // filters the ship's seats against.
        BlockPos afcPos = VSIntegration.flightComputerAt(src,
                srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5);
        if (afcPos == null) {
            // A departure that carries NOTHING — no crew, no loose body — because it could not find
            // the ship at the anchor it was given. That is a different failure from "there was nobody
            // aboard", and it used to be the same silence.
            LOGGER.warn("[SPACE] depart capture found no flight computer at anchor {} in slot dim {}: "
                    + "this jump carries neither crew nor anything else that was aboard",
                    srcAnchor, srcSlotDim);
            return Collections.emptyList();
        }
        // Everything that is not crew comes out FIRST, and it is deliberately ahead of the crew's own
        // guards. A mob on the deck and a dropped item are carried by the same ship-relative point as
        // the crew — they simply need no negotiation with a client, so they are stowed rather than
        // held — but the guards below answer about the CREW's needs, and a crewless ship that failed
        // one of them used to take its loose bodies down with it, silently.
        List<AboardBodies.Stowed> bodies = AboardBodies.capture(src, afcPos);
        if (!bodies.isEmpty()) {
            bodyStash.put(shipId, bodies);
        }
        // The ship's live WORLD position, keyed by the AFC's SUBSPACE block: getShipWorldPosition takes a
        // managed subspace block (as entry/descent pass their afcPos), NOT the world anchor - passing the world
        // anchor returns null (it is not a block the ship manages).
        double[] shipWorldPos = VSIntegration.getShipWorldPosition(src, afcPos);
        if (shipWorldPos == null) {
            return Collections.emptyList();
        }
        List<CrewTransfer.Crew> crew = CrewTransfer.capture(src, afcPos, shipWorldPos);
        if (!crew.isEmpty()) {
            crewStash.put(shipId, crew);
        }
        List<UUID> ids = new ArrayList<>();
        for (CrewTransfer.Crew c : crew) {
            ids.add(c.player.getUniqueID());
        }
        return ids;
    }

    @Override
    public boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId, UUID vsShipUuid) {
        List<CrewTransfer.Crew> stash = crewStash.get(shipId);
        List<AboardBodies.Stowed> bodies = bodyStash.get(shipId);
        boolean anyCrew = stash != null && !stash.isEmpty();
        boolean anyBodies = bodies != null && !bodies.isEmpty();
        if (!anyCrew && !anyBodies) {
            return true; // crewless, restored (stash wiped on restart), or already placed - nothing to do
        }
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (dst == null || arrivalAnchor == null) {
            return false; // target world not up yet - retry next tick
        }
        // The stowed bodies are placed on the same retry loop and reported through the same verdict:
        // a jump is not finished while a mob that was standing on the deck is still in a map here.
        boolean bodiesPlaced = !anyBodies || releaseStowed(dst, arrivalAnchor, shipId, bodies);
        if (!anyCrew) {
            return bodiesPlaced;
        }
        // NOTE: no load pump here on purpose (see settleArrivedPose for the other half of this). The
        // re-seat used to force-load every ship in the target cell each retry so the re-assembled seat
        // tiles would resolve, which put AR in a per-tick tug of war with VS's unload of a ship nobody is
        // near. It reads the seats' positions off the ships' durable records now, and force-loads only the
        // shipyard CHUNKS it has to scan — neither of which needs a live physics object.
        // Keyed by IDENTITY, like the entry and descent re-seats: the seat scan searches the shipyard of
        // the ship that arrived, not of whichever craft happens to be nearest the arrival point. That
        // distinction is the whole failure this path used to be able to produce - a destination holding
        // a second ship had its arrival scan the stranger's yard, find no seat, and give up while the
        // crew's own seat sat tens of thousands of blocks away in the same world.
        if (CrewTransfer.reseat(dst, arrivalAnchor, stash, toUuid(shipId), vsShipUuid)) {
            crewStash.remove(shipId);
            return bodiesPlaced;
        }
        return false;
    }

    /**
     * Put the bodies stowed for {@code shipId} back on the ship that arrived at {@code anchor}, and
     * drop the stash once they are down. {@code false} means the ship is not rebuilt here yet, which
     * is the same "come back next tick" the crew placement answers with — and nothing has been placed,
     * so a retry cannot duplicate anything.
     */
    private boolean releaseStowed(WorldServer world, BlockPos anchor, String shipId,
                                  List<AboardBodies.Stowed> bodies) {
        BlockPos afcPos = VSIntegration.flightComputerAt(world, anchor.getX() + 0.5,
                anchor.getY() + 0.5, anchor.getZ() + 0.5);
        if (afcPos == null || AboardBodies.release(world, afcPos, bodies) == 0) {
            return false;
        }
        bodyStash.remove(shipId);
        return true;
    }

    @Override
    public int parkedDim() {
        return HyperspaceWorld.dimId();
    }

    @Override
    public boolean hasStowedBodies(String shipId) {
        List<AboardBodies.Stowed> bodies = bodyStash.get(shipId);
        return bodies != null && !bodies.isEmpty();
    }

    @Override
    public boolean parkedShipPresent(BlockPos hyperAnchor) {
        WorldServer hyper = HyperspaceWorld.getIfLoaded();
        if (hyper == null || hyperAnchor == null) {
            return false;
        }
        // Asked of the REGISTRY, not of the loaded set. This runs at boot, when nobody is anywhere
        // near a parked ship, and the physics mod decides loadedness from player proximity every
        // tick — so "is it loaded" would answer no for every ship that is perfectly well there.
        return VSIntegration.shipUuidAt(hyper, hyperAnchor.getX() + 0.5,
                hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5) != null;
    }

    @Override
    public List<Integer> parkedShipLanes() {
        List<Integer> lanes = new ArrayList<>();
        WorldServer hyper = HyperspaceWorld.getIfLoaded();
        if (hyper == null) {
            return lanes;
        }
        for (Map.Entry<UUID, double[]> ship : VSIntegration.registeredShipPoses(hyper).entrySet()) {
            double[] pos = ship.getValue();
            int lane = HyperspaceTiles.laneIndexAt(pos[0], pos[2]);
            if (lane >= 0) {
                lanes.add(lane);
            } else {
                // A ship in this world that is in no lane at all. Nothing parks outside a lane, so it
                // is either debris from an interrupted crossing or something another mod put here;
                // either way no record can claim it, and the reconciliation cannot address it by lane.
                LOGGER.warn("[SPACE] a ship in hyperspace sits in no lane ({}, {}, {}) - it is not "
                        + "reachable by the lane reconciliation and will be left alone",
                        (long) pos[0], (long) pos[1], (long) pos[2]);
            }
        }
        return lanes;
    }

    @Override
    public boolean disposeParkedLane(int laneIndex) {
        WorldServer hyper = HyperspaceWorld.getIfLoaded();
        if (hyper == null) {
            return false;
        }
        BlockPos lane = HyperspaceTiles.tilePos(laneIndex);
        UUID uuid = VSIntegration.shipUuidAt(hyper, lane.getX() + 0.5, lane.getY() + 0.5,
                lane.getZ() + 0.5);
        if (uuid == null) {
            return false;
        }
        // Deregistration, not a block wipe. A hull the physics mod no longer knows about is inert:
        // its blocks sit in a far subspace shipyard that nothing loads, claims or ticks, and cutting
        // them would mean force-loading a shipyard to delete blocks nobody can reach. What has to
        // stop is the ship EXISTING as a ship in a permanently loaded world; the caller retires the
        // lane so nothing is ever pasted on top of what is left.
        return VSIntegration.releaseShipIfNothingLoaded(hyper, uuid);
    }

    @Override
    public boolean boardCrew(int parkedDim, BlockPos anchor, String shipId, UUID vsShipUuid) {
        List<CrewTransfer.Crew> stash = crewStash.get(shipId);
        List<AboardBodies.Stowed> bodies = bodyStash.get(shipId);
        boolean anyCrew = stash != null && !stash.isEmpty();
        boolean anyBodies = bodies != null && !bodies.isEmpty();
        if (!anyCrew && !anyBodies) {
            return true; // crewless, or a restored transit whose stash did not survive the restart
        }
        WorldServer dst = DimensionManager.getWorld(parkedDim);
        if (dst == null || anchor == null) {
            return false; // hyperspace not up yet - retry next tick
        }
        // The stowed bodies come back out here, onto the parked hull, and the ARRIVAL cut stows them
        // again — the same two-leg shape the crew has, for the same reason: the far side is a fresh
        // re-assembly and nothing that was written down against the old one survives it.
        boolean bodiesPlaced = !anyBodies || releaseStowed(dst, anchor, shipId, bodies);
        if (!anyCrew) {
            return bodiesPlaced;
        }
        // Deliberately does NOT remove the stash: these same records seat the crew again at the far
        // end, and only their flight-computer link offsets can re-identify a seat on a ship that has
        // been re-assembled into a fresh subspace since.
        //
        // Named by identity, and hyperspace is where that matters most: every ship in flight is parked
        // in the same world, so "the ship at this anchor" has neighbours by construction.
        return CrewTransfer.reseat(dst, anchor, stash, toUuid(shipId), vsShipUuid) && bodiesPlaced;
    }

    @Override
    public void messageCrew(List<UUID> crew, String translationKey) {
        if (crew == null || crew.isEmpty()) {
            return;
        }
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        // The transit carries player UUIDs, not the crew records the crossing ops message: an aboard
        // player who logged out mid-jump is simply absent here, which is the right outcome — there is
        // nobody to tell, and his ship's state is on the ledger for when he returns.
        for (UUID id : crew) {
            net.minecraft.entity.player.EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(id);
            if (p != null && !p.hasDisconnected()) {
                p.sendMessage(new net.minecraft.util.text.TextComponentTranslation(translationKey));
            }
        }
    }

    /** The transit keys ships by the AR ship UUID string; a non-UUID key (test fixtures) carries
     *  no durable identity, so the re-seat runs without the wrong-ship filter there. */
    private static java.util.UUID toUuid(String shipId) {
        try {
            return java.util.UUID.fromString(shipId);
        } catch (Exception e) {
            return null;
        }
    }
}
