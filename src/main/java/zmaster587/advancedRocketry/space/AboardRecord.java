package zmaster587.advancedRocketry.space;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * The <b>one writer</b> of a player's durable aboard record ({@link ShipAboardTag}).
 *
 * <h2>Why exactly one</h2>
 *
 * There used to be two independent durable records of "this player is aboard a ship", written by
 * two subsystems with no stated authority between them, and a third half-writer clearing one of
 * them on every dismount. That is what sent a crew member who stood up in orbit to an ordinary
 * spawn: one record said "not aboard" because he had left his seat, while the other still knew
 * exactly where on the deck he was standing. Everything that changes the record now goes through
 * {@link #reconcile}, so there is one derivation to be right or wrong about.
 *
 * <h2>Derived from state, not from transitions</h2>
 *
 * The record is computed from where the player IS — seated on a ship's seat, or resolved on its
 * deck — rather than accumulated from mount/dismount events. A transition-driven record misses
 * every route nobody hooked: the pilot who sits down on the planet and flies up crossed into space
 * without any event firing in a cell, and was then a player in orbit with no evidence that he was
 * aboard anything. Deriving it also makes the writer idempotent and self-healing: a record that
 * went stale while the ship moved under another pilot is corrected on the next pass.
 *
 * <h2>Cadence</h2>
 *
 * Refreshed at most once a second, plus at logout — the event that immediately precedes the save
 * whose data a login reads back. (Forge fires the logout event before the player file is written;
 * a world save, by contrast, writes player data BEFORE any world-save hook runs, so there is no
 * usable pre-write hook there and the one-second bound is what covers a crash.) In between, a
 * record can be up to a second stale in memory, which nothing observes: the only readers are the
 * login restore and the deck hold, and both run after a save.
 *
 * <p>Both triggers deliberately run OUTSIDE the world's entity tick — the end-of-server-tick pass
 * and the logout event. The clear below has to look a ship up over the world's tile entities, and
 * that list is not safe to walk from inside the tick that mutates it. Nothing calls in from a
 * movement path for the same reason.</p>
 *
 * <h2>Clearing needs positive evidence</h2>
 *
 * "Not aboard right now" is not enough to drop a record: a crossing lifts its crew out of their
 * seats for as long as it takes to cut, move and rebuild the ship, and during that window every
 * seat and every deck honestly answers "he is not on me". Silence from an absent ship is not
 * evidence about the player. The record is therefore dropped only when the ship it names is
 * present in the player's world and he is demonstrably not aboard it.
 *
 * <p>Server main thread only; every method is a safe no-op off the server side.</p>
 */
public final class AboardRecord {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    private AboardRecord() { }

    /**
     * Bring {@code entity}'s durable record into line with where he actually is: stamp it when he
     * is aboard and the record would change, drop it when he demonstrably is not, and leave it
     * alone whenever the answer cannot be established.
     *
     * <p>The three outcomes are deliberately not two. "Cannot be established" covers a ship that is
     * mid-crossing, not loaded, or in another dimension — and treating that as "not aboard" would
     * erase the binding that puts the crew back on the far side of a jump.</p>
     */
    public static void reconcile(Entity entity) {
        if (!(entity instanceof EntityPlayerMP) || entity instanceof FakePlayer
                || entity.world == null || entity.world.isRemote) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) entity;
        ShipAboardTag.Aboard existing = ShipAboardTag.of(player);
        ShipAboardTag.Aboard derived = derive(player);
        if (derived != null) {
            if (!derived.equals(existing)) {
                ShipAboardTag.stamp(player, derived);
                LOGGER.info("[SPACE] aboard record {} for {}: ship {} {} at {}",
                        existing == null ? "stamped" : "refreshed", player.getName(),
                        derived.shipId, derived.posture,
                        derived.coord == null ? "no cell" : derived.coord.cellKey());
            }
            return;
        }
        if (existing == null) {
            return;
        }
        if (isAboardSomething(player)) {
            return; // aboard, but the ship cannot express the record yet - keep what we have
        }
        if (ShipRelativePoint.flightComputerOfDurableShip(player.world, existing.shipId) == null) {
            return; // his ship is not here to be judged by: silence, not evidence
        }
        ShipAboardTag.clear(player);
        LOGGER.info("[SPACE] aboard record dropped for {}: ship {} is here and he is not on it",
                player.getName(), existing.shipId);
    }

    /**
     * The record {@code player}'s current state warrants, or {@code null} when he is not aboard a
     * tier-2 ship <i>or</i> the ship cannot answer for him right now (its computer is unloaded, or
     * it never minted a durable id). The two are told apart by {@link #isAboardSomething}, never by
     * this method's {@code null}.
     */
    private static ShipAboardTag.Aboard derive(EntityPlayer player) {
        World world = player.world;
        GalacticCoord presence = presenceOf(world);
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityDummy) {
            // A crew member SEATED on a ship that is in no cell needs no record: vanilla brings a
            // seated player back on his own mount, and there is no dimension question to answer for
            // a ship parked on a planet. The record answers exactly two questions - which world he
            // belongs in (which needs a cell) and where on a deck to put a BODY back (which needs
            // him to be on his feet) - and a planet-side seat asks neither.
            return presence == null
                    ? null : seatedRecord(world, ((EntityDummy) riding).getSeatPos(), presence);
        }
        return standingRecord(world, player, presence);
    }

    /**
     * A seated crew member's record: the ship, and the seat identified by its flight-computer link
     * offset — the one binding that survives the ship being re-assembled into a fresh subspace.
     */
    private static ShipAboardTag.Aboard seatedRecord(World world, BlockPos seatPos,
                                                     GalacticCoord presence) {
        if (seatPos == null) {
            return null;
        }
        TileEntity seatTile = world.getTileEntity(seatPos);
        if (!(seatTile instanceof TilePilotSeat)) {
            return null;
        }
        BlockPos afcPos = ((TilePilotSeat) seatTile).getFlightComputerPos();
        UUID shipId = durableShipIdAt(world, afcPos);
        if (shipId == null) {
            return null; // an unlinked seat, or a computer that never minted an id
        }
        return new ShipAboardTag.Aboard(shipId, presence,
                afcPos.getX() - seatPos.getX(),
                afcPos.getY() - seatPos.getY(),
                afcPos.getZ() - seatPos.getZ());
    }

    /**
     * A crew member on his feet: the ship, and the deck point he stands on, expressed against the
     * flight computer by {@link ShipRelativePoint}.
     *
     * <p>The offset itself is derived by {@link ShipRelativePoint#deckOffsetOfAboardBody} — the one
     * definition of "where on this ship is this body", shared with the crossing that carries a crew
     * member on his feet. Two copies of that derivation would be two chances to disagree about the
     * same player.</p>
     */
    private static ShipAboardTag.Aboard standingRecord(World world, EntityPlayer player,
                                                       GalacticCoord presence) {
        String vsShipId = ShipFrameTravel.aboardShipId(player);
        if (vsShipId == null) {
            return null;
        }
        BlockPos afcPos = ShipRelativePoint.flightComputerOfShip(world, vsShipId);
        UUID shipId = durableShipIdAt(world, afcPos);
        if (shipId == null) {
            return null;
        }
        double[] offset = ShipRelativePoint.deckOffsetOfAboardBody(world, player, afcPos);
        if (offset == null) {
            return null;
        }
        return ShipAboardTag.Aboard.standing(shipId, presence, offset[0], offset[1], offset[2]);
    }

    /**
     * Whether {@code player} is aboard a ship AT ALL — riding a ship's seat, or resolved on a deck.
     * This is the question the clear consults; it must stay independent of whether the record can
     * currently be expressed, which is the whole point of keeping it apart from {@link #derive}.
     */
    private static boolean isAboardSomething(EntityPlayer player) {
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityDummy && ((EntityDummy) riding).getSeatPos() != null) {
            return true;
        }
        return ShipFrameTravel.aboardShipId(player) != null;
    }

    /** The cell {@code world} is, or {@code null} for any world that is not a space cell — a
     *  planet, or the shared hyperspace parking world. */
    private static GalacticCoord presenceOf(World world) {
        if (!(world.provider instanceof WorldProviderSpaceSlot)) {
            return null;
        }
        return GalacticCoord.fromCellKey(SpaceSlotPool.cellKeyFor(world.provider.getDimension()));
    }

    /** The durable ship id of the flight computer at subspace {@code afcPos}, or {@code null}. */
    private static UUID durableShipIdAt(World world, BlockPos afcPos) {
        if (afcPos == null) {
            return null;
        }
        TileEntity afcTile = world.getTileEntity(afcPos);
        return afcTile instanceof TileAdvancedFlightComputer
                ? ((TileAdvancedFlightComputer) afcTile).shipIdOrNull() : null;
    }
}
