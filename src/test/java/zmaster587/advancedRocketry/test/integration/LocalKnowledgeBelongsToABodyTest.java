package zmaster587.advancedRocketry.test.integration;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a BODY knows, as opposed to what the game knows.
 *
 * <p>Discovery used to have exactly one store: a global set that a beacon or the planet XML wrote
 * into, which every launch pad in every world read. A telescope survey wrote nothing into it, so a
 * player could chart a system, fly there with a ship, and still not be offered that planet by a
 * tier-1 rocket standing on the ground.</p>
 *
 * <p>The contract these tests pin is that knowledge is a property of the PLACE: what one body has
 * learned does not leak to its neighbour, the global set remains a floor under every body rather
 * than being replaced, and a body's learning survives a save/load. They deliberately do not pin who
 * WRITES the set - an observatory, a beacon and a crystal upload are separate mechanics with their
 * own tests - only that a body has one and that it is asked.</p>
 */
public class LocalKnowledgeBelongsToABodyTest {

    private static final int TARGET = 4242;
    private static final int OTHER_TARGET = 4243;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void whatOneBodyLearnsIsNotKnownOnAnother() {
        DimensionProperties here = new DimensionProperties(101);
        DimensionProperties elsewhere = new DimensionProperties(102);

        here.discoverPlanet(TARGET);

        assertTrue("the body that learned it must know it", here.isPlanetKnownHere(TARGET));
        assertFalse("a different body must not have learned anything",
                elsewhere.isPlanetKnownHere(TARGET));
    }

    @Test
    public void aBodyKnowsOnlyWhatItWasTaught() {
        DimensionProperties here = new DimensionProperties(103);
        here.discoverPlanet(TARGET);

        assertFalse("a target nobody taught it must stay unknown", here.isPlanetKnownHere(OTHER_TARGET));
        assertEquals("and the set holds exactly what was taught", 1, here.getLocallyKnownPlanets().size());
    }

    @Test
    public void teachingTheSameBodyTwiceIsOneFact() {
        DimensionProperties here = new DimensionProperties(104);

        here.discoverPlanet(TARGET);
        here.discoverPlanet(TARGET);

        assertEquals("a second survey of the same target must not double the entry",
                1, here.getLocallyKnownPlanets().size());
    }

    @Test
    public void whatABodyLearnedSurvivesASaveAndLoad() {
        DimensionProperties saved = new DimensionProperties(105);
        saved.discoverPlanet(TARGET);
        saved.discoverPlanet(OTHER_TARGET);

        NBTTagCompound nbt = new NBTTagCompound();
        saved.writeToNBT(nbt);

        DimensionProperties loaded = new DimensionProperties(105);
        loaded.readFromNBT(nbt);

        assertTrue("the first target must survive the round trip", loaded.isPlanetKnownHere(TARGET));
        assertTrue("and so must the second", loaded.isPlanetKnownHere(OTHER_TARGET));
        assertEquals("with nothing else invented on the way",
                2, loaded.getLocallyKnownPlanets().size());
    }

    @Test
    public void aBodyThatLearnedNothingWritesNothing() {
        DimensionProperties untaught = new DimensionProperties(106);

        NBTTagCompound nbt = new NBTTagCompound();
        untaught.writeToNBT(nbt);

        assertFalse("an empty set must not occupy a key in every planet's save data",
                nbt.hasKey("locallyKnownPlanets"));
    }

    @Test
    public void loadingReplacesWhatTheObjectHeldRatherThanMergingIntoIt() {
        // These objects are reused across loads. A merge would make a body remember a target that
        // the save it is being loaded from does not contain - knowledge appearing from nowhere.
        DimensionProperties reused = new DimensionProperties(107);
        reused.discoverPlanet(TARGET);

        NBTTagCompound fromAnotherSave = new NBTTagCompound();
        fromAnotherSave.setIntArray("locallyKnownPlanets", new int[]{OTHER_TARGET});
        reused.readFromNBT(fromAnotherSave);

        assertTrue("the loaded target must be known", reused.isPlanetKnownHere(OTHER_TARGET));
        assertFalse("what the object held before the load must be gone",
                reused.isPlanetKnownHere(TARGET));
    }
}
