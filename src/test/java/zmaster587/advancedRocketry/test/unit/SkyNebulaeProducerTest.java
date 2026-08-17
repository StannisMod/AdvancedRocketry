package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderNebula;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.SkyNebulaeProducer;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Nebula;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the sky is told about the clouds around a cell.
 *
 * <p>These pin the promises a viewer can check: a cloud lies in the direction it really lies in, it
 * LOOKS bigger from closer, a viewer inside one has it all around him, a cloud too small to be a
 * landmark is left out rather than drawn as a speck, and a generator with no clusters produces an
 * empty sky rather than a fabricated one. They do not pin the reach, the filter threshold or the cap
 * — those are render tunables and moving them must not turn a test red.</p>
 */
public class SkyNebulaeProducerTest {

    /** A cloud seated at a stated point, with a stated size. The cluster behind it is not read here. */
    private static Nebula cloudAt(double xLy, double yLy, double zLy, double radiusLy) {
        return new Nebula(null, Nebula.Appearance.EMISSION, xLy, yLy, zLy, radiusLy, 0.8d);
    }

    /** A generator that answers with exactly these clouds, whatever is asked. */
    private static IGalaxyGenerator generatorOf(final List<Nebula> clouds) {
        return new IGalaxyGenerator() {
            @Override
            public Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord) {
                return Optional.empty();
            }

            @Override
            public Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min,
                                                                  GalacticCoord max) {
                return Collections.emptyMap();
            }

            @Override
            public List<Nebula> nebulaeAround(long seed, GalacticCoord cell, double radiusLy) {
                return new ArrayList<>(clouds);
            }
        };
    }

    /** The cell whose centre sits {@code ly} light years out along +X. */
    private static GalacticCoord cellAtLightYears(double ly) {
        return GalacticCoord.ofSectorLocal(UniverseScale.cellsAt(ly), 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    public void aCloudLiesInTheDirectionItReallyLies() {
        // The one thing a landmark has to get right: look that way and it is there.
        List<RenderNebula> sky = SkyNebulaeProducer.around(
                generatorOf(Arrays.asList(cloudAt(0d, 0d, 200d, 40d))), 1L, GalacticCoord.ORIGIN);

        assertEquals("the one cloud in reach must be in the sky", 1, sky.size());
        RenderNebula drawn = sky.get(0);
        assertEquals("a cloud straight along +Z must be drawn straight along +Z", 1.0F, drawn.dirZ, 1.0E-4F);
        assertEquals(0.0F, drawn.dirX, 1.0E-4F);
        assertEquals(0.0F, drawn.dirY, 1.0E-4F);
    }

    @Test
    public void aCloudLooksBiggerFromCloser() {
        // What makes it a landmark rather than a decal: it opens as you close on it, so a pilot can
        // tell whether he is approaching one.
        // The cloud sits along +X because that is the axis the observer moves along; put it anywhere
        // else and stepping "closer" walks past it, which is what the first version of this did.
        List<Nebula> one = Arrays.asList(cloudAt(400d, 0d, 0d, 50d));
        float far = SkyNebulaeProducer.around(generatorOf(one), 1L, GalacticCoord.ORIGIN)
                .get(0).angularRadius;
        float near = SkyNebulaeProducer.around(generatorOf(one), 1L, cellAtLightYears(200d))
                .get(0).angularRadius;

        assertTrue("a cloud must subtend more from closer: far=" + far + " near=" + near, near > far);
    }

    @Test
    public void insideACloudItIsAllAroundYou() {
        // The honest limit rather than an overflow: at zero distance the half-angle would diverge if
        // it were computed on a plane, and a ship that flew into a cloud would see a NaN-sized hole.
        RenderNebula inside = SkyNebulaeProducer.renderOf(cloudAt(0d, 0d, 10d, 100d), 0d, 0d, 0d);

        assertNotNull("a viewer inside a cloud still has a sky", inside);
        assertEquals("and the cloud fills half of it", (float) (Math.PI / 2d), inside.angularRadius,
                1.0E-4F);
    }

    @Test
    public void aCloudTooSmallToBeALandmarkIsNotDrawn() {
        // The LOD rule, stated as the thing it protects: a few pixels of haze is not a landmark, and
        // drawing it costs a fan for something nobody can navigate by.
        double farAway = 100_000d;
        assertNull("a distant speck must be left out",
                SkyNebulaeProducer.renderOf(cloudAt(0d, 0d, farAway, 1d), 0d, 0d, 0d));
        assertNotNull("while the same cloud near enough to see must not be",
                SkyNebulaeProducer.renderOf(cloudAt(0d, 0d, 50d, 1d), 0d, 0d, 0d));
    }

    @Test
    public void aGeneratorWithNoCloudsGivesAnEmptySkyAndNotAFabricatedOne() {
        // The negative case the whole feed has to keep: a universe with no clusters has no gas, and
        // an empty sky must stay empty rather than acquire a default cloud.
        assertTrue("void must yield no clouds",
                SkyNebulaeProducer.around(generatorOf(Collections.<Nebula>emptyList()), 1L,
                        GalacticCoord.ORIGIN).isEmpty());
        assertTrue("and so must no generator at all",
                SkyNebulaeProducer.around(null, 1L, GalacticCoord.ORIGIN).isEmpty());
    }

    @Test
    public void theSkyIsOrderedLargestFirstSoTheCapDropsTheLeastVisible() {
        // The cap is a bound on work, and a bound on work must never decide WHICH landmark survives
        // by accident of enumeration order.
        List<Nebula> many = new ArrayList<>();
        for (int i = 1; i <= SkyNebulaeProducer.MAX_PER_CELL + 6; i++) {
            many.add(cloudAt(0d, 0d, 100d * i, 30d * i * 0.5d));
        }
        List<RenderNebula> sky = SkyNebulaeProducer.around(generatorOf(many), 1L, GalacticCoord.ORIGIN);

        assertTrue("the sky must be capped: " + sky.size(), sky.size() <= SkyNebulaeProducer.MAX_PER_CELL);
        for (int i = 1; i < sky.size(); i++) {
            assertTrue("clouds must be ordered largest first",
                    sky.get(i - 1).angularRadius >= sky.get(i).angularRadius);
        }
    }

    @Test
    public void whatIsSeatedAndWhatIsDrawnAreSeparatelyReadable() {
        // So a reader can tell a working LOD filter from a missing cloud — the distinction the probe
        // reply reports and a test would otherwise have to guess at.
        List<Nebula> mixed = Arrays.asList(cloudAt(0d, 0d, 200d, 40d), cloudAt(0d, 0d, 200_000d, 1d));
        IGalaxyGenerator gen = generatorOf(mixed);

        assertEquals("both are out there", 2, SkyNebulaeProducer.countAround(gen, 1L, GalacticCoord.ORIGIN));
        assertEquals("only one is worth drawing", 1,
                SkyNebulaeProducer.around(gen, 1L, GalacticCoord.ORIGIN).size());
    }

    @Test
    public void aCloudCarriesItsAppearanceAndItsThickness() {
        // The two fields the renderer branches on: the age sequence decides the tint, and a dark
        // cloud is the one that must be drawn OVER the stars rather than behind them.
        Nebula dark = new Nebula(null, Nebula.Appearance.DARK, 0d, 0d, 150d, 40d, 0.6d);
        RenderNebula drawn = SkyNebulaeProducer.renderOf(dark, 0d, 0d, 0d);

        assertNotNull(drawn);
        assertEquals("the appearance must survive the trip to the client",
                Nebula.Appearance.DARK.ordinal(), drawn.appearanceOrdinal);
        assertEquals("and so must how thick it is", 0.6F, drawn.opacity, 1.0E-4F);
    }
}
