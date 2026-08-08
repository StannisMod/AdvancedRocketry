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
 *   <li>{@link #capture}: BEFORE the cut — enumerate the seated crew at the ship's live world
 *       position (riders live in the WORLD frame, never in the shipyard box), record each rider
 *       against its seat's AFC-link offset (the one binding that IS invariant under re-assembly),
 *       and retire the old dummies (their seat blocks are about to stop existing).</li>
 *   <li>{@link #reseat}: AFTER re-assembly — find each seat's NEW subspace position by matching
 *       the recorded link offset over the fresh ship's seat tiles, transfer the rider into the
 *       destination world, and re-mount it on a freshly-bound dummy (the
 *       {@code BlockPilotSeat} mount recipe).</li>
 * </ol>
 *
 * <p>Scope: SEATED crew (a pilot on a linked pilot seat). Walking crew waits on the extreme-pose
 * collision fix. Server main thread only.</p>
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

    /** One captured rider: the player and the seat's AFC-link offset that re-identifies its seat
     *  on the re-assembled ship (relative offsets survive the rigid relocation; absolute subspace
     *  coordinates do not). */
    public static final class Crew {
        public final EntityPlayerMP player;
        public final int afcDx, afcDy, afcDz;

        /** Whether this rider has already been told his seat is held by someone else — the reseat
         *  retries every tick until the whole crew resolves, and the message must not repeat. */
        boolean seatLostNotified;

        public Crew(EntityPlayerMP player, int afcDx, int afcDy, int afcDz) {
            this.player = player;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
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
        return crew;
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
            java.util.UUID expectedShipId) {
        if (crew.isEmpty()) {
            return true;
        }
        List<TilePilotSeat> seats = seatsOfShipAt(dstWorld, anchor);
        boolean allSeated = true;
        for (Crew rider : crew) {
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
            EntityPlayerMP player = rider.player;
            if (player.hasDisconnected()) {
                // A rider who RELOGGED mid-crossing: the captured reference is the pre-relog
                // entity, replaced wholesale by his fresh login. Re-resolve by UUID — the durable
                // identity — so the arrival still seats the RETURNED player (a mid-transit relog
                // must hand control back on arrival). Genuinely-offline crew stays skipped: the
                // login restore owns whoever comes back after the crossing is over.
                EntityPlayerMP fresh = player.getServer() == null ? null
                        : player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID());
                if (fresh == null || fresh.hasDisconnected()) {
                    continue;
                }
                player = fresh;
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
                expectedShipId);
        return allSeated;
    }

    /**
     * One line naming the step at which the re-seat's seat lookup stopped: whether any ship claims
     * the arrival point at all, how many seat tiles the scan reached, and for each of them the three
     * things {@link #matchSeat} discriminates on (its AFC link offset, the durable ship id behind
     * that link, and whether its world position resolves). Built only on the failing path.
     */
    private static String describeReseatBlock(WorldServer world, BlockPos anchor,
            List<TilePilotSeat> seats, List<Crew> crew, java.util.UUID expectedShipId) {
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(world, anchor.getX() + 0.5,
                anchor.getY() + 0.5, anchor.getZ() + 0.5);
        StringBuilder sb = new StringBuilder(320);
        sb.append("anchor=").append(anchor.getX()).append(',').append(anchor.getY()).append(',')
                .append(anchor.getZ())
                .append(" yard=")
                .append(yard == null ? "NONE(no ship claims the arrival point)"
                        : "[" + (int) yard.minX + ".." + (int) yard.maxX + "]x["
                                + (int) yard.minZ + ".." + (int) yard.maxZ + "]")
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
        TilePilotSeat seat = matchSeat(seatsOfShipAt(world, anchor),
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
    private static List<TilePilotSeat> seatsOfShipAt(WorldServer world, BlockPos anchor) {
        List<TilePilotSeat> seats = new ArrayList<>();
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(world,
                anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        // Force-load the shipyard's chunks before reading the world's tile list — the same
        // force-load-then-scan idiom shipBlockAt/flightComputerAt use. Unloading a ship queues its
        // shipyard chunks for unload, and a seat tile in an unloaded chunk is absent from
        // loadedTileEntityList, so without this the scan silently finds nothing on exactly the ship
        // that has nobody near it. Loading a CHUNK is not loading the ship: it costs no physics
        // object and does not fight VS's own load policy.
        if (yard != null) {
            for (int cx = ((int) yard.minX) >> 4; cx <= (((int) yard.maxX) >> 4); cx++) {
                for (int cz = ((int) yard.minZ) >> 4; cz <= (((int) yard.maxZ) >> 4); cz++) {
                    world.getChunkProvider().provideChunk(cx, cz);
                }
            }
        }
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
