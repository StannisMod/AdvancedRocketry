package zmaster587.advancedRocketry.universe;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.block.Block;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;
import zmaster587.advancedRocketry.util.XMLPlanetLoader;

/**
 * The seam where a scanned dot becomes a world: turning a procedural {@link SystemBody} into a real
 * dimension a ship can put down on.
 *
 * <p>Without this the procedural galaxy is look-but-do-not-touch. Every body the generator places
 * carries {@link Constants#INVALID_PLANET}, and {@code isDescendTarget()} is false for all of them, so a
 * system full of planets has nowhere to land.</p>
 *
 * <h3>The four rules this class exists to keep</h3>
 * <ol>
 *   <li><b>A DESCENT realizes, and nothing else does.</b> Scanning is cheap, remote and repeatable, and
 *       the tier schema answers a scan from the derivation on purpose — so minting on a scan would let
 *       one telescope sweep allocate dimensions by the dozen. Moons obey the same rule on their own
 *       account rather than being realized eagerly with a parent.</li>
 *   <li><b>Realization MATERIALIZES what was already derived; it never rolls fresh values.</b> Mass,
 *       atmosphere, temperature and water are promised to a telescope from across the system, so a
 *       landing that disagreed with the scan would make the whole tier schema a lie. This is why
 *       {@code generateRandom} cannot be reused here: it walks a shared {@code Random}, allocates an id
 *       immediately, and seeds a biome roll from {@code System.nanoTime()} — none of which can answer
 *       the same question twice.</li>
 *   <li><b>After realization the SAVE is authoritative.</b> The body is pinned, the dimension is
 *       registered and its properties are written down; a later seed, config, XML or modset change must
 *       not move or reshape a planet somebody has stood on.</li>
 *   <li><b>A realized planet is never un-realized.</b> There is no eviction path here on purpose. A long
 *       game accumulates dimensions in proportion to the planets a player has actually LANDED on, which
 *       is bounded by play rather than by the size of the galaxy — and rule 1 is what keeps that bound
 *       tight.</li>
 * </ol>
 *
 * <p>Server main thread only.</p>
 */
public final class PlanetRealizer {

    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    private PlanetRealizer() {
    }

    /**
     * Realize the descend-target body standing in {@code bodyCell}, returning its dimension id — or
     * {@link Constants#INVALID_PLANET} when that cell holds nothing anyone could land on.
     *
     * <p><b>Idempotent.</b> A cell whose body already has a world answers with that world; a second
     * descent into the same cell therefore reuses the dimension instead of minting another. This is the
     * only entry point, so that "one body, one world" cannot be true in one caller and false in
     * another.</p>
     */
    public static int realize(MinecraftServer server, GalacticCoord bodyCell) {
        if (server == null || bodyCell == null) {
            return Constants.INVALID_PLANET;
        }
        UniverseRegistry registry = UniverseRegistry.get(server);
        if (registry == null) {
            return Constants.INVALID_PLANET;
        }

        // Pin FIRST. A touch is what freezes a procedural system into the save, and by the time this
        // body has a dimension its surroundings must already be unable to drift away from under it.
        registry.pinSystem(bodyCell);

        OptionalInt existing = registry.realizedDimAt(bodyCell);
        if (existing.isPresent()) {
            return existing.getAsInt();
        }

        Optional<GalacticCoord> anchorOpt = registry.anchorForCell(bodyCell);
        if (!anchorOpt.isPresent()) {
            return Constants.INVALID_PLANET;
        }
        GalacticCoord anchor = anchorOpt.get();

        List<SystemBody> here = registry.bodiesAt(bodyCell);
        SystemBody target = null;
        SystemBody parentBody = null;
        int variant = 0;
        int seen = 0;
        for (SystemBody body : here) {
            if (body.kind() == SystemBodyKind.STAR || body.kind() == SystemBodyKind.STATION_SLOT
                    || body.kind() == SystemBodyKind.ASTEROID_BELT) {
                continue;
            }
            // A moon shares its parent's cell, and the scan below can only reach one once the parent
            // already HAS a dimension (an unrealized parent would be picked as the target first), so
            // the parent found here is always realizable into a link.
            if (parentBody == null && body.kind() != SystemBodyKind.MOON) {
                parentBody = body;
            }
            // The variant is a body's rank among the worlds SHARING this cell, and it must be counted
            // exactly the way the generator assigned it — a planet is 0 and its moons follow — or a
            // realized moon would materialize a different world than the one that was scanned.
            if (target == null && body.kind().canDescend()
                    && body.dimId() == Constants.INVALID_PLANET) {
                target = body;
                variant = seen;
            }
            seen++;
        }
        if (target == null) {
            return Constants.INVALID_PLANET;
        }

        Optional<StellarBody> starOpt = registry.starAt(bodyCell);
        if (!starOpt.isPresent()) {
            LOGGER.warn("[UNIVERSE] cannot realize the body at {}: its system has no star", bodyCell.cellKey());
            return Constants.INVALID_PLANET;
        }
        StellarBody star = starOpt.get();

        // A procedural star keeps its SYNTHETIC NEGATIVE id — the pin already made that id a durable key
        // in the save — but the catalogue has to learn about it, because a planet resolves its sun,
        // its sky colour and its orbital period through the star list.
        if (DimensionManager.getInstance().getStar(star.getId()) == null) {
            DimensionManager.getInstance().addStar(star);
        } else {
            star = DimensionManager.getInstance().getStar(star.getId());
        }

        int dimId = DimensionManager.getInstance().getNextFreeDim(DimensionManager.dimOffset);
        if (dimId == Constants.INVALID_PLANET) {
            LOGGER.error("[UNIVERSE] no free dimension id left to realize the body at {}", bodyCell.cellKey());
            return Constants.INVALID_PLANET;
        }

        BodyProfile profile = PlanetDerivation.derive(registry.worldSeed(), anchor, target.name(), variant,
                star, target.kind() == SystemBodyKind.MOON, target.orbitalDistance());
        DimensionProperties props = materialize(dimId, profile, star, target, parentBody);

        if (!DimensionManager.getInstance().registerDim(props, true)) {
            LOGGER.error("[UNIVERSE] dimension {} was already registered while realizing {}", dimId,
                    bodyCell.cellKey());
            return Constants.INVALID_PLANET;
        }
        star.addPlanet(props);
        if (!registry.realizeBody(bodyCell, dimId)) {
            LOGGER.error("[UNIVERSE] realized dimension {} for {} but the body could not be rewritten - "
                    + "the world exists and nothing points at it", dimId, bodyCell.cellKey());
            return Constants.INVALID_PLANET;
        }
        LOGGER.info("[UNIVERSE] realized {} '{}' as dim {} at cell {} (type {}, {} K, {} atm-units, {}% g)",
                profile.kind(), props.getName(), dimId, bodyCell.cellKey(), profile.typeName(),
                profile.temperatureKelvin(), profile.pressure(), profile.gravityPercent());
        return dimId;
    }

    /**
     * Write a derived profile into a real {@link DimensionProperties}. Everything physical comes from
     * the profile; everything cosmetic is derived from those same numbers, so nothing here consults a
     * {@code Random}.
     */
    private static DimensionProperties materialize(int dimId, BodyProfile profile, StellarBody star,
                                                   SystemBody body, SystemBody parentBody) {
        DimensionProperties props = new DimensionProperties(dimId);
        props.setName(star.getName() + " " + dimId);
        props.setStar(star);

        props.orbitalDist = Math.max(DimensionProperties.MIN_DISTANCE, profile.orbitalDistance());
        // A MOON must be realized as a moon. Without this it became a planet standing at its parent's
        // exact orbit forever, and every moon-specific path — the parent-mass period law, the moon sky,
        // the moon branch of orbitThetaAt — was dead for it, because isMoon() answered false.
        // Its own distance from the parent lives in its ephemeris; profile.orbitalDistance() is the
        // PARENT's distance from the star, which is what its climate is derived from and must stay.
        if (body != null && body.kind() == SystemBodyKind.MOON && parentBody != null
                && parentBody.dimId() != Constants.INVALID_PLANET) {
            DimensionProperties parentProps =
                    DimensionManager.getInstance().getDimensionProperties(parentBody.dimId());
            if (parentProps != null) {
                int localOrbit = (int) Math.round(body.offsetLaw().distUnits());
                props.orbitalDist = Math.max(DimensionProperties.MIN_DISTANCE, localOrbit);
                props.setParentPlanet(parentProps);
            } else {
                LOGGER.warn("[UNIVERSE] moon {} realized without a parent: dim {} has no properties",
                        body.name().cellKey(), parentBody.dimId());
            }
        }
        // The orbital angle is taken from the body's own law, so the planet the sky shows and the
        // planet the orbital elements describe are in the same place. A planet's angle lives in the
        // FRAME its cell rides; a moon's lives in its own offset law, because a moon shares its
        // parent's frame and going through that would hand it its parent's angle instead of its own.
        BodyEphemeris ownLaw = body.kind() == SystemBodyKind.MOON
                ? body.offsetLaw() : body.frame().law();
        props.baseOrbitTheta = ownLaw.baseTheta();
        props.orbitTheta = props.baseOrbitTheta;

        props.setAtmosphereDensityDirect(profile.pressure());
        props.averageTemperature = profile.temperatureKelvin();
        props.hasOxygen = profile.hasOxygen();
        props.setBulk(profile.massEarths(), profile.radiusEarths());
        props.setTidallyLocked(profile.tidallyLocked());
        props.setHasRings(profile.hasRings());
        props.setMetallicity(profile.metallicity());
        props.setGasGiant(profile.kind() == SystemBodyKind.GAS_GIANT);
        props.rotationalPeriod = rotationalPeriodOf(profile, star);

        applyTerrain(props, profile.terrain());

        PlanetTypePreset preset = profile.preset();
        if (preset != null) {
            // The type states what the surface is made of, so it states how much light it throws back.
            props.setAlbedo(preset.albedo());
            if (!preset.biomes().isEmpty()) {
                XMLPlanetLoader.applyBiomeList(props, preset.biomes());
            }
            if (preset.seaLevel() != PlanetTypePreset.SEA_LEVEL_UNSET) {
                props.setSeaLevel(preset.seaLevel());
            }
            if (!preset.oceanBlock().isEmpty()) {
                Block block = Block.REGISTRY.getObject(new ResourceLocation(preset.oceanBlock()));
                if (block != null) {
                    props.setOceanBlock(block.getDefaultState());
                }
            }
            if (preset.oreProperties() != null) {
                props.oreProperties = preset.oreProperties();
            }
        }
        // No palette from the type: let the world derive one from its own climate, which is what an
        // authored planet with no <biomeIds> does.
        if (props.getBiomes().isEmpty() && props.hasSurface()) {
            props.addBiomes(props.getViableBiomes(true));
        }
        props.initDefaultAttributes();
        return props;
    }

    private static void applyTerrain(DimensionProperties props, TerrainOption terrain) {
        if (terrain == null) {
            return;
        }
        // Fixed HERE and never re-derived: from this point the save owns how this world generates, so a
        // pack that later adds or removes a world generator cannot reshape ground somebody has walked on.
        props.setTerrainSource(terrain.source());
        props.setTerrainWorldType(terrain.worldType());
        props.setTerrainTemplate(terrain.template());
        props.setTerrainGeneratorOptions(terrain.options());
        props.setGenType(terrain.genType());
    }

    /**
     * How long this world's day is. A locked world's rotation IS its orbit — that is what locking means
     * — and every other world keeps the legacy gravity-derived period so procedural planets have the
     * same spread of day lengths the game has always had.
     */
    private static int rotationalPeriodOf(BodyProfile profile, StellarBody star) {
        if (profile.tidallyLocked()) {
            double days = AstronomicalBodyHelper.getOrbitalPeriod(profile.orbitalDistance(), star.getMass());
            double ticks = days * AstronomicalBodyHelper.TICKS_PER_DAY;
            if (!(ticks > 0d) || ticks > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) ticks;
        }
        // Spin is a property of the body, drawn where every other one is derived. It used to be
        // computed here from surface GRAVITY, which does not bear on rotation at all.
        return profile.rotationalPeriodTicks();
    }

    // angleOf — recovering a body's orbital angle from where its cell ended up — is gone: the angle is
    // now carried by the body's own law, which is what the cell was derived FROM. Recovering it was
    // only ever an approximation of the drawn value, accurate to whatever the cell grid could express.
}
