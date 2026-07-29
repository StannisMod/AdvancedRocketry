package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.navigation.TargetPrediction;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a navigation computer promises when it aims at a moving destination: the ship is aimed at
 * where the body WILL BE when the flight ends, never at where it is when the pilot presses the
 * button. Pure — no world, no registry, no drive.
 */
public class TargetPredictionTest {

    private static final int BODY = 7;
    private static final GalacticCoord ORIGIN = GalacticCoord.ORIGIN;

    /** A body that walks one cell along +X per tick — fast, so a wrong aim is unmissable. */
    private static TargetPrediction.Ephemeris marching(final long cellsPerTick) {
        return new TargetPrediction.Ephemeris() {
            @Override
            public GalacticCoord cellAt(int dimId, long worldTick) {
                return dimId != BODY ? null
                        : GalacticCoord.ofSectorLocal(worldTick * cellsPerTick, 0L, 0L, 0L, 0L, 0L);
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
        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 100L, marching(1L), lasting(40L));

        assertNotNull(aim);
        // NOT sector 100 (where the body is now) — sector 140, where it is when the ship arrives.
        // This is the whole mechanic: a ship aimed at "now" lands where the planet was.
        assertEquals("the aim must lead the body by the flight time", 140L, aim.sectorX());
    }

    @Test
    public void aStationaryBodyIsAimedAtDirectly() {
        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 100L, marching(0L), lasting(40L));

        assertNotNull(aim);
        assertEquals("nothing to lead: the body is where it is", 0L, aim.sectorX());
    }

    @Test
    public void theAimSettlesEvenThoughFurtherMeansLonger() {
        // The circular part: a further aim costs a longer flight, and a longer flight moves the body
        // further still. Priced proportionally to distance, the passes chase each other — the answer
        // must still come back bounded and ahead of the body's present position.
        TargetPrediction.Flight proportional = new TargetPrediction.Flight() {
            @Override
            public long ticksFor(double distanceBlocks) {
                return (long) (distanceBlocks / GalacticCoord.CELL);
            }
        };

        GalacticCoord aim = TargetPrediction.aimAt(BODY, ORIGIN, 10L, marching(1L), proportional);

        assertNotNull(aim);
        assertTrue("the aim must lead the body's present cell, not sit on it", aim.sectorX() > 10L);
    }

    @Test
    public void aBodyThatCannotBeFoundIsNotAimedAtAtAll() {
        // The caller must be able to tell "I do not know where this is" from "it is at the origin".
        // Answering with a coordinate would put a ship on course for one.
        assertNull(TargetPrediction.aimAt(999, ORIGIN, 0L, marching(1L), lasting(10L)));
    }

    @Test
    public void aShipWithNoRecordedPositionStillGetsTheBodysPresentCell() {
        // Nothing to price a flight from, so nothing to lead by. Such a ship is refused by the gate
        // long before it flies; what matters here is that it does not come back "target unknown".
        GalacticCoord aim = TargetPrediction.aimAt(BODY, null, 100L, marching(1L), lasting(40L));

        assertNotNull(aim);
        assertEquals(100L, aim.sectorX());
    }
}
