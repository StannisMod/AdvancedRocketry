package zmaster587.advancedRocketry.util;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTException;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeManager.BiomeEntry;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.oredict.OreDictionary;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.IGalaxy;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.dimension.TerrainSource;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.PlanetTypePreset;
import zmaster587.advancedRocketry.universe.PlanetTypes;
import zmaster587.advancedRocketry.universe.TerrainOption;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import javax.annotation.Nonnull;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class XMLPlanetLoader {


    private static final String ATTR_TEMP = "temp";
    private static final String GENERATEGEODES = "generateGeodes";
    private static final String GENERATESTRUCTURES = "generateStructures";
    private static final String GENERATEVOLCANOS = "generateVolcanos";
    private static final String GENERATECRATERS = "generateCraters";
    private static final String GENERATECAVES = "generateCaves";
    private static final String ELEMENT_GALAXY = "galaxy";
    private static final String ELEMENT_STAR = "star";
    // Optional procedural-galaxy generation. Present -> a clustered generator fills the void
    // between authored anchors; absent -> authored anchors only. All attrs are balance knobs with defaults.
    private static final String ELEMENT_GALAXYGEN = "galaxyGen";
    private static final String ELEMENT_STARTYPE = "starType";
    // A planet TYPE preset: the named region of parameter space a world can land in, plus everything
    // that follows from being that kind of world. Present -> replaces the whole stock table.
    private static final String ELEMENT_PLANETTYPE = "planetType";
    private static final String ELEMENT_TYPE_PRESSURE = "pressure";
    private static final String ELEMENT_TYPE_TEMPERATURE = "temperature";
    private static final String ELEMENT_TYPE_GRAVITY = "gravity";
    private static final String ELEMENT_TYPE_TERRAIN = "terrain";
    private static final String ELEMENT_TYPE_GEN = "gen";
    private static final String ATTR_MIN = "min";
    private static final String ATTR_MAX = "max";
    private static final String ATTR_SOURCE = "source";
    private static final String ATTR_WORLDTYPE = "worldType";
    private static final String ATTR_TEMPLATE_PATH = "path";
    private static final String ATTR_GENTYPE = "genType";
    private static final String ATTR_OPTIONS = "options";
    private static final String ATTR_GASGIANT = "gasGiant";
    private static final String ATTR_ALLOWS_OXYGEN = "allowsOxygen";
    private static final String ATTR_TIDALLY_LOCKABLE = "tidallyLockable";
    private static final String ATTR_DENSITY = "density";
    private static final String ATTR_MINSPACING = "minSpacing";
    private static final String ATTR_GALAXYSPACING = "galaxySpacing";
    private static final String ATTR_GALAXYDENSITY = "galaxyDensity";
    private static final String ATTR_MINSIZE = "minSize";
    private static final String ATTR_MAXSIZE = "maxSize";
    private static final String ELEMENT_PLANET = "planet";
    private static final String ATTR_BLACKHOLE = "blackHole";
    private static final String ATTR_BLACKHOLE_DISK_ANGLE = "diskAngle";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_X = "x";
    private static final String ATTR_Y = "y";
    // Explicit galactic address of an authored anchor system: "sectorX,sectorY,sectorZ" (cell indices).
    // Absent -> the system falls back to a deterministic cell (Sol -> origin). See UniverseRegistry.
    private static final String ATTR_GALACTIC_COORD = "galacticCoord";
    private static final String ATTR_SIZE = "size";
    private static final String ATTR_NUMPLANETS = "numPlanets";
    private static final String ATTR_NUMGASPLANETS = "numGasGiants";
    private static final String ATTR_COMPANION_ORBIT = "orbitalDistance";
    private static final String ATTR_COMPANION_THETA = "orbitalTheta";
    private static final String ATTR_DIMID = "DIMID";
    private static final String ATTR_NATIVEDIM = "dimMapping";
    private static final String ATTR_ICON = "customIcon";
    private static final String ELEMENT_ISKNOWN = "isKnown";
    private static final String ELEMENT_HASRINGS = "hasRings";
    private static final String ELEMENT_RING_ANGLE = "ringAngle";
    private static final String ELEMENT_RINGCOLOR = "ringColor";
    private static final String ELEMENT_GASGIANT = "GasGiant";
    private static final String ELEMENT_GAS = "gas";
    private static final String ELEMENT_FOGCOLOR = "fogColor";
    private static final String ELEMENT_SKYCOLOR = "skyColor";
    private static final String ELEMENT_GRAVITY = "gravitationalMultiplier";
    private static final String ELEMENT_MASS = "mass";
    private static final String ELEMENT_RADIUS = "radius";
    private static final String ELEMENT_TIDALLY_LOCKED = "tidallyLocked";
    private static final String ELEMENT_METALLICITY = "metallicity";
    private static final String ELEMENT_DISTANCE = "orbitalDistance";
    private static final String ELEMENT_BASEORBITTHETA = "orbitalTheta";
    private static final String ELEMENT_PHI = "orbitalPhi";
    private static final String ELEMENT_RETROGRADE = "retrograde";
    private static final String AVG_TEMPERATURE = "avgTemperature";
    private static final String ELEMENT_PERIOD = "rotationalPeriod";
    private static final String ELEMENT_HASOXYGEN = "hasOxygen";
    private static final String ELEMENT_ATMDENSITY = "atmosphereDensity";
    private static final String ELEMENT_SEALEVEL = "seaLevel";
    private static final String ELEMENT_ORBIT_HEIGHT = "orbitHeight";
    //private static final String ELEMENT_TARGETSEALEVEL = "targetseaLevel";
    private static final String ELEMENT_GENTYPE = "genType";
    private static final String ELEMENT_TERRAIN_SOURCE = "terrainSource";
    private static final String ELEMENT_TERRAIN_WORLDTYPE = "terrainWorldType";
    private static final String ELEMENT_TERRAIN_TEMPLATE = "terrainTemplate";
    private static final String ELEMENT_TERRAIN_GENERATOR_OPTIONS = "terrainGeneratorOptions";
    private static final String ELEMENT_RIVER_OVERRIDE = "forceRiverGeneration";
    private static final String ELEMENT_OREGEN = "oreGen";
    private static final String ELEMENT_LASER_DRILL_ORES = "laserDrillOres";
    private static final String ELEMENT_GEODE_ORES = "geodeOres";
    private static final String ELEMENT_CRATER_ORES = "craterOres";
    private static final String ELEMENT_BIOMEIDS = "biomeIds";
    private static final String ELEMENT_CRATER_BIOMEIDS = "craterBiomeWeights";
    private static final String ELEMENT_ARTIFACT = "artifact";
    private static final String ELEMENT_OCEANBLOCK = "oceanBlock";
    private static final String ELEMENT_FILLERBLOCK = "fillerBlock";
    private static final String ELEMENT_SPAWNABLE = "spawnable";
    private static final String ELEMENT_CRATER_MULTIPLIER = "craterFrequencyMultiplier";
    private static final String ELEMENT_VOLCANO_MULTIPLIER = "volcanoFrequencyMultiplier";
    private static final String ELEMENT_GEODE_MULTIPLIER = "geodeFrequencyMultiplier";
    private static final String ELEMENT_CAN_DECORATE = "hasShading";
    private static final String ELEMENT_COLOR_OVERRIDE = "hasColorOverride";
    private static final String ELEMENT_SKYOVERRIDE = "skyRenderOverride";
    private static final String ATTR_WEIGHT = "weight";
    private static final String ATTR_GROUPMIN = "groupMin";
    private static final String ATTR_GROUPMAX = "groupMax";
    private static final String ATTR_NBT = "nbt";
    // Weather
    private static final String ELEMENT_RAIN_START_LENGTH = "rainStartLength";
    private static final String ELEMENT_THUNDER_START_LENGTH = "thunderStartLength";
    private static final String ELEMENT_RAIN_PROLONGATION_LENGTH = "rainProlongationLength";
    private static final String ELEMENT_THUNDER_PROLONGATION_LENGTH = "thunderProlongationLength";
    private static final String ELEMENT_RAIN_MARKER = "rainMarker";
    private static final String ELEMENT_THUNDER_MARKER = "thunderMarker";
    private static final String ELEMENT_ACIDIC_RAIN = "acidicRain";

    NodeList currentList;
    private Document doc;
    private int currentNodeIndex;
    private int starId;
    private int offset;

    private HashMap<StellarBody, Integer> maxPlanetNumber = new HashMap<>();
    private HashMap<StellarBody, Integer> maxGasPlanetNumber = new HashMap<>();

    public XMLPlanetLoader() {
        doc = null;
        currentNodeIndex = -1;
        starId = 0;
    }

    /**
     * Resolve a system's authored galactic coordinate from the live universe registry, or {@code null} when
     * no server/registry is reachable (so a no-server unit-test export simply omits the attribute).
     */
    private static GalacticCoord anchorCoordForWrite(int starId) {
        MinecraftServer server;
        try {
            server = FMLCommonHandler.instance().getMinecraftServerInstance();
        } catch (Exception e) {
            // No live server context (e.g. a headless test export where the sided delegate is unset) —
            // omit the attribute rather than fail the whole XML write.
            return null;
        }
        if (server == null) {
            return null;
        }
        UniverseRegistry registry = UniverseRegistry.get(server);
        if (registry == null) {
            return null;
        }
        return registry.coordForSystem(starId).orElse(null);
    }

    private static String attr(Node node, String name) {
        if (node == null || !node.hasAttributes()) {
            return null;
        }
        Node a = node.getAttributes().getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static int attrInt(Node node, String name, int def) {
        String v = attr(node, name);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            AdvancedRocketry.logger.warn("Invalid " + name + " in <galaxyGen>: " + v);
            return def;
        }
    }

    private static long attrLong(Node node, String name, long def) {
        String v = attr(node, name);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            AdvancedRocketry.logger.warn("Invalid " + name + " in <galaxyGen>: " + v);
            return def;
        }
    }

    private static double attrDouble(Node node, String name, double def) {
        String v = attr(node, name);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            AdvancedRocketry.logger.warn("Invalid " + name + " in <galaxyGen>: " + v);
            return def;
        }
    }

    /** Parse a {@code <galaxyGen>} element (attrs + {@code <starType>} children) into a config. */
    private GalaxyGenConfig readGalaxyGen(Node node) {
        GalaxyGenConfig defaults = GalaxyGenConfig.defaults();
        double density = attrDouble(node, ATTR_DENSITY, defaults.density);
        int minSpacing = attrInt(node, ATTR_MINSPACING, defaults.minSpacing);
        long galaxySpacing = attrLong(node, ATTR_GALAXYSPACING, defaults.galaxySpacing);
        double galaxyDensity = attrDouble(node, ATTR_GALAXYDENSITY, defaults.galaxyDensity);

        List<GalaxyGenConfig.StarType> types = new ArrayList<>();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (ELEMENT_STARTYPE.equalsIgnoreCase(child.getNodeName())) {
                types.add(new GalaxyGenConfig.StarType(
                        attrInt(child, ATTR_TEMP, 100),
                        (float) attrDouble(child, ATTR_MINSIZE, 0.8d),
                        (float) attrDouble(child, ATTR_MAXSIZE, 1.2d),
                        attrInt(child, ATTR_WEIGHT, 1)));
            }
        }
        // An empty <starType> list falls back to the default archetypes (handled by the config ctor).
        // The GALAXY archetype table is not authorable yet: it ships as a stock table in code, and a
        // <galaxyType> element is the natural place to override it when a pack needs to.
        return new GalaxyGenConfig(minSpacing, density, galaxySpacing, galaxyDensity, types, null);
    }

    /**
     * Parse one {@code <planetType>} element into a preset.
     *
     * <pre>{@code
     * <planetType name="ice" weight="20" allowsOxygen="false">
     *   <pressure    min="0"  max="80"/>
     *   <temperature min="0"  max="175"/>
     *   <gravity     min="10" max="140"/>
     *   <terrain>
     *     <gen source="MOD_WORLDTYPE" worldType="RTG" options="" weight="3"/>
     *     <gen source="NATIVE"        genType="0"                weight="2"/>
     *     <gen source="TEMPLATE"      path="frozen_ruins"        weight="1"/>
     *   </terrain>
     *   <biomeIds>advancedrocketry:moondark;10,minecraft:ice_flats;30</biomeIds>
     *   <oreGen>...</oreGen>
     * </planetType>
     * }</pre>
     *
     * <p>Ranges are in the game's own units: pressure in atmosphere-density units (100 = 1 atm),
     * temperature in KELVIN, gravity in percent of Earth's. Every attribute has a default, so a
     * {@code <planetType name="x"/>} with nothing else is a valid (if very greedy) preset.</p>
     */
    private PlanetTypePreset readPlanetType(Node node) {
        String name = attr(node, ATTR_NAME);
        PlanetTypePreset.Builder b = PlanetTypePreset.builder(name == null ? "" : name)
                .weight(attrInt(node, ATTR_WEIGHT, 10))
                .gasGiant(attrBool(node, ATTR_GASGIANT, false))
                .allowsOxygen(attrBool(node, ATTR_ALLOWS_OXYGEN, false))
                .tidallyLockable(attrBool(node, ATTR_TIDALLY_LOCKABLE, true));

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String tag = child.getNodeName();
            if (ELEMENT_TYPE_PRESSURE.equalsIgnoreCase(tag)) {
                b.pressure(attrInt(child, ATTR_MIN, DimensionProperties.MIN_ATM_PRESSURE),
                        attrInt(child, ATTR_MAX, DimensionProperties.MAX_ATM_PRESSURE));
            } else if (ELEMENT_TYPE_TEMPERATURE.equalsIgnoreCase(tag)) {
                b.temperature(attrInt(child, ATTR_MIN, 0), attrInt(child, ATTR_MAX, 5000));
            } else if (ELEMENT_TYPE_GRAVITY.equalsIgnoreCase(tag)) {
                b.gravity(attrInt(child, ATTR_MIN, DimensionProperties.MIN_GRAVITY),
                        attrInt(child, ATTR_MAX, DimensionProperties.MAX_GRAVITY));
            } else if (ELEMENT_TYPE_TERRAIN.equalsIgnoreCase(tag)) {
                NodeList gens = child.getChildNodes();
                for (int j = 0; j < gens.getLength(); j++) {
                    Node gen = gens.item(j);
                    if (ELEMENT_TYPE_GEN.equalsIgnoreCase(gen.getNodeName())) {
                        b.terrain(new TerrainOption(
                                TerrainSource.byName(attr(gen, ATTR_SOURCE)),
                                attr(gen, ATTR_WORLDTYPE),
                                attr(gen, ATTR_TEMPLATE_PATH),
                                attrInt(gen, ATTR_GENTYPE, 0),
                                attr(gen, ATTR_OPTIONS),
                                attrInt(gen, ATTR_WEIGHT, 1)));
                    }
                }
            } else if (ELEMENT_BIOMEIDS.equalsIgnoreCase(tag)) {
                b.biomes(child.getTextContent());
            } else if (ELEMENT_OREGEN.equalsIgnoreCase(tag)) {
                b.ores(XMLOreLoader.loadOre(child));
            } else if (ELEMENT_SEALEVEL.equalsIgnoreCase(tag)) {
                b.seaLevel(parseIntOr(child.getTextContent(), PlanetTypePreset.SEA_LEVEL_UNSET));
            } else if (ELEMENT_OCEANBLOCK.equalsIgnoreCase(tag)) {
                b.oceanBlock(child.getTextContent());
            }
        }
        return b.build();
    }

    /**
     * Apply an authored biome palette — the {@code <biomeIds>} format — to a planet.
     *
     * <p>Public and shared because a planet TYPE declares its palette in exactly the same language a
     * planet does, and a realized procedural world has to mean by it precisely what an authored world
     * means. Two parsers for one format is two chances for a pack's entry to work in one place and be
     * ignored in the other.</p>
     *
     * <p>Format: comma-separated entries of {@code biome} or {@code biome;weight}, where {@code biome}
     * is a registry name (preferred) or a raw numeric id (legacy, and modset-dependent). A malformed
     * entry is warned about and skipped; it never aborts the rest of the list.</p>
     */
    public static void applyBiomeList(DimensionProperties properties, String authoredList) {
        if (properties == null || authoredList == null || authoredList.trim().isEmpty()) {
            return;
        }
        for (String s : authoredList.split(",")) {
            if (s.trim().isEmpty()) {
                continue;
            }
            int biomeWeight = 30;
            String[] weightSplit = s.trim().split(";");

            //Try to get a weight out of the semicolon separator
            if (weightSplit.length > 1) {
                try {
                    biomeWeight = Integer.parseInt(weightSplit[1].trim());
                    if (biomeWeight == 0) {
                        AdvancedRocketry.logger.warn("Weight cannot be 0! Setting weight to default");
                        biomeWeight = 30;
                    }
                } catch (NumberFormatException e) {
                    biomeWeight = 30;
                    AdvancedRocketry.logger.warn(weightSplit[1] + " is not a valid biome weight");
                }
            }

            //Check whether we have numeric IDs (bad!) or RL ids
            ResourceLocation location = new ResourceLocation(weightSplit[0]);
            if (Biome.REGISTRY.containsKey(location)) {
                Biome biome = Biome.REGISTRY.getObject(location);
                if (biome == null)
                    AdvancedRocketry.logger.warn("Error adding " + weightSplit[0]); //TODO: more detailed error msg
                else
                    properties.addBiomeWeighted(biome, biomeWeight);
            } else {
                try {
                    int biome = Integer.parseInt(weightSplit[0]);

                    if (!properties.addBiome(biome))
                        AdvancedRocketry.logger.warn(weightSplit[0] + " is not a valid biome id"); //TODO: more detailed error msg
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn(weightSplit[0] + " is not a valid biome id or name"); //TODO: more detailed error msg
                }
            }
        }
    }

    private static boolean attrBool(Node node, String name, boolean def) {
        String v = attr(node, name);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static int parseIntOr(String text, int def) {
        if (text == null || text.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Emit a preset so a re-read round-trips the authored table. */
    private static Element writePlanetType(Document doc, PlanetTypePreset preset) {
        Element e = doc.createElement(ELEMENT_PLANETTYPE);
        e.setAttribute(ATTR_NAME, preset.name());
        e.setAttribute(ATTR_WEIGHT, Integer.toString(preset.weight()));
        if (preset.gasGiant()) {
            e.setAttribute(ATTR_GASGIANT, "true");
        }
        if (preset.allowsOxygen()) {
            e.setAttribute(ATTR_ALLOWS_OXYGEN, "true");
        }
        if (!preset.tidallyLockable()) {
            e.setAttribute(ATTR_TIDALLY_LOCKABLE, "false");
        }
        e.appendChild(range(doc, ELEMENT_TYPE_PRESSURE, preset.minPressure(), preset.maxPressure()));
        e.appendChild(range(doc, ELEMENT_TYPE_TEMPERATURE, preset.minTemperature(), preset.maxTemperature()));
        e.appendChild(range(doc, ELEMENT_TYPE_GRAVITY, preset.minGravity(), preset.maxGravity()));
        Element terrain = doc.createElement(ELEMENT_TYPE_TERRAIN);
        for (TerrainOption option : preset.terrain()) {
            Element gen = doc.createElement(ELEMENT_TYPE_GEN);
            gen.setAttribute(ATTR_SOURCE, option.source().name());
            if (!option.worldType().isEmpty()) {
                gen.setAttribute(ATTR_WORLDTYPE, option.worldType());
            }
            if (!option.template().isEmpty()) {
                gen.setAttribute(ATTR_TEMPLATE_PATH, option.template());
            }
            if (option.source() == TerrainSource.NATIVE) {
                gen.setAttribute(ATTR_GENTYPE, Integer.toString(option.genType()));
            }
            if (!option.options().isEmpty()) {
                gen.setAttribute(ATTR_OPTIONS, option.options());
            }
            gen.setAttribute(ATTR_WEIGHT, Integer.toString(option.weight()));
            terrain.appendChild(gen);
        }
        e.appendChild(terrain);
        if (!preset.biomes().isEmpty()) {
            e.appendChild(createTextNode(doc, ELEMENT_BIOMEIDS, preset.biomes()));
        }
        if (preset.seaLevel() != PlanetTypePreset.SEA_LEVEL_UNSET) {
            e.appendChild(createTextNode(doc, ELEMENT_SEALEVEL, Integer.toString(preset.seaLevel())));
        }
        if (!preset.oceanBlock().isEmpty()) {
            e.appendChild(createTextNode(doc, ELEMENT_OCEANBLOCK, preset.oceanBlock()));
        }
        return e;
    }

    private static Element range(Document doc, String tag, int min, int max) {
        Element e = doc.createElement(tag);
        e.setAttribute(ATTR_MIN, Integer.toString(min));
        e.setAttribute(ATTR_MAX, Integer.toString(max));
        return e;
    }

    private static Element writeGalaxyGen(Document doc, GalaxyGenConfig cfg) {
        Element e = doc.createElement(ELEMENT_GALAXYGEN);
        e.setAttribute(ATTR_DENSITY, Double.toString(cfg.density));
        e.setAttribute(ATTR_MINSPACING, Integer.toString(cfg.minSpacing));
        e.setAttribute(ATTR_GALAXYSPACING, Long.toString(cfg.galaxySpacing));
        e.setAttribute(ATTR_GALAXYDENSITY, Double.toString(cfg.galaxyDensity));
        for (GalaxyGenConfig.StarType t : cfg.starTypes) {
            Element st = doc.createElement(ELEMENT_STARTYPE);
            st.setAttribute(ATTR_TEMP, Integer.toString(t.temperature));
            st.setAttribute(ATTR_MINSIZE, Float.toString(t.minSize));
            st.setAttribute(ATTR_MAXSIZE, Float.toString(t.maxSize));
            st.setAttribute(ATTR_WEIGHT, Integer.toString(t.weight));
            e.appendChild(st);
        }
        return e;
    }

    public static String writeXML(IGalaxy galaxy) {

        Document doc;
        DocumentBuilder docBuilder;
        try {
            docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            return "";
        }
        doc = docBuilder.newDocument();
        Element galaxyElement = doc.createElement(ELEMENT_GALAXY);
        doc.appendChild(galaxyElement);

        Collection<StellarBody> stars = galaxy.getStars();

        for (StellarBody star : stars) {
            Element nodeStar = doc.createElement(ELEMENT_STAR);
            nodeStar.setAttribute(ATTR_BLACKHOLE, Boolean.toString(star.isBlackHole()));
            nodeStar.setAttribute(ATTR_BLACKHOLE_DISK_ANGLE, Float.toString(star.diskAngle));
            nodeStar.setAttribute(ATTR_NAME, star.getName());
            nodeStar.setAttribute(ATTR_TEMP, Integer.toString(star.getTemperature()));
            nodeStar.setAttribute(ATTR_X, Integer.toString(star.getPosX()));
            nodeStar.setAttribute(ATTR_Y, Integer.toString(star.getPosZ()));
            GalacticCoord starCoord = anchorCoordForWrite(star.getId());
            if (starCoord != null) {
                nodeStar.setAttribute(ATTR_GALACTIC_COORD, UniverseRegistry.formatAnchor(starCoord));
            }
            nodeStar.setAttribute(ATTR_SIZE, Float.toString(star.getSize()));
            nodeStar.setAttribute(ATTR_NUMPLANETS, "0");
            nodeStar.setAttribute(ATTR_NUMGASPLANETS, "0");

            for (StellarBody star2 : star.getSubStars()) {
                Element nodeSubStar = doc.createElement(ELEMENT_STAR);

                nodeSubStar.setAttribute(ATTR_BLACKHOLE, Boolean.toString(star2.isBlackHole()));
                nodeSubStar.setAttribute(ATTR_BLACKHOLE_DISK_ANGLE, Float.toString(star2.diskAngle));
                nodeSubStar.setAttribute(ATTR_TEMP, Integer.toString(star2.getTemperature()));
                nodeSubStar.setAttribute(ATTR_SIZE, Float.toString(star2.getSize()));
                nodeSubStar.setAttribute(ATTR_COMPANION_ORBIT, Integer.toString(star2.getOrbitalDistance()));
                nodeSubStar.setAttribute(ATTR_COMPANION_THETA,
                        Double.toString(Math.toDegrees(star2.getBaseTheta())));
                nodeStar.appendChild(nodeSubStar);
            }

            for (IDimensionProperties properties : star.getPlanets()) {
                if (!properties.isMoon())
                    nodeStar.appendChild(writePlanet(doc, (DimensionProperties) properties));
            }

            galaxyElement.appendChild(nodeStar);
        }

        // Emit the active procedural generator's config so a re-read (resetFromXml) round-trips it.
        IGalaxyGenerator activeGenerator = UniverseRegistry.getGenerator();
        if (activeGenerator instanceof ClusteredGalaxyGenerator) {
            galaxyElement.appendChild(writeGalaxyGen(doc, ((ClusteredGalaxyGenerator) activeGenerator).config()));
            // The planet-type table travels with the generator, and only with it: an authored-anchors-only
            // world has nothing that draws a type, so writing the presets there would put a section into
            // the file that nothing reads.
            for (PlanetTypePreset preset : PlanetTypes.presets()) {
                galaxyElement.appendChild(writePlanetType(doc, preset));
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer;
        try {
            transformer = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            return "";
        }

        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        DOMSource source = new DOMSource(doc);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        StreamResult result = new StreamResult(stream);

        try {
            transformer.transform(source, result);
        } catch (TransformerException e) {
            e.printStackTrace();
            return "";
        }

        return new String(stream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Node createTextNode(Document doc, String nodeName, double nodeText) {
        return createTextNode(doc, nodeName, Double.toString(nodeText));
    }

    private static Node createTextNode(Document doc, String nodeName, boolean nodeText) {
        return createTextNode(doc, nodeName, Boolean.toString(nodeText));
    }

    private static Node createTextNode(Document doc, String nodeName, int nodeText) {
        return createTextNode(doc, nodeName, Integer.toString(nodeText));
    }

    private static Node createTextNode(Document doc, String nodeName, String nodeText) {
        Element element = doc.createElement(nodeName);
        element.appendChild(doc.createTextNode(nodeText));

        return element;
    }

    private static Node writePlanet(Document doc, DimensionProperties properties) {
        Element nodePlanet = doc.createElement(ELEMENT_PLANET);
        nodePlanet.setAttribute(ATTR_NAME, properties.getName());
        nodePlanet.setAttribute(ATTR_DIMID, Integer.toString(properties.getId()));
        if (!properties.isNativeDimension)
            nodePlanet.setAttribute(ATTR_NATIVEDIM, "");
        if (!properties.customIcon.isEmpty())
            nodePlanet.setAttribute(ATTR_ICON, properties.customIcon);

        nodePlanet.appendChild(createTextNode(doc, ELEMENT_ISKNOWN, Boolean.toString(ARConfiguration.getCurrentConfig().initiallyKnownPlanets.contains(properties.getId()))));

        if (properties.hasRings) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_HASRINGS, "true"));
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_RING_ANGLE, properties.ringAngle));
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_RINGCOLOR, properties.ringColor[0] + "," + properties.ringColor[1] + "," + properties.ringColor[2]));
        }

        if (!properties.hasOxygen)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_HASOXYGEN, "false"));
        if (properties.colorOverride)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_COLOR_OVERRIDE, "true"));
        if (properties.skyRenderOverride)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_SKYOVERRIDE, "true"));


        if (properties.hasRivers)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_RIVER_OVERRIDE, "true"));

        if (properties.isGasGiant()) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_GASGIANT, "true"));

            if (!properties.getHarvestableGasses().isEmpty()) {
                for (Fluid f : properties.getHarvestableGasses()) {
                    nodePlanet.appendChild(createTextNode(doc, ELEMENT_GAS, f.getName()));
                }

            }
        }

        nodePlanet.appendChild(createTextNode(doc, ELEMENT_FOGCOLOR, properties.fogColor[0] + "," + properties.fogColor[1] + "," + properties.fogColor[2]));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_SKYCOLOR, properties.skyColor[0] + "," + properties.skyColor[1] + "," + properties.skyColor[2]));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_GRAVITY, (int) (properties.getGravitationalMultiplier() * 100f)));
        // Bulk properties are written only when the planet HAS them, so a catalogue that never stated a
        // mass round-trips to the same file it came from.
        if (properties.hasBulkProperties()) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_MASS, Double.toString(properties.getMass())));
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_RADIUS, Double.toString(properties.getRadius())));
        }
        if (properties.isTidallyLocked()) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_TIDALLY_LOCKED, "true"));
        }
        if (properties.getMetallicity() != 1d) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_METALLICITY,
                    Double.toString(properties.getMetallicity())));
        }
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_DISTANCE, properties.getOrbitalDist()));
        // Written as fractional degrees, not truncated to whole ones: these two angles are the only
        // authored inputs a body's durable CELL NAME is derived from, and one degree at a large
        // orbital radius is more than a cell across. A whole-degree round-trip could therefore rename
        // a body on a reload. Integer-valued files parse unchanged.
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_BASEORBITTHETA, Math.toDegrees(properties.baseOrbitTheta)));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_PHI, properties.orbitalPhi));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_RETROGRADE, properties.isRetrograde));
        nodePlanet.appendChild(createTextNode(doc, AVG_TEMPERATURE, properties.averageTemperature));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_PERIOD, properties.rotationalPeriod));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_ATMDENSITY, properties.getAtmosphereDensity()));
        // Custom weather properties
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_RAIN_START_LENGTH, properties.getRainStartLength()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_RAIN_PROLONGATION_LENGTH, properties.getRainProlongationLength()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_THUNDER_START_LENGTH, properties.getThunderStartLength()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_THUNDER_PROLONGATION_LENGTH, properties.getThunderProlongationLength()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_RAIN_MARKER, properties.getRainMarker()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_THUNDER_MARKER, properties.getThunderMarker()));
        nodePlanet.appendChild(createTextNode(doc, ELEMENT_ACIDIC_RAIN, properties.isAcidicRain()));

        nodePlanet.appendChild(createTextNode(doc, GENERATECRATERS, properties.canGenerateCraters()));
        nodePlanet.appendChild(createTextNode(doc, GENERATECAVES, properties.canGenerateCaves()));
        nodePlanet.appendChild(createTextNode(doc, GENERATEVOLCANOS, properties.canGenerateVolcanos()));
        nodePlanet.appendChild(createTextNode(doc, GENERATESTRUCTURES, properties.canGenerateStructures()));
        nodePlanet.appendChild(createTextNode(doc, GENERATEGEODES, properties.canGenerateGeodes()));


        if (properties.canGenerateCraters() && !(properties.getCraterMultiplier() == 1))
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_CRATER_MULTIPLIER, properties.getCraterMultiplier()));

        if (properties.canGenerateVolcanos() && !(properties.getVolcanoMultiplier() == 1))
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_VOLCANO_MULTIPLIER, properties.getVolcanoMultiplier()));

        if (properties.canGenerateGeodes() && !(properties.getGeodeMultiplier() == 1))
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_GEODE_MULTIPLIER, properties.getGeodeMultiplier()));

        nodePlanet.appendChild(createTextNode(doc, ELEMENT_SEALEVEL, properties.getSeaLevel()));

        // Emit only when overridden so a default planet's XML is unchanged (terrain-source pattern).
        if (properties.hasCustomOrbitHeight())
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_ORBIT_HEIGHT, properties.getOrbitHeight()));

//        nodePlanet.appendChild(createTextNode(doc, ELEMENT_TARGETSEALEVEL, properties.getTargetSeaLevel()));

        if (properties.getGenType() != 0)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_GENTYPE, properties.getGenType()));

        // Emit terrain-source elements only when non-default so a NATIVE planet's XML is unchanged.
        if (properties.getTerrainSource() != TerrainSource.NATIVE)
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_TERRAIN_SOURCE, properties.getTerrainSource().name()));
        if (!properties.getTerrainWorldType().isEmpty())
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_TERRAIN_WORLDTYPE, properties.getTerrainWorldType()));
        if (!properties.getTerrainTemplate().isEmpty())
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_TERRAIN_TEMPLATE, properties.getTerrainTemplate()));
        if (!properties.getTerrainGeneratorOptions().isEmpty())
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_TERRAIN_GENERATOR_OPTIONS, properties.getTerrainGeneratorOptions()));

        if (properties.oreProperties != null) {
            nodePlanet.appendChild(XMLOreLoader.writeOreEntryXML(doc, properties.oreProperties));
        }
        if (properties.laserDrillOresRaw != null) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_LASER_DRILL_ORES, properties.laserDrillOresRaw));
        }
        if (!properties.geodeOres.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            for (String ore : properties.geodeOres) {
                joiner.add(ore);
            }
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_GEODE_ORES, joiner.toString()));
        }
        if (!properties.craterOres.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            for (String ore : properties.craterOres) {
                joiner.add(ore);
            }
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_CRATER_ORES, joiner.toString()));
        }

        if (properties.isDecorationOverridden())
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_CAN_DECORATE, properties.hasDecorators()));

        if (properties.isNativeDimension && !properties.isGasGiant()) {
            StringBuilder biomeIds = new StringBuilder();
            for (BiomeEntry biome : properties.getBiomes()) {
                try {
                    biomeIds.append(",").append(Biome.REGISTRY.getNameForObject(biome.biome).toString()).append(";").append(biome.itemWeight);//Biome.getIdForBiome(biome.biome);
                } catch (NullPointerException e) {
                    AdvancedRocketry.logger.warn("Error saving biomes for world, biomes list saved may be incomplete.  World: " + properties.getId());
                }
            }
            if (biomeIds.length() > 0)
                biomeIds = new StringBuilder(biomeIds.substring(1));
            else
                AdvancedRocketry.logger.warn("Dim " + properties.getId() + " has no biomes to save!");
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_BIOMEIDS, biomeIds.toString()));
        }

        if (!properties.getCraterBiomeWeights().isEmpty() && !properties.isGasGiant()) {
            StringBuilder biomeIds = new StringBuilder();
            for (BiomeEntry biome : properties.getCraterBiomeWeights()) {
                try {
                    biomeIds.append(",").append(Biome.REGISTRY.getNameForObject(biome.biome).toString()).append(";").append(biome.itemWeight);//Biome.getIdForBiome(biome.biome);
                } catch (NullPointerException e) {
                    AdvancedRocketry.logger.warn("Error saving biomes for world, crater biomes list saved may be incomplete.  World: " + properties.getId());
                }
            }
            biomeIds = new StringBuilder(biomeIds.substring(1));
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_CRATER_BIOMEIDS, biomeIds.toString()));
        }

        for (ItemStack stack : properties.getRequiredArtifacts()) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_ARTIFACT, stack.getItem().getRegistryName() + " " + stack.getItemDamage() + " " + stack.getCount()));
        }

        for (Integer properties2 : properties.getChildPlanets()) {
            nodePlanet.appendChild(writePlanet(doc, DimensionManager.getInstance().getDimensionProperties(properties2)));
        }

        if (properties.getOceanBlock() != null) {
            nodePlanet.appendChild(createTextNode(doc, ELEMENT_OCEANBLOCK, Block.REGISTRY.getNameForObject(properties.getOceanBlock().getBlock()).toString()));
        }

        if (properties.getStoneBlock() != null) {
            int meta = properties.getStoneBlock().getBlock().getMetaFromState(properties.getStoneBlock());
            if (meta != 0)
                nodePlanet.appendChild(createTextNode(doc, ELEMENT_FILLERBLOCK, Block.REGISTRY.getNameForObject(properties.getStoneBlock().getBlock()) + ":" + meta));
            else
                nodePlanet.appendChild(createTextNode(doc, ELEMENT_FILLERBLOCK, Block.REGISTRY.getNameForObject(properties.getStoneBlock().getBlock()).toString()));
        }

        for (SpawnListEntryNBT e : properties.getSpawnListEntries()) {
            String nbtString = e.getNBTString();
            if (!nbtString.isEmpty())
                nbtString = " nbt=\"" + nbtString.replaceAll("\"", "&quot;") + "\"";
            Element spawnable = doc.createElement(ELEMENT_SPAWNABLE);
            spawnable.setAttribute(ATTR_WEIGHT, Integer.toString(e.itemWeight));
            spawnable.setAttribute(ATTR_GROUPMIN, Integer.toString(e.minGroupCount));
            spawnable.setAttribute(ATTR_GROUPMAX, Integer.toString(e.maxGroupCount));
            spawnable.setAttribute(ATTR_NBT, nbtString.replaceAll("\"", "&quot;"));

            spawnable.appendChild(doc.createTextNode(EntityRegistry.getEntry(e.entityClass).getRegistryName().toString()));

            nodePlanet.appendChild(spawnable);
        }

        return nodePlanet;
    }

    @Nonnull
    public static ItemStack getStack(String text) {
        String[] splitStr = text.split(" ");
        int meta = 0;
        int size = 1;
        //format: "name meta size"
        if (splitStr.length > 1) {
            try {
                meta = Integer.parseInt(splitStr[1]);
            } catch (NumberFormatException ignored) {
            }

            if (splitStr.length > 2) {
                try {
                    size = Integer.parseInt(splitStr[2]);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        ItemStack stack = ItemStack.EMPTY;
        Block block = Block.getBlockFromName(splitStr[0]);
        if (block == null) {
            Item item = Item.getByNameOrId(splitStr[0]);
            if (item != null)
                stack = new ItemStack(item, size, meta);
        } else
            stack = new ItemStack(block, size, meta);

        return stack;
    }

    public boolean loadFile(File xmlFile) throws IOException {
        DocumentBuilder docBuilder;
        doc = null;
        try {
            docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            return false;
        }

        try {
            doc = docBuilder.parse(xmlFile);
        } catch (SAXException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean isValid() {
        return doc != null;
    }

    public int getMaxNumPlanets(StellarBody body) {
        if (!maxPlanetNumber.containsKey(body)) {
            AdvancedRocketry.logger.warn("Star ID " + body.getId() + " has no entry for numPlanets");
            return 0;
        }
        return maxPlanetNumber.get(body);
    }

    public int getMaxNumGasGiants(StellarBody body) {
        if (!maxGasPlanetNumber.containsKey(body)) {
            AdvancedRocketry.logger.warn("Star ID " + body.getId() + " has no entry for numGasGiants");
            return 0;
        }
        return maxGasPlanetNumber.get(body);
    }

    private List<DimensionProperties> readPlanetFromNode(Node planetNode, StellarBody star) {
        List<DimensionProperties> list = new ArrayList<>();
        Node planetPropertyNode = planetNode.getFirstChild();


        DimensionProperties properties = new DimensionProperties(DimensionManager.getInstance().getNextFreeDim(offset));
        list.add(properties);
        offset++;//Increment for dealing with child planets


        //Set name for dimension if exists
        if (planetNode.hasAttributes()) {
            Node nameNode = planetNode.getAttributes().getNamedItem("name");
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                properties.setName(nameNode.getNodeValue());
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_DIMID);
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    if (nameNode.getTextContent().isEmpty()) throw new NumberFormatException();
                    properties.setId(Integer.parseInt(nameNode.getTextContent()));
                    //We're not using the offset so decrement to prepare for next planet
                    offset--;
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid DIMID specified for planet " + properties.getName()); //TODO: more detailed error msg
                    list.remove(properties);
                    offset--;
                    return list;
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_NATIVEDIM);
            if (nameNode != null) {
                properties.isNativeDimension = false;
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_ICON);
            if (nameNode != null) {
                properties.customIcon = nameNode.getTextContent();
            }
        }

        while (planetPropertyNode != null) {
            if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_FOGCOLOR)) {
                String[] colors = planetPropertyNode.getTextContent().split(",");
                try {
                    if (colors.length >= 3) {
                        float[] rgb = new float[3];


                        for (int j = 0; j < 3; j++)
                            rgb[j] = Float.parseFloat(colors[j]);
                        properties.fogColor = rgb;

                    } else if (colors.length == 1) {
                        int cols = Integer.parseUnsignedInt(colors[0].substring(2), 16);
                        float[] rgb = new float[3];

                        rgb[0] = ((cols >>> 16) & 0xff) / 255f;
                        rgb[1] = ((cols >>> 8) & 0xff) / 255f;
                        rgb[2] = (cols & 0xff) / 255f;

                        properties.fogColor = rgb;
                    } else
                        AdvancedRocketry.logger.warn("Invalid number of floats specified for fog color (Required 3, comma sperated)"); //TODO: more detailed error msg
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid fog color specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GAS)) {
                Fluid fluid = FluidRegistry.getFluid(planetPropertyNode.getTextContent());

                if (fluid == null)
                    AdvancedRocketry.logger.warn("\"" + planetPropertyNode.getTextContent() + "\" is not a valid fluid"); //TODO: more detailed error msg
                else {
                    properties.getHarvestableGasses().add(fluid);
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_OCEANBLOCK)) {
                String blockName = planetPropertyNode.getTextContent();
                Block block = Block.REGISTRY.getObject(new ResourceLocation(blockName));

                if (block == Blocks.AIR)
                    AdvancedRocketry.logger.warn("Invalid ocean block: " + blockName); //TODO: more detailed error msg

                properties.setOceanBlock(block.getDefaultState());
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_FILLERBLOCK)) {
                String blockName = planetPropertyNode.getTextContent();
                String[] splitBlockName = blockName.split(":");

                if (splitBlockName.length < 2) {
                    AdvancedRocketry.logger.warn("Invalid resource location for fillerBlock: " + blockName);
                } else {
                    Block block = Block.REGISTRY.getObject(new ResourceLocation(splitBlockName[0], splitBlockName[1]));
                    int metaValue = 0;

                    if (splitBlockName.length > 2) {
                        try {
                            metaValue = Integer.parseInt(splitBlockName[2]);
                        } catch (NumberFormatException e) {
                            AdvancedRocketry.logger.warn("Invalid meta value location for fillerBlock: " + blockName + " using " + splitBlockName[2]);
                        }
                    }

                    if (block == Blocks.AIR)
                        AdvancedRocketry.logger.warn("Invalid filler block: " + blockName); //TODO: more detailed error msg

                    properties.setStoneBlock(block.getStateFromMeta(metaValue));
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_SKYCOLOR)) {
                String[] colors = planetPropertyNode.getTextContent().split(",");
                try {

                    if (colors.length >= 3) {
                        float[] rgb = new float[3];

                        for (int j = 0; j < 3; j++)
                            rgb[j] = Float.parseFloat(colors[j]);
                        properties.skyColor = rgb;

                    } else if (colors.length == 1) {
                        int cols = Integer.parseUnsignedInt(colors[0].substring(2), 16);
                        float[] rgb = new float[3];

                        rgb[0] = ((cols >>> 16) & 0xff) / 255f;
                        rgb[1] = ((cols >>> 8) & 0xff) / 255f;
                        rgb[2] = (cols & 0xff) / 255f;

                        properties.skyColor = rgb;
                    } else
                        AdvancedRocketry.logger.warn("Invalid number of floats specified for sky color (Required 3, comma sperated)"); //TODO: more detailed error msg

                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid sky color specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_HASOXYGEN))
                properties.hasOxygen = Boolean.parseBoolean(planetPropertyNode.getTextContent());
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_COLOR_OVERRIDE))
                properties.colorOverride = Boolean.parseBoolean(planetPropertyNode.getTextContent());
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_SKYOVERRIDE))
                properties.skyRenderOverride = Boolean.parseBoolean(planetPropertyNode.getTextContent());
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RAIN_START_LENGTH))
                properties.setRainStartLength(Integer.parseInt(planetPropertyNode.getTextContent()));
            // TODO Create default values for new fields
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RAIN_PROLONGATION_LENGTH))
                properties.setRainProlongationLength(Integer.parseInt(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_THUNDER_START_LENGTH))
                properties.setThunderStartLength(Integer.parseInt(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_THUNDER_PROLONGATION_LENGTH))
                properties.setThunderProlongationLength(Integer.parseInt(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RAIN_MARKER))
                properties.setRainMarker(Integer.parseInt(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_THUNDER_MARKER))
                properties.setThunderMarker(Integer.parseInt(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_ACIDIC_RAIN))
                properties.setAcidicRain(Boolean.parseBoolean(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_ATMDENSITY)) {

                try {
                    properties.setAtmosphereDensityDirect(Math.min(Math.max(Integer.parseInt(planetPropertyNode.getTextContent()), DimensionProperties.MIN_ATM_PRESSURE), DimensionProperties.MAX_ATM_PRESSURE));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid atmosphereDensity specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GRAVITY)) {

                try {
                    properties.gravitationalMultiplier = Math.min(Math.max(Integer.parseInt(planetPropertyNode.getTextContent()), DimensionProperties.MIN_GRAVITY), DimensionProperties.MAX_GRAVITY) / 100f;
                    // Stating a gravity makes it an OVERRIDE: a planet that also declares a mass and a
                    // radius keeps the gravity its author wrote, so adding bulk properties to an
                    // existing planet cannot change how it plays.
                    properties.setGravityAuthored(true);
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid gravitationalMultiplier specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_MASS)) {
                try {
                    properties.setBulk(Double.parseDouble(planetPropertyNode.getTextContent()),
                            properties.getRadius());
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid mass specified for dimension " + properties.getId());
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RADIUS)) {
                try {
                    properties.setBulk(properties.getMass(),
                            Double.parseDouble(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid radius specified for dimension " + properties.getId());
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TIDALLY_LOCKED)) {
                properties.setTidallyLocked(Boolean.parseBoolean(planetPropertyNode.getTextContent()));
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_METALLICITY)) {
                try {
                    properties.setMetallicity(Double.parseDouble(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid metallicity specified for dimension "
                            + properties.getId());
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_DISTANCE)) {

                try {
                    properties.orbitalDist = Math.min(Math.max(Integer.parseInt(planetPropertyNode.getTextContent()), DimensionProperties.MIN_DISTANCE), DimensionProperties.MAX_DISTANCE);
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid orbitalDist specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_BASEORBITTHETA)) {

                try {
                    // Fractional degrees accepted, so the angle survives a save/load round-trip; a
                    // whole-degree file from an older world parses to exactly the same value.
                    properties.baseOrbitTheta = Math.toRadians(
                            Double.parseDouble(planetPropertyNode.getTextContent()) % 360d);
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid orbitalTheta specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RETROGRADE)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && text.equalsIgnoreCase("true"))
                    properties.isRetrograde = true;
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_PERIOD)) {
                try {
                    int rotationalPeriod = Integer.parseInt(planetPropertyNode.getTextContent());
                    if (rotationalPeriod > 0)
                        properties.rotationalPeriod = rotationalPeriod;
                    else
                        AdvancedRocketry.logger.warn("rotational Period must be greater than 0 for dimension " + properties.getId()); //TODO: more detailed error msg
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid rotational period specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_SEALEVEL)) {
                try {
                    properties.setSeaLevel(Integer.parseInt(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid sealeve specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_ORBIT_HEIGHT)) {
                try {
                    properties.setOrbitHeight(Integer.parseInt(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid orbitHeight specified for dimension "
                            + properties.getId() + "; keeping the global default");
                }
            }
            /*
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TARGETSEALEVEL)) {
                try {
                    properties.setTargetSeaLevel(Integer.parseInt(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid targetsealeve specified"); //TODO: more detailed error msg
                }
            }
             */
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RIVER_OVERRIDE))
                properties.hasRivers = Boolean.parseBoolean(planetPropertyNode.getTextContent());
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_BIOMEIDS)) {
                applyBiomeList(properties, planetPropertyNode.getTextContent());
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_CRATER_BIOMEIDS)) {

                String[] biomeList = planetPropertyNode.getTextContent().split(",");
                for (String s : biomeList) {

                    int biomeFrequency = 100;
                    String[] frequencySplit = s.split(";");

                    //Try to get a weight out of the semicolon separator
                    if (frequencySplit.length > 1) {
                        try {
                            biomeFrequency = Integer.parseInt(frequencySplit[1]);
                        } catch (NumberFormatException e) {
                            biomeFrequency = 100;
                            AdvancedRocketry.logger.warn(frequencySplit[1] + " is not a valid crater frequency");
                        }
                    } else {
                        AdvancedRocketry.logger.warn("Crater frequency term must exist for all biomes, setting frequency to default 100");
                    }

                    //Check whether we have numeric IDs (bad!) or RL ids
                    ResourceLocation location = new ResourceLocation(frequencySplit[0]);
                    if (Biome.REGISTRY.containsKey(location)) {
                        Biome biome = Biome.REGISTRY.getObject(location);
                        if (biome == null)
                            AdvancedRocketry.logger.warn("Error adding " + frequencySplit[0] + ", biome is null");
                        else
                            properties.addCraterBiomeWeight(biome, biomeFrequency);
                    } else {
                        AdvancedRocketry.logger.warn("Error adding " + frequencySplit[0] + ", it is not a biome resource location");
                    }
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_SPAWNABLE)) {
                int weight = 100;
                int groupMin = 1, groupMax = 1;
                String nbtString = "";
                Node weightNode = planetPropertyNode.getAttributes().getNamedItem(ATTR_WEIGHT);
                Node groupMinNode = planetPropertyNode.getAttributes().getNamedItem(ATTR_GROUPMIN);
                Node groupMaxNode = planetPropertyNode.getAttributes().getNamedItem(ATTR_GROUPMAX);
                Node nbtNode = planetPropertyNode.getAttributes().getNamedItem(ATTR_NBT);

                //Get spawn properties
                if (weightNode != null) {
                    try {
                        weight = Integer.parseInt(weightNode.getTextContent());
                        weight = Math.max(1, weight);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (groupMinNode != null) {
                    try {
                        groupMin = Integer.parseInt(groupMinNode.getTextContent());
                        groupMin = Math.max(1, groupMin);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (groupMaxNode != null) {
                    try {
                        groupMax = Integer.parseInt(groupMaxNode.getTextContent());
                        groupMax = Math.max(1, groupMax);
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (nbtNode != null) {
                    nbtString = nbtNode.getTextContent();
                }

                if (groupMax < groupMin) {
                    groupMax = groupMin;
                }

                Class clazz = EntityList.getClass(new ResourceLocation(planetPropertyNode.getTextContent()));

                //If not using string name maybe it's a class name?
                if (clazz == null) {
                    try {
                        clazz = Class.forName(planetPropertyNode.getTextContent());
                        if (!Entity.class.isAssignableFrom(clazz))
                            clazz = null;

                    } catch (Exception e) {
                        //Fail silently
                    }
                }

                if (clazz != null) {
                    SpawnListEntryNBT entry = new SpawnListEntryNBT(clazz, weight, groupMin, groupMax);
                    if (!nbtString.isEmpty())
                        try {
                            entry.setNbt(nbtString);
                        } catch (DOMException e) {
                            AdvancedRocketry.logger.fatal("===== Configuration Error!  Please check your save's planetDefs.xml config file =====\n"
                                    + e.getLocalizedMessage()
                                    + "\nThe following is not valid JSON:\n" + nbtString);
                        } catch (NBTException e) {
                            AdvancedRocketry.logger.fatal("===== Configuration Error!  Please check your save's planetDefs.xml config file =====\n"
                                    + e.getLocalizedMessage()
                                    + "\nThe following is not valid NBT data:\n" + nbtString);
                        }

                    properties.getSpawnListEntries().add(entry);
                } else
                    AdvancedRocketry.logger.warn("Cannot find " + planetPropertyNode.getTextContent() + " while registering entity for planet spawn");


            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_ARTIFACT)) {
                ItemStack stack = XMLPlanetLoader.getStack(planetPropertyNode.getTextContent());

                if (!stack.isEmpty())
                    properties.getRequiredArtifacts().add(stack);
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_PLANET)) {
                List<DimensionProperties> childList = readPlanetFromNode(planetPropertyNode, star);
                if (childList.size() > 0) {
                    DimensionProperties child = childList.get(0); // First entry in the list is the child planet
                    properties.addChildPlanet(child);
                    list.addAll(childList);
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_PHI)) {
                try {
                    properties.orbitalPhi = Double.parseDouble(planetPropertyNode.getTextContent()) % 360d;
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid orbitalPhi specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_OREGEN)) {
                properties.oreProperties = XMLOreLoader.loadOre(planetPropertyNode);
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_LASER_DRILL_ORES) && !properties.isGasGiant()) {

                properties.laserDrillOresRaw = planetPropertyNode.getTextContent();

                String[] entries = properties.laserDrillOresRaw.split(",");
                for (String entry : entries) {

                    String[] parts = entry.split(";");
                    String oreName = parts[0].trim();

                    if (OreDictionary.doesOreNameExist(oreName)) {
                        // doesOreNameExist returns true for any *reserved* ore name even
                        // when no items are registered under it (e.g. the providing mod
                        // isn't installed), so getOres can hand back an empty list.
                        List<ItemStack> ores = OreDictionary.getOres(oreName);
                        if (ores.isEmpty()) {
                            AdvancedRocketry.logger.warn(oreName + " is a known ore dictionary name but has no "
                                    + "registered items (providing mod not installed?); skipping laser drill ore entry");
                        } else {
                            ItemStack item = ores.get(0).copy();
                            if (parts.length > 1) {
                                try {
                                    item.setCount(Integer.parseInt(parts[1].trim()));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            properties.laserDrillOres.add(item);
                        }
                    } else if (Item.getByNameOrId(oreName) != null) {
                        int quantity = 1;
                        int damage = 0;
                        if (parts.length > 1) {
                            try {
                                quantity = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException ignored) {
                            }
                            if (parts.length > 2) {
                                try {
                                    damage = Integer.parseInt(parts[2]);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        properties.laserDrillOres.add(new ItemStack(Objects.requireNonNull(Item.getByNameOrId(oreName)), quantity, damage));
                    } else {
                        AdvancedRocketry.logger.warn(oreName + " is not a valid OreDictionary name or item ID");
                    }
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GEODE_ORES)) {
                String[] entries = planetPropertyNode.getTextContent().split(",");
                properties.geodeOres.addAll(Arrays.stream(entries)
                        .filter(e -> OreDictionary.doesOreNameExist(e.trim()))
                        .collect(Collectors.toSet())
                );
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_CRATER_ORES)) {
                String[] entries = planetPropertyNode.getTextContent().split(",");
                properties.craterOres.addAll(Arrays.stream(entries)
                        .filter(e -> OreDictionary.doesOreNameExist(e.trim()))
                        .collect(Collectors.toSet())
                );
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GENTYPE)) {
                try {
                    properties.setGenType(Integer.parseInt(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid generator type specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TERRAIN_SOURCE)) {
                properties.setTerrainSource(TerrainSource.byName(planetPropertyNode.getTextContent().trim()));
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TERRAIN_WORLDTYPE)) {
                properties.setTerrainWorldType(planetPropertyNode.getTextContent().trim());
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TERRAIN_TEMPLATE)) {
                properties.setTerrainTemplate(planetPropertyNode.getTextContent().trim());
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_TERRAIN_GENERATOR_OPTIONS)) {
                // NOT trimmed: a generator settings string is opaque to us and may be whitespace-significant.
                properties.setTerrainGeneratorOptions(planetPropertyNode.getTextContent());
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_HASRINGS))
                properties.hasRings = Boolean.parseBoolean(planetPropertyNode.getTextContent());
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_CAN_DECORATE))
                properties.setDecoratoration(Boolean.parseBoolean(planetPropertyNode.getTextContent()));
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RING_ANGLE)) {
                properties.ringAngle = Integer.parseInt(planetPropertyNode.getTextContent());
            }
            else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_RINGCOLOR)) {
                String[] colors = planetPropertyNode.getTextContent().split(",");
                try {

                    if (colors.length >= 3) {
                        float[] rgb = new float[3];

                        for (int j = 0; j < 3; j++)
                            rgb[j] = Float.parseFloat(colors[j]);
                        properties.ringColor = rgb;

                    } else if (colors.length == 1) {
                        int cols = Integer.parseUnsignedInt(colors[0].substring(2), 16);
                        float[] rgb = new float[3];

                        rgb[0] = ((cols >>> 16) & 0xff) / 255f;
                        rgb[1] = ((cols >>> 8) & 0xff) / 255f;
                        rgb[2] = (cols & 0xff) / 255f;

                        properties.ringColor = rgb;
                    } else
                        AdvancedRocketry.logger.warn("Invalid number of floats specified for ring color (Required 3, comma sperated)"); //TODO: more detailed error msg

                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid sky color specified"); //TODO: more detailed error msg
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GASGIANT)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && text.equalsIgnoreCase("true"))
                    properties.setGasGiant(true);
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_ISKNOWN)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && text.equalsIgnoreCase("true")) {
                    ARConfiguration.getCurrentConfig().initiallyKnownPlanets.add(properties.getId());
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(GENERATECRATERS)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && !text.isEmpty()) {
                    properties.setGenerateCraters(text.equalsIgnoreCase("true"));
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_CRATER_MULTIPLIER)) {
                try {
                    properties.setCraterMultiplier(Float.parseFloat(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid crater multiplier specified, must be a number");
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_VOLCANO_MULTIPLIER)) {
                try {
                    properties.setVolcanoMultiplier(Float.parseFloat(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid volcano multiplier specified, must be a number");
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(ELEMENT_GEODE_MULTIPLIER)) {
                try {
                    properties.setGeodeMultiplier(Float.parseFloat(planetPropertyNode.getTextContent()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Invalid geode multiplier specified, must be a number");
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(GENERATEGEODES)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && !text.isEmpty()) {
                    properties.setGenerateGeodes(text.equalsIgnoreCase("true"));
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(GENERATEVOLCANOS)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && !text.isEmpty()) {
                    properties.setGenerateVolcanos(text.equalsIgnoreCase("true"));
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(GENERATESTRUCTURES)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && !text.isEmpty()) {
                    properties.setGenerateStructures(text.equalsIgnoreCase("true"));
                }
            } else if (planetPropertyNode.getNodeName().equalsIgnoreCase(GENERATECAVES)) {
                String text = planetPropertyNode.getTextContent();
                if (text != null && !text.isEmpty()) {
                    properties.setGenerateCaves(text.equalsIgnoreCase("true"));
                }
            }


            planetPropertyNode = planetPropertyNode.getNextSibling();
        }

        //Star may not be registered at this time, use ID version instead
        properties.setStar(star.getId());

        //Set temperature
        properties.averageTemperature = AstronomicalBodyHelper.getAverageTemperature(star, properties.getSolarOrbitalDistance(), properties.getAtmosphereDensity());

        //If no biomes are specified add some!
        if (properties.getBiomes().isEmpty())
            properties.addBiomes(properties.getViableBiomes(true));

        return list;
    }

    public StellarBody readStar(Node planetNode) {
        StellarBody star = readSubStar(planetNode);
        if (planetNode.hasAttributes()) {
            Node nameNode;

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_X);

            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.setPosX(Integer.parseInt(nameNode.getNodeValue()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_Y);

            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.setPosZ(Integer.parseInt(nameNode.getNodeValue()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_NUMPLANETS);

            try {
                maxPlanetNumber.put(star, Integer.parseInt(nameNode.getNodeValue()));
            } catch (Exception e) {
                AdvancedRocketry.logger.warn("Invalid number of planets specified in xml config!");
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_NUMGASPLANETS);
            try {
                maxGasPlanetNumber.put(star, Integer.parseInt(nameNode.getNodeValue()));
            } catch (Exception e) {
                AdvancedRocketry.logger.warn("Invalid number of planets specified in xml config!");
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_BLACKHOLE);
            if (nameNode != null && nameNode.getNodeValue().equalsIgnoreCase("true")) {
                star.setBlackHole(true);
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_BLACKHOLE_DISK_ANGLE);

            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.diskAngle = Float.parseFloat(nameNode.getNodeValue());
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

        }

        star.setId(starId++);
        return star;
    }

    public StellarBody readSubStar(Node planetNode) {
        StellarBody star = new StellarBody();
        if (planetNode.hasAttributes()) {
            Node nameNode = planetNode.getAttributes().getNamedItem("name");
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                star.setName(nameNode.getNodeValue());
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_TEMP);

            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.setTemperature(Integer.parseInt(nameNode.getNodeValue()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                    AdvancedRocketry.logger.warn("using temp value of 100 now");
                    star.setTemperature(100);
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_SIZE);
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.setSize(Float.parseFloat(nameNode.getNodeValue()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_BLACKHOLE);
            if (nameNode != null && nameNode.getNodeValue().equalsIgnoreCase("true")) {
                star.setBlackHole(true);
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_BLACKHOLE_DISK_ANGLE);

            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.diskAngle = Float.parseFloat(nameNode.getNodeValue());
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

            // A companion's orbit about its primary, in the same distance units a planet's is in.
            // It used to be an angle called "separation", which could say how far off the primary a
            // companion LOOKED from one particular world and nothing else — not where it was, not
            // what it lit, and not that it moved.
            nameNode = planetNode.getAttributes().getNamedItem(ATTR_COMPANION_ORBIT);
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    star.setOrbitalDistance(Integer.parseInt(nameNode.getNodeValue()));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }

            nameNode = planetNode.getAttributes().getNamedItem(ATTR_COMPANION_THETA);
            if (nameNode != null && !nameNode.getNodeValue().isEmpty()) {
                try {
                    // DEGREES, exactly as a planet's <orbitalTheta> is. One name, one unit: an
                    // angle that meant radians here and degrees one element away would be a trap
                    // no author could see, because both parse and neither complains.
                    star.setBaseTheta(Math.toRadians(
                            Double.parseDouble(nameNode.getNodeValue()) % 360d));
                } catch (NumberFormatException e) {
                    AdvancedRocketry.logger.warn("Error Reading star " + star.getName());
                }
            }
        }

        return star;
    }

    public DimensionPropertyCoupling readAllPlanets() {
        DimensionPropertyCoupling coupling = new DimensionPropertyCoupling();

        NodeList galaxyNodes = doc.getElementsByTagName("galaxy");
        if (galaxyNodes.getLength() == 0) {
            throw new RuntimeException("planetDefs XML has no <galaxy> root element");
        }
        Node masterNode = galaxyNodes.item(0).getFirstChild();

        //readPlanetFromNode changes value
        //Yes it's hacky but that's another reason why it's private

        offset = DimensionManager.dimOffset;
        while (masterNode != null) {
            if (masterNode.getNodeName().equalsIgnoreCase(ELEMENT_GALAXYGEN)) {
                coupling.galaxyGenConfig = readGalaxyGen(masterNode);
                masterNode = masterNode.getNextSibling();
                continue;
            }
            if (masterNode.getNodeName().equalsIgnoreCase(ELEMENT_PLANETTYPE)) {
                coupling.planetTypes.add(readPlanetType(masterNode));
                masterNode = masterNode.getNextSibling();
                continue;
            }
            if (!masterNode.getNodeName().equals("star")) {
                masterNode = masterNode.getNextSibling();
                continue;
            }

            StellarBody star = readStar(masterNode);
            coupling.stars.add(star);

            // Explicit galactic address for this authored anchor (optional). Staged into the universe
            // registry after the catalogue is built; absent -> a deterministic fallback cell downstream.
            if (masterNode.hasAttributes()) {
                Node coordNode = masterNode.getAttributes().getNamedItem(ATTR_GALACTIC_COORD);
                if (coordNode != null && !coordNode.getNodeValue().isEmpty()) {
                    coupling.anchorCoords.put(star.getId(), UniverseRegistry.parseAnchor(coordNode.getNodeValue()));
                }
            }

            NodeList planetNodeList = masterNode.getChildNodes();

            Node planetNode = planetNodeList.item(0);

            while (planetNode != null) {
                if (planetNode.getNodeName().equalsIgnoreCase(ELEMENT_PLANET)) {
                    // Isolate each planet: a malformed definition (e.g. an ore name
                    // from a mod that isn't installed) is logged and skipped rather
                    // than aborting the whole config load. See issue #77.
                    try {
                        coupling.dims.addAll(readPlanetFromNode(planetNode, star));
                    } catch (RuntimeException e) {
                        AdvancedRocketry.logger.warn("Skipping malformed planet definition under star '"
                                + star.getName() + "' — check your planetDefs.xml: " + e, e);
                    }
                }
                if (planetNode.getNodeName().equalsIgnoreCase("star")) {
                    StellarBody star2 = readSubStar(planetNode);
                    star.addSubStar(star2);
                }
                planetNode = planetNode.getNextSibling();
            }

            masterNode = masterNode.getNextSibling();
        }
        return coupling;
    }

    /**
     * Loads {@code file} and parses every planet, throwing a {@link RuntimeException}
     * on a fatal/structural failure (unparseable XML, missing {@code <galaxy>} root)
     * instead of terminating the JVM. At the call site (server start) Forge turns the
     * thrown exception into a normal crash report, which is far more diagnosable than
     * the old silent {@link net.minecraftforge.fml.common.FMLCommonHandler#exitJava}.
     * Recoverable per-planet config mistakes are skipped-and-warned inside
     * {@link #readAllPlanets()} and never reach here.
     */
    public DimensionPropertyCoupling loadPlanetsOrThrow(File file) {
        try {
            if (!loadFile(file)) {
                throw new RuntimeException("planetDefs XML at " + file.getAbsolutePath()
                        + " could not be parsed as valid XML");
            }
        } catch (IOException e) {
            throw new RuntimeException("planetDefs XML at " + file.getAbsolutePath()
                    + " could not be read", e);
        }
        return readAllPlanets();
    }

    public static class DimensionPropertyCoupling {

        public List<StellarBody> stars = new LinkedList<>();
        public List<DimensionProperties> dims = new LinkedList<>();
        // Authored galactic addresses, keyed by star id (parse order). Only anchors that declared an
        // explicit <star galacticCoord> appear here; the rest get a deterministic fallback at population.
        public Map<Integer, GalacticCoord> anchorCoords = new HashMap<>();
        // Procedural-galaxy generation config from an optional <galaxyGen> element; null = authored-only.
        public GalaxyGenConfig galaxyGenConfig = null;
        // Authored <planetType> presets. Empty -> the stock table stands.
        public List<PlanetTypePreset> planetTypes = new ArrayList<>();

    }
}
