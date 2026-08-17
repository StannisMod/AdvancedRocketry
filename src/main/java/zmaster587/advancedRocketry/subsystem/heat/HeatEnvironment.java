package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.shield.ShieldCoverage;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.SpaceSlotPool;
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.SystemContent;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import java.util.List;

/**
 * What the outside is doing to a ship: the energy arriving at a radiating cell, per tick.
 * <p>
 * <b>One term, many contributors.</b> A star overhead, the warm world underneath and — later — another
 * ship's radiators or a weapon built to cook you all arrive as the same number through the same
 * channel. Nothing gets a mechanism of its own, which is what stops the environment growing a special
 * case per source and is why a heat weapon needs no new pathway to exist: it adds to this sum.
 * <p>
 * <b>This is the term that used to be a constant.</b> Rejection is written {@code T⁴ − T_amb⁴}, and
 * {@code k·A·T_amb⁴} is an incident flux spelled as a temperature. Feeding that subtraction a config
 * number meant a radiator in deep space was radiating against a room, which cost it roughly half its
 * strength; there is now nothing to feed, because the second half of the subtraction is what this
 * class computes.
 * <p>
 * <b>It couples through the radiators and nowhere else.</b> A radiating cell is the ship's deliberate
 * high-emissivity surface, so it absorbs as well as sheds; a hull does neither. A ship with no
 * radiators therefore does not heat in a star, and shutting the sinks is protection as well as
 * silence.
 */
public final class HeatEnvironment {

    /**
     * The most a shield may take off the incident flux, ever. Capped here rather than in the config's
     * own range because a value clamped where it is READ cannot be got past by editing a file, by a
     * pack that ships its own config, or by a future key that forgets to declare a bound: total
     * thermal immunity is not a setting, and a ship parked in a star must always be heating.
     */
    private static final double MAX_SHIELD_ATTENUATION = 0.995D;

    /** Nothing at all is arriving — interstellar void, and the honest answer for a world we cannot place. */
    private static final HeatEnvironment NOTHING = new HeatEnvironment(0.0D, null, null);

    private final double unshieldedFluxPerCell;
    private final World world;
    private final List<TileEntityFieldGenerator> shieldGenerators;

    private HeatEnvironment(double unshieldedFluxPerCell, World world,
                            List<TileEntityFieldGenerator> shieldGenerators) {
        this.unshieldedFluxPerCell = unshieldedFluxPerCell;
        this.world = world;
        this.shieldGenerators = shieldGenerators;
    }

    /**
     * The environment around {@code sample}, resolved once for a whole loop's tick.
     * <p>
     * The SOURCES are a property of where the ship is and do not vary across it — a star is millions of
     * blocks away and a ship is tens. The SHIELD does vary, because a cell can be outside a field its
     * neighbour is inside, so that half is asked per cell in {@link #incidentFluxPerCell(BlockPos)}.
     */
    public static HeatEnvironment at(World world, BlockPos sample) {
        if (world == null || world.isRemote || sample == null || !HeatNetwork.enabled()) {
            return NOTHING;
        }
        double flux = bodyEnvironmentFlux(world) + cellEnvironmentFlux(world, sample);
        if (flux <= 0.0D) {
            // Still carry the world: a shield attenuating nothing is nothing, but the caller reads the
            // same object either way and must not have to care which case it got.
            return new HeatEnvironment(0.0D, world, null);
        }
        return new HeatEnvironment(flux, world, ShieldCoverage.activeGenerators(world));
    }

    /** What arrives at one radiating cell at {@code pos} this tick, after whatever the shield stopped. */
    public double incidentFluxPerCell(BlockPos pos) {
        if (unshieldedFluxPerCell <= 0.0D) {
            return 0.0D;
        }
        if (ShieldCoverage.isCovered(shieldGenerators, world, pos)) {
            return unshieldedFluxPerCell * (1.0D - shieldAttenuation());
        }
        return unshieldedFluxPerCell;
    }

    /** What would arrive with no shield in the way — the readout, and the sum the clause is about. */
    public double unshieldedFluxPerCell() {
        return unshieldedFluxPerCell;
    }

    /**
     * How much of the incident flux a raised shield takes off, as a fraction strictly below one.
     * <p>
     * A shield is sunscreen and never a wall. The config may ask for anything, including all
     * of it; what comes out of here is clamped, so an enormous generator still leaves a residue and a
     * ship in a star heats slowly rather than not at all. The shield pays for this with its generator's
     * own draw, which is itself a heat source — which is why tanking a star costs twice.
     */
    static double shieldAttenuation() {
        double asked = ARConfiguration.getCurrentConfig().shipHeatShieldAttenuation / 1000.0D;
        return Math.max(0.0D, Math.min(MAX_SHIELD_ATTENUATION, asked));
    }

    /**
     * The flux from being AT a body: the body's own temperature, and nothing else.
     * <p>
     * <b>The star is deliberately NOT added here, and leaving it out is a correction rather than a
     * simplification.</b> A body's {@code averageTemperature} is DERIVED from its star — brightness,
     * distance and atmosphere are its inputs — so a surface at 286 K is already the answer to "what
     * does this star do to this place". Adding insolation on top counts the same star twice, and it
     * showed: a loop standing still on Earth with one radiator drifted upward forever, because the
     * doubled environment beat what the cell could shed at room temperature. What a ship parked on a
     * world is in is that world's environment, and the honest floor for its coolant is that world's
     * temperature.
     * <p>
     * Resolved through {@code getDimensionPropertiesOrNull}, and that choice is load-bearing. The
     * lenient accessor answers an id it does not know with EARTH, so asking it about a slot world would
     * hand a ship in deep space Earth's 286 K — which is exactly the mistake a constant ambient was
     * already making. A {@code null} here means "not a body", and the caller then asks where it really
     * is.
     */
    private static double bodyEnvironmentFlux(World world) {
        DimensionProperties properties = DimensionManager.getInstance()
                .getDimensionPropertiesOrNull(world.provider.getDimension());
        if (properties == null) {
            return 0.0D;
        }
        return HeatNetwork.cellPowerAt(Math.max(0, properties.averageTemperature));
    }

    /**
     * The flux from the star of the CELL a ship is flying in, by where that ship actually is inside it.
     * <p>
     * The block distance to the star becomes an orbital distance through the very scale the universe
     * layer placed the system with ({@link SystemContent#ORBIT_UNIT_BLOCKS}), so the brightness a ship
     * a quarter of the way out reads is the one a planet parked there would have. Inventing a second
     * scale here would let the two drift, and a system whose sunlight disagreed with its own geometry
     * is a bug nothing would catch.
     * <p>
     * No body term: across a cell a planet is millions of blocks away and radiates a vanishing amount at
     * you, so there is nothing to suppress. Interstellar void answers zero on every path, which is what
     * "space is cold" has to mean for the rest of this system to be interesting.
     */
    private static double cellEnvironmentFlux(World world, BlockPos sample) {
        String cellKey = SpaceSlotPool.cellKeyFor(world.provider.getDimension());
        if (cellKey == null) {
            return 0.0D;
        }
        GalacticCoord cell = GalacticCoord.fromCellKey(cellKey);
        UniverseRegistry registry = UniverseRegistry.get(world.getMinecraftServer());
        if (cell == null || registry == null) {
            return 0.0D;
        }
        long tick = SpaceSubsystem.spaceClock();
        // The WITHIN reading, not the plain inverse. A radiator is a BLOCK and blocks sit at ordinary
        // block height, which the pose mapping inverts to a local Y below the cell's own range — so the
        // plain inverse would place the observer a whole cell away in Y, in a sector nobody bound. This
        // is a report of where something is rather than a path being integrated, which is exactly the
        // distinction the mapper draws.
        AbsolutePos observer = registry.absoluteOf(
                CellWorldMapper.coordOfPoseWithin(cell, sample.getX(), sample.getY(), sample.getZ()), tick);
        double flux = 0.0D;
        for (SystemBody body : registry.skyBodiesAt(cell)) {
            if (body == null || body.kind() != SystemBodyKind.STAR) {
                continue;
            }
            StellarBody star = DimensionManager.getInstance().getStar(body.starId());
            if (star == null) {
                continue;
            }
            flux += starFlux(AstronomicalBodyHelper.getStellarBrightness(star, orbitalUnitsTo(observer, body, tick)));
        }
        return flux;
    }

    /** The star's distance in the units every stellar formula in this mod is written in. */
    private static int orbitalUnitsTo(AbsolutePos observer, SystemBody star, long tick) {
        double blocks = observer.distanceTo(star.absoluteAt(tick));
        long units = Math.round(blocks / SystemContent.ORBIT_UNIT_BLOCKS);
        // Never zero: the brightness formula divides by the square of this, and a ship that has flown
        // into the star is a case for star contact rather than for an infinity here.
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, units));
    }

    /**
     * Starlight as a flux on one cell, from its brightness relative to Earth's.
     * <p>
     * The config states the one number that makes this physical: the temperature a cell settles at in
     * unshaded Earth-normal starlight. That is a point on the same curve the radiator itself is quoted
     * on, so the two are directly comparable — a cell facing a sun that would hold it at 278 K cannot
     * be cooled below 278 K by any amount of area, and the arithmetic says so without anyone stating
     * it.
     */
    static double starFlux(double brightnessRelativeToEarth) {
        if (!(brightnessRelativeToEarth > 0.0D) || !Double.isFinite(brightnessRelativeToEarth)) {
            return 0.0D;
        }
        double flux = brightnessRelativeToEarth * HeatNetwork.cellPowerAt(
                Math.max(0, ARConfiguration.getCurrentConfig().shipHeatStarFluxReferenceKelvin));
        return Double.isFinite(flux) ? flux : 0.0D;
    }
}
