package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.navigation.TargetPrediction;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a navigation computer promises when it aims at a moving destination: the ship is aimed at
 * where the body WILL BE when the flight ends, never at where it is when the pilot presses the
 * button. Pure — no world, no registry, no drive.
 *
 * <p>What moves is the POINT, not the cell: a body's cell name is durable (C15 ADDR-1), so the
 * arrival cell equals the aimed cell at every tick and needs no projection at all (ADDR-14). The
 * body slides inside its cell, and the cell's own frame slides through space, and both of those are
 * what the aim and its price have to follow.</p>
 */
public class TargetPredictionTest {

    private static final int BODY = 7;
    private static final GalacticCoord ORIGIN = GalacticCoord.ORIGIN;
    /** The body's one, eternal name. Nothing below may change it. */
    private static final GalacticCoord BODY_CELL = GalacticCoord.ofSectorLocal(50, 0, 0, 0, 0, 0);

    /**
     * A body that slides {@code blocksPerTick} along +X INSIDE its own cell — a moon's own motion.
     * Its name never changes, which is the model.
     */
    private static TargetPrediction.Ephemeris slidingInsideItsCell(final long blocksPerTick) {
        return new TargetPrediction.Ephemeris() {
            @Override
            public GalacticCoord addressAt(int dimId, long worldTick) {
                return dimId != BODY ? null
                        : BODY_CELL.plusLocalSaturating(worldTick * blocksPerTick, 0L, 0L);
            }
        };
    }

    /** The body's CELL rides its primary: its frame origin walks +X at {@code blocksPerTick}. */
    private static CellFrames frameMovingAt(final long blocksPerTick) {
        return new CellFrames() {
            @Override
            public AbsolutePos originAt(GalacticCoord name, long tick) {
                AbsolutePos base = AbsolutePos.ofCellName(name);
                return name.sameCell(BODY_CELL) ? base.plus(tick * blocksPerTick, 0L, 0L) : base;
            }
        };
    }

    /** A flight of a fixed duration, whatever the distance — isolates the ephemeris from the pricing. */
    private static TargetPrediction.Flight lasting(final long ticks) {
        return new TargetPrediction.Flight() {
            @Override
            public long ticksFor(double distanceBlocks) {
                return ticks;
            }
        };
    }

    @Test
    public void theAimIsWhereTheBodyWillBeWhenTheFlightEnds() {
        TargetPrediction.Ephemeris body = slidingInsideItsCell(1_000L);
        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 100L, body, lasting(40L),
                CellFrames.STATIC);

        assertNotNull(aim);
        // NOT where the body is at tick 100 — where it is at 140, when the ship comes out.
        // This is the whole mechanic: a ship aimed at "now" arrives where the body was.
        assertEquals("the aim must lead the body by the flight time",
                body.addressAt(BODY, 140L), aim);
        assertNotEquals("aiming at the present position is the defect this exists to prevent",
                body.addressAt(BODY, 100L), aim);
    }

    /**
     * ADDR-14. The cell is the destination and the cell is durable, so no amount of leading may
     * change which cell the ship is aimed at — the pilot chose a body, not a place the body passes.
     */
    @Test
    public void theAimedCellIsTheBodysDurableNameWhateverTheFlightCosts() {
        for (long flightTicks : new long[]{0L, 40L, 100_000L}) {
            GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 100L,
                    slidingInsideItsCell(1_000L), lasting(flightTicks), frameMovingAt(50_000L));
            assertNotNull(aim);
            assertEquals("a flight of " + flightTicks + " ticks renamed the destination",
                    BODY_CELL.cellKey(), aim.cellKey());
        }
    }

    @Test
    public void aStationaryBodyIsAimedAtDirectly() {
        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 100L, slidingInsideItsCell(0L),
                lasting(40L), CellFrames.STATIC);

        assertNotNull(aim);
        assertEquals("nothing to lead: the body is where it is", BODY_CELL, aim);
    }

    /**
     * The circular part: a further aim costs a longer flight, and a longer flight moves the body
     * further still. The frame term is what dominates it — over a jump a destination's cell travels
     * far more than the body travels inside that cell — so the price has to be taken through the
     * frames or the iteration is pricing a galaxy that does not move.
     */
    @Test
    public void theAimSettlesEvenThoughFurtherMeansLonger() {
        TargetPrediction.Flight proportional = new TargetPrediction.Flight() {
            @Override
            public long ticksFor(double distanceBlocks) {
                return (long) (distanceBlocks / 1_000_000d);
            }
        };
        TargetPrediction.Ephemeris body = slidingInsideItsCell(1_000L);

        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 10L, body, proportional,
                frameMovingAt(100_000L));

        assertNotNull(aim);
        assertEquals("the destination is still the body's own cell", BODY_CELL.cellKey(), aim.cellKey());
        assertTrue("the aim must lead the body's present point, not sit on it",
                aim.localX() > body.addressAt(BODY, 10L).localX());
    }

    /**
     * The pricing reads the frames. Without this the iteration converges on pass one against a
     * static grid and the ship is aimed at a rendezvous the destination left minutes ago — which is
     * exactly what the old {@code sameCell} convergence test did once names became durable.
     */
    @Test
    public void aMovingFrameChangesTheAnswer() {
        TargetPrediction.Flight proportional = new TargetPrediction.Flight() {
            @Override
            public long ticksFor(double distanceBlocks) {
                return (long) (distanceBlocks / 1_000_000d);
            }
        };
        TargetPrediction.Ephemeris body = slidingInsideItsCell(1_000L);

        GalacticCoord still = TargetPrediction.aimAt(BODY, ORIGIN, 10L, body, proportional,
                CellFrames.STATIC);
        GalacticCoord receding = TargetPrediction.aimAt(BODY, ORIGIN, 10L, body, proportional,
                frameMovingAt(1_000_000L));

        assertNotNull(still);
        assertNotNull(receding);
        assertTrue("a receding destination is a longer flight, so a further-led aim",
                receding.localX() > still.localX());
    }

    @Test
    public void aBodyThatCannotBeFoundIsNotAimedAtAtAll() {
        // The caller must be able to tell "I do not know where this is" from "it is at the origin".
        // Answering with a coordinate would put a ship on course for one.
        assertNull(TargetPrediction.aimAt(999, ORIGIN, 0L, slidingInsideItsCell(1_000L),
                lasting(10L), CellFrames.STATIC));
    }

    @Test
    public void aShipWithNoRecordedPositionStillGetsTheBodysPresentAddress() {
        // Nothing to price a flight from, so nothing to lead by. Such a ship is refused by the gate
        // long before it flies; what matters here is that it does not come back "target unknown".
        TargetPrediction.Ephemeris body = slidingInsideItsCell(1_000L);
        GalacticCoord aim = TargetPrediction.aimAt(BODY, null, 100L, body, lasting(40L),
                CellFrames.STATIC);

        assertNotNull(aim);
        assertEquals(body.addressAt(BODY, 100L), aim);
    }
}
