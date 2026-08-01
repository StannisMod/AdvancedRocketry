package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.StandoffRing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for where a craft is put down NEAR something.
 *
 * <p>Three properties are the contract and the radii are not: a placement clears what it was told to
 * stand off from, a placement never leaves the anchor's cell, and a placement with nothing to stand
 * off from does not move the destination at all. The ring distance itself is {@code tunable} and is
 * deliberately never pinned to a number.</p>
 */
public class StandoffRingTest {

    private static final long RING = 1024L;
    private static final long CLEARANCE = 512L;

    private static GalacticCoord at(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    /**
     * The point of the whole class: a craft placed beside a body is far enough from it that whatever
     * proximity rule made the caller ask for a standoff does not immediately fire.
     */
    @Test
    public void aPlacementClearsWhatItStandsOffFrom() {
        GalacticCoord body = at(12, 0, -4, 0, 0, 0);
        GalacticCoord placed = StandoffRing.standoffFrom(body, Collections.singletonList(body),
                RING, CLEARANCE, 0x5A);

        assertTrue("the placement must clear the body it was standing off from",
                placed.distanceTo(body) >= CLEARANCE);
    }

    /**
     * A placement may not rename the cell. An offset that carries into the next sector puts the craft
     * in a cell nobody materialized, nobody bound to a slot world and nobody told the ledger about —
     * so at a cell face the ring gives up distance, never identity.
     */
    @Test
    public void aRingAtTheCellFaceKeepsTheCellName() {
        GalacticCoord atFace = at(5, 1, -9, GalacticCoord.HALF_CELL - 1L, 0L, GalacticCoord.HALF_CELL - 1L);

        for (int seed = 0; seed < 256; seed += 7) {
            GalacticCoord placed = StandoffRing.pointAround(atFace, RING, seed);
            assertEquals("a ring around a body at its cell's face stays in that cell (seed " + seed + ")",
                    atFace.cellKey(), placed.cellKey());
        }
    }

    /** The same bound, through the clearance-seeking entry point. */
    @Test
    public void aStandoffAtTheCellFaceKeepsTheCellName() {
        GalacticCoord atFace = at(-2, 0, 3, -GalacticCoord.HALF_CELL, 0L, -GalacticCoord.HALF_CELL);
        GalacticCoord placed = StandoffRing.standoffFrom(atFace, Collections.singletonList(atFace),
                RING, CLEARANCE, 0x11);

        assertEquals("a standoff at a cell face stays in that cell", atFace.cellKey(), placed.cellKey());
    }

    /**
     * Nothing to stand off from means nothing to move. A jump to a bare coordinate is a destination
     * the pilot CHOSE rather than one derived from a body, and displacing it would put the ship
     * somewhere he did not ask for, for no reason — no proximity rule can fire where no body is.
     */
    @Test
    public void anEmptyCellLeavesTheDestinationExactlyWhereItWasAimed() {
        GalacticCoord aim = at(700, -3, 21, 1234L, -99L, 4321L);

        assertSame("an empty occupant list must not displace the aim",
                aim, StandoffRing.standoffFrom(aim, new ArrayList<GalacticCoord>(), RING, CLEARANCE, 3));
        assertSame("a null occupant list must not displace the aim either",
                aim, StandoffRing.standoffFrom(aim, null, RING, CLEARANCE, 3));
    }

    /**
     * The placement is never FURTHER out than it was asked for. A caller that also uses the radius as
     * a threshold — the survey tier is exactly the entry ring today — would otherwise land on one
     * side of its own boundary or the other depending on the bearing, which reads as a flickering
     * readout with no cause.
     */
    @Test
    public void aPlacementIsNeverFurtherOutThanTheRadiusAsked() {
        GalacticCoord anchor = at(1, 1, 1, 0, 0, 0);

        for (int seed = 0; seed < 256; seed++) {
            GalacticCoord placed = StandoffRing.pointAround(anchor, RING, seed);
            assertTrue("bearing " + seed + " overshot the ring: " + placed.distanceTo(anchor),
                    placed.distanceTo(anchor) <= RING);
        }
    }

    /**
     * Two craft placed at one body in the same tick do not share a point. Not a fairness nicety: two
     * ships pasted at identical coordinates are two ships in the same blocks.
     */
    @Test
    public void twoCraftAtOneBodyGetDifferentPoints() {
        GalacticCoord body = at(9, 0, 9, 0, 0, 0);

        assertTrue("different seeds must give different points",
                !StandoffRing.pointAround(body, RING, 0x10)
                        .equals(StandoffRing.pointAround(body, RING, 0x90)));
    }

    /**
     * With several bodies in one cell the placement takes the bearing that clears them best. A cell
     * holding a planet and one of its moons is the ordinary case, and clearing only the body that was
     * aimed at would leave the craft sitting on the other one.
     */
    @Test
    public void aPlacementPrefersTheBearingThatClearsEveryOccupant() {
        GalacticCoord aim = at(4, 0, 4, 0, 0, 0);
        List<GalacticCoord> occupied = Arrays.asList(aim, aim.plusLocalSaturating(900L, 0L, 0L));

        GalacticCoord placed = StandoffRing.standoffFrom(aim, occupied, RING, CLEARANCE, 0x2C);

        for (GalacticCoord occupant : occupied) {
            assertTrue("the placement must clear every occupant, not only the one aimed at",
                    placed.distanceTo(occupant) >= CLEARANCE);
        }
    }
}
