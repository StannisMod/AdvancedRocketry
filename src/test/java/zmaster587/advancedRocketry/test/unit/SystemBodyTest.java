package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.BodyEphemeris;
import zmaster587.advancedRocketry.universe.CellFrame;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link SystemBody}: the split between a body's durable NAME and where it
 * actually is (C15 ADDR-1/5/6), its NBT round-trip (used by the universe registry's POI and pinned
 * stores) and the descend-target rule. Pure-JUnit.
 */
public class SystemBodyTest {

    /** An orbit big enough that a tick apart is a different place, with a short period so it moves. */
    private static BodyEphemeris orbit(double distUnits, long unitBlocks) {
        return BodyEphemeris.orbit(distUnits, 0.0, 0.0, false, 1000d, unitBlocks);
    }

    @Test
    public void nbtRoundTripPreservesEveryField() {
        SystemBody body = new SystemBody(GalacticCoord.ofSectorLocal(4, -5, 6, 123_456, -7_890, 42),
                SystemBodyKind.STATION_SLOT, 815, -12345);
        NBTTagCompound tag = new NBTTagCompound();
        body.writeToNBT(tag);
        SystemBody round = SystemBody.readFromNBT(tag);
        assertEquals(body, round);
        assertEquals(body.name(), round.name());
        assertEquals(body.addressAt(0L), round.addressAt(0L));
        assertEquals(SystemBodyKind.STATION_SLOT, round.kind());
        assertEquals(815, round.dimId());
        assertEquals(-12345, round.starId());
    }

    /**
     * ADDR-1. The whole point of the model: a cell name is an identifier, so no amount of time
     * changes it. The second half is the control — without it a body that never moves would pass.
     */
    @Test
    public void aNameIsTheSameAtEveryTickWhileThePlaceIsNot() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(57, 0, 5, 0, 0, 0);
        SystemBody planet = new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 3, 0);

        assertEquals(name, planet.name());
        assertEquals("a name is not a function of time", planet.name(), planet.name());
        for (long tick : new long[]{0L, 137L, 250L, 500_000L}) {
            assertEquals("tick " + tick + " renamed the cell", name.cellKey(),
                    planet.addressAt(tick).cellKey());
        }
        assertNotEquals("the fixture's body never moves, so nothing above was tested",
                planet.absoluteAt(0L), planet.absoluteAt(250L));
    }

    /** ADDR-6. A cell's primary is what its frame is centred on, so its in-cell offset is zero. */
    @Test
    public void aPrimarySitsAtItsOwnFramesOrigin() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(1, 2, 3, 0, 0, 0);
        CellFrame frame = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody planet = new SystemBody(name, frame, BodyEphemeris.STATIC,
                SystemBodyKind.PLANET, 4, 0);

        for (long tick : new long[]{0L, 300L, 999_999L}) {
            assertTrue("a primary drifted off its own frame origin at tick " + tick,
                    planet.inCellOffsetAt(tick).isZero());
            assertEquals(frame.originAt(tick), planet.absoluteAt(tick));
        }
    }

    /**
     * ADDR-5. A moon shares its parent's cell NAME and rides its parent's frame, while keeping its
     * own live offset inside it — that is what makes a planet-and-its-moons one destination.
     */
    @Test
    public void aMoonKeepsItsParentsNameAndMovesInsideIt() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(9, 0, 0, 0, 0, 0);
        CellFrame parentFrame = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody moon = new SystemBody(name, parentFrame, orbit(300d, 200L),
                SystemBodyKind.MOON, 11, 0);

        assertEquals(name, moon.name());
        assertEquals(name.cellKey(), moon.addressAt(400L).cellKey());
        assertNotEquals("a moon's offset inside its parent's cell is live",
                moon.inCellOffsetAt(0L), moon.inCellOffsetAt(300L));
        assertFalse("a moon is not at its parent's centre", moon.inCellOffsetAt(0L).isZero());
    }

    /**
     * ADDR-6's requirement on the pinned store, and the reason a body carries a LAW rather than a
     * position: a pin freezes the ELEMENTS. Pin-on-touch fires the first time a player builds a
     * station in a system, so a pin that froze positions would stop that system for the rest of the
     * save — and nothing would ever say so.
     */
    @Test
    public void aBodyStillMovesAfterAnNbtRoundTrip() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(20, 0, 0, 0, 0, 0);
        SystemBody planet = new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 6, 2);

        NBTTagCompound tag = new NBTTagCompound();
        planet.writeToNBT(tag);
        SystemBody round = SystemBody.readFromNBT(tag);

        assertEquals(planet.absoluteAt(0L), round.absoluteAt(0L));
        assertEquals(planet.absoluteAt(250L), round.absoluteAt(250L));
        assertNotEquals("a round-tripped body was frozen where it stood", round.absoluteAt(0L),
                round.absoluteAt(250L));
    }

    /** A POI is re-bound to its cell's frame when served, so a station rides the planet it orbits. */
    @Test
    public void aBodyRebindsToTheFrameOfTheCellItIsServedFrom() {
        GalacticCoord name = GalacticCoord.ofSectorLocal(3, 0, 0, 5_000, 0, 0);
        SystemBody station = new SystemBody(name, SystemBodyKind.STATION_SLOT,
                Constants.INVALID_PLANET, 0);
        assertEquals("a bare POI stands still", station.absoluteAt(0L), station.absoluteAt(500L));

        CellFrame moving = CellFrame.of(AbsolutePos.ofCellName(name), orbit(200d, 1_000_000L));
        SystemBody carried = station.withFrame(moving);
        assertEquals("re-binding a frame may not move the station inside its cell",
                station.inCellOffsetAt(0L), carried.inCellOffsetAt(0L));
        assertNotEquals("a station in a body's cell must travel with it",
                carried.absoluteAt(0L), carried.absoluteAt(500L));
    }

    /** Only a real body may define a frame: a moon rides its parent's and a POI rides its cell's. */
    @Test
    public void onlyRealBodiesDefineACellsFrame() {
        GalacticCoord at = GalacticCoord.ORIGIN;
        assertTrue(new SystemBody(at, SystemBodyKind.STAR, Constants.INVALID_PLANET, 0).definesFrame());
        assertTrue(new SystemBody(at, SystemBodyKind.PLANET, 1, 0).definesFrame());
        assertTrue(new SystemBody(at, SystemBodyKind.GAS_GIANT, 2, 0).definesFrame());
        assertTrue(new SystemBody(at, SystemBodyKind.ASTEROID_BELT,
                Constants.INVALID_PLANET, 0).definesFrame());
        assertFalse(new SystemBody(at, SystemBodyKind.MOON, 3, 0).definesFrame());
        assertFalse(new SystemBody(at, SystemBodyKind.STATION_SLOT,
                Constants.INVALID_PLANET, 0).definesFrame());
    }

    @Test
    public void descendTargetOnlyForPlanetOrMoonWithARealDimension() {
        GalacticCoord at = GalacticCoord.ofSectorLocal(1, 1, 1, 10, 20, 30);
        assertTrue(new SystemBody(at, SystemBodyKind.PLANET, 7, 1).isDescendTarget());
        assertTrue(new SystemBody(at, SystemBodyKind.MOON, 8, 1).isDescendTarget());
        assertFalse("a planet with no realized dim is not yet a descent target",
                new SystemBody(at, SystemBodyKind.PLANET, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(new SystemBody(at, SystemBodyKind.STAR, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(new SystemBody(at, SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, 1).isDescendTarget());
        assertFalse(new SystemBody(at, SystemBodyKind.ASTEROID_BELT, Constants.INVALID_PLANET, 1).isDescendTarget());
    }

    @Test
    public void unknownKindDecodesToAnInertPoiRatherThanCrashing() {
        NBTTagCompound tag = new NBTTagCompound();
        new SystemBody(GalacticCoord.ORIGIN, SystemBodyKind.PLANET, 5, 1).writeToNBT(tag);
        tag.setString("kind", "SOME_FUTURE_KIND"); // a kind this version doesn't know
        SystemBody round = SystemBody.readFromNBT(tag);
        assertEquals(SystemBodyKind.STATION_SLOT, round.kind());
        assertFalse(round.isDescendTarget());
    }

    @Test
    public void kindDescendCapability() {
        assertTrue(SystemBodyKind.PLANET.canDescend());
        assertTrue(SystemBodyKind.MOON.canDescend());
        assertFalse(SystemBodyKind.STAR.canDescend());
        assertFalse(SystemBodyKind.ASTEROID_BELT.canDescend());
        assertFalse(SystemBodyKind.STATION_SLOT.canDescend());
    }
}
