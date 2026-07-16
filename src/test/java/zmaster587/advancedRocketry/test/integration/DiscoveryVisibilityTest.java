package zmaster587.advancedRocketry.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.util.XMLPlanetLoader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Axis-E E-1 (visibility) universe-layer contracts: the authored {@code <isKnown>} flag drives the initial
 * known set, a planet's global known bit reads through {@link DimensionManager#isPlanetKnown}, and a system's
 * known state is DERIVED from its member bodies via {@link UniverseRegistry#isSystemKnown}. Needs
 * {@link MinecraftBootstrap} for XML parsing and {@link DimensionProperties} construction.
 */
public class DiscoveryVisibilityTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void resetStarLookup() {
        UniverseRegistry.setStarLookup(null);
    }

    // ---- E-1 per-planet: <isKnown> XML flag + the global read ----------------

    @Test
    public void isKnownXmlFlagDrivesInitialKnownSet() throws Exception {
        int knownDim = 7901;
        int unknownDim = 7902;
        Set<Integer> initSet = ARConfiguration.getCurrentConfig().initiallyKnownPlanets;
        boolean hadKnown = initSet.contains(knownDim);
        boolean hadUnknown = initSet.contains(unknownDim);
        try {
            parse(galaxy(starWithPlanet("KnownWorld", knownDim, true)));
            assertTrue("<isKnown>true> must add the planet to the initial known set",
                    initSet.contains(knownDim));

            parse(galaxy(starWithPlanet("UnknownWorld", unknownDim, false)));
            assertFalse("<isKnown>false> must NOT add the planet to the initial known set",
                    initSet.contains(unknownDim));
        } finally {
            if (!hadKnown) initSet.remove(knownDim);
            if (!hadUnknown) initSet.remove(unknownDim);
        }
    }

    @Test
    public void isPlanetKnownReflectsGlobalKnownSet() {
        DimensionManager dm = DimensionManager.getInstance();
        int dim = 7900;
        boolean had = dm.knownPlanets.contains(dim);
        try {
            dm.knownPlanets.remove(dim);
            assertFalse("absent from the known set -> not known", dm.isPlanetKnown(dim));
            dm.knownPlanets.add(dim);
            assertTrue("present in the known set -> known", dm.isPlanetKnown(dim));
        } finally {
            if (had) dm.knownPlanets.add(dim); else dm.knownPlanets.remove(dim);
        }
    }

    // ---- E-1 per-system: derived isSystemKnown -------------------------------

    @Test
    public void isSystemKnownDerivesFromMemberBodies() {
        StellarBody star = new StellarBody();
        star.setId(4400);
        star.setName("KnownSys");
        planet(730, 100, 0.0).setStar(star);          // setStar back-adds the planet to the star
        planet(731, 200, Math.PI / 2).setStar(star);
        UniverseRegistry.setStarLookup(id -> id == 4400 ? star : null);

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord coord = GalacticCoord.ofSectorLocal(1, 2, 3, 0, 0, 0);
        reg.place(coord, 4400);

        DimensionManager dm = DimensionManager.getInstance();
        boolean had730 = dm.knownPlanets.contains(730);
        boolean had731 = dm.knownPlanets.contains(731);
        try {
            dm.knownPlanets.remove(730);
            dm.knownPlanets.remove(731);
            assertFalse("no member known -> system unknown", reg.isSystemKnown(coord));
            // An empty (procedural / unplaced) system has no dimensioned bodies -> unknown.
            assertFalse("an empty system is unknown",
                    reg.isSystemKnown(GalacticCoord.ofSectorLocal(40, 41, 42, 0, 0, 0)));

            dm.knownPlanets.add(730);
            assertTrue("one dimensioned member known -> system known", reg.isSystemKnown(coord));
        } finally {
            if (had730) dm.knownPlanets.add(730); else dm.knownPlanets.remove(730);
            if (had731) dm.knownPlanets.add(731); else dm.knownPlanets.remove(731);
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static DimensionProperties planet(int dimId, int orbitalDist, double theta) {
        DimensionProperties p = new DimensionProperties(dimId);
        p.orbitalDist = orbitalDist;
        p.orbitTheta = theta;
        p.orbitalPhi = 0;
        return p;
    }

    private void parse(String xml) throws IOException {
        File file = tempFolder.newFile();
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        XMLPlanetLoader loader = new XMLPlanetLoader();
        assertTrue("loader.loadFile must succeed for the XML fixture", loader.loadFile(file));
        loader.readAllPlanets();
    }

    private static String galaxy(String stars) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<galaxy>\n" + stars + "</galaxy>\n";
    }

    private static String starWithPlanet(String planetName, int dimId, boolean known) {
        return "<star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" isBlackHole=\"false\""
                + " diskAngle=\"70\" numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "  <planet name=\"" + planetName + "\" DIMID=\"" + dimId + "\">\n"
                + "    <isKnown>" + known + "</isKnown>\n"
                + "  </planet>\n"
                + "</star>\n";
    }
}
