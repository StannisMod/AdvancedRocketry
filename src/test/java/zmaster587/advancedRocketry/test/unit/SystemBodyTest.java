package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link SystemBody}: its NBT round-trip (used by the universe registry's POI store) and
 * the descend-target rule. Pure-JUnit.
 */
public class SystemBodyTest {

    @Test
    public void nbtRoundTripPreservesEveryField() {
        SystemBody body = new SystemBody(GalacticCoord.ofSectorLocal(4, -5, 6, 123_456, -7_890, 42),
                SystemBodyKind.STATION_SLOT, 815, -12345);
        NBTTagCompound tag = new NBTTagCompound();
        body.writeToNBT(tag);
        SystemBody round = SystemBody.readFromNBT(tag);
        assertEquals(body, round);
        assertEquals(body.address(), round.address());
        assertEquals(SystemBodyKind.STATION_SLOT, round.kind());
        assertEquals(815, round.dimId());
        assertEquals(-12345, round.starId());
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
