package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * Moves a tier-2 ship's crew across a per-ship crossing. {@link VSIntegration#crossShip} moves
 * blocks + tile NBT only — riders differ per caller — and a crossing re-assembles the ship into a
 * <b>fresh subspace</b>, so every absolute-subspace binding (a pilot dummy's {@code seatPos})
 * goes stale. This class owns the two halves around the crossing:
 *
 * <ol>
 *   <li>{@link #capture}: BEFORE the cut — enumerate the crew at the ship's live world position
 *       (riders live in the WORLD frame, never in the shipyard box), record each of them against a
 *       binding that IS invariant under re-assembly, and retire the old dummies (their seat blocks
 *       are about to stop existing).</li>
 *   <li>{@link #reseat}: AFTER re-assembly — re-establish each of them on the rebuilt ship and
 *       transfer them into the destination world.</li>
 * </ol>
 *
 * <h2>Both postures of being aboard</h2>
 *
 * A crew member is carried in whatever posture he is in; SEATED and STANDING are two shapes of one
 * membership, not two populations:
 *
 * <ul>
 *   <li><b>SEATED</b> — a pilot on a linked pilot seat. Recorded by his seat's AFC-link offset and
 *       re-mounted at the far end on a freshly-bound dummy (the {@code BlockPilotSeat} mount
 *       recipe).</li>
 *   <li><b>STANDING</b> — a crew member on his feet on the deck. Recorded by the deck point he
 *       stands on relative to the same flight computer, and placed back at that point on the far
 *       side, HELD there until his ship exists: the re-assembly is asynchronous, and a body handed
 *       to world gravity meanwhile falls off (or through) a deck that is not there yet.</li>
 * </ul>
 *
 * <p>The two are recorded against the same landmark for the same reason — the flight computer is
 * what survives a ship being rebuilt into a fresh subspace.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class CrewTransfer {

    /** How far (blocks) around the ship's world position riders are enumerated — the proven
     *  rider-carry box of the ship-move probes. */
    private static final double RIDER_RANGE = 8.0;

    /** Why the last {@link #reseat} call could not seat its whole crew, or {@code ""} when the last
     *  one succeeded. The re-seat is one half of an asynchronous settle whose only failure report is
     *  "gave up after N attempts"; that report is unactionable without knowing WHICH of the seat
     *  lookup's steps failed, so each retry records its own block here and the crossing prints it
     *  when it gives up. Deliberately not test-gated: a harness child JVM has no test mode. */
    private static volatile String lastReseatBlock = "";

    /** @see #lastReseatBlock */
    public static String lastReseatBlock() {
        return lastReseatBlock;
    }

    /**
     * One captured crew member: the player, the posture he was aboard in, and the flight-computer-
     * relative binding that re-identifies where he belongs on the re-assembled ship (relative
     * offsets survive the rigid relocation; absolute subspace coordinates do not).
     *
     * <p>The posture decides which of the two offsets carries the meaning — a seat lands on a block
     * and stays integral, a body on its feet stands at a continuous point — exactly as the durable
     * aboard record splits them, and this reuses that record's {@code Posture} rather than minting a
     * second vocabulary for the same distinction.</p>
     */
    public static final class Crew {
        public final EntityPlayerMP player;
        public final ShipAboardTag.Posture posture;
        /** SEATED: the seat's AFC-link offset. Zero and meaningless when the posture is STANDING. */
        public final int afcDx, afcDy, afcDz;
        /** STANDING: the deck point, relative to the computer. Zero when the posture is SEATED. */
        public final double standDx, standDy, standDz;

        /** Whether this rider has already been told his seat is held by someone else — the reseat
         *  retries every tick until the whole crew resolves, and the message must not repeat. */
        boolean seatLostNotified;

        public Crew(EntityPlayerMP player, int afcDx, int afcDy, int afcDz) {
            this.player = player;
            this.posture = ShipAboardTag.Posture.SEATED;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
            this.standDx = 0.0D;
            this.standDy = 0.0D;
            this.standDz = 0.0D;
        }

        private Crew(EntityPlayerMP player, double dx, double dy, double dz) {
            this.player = player;
            this.posture = ShipAboardTag.Posture.STANDING;
            this.afcDx = 0;
            this.afcDy = 0;
            this.afcDz = 0;
            this.standDx = dx;
            this.standDy = dy;
            this.standDz = dz;
        }

        /** A crew member on his feet at {@code (dx,dy,dz)} from his ship's flight computer. */
        public static Crew standing(EntityPlayerMP player, double dx, double dy, double dz) {
            return new Crew(player, dx, dy, dz);
        }
    }

    private CrewTransfer() { }

    /**
     * Enumerate the seated crew of the ship whose flight computer sits at subspace {@code afcPos},
     * with the ship's live world position {@code shipWorldPos}. Records each seated player against
     * its seat's link offset, dismounts it, and retires the now-orphaned dummy. Call BEFORE the
     * crossing cuts the ship's blocks — and only once every refusal is behind: a capture unseats
     * the crew, so a crossing that can still be refused must {@link #peek} instead.
     */
    public static List<Crew> capture(WorldServer world, BlockPos afcPos, double[] shipWorldPos) {
        // A crossing's dismount is NOT the player leaving his post, and his durable aboard record
        // must survive it. Nothing special is needed here for that any more: the record is derived
        // from state by one writer, which drops a record only on positive evidence that the player
        // is off a ship that is present — and mid-crossing the ship is not present to judge by.
        return walk(world, afcPos, shipWorldPos, true);
    }

    /**
     * The read-only twin of {@link #capture}: enumerate the same seated crew WITHOUT touching it —
     * no dismount, no dummy retirement. This is what a refusal path reads to message the crew while
     * leaving every pilot exactly where he sits.
     */
    public static List<Crew> peek(WorldServer world, BlockPos afcPos, double[] shipWorldPos) {
        return walk(world, afcPos, shipWorldPos, false);
    }

    /**
     * Re-read the postures of an ALREADY-captured crew against the ship they are aboard right now,
     * and return the records the far end should be re-established from.
     *
     * <p>A jump has TWO cuts, and between them the ship is a place its crew lives in: they may stand
     * up, walk the deck, or take a different chair. The first cut's capture answers where each of them
     * was when the jump fired; replaying that at the second returns a posture that can be an entire
     * flight out of date, which from the cockpit looks like standing up in the corridor and being
     * folded back into the seat on arrival.
     *
     * <p><b>Read-only against the world</b>: nothing is dismounted and no dummy is retired, so how the
     * crossing treats a still-mounted rider is exactly as it was. A crew member the live enumeration
     * cannot find keeps his earlier record — offline, taken by the void, and momentarily unresolvable
     * all look alike from here, and dropping the record would strand whoever IS still aboard.
     *
     * <p>Anyone now found on his FEET is pinned where he stands, for the same reason {@link #capture}
     * pins him: the blocks under him are about to be cut.
     */
    public static List<Crew> refreshPostures(WorldServer world, BlockPos afcPos,
            double[] shipWorldPos, List<Crew> captured) {
        if (captured == null || captured.isEmpty()) {
            return captured;
        }
        List<Crew> live = peek(world, afcPos, shipWorldPos);
        List<Crew> out = new ArrayList<>(captured.size());
        for (Crew was : captured) {
            Crew now = null;
            for (Crew c : live) {
                // By UUID, never by entity identity: a crew member who relogged mid-flight is a
                // different object with the same player.
                if (c.player.getUniqueID().equals(was.player.getUniqueID())) {
                    now = c;
                    break;
                }
            }
            if (now == null) {
                out.add(was);
                continue;
            }
            if (now.posture == ShipAboardTag.Posture.STANDING) {
                zmaster587.advancedRocketry.integration.vs.DeckHold.pinInPlace(now.player);
            }
            out.add(now);
        }
        return out;
    }

    /** The shared enumeration behind {@link #capture} / {@link #peek}; {@code detach} is the only
     *  difference between them. */
    private static List<Crew> walk(WorldServer world, BlockPos afcPos, double[] shipWorldPos,
            boolean detach) {
        List<Crew> crew = new ArrayList<>();
        if (shipWorldPos == null) {
            return crew;
        }
        AxisAlignedBB box = new AxisAlignedBB(
                shipWorldPos[0], shipWorldPos[1], shipWorldPos[2],
                shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]).grow(RIDER_RANGE);
        for (EntityDummy dummy : world.getEntitiesWithinAABB(EntityDummy.class, box)) {
            BlockPos seatPos = dummy.getSeatPos();
            if (seatPos == null) {
                continue;
            }
            TileEntity te = world.getTileEntity(seatPos);
            if (!(te instanceof TilePilotSeat)) {
                continue;
            }
            TilePilotSeat seat = (TilePilotSeat) te;
            BlockPos linkedAfc = seat.getFlightComputerPos();
            if (linkedAfc == null || !linkedAfc.equals(afcPos)) {
                continue; // a different ship's seat sharing the neighbourhood
            }
            int dx = linkedAfc.getX() - seatPos.getX();
            int dy = linkedAfc.getY() - seatPos.getY();
            int dz = linkedAfc.getZ() - seatPos.getZ();
            for (Entity passenger : dummy.getPassengers()) {
                if (passenger instanceof EntityPlayerMP) {
                    crew.add(new Crew((EntityPlayerMP) passenger, dx, dy, dz));
                    if (detach) {
                        passenger.dismountRidingEntity();
                    }
                }
            }
            if (detach) {
                // The seat block this dummy is bound to is about to be cut; a stale dummy would
                // otherwise linger and clear the (re-assembled) ship's pilot input every tick.
                dummy.setDead();
            }
        }
        crew.addAll(walkStanding(world, afcPos, detach));
        return crew;
    }

    /**
     * The crew members of this ship who are on their FEET, and the deck point each of them stands
     * on. A {@code detach} pass also pins each of them where he is: the next thing that happens to
     * this ship is that its blocks are cut, and a body standing on a deck that stops existing is a
     * body falling through a void cell until something puts it back.
     *
     * <p>Keyed by IDENTITY, not by a box. A standing crew member rides nothing, so there is no
     * dummy to find him through, and the box the seated scan uses is a proxy for "on this ship"
     * that a body one block outside it would defeat. The deck resolver already answers the exact
     * question — which ship is this body aboard — so the enumeration walks the world's players and
     * keeps the ones whose answer is the ship being crossed.</p>
     */
    private static List<Crew> walkStanding(WorldServer world, BlockPos afcPos, boolean detach) {
        List<Crew> standing = new ArrayList<>();
        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
            if (!(p instanceof EntityPlayerMP) || p.getRidingEntity() instanceof EntityDummy) {
                continue; // a seated crew member is the loop above's; never carry one twice
            }
            double[] offset = ShipRelativePoint.deckOffsetOfAboardBody(world, p, afcPos);
            if (offset == null) {
                continue;
            }
            standing.add(Crew.standing((EntityPlayerMP) p, offset[0], offset[1], offset[2]));
            if (detach) {
                zmaster587.advancedRocketry.integration.vs.DeckHold.pinInPlace((EntityPlayerMP) p);
            }
        }
        return standing;
    }

    /**
     * Re-seat the captured crew on the re-assembled ship anchored (any ship block) at
     * {@code anchor} in {@code dstWorld}: for each rider, find the seat whose AFC-link offset
     * matches its record, transfer the rider into {@code dstWorld} (production player-list path),
     * and mount it on a freshly-bound dummy. Returns {@code false} if any rider's seat could not
     * be resolved yet (the caller retries next tick — re-assembly is asynchronous; already-seated
     * riders are not double-mounted thanks to the bound-dummy reuse in the mount recipe).
     *
     * <p>{@code expectedShipId} is the DESTINATION ship's durable id (the flight computer's
     * persisted UUID, which rides the crossing's tile NBT verbatim). When non-null, only a seat
     * whose linked computer carries that id is accepted — the seat search is a spatial
     * neighbourhood scan, and without the id filter two ships parked within a few blocks of each
     * other with matching seat offsets can CROSS-SEAT a rider onto the wrong craft. {@code null}
     * skips the filter (caller has no id — e.g. a ship whose computer never minted one).</p>
     */
    public static boolean reseat(WorldServer dstWorld, BlockPos anchor, List<Crew> crew,
            java.util.UUID expectedShipId, java.util.UUID vsShipUuid) {
        if (crew.isEmpty()) {
            return true;
        }
        List<TilePilotSeat> seats = seatsOfShipAt(dstWorld, anchor, vsShipUuid);
        boolean allSeated = true;
        for (Crew rider : crew) {
            if (rider.posture == ShipAboardTag.Posture.STANDING) {
                // A crew member on his feet has no seat to look for: he is put back at his own deck
                // point and held there. Same retry contract as the seated branch — false means the
                // ship is not up yet, and the caller comes back next tick.
                allSeated &= placeOnDeck(dstWorld, anchor, rider, expectedShipId, vsShipUuid);
                continue;
            }
            TilePilotSeat seat = matchSeat(seats, rider, expectedShipId, DURABLE_SHIP_ID);
            // Registry-keyed, NOT physo-keyed: an arriving ship has nobody near it — the crew who would
            // load it are the ones this method is carrying across — so asking a question only a LOADED
            // ship can answer made the re-seat wait on AR force-loading the ship against VS's own unload.
            // The seat's position is on the ship's durable record and needs no live physics object.
            double[] seatWorld = seat == null
                    ? null : VSIntegration.getRegisteredSeatWorldPosition(dstWorld, seat.getPos());
            if (seat == null || seatWorld == null) {
                allSeated = false; // seat tile or ship transform not up yet — retry
                continue;
            }
            EntityPlayerMP player = liveEntityOf(rider.player);
            if (player == null) {
                continue;
            }
            if (player.dimension != dstWorld.provider.getDimension()) {
                final double tx = seatWorld[0], ty = seatWorld[1], tz = seatWorld[2];
                player.getServer().getPlayerList().transferPlayerToDimension(player,
                        dstWorld.provider.getDimension(),
                        (world, entity, yaw) -> entity.setLocationAndAngles(tx, ty, tz, yaw, 0f));
                ArrivalTrace.server("reseat.dimTransfer t=" + dstWorld.getTotalWorldTime()
                        + " p=" + player.getEntityId() + " toY=" + ArrivalTrace.fmt(ty));
            } else {
                player.setPositionAndUpdate(seatWorld[0], seatWorld[1], seatWorld[2]);
                ArrivalTrace.server("reseat.setPos t=" + dstWorld.getTotalWorldTime()
                        + " p=" + player.getEntityId() + " toY=" + ArrivalTrace.fmt(seatWorld[1]));
            }
            // "Already seated" means seated ON THIS SEAT — riding the dummy bound to it, in this
            // world. Any other dummy is a leftover mount, and treating one as proof of a finished
            // re-seat is how a crossing loses its pilot: the transfer above carries him into the
            // target dimension WITHOUT dismounting him (vanilla's transferPlayerToDimension goes
            // through removeEntityDangerously, which — unlike removeEntity — leaves the ride
            // intact), so he arrives still bound to the departure hull's dummy, in the world he
            // just left. Skipping the mount then reported the whole crew seated while his client
            // could not even see what he was riding.
            EntityDummy seatDummy = zmaster587.advancedRocketry.block.BlockPilotSeat
                    .boundDummyAt(dstWorld, seat.getPos());
            Entity ridden = player.getRidingEntity();
            if (ridden instanceof EntityDummy && ridden == seatDummy) {
                continue; // already re-seated by an earlier retry
            }
            if (ridden instanceof EntityDummy) {
                // Any OTHER dummy is a stale mount and must not stop the re-seat. The swap needs no
                // dismount here — the mount below is forced, and a forced startRiding dismounts
                // first — which is also how the pre-assembly rebind below does it.
                ArrivalTrace.server("reseat.staleMount t=" + dstWorld.getTotalWorldTime()
                        + " p=" + player.getEntityId() + " stale=" + ridden.getEntityId());
            }
            // The BlockPilotSeat mount recipe: a dummy at the seat's live world position, bound
            // to the seat's (new) subspace block, and the player riding it. Reuse the seat's
            // existing bound dummy when one is already there (one seat — one dummy; a second
            // dummy's riderless twin would clear the ship's pilot input every tick).
            EntityDummy dummy = boundDummyForMount(dstWorld, seat.getPos(),
                    seatWorld[0], seatWorld[1], seatWorld[2]);
            if (dummy == null) {
                // The seat's dummy is occupied by someone else — never double-mount. The rider
                // stays where the transfer above put him: STANDING aboard at his post. A silently
                // lost chair reads as a broken restore, so tell him who holds it (once).
                if (!rider.seatLostNotified) {
                    rider.seatLostNotified = true;
                    EntityDummy resident = zmaster587.advancedRocketry.block.BlockPilotSeat
                            .boundDummyAt(dstWorld, seat.getPos());
                    if (resident != null && !resident.getPassengers().isEmpty()) {
                        zmaster587.advancedRocketry.util.DelayedActionBar.send(player,
                                new net.minecraft.util.text.TextComponentTranslation(
                                        "msg.pilotseat.taken",
                                        resident.getPassengers().get(0).getName()), 20);
                    }
                }
                continue;
            }
            player.startRiding(dummy, true);
            ArrivalTrace.server("reseat.mount t=" + dstWorld.getTotalWorldTime()
                    + " p=" + player.getEntityId() + " dummy=" + dummy.getEntityId()
                    + " y=" + ArrivalTrace.fmt(seatWorld[1]));
        }
        lastReseatBlock = allSeated ? "" : describeReseatBlock(dstWorld, anchor, seats, crew,
                expectedShipId, vsShipUuid);
        return allSeated;
    }

    /**
     * The live player behind a captured record, or {@code null} when there is none right now.
     *
     * <p>A crew member who RELOGGED mid-crossing is a different entity object: the captured
     * reference is the pre-relog one, replaced wholesale by his fresh login. He is re-resolved by
     * UUID — the durable identity — so an arrival still hands control back to the RETURNED player.
     * Genuinely-offline crew answers {@code null} and is skipped: the login restore owns whoever
     * comes back after the crossing is over.</p>
     */
    private static EntityPlayerMP liveEntityOf(EntityPlayerMP player) {
        if (!player.hasDisconnected()) {
            return player;
        }
        EntityPlayerMP fresh = player.getServer() == null ? null
                : player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID());
        return fresh == null || fresh.hasDisconnected() ? null : fresh;
    }

    /**
     * Put a crew member who was on his FEET back on the deck of the ship that arrived: at the same
     * point relative to its flight computer, at rest, and HELD there until his own client has taken
     * the deck capture over.
     *
     * <p>Returns {@code false} while the arrived ship cannot yet say where that point is in the
     * world — the caller retries next tick, exactly as it does for a seat that has not come up. The
     * body stays where it is meanwhile, pinned by the hold the capture installed: nothing is moved
     * half-way and then abandoned, which is what {@code D207-9}'s "hold the body, do not race the
     * client" costs and buys.</p>
     *
     * <p>The two questions this asks are both keyed by IDENTITY: which flight computer belongs to
     * the ship that crossed, and where that ship's transform puts the point. Neither is a lookup by
     * position, because a destination that already holds another craft would answer both of them
     * about the stranger.</p>
     */
    private static boolean placeOnDeck(WorldServer dstWorld, BlockPos anchor, Crew rider,
            java.util.UUID expectedShipId, java.util.UUID vsShipUuid) {
        EntityPlayerMP player = liveEntityOf(rider.player);
        if (player == null) {
            return true; // offline: his durable aboard record puts him back when he returns
        }
        BlockPos afcPos = flightComputerOfShipAt(dstWorld, anchor, vsShipUuid, expectedShipId);
        double[] sub = ShipRelativePoint.subspacePointOf(
                afcPos, rider.standDx, rider.standDy, rider.standDz);
        // Registry-keyed for the same reason the seat lookup is: an arriving ship has nobody near
        // it — the crew who would load it are the ones this method is carrying — so a question only
        // a LOADED ship can answer would make the placement wait on force-loading the ship against
        // the physics mod's own unload of it.
        double[] deckWorld = sub == null ? null
                : VSIntegration.getRegisteredSubspacePointWorldPosition(
                        dstWorld, afcPos, sub[0], sub[1], sub[2]);
        if (deckWorld == null) {
            return false; // the ship has not been rebuilt here yet: retry
        }
        if (player.dimension != dstWorld.provider.getDimension()) {
            final double tx = deckWorld[0], ty = deckWorld[1], tz = deckWorld[2];
            player.getServer().getPlayerList().transferPlayerToDimension(player,
                    dstWorld.provider.getDimension(),
                    (world, entity, yaw) -> entity.setLocationAndAngles(tx, ty, tz, yaw, 0f));
            ArrivalTrace.server("deck.dimTransfer t=" + dstWorld.getTotalWorldTime()
                    + " p=" + player.getEntityId() + " toY=" + ArrivalTrace.fmt(ty));
        } else {
            player.setPositionAndUpdate(deckWorld[0], deckWorld[1], deckWorld[2]);
        }
        // At rest, relative to the deck: a crossing may take a couple of ticks of his movement, and
        // whatever motion he carried belongs to a world he is no longer in.
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.fallDistance = 0.0f;
        // The ship is named by the identity it crossed with, not by whatever is loaded here now: the
        // hold outlives the moment, and the ship it waits for is precisely the one that arrived.
        zmaster587.advancedRocketry.integration.vs.DeckHold.holdOnDeck(player,
                vsShipUuid == null
                        ? VSIntegration.shipIdManagingBlock(dstWorld, afcPos) : vsShipUuid.toString(),
                sub[0], sub[1], sub[2]);
        ArrivalTrace.server("deck.place t=" + dstWorld.getTotalWorldTime()
                + " p=" + player.getEntityId() + " y=" + ArrivalTrace.fmt(deckWorld[1]));
        return true;
    }

    /**
     * The SUBSPACE position of the flight computer of the ship that arrived at {@code anchor}, or
     * {@code null} while it is not reachable yet. The standing half of what {@link #seatsOfShipAt}
     * does for seats, over the same shipyard box and with the same chunk force-load, and filtered by
     * the DURABLE ship id so a neighbouring craft's computer is never taken for this one's.
     */
    private static BlockPos flightComputerOfShipAt(WorldServer world, BlockPos anchor,
            java.util.UUID vsShipUuid, java.util.UUID expectedShipId) {
        AxisAlignedBB yard = yardOfTheShipWeMean(world, anchor, vsShipUuid);
        forceLoadYard(world, yard);
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer)) {
                continue;
            }
            BlockPos p = te.getPos();
            boolean inYard = yard != null && yard.contains(new net.minecraft.util.math.Vec3d(
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            boolean nearAnchor = p.distanceSq(anchor) <= RIDER_RANGE * RIDER_RANGE;
            if (!inYard && !nearAnchor) {
                continue;
            }
            if (expectedShipId != null && !expectedShipId.equals(
                    ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) te)
                            .shipIdOrNull())) {
                continue; // another craft's computer sharing the neighbourhood
            }
            return p;
        }
        return null;
    }

    /**
     * One line naming the step at which the re-seat's seat lookup stopped: whether any ship claims
     * the arrival point at all, how many seat tiles the scan reached, and for each of them the three
     * things {@link #matchSeat} discriminates on (its AFC link offset, the durable ship id behind
     * that link, and whether its world position resolves). Built only on the failing path.
     */
    private static String describeReseatBlock(WorldServer world, BlockPos anchor,
            List<TilePilotSeat> seats, List<Crew> crew, java.util.UUID expectedShipId,
            java.util.UUID vsShipUuid) {
        AxisAlignedBB yard = yardOfTheShipWeMean(world, anchor, vsShipUuid);
        StringBuilder sb = new StringBuilder(320);
        sb.append("anchor=").append(anchor.getX()).append(',').append(anchor.getY()).append(',')
                .append(anchor.getZ())
                .append(" scannedShip=")
                .append(vsShipUuid == null ? "BY-POSITION" : vsShipUuid.toString())
                .append(" yard=")
                .append(yard == null
                        ? (vsShipUuid == null ? "NONE(no ship is registered in this world)"
                                : "NONE(the crossed ship is not registered here)")
                        : "[" + (int) yard.minX + ".." + (int) yard.maxX + "]x["
                                + (int) yard.minZ + ".." + (int) yard.maxZ + "]")
                // What a POSITION lookup would have answered, always — it is the difference between
                // the two that says "we were asking about the wrong ship", and reconstructing it
                // afterwards took a scan of the world's region files.
                .append(" nearestToAnchor=").append(VSIntegration.describeShipAt(world,
                        anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5))
                .append(" seatsReached=").append(seats.size())
                .append(" crew=").append(crew.size())
                .append(" wantShip=").append(expectedShipId);
        if (!crew.isEmpty()) {
            Crew first = crew.get(0);
            sb.append(" wantLink=").append(first.afcDx).append(',').append(first.afcDy)
                    .append(',').append(first.afcDz);
        }
        if (seats.isEmpty()) {
            sb.append(" | no pilot-seat tile is reachable in the shipyard box or within ")
                    .append((int) RIDER_RANGE).append(" blocks of the arrival point");
            return sb.toString();
        }
        int shown = 0;
        for (TilePilotSeat seat : seats) {
            if (shown++ == 3) {
                sb.append(" | ...").append(seats.size() - 3).append(" more");
                break;
            }
            BlockPos p = seat.getPos();
            BlockPos afc = seat.getFlightComputerPos();
            double[] seatWorld = VSIntegration.getRegisteredSeatWorldPosition(world, p);
            sb.append(" | seat@").append(p.getX()).append(',').append(p.getY()).append(',')
                    .append(p.getZ())
                    .append(" link=").append(afc == null ? "UNSET"
                            : (afc.getX() - p.getX()) + "," + (afc.getY() - p.getY()) + ","
                                    + (afc.getZ() - p.getZ()))
                    .append(" ship=").append(DURABLE_SHIP_ID.apply(seat))
                    .append(" world=").append(seatWorld == null ? "UNRESOLVED"
                            : "ok");
        }
        return sb.toString();
    }

    /**
     * Re-express a PRE-ASSEMBLY boarding across the assembly relocation. A pilot who took the seat
     * while his craft was still loose blocks is riding a mount bound to the seat's build-time world
     * position; assembly cuts those blocks and relocates them into the ship's subspace, so the
     * binding names vacated coordinates and nothing in the control chain resolves - the piloting
     * client never even sends. Once the relocated seat (re-identified by its AFC-link offset, the
     * one relocation-invariant identity) is managed by a live ship, this atomically swaps the stale
     * mount for a freshly-bound one - the same mount recipe every other boarding path ends in, so
     * the pilot keeps his seat with no re-click.
     *
     * <p>The caller owns retry and give-up policy, so the return is a tri-state: the swap
     * happened; the relocated seat is not resolvable yet (relocation is asynchronous - retry next
     * tick); or the pilot is not riding the recorded stale mount THIS TICK. The last one is
     * deliberately not treated as final here: a single such observation can be a transient read
     * during entity churn, and cancelling on it once left a pilot permanently on his stale mount -
     * the caller debounces it over consecutive ticks before letting the entry go.</p>
     */
    public enum RebindOutcome { REBOUND, NOT_READY, NOT_ON_STALE_MOUNT }

    public static RebindOutcome rebindAcrossAssembly(WorldServer world, BlockPos anchor,
            EntityPlayerMP player, int staleDummyId, int afcDx, int afcDy, int afcDz,
            java.util.UUID expectedShipId) {
        if (player.hasDisconnected() || player.world != world) {
            return RebindOutcome.NOT_ON_STALE_MOUNT; // gone from this world; login-restore owns him
        }
        Entity riding = player.getRidingEntity();
        if (!(riding instanceof EntityDummy) || riding.getEntityId() != staleDummyId) {
            return RebindOutcome.NOT_ON_STALE_MOUNT; // stood up / re-seated - never force him back
        }
        // No crossing identity here: a stale-mount rebind is driven by the player's own position,
        // not by a crossing that knows which ship it created.
        TilePilotSeat seat = matchSeat(seatsOfShipAt(world, anchor, null),
                new Crew(player, afcDx, afcDy, afcDz), expectedShipId, DURABLE_SHIP_ID);
        // getSeatWorldPosition is non-null only for a block MANAGED by a live ship, so a seat tile
        // still sitting at the paste site (relocation unfinished) does not pass - rebinding to it
        // would just go stale again the moment the blocks move.
        double[] seatWorld = seat == null
                ? null : VSIntegration.getSeatWorldPosition(world, seat.getPos());
        if (seat == null || seatWorld == null) {
            return RebindOutcome.NOT_READY; // ship not up yet - retry
        }
        // Atomic swap, one tick: a mechanical dismount (not the player leaving his post - the
        // aboard record must survive it, and does: it is re-derived from state, and the state one
        // tick later is "seated on the relocated seat"), the stale mount retired, then the standard
        // mount recipe on the seat's current subspace binding.
        player.dismountRidingEntity();
        riding.setDead();
        player.setPositionAndUpdate(seatWorld[0], seatWorld[1], seatWorld[2]);
        EntityDummy dummy = boundDummyForMount(world, seat.getPos(),
                seatWorld[0], seatWorld[1], seatWorld[2]);
        if (dummy == null) {
            return RebindOutcome.NOT_ON_STALE_MOUNT; // seat taken while he rode the stale mount
        }
        player.startRiding(dummy, true);
        ArrivalTrace.server("rebind.swap t=" + world.getTotalWorldTime()
                + " p=" + player.getEntityId() + " stale=" + staleDummyId
                + " dummy=" + dummy.getEntityId() + " toY=" + ArrivalTrace.fmt(seatWorld[1]));
        return RebindOutcome.REBOUND;
    }

    /** Every pilot-seat tile of the ship at {@code anchor}, found over its subspace shipyard. */
    /**
     * The shipyard box to scan for {@code vsShipUuid}'s seats: that ship's own claim when the caller
     * knows which ship it means, and only otherwise the ship nearest to {@code anchor}.
     *
     * <p>The distinction decides whether an arrival works. A position lookup answers for the CLOSEST
     * registered ship with no distance bound, so in a destination that already holds another craft
     * the arrival scans the stranger's shipyard — no pilot seat in it, ever — while the ship that
     * crossed sits elsewhere in the same world with the crew's own seat aboard. Measured on a failed
     * entry to orbit: the two shipyards were 51,200 blocks apart in one world.</p>
     */
    private static AxisAlignedBB yardOfTheShipWeMean(WorldServer world, BlockPos anchor,
            java.util.UUID vsShipUuid) {
        if (vsShipUuid != null) {
            return VSIntegration.shipyardBoundsOf(world, vsShipUuid);
        }
        return VSIntegration.shipyardBoundsAt(world,
                anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
    }

    /**
     * How many pilot-seat tiles the arrival's own seat lookup reaches for {@code vsShipUuid} at
     * {@code anchor} — the exact scan {@link #reseat} runs, exposed so a test can ask it directly
     * instead of inferring it from a crossing that either completed or did not. A {@code null} uuid
     * reproduces the position-keyed lookup.
     */
    public static int countSeatsOfShip(WorldServer world, BlockPos anchor,
            java.util.UUID vsShipUuid) {
        return seatsOfShipAt(world, anchor, vsShipUuid).size();
    }

    private static List<TilePilotSeat> seatsOfShipAt(WorldServer world, BlockPos anchor,
            java.util.UUID vsShipUuid) {
        List<TilePilotSeat> seats = new ArrayList<>();
        AxisAlignedBB yard = yardOfTheShipWeMean(world, anchor, vsShipUuid);
        forceLoadYard(world, yard);
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TilePilotSeat)) {
                continue;
            }
            BlockPos p = te.getPos();
            // Before relocation finishes the seat may still sit at the paste site (near the
            // anchor); after it, inside the shipyard box. Accept both while the ship settles.
            boolean inYard = yard != null && yard.contains(new net.minecraft.util.math.Vec3d(
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            boolean nearAnchor = p.distanceSq(anchor) <= RIDER_RANGE * RIDER_RANGE;
            if (inYard || nearAnchor) {
                seats.add((TilePilotSeat) te);
            }
        }
        return seats;
    }

    /**
     * Force-load {@code yard}'s chunks before anything reads the world's tile list over it — the
     * same force-load-then-scan idiom {@code shipBlockAt}/{@code flightComputerAt} use. Unloading a
     * ship queues its shipyard chunks for unload, and a tile in an unloaded chunk is absent from
     * {@code loadedTileEntityList}, so without this a scan silently finds nothing on exactly the
     * ship that has nobody near it. Loading a CHUNK is not loading the ship: it costs no physics
     * object and does not fight VS's own load policy.
     */
    private static void forceLoadYard(WorldServer world, AxisAlignedBB yard) {
        if (yard == null) {
            return;
        }
        for (int cx = ((int) yard.minX) >> 4; cx <= (((int) yard.maxX) >> 4); cx++) {
            for (int cz = ((int) yard.minZ) >> 4; cz <= (((int) yard.maxZ) >> 4); cz++) {
                world.getChunkProvider().provideChunk(cx, cz);
            }
        }
    }

    /**
     * The durable ship id a seat belongs to: its linked flight computer tile's persisted UUID, or
     * {@code null} while the computer tile is not resolvable (ship still assembling — the caller's
     * retry loop covers that) or has never minted one.
     */
    static final java.util.function.Function<TilePilotSeat, java.util.UUID> DURABLE_SHIP_ID =
            seat -> {
                zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer afc =
                        seat.getFlightComputer();
                return afc == null ? null : afc.shipIdOrNull();
            };

    /**
     * The seat whose AFC-link offset matches {@code rider}'s record — and, when
     * {@code expectedShipId} is given, whose ship (per {@code shipIdOf}) IS that ship — or
     * {@code null}. The offset alone is a weak identity: any two ships built from the same design
     * share it, and the candidate list is gathered by spatial proximity, so without the id check a
     * neighbouring ship's seat can win. A candidate whose id cannot be resolved yet does not match
     * (the callers retry until the destination's tiles are up). Public with the resolver
     * injected so the discrimination is testable without a world.
     */
    public static TilePilotSeat matchSeat(List<TilePilotSeat> seats, Crew rider,
            java.util.UUID expectedShipId,
            java.util.function.Function<TilePilotSeat, java.util.UUID> shipIdOf) {
        for (TilePilotSeat seat : seats) {
            BlockPos afc = seat.getFlightComputerPos();
            if (afc == null) {
                continue;
            }
            BlockPos p = seat.getPos();
            if (afc.getX() - p.getX() != rider.afcDx
                    || afc.getY() - p.getY() != rider.afcDy
                    || afc.getZ() - p.getZ() != rider.afcDz) {
                continue;
            }
            if (expectedShipId != null && !expectedShipId.equals(shipIdOf.apply(seat))) {
                continue; // same design, different craft — never cross-seat
            }
            return seat;
        }
        return null;
    }

    /**
     * The seat's single mount dummy, ready to be ridden: reuse the one already bound to
     * {@code seatPos} (moved to the seat's live world position), or spawn a fresh bound one there.
     * Returns {@code null} when the existing dummy is occupied — the caller must never mount a
     * second rider onto a taken seat, and must never spawn a second dummy beside it.
     */
    private static EntityDummy boundDummyForMount(WorldServer world, BlockPos seatPos,
            double x, double y, double z) {
        EntityDummy existing =
                zmaster587.advancedRocketry.block.BlockPilotSeat.boundDummyAt(world, seatPos);
        if (existing != null) {
            if (!existing.getPassengers().isEmpty()) {
                return null;
            }
            existing.setPosition(x, y, z);
            return existing;
        }
        EntityDummy dummy = new EntityDummy(world, x, y, z);
        dummy.setSeatPos(seatPos);
        world.spawnEntity(dummy);
        return dummy;
    }
}
