package zmaster587.advancedRocketry.test.integration;

import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import zmaster587.advancedRocketry.util.OreGenProperties;
import zmaster587.advancedRocketry.util.OreGenProperties.OreEntry;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.dimension.TerrainSource;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalacticAnchor;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.GalaxyKey;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.util.XMLPlanetLoader;
import zmaster587.advancedRocketry.util.XMLPlanetLoader.DimensionPropertyCoupling;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * XML planet definitions — deep parsing path that needs
 * {@link MinecraftBootstrap#ensure()}.
 *
 * <p>The simple {@code loadFile}/{@code isValid} sanity checks live in
 * {@code unit/XMLPlanetLoaderTest}. This class drives {@code readAllPlanets()}
 * through actual XML fixtures and verifies every parsed field (DIMID
 * resolution, atmosphere/gravity clamping, weather field preservation,
 * defaults, parent/child planet hierarchy).</p>
 */
public class XMLPlanetLoaderTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DimensionPropertyCoupling parse(String xml) throws IOException {
        File file = tempFolder.newFile();
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        XMLPlanetLoader loader = new XMLPlanetLoader();
        assertTrue("loader.loadFile must succeed for XML fixture", loader.loadFile(file));
        return loader.readAllPlanets();
    }

    private static String galaxy(String stars) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<galaxy>\n" + stars + "</galaxy>\n";
    }

    private static String star(String name, String body) {
        return "<star name=\"" + name + "\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                + " isBlackHole=\"false\" diskAngle=\"70\""
                + " numPlanets=\"1\" numGasGiants=\"0\">\n"
                + body
                + "</star>\n";
    }

    // ---- Star/planet discovery -----------------------------------------------

    @Test
    public void readAllPlanetsReturnsAtLeastOneStar() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol", "")));
        assertEquals("expected 1 star parsed", 1, coupling.stars.size());
        StellarBody star = coupling.stars.get(0);
        assertEquals("Sol", star.getName());
        assertEquals(0, coupling.dims.size());
    }

    @Test
    public void planetWithExplicitDimIdGetsThatId() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"Earth\" DIMID=\"9001\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "</planet>\n")));
        assertEquals(1, coupling.dims.size());
        DimensionProperties props = coupling.dims.get(0);
        assertEquals("DIMID attribute must override the allocator", 9001, props.getId());
        assertEquals("Earth", props.getName());
    }

    @Test
    public void planetWithoutDimIdGetsAllocatedDim() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"Earth\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "</planet>\n")));
        assertEquals(1, coupling.dims.size());
        DimensionProperties props = coupling.dims.get(0);
        // INVALID_PLANET is Integer.MIN_VALUE — an allocation failure marker. The
        // allocator must return a usable dim.
        assertNotEquals("allocator returned INVALID_PLANET",
                zmaster587.advancedRocketry.api.Constants.INVALID_PLANET, props.getId());
        // Vanilla dims 0/-1/1 are reserved; allocator skips them.
        assertTrue("auto-allocated dim should be ≥ 2 (vanilla reserved 0/-1/1), got " + props.getId(),
                props.getId() >= 2);
    }

    @Test
    public void nestedPlanetBecomesChildOfParent() throws Exception {
        // Moon -> child of Earth via nested <planet>.
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"Earth\" DIMID=\"7001\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <planet name=\"Moon\" DIMID=\"7002\">\n"
              + "    <isKnown>true</isKnown>\n"
              + "  </planet>\n"
              + "</planet>\n")));
        // readAllPlanets flattens hierarchy — both Earth + Moon in dims list.
        assertEquals("Earth + Moon = 2 dims parsed", 2, coupling.dims.size());

        DimensionProperties earth = findByName(coupling.dims, "Earth");
        DimensionProperties moon = findByName(coupling.dims, "Moon");
        assertNotNull(earth);
        assertNotNull(moon);

        // The parent's getChildPlanets() must contain the moon's dim id.
        assertTrue("Earth.getChildPlanets() must include the moon dim id ("
                        + moon.getId() + "): " + earth.getChildPlanets(),
                earth.getChildPlanets().contains(moon.getId()));
        assertEquals("Moon.getParentPlanet() must be Earth's dim id",
                earth.getId(), moon.getParentPlanet());
    }

    // ---- Weather fields ------------------------------------------------------

    @Test
    public void weatherFieldsAreParsed() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"Stormworld\" DIMID=\"7100\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <rainStartLength>3000</rainStartLength>\n"
              + "  <rainProlongationLength>4000</rainProlongationLength>\n"
              + "  <thunderStartLength>5000</thunderStartLength>\n"
              + "  <thunderProlongationLength>6000</thunderProlongationLength>\n"
              + "  <rainMarker>1</rainMarker>\n"
              + "  <thunderMarker>-1</thunderMarker>\n"
              + "  <acidicRain>true</acidicRain>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertEquals(3000, props.getRainStartLength());
        assertEquals(4000, props.getRainProlongationLength());
        assertEquals(5000, props.getThunderStartLength());
        assertEquals(6000, props.getThunderProlongationLength());
        assertEquals("rainMarker=1 -> always rain", 1, props.getRainMarker());
        assertEquals("thunderMarker=-1 -> never thunder", -1, props.getThunderMarker());
        assertTrue("acidicRain=true must be parsed from the planet XML", props.isAcidicRain());
    }

    @Test
    public void weatherFieldsDefaultWhenMissing() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"NoWeatherXml\" DIMID=\"7101\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        // Production defaults: see DimensionProperties.rainStartLength=168000 etc.
        assertEquals(168000, props.getRainStartLength());
        assertEquals(168000, props.getThunderStartLength());
        assertEquals("default rainMarker=0 (regular weather)", 0, props.getRainMarker());
        assertEquals("default thunderMarker=0 (regular weather)", 0, props.getThunderMarker());
        assertFalse("acidicRain must default to false when the tag is absent", props.isAcidicRain());
    }

    @Test
    public void invalidWeatherMarkerSkipsPlanetInsteadOfCrashing() throws Exception {
        // A non-numeric rainMarker makes Integer.parseInt throw deep inside
        // readPlanetFromNode. Per-planet isolation (issue #77 fix) must catch
        // that, skip the offending planet, and keep loading the rest — rather
        // than the old behaviour of propagating up to a fatal exitJava.
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"BadWeather\" DIMID=\"7102\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <rainMarker>NOT_A_NUMBER</rainMarker>\n"
              + "</planet>\n")));
        assertTrue("a planet with a non-numeric rainMarker must be skipped, not crash",
                coupling.dims.isEmpty());
    }

    // ---- Clamping ------------------------------------------------------------

    @Test
    public void atmosphereDensityClampsAboveMax() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"DenseAtm\" DIMID=\"7200\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <atmosphereDensity>99999</atmosphereDensity>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertEquals("atmosphere density must clamp to MAX_ATM_PRESSURE",
                DimensionProperties.MAX_ATM_PRESSURE, props.getAtmosphereDensity());
    }

    @Test
    public void atmosphereDensityClampsBelowMin() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"VacuumAtm\" DIMID=\"7201\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <atmosphereDensity>-999</atmosphereDensity>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertEquals("atmosphere density must clamp to MIN_ATM_PRESSURE",
                DimensionProperties.MIN_ATM_PRESSURE, props.getAtmosphereDensity());
    }

    @Test
    public void gravityClampsAboveMax() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"HeavyG\" DIMID=\"7202\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <gravitationalMultiplier>99999</gravitationalMultiplier>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        // Stored as float = clamped int / 100.
        assertEquals("gravity must clamp to MAX_GRAVITY/100",
                DimensionProperties.MAX_GRAVITY / 100f,
                props.getGravitationalMultiplier(), 1e-6);
    }

    // ---- Write -> read full round-trip ---------------------------------------

    /**
     * writeXML produces XML that readAllPlanets parses back into a
     * DimensionProperties carrying every field we wrote.
     *
     * Production save path: AR writes planet definitions to
     * {@code config/advRocketry/planetDefs.xml} via {@code XMLPlanetLoader.writeXML(DimensionManager)}.
     * That XML is later re-read at startup. The contract this test pins down is:
     * critical numeric/identity fields survive the round-trip.
     */
    @Test
    public void writeThenReadPreservesCriticalFields() throws Exception {
        // 1. Build an in-memory galaxy: 1 star + 1 planet attached to it.
        zmaster587.advancedRocketry.api.dimension.solar.StellarBody star =
                new zmaster587.advancedRocketry.api.dimension.solar.StellarBody();
        star.setId(7301);
        star.setName("WriteRtStar");
        star.setTemperature(120);
        star.setSize(1.25f);
        star.setBlackHole(false);

        DimensionProperties planet = new DimensionProperties(7302, "WriteRtPlanet");
        planet.gravitationalMultiplier = 1.5f;
        planet.orbitalDist = 175;
        planet.rotationalPeriod = 19_200;
        planet.setAtmosphereDensityDirect(125);
        planet.setStar(star);
        planet.hasOxygen = true;

        star.addPlanet(planet);

        // 2. Wrap into a minimal IGalaxy and serialise.
        zmaster587.advancedRocketry.api.dimension.solar.IGalaxy galaxy =
                new SingleStarGalaxyFixture(star);

        String xml = XMLPlanetLoader.writeXML(galaxy);
        assertTrue("writeXML must include the star name", xml.contains("WriteRtStar"));
        assertTrue("writeXML must include the planet name", xml.contains("WriteRtPlanet"));

        // 3. Round-trip through a temp file + loadFile + readAllPlanets.
        File out = tempFolder.newFile("written-planets.xml");
        Files.write(out.toPath(), xml.getBytes(StandardCharsets.UTF_8));

        XMLPlanetLoader reader = new XMLPlanetLoader();
        assertTrue("loadFile must accept self-generated XML", reader.loadFile(out));

        DimensionPropertyCoupling restored = reader.readAllPlanets();
        assertEquals("1 star round-trips", 1, restored.stars.size());
        assertEquals("WriteRtStar", restored.stars.get(0).getName());

        assertEquals("1 planet round-trips", 1, restored.dims.size());
        DimensionProperties restoredPlanet = restored.dims.get(0);
        assertEquals("WriteRtPlanet", restoredPlanet.getName());

        // Critical numeric fields — anything off-by-one here means a writeXML ->
        // loadFile divergence that corrupts saves.
        assertEquals("gravity must round-trip",
                1.5f, restoredPlanet.getGravitationalMultiplier(), 1e-3);
        assertEquals("orbitalDist must round-trip",
                175, restoredPlanet.orbitalDist);
        assertEquals("rotationalPeriod must round-trip",
                19_200, restoredPlanet.rotationalPeriod);
        assertEquals("atmosphereDensity must round-trip",
                125, restoredPlanet.getAtmosphereDensity());
    }

    /**
     * Minimal IGalaxy fixture wrapping a single star. Only {@link #getStars()}
     * is consumed by {@link XMLPlanetLoader#writeXML}; all other methods throw
     * so that if the write path expands its API usage we fail loudly.
     */
    private static final class SingleStarGalaxyFixture
            implements zmaster587.advancedRocketry.api.dimension.solar.IGalaxy {
        private final zmaster587.advancedRocketry.api.dimension.solar.StellarBody star;
        SingleStarGalaxyFixture(zmaster587.advancedRocketry.api.dimension.solar.StellarBody s) {
            this.star = s;
        }

        @Override
        public java.util.Collection<zmaster587.advancedRocketry.api.dimension.solar.StellarBody>
        getStars() {
            return java.util.Collections.singletonList(star);
        }
        @Override public Integer[] getRegisteredDimensions() { throw new UnsupportedOperationException(); }
        @Override public zmaster587.advancedRocketry.api.satellite.SatelliteBase getSatellite(long satId) { throw new UnsupportedOperationException(); }
        @Override public boolean canTravelTo(int dimId) { throw new UnsupportedOperationException(); }
        @Override public zmaster587.advancedRocketry.api.dimension.IDimensionProperties getDimensionProperties(int dimId) { throw new UnsupportedOperationException(); }
        @Override public zmaster587.advancedRocketry.api.dimension.solar.StellarBody getStar(int id) { throw new UnsupportedOperationException(); }
        @Override public boolean isDimensionCreated(int dimId) { throw new UnsupportedOperationException(); }
        @Override public boolean areDimensionsInSamePlanetMoonSystem(int a, int b) { throw new UnsupportedOperationException(); }
    }

    @Test
    public void gravityClampsBelowMin() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"NoG\" DIMID=\"7203\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <gravitationalMultiplier>-100</gravitationalMultiplier>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertEquals("gravity must clamp to MIN_GRAVITY/100",
                DimensionProperties.MIN_GRAVITY / 100f,
                props.getGravitationalMultiplier(), 1e-6);
    }

    // ---- laser drill ores: tolerant ore-name resolution ----------------------

    /**
     * Regression for dercodeKoenig/AdvancedRocketry#77 — creating a world with a
     * subset of mods crashed with {@code IndexOutOfBoundsException: Index 0 out of
     * bounds for length 0} at the {@code <laserDrillOres>} parse path.
     *
     * <p>{@link OreDictionary#doesOreNameExist} returns {@code true} for any ore
     * name that has merely been <em>reserved</em> in the dictionary, even when no
     * items are registered under it (the mod that would provide them isn't
     * installed). The old code did {@code getOres(name).get(0)} on that empty
     * list &rarr; crash that killed the server via {@code FMLCommonHandler.exitJava}.
     * The parser must now skip the entry and keep loading.</p>
     */
    @Test
    public void laserDrillOresReservedButEmptyOreNameDoesNotCrash() throws Exception {
        String phantom = "arPhantomOreNoItems77";
        OreDictionary.getOreID(phantom); // reserve the name without registering items
        assertTrue("precondition: name must be reserved in the dictionary",
                OreDictionary.doesOreNameExist(phantom));
        assertTrue("precondition: no items registered under the name",
                OreDictionary.getOres(phantom).isEmpty());

        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"PhantomOreWorld\" DIMID=\"7400\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <laserDrillOres>" + phantom + "</laserDrillOres>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertTrue("unresolved ore name must be skipped, not added and not thrown on",
                props.laserDrillOres.isEmpty());
    }

    /**
     * Pins the trim + count handling on the {@code <laserDrillOres>} path:
     * whitespace around the ore name and the {@code ;count} suffix must be
     * tolerated, and the resolved stack must be a {@code copy()} so writing its
     * count back does not mutate the shared OreDictionary prototype.
     */
    @Test
    public void laserDrillOresTrimsWhitespaceParsesCountAndCopiesStack() throws Exception {
        String oreName = "arTestDrillOreWithItem77";
        OreDictionary.registerOre(oreName, new ItemStack(Items.IRON_INGOT));

        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"DrillOreWorld\" DIMID=\"7401\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <laserDrillOres>  " + oreName + " ; 5 </laserDrillOres>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertEquals("whitespace-padded ore name must resolve to exactly 1 entry",
                1, props.laserDrillOres.size());
        assertEquals("count must be parsed from the trimmed second field",
                5, props.laserDrillOres.get(0).getCount());
        assertEquals("copy() must protect the OreDictionary prototype from count mutation",
                1, OreDictionary.getOres(oreName).get(0).getCount());
    }

    // ---- fault tolerance: skip bad planet, crash loudly on broken file -------

    /**
     * Issue #77 broader fix (A) — a single malformed planet must not take down
     * the whole config. One well-formed planet plus one with a non-numeric
     * {@code rainMarker} (throws deep in {@code readPlanetFromNode}): the bad one
     * is skipped, the good one survives, and the loader returns normally instead
     * of killing the JVM via {@code FMLCommonHandler.exitJava} — the test
     * returning at all proves no silent process exit happened.
     */
    @Test
    public void malformedPlanetIsSkippedAndOthersStillLoad() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"GoodWorld\" DIMID=\"7500\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "</planet>\n"
              + "<planet name=\"BrokenWorld\" DIMID=\"7501\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <rainMarker>NOT_A_NUMBER</rainMarker>\n"
              + "</planet>\n")));
        assertEquals("only the well-formed planet must survive", 1, coupling.dims.size());
        assertEquals("GoodWorld", coupling.dims.get(0).getName());
    }

    /**
     * Issue #77 broader fix (C) — a completely unparseable planetDefs file is a
     * genuinely fatal/structural error. It must throw so that Forge produces a
     * normal crash report at server start, rather than the old silent
     * {@code FMLCommonHandler.exitJava} that closed the window with no report.
     * Catching a {@link RuntimeException} here (instead of the test JVM dying)
     * is the testable proxy for "crashes with a report, doesn't exit silently".
     */
    @Test
    public void completelyMalformedXmlThrowsForCrashReportInsteadOfSilentExit() throws Exception {
        File garbage = tempFolder.newFile("garbage-planetDefs.xml");
        Files.write(garbage.toPath(),
                "this is not xml <<< &&& >>>".getBytes(StandardCharsets.UTF_8));

        XMLPlanetLoader loader = new XMLPlanetLoader();
        try {
            loader.loadPlanetsOrThrow(garbage);
            fail("unparseable planetDefs XML must throw so Forge generates a crash "
                    + "report — it must not be swallowed or trigger a silent exitJava");
        } catch (RuntimeException expected) {
            assertNotNull("fatal load failure must carry a diagnostic message",
                    expected.getMessage());
            assertTrue("the message should point at the planetDefs XML file: "
                            + expected.getMessage(),
                    expected.getMessage().contains("planetDefs XML"));
        }
    }

    // ---- oregen persistence (issue #73) --------------------------------------

    /**
     * Issue #73 — per-planet {@code <oreGen>} must survive the planetDefs.xml
     * write/read round-trip. AR persists a planet's ore generation config only
     * through the per-world planetDefs.xml (it is NOT written to the per-dimension
     * NBT), so {@code writeXML} &rarr; {@code readAllPlanets} losing the {@code <oreGen>}
     * block is exactly the "oregen doesn't stick to the worldsave" bug kaduvill
     * traced back to 2019. This pins the round-trip so it can't regress.
     */
    @Test
    public void oreGenPropertiesSurviveWriteReadRoundTrip() throws Exception {
        Block ironOre = Block.getBlockFromName("minecraft:iron_ore");
        assertNotNull("precondition: minecraft:iron_ore must be registered", ironOre);

        zmaster587.advancedRocketry.api.dimension.solar.StellarBody star =
                new zmaster587.advancedRocketry.api.dimension.solar.StellarBody();
        star.setId(7600);
        star.setName("OreGenStar");
        star.setTemperature(120);
        star.setSize(1.0f);
        star.setBlackHole(false);

        DimensionProperties planet = new DimensionProperties(7601, "OreGenWorld");
        planet.setStar(star);

        OreGenProperties ore = new OreGenProperties();
        ore.addEntry(ironOre.getDefaultState(), 5, 60, 8, 20);
        planet.oreProperties = ore;

        star.addPlanet(planet);

        String xml = XMLPlanetLoader.writeXML(new SingleStarGalaxyFixture(star));
        assertTrue("writeXML must emit the <oreGen> block", xml.contains("oreGen"));
        assertTrue("writeXML must reference the ore block by registry name",
                xml.contains("minecraft:iron_ore"));

        File out = tempFolder.newFile("oregen-planets.xml");
        Files.write(out.toPath(), xml.getBytes(StandardCharsets.UTF_8));

        XMLPlanetLoader reader = new XMLPlanetLoader();
        assertTrue("loadFile must accept the written XML", reader.loadFile(out));
        DimensionPropertyCoupling restored = reader.readAllPlanets();

        assertEquals(1, restored.dims.size());
        OreGenProperties restoredOre = restored.dims.get(0).oreProperties;
        assertNotNull("oreProperties must round-trip, not be dropped", restoredOre);
        assertEquals("exactly one ore entry must survive", 1, restoredOre.getOreEntries().size());

        OreEntry entry = restoredOre.getOreEntries().get(0);
        assertEquals("ore block must round-trip", ironOre, entry.getBlockState().getBlock());
        assertEquals("minHeight must round-trip", 5, entry.getMinHeight());
        assertEquals("maxHeight must round-trip", 60, entry.getMaxHeight());
        assertEquals("clumpSize must round-trip", 8, entry.getClumpSize());
        assertEquals("chancePerChunk must round-trip", 20, entry.getChancePerChunk());
    }

    @Test
    public void galaxyGenElementParsesIntoConfig() throws IOException {
        DimensionPropertyCoupling c = parse(galaxy(
                "<galaxyGen density=\"0.42\" minSpacing=\"7\" galaxySpacing=\"900000\""
                        + " galaxyDensity=\"0.3\">\n"
                        + "  <starType temp=\"55\" minSize=\"0.7\" maxSize=\"1.1\" weight=\"3\"/>\n"
                        + "  <starType temp=\"180\" minSize=\"1.5\" maxSize=\"2.2\" weight=\"1\"/>\n"
                        + "</galaxyGen>\n"));
        assertNotNull("a <galaxyGen> element must parse into a config", c.galaxyGenConfig);
        assertEquals(0.42d, c.galaxyGenConfig.density, 1e-9);
        assertEquals(7, c.galaxyGenConfig.minSpacing);
        assertEquals(900000L, c.galaxyGenConfig.galaxySpacing);
        assertEquals(0.3d, c.galaxyGenConfig.galaxyDensity, 1e-9);
        assertFalse("the stock galaxy archetypes stand in when XML declares none",
                c.galaxyGenConfig.galaxyTypes.isEmpty());
        assertEquals(2, c.galaxyGenConfig.starTypes.size());
        assertEquals(55, c.galaxyGenConfig.starTypes.get(0).temperature);
        assertEquals(3, c.galaxyGenConfig.starTypes.get(0).weight);
    }

    @Test
    public void galaxyTypeChildrenReplaceTheStockTable() throws Exception {
        // The one table a pack is most likely to want to touch, and the reason it is authorable at
        // all: how flat a disc is has no derivation — it is a free parameter of the shape.
        DimensionPropertyCoupling c = parse(galaxy(
                "<galaxyGen density=\"0.4\" minSpacing=\"7\">\n"
                        + "  <galaxyType name=\"Fat Disc\" profile=\"DISC\" minRadius=\"1000\""
                        + " maxRadius=\"1800\" thickness=\"0.25\" arms=\"3\" rotationSpeed=\"180\""
                        + " coreFraction=\"0.2\" weight=\"5\"/>\n"
                        + "</galaxyGen>\n"));
        assertNotNull(c.galaxyGenConfig);
        assertEquals("one <galaxyType> must REPLACE the stock table, not extend it", 1,
                c.galaxyGenConfig.galaxyTypes.size());
        GalaxyGenConfig.GalaxyType t = c.galaxyGenConfig.galaxyTypes.get(0);
        assertEquals("Fat Disc", t.name);
        assertEquals(GalaxyGenConfig.GalaxyProfile.DISC, t.profile);
        assertEquals(1000d, t.minRadiusLy, 1e-9);
        assertEquals(1800d, t.maxRadiusLy, 1e-9);
        assertEquals("disc thickness must be what the pack asked for", 0.25d, t.scaleHeightRatio, 1e-9);
        assertEquals(3, t.armCount);
        assertEquals(180d, t.rotationSpeedKmS, 1e-9);
        assertEquals(0.2d, t.coreRadiusFraction, 1e-9);
        assertEquals(5, t.weight);
    }

    @Test
    public void galaxyTypesRoundTripThroughWriteXml() throws IOException {
        // This file is REWRITTEN on every world save, so a table the writer does not emit is a table a
        // pack silently loses the first time anybody saves.
        GalaxyGenConfig parsed = parse(galaxy(
                "<galaxyGen density=\"0.3\" minSpacing=\"9\">\n"
                        + "  <galaxyType name=\"Thin\" profile=\"DISC\" thickness=\"0.005\" arms=\"4\""
                        + " weight=\"2\"/>\n"
                        + "  <galaxyType name=\"Blob\" profile=\"SPHEROID\" thickness=\"0.9\" arms=\"0\""
                        + " weight=\"11\"/>\n"
                        + "</galaxyGen>\n")).galaxyGenConfig;
        try {
            UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(parsed));
            String written = XMLPlanetLoader.writeXML(DimensionManager.getInstance());
            File f = tempFolder.newFile();
            Files.write(f.toPath(), written.getBytes(StandardCharsets.UTF_8));
            XMLPlanetLoader loader = new XMLPlanetLoader();
            assertTrue(loader.loadFile(f));
            GalaxyGenConfig round = loader.readAllPlanets().galaxyGenConfig;

            assertNotNull(round);
            assertEquals(2, round.galaxyTypes.size());
            assertEquals("Thin", round.galaxyTypes.get(0).name);
            assertEquals(0.005d, round.galaxyTypes.get(0).scaleHeightRatio, 1e-9);
            assertEquals(GalaxyGenConfig.GalaxyProfile.SPHEROID, round.galaxyTypes.get(1).profile);
            assertEquals(11, round.galaxyTypes.get(1).weight);
        } finally {
            UniverseRegistry.setGenerator(null);
        }
    }

    @Test
    public void anAuthoredAnchorIsDeclaredAgainstAGalaxy() throws Exception {
        // A galaxy fills about three thousandths of a percent of its own lattice cell, so an absolute
        // declaration would land in intergalactic space on virtually every seed. An unqualified
        // declaration means `home`, which is what a pack that never thinks about galaxies gets.
        DimensionPropertyCoupling c = parse(galaxy(
                "<galaxyGen density=\"0.4\" minSpacing=\"7\"/>\n"
                        + "<star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                        + " galacticCoord=\"0,0,0\"/>\n"
                        + "<star name=\"Far\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                        + " galaxy=\"4,-1,2\" galacticCoord=\"500,0,0\"/>\n"));
        assertEquals(2, c.anchorCoords.size());
        GalacticAnchor sol = c.anchorCoords.get(c.stars.get(0).getId());
        GalacticAnchor far = c.anchorCoords.get(c.stars.get(1).getId());
        assertTrue("an unqualified anchor lives in the home galaxy", sol.galaxy().isHome());
        assertEquals(GalaxyKey.of(4L, -1L, 2L), far.galaxy());
        assertEquals(500L, far.local().sectorX());

        assertEquals("every non-home galaxy an anchor named must be reserved", 1,
                c.declaredGalaxies.size());
        assertTrue("and the config must carry it, so its cell is seated on every seed",
                c.galaxyGenConfig.reservedGalaxies.contains(GalaxyKey.of(4L, -1L, 2L)));
    }

    @Test
    public void theWrittenCatalogueTellsAnAuthorWhatItCostsToEditIt() throws Exception {
        // Both facts are otherwise discoverable only from source, and by then the damage is done: the
        // system is already in the void, or the universe is already rerolled under a live save. The
        // WRITER emits it, because this file is rewritten on every save and a shipped template would
        // be replaced by the first one.
        String written = XMLPlanetLoader.writeXML(DimensionManager.getInstance());
        assertTrue("the written catalogue must say an anchor is galaxy-local: " + written,
                written.contains("GALAXY-LOCAL"));
        assertTrue("and that the home galaxy always exists",
                written.contains("home") && written.contains("800 light years"));
        assertTrue("and that changing a generator parameter mid-save is undefined",
                written.contains("UNDEFINED BEHAVIOUR"));

        // And it must still be a document that parses, or the notice would cost the catalogue.
        File f = tempFolder.newFile();
        Files.write(f.toPath(), written.getBytes(StandardCharsets.UTF_8));
        XMLPlanetLoader loader = new XMLPlanetLoader();
        assertTrue("a catalogue carrying the notice must still load", loader.loadFile(f));
        assertNotNull(loader.readAllPlanets());
    }

    @Test
    public void absentGalaxyGenLeavesConfigNull() throws IOException {
        DimensionPropertyCoupling c = parse(galaxy(star("Sol", "")));
        assertNull("no <galaxyGen> means authored-only (null config)", c.galaxyGenConfig);
    }

    @Test
    public void galaxyGenRoundTripsThroughWriteXml() throws IOException {
        GalaxyGenConfig parsed = parse(galaxy(
                "<galaxyGen density=\"0.25\" minSpacing=\"5\" galaxySpacing=\"1200000\""
                        + " galaxyDensity=\"0.45\">\n"
                        + "  <starType temp=\"60\" minSize=\"0.6\" maxSize=\"1.0\" weight=\"9\"/>\n"
                        + "</galaxyGen>\n")).galaxyGenConfig;

        try {
            UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(parsed));
            String written = XMLPlanetLoader.writeXML(DimensionManager.getInstance());

            File f = tempFolder.newFile();
            Files.write(f.toPath(), written.getBytes(StandardCharsets.UTF_8));
            XMLPlanetLoader loader = new XMLPlanetLoader();
            assertTrue(loader.loadFile(f));
            GalaxyGenConfig round = loader.readAllPlanets().galaxyGenConfig;

            assertNotNull("the written galaxy must round-trip its <galaxyGen>", round);
            assertEquals(0.25d, round.density, 1e-9);
            assertEquals(5, round.minSpacing);
            assertEquals(1200000L, round.galaxySpacing);
            assertEquals(0.45d, round.galaxyDensity, 1e-9);
            assertEquals(60, round.starTypes.get(0).temperature);
            assertEquals(9, round.starTypes.get(0).weight);
        } finally {
            UniverseRegistry.setGenerator(null); // restore the authored-only default
        }
    }

    // ---- terrainSource -------------------------------------------------------

    @Test
    public void terrainSourceModWorldtypeParsesFromXml() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"ForeignWorld\" DIMID=\"7700\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <terrainSource>MOD_WORLDTYPE</terrainSource>\n"
              + "  <terrainWorldType>BIOMESOP</terrainWorldType>\n"
              + "</planet>\n")));
        DimensionProperties props = coupling.dims.get(0);
        assertSame(TerrainSource.MOD_WORLDTYPE, props.getTerrainSource());
        assertEquals("BIOMESOP", props.getTerrainWorldType());
    }

    @Test
    public void unknownTerrainSourceDegradesToNativeWithoutSkippingPlanet() throws Exception {
        DimensionPropertyCoupling coupling = parse(galaxy(star("Sol",
                "<planet name=\"TypoWorld\" DIMID=\"7701\">\n"
              + "  <isKnown>true</isKnown>\n"
              + "  <terrainSource>NONSENSE</terrainSource>\n"
              + "</planet>\n")));
        assertEquals("a bogus terrainSource must not skip the planet", 1, coupling.dims.size());
        assertSame("a bogus terrainSource must degrade to NATIVE",
                TerrainSource.NATIVE, coupling.dims.get(0).getTerrainSource());
    }

    @Test
    public void terrainSourceRoundTripsThroughWriteXml() throws Exception {
        StellarBody star = new StellarBody();
        star.setId(7710);
        star.setName("TemplateStar");
        star.setTemperature(120);
        star.setSize(1.0f);
        star.setBlackHole(false);

        DimensionProperties planet = new DimensionProperties(7711, "TemplatePlanet");
        planet.setTerrainSource(TerrainSource.TEMPLATE);
        planet.setTerrainTemplate("packplanet");
        planet.setStar(star);
        star.addPlanet(planet);

        String xml = XMLPlanetLoader.writeXML(new SingleStarGalaxyFixture(star));
        assertTrue("writeXML must emit terrainSource", xml.contains("terrainSource"));
        assertTrue("writeXML must emit the template name", xml.contains("packplanet"));

        File out = tempFolder.newFile("terrain-source-planets.xml");
        Files.write(out.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        XMLPlanetLoader reader = new XMLPlanetLoader();
        assertTrue(reader.loadFile(out));
        DimensionPropertyCoupling restored = reader.readAllPlanets();

        assertEquals(1, restored.dims.size());
        DimensionProperties rp = restored.dims.get(0);
        assertSame(TerrainSource.TEMPLATE, rp.getTerrainSource());
        assertEquals("packplanet", rp.getTerrainTemplate());
    }

    @Test
    public void nativeTerrainSourceEmitsNoXmlElements() throws Exception {
        StellarBody star = new StellarBody();
        star.setId(7720);
        star.setName("PlainStar");
        star.setTemperature(120);
        star.setSize(1.0f);
        star.setBlackHole(false);

        DimensionProperties planet = new DimensionProperties(7721, "PlainWorld");
        planet.setStar(star);
        star.addPlanet(planet);

        String xml = XMLPlanetLoader.writeXML(new SingleStarGalaxyFixture(star));
        assertFalse("NATIVE planet XML must not contain a terrainSource element", xml.contains("terrainSource"));
        assertFalse(xml.contains("terrainWorldType"));
        assertFalse(xml.contains("terrainTemplate"));
    }

    // ---- helpers -------------------------------------------------------------

    private static DimensionProperties findByName(List<DimensionProperties> list, String name) {
        for (DimensionProperties p : list) {
            if (name.equals(p.getName())) return p;
        }
        return null;
    }
}
