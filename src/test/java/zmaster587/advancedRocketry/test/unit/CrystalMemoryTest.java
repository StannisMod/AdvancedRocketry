package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for what a memory crystal promises its owner: an address written into it stays
 * written, copying a crystal never costs either side an address, the fresher observation of a body
 * wins, and everything survives the trip through item NBT (the crystal is traded between players and
 * carried between ships, so its NBT shape is a same-version wire contract).
 */
public class CrystalMemoryTest {

    private static GalacticCoord coord(long sector) {
        return GalacticCoord.ofSectorLocal(sector, 2L, 2L, 0L, 0L, 0L);
    }

    private static CrystalEntry entry(long sector, String name, InfoTier detail, long observedTick) {
        return new CrystalEntry(coord(sector), name, SystemBodyKind.PLANET, detail, observedTick);
    }

    @Test
    public void aRecordedAddressIsKnownAndReadableBack() {
        CrystalMemory crystal = new CrystalMemory();
        assertTrue("a fresh crystal is blank, not broken", crystal.isEmpty());

        assertTrue("recording a new address changes the crystal",
                crystal.record(entry(5, "Kepler", InfoTier.TELESCOPE, 100L)));

        assertTrue(crystal.knows(coord(5)));
        assertEquals("Kepler", crystal.get(coord(5)).name());
        assertEquals(1, crystal.size());
    }

    @Test
    public void twoSightingsOfOneBodyAreOneRecord() {
        // A planet orbits, so two observations of it are at two different coordinates. What makes
        // them the same knowledge is the BODY. Keyed by coordinate instead, a crystal would collect
        // one entry per sighting of the same planet and the console's list would fill with copies of
        // it — each one aimed at a point the planet had already left.
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(coord(5), "Kepler", SystemBodyKind.PLANET,
                InfoTier.TELESCOPE, 100L, 42));
        crystal.record(new CrystalEntry(coord(9), "Kepler", SystemBodyKind.PLANET,
                InfoTier.ORBIT, 500L, 42));

        assertEquals("one body, one record — wherever it was standing when it was seen",
                1, crystal.size());
        assertNotNull(crystal.forBody(42));
        assertEquals("the fresher sighting wins, and it is the fresher POSITION too",
                coord(9), crystal.forBody(42).coord());
        assertEquals(InfoTier.ORBIT, crystal.forBody(42).detail());
    }

    @Test
    public void aBodyAndABareCoordinateAtTheSamePointAreDifferentKnowledge() {
        // A hand-noted point is not a claim about a body: nothing says the planet that happens to be
        // passing through it now is what the pilot wrote down.
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(coord(5), "scratch", SystemBodyKind.PLANET,
                InfoTier.TELESCOPE, 100L));
        crystal.record(new CrystalEntry(coord(5), "Kepler", SystemBodyKind.PLANET,
                InfoTier.TELESCOPE, 100L, 42));

        assertEquals(2, crystal.size());
    }

    @Test
    public void oneAddressIsHeldOnce() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(entry(5, "Kepler", InfoTier.TELESCOPE, 100L));
        crystal.record(entry(5, "Kepler", InfoTier.TELESCOPE, 100L));

        assertEquals("re-recording what the crystal already knows must not duplicate the address",
                1, crystal.size());
    }

    @Test
    public void theFresherObservationOfABodyWins() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(entry(5, "Kepler", InfoTier.TELESCOPE, 100L));
        crystal.record(entry(5, "Kepler b", InfoTier.ORBIT, 500L));

        assertEquals("a newer observation must replace the older record of the same body",
                InfoTier.ORBIT, crystal.get(coord(5)).detail());
        assertEquals("Kepler b", crystal.get(coord(5)).name());
        assertEquals(1, crystal.size());
    }

    @Test
    public void anOlderObservationNeverOverwritesANewerOne() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(entry(5, "surveyed", InfoTier.ORBIT, 500L));

        assertFalse("an older observation must not change the crystal",
                crystal.record(entry(5, "glimpsed", InfoTier.TELESCOPE, 100L)));
        assertEquals(InfoTier.ORBIT, crystal.get(coord(5)).detail());
        assertEquals("surveyed", crystal.get(coord(5)).name());
    }

    @Test
    public void copyingIsAddOnlyForBothCrystals() {
        CrystalMemory source = new CrystalMemory();
        source.record(entry(1, "Alpha", InfoTier.TELESCOPE, 10L));
        source.record(entry(2, "Beta", InfoTier.TELESCOPE, 10L));

        CrystalMemory target = new CrystalMemory();
        target.record(entry(3, "Gamma", InfoTier.TELESCOPE, 10L));

        assertEquals("both of the source's addresses are new to the target", 2, target.copyFrom(source));

        assertEquals("the target keeps what it already knew and gains the rest", 3, target.size());
        assertTrue(target.knows(coord(1)));
        assertTrue(target.knows(coord(2)));
        assertTrue("a copy must never cost the target an address it already had", target.knows(coord(3)));
        assertEquals("a copy must never take anything from the source", 2, source.size());
    }

    @Test
    public void copyingMergesSharedAddressesByFreshness() {
        CrystalMemory ship = new CrystalMemory();
        ship.record(entry(7, "surveyed at orbit", InfoTier.ORBIT, 900L));

        CrystalMemory base = new CrystalMemory();
        base.record(entry(7, "old glimpse", InfoTier.TELESCOPE, 100L));
        base.record(entry(8, "somewhere else", InfoTier.TELESCOPE, 100L));

        base.copyFrom(ship);

        assertEquals("the base learns the address it lacked", 2, base.size());
        assertEquals("where both knew the body, the base must keep the fresher record",
                InfoTier.ORBIT, base.get(coord(7)).detail());
        assertEquals("the ship's crystal is untouched by being copied FROM", 1, ship.size());
    }

    @Test
    public void erasingForgetsOneAddressAndKeepsTheRest() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(entry(1, "Alpha", InfoTier.TELESCOPE, 10L));
        crystal.record(entry(2, "Beta", InfoTier.TELESCOPE, 10L));

        assertTrue(crystal.erase(coord(1)));
        assertFalse("erasing an address the crystal does not hold changes nothing",
                crystal.erase(coord(1)));
        assertFalse(crystal.knows(coord(1)));
        assertTrue(crystal.knows(coord(2)));
    }

    @Test
    public void theAddressListSurvivesAnNbtRoundTrip() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(coord(4), "Home", SystemBodyKind.MOON, InfoTier.APPROACH, 4242L));
        crystal.record(entry(9, "Far", InfoTier.TELESCOPE, 7L));

        NBTTagCompound nbt = new NBTTagCompound();
        crystal.writeToNBT(nbt);
        CrystalMemory read = CrystalMemory.readFromNBT(nbt);

        assertEquals(2, read.size());
        CrystalEntry home = read.get(coord(4));
        assertNotNull("the address itself must survive the round trip", home);
        assertEquals("Home", home.name());
        assertEquals("the body kind must survive", SystemBodyKind.MOON, home.kind());
        assertEquals("the resolved detail level must survive", InfoTier.APPROACH, home.detail());
        assertEquals("the observation timestamp must survive", 4242L, home.observedTick());
    }

    @Test
    public void anUnwrittenCrystalReadsAsBlank() {
        CrystalMemory read = CrystalMemory.readFromNBT(new NBTTagCompound());

        assertTrue("a freshly crafted crystal carries no NBT at all and must read as empty",
                read.isEmpty());
    }

    @Test
    public void theListIsOrderedByWhenAddressesWereLearned() {
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(entry(3, "first", InfoTier.TELESCOPE, 10L));
        crystal.record(entry(1, "second", InfoTier.TELESCOPE, 20L));
        crystal.record(entry(2, "third", InfoTier.TELESCOPE, 30L));

        assertEquals("first", crystal.list().get(0).name());
        assertEquals("second", crystal.list().get(1).name());
        assertEquals("third", crystal.list().get(2).name());
    }
}
